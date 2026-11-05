package main

// Things that differ between Linux systems: running a command as root through a graphical
// prompt, package names per distro, loading kernel modules at boot, the firewall.

import (
	"bufio"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"sync"
)

// distro: "arch", "debian", "fedora", "suse" or "" (from /etc/os-release ID / ID_LIKE).
func distro() string {
	f, err := os.Open("/etc/os-release")
	if err != nil {
		return ""
	}
	defer f.Close()
	ids := ""
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		l := sc.Text()
		if strings.HasPrefix(l, "ID=") || strings.HasPrefix(l, "ID_LIKE=") {
			ids += " " + strings.Trim(strings.SplitN(l, "=", 2)[1], `"`)
		}
	}
	for _, d := range []struct{ key, fam string }{
		{"arch", "arch"}, {"manjaro", "arch"}, {"cachyos", "arch"}, {"endeavouros", "arch"},
		{"debian", "debian"}, {"ubuntu", "debian"}, {"mint", "debian"}, {"pop", "debian"},
		{"fedora", "fedora"}, {"rhel", "fedora"}, {"centos", "fedora"}, {"nobara", "fedora"},
		{"suse", "suse"}, {"opensuse", "suse"},
	} {
		for _, id := range strings.Fields(ids) {
			if id == d.key || strings.HasPrefix(id, d.key) {
				return d.fam
			}
		}
	}
	return ""
}

// installHint: how to install a dependency on this distro.
func installHint(what string) string {
	pkgs := map[string]map[string]string{
		"v4l2loopback": {"arch": "sudo pacman -S v4l2loopback-dkms", "debian": "sudo apt install v4l2loopback-dkms",
			"fedora": "sudo dnf install akmod-v4l2loopback (RPM Fusion)", "suse": "sudo zypper install v4l2loopback-kmp-default"},
		"ffmpeg": {"arch": "sudo pacman -S ffmpeg", "debian": "sudo apt install ffmpeg",
			"fedora": "sudo dnf install ffmpeg (RPM Fusion)", "suse": "sudo zypper install ffmpeg"},
		"adb": {"arch": "sudo pacman -S android-tools", "debian": "sudo apt install adb",
			"fedora": "sudo dnf install android-tools", "suse": "sudo zypper install android-tools"},
		"adb-udev": {"arch": "sudo pacman -S android-udev, then replug the phone",
			"debian": "sudo apt install android-sdk-platform-tools-common, then replug the phone",
			"fedora": "sudo dnf install android-tools (it has the udev rules), then replug the phone"},
		"pipewire-pulse": {"arch": "sudo pacman -S pipewire-pulse", "debian": "sudo apt install pipewire-pulse",
			"fedora": "sudo dnf install pipewire-pulseaudio", "suse": "sudo zypper install pipewire-pulseaudio"},
	}
	if h := pkgs[what][distro()]; h != "" {
		return h
	}
	return "install " + what + " with your package manager"
}

// rootRun runs sh -c script as root behind a graphical password prompt: polkit (pkexec, part of
// every major desktop) first, sudo -A if the user set up an askpass helper. Without either it
// returns an error with the command to run by hand.
func rootRun(script, manual string) error {
	var tries [][]string
	if _, err := exec.LookPath("pkexec"); err == nil {
		tries = append(tries, []string{"pkexec", "sh", "-c", script})
	}
	if os.Getenv("SUDO_ASKPASS") != "" {
		if _, err := exec.LookPath("sudo"); err == nil {
			tries = append(tries, []string{"sudo", "-A", "sh", "-c", script})
		}
	}
	var last string
	for _, t := range tries {
		out, err := exec.Command(t[0], t[1:]...).CombinedOutput()
		if err == nil {
			return nil
		}
		last = strings.TrimSpace(string(out))
		if code := exitCode(err); t[0] == "pkexec" && (code == 126 || code == 127) &&
			!strings.Contains(last, "authentication agent") {
			// 126: the user dismissed the prompt; 127 without "agent": not authorized. Don't
			// pop a second prompt after a deliberate cancel.
			return errors.New("cancelled")
		}
	}
	if last == "" {
		last = "no graphical password prompt here (polkit agent or sudo askpass)"
	}
	return fmt.Errorf("%s\nrun it yourself in a terminal:  %s", last, manual)
}

func exitCode(err error) int {
	var ee *exec.ExitError
	if errors.As(err, &ee) {
		return ee.ExitCode()
	}
	return -1
}

// ---------------------------------------------------------------- firewall

var (
	fwOnce sync.Once
	fwName string
)

// firewallName: firewall() checked once (the window asks several times a second).
func firewallName() string {
	fwOnce.Do(func() { fwName = firewall() })
	return fwName
}

// firewall: "ufw" / "firewalld" if one is active.
func firewall() string {
	for _, fw := range []string{"ufw", "firewalld"} {
		if exec.Command("systemctl", "is-active", "-q", fw).Run() == nil {
			return fw
		}
	}
	return ""
}

func openFirewall() error {
	var script, manual string
	switch firewall() {
	case "ufw":
		script = fmt.Sprintf("ufw allow %d/udp && ufw allow %d/tcp", netPort, netPort)
		manual = fmt.Sprintf("sudo ufw allow %d", netPort)
	case "firewalld":
		script = fmt.Sprintf("firewall-cmd --permanent --add-port=%d/udp --add-port=%d/tcp && firewall-cmd --reload",
			netPort, netPort)
		manual = fmt.Sprintf("sudo firewall-cmd --permanent --add-port=%d/udp --add-port=%d/tcp && sudo firewall-cmd --reload",
			netPort, netPort)
	default:
		return errors.New("no ufw / firewalld running: nothing to open")
	}
	if err := rootRun(script, manual); err != nil {
		return err
	}
	logf("firewall: port %d open (UDP and TCP)", netPort)
	return nil
}
