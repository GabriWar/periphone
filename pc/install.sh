#!/usr/bin/env bash
# One-time PC setup: PipeWire configs + scripts, and (optionally) the virtual webcam module.
# Safe to run again after updating the repo.
set -euo pipefail
cd "$(dirname "$0")"

CONF_DIR="$HOME/.config/pipewire/pipewire.conf.d"
BIN_DIR="$HOME/.local/bin"

ok()   { echo "  ok    $*"; }
warn() { echo "  !!    $*"; }

echo "== dependencies"
for cmd in adb pactl ss; do
    if command -v "$cmd" >/dev/null; then ok "$cmd"; else warn "$cmd missing (needed)"; fi
done
for cmd in ffmpeg python3; do
    if command -v "$cmd" >/dev/null; then ok "$cmd"; else warn "$cmd missing (only needed for the camera)"; fi
done

echo "== PipeWire configs -> $CONF_DIR"
mkdir -p "$CONF_DIR"
for f in pipewire/*.conf; do
    dst="$CONF_DIR/$(basename "$f")"
    # keep ports changed with 'phone-bridge ports'
    old_port=$(grep -oP 'tcp:127\.0\.0\.1:\K[0-9]+' "$dst" 2>/dev/null | head -1 || true)
    install -m 644 "$f" "$dst"
    if [ -n "$old_port" ]; then sed -i "s|tcp:127\.0\.0\.1:[0-9]*|tcp:127.0.0.1:$old_port|" "$dst"; fi
    ok "$(basename "$f")${old_port:+ (port $old_port kept)}"
done

echo "== scripts -> $BIN_DIR"
mkdir -p "$BIN_DIR"
for f in bin/*; do
    install -m 755 "$f" "$BIN_DIR/$(basename "$f")"
    ok "$(basename "$f")"
done
case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *) warn "$BIN_DIR is not in your PATH; add it to run phone-bridge directly" ;;
esac

echo "== restarting PipeWire"
systemctl --user restart pipewire pipewire-pulse wireplumber
sleep 1
if pactl list short sinks | grep -q phone_speaker; then ok "sink 'Phone speaker (USB)' is up"; else warn "phone_speaker sink not found, check: journalctl --user -u pipewire"; fi
if pactl list short sources | grep -q phone_mic; then ok "source 'Phone mic (USB)' is up"; else warn "phone_mic source not found"; fi

echo "== camera (v4l2loopback)"
if ls /sys/devices/virtual/video4linux/video* >/dev/null 2>&1; then
    ok "v4l2loopback device present: $(ls /sys/devices/virtual/video4linux | sed 's|^|/dev/|' | tr '\n' ' ')"
elif modinfo v4l2loopback >/dev/null 2>&1; then
    echo "  The v4l2loopback module is installed but not loaded. Load it now (sudo) and at every boot?"
    read -r -p "  [y/N] " ans
    if [[ "$ans" =~ ^[Yy] ]]; then
        sudo modprobe v4l2loopback exclusive_caps=1 card_label="Phone Camera" \
            && ok "loaded" || warn "modprobe failed"
        echo v4l2loopback | sudo tee /etc/modules-load.d/phone-camera.conf >/dev/null \
            && ok "will load at boot (/etc/modules-load.d/phone-camera.conf)"
        if ! grep -rqs "options v4l2loopback" /etc/modprobe.d/; then
            echo 'options v4l2loopback exclusive_caps=1 card_label="Phone Camera"' \
                | sudo tee /etc/modprobe.d/phone-camera.conf >/dev/null && ok "module options set"
        fi
    else
        echo "  skipped. Later: sudo modprobe v4l2loopback exclusive_caps=1 card_label=\"Phone Camera\""
    fi
else
    warn "v4l2loopback not installed (camera disabled). Arch: sudo pacman -S v4l2loopback-dkms"
fi

echo
echo "Done. Install the app (./build.sh && adb install -r usb-audio-bridge.apk), then run: phone-bridge"
