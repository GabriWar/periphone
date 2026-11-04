package main

// Sound server side, over the PulseAudio native protocol (PulseAudio or PipeWire's pipewire-pulse).
// The virtual devices are created at runtime and removed on exit; nothing to install.
//   phone_speaker  sink apps play into; its monitor is recorded and sent to the phone
//   phone_mic      microphone apps see (a remap of the hidden phone_mic_in sink, fed by the phone)
//   phone_audio    the phone's own sound: a device of its own (apps can record "Phone audio"),
//                  also played on the speakers that were the default before so you hear it

import (
	"encoding/binary"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/jfreymuth/pulse"
	"github.com/jfreymuth/pulse/proto"
)

const (
	spkSink   = "phone_speaker"
	micSink   = "phone_mic_in"
	micSource = "phone_mic"
	audSink   = "phone_audio"
	ourMark   = "phonebridge" // in every module we load, to find leftovers after a crash
	chunkSt   = frames * 4    // 5 ms stereo S16LE
	chunkMono = frames * 2
)

type audio struct {
	c                    *pulse.Client
	mods                 []uint32
	prevSink, prevSource string
	setSink, setSource   bool
	rec                  *pulse.RecordStream
	micPlay, phonePlay   *pulse.PlaybackStream
	mic, phone           *ring
	mu                   sync.Mutex
	subs                 map[chan []byte]struct{}
	acc                  []byte
	spkPeak              peak
}

// peak: recent loudness for the window's meters (0..32767, fades out after 300 ms).
type peak struct {
	v atomic.Int32
	t atomic.Int64
}

func (p *peak) put(v int32) {
	now := time.Now().UnixNano()
	if v >= p.v.Load() || now-p.t.Load() > int64(80*time.Millisecond) {
		p.v.Store(v)
		p.t.Store(now)
	}
}

func (p *peak) get() float64 {
	if time.Now().UnixNano()-p.t.Load() > int64(300*time.Millisecond) {
		return 0
	}
	return float64(p.v.Load()) / 32768
}

// levels: PC audio -> phone, phone mic, phone audio.
func (a *audio) levels() [3]float64 {
	return [3]float64{a.spkPeak.get(), a.mic.pk.get(), a.phone.pk.get()}
}

func peakOf16(buf []int16) int32 {
	var m int32
	for _, v := range buf {
		x := int32(v)
		if x < 0 {
			x = -x
		}
		m = max(m, x)
	}
	return m
}

func openAudio() (*audio, error) {
	c, err := pulse.NewClient(pulse.ClientApplicationName("phonebridge"))
	if err != nil {
		return nil, fmt.Errorf("can't reach the sound server (PulseAudio / PipeWire): %w", err)
	}
	a := &audio{c: c, subs: map[chan []byte]struct{}{}}
	a.unloadLeftovers()
	if _, err := c.SinkByID(spkSink); err == nil { // not ours (those were just unloaded)
		c.Close()
		return nil, fmt.Errorf("a %q device already exists: the old phone-bridge PipeWire configs are "+
			"installed; run once: phonebridge install (removes them)", spkSink)
	}

	var si proto.GetServerInfoReply
	if err := c.RawRequest(&proto.GetServerInfo{}, &si); err != nil {
		c.Close()
		return nil, err
	}
	a.prevSink, a.prevSource = si.DefaultSinkName, si.DefaultSourceName
	if saved, err := os.ReadFile(prevFile()); err == nil { // a run that died: its "before" is the real one
		if f := strings.SplitN(strings.TrimSpace(string(saved)), "\n", 2); len(f) == 2 {
			a.prevSink, a.prevSource = f[0], f[1]
		}
	}
	if a.prevSink == spkSink || a.prevSink == micSink || a.prevSink == audSink || a.prevSink == "" {
		a.prevSink = a.firstRealSink()
	}
	if a.prevSource == micSource || strings.HasPrefix(a.prevSource, spkSink) || strings.HasPrefix(a.prevSource, micSink) {
		a.prevSource = ""
	}

	load := func(name, args string) error {
		var r proto.LoadModuleReply
		if err := c.RawRequest(&proto.LoadModule{Name: name, Args: args}, &r); err != nil {
			return fmt.Errorf("%s: %w", name, err)
		}
		a.mods = append(a.mods, r.ModuleIndex)
		return nil
	}
	steps := []struct{ name, args string }{
		{"module-null-sink", `sink_name=` + spkSink + ` rate=48000 channels=2 channel_map=front-left,front-right ` +
			`sink_properties='device.description="Phone speaker" ` + ourMark + `=1'`},
		{"module-null-sink", `sink_name=` + micSink + ` rate=48000 channels=1 channel_map=mono ` +
			`sink_properties='device.description="Phone mic input (internal)" ` + ourMark + `=1'`},
		{"module-remap-source", `source_name=` + micSource + ` master=` + micSink + `.monitor channels=1 ` +
			`channel_map=mono source_properties='device.description="Phone mic" ` + ourMark + `=1'`},
		{"module-null-sink", `sink_name=` + audSink + ` rate=48000 channels=2 channel_map=front-left,front-right ` +
			`sink_properties='device.description="Phone audio" ` + ourMark + `=1'`},
	}
	if a.prevSink != "" { // hear it too: phone_audio -> the real speakers
		steps = append(steps, struct{ name, args string }{"module-loopback",
			`source=` + audSink + `.monitor sink=` + a.prevSink + ` latency_msec=20 ` +
				`source_dont_move=true sink_dont_move=true ` +
				`sink_input_properties='media.name="Phone audio" ` + ourMark + `=1'`})
	}
	for _, s := range steps {
		if err := load(s.name, s.args); err != nil {
			a.close()
			return nil, err
		}
	}

	spk, err := c.SinkByID(spkSink)
	if err == nil {
		a.rec, err = c.NewRecord(pulse.Int16Writer(a.onSpeaker), pulse.RecordMonitor(spk),
			pulse.RecordSampleRate(48000), pulse.RecordStereo, pulse.RecordLatency(0.005),
			pulse.RecordMediaName("to the phone"))
	}
	if err == nil {
		a.mic = newRing(1)
		var ms *pulse.Sink
		if ms, err = c.SinkByID(micSink); err == nil {
			a.micPlay, err = c.NewPlayback(pulse.Int16Reader(a.mic.read), pulse.PlaybackSink(ms),
				pulse.PlaybackSampleRate(48000), pulse.PlaybackMono, pulse.PlaybackLatency(0.01),
				pulse.PlaybackMediaName("phone mic"))
		}
	}
	if err == nil {
		a.phone = newRing(2)
		var as *pulse.Sink
		if as, err = c.SinkByID(audSink); err == nil {
			a.phonePlay, err = c.NewPlayback(pulse.Int16Reader(a.phone.read), pulse.PlaybackSink(as),
				pulse.PlaybackSampleRate(48000), pulse.PlaybackStereo, pulse.PlaybackLatency(0.01),
				pulse.PlaybackMediaName("phone audio"))
		}
	}
	if err != nil {
		a.close()
		return nil, fmt.Errorf("audio streams: %w", err)
	}
	a.rec.Start()
	a.micPlay.Start()
	a.phonePlay.Start()
	return a, nil
}

// firstRealSink: a sink that isn't ours (the speakers to fall back to).
func (a *audio) firstRealSink() string {
	var l proto.GetSinkInfoListReply
	if a.c.RawRequest(&proto.GetSinkInfoList{}, &l) == nil {
		for _, s := range l {
			if s.SinkName != spkSink && s.SinkName != micSink && s.SinkName != audSink {
				return s.SinkName
			}
		}
	}
	return ""
}

// unloadLeftovers removes our modules from a run that died without cleaning up.
func (a *audio) unloadLeftovers() {
	var l proto.GetModuleInfoListReply
	if a.c.RawRequest(&proto.GetModuleInfoList{}, &l) != nil {
		return
	}
	for _, m := range l {
		if strings.Contains(m.ModuleArgs, ourMark+"=1") {
			a.c.RawRequest(&proto.UnloadModule{ModuleIndex: m.ModuleIndex}, nil)
		}
	}
}

// useAsDefaults makes the phone the system output / input, moving what's playing now.
func (a *audio) useAsDefaults(out, in bool) {
	if out || in { // remembered on disk until we put them back, in case we die before
		os.WriteFile(prevFile(), []byte(a.prevSink+"\n"+a.prevSource+"\n"), 0o600)
	}
	if out {
		a.c.RawRequest(&proto.SetDefaultSink{SinkName: spkSink}, nil)
		a.setSink = true
		a.moveInputs(a.prevSink, spkSink)
	}
	if in {
		a.c.RawRequest(&proto.SetDefaultSource{SourceName: micSource}, nil)
		a.setSource = true
	}
}

// moveInputs moves what plays on sink "from" to "to", except our own streams (the phone-audio
// loopback must stay on the speakers, or the phone's sound would go back to the phone).
func (a *audio) moveInputs(from, to string) {
	var sinks proto.GetSinkInfoListReply
	var inputs proto.GetSinkInputInfoListReply
	if a.c.RawRequest(&proto.GetSinkInfoList{}, &sinks) != nil ||
		a.c.RawRequest(&proto.GetSinkInputInfoList{}, &inputs) != nil {
		return
	}
	idx := map[string]uint32{}
	for _, s := range sinks {
		idx[s.SinkName] = s.SinkIndex
	}
	f, ok1 := idx[from]
	t, ok2 := idx[to]
	if !ok1 || !ok2 {
		return
	}
	ours := map[uint32]bool{}
	for _, m := range a.mods {
		ours[m] = true
	}
	for _, in := range inputs {
		if in.SinkIndex == f && !ours[in.ModuleIndex] {
			a.c.RawRequest(&proto.MoveSinkInput{SinkInputIndex: in.SinkInputIndex, DeviceIndex: t}, nil)
		}
	}
}

func (a *audio) close() {
	if a.setSink && a.prevSink != "" {
		a.c.RawRequest(&proto.SetDefaultSink{SinkName: a.prevSink}, nil)
		a.moveInputs(spkSink, a.prevSink)
	}
	if a.setSource && a.prevSource != "" {
		a.c.RawRequest(&proto.SetDefaultSource{SourceName: a.prevSource}, nil)
	}
	if a.rec != nil {
		a.rec.Close()
	}
	if a.micPlay != nil {
		a.micPlay.Close()
	}
	if a.phonePlay != nil {
		a.phonePlay.Close()
	}
	for i := len(a.mods) - 1; i >= 0; i-- {
		a.c.RawRequest(&proto.UnloadModule{ModuleIndex: a.mods[i]}, nil)
	}
	a.c.Close()
	os.Remove(prevFile())
}

func prevFile() string { return filepath.Join(runtimeDir(), "phonebridge.prev") }

// ---- PC audio -> phone: 5 ms chunks fanned out to whoever is connected

func (a *audio) onSpeaker(buf []int16) (int, error) {
	a.spkPeak.put(peakOf16(buf))
	a.mu.Lock()
	defer a.mu.Unlock()
	for _, v := range buf {
		a.acc = binary.LittleEndian.AppendUint16(a.acc, uint16(v))
	}
	for len(a.acc) >= chunkSt {
		chunk := append([]byte(nil), a.acc[:chunkSt]...)
		a.acc = a.acc[chunkSt:]
		for ch := range a.subs {
			select {
			case ch <- chunk:
			default: // that consumer is stuck: drop rather than block the sound server
			}
		}
	}
	if cap(a.acc) > 64*chunkSt {
		a.acc = append([]byte(nil), a.acc...)
	}
	return len(buf), nil
}

func (a *audio) subscribe() chan []byte {
	ch := make(chan []byte, 64)
	a.mu.Lock()
	a.subs[ch] = struct{}{}
	a.mu.Unlock()
	return ch
}

func (a *audio) unsubscribe(ch chan []byte) {
	a.mu.Lock()
	delete(a.subs, ch)
	a.mu.Unlock()
}

// ---- phone -> PC: a small queue the sound server pulls from

type ring struct {
	mu       sync.Mutex
	buf      []int16
	channels int
	pk       peak
}

func newRing(channels int) *ring { return &ring{channels: channels} }

const ringTarget, ringMax = 48 * 20, 48 * 80 // frames: keep ~20 ms, never more than 80

// write adds S16LE PCM from the phone.
func (r *ring) write(pcm []byte) {
	r.mu.Lock()
	n := len(r.buf)
	for i := 0; i+1 < len(pcm); i += 2 {
		r.buf = append(r.buf, int16(binary.LittleEndian.Uint16(pcm[i:])))
	}
	r.pk.put(peakOf16(r.buf[n:]))
	if over := len(r.buf) - ringMax*r.channels; over > 0 { // a burst: cut the delay back
		drop := len(r.buf) - ringTarget*r.channels
		r.buf = append(r.buf[:0], r.buf[drop:]...)
	}
	r.mu.Unlock()
}

// read fills what the sound server asks for; silence when the phone is late.
func (r *ring) read(out []int16) (int, error) {
	r.mu.Lock()
	n := copy(out, r.buf)
	r.buf = append(r.buf[:0], r.buf[n:]...)
	r.mu.Unlock()
	for i := n; i < len(out); i++ {
		out[i] = 0
	}
	return len(out), nil
}

func (r *ring) reset() {
	r.mu.Lock()
	r.buf = r.buf[:0]
	r.mu.Unlock()
}
