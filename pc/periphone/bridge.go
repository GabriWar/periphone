package main

// One running bridge: sound devices, the chosen link (USB tunnel / Wi-Fi / Bluetooth tethering /
// Bluetooth direct), the camera receiver, and the app on the phone (through adb, if reachable).

import (
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
)

// USB tunnel ports (adb reverse), raw PCM / framed H.264 as the app expects.
var usbPorts = map[byte]int{roleSpk: 12345, roleMic: 12346, roleCam: 12347, roleAudio: 12348}

type options struct {
	Link       string `json:"link"` // usb, wifi, pan, bt
	Out        bool   `json:"out"`
	Mic        bool   `json:"mic"`
	PhoneAudio bool   `json:"phoneAudio"`
	CamFacing  string `json:"camFacing"` // "" = leave the camera to the app, front / back = start it
	Codec      string `json:"codec"`     // auto, pcm, opus
	Encrypt    bool   `json:"encrypt"`
	PanMAC     string `json:"panMac"`
	Buffer     int    `json:"buffer"`  // ms, 0 = what the app has
	MicMode    int    `json:"micMode"` // 0 = what the app has
}

func defaultOptions() options {
	return options{Link: "usb", Out: true, Mic: true, Codec: "auto", Encrypt: true}
}

type bridge struct {
	mu      sync.RWMutex // opt, cam, camWhy: changed live by update / enableCamera
	opt     options
	au      *audio
	cam     *camera
	camWhy  string
	pcHost  string // what the app was told, for later settings pushes
	btAddr  string
	smu     sync.Mutex
	streams map[*streamRef]struct{} // open streams, to cut the ones switched off
	hs      *handshaker
	pin     string
	adb     *adbDev
	closers []io.Closer
	done    chan struct{}
	closing sync.Once
	conns   [5]atomic.Int32
}

func linkLabel(l string) string {
	return map[string]string{"usb": "USB", "wifi": "Wi-Fi", "pan": "Bluetooth tethering", "bt": "Bluetooth direct"}[l]
}

func startBridge(opt options) (*bridge, error) {
	b := &bridge{opt: opt, done: make(chan struct{}), streams: map[*streamRef]struct{}{}}
	var err error
	if opt.Link != "usb" {
		if b.pin, err = loadPIN(false); err != nil {
			return nil, err
		}
		b.hs = newHandshaker(b.pin)
	}
	b.adb, err = findADB()
	if opt.Link == "usb" && b.adb == nil {
		return nil, err
	}
	if b.au, err = openAudio(); err != nil {
		return nil, err
	}
	if opt.Link == "bt" {
		b.camWhy = "not over Bluetooth direct (too little bandwidth)"
	} else {
		b.cam, b.camWhy = newCamera()
	}
	fail := func(e error) (*bridge, error) { b.close(); return nil, e }

	btAddr, pcHost := "", ""
	switch opt.Link {
	case "usb":
		if err := b.serveUSB(); err != nil {
			return fail(err)
		}
	case "wifi", "pan":
		label := "wifi"
		if opt.Link == "pan" {
			label = "bt-tether"
			joinTethering(opt.PanMAC)
		} else {
			wifiChecks(b.adb)
			pcHost = b.adb.pcAddressForPhone()
		}
		firewallHint()
		if err := b.serveUDP(label); err != nil {
			return fail(fmt.Errorf("can't listen on port %d (another periphone running?): %w", netPort, err))
		}
		if err := b.serveTCP(label); err != nil {
			return fail(fmt.Errorf("can't listen on port %d: %w", netPort, err))
		}
		logf("network: listening on port %d (udp audio, tcp camera)", netPort)
	case "bt":
		if btAddr, err = b.serveBluetooth(); err != nil {
			return fail(err)
		}
		logf("bluetooth direct: ready (pair the phone with this PC first)")
	}
	if b.cam == nil {
		logf("camera: unavailable (%s)", b.camWhy)
	}
	if b.pin != "" {
		logf("PIN: %s", b.pin)
	}
	b.au.useAsDefaults(opt.Out, opt.Mic)
	if b.adb != nil {
		b.pcHost, b.btAddr = pcHost, btAddr
		b.adb.startApp(b)
	} else {
		logf("on the phone: open Periphone, Connection > %s, PIN %s, tap Start", linkLabel(opt.Link), b.pin)
	}
	return b, nil
}

func (b *bridge) getOpt() options {
	b.mu.RLock()
	defer b.mu.RUnlock()
	return b.opt
}

func (b *bridge) getCam() (*camera, string) {
	b.mu.RLock()
	defer b.mu.RUnlock()
	return b.cam, b.camWhy
}

func (b *bridge) wants(role byte) bool {
	o := b.getOpt()
	switch role {
	case roleSpk:
		return o.Out
	case roleMic:
		return o.Mic
	case roleAudio:
		return o.PhoneAudio
	case roleCam:
		cam, _ := b.getCam()
		return cam != nil
	}
	return false
}

// streamRef: one open stream (USB connection or network session), so update can cut it.
type streamRef struct {
	role  byte
	plain bool // a network stream without encryption
	close func()
}

func (b *bridge) track(role byte, plain bool, close func()) (untrack func()) {
	r := &streamRef{role, plain, close}
	b.smu.Lock()
	b.streams[r] = struct{}{}
	b.smu.Unlock()
	return func() {
		b.smu.Lock()
		delete(b.streams, r)
		b.smu.Unlock()
	}
}

// update applies new settings to the running bridge (same link): no restart, the app on the phone
// gets them in the background and restarts only the streams they affect.
func (b *bridge) update(o options) {
	b.mu.Lock()
	old := b.opt
	b.opt = o
	b.mu.Unlock()
	if o.Out != old.Out {
		if o.Out {
			b.au.useAsDefaults(true, false)
		} else {
			b.au.restoreSink()
		}
	}
	if o.Mic != old.Mic {
		if o.Mic {
			b.au.useAsDefaults(false, true)
		} else {
			b.au.restoreSource()
		}
	}
	b.smu.Lock()
	var cut []*streamRef
	for r := range b.streams {
		// switched off, or encryption turned on under an unencrypted stream (it reconnects encrypted)
		if !b.wants(r.role) || (o.Encrypt && r.plain) {
			cut = append(cut, r)
		}
	}
	b.smu.Unlock()
	for _, r := range cut {
		r.close()
	}
	if b.adb != nil {
		b.adb.pushSettings(b)
	}
	logf("settings applied")
}

// enableCamera starts the camera receiver once the webcam module is there (no restart).
func (b *bridge) enableCamera() error {
	if cam, _ := b.getCam(); cam != nil {
		return nil
	}
	if b.getOpt().Link == "bt" {
		return fmt.Errorf("no camera over Bluetooth direct")
	}
	cam, why := newCamera()
	b.mu.Lock()
	b.cam, b.camWhy = cam, why
	b.mu.Unlock()
	if cam == nil {
		return fmt.Errorf("%s", why)
	}
	if b.getOpt().Link == "usb" {
		if err := b.listenUSB(roleCam); err != nil {
			return err
		}
		if b.adb != nil {
			p := "tcp:" + strconv.Itoa(usbPorts[roleCam])
			b.adb.cmd("reverse", p, p)
		}
	}
	logf("camera: ready (%s)", cam.device)
	return nil
}

func (b *bridge) connected(role byte, d int32) { b.conns[role].Add(d) }

func (b *bridge) isOn(role byte) bool { return b.conns[role].Load() > 0 }

func (b *bridge) isClosed() bool {
	select {
	case <-b.done:
		return true
	default:
		return false
	}
}

func (b *bridge) close() {
	b.closing.Do(func() {
		close(b.done)
		for _, c := range b.closers {
			c.Close()
		}
		if cam, _ := b.getCam(); cam != nil {
			cam.close()
		}
		if b.au != nil {
			b.au.close()
		}
		if b.adb != nil {
			b.adb.stopApp(b.getOpt().Link == "usb")
		}
	})
}

// ---------------------------------------------------------------- USB tunnel

func (b *bridge) serveUSB() error {
	for role := range usbPorts {
		if cam, _ := b.getCam(); role == roleCam && cam == nil {
			continue
		}
		if err := b.listenUSB(role); err != nil {
			return err
		}
	}
	return nil
}

func (b *bridge) listenUSB(role byte) error {
	port := usbPorts[role]
	l, err := net.Listen("tcp4", "127.0.0.1:"+strconv.Itoa(port))
	if err != nil {
		return fmt.Errorf("port %d is taken (another program on it? an old phone-bridge PipeWire config? "+
			"run: periphone install): %w", port, err)
	}
	b.mu.Lock()
	b.closers = append(b.closers, l)
	b.mu.Unlock()
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go b.handleUSB(role, c)
		}
	}()
	return nil
}

func (b *bridge) handleUSB(role byte, c net.Conn) {
	defer c.Close()
	if !b.wants(role) {
		return
	}
	if t, ok := c.(*net.TCPConn); ok {
		t.SetNoDelay(true)
	}
	go func() { <-b.done; c.Close() }()
	defer b.track(role, false, func() { c.Close() })()
	b.connected(role, 1)
	defer b.connected(role, -1)
	logf("usb: %s connected", roleNames[role])
	defer logf("usb: %s disconnected", roleNames[role])
	switch role {
	case roleSpk:
		ch := b.au.subscribe()
		defer b.au.unsubscribe(ch)
		for {
			select {
			case <-b.done:
				return
			case chunk := <-ch:
				if _, err := c.Write(chunk); err != nil {
					return
				}
			}
		}
	case roleMic, roleAudio:
		r, size := b.au.mic, chunkMono
		if role == roleAudio {
			r, size = b.au.phone, chunkSt
		}
		buf := make([]byte, size)
		for {
			if _, err := io.ReadFull(c, buf); err != nil {
				return
			}
			r.write(buf)
		}
	case roleCam:
		if cam, _ := b.getCam(); cam != nil {
			cam.serve(c)
		}
	}
}

// ---------------------------------------------------------------- adb

type adbDev struct{ serial string }

// findADB: the one phone adb sees (ANDROID_SERIAL picks one if there are several).
func findADB() (*adbDev, error) {
	if _, err := exec.LookPath("adb"); err != nil {
		return nil, errors.New("adb isn't installed (needed for USB): " + installHint("adb"))
	}
	out, _ := exec.Command("adb", "devices").Output()
	var ok []string
	unauthorized, noPerm := false, false
	for _, line := range strings.Split(string(out), "\n")[1:] {
		f := strings.Fields(line)
		if len(f) == 2 && f[1] == "device" {
			ok = append(ok, f[0])
		}
		if len(f) == 2 && f[1] == "unauthorized" {
			unauthorized = true
		}
		if len(f) >= 2 && strings.HasPrefix(f[1], "no") && strings.Contains(line, "permissions") {
			noPerm = true // the USB device isn't accessible: udev rules missing
		}
	}
	if noPerm && len(ok) == 0 {
		return nil, errors.New("adb sees the phone but has no permission to use it (missing udev rules): " +
			installHint("adb-udev"))
	}
	if s := os.Getenv("ANDROID_SERIAL"); s != "" {
		for _, d := range ok {
			if d == s {
				return &adbDev{serial: s}, nil
			}
		}
		return nil, fmt.Errorf("ANDROID_SERIAL=%s isn't connected (adb devices)", s)
	}
	switch {
	case len(ok) == 1:
		return &adbDev{serial: ok[0]}, nil
	case len(ok) > 1:
		return nil, errors.New("more than one phone in adb; pick one with ANDROID_SERIAL=<serial>")
	case unauthorized:
		return nil, errors.New("the phone hasn't authorized this PC: unlock it and accept the USB debugging prompt")
	}
	return nil, errors.New("no phone found by adb: check the cable and USB debugging, or use Wi-Fi / Bluetooth")
}

func (d *adbDev) sh(args ...string) (string, error) {
	if d == nil {
		return "", errors.New("no adb")
	}
	out, err := exec.Command("adb", append([]string{"-s", d.serial, "shell"}, args...)...).Output()
	return string(out), err
}

func (d *adbDev) cmd(args ...string) error {
	return exec.Command("adb", append([]string{"-s", d.serial}, args...)...).Run()
}

const pkg = "dev.gabriwar.periphone"

func (d *adbDev) startApp(b *bridge) {
	if out, _ := d.sh("pm", "path", pkg); !strings.Contains(out, "package:") {
		logf("the app isn't installed on the phone: ./build.sh && adb install -r periphone.apk")
		return
	}
	d.sh("am", "force-stop", "com.kaytat.simpleprotocolplayer") // the old player would double the stream
	// no force-stop of the app: a running one takes the new settings (it restarts only the
	// streams they affect); killing it looked like a crash on the phone
	opt := b.getOpt()
	cam, _ := b.getCam()
	if opt.Link == "usb" {
		for role, port := range usbPorts {
			p := "tcp:" + strconv.Itoa(port)
			if role != roleCam || cam != nil {
				d.cmd("reverse", p, p)
			}
		}
	}
	for _, perm := range []string{"RECORD_AUDIO", "CAMERA", "POST_NOTIFICATIONS", "BLUETOOTH_CONNECT"} {
		d.sh("pm", "grant", pkg, "android.permission."+perm)
	}
	// stay alive in the background: no battery optimization, no background limit, active bucket
	d.sh("dumpsys", "deviceidle", "whitelist", "+"+pkg)
	d.sh("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow")
	d.sh("am", "set-standby-bucket", pkg, "active")
	if opt.PhoneAudio { // lets the capture start without the screen-capture prompt, where honored
		d.sh("appops", "set", pkg, "PROJECT_MEDIA", "allow")
	}
	d.sh("input", "keyevent", "KEYCODE_WAKEUP")

	a := append([]string{"am", "start", "-n", pkg + "/.Autostart", "--ez", "autostart", "true"},
		settingsExtras(b, opt)...)
	if _, err := d.sh(a...); err != nil {
		logf("couldn't start the app over adb: %v", err)
		return
	}
	logf("app started on the phone (%s)", d.serial)
}

// settingsExtras: the app's settings as am extras (see Cfg.applyExtras on the phone).
func settingsExtras(b *bridge, opt options) []string {
	link := map[string]string{"usb": "0", "wifi": "1", "pan": "2", "bt": "3"}[opt.Link]
	codec := map[string]string{"auto": "0", "pcm": "1", "opus": "2"}[opt.Codec]
	tf := func(v bool) string { return strconv.FormatBool(v) }
	a := []string{"--ez", "spk", tf(opt.Out), "--ez", "mic", tf(opt.Mic), "--ez", "cap", tf(opt.PhoneAudio),
		"--ei", "spkport", "12345", "--ei", "micport", "12346", "--ei", "camport", "12347",
		"--ei", "link", link}
	if opt.Link != "usb" {
		a = append(a, "--es", "pin", b.pin, "--ez", "encrypt", tf(opt.Encrypt), "--ei", "codec", codec)
		if opt.Link == "wifi" {
			a = append(a, "--es", "host", b.pcHost)
		}
		if b.btAddr != "" {
			a = append(a, "--es", "btaddr", b.btAddr)
		}
	}
	if opt.Buffer > 0 {
		a = append(a, "--ei", "buf", strconv.Itoa(opt.Buffer))
	}
	if opt.MicMode > 0 {
		a = append(a, "--ei", "src", strconv.Itoa(opt.MicMode))
	}
	if opt.CamFacing != "" {
		a = append(a, "--ez", "cam", "true", "--ez", "camfront", tf(opt.CamFacing == "front"))
	}
	return a
}

// pushSettings hands new settings to the running app in the background (no screen switch).
func (d *adbDev) pushSettings(b *bridge) {
	opt := b.getOpt()
	if opt.PhoneAudio { // lets the capture start without the screen-capture prompt, where honored
		d.sh("appops", "set", pkg, "PROJECT_MEDIA", "allow")
	}
	a := append([]string{"am", "broadcast", "-n", pkg + "/.SettingsReceiver"}, settingsExtras(b, opt)...)
	if _, err := d.sh(a...); err != nil {
		logf("couldn't send the settings to the phone: %v", err)
	}
}

// stopApp asks the app (if it runs) to stop its bridge and go to the background; no force-stop.
func (d *adbDev) stopApp(usb bool) {
	if out, _ := d.sh("pidof", pkg); strings.TrimSpace(out) != "" {
		d.sh("am", "broadcast", "-n", pkg+"/.SettingsReceiver", "--ez", "stopbridge", "true")
	}
	if usb {
		for _, port := range usbPorts {
			d.cmd("reverse", "--remove", "tcp:"+strconv.Itoa(port))
		}
	}
}

// pcAddressForPhone: our address on the network the phone is on (wireless debugging / cable).
func (d *adbDev) pcAddressForPhone() string {
	if d == nil {
		return ""
	}
	out, _ := d.sh("ip", "-4", "-o", "addr", "show", "wlan0")
	f := strings.Fields(out)
	for i, w := range f {
		if w == "inet" && i+1 < len(f) {
			ip := strings.Split(f[i+1], "/")[0]
			c, err := net.Dial("udp4", ip+":"+strconv.Itoa(netPort)) // no packet sent: just routing
			if err != nil {
				return ""
			}
			defer c.Close()
			return c.LocalAddr().(*net.UDPAddr).IP.String()
		}
	}
	return ""
}

// ---------------------------------------------------------------- network hints

func wifiChecks(d *adbDev) {
	if _, err := exec.LookPath("iw"); err == nil {
		out, _ := exec.Command("iw", "dev").Output()
		for _, line := range strings.Split(string(out), "\n") {
			f := strings.Fields(line)
			if len(f) != 2 || f[0] != "Interface" {
				continue
			}
			w := f[1]
			if ps, _ := exec.Command("iw", "dev", w, "get", "power_save").Output(); strings.Contains(string(ps), ": on") {
				logf("tip: %s has power saving on (adds jitter): sudo iw dev %s set power_save off", w, w)
			}
			link, _ := exec.Command("iw", "dev", w, "link").Output()
			for _, l := range strings.Split(string(link), "\n") {
				if f := strings.Fields(l); len(f) >= 2 && f[0] == "freq:" {
					if mhz, _ := strconv.ParseFloat(f[1], 64); mhz > 0 && mhz < 3000 {
						logf("warning: the PC's Wi-Fi (%s) is on 2.4 GHz: expect dropouts; use 5 GHz", w)
					}
				}
			}
		}
	}
	if out, err := d.sh("dumpsys", "wifi"); err == nil {
		if i := strings.Index(out, "Frequency: "); i >= 0 {
			mhz, _ := strconv.Atoi(strings.TrimRight(strings.Fields(out[i+11:])[0], "MHz,"))
			if mhz > 0 && mhz < 3000 {
				logf("warning: the phone's Wi-Fi is on 2.4 GHz: expect dropouts; use 5 GHz")
			}
		}
	}
}

func firewallHint() {
	for _, fw := range []string{"ufw", "firewalld"} {
		if exec.Command("systemctl", "is-active", "-q", fw).Run() == nil {
			logf("note: %s is on: if the phone can't connect, use \"Open port %d\" in the window", fw, netPort)
		}
	}
}

func joinTethering(mac string) {
	if _, err := exec.LookPath("nmcli"); err != nil {
		logf("join the phone's Bluetooth tethering with your network manager")
		return
	}
	if mac == "" {
		out, _ := exec.Command("bluetoothctl", "devices", "Paired").Output()
		var macs []string
		for _, l := range strings.Split(string(out), "\n") {
			if f := strings.Fields(l); len(f) >= 2 && f[0] == "Device" {
				macs = append(macs, f[1])
			}
		}
		if len(macs) != 1 {
			logf("join the phone's Bluetooth tethering: periphone --pan --pan-mac <phone MAC>")
			return
		}
		mac = macs[0]
	}
	logf("joining the phone's Bluetooth tethering (%s)...", mac)
	if exec.Command("nmcli", "device", "connect", mac).Run() != nil {
		logf("couldn't join it: turn on Bluetooth tethering on the phone (Settings > Hotspot & tethering)")
	}
}

func setTOS(fd, tos int) { syscall.SetsockoptInt(fd, syscall.IPPROTO_IP, syscall.IP_TOS, tos) }
