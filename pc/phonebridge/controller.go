package main

// controller owns the running bridge and the settings; the tray and the window are views on it.

import (
	"strings"
	"sync"
	"time"
)

type logLine struct {
	T   string `json:"t"`
	Msg string `json:"msg"`
}

type controller struct {
	mu       sync.Mutex
	opt      options
	b        *bridge
	err      string
	starting bool
	logs     []logLine
	changed  []func() // views to refresh after a change
}

func newController(opt options) *controller {
	c := &controller{opt: opt}
	logHook = func(msg string) {
		c.mu.Lock()
		c.logs = append(c.logs, logLine{time.Now().Format("15:04:05"), msg})
		if len(c.logs) > 200 {
			c.logs = c.logs[len(c.logs)-200:]
		}
		c.mu.Unlock()
	}
	return c
}

func (c *controller) onChange(f func()) {
	c.mu.Lock()
	c.changed = append(c.changed, f)
	c.mu.Unlock()
}

func (c *controller) notify() {
	c.mu.Lock()
	fs := append([]func(){}, c.changed...)
	c.mu.Unlock()
	for _, f := range fs {
		f()
	}
}

// apply saves the settings and restarts the bridge with them (and the app on the phone).
func (c *controller) apply(opt options) {
	c.mu.Lock()
	c.opt = opt
	c.mu.Unlock()
	saveSettings(opt)
	c.restart()
}

func (c *controller) restart() {
	c.stop()
	c.mu.Lock()
	opt := c.opt
	c.starting, c.err = true, ""
	c.mu.Unlock()
	c.notify()
	b, err := startBridge(opt)
	c.mu.Lock()
	c.b, c.starting = b, false
	if err != nil {
		c.err = err.Error()
	}
	c.mu.Unlock()
	if err != nil {
		logf("error: %v", err)
	}
	c.notify()
}

func (c *controller) stop() {
	c.mu.Lock()
	b := c.b
	c.b = nil
	c.mu.Unlock()
	if b != nil {
		b.close()
	}
	c.notify()
}

func (c *controller) newPIN() {
	if _, err := loadPIN(true); err == nil {
		logf("new PIN: the phone needs it before it can connect again")
		c.mu.Lock()
		running := c.b != nil
		c.mu.Unlock()
		if running {
			c.restart()
		}
	}
}

// ---- a snapshot for the views

type streamState struct {
	Want  bool    `json:"want"`
	On    bool    `json:"on"`
	Level float64 `json:"level"` // 0..1, recent peak
}

type state struct {
	Running  bool                   `json:"running"`
	Starting bool                   `json:"starting"`
	Error    string                 `json:"error"`
	Opt      options                `json:"opt"`
	Streams  map[string]streamState `json:"streams"`
	CamWhy   string                 `json:"camWhy"`
	PIN      string                 `json:"pin"`
	Phone    string                 `json:"phone"` // adb serial, "" = none
	Summary  string                 `json:"summary"`
	Color    string                 `json:"color"` // gray amber green red
	Logs     []logLine              `json:"logs"`
}

func (c *controller) snapshot() state {
	c.mu.Lock()
	defer c.mu.Unlock()
	pin, _ := loadPIN(false)
	s := state{Running: c.b != nil, Starting: c.starting, Error: c.err, Opt: c.opt, PIN: pin,
		Streams: map[string]streamState{}, Logs: append([]logLine{}, c.logs...)}
	b := c.b
	wanted, live := 0, 0
	add := func(name string, role byte, want bool, lvl float64) {
		st := streamState{Want: want}
		if b != nil {
			st.On = b.isOn(role)
			if st.On {
				st.Level = lvl
			}
		}
		if want {
			wanted++
			if st.On {
				live++
			}
		}
		s.Streams[name] = st
	}
	var lv [3]float64
	if b != nil && b.au != nil {
		lv = b.au.levels()
		s.Phone = ""
		if b.adb != nil {
			s.Phone = b.adb.serial
		}
		s.CamWhy = b.camWhy
	}
	add("out", roleSpk, c.opt.Out, lv[0])
	add("mic", roleMic, c.opt.Mic, lv[1])
	add("phoneAudio", roleAudio, c.opt.PhoneAudio, lv[2])
	add("camera", roleCam, b != nil && b.cam != nil, 0)
	if cs := s.Streams["camera"]; cs.Want && !cs.On {
		cs.Want = false // the camera is on demand: waiting isn't a problem
		s.Streams["camera"] = cs
	}
	link := linkLabel(c.opt.Link)
	switch {
	case c.err != "":
		s.Color, s.Summary = "red", firstLine(c.err)
	case c.starting:
		s.Color, s.Summary = "amber", "Starting "+link+"…"
	case b == nil:
		s.Color, s.Summary = "gray", "Stopped"
	case wanted > 0 && live == wanted:
		s.Color, s.Summary = "green", "Connected over "+link
	case live > 0:
		s.Color, s.Summary = "amber", "Partly connected over "+link
	default:
		s.Color, s.Summary = "amber", "Waiting for the phone ("+link+")"
	}
	return s
}

func firstLine(s string) string {
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		return s[:i]
	}
	return s
}
