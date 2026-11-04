package main

// Tray icon (StatusNotifierItem over D-Bus: KDE, XFCE, Cinnamon, sway/waybar...; GNOME needs the
// AppIndicator extension). Its color is the state: gray stopped, amber waiting, green connected,
// red error. A view on the controller, like the window.

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"sync"
	"sync/atomic"

	"fyne.io/systray"
	"github.com/godbus/dbus/v5"
)

func trayAvailable() bool {
	conn, err := dbus.ConnectSessionBus()
	if err != nil {
		return false
	}
	defer conn.Close()
	var has bool
	conn.BusObject().Call("org.freedesktop.DBus.NameHasOwner", 0, "org.kde.StatusNotifierWatcher").Store(&has)
	return has
}

var stateColor = map[string]color.RGBA{
	"gray":  {0x6B, 0x72, 0x80, 0xFF},
	"amber": {0xFF, 0xB0, 0x20, 0xFF},
	"green": {0x34, 0xC7, 0x59, 0xFF},
	"red":   {0xFF, 0x45, 0x3A, 0xFF},
}

// icon: a phone outline in the state color.
func icon(c color.RGBA) []byte {
	const n = 64
	img := image.NewRGBA(image.Rect(0, 0, n, n))
	in := func(x, y, x0, y0, x1, y1 int) bool { return x >= x0 && x < x1 && y >= y0 && y < y1 }
	for y := 0; y < n; y++ {
		for x := 0; x < n; x++ {
			body := in(x, y, 16, 4, 48, 60)
			screen := in(x, y, 21, 11, 43, 49)
			button := in(x, y, 28, 52, 36, 56)
			if body && !screen && !button {
				img.Set(x, y, c)
			}
		}
	}
	var buf bytes.Buffer
	png.Encode(&buf, img)
	return buf.Bytes()
}

// trayGone: set before quitting the tray; touching it afterwards panics (its D-Bus is closed).
var trayGone atomic.Bool

func quitTray() {
	trayGone.Store(true)
	systray.Quit()
}

// runTray blocks until Quit; open shows the window.
func runTray(c *controller, open func(), onExit func()) {
	var mu sync.Mutex
	lastColor, lastSummary := "", ""
	systray.Run(func() {
		systray.SetIcon(icon(stateColor["gray"]))
		systray.SetTitle("Phone Bridge")
		systray.SetTooltip("Phone Bridge")
		systray.SetOnTapped(open) // left click: the window

		status := systray.AddMenuItem("…", "")
		status.Disable()
		win := systray.AddMenuItem("Open window", "Status, settings, PIN, log")
		systray.AddSeparator()
		run := systray.AddMenuItemCheckbox("Running", "Start / stop the bridge", false)
		links := map[string]*systray.MenuItem{}
		link := systray.AddMenuItem("Link", "How the phone connects")
		for _, l := range []string{"usb", "wifi", "pan", "bt"} {
			links[l] = link.AddSubMenuItemCheckbox(linkLabel(l), "", false)
		}
		out := systray.AddMenuItemCheckbox("PC audio → phone", "", false)
		mic := systray.AddMenuItemCheckbox("Phone mic → PC", "", false)
		pa := systray.AddMenuItemCheckbox("Phone audio → PC", "The phone's own sound (Android 10+)", false)
		systray.AddSeparator()
		pin := systray.AddMenuItem("PIN", "Type it in the app: Connection > PIN")
		pin.Disable()
		systray.AddSeparator()
		quit := systray.AddMenuItem("Quit", "Puts the audio back where it was")

		check := func(it *systray.MenuItem, on bool) {
			if on {
				it.Check()
			} else {
				it.Uncheck()
			}
		}
		refresh := func() {
			if trayGone.Load() {
				return
			}
			defer func() { recover() }() // the tray went away mid-refresh
			s := c.snapshot()
			mu.Lock()
			defer mu.Unlock()
			if s.Color != lastColor {
				systray.SetIcon(icon(stateColor[s.Color]))
				lastColor = s.Color
			}
			if s.Summary != lastSummary {
				status.SetTitle(s.Summary)
				systray.SetTooltip("Phone Bridge: " + s.Summary)
				lastSummary = s.Summary
			}
			check(run, s.Running || s.Starting)
			for l, it := range links {
				check(it, s.Opt.Link == l)
			}
			check(out, s.Opt.Out)
			check(mic, s.Opt.Mic)
			check(pa, s.Opt.PhoneAudio)
			if s.Opt.Link == "usb" {
				pin.SetTitle("PIN " + s.PIN + " (not needed on USB)")
			} else {
				pin.SetTitle("PIN " + s.PIN)
			}
		}
		c.onChange(refresh)
		refresh()
		go func() { // the streams come and go without a settings change
			for range tick() {
				if trayGone.Load() {
					return
				}
				refresh()
			}
		}()

		setOpt := func(f func(*options)) {
			s := c.snapshot()
			o := s.Opt
			f(&o)
			go c.apply(o)
		}
		go func() {
			for {
				select {
				case <-win.ClickedCh:
					open()
				case <-run.ClickedCh:
					if s := c.snapshot(); s.Running || s.Starting {
						go c.stop()
					} else {
						go c.restart()
					}
				case <-links["usb"].ClickedCh:
					setOpt(func(o *options) { o.Link = "usb" })
				case <-links["wifi"].ClickedCh:
					setOpt(func(o *options) { o.Link = "wifi" })
				case <-links["pan"].ClickedCh:
					setOpt(func(o *options) { o.Link = "pan" })
				case <-links["bt"].ClickedCh:
					setOpt(func(o *options) { o.Link = "bt" })
				case <-out.ClickedCh:
					setOpt(func(o *options) { o.Out = !o.Out })
				case <-mic.ClickedCh:
					setOpt(func(o *options) { o.Mic = !o.Mic })
				case <-pa.ClickedCh:
					setOpt(func(o *options) { o.PhoneAudio = !o.PhoneAudio })
				case <-quit.ClickedCh:
					quitTray()
					return
				}
			}
		}()
	}, onExit)
}
