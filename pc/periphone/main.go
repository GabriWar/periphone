// periphone: the PC side of Periphone in one static binary.
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

const usage = `periphone: use an Android phone as this PC's speaker, mic and webcam.

usage:
  periphone [options]           window + tray icon (running again reopens the window)
  periphone --background        tray only (the window opens from the tray)
  periphone --no-tray [options] in the terminal, Ctrl-C to stop
  (the window/tray modes detach: closing the terminal or the window keeps the bridge running;
   Quit in the window/tray or "periphone stop" ends it)
  periphone pin [new]           the PIN for Wi-Fi / Bluetooth (new: change it)
  periphone install [--autostart]   copy to ~/.local/bin, add to the app menu (and login)
  periphone uninstall
  periphone stop                stop the running one

options (for this run; what you change in the window or tray is remembered):
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
	dir := filepath.Join(d, "periphone")
	if _, err := os.Stat(dir); os.IsNotExist(err) { // before the rename: keep the PIN and settings
		os.Rename(filepath.Join(d, "phone-bridge"), dir)
	}
	return dir
}

// runtimeDir: lock / state files. XDG_RUNTIME_DIR is private to the user; without it a 0700
// directory of our own (never the shared /tmp: other users could plant files or symlinks there).
func runtimeDir() string {
	if d := os.Getenv("XDG_RUNTIME_DIR"); d != "" {
		return d
	}
	d, err := os.UserCacheDir()
	if err != nil {
		d = filepath.Join(os.Getenv("HOME"), ".cache")
	}
	d = filepath.Join(d, "periphone")
	os.MkdirAll(d, 0o700)
	os.Chmod(d, 0o700)
	return d
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
	f, err := os.OpenFile(filepath.Join(runtimeDir(), "periphone.lock"), os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil {
		return nil, err
	}
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		b, _ := io.ReadAll(f)
		f.Close()
		return nil, fmt.Errorf("periphone is already running (pid %s); stop it with: periphone stop",
			strings.TrimSpace(string(b)))
	}
	f.Truncate(0)
	f.WriteAt([]byte(strconv.Itoa(os.Getpid())+"\n"), 0)
	return f, nil
}

// signalRunning sends sig to the running periphone (pid in the lock file).
// signalRunning sends sig to the running periphone: the pid in the lock file, and only if that
// process really is periphone (a stale pid may belong to another program by now).
func signalRunning(sig syscall.Signal) bool {
	b, err := os.ReadFile(filepath.Join(runtimeDir(), "periphone.lock"))
	pid, _ := strconv.Atoi(strings.TrimSpace(string(b)))
	if err != nil || pid <= 0 || pid == os.Getpid() {
		return false
	}
	comm, _ := os.ReadFile(fmt.Sprintf("/proc/%d/comm", pid))
	if strings.TrimSpace(string(comm)) != "periphone" {
		return false
	}
	return syscall.Kill(pid, sig) == nil
}

func stopRunning() {
	if !signalRunning(syscall.SIGTERM) {
		fmt.Println("no periphone running.")
		return
	}
	fmt.Println("periphone stopped.")
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

	fs := flag.NewFlagSet("periphone", flag.ExitOnError)
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

	// From here a SIGUSR1 ("show the window", sent by another launch) never kills us.
	signal.Notify(showReq, syscall.SIGUSR1)
	detached := os.Getenv("PERIPHONE_DETACHED") != ""

	if !*noTray && !detached { // the launcher: show the running one, or start one in the background
		if signalRunning(syscall.SIGUSR1) {
			fmt.Println("periphone is already running: opened its window.")
			return
		}
		detach(os.Args[1:]) // same options, for this run (they aren't saved)
		return
	}
	lk, err := lock()
	if err != nil {
		if detached { // another launch won the race: it is the one running
			return
		}
		fail(err)
	}
	defer lk.Close()
	if *noTray {
		runTerminal(opt)
		return
	}
	runGUI(opt, !*background)
}

// showReq: SIGUSR1 from another launch (registered first thing, so it can't kill us).
var showReq = make(chan os.Signal, 1)

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
	open, err := serveWeb(c, stop)
	if err != nil {
		fail(err)
	}
	go func() { // another "periphone" launch: show the window
		for range showReq {
			open()
		}
	}()
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

// detach starts this same program again in its own session (closing the terminal won't stop
// it), output to a log file, and returns at once.
func detach(args []string) {
	exe, err := os.Executable()
	if err != nil {
		fail(err)
	}
	logPath := filepath.Join(runtimeDir(), "periphone.log")
	lf, err := os.OpenFile(logPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		fail(err)
	}
	defer lf.Close()
	cmd := exec.Command(exe, args...)
	cmd.Env = append(os.Environ(), "PERIPHONE_DETACHED=1")
	cmd.Stdout, cmd.Stderr = lf, lf
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := cmd.Start(); err != nil {
		fail(err)
	}
	cmd.Process.Release()
	fmt.Println("periphone is running in the background (tray icon; closing the window keeps it running).")
	fmt.Println("  window again: periphone    stop: periphone stop    log:", logPath)
}

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
	if cam, _ := b.getCam(); cam != nil {
		s += " camera=" + on(roleCam, true)
	}
	return s
}

// ---------------------------------------------------------------- install

func home(p ...string) string { return filepath.Join(append([]string{os.Getenv("HOME")}, p...)...) }

// appIcon: the app's launcher icon (res/drawable/ic_fg.xml on the gradient), for the app menu.
const appIcon = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#6E9BFF"/><stop offset="1" stop-color="#3157D6"/></linearGradient></defs>
<rect x="6" y="6" width="96" height="96" rx="24" fill="url(#g)"/>
<g fill="none" stroke="#fff" stroke-width="3.5" stroke-linecap="round">
<path d="M47.5,33h13a4.5,4.5 0 0,1 4.5,4.5v33a4.5,4.5 0 0,1 -4.5,4.5h-13a4.5,4.5 0 0,1 -4.5,-4.5v-33a4.5,4.5 0 0,1 4.5,-4.5z"/>
<path d="M71,47a9,9 0 0,1 0,14"/><path d="M77,41.5a17,17 0 0,1 0,25"/><path d="M37,47a9,9 0 0,0 0,14"/><path d="M31,41.5a17,17 0 0,0 0,25"/></g>
<path fill="#fff" d="M50.5,68.5h7a1.5,1.5 0 0,1 0,3h-7a1.5,1.5 0 0,1 0,-3z"/></svg>
`

func iconPath() string {
	return home(".local", "share", "icons", "hicolor", "scalable", "apps", "periphone.svg")
}

const desktopEntry = `[Desktop Entry]
Type=Application
Name=Periphone
Comment=Use the phone as speaker, mic and webcam (Periphone)
Exec=%s
Icon=periphone
Terminal=false
Categories=AudioVideo;Audio;
`

func install(autostart bool) {
	exe, err := os.Executable()
	if err != nil {
		fail(err)
	}
	dst := home(".local", "bin", "periphone")
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
	for _, p := range []string{home(".local", "share", "applications", "phonebridge.desktop"),
		home(".config", "autostart", "phonebridge.desktop")} {
		if os.Remove(p) == nil {
			fmt.Println("  removed old", p)
		}
	}
	os.MkdirAll(filepath.Dir(iconPath()), 0o755)
	os.WriteFile(iconPath(), []byte(appIcon), 0o644)
	app := home(".local", "share", "applications", "periphone.desktop")
	os.MkdirAll(filepath.Dir(app), 0o755)
	os.WriteFile(app, []byte(fmt.Sprintf(desktopEntry, dst)), 0o644)
	fmt.Println("  ok   ", app, "(app menu)")
	if autostart {
		as := home(".config", "autostart", "periphone.desktop")
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
	// earlier versions: the shell/python scripts, then "phonebridge"
	for _, f := range []string{"phone-bridge", "phonebridge", "phone-relay", "phone-cam", "phone-mic", "phone-speaker"} {
		if os.Remove(home(".local", "bin", f)) == nil {
			fmt.Println("  removed old", home(".local", "bin", f))
		}
	}
	if restart {
		exec.Command("systemctl", "--user", "restart", "pipewire", "pipewire-pulse", "wireplumber").Run()
		fmt.Println("  ok    PipeWire restarted without the old configs")
	}

	// tiling compositors don't run ~/.config/autostart by themselves
	wm := strings.ToLower(os.Getenv("XDG_CURRENT_DESKTOP"))
	for _, w := range []struct{ name, line string }{
		{"hyprland", "exec-once = periphone --background   (in ~/.config/hypr/hyprland.conf)"},
		{"sway", "exec periphone --background   (in ~/.config/sway/config)"},
		{"i3", "exec --no-startup-id periphone --background   (in ~/.config/i3/config)"},
		{"niri", `spawn-at-startup "periphone" "--background"   (in ~/.config/niri/config.kdl)`},
		{"river", "riverctl spawn 'periphone --background'   (in ~/.config/river/init)"},
	} {
		if strings.Contains(wm, w.name) {
			fmt.Println("  note  to start it at login on", w.name+", add:", w.line)
		}
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
	switch {
	case e2 != nil:
		check(false, "", "camera off: ffmpeg isn't installed ("+installHint("ffmpeg")+")")
	case !moduleInstalled():
		check(false, "", "camera off: v4l2loopback isn't installed ("+installHint("v4l2loopback")+")")
	case findLoopback() == "":
		check(false, "", `camera: the webcam module isn't loaded yet; the window's "Enable the camera" does it (once)`)
	default:
		check(true, "camera (ffmpeg + v4l2loopback)", "")
	}
	if fw := firewall(); fw != "" {
		fmt.Printf("  note  %s is on: for Wi-Fi the window's \"Open port %d\" lets the phone in\n", fw, netPort)
	}
	check(trayAvailable(), "system tray", "no system tray right now (GNOME: AppIndicator extension); the window works anyway")
	fmt.Printf("\nDone. Run: periphone   (or open \"Periphone\" from the app menu)\n")
}

func uninstall() {
	for _, p := range []string{home(".local", "bin", "periphone"),
		home(".local", "share", "applications", "periphone.desktop"),
		home(".config", "autostart", "periphone.desktop"), iconPath()} {
		if os.Remove(p) == nil {
			fmt.Println("  removed", p)
		}
	}
	fmt.Println("settings and PIN kept in", configDir())
}
