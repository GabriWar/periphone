package main

// Loading the webcam kernel module (v4l2loopback) from the window: needs root once. It also sets
// the module to load at every boot, so this is a one-time thing.

import (
	"errors"
	"fmt"
	"os/exec"
	"strings"
	"time"
)

// The options file is only written if there is none (the user's wins). Boot loading: systemd's
// modules-load.d, else /etc/modules (Debian-style, Alpine / OpenRC).
const loadModuleScript = `set -e
if ! grep -rqs '^options v4l2loopback' /etc/modprobe.d; then
  mkdir -p /etc/modprobe.d
  printf 'options v4l2loopback exclusive_caps=1 card_label="Phone Camera"\n' > /etc/modprobe.d/periphone-camera.conf
fi
if [ -d /etc/modules-load.d ]; then
  grep -rqsx 'v4l2loopback' /etc/modules-load.d || printf 'v4l2loopback\n' > /etc/modules-load.d/periphone-camera.conf
elif [ -f /etc/modules ]; then
  grep -qsx 'v4l2loopback' /etc/modules || printf 'v4l2loopback\n' >> /etc/modules
fi
modprobe v4l2loopback`

const loadModuleManual = `sudo modprobe v4l2loopback exclusive_caps=1 card_label="Phone Camera"`

func moduleInstalled() bool {
	return exec.Command("modinfo", "-F", "filename", "v4l2loopback").Run() == nil
}

// canLoadCameraModule: the only thing missing for the camera is loading the module.
func canLoadCameraModule() bool {
	_, ff := exec.LookPath("ffmpeg")
	return ff == nil && findLoopback() == "" && moduleInstalled()
}

func loadCameraModule() error {
	if findLoopback() != "" {
		return nil
	}
	if !moduleInstalled() {
		return fmt.Errorf("v4l2loopback isn't installed: %s", installHint("v4l2loopback"))
	}
	logf("camera: loading the webcam module (asks for your password once; it loads at boot from now on)")
	if err := rootRun(loadModuleScript, loadModuleManual); err != nil {
		msg := err.Error()
		if strings.Contains(msg, "Key was rejected") || strings.Contains(msg, "Operation not permitted") ||
			strings.Contains(msg, "Required key not available") {
			return errors.New("Secure Boot refused the module: it must be signed with a key enrolled in " +
				"your firmware (DKMS usually offers this when installing: 'mokutil --import'), or Secure Boot " +
				"turned off")
		}
		return fmt.Errorf("couldn't load the webcam module: %s", msg)
	}
	for i := 0; i < 30 && findLoopback() == ""; i++ { // udev creates /dev/videoN a moment later
		time.Sleep(100 * time.Millisecond)
	}
	if findLoopback() == "" {
		return errors.New("the module loaded but no /dev/video device showed up")
	}
	return nil
}
