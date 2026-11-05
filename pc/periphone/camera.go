package main

// Phone camera -> v4l2loopback webcam. The stream (see CameraPipe.java): packets
// [type u8][length u32][payload]; CONFIG (1) = width u16, height u16, fps u16, rotation u16,
// flags u8 (bit0 mirror) and starts a new H.264 stream; DATA (2) = Annex-B H.264.
// ffmpeg decodes; the webcam device stays open the whole session (black frames while idle), so
// apps don't lose the camera when the phone switches cameras or rotates.

import (
	"encoding/binary"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

type camera struct {
	device string
	mu     sync.Mutex
	sink   *camSink
}

// findLoopback: the first v4l2loopback device, or "".
func findLoopback() string {
	l, _ := filepath.Glob("/sys/devices/virtual/video4linux/video*")
	sort.Strings(l)
	for _, p := range l {
		d := "/dev/" + filepath.Base(p)
		if _, err := os.Stat(d); err == nil {
			return d
		}
	}
	return ""
}

func newCamera() (*camera, string) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		return nil, "ffmpeg isn't installed: " + installHint("ffmpeg")
	}
	d := findLoopback()
	if d == "" {
		if !moduleInstalled() {
			return nil, "the webcam module (v4l2loopback) isn't installed: " + installHint("v4l2loopback")
		}
		return nil, "the webcam module isn't loaded: use \"Enable the camera\" (asks for your password once)"
	}
	return &camera{device: d}, ""
}

func (c *camera) close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.sink != nil {
		c.sink.close()
		c.sink = nil
	}
}

// serve reads one phone connection's packets until it ends.
func (c *camera) serve(r io.Reader) {
	defer io.Copy(io.Discard, r)
	var dec *camDecoder
	defer func() {
		if dec != nil {
			dec.close()
		}
	}()
	var hdr [5]byte
	for {
		if _, err := io.ReadFull(r, hdr[:]); err != nil {
			return
		}
		n := binary.BigEndian.Uint32(hdr[1:])
		if n > 8<<20 {
			logf("camera: packet too big, dropping the connection")
			return
		}
		p := make([]byte, n)
		if _, err := io.ReadFull(r, p); err != nil {
			return
		}
		switch hdr[0] {
		case 1:
			if len(p) < 9 {
				return
			}
			w, h := int(binary.BigEndian.Uint16(p)), int(binary.BigEndian.Uint16(p[2:]))
			fps, rot := int(binary.BigEndian.Uint16(p[4:])), int(binary.BigEndian.Uint16(p[6:]))
			mirror := p[8]&1 != 0
			if w < 16 || w > 1920 || h < 16 || h > 1920 || fps < 1 || fps > 120 || rot%90 != 0 || rot > 270 {
				logf("camera: bad config %dx%d %d fps rotation %d", w, h, fps, rot)
				return
			}
			rw, rh := w, h
			if rot == 90 || rot == 270 {
				rw, rh = h, w
			}
			sink, err := c.getSink(max(rw, rh), min(rw, rh), fps) // webcams are landscape
			if err != nil {
				logf("camera: %v", err)
				return
			}
			if dec != nil {
				dec.close()
			}
			dec = newCamDecoder(sink, w, h, fps, rot, mirror)
			logf("camera: phone stream %dx%d %d fps, rotation %d", w, h, fps, rot)
		case 2:
			if dec != nil {
				dec.feed(p)
			}
		}
	}
}

func (c *camera) getSink(w, h, fps int) (*camSink, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.sink != nil && !c.sink.alive() {
		c.sink.close()
		c.sink = nil
	}
	if c.sink == nil {
		s, err := newCamSink(c.device, w&^1, h&^1, fps)
		if err != nil {
			return nil, err
		}
		c.sink = s
		logf("camera: webcam %s open at %dx%d", c.device, s.w, s.h)
	}
	return c.sink, nil
}

// camSink: ffmpeg holding the v4l2 device open, fed raw yuv420p frames of a fixed size.
type camSink struct {
	w, h      int
	cmd       *exec.Cmd
	in        io.WriteCloser
	mu        sync.Mutex
	last      time.Time
	dead      bool
	black     []byte
	frameSize int
	stop      chan struct{}
}

func newCamSink(dev string, w, h, fps int) (*camSink, error) {
	cmd := exec.Command("ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
		"-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", fmt.Sprintf("%dx%d", w, h), "-r", fmt.Sprint(fps),
		"-i", "pipe:0", "-fps_mode", "passthrough", "-f", "v4l2", "-pix_fmt", "yuv420p", dev)
	in, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	s := &camSink{w: w, h: h, cmd: cmd, in: in, frameSize: w * h * 3 / 2, stop: make(chan struct{})}
	s.black = make([]byte, s.frameSize)
	for i := range s.black {
		if i < w*h {
			s.black[i] = 16
		} else {
			s.black[i] = 128
		}
	}
	go s.idle()
	return s, nil
}

func (s *camSink) write(frame []byte, real bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.dead {
		return
	}
	if _, err := s.in.Write(frame); err != nil {
		s.dead = true
		select {
		case <-s.stop: // closing: not a failure
		default:
			logf("camera: the webcam writer (ffmpeg) died")
		}
		return
	}
	if real {
		s.last = time.Now()
	}
}

// idle keeps consumers alive with black frames (5 fps) while nothing comes from the phone.
func (s *camSink) idle() {
	t := time.NewTicker(200 * time.Millisecond)
	defer t.Stop()
	for {
		select {
		case <-s.stop:
			return
		case <-t.C:
			s.mu.Lock()
			quiet := time.Since(s.last) > time.Second
			s.mu.Unlock()
			if quiet {
				s.write(s.black, false)
			}
		}
	}
}

func (s *camSink) alive() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return !s.dead
}

func (s *camSink) close() {
	close(s.stop)
	s.in.Close()
	done := make(chan struct{})
	go func() { s.cmd.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(time.Second):
		s.cmd.Process.Kill()
	}
}

// camDecoder: ffmpeg decoding one H.264 stream into frames that fit the sink.
type camDecoder struct {
	cmd *exec.Cmd
	in  io.WriteCloser
}

func newCamDecoder(sink *camSink, w, h, fps, rot int, mirror bool) *camDecoder {
	var vf []string
	switch rot {
	case 90:
		vf = append(vf, "transpose=clock")
	case 180:
		vf = append(vf, "hflip", "vflip")
	case 270:
		vf = append(vf, "transpose=cclock")
	}
	if mirror {
		vf = append(vf, "hflip")
	}
	vf = append(vf, fmt.Sprintf("scale=%d:%d:force_original_aspect_ratio=decrease", sink.w, sink.h),
		fmt.Sprintf("pad=%d:%d:(ow-iw)/2:(oh-ih)/2", sink.w, sink.h), "format=yuv420p")
	filters := vf[0]
	for _, f := range vf[1:] {
		filters += "," + f
	}
	// no probing, no frame threading: a frame goes out as soon as it's decoded (~1 frame behind)
	cmd := exec.Command("ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
		"-flags", "low_delay", "-strict", "experimental", "-probesize", "32", "-analyzeduration", "0",
		"-fpsprobesize", "0", "-f", "h264", "-framerate", fmt.Sprint(fps), "-i", "pipe:0",
		"-vf", filters, "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "yuv420p",
		"-flush_packets", "1", "pipe:1")
	in, _ := cmd.StdinPipe()
	out, _ := cmd.StdoutPipe()
	d := &camDecoder{cmd: cmd, in: in}
	if err := cmd.Start(); err != nil {
		logf("camera: ffmpeg: %v", err)
		return d
	}
	go func() {
		buf := make([]byte, sink.frameSize)
		for {
			if _, err := io.ReadFull(out, buf); err != nil {
				return
			}
			sink.write(buf, true)
		}
	}()
	return d
}

func (d *camDecoder) feed(p []byte) { d.in.Write(p) }

func (d *camDecoder) close() {
	d.in.Close()
	if d.cmd.Process == nil {
		return
	}
	done := make(chan struct{})
	go func() { d.cmd.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(time.Second):
		d.cmd.Process.Kill()
	}
}
