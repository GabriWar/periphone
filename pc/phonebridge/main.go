// phonebridge: the PC side of USB Audio Bridge in one static binary.
//
// Use an Android phone as the PC's sound card, microphone and webcam over USB, Wi-Fi or
// Bluetooth, and play the phone's own sound on the PC. Runs as a tray icon (or in the terminal
// with --no-tray). Needs a sound server (PulseAudio or PipeWire); adb for USB, ffmpeg and
// v4l2loopback for the camera.
package main

import (
	"crypto/rand"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"math/big"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

const usage = `phonebridge: use an Android phone as this PC's speaker, mic and webcam.

usage:
  phonebridge [options]           window + tray icon (running again reopens the window)
  phonebridge --background        tray only (the window opens from the tray)
  phonebridge --no-tray [options] in the terminal, Ctrl-C to stop
  phonebridge pin [new]           the PIN for Wi-Fi / Bluetooth (new: change it)
  phonebridge install [--autostart]   copy to ~/.local/bin, add to the app menu (and login)
  phonebridge uninstall
  phonebridge stop                stop the running one

options (the window/tray remember their choices; options here change them):
  --usb | --wifi | --pan | --bt   link: USB cable (default), Wi-Fi, Bluetooth tethering,
                                  Bluetooth direct
  --pan-mac MAC     phone to join for --pan (default: the only paired device)
  -a                also play the phone's own sound on the PC ("Phone audio" device)
  -c front|back     start the phone camera as a webcam
  --no-mic          don't use the phone mic
  --no-out          don't send PC audio to the phone
  -b MS             output buffer on the phone, 10..200 ms
  -m N              mic mode: 1 standard, 7 voice call (echo cancelling), 9 raw, 6, 5
  --codec auto|pcm|opus   Wi-Fi / Bluetooth audio codec (auto = opus)
  --no-encrypt      don't encrypt Wi-Fi / Bluetooth streams (the PIN is still checked)
`

var logMu sync.Mutex
var logHook func(string)

func logf(format string, a ...any) {
	msg := fmt.Sprintf(format, a...)
	logMu.Lock()
	fmt.Printf("[%s] %s\n", time.Now().Format("15:04:05"), msg)
	h := logHook
	logMu.Unlock()
	if h != nil {
		h(msg)
	}
}

func configDir() string {
	d := os.Getenv("XDG_CONFIG_HOME")
	if d == "" {
		d = filepath.Join(os.Getenv("HOME"), ".config")
	}
	return filepath.Join(d, "phone-bridge")
}

func runtimeDir() string {
	if d := os.Getenv("XDG_RUNTIME_DIR"); d != "" {
		return d
	}
	return os.TempDir()
}

// loadPIN reads (or creates) the 6-digit PIN the app must know.
func loadPIN(renew bool) (string, error) {
	p := filepath.Join(configDir(), "pin")
	if !renew {
		if b, err := os.ReadFile(p); err == nil && strings.TrimSpace(string(b)) != "" {
			return strings.TrimSpace(string(b)), nil
		}
	}
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		return "", err
	}
	pin := fmt.Sprintf("%06d", n.Int64())
	if err := os.MkdirAll(configDir(), 0o700); err != nil {
		return "", err
	}
	return pin, os.WriteFile(p, []byte(pin+"\n"), 0o600)
}

func settingsPath() string { return filepath.Join(configDir(), "settings.json") }

func loadSettings() options {
	o := defaultOptions()
	if b, err := os.ReadFile(settingsPath()); err == nil {
		json.Unmarshal(b, &o)
	}
	return o
}

func saveSettings(o options) {
	os.MkdirAll(configDir(), 0o700)
	b, _ := json.MarshalIndent(o, "", "  ")
	os.WriteFile(settingsPath(), b, 0o600)
}

// lock: one bridge at a time (they'd fight over the ports and the default devices).
func lock() (*os.File, error) {
	f, err := os.OpenFile(filepath.Join(runtimeDir(), "phonebridge.lock"), os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil {
		return nil, err
	}
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		b, _ := io.ReadAll(f)
		f.Close()
		return nil, fmt.Errorf("phonebridge is already running (pid %s); stop it with: phonebridge stop",
			strings.TrimSpace(string(b)))
	}
	f.Truncate(0)
	f.WriteAt([]byte(strconv.Itoa(os.Getpid())+"\n"), 0)
	return f, nil
}

func stopRunning() {
	b, err := os.ReadFile(filepath.Join(runtimeDir(), "phonebridge.lock"))
	pid, _ := strconv.Atoi(strings.TrimSpace(string(b)))
	if err != nil || pid <= 0 || syscall.Kill(pid, syscall.SIGTERM) != nil {
		fmt.Println("no phonebridge running.")
		return
	}
	fmt.Println("phonebridge stopped.")
}

func main() {
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "pin":
			pin, err := loadPIN(len(os.Args) > 2 && os.Args[2] == "new")
			if err != nil {
				fail(err)
			}
			fmt.Printf("PIN: %s   (type it in the app: Connection > PIN)\n", pin)
			return
		case "install":
			install(len(os.Args) > 2 && os.Args[2] == "--autostart")
			return
		case "uninstall":
			uninstall()
			return
		case "stop":
			stopRunning()
			return
		}
	}

	fs := flag.NewFlagSet("phonebridge", flag.ExitOnError)
	fs.Usage = func() { fmt.Print(usage) }
	usb := fs.Bool("usb", false, "")
	wifi := fs.Bool("wifi", false, "")
	pan := fs.Bool("pan", false, "")
	bt := fs.Bool("bt", false, "")
	panMAC := fs.String("pan-mac", "", "")
	phoneAudio := fs.Bool("a", false, "")
	cam := fs.String("c", "", "")
	noMic := fs.Bool("no-mic", false, "")
	noOut := fs.Bool("no-out", false, "")
	buf := fs.Int("b", 0, "")
	micMode := fs.Int("m", 0, "")
	codec := fs.String("codec", "", "")
	noEnc := fs.Bool("no-encrypt", false, "")
	noTray := fs.Bool("no-tray", false, "")
	background := fs.Bool("background", false, "")
	fs.Parse(os.Args[1:])
	if fs.NArg() > 0 {
		fail(fmt.Errorf("unknown argument %q (see --help)", fs.Arg(0)))
	}

	opt := loadSettings()
	set := map[string]bool{}
	fs.Visit(func(f *flag.Flag) { set[f.Name] = true })
	for _, l := range []struct {
		name string
		on   bool
	}{{"usb", *usb}, {"wifi", *wifi}, {"pan", *pan}, {"bt", *bt}} {
		if l.on {
			opt.Link = l.name
		}
	}
	if set["pan-mac"] {
		opt.PanMAC = *panMAC
	}
	if set["a"] {
		opt.PhoneAudio = *phoneAudio
	}
	if set["c"] {
		if *cam != "front" && *cam != "back" {
			fail(fmt.Errorf("-c: front or back"))
		}
		opt.CamFacing = *cam
	}
	if set["no-mic"] && *noMic {
		opt.Mic = false
	}
	if set["no-out"] && *noOut {
		opt.Out = false
	}
	if set["b"] && (*buf < 10 || *buf > 200) {
		fail(fmt.Errorf("-b: 10..200 ms"))
	}
	if set["b"] {
		opt.Buffer = *buf
	}
	if set["m"] {
		opt.MicMode = *micMode
	}
	if set["codec"] {
		if *codec != "auto" && *codec != "pcm" && *codec != "opus" {
			fail(fmt.Errorf("--codec: auto, pcm or opus"))
		}
		opt.Codec = *codec
	}
	if set["no-encrypt"] && *noEnc {
		opt.Encrypt = false
	}

	lk, err := lock()
	if err != nil {
		if !*noTray && openRunningWindow() {
			fmt.Println("phonebridge is already running: opened its window.")
			return
		}
		fail(err)
	}
	defer lk.Close()

	if *noTray {
		runTerminal(opt)
		return
	}
	if len(set) > 0 {
		saveSettings(opt) // options given here become the window's settings too
	}
	runGUI(opt, !*background)
}

// runGUI: the window (web UI) and, if the desktop has one, the tray icon.
func runGUI(opt options, showWindow bool) {
	c := newController(opt)
	quit := make(chan struct{})
	var once sync.Once
	tray := trayAvailable()
	stop := func() {
		once.Do(func() {
			if tray {
				quitTray()
			} else {
				close(quit)
			}
		})
	}
	url, err := serveWeb(c, stop)
	if err != nil {
		fail(err)
	}
	defer os.Remove(urlFile())
	open := func() { openWindow(url) }
	logf("window: %s", url)
	go c.restart()
	if showWindow || !tray {
		open()
	}
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	go func() { <-sig; stop() }()
	if tray {
		runTray(c, open, func() {})
	} else {
		logf("no system tray here: the window is the way in (its Quit button stops the bridge)")
		<-quit
	}
	logf("putting the audio back and closing the app...")
	c.stop()
}

func tick() <-chan time.Time { return time.Tick(time.Second) }

func fail(err error) {
	fmt.Fprintln(os.Stderr, "error:", err)
	os.Exit(1)
}

func runTerminal(opt options) {
	b, err := startBridge(opt)
	if err != nil {
		fail(err)
	}
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	last := ""
	t := time.NewTicker(2 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-sig:
			fmt.Println()
			logf("putting the audio back and closing the app...")
			b.close()
			return
		case <-t.C:
			if s := b.statusLine(); s != last {
				logf("%s", s)
				last = s
			}
		}
	}
}

func (b *bridge) statusLine() string {
	on := func(r byte, want bool) string {
		switch {
		case !want:
			return "off"
		case b.isOn(r):
			return "on"
		}
		return "waiting"
	}
	s := fmt.Sprintf("%s: output=%s mic=%s phone-audio=%s", linkLabel(b.opt.Link),
		on(roleSpk, b.opt.Out), on(roleMic, b.opt.Mic), on(roleAudio, b.opt.PhoneAudio))
	if b.cam != nil {
		s += " camera=" + on(roleCam, true)
	}
	return s
}

// ---------------------------------------------------------------- install

func home(p ...string) string { return filepath.Join(append([]string{os.Getenv("HOME")}, p...)...) }

const desktopEntry = `[Desktop Entry]
Type=Application
Name=Phone Bridge
Comment=Use the phone as speaker, mic and webcam (USB Audio Bridge)
Exec=%s
Icon=audio-card
Terminal=false
Categories=AudioVideo;Audio;
`

func install(autostart bool) {
	exe, err := os.Executable()
	if err != nil {
		fail(err)
	}
	dst := home(".local", "bin", "phonebridge")
	if exe != dst {
		os.MkdirAll(filepath.Dir(dst), 0o755)
		in, err := os.ReadFile(exe)
		if err != nil {
			fail(err)
		}
		tmp := dst + ".new"
		if err := os.WriteFile(tmp, in, 0o755); err != nil {
			fail(err)
		}
		if err := os.Rename(tmp, dst); err != nil { // atomic: a running copy keeps working
			fail(err)
		}
	}
	fmt.Println("  ok   ", dst)
	app := home(".local", "share", "applications", "phonebridge.desktop")
	os.MkdirAll(filepath.Dir(app), 0o755)
	os.WriteFile(app, []byte(fmt.Sprintf(desktopEntry, dst)), 0o644)
	fmt.Println("  ok   ", app, "(app menu)")
	if autostart {
		as := home(".config", "autostart", "phonebridge.desktop")
		os.MkdirAll(filepath.Dir(as), 0o755)
		os.WriteFile(as, []byte(fmt.Sprintf(desktopEntry, dst+" --background")), 0o644)
		fmt.Println("  ok   ", as, "(starts at login)")
	}

	// the old shell/python version: its PipeWire configs grab the same names and ports
	restart := false
	for _, f := range []string{"20-phone-mic.conf", "21-phone-speaker.conf", "22-phone-mic-server.conf"} {
		p := home(".config", "pipewire", "pipewire.conf.d", f)
		if os.Remove(p) == nil {
			fmt.Println("  removed old", p)
			restart = true
		}
	}
	for _, f := range []string{"phone-bridge", "phone-relay", "phone-cam", "phone-mic", "phone-speaker"} {
		if os.Remove(home(".local", "bin", f)) == nil {
			fmt.Println("  removed old", home(".local", "bin", f))
		}
	}
	if restart {
		exec.Command("systemctl", "--user", "restart", "pipewire", "pipewire-pulse", "wireplumber").Run()
		fmt.Println("  ok    PipeWire restarted without the old configs")
	}

	fmt.Println("== what's available here")
	check := func(ok bool, yes, no string) {
		if ok {
			fmt.Println("  ok   ", yes)
		} else {
			fmt.Println("  --   ", no)
		}
	}
	_, e1 := exec.LookPath("adb")
	check(e1 == nil, "adb (USB, starting the app)", "adb missing: USB won't work (install android-tools)")
	_, e2 := exec.LookPath("ffmpeg")
	check(e2 == nil && findLoopback() != "", "camera (ffmpeg + v4l2loopback)",
		`camera off: needs ffmpeg and v4l2loopback (sudo modprobe v4l2loopback exclusive_caps=1 card_label="Phone Camera")`)
	check(trayAvailable(), "system tray", "no system tray right now (GNOME: AppIndicator extension); the window works anyway")
	fmt.Printf("\nDone. Run: phonebridge   (or open \"Phone Bridge\" from the app menu)\n")
}

func uninstall() {
	for _, p := range []string{home(".local", "bin", "phonebridge"),
		home(".local", "share", "applications", "phonebridge.desktop"),
		home(".config", "autostart", "phonebridge.desktop")} {
		if os.Remove(p) == nil {
			fmt.Println("  removed", p)
		}
	}
	fmt.Println("settings and PIN kept in", configDir())
}
