# USB Audio Bridge

Use an Android phone as the PC's sound card **and webcam over the USB cable, Wi-Fi or Bluetooth**:
the PC's audio plays on the phone (and from there on Bluetooth or wired headphones), the phone mic
becomes a microphone on the PC, the phone camera (front or back) becomes a webcam, and the phone's
own sound can play on the PC speakers.
Made for a machine with no Bluetooth and no analog output.

```
PC                                     USB cable (adb reverse)             Phone (app)
phone_speaker ── TCP 127.0.0.1:12345 ─────────────────────────────▶ AudioTrack ─▶ headphones
phone_mic     ◀─ TCP 127.0.0.1:12346 ◀───────────────────────────── AudioRecord ◀ mic
/dev/videoN   ◀─ phone-cam :12347    ◀── H.264 ──────────────────── Camera2 + HW encoder
```

Audio format is fixed: PCM S16LE 48 kHz (stereo out, mono mic). Fixed on purpose: a player at
the wrong rate pitches the audio down (48k played at 44.1k).

Wi-Fi and Bluetooth go through `phone-relay` on the PC; the PipeWire ports stay loopback-only:

```
phone ══ UDP :12350 (audio, 5 ms packets) ═══╗
      ══ TCP :12350 (camera) ════════════════╬══ phone-relay ── 127.0.0.1:12345..12348
      ══ RFCOMM (Bluetooth direct, audio) ═══╝
```

## Links

| Link | PC | Audio | Camera | Extra delay vs USB (estimate, not measured) |
|------|----|-------|--------|--------------------|
| USB cable (default) | `phone-bridge` | raw PCM | yes | — |
| Wi-Fi | `phone-bridge --wifi` | raw PCM over UDP | yes | ~2–10 ms (more if the network is busy) |
| Bluetooth tethering | `phone-bridge --pan [phone MAC]` | Opus 5 ms | low quality only | ~20–40 ms |
| Bluetooth direct | `phone-bridge --bt` | Opus 5 ms | no | ~20–40 ms |

Pick the same link in the app (Connection card). With wireless debugging (`adb pair` / `adb connect`)
`phone-bridge` sets up the app itself; without adb, open the app, pick the link, type the PIN and
tap Start.

- **PIN**: `phone-bridge pin` shows it (`phone-bridge pin new` changes it). The check is SPAKE2,
  so someone recording the traffic can't brute-force the PIN offline; wrong guesses are limited
  to one per second.
- **Encryption** (on by default, `--no-encrypt` / app switch to turn it off): AES-128-GCM per
  packet, a fresh key per stream from the PIN handshake. Hardware AES on both sides: a few µs
  per packet.
- **Firewall**: the phone must reach UDP and TCP port 12350 on the PC (`sudo ufw allow 12350`).
  Discovery (empty "PC address" in the app) uses a UDP broadcast.
- **Bluetooth**: pair the phone with the PC first. Direct mode needs `python-dbus` and
  `python-gobject`; tethering needs NetworkManager (`nmcli`) to join the phone's network.
- **Codec**: Automatic = raw PCM on Wi-Fi (no codec delay), Opus on Bluetooth (raw stereo + mic is
  ~2.3 Mbit/s, more than Bluetooth carries reliably). Opus runs in restricted low-delay mode with
  5 ms frames, so it adds ~5 ms; lost packets are concealed instead of clicking.

### Making Wi-Fi faster

1. Turn off power saving on the PC's Wi-Fi: `sudo iw dev wlan0 set power_save off` (it parks the
   radio between beacons: tens of ms of jitter). The app already holds a low-latency Wi-Fi lock
   on the phone (fully effective while the screen is on).
2. Use 5 GHz, close to the router. Packets are marked DSCP EF (voice queue on WMM routers).
3. Wired Ethernet on the PC, phone on Wi-Fi, is better than both on Wi-Fi.
4. If it crackles, raise the output buffer (Latency card); Wi-Fi jitter is the limit, not the code.

## Quick start

```sh
pc/install.sh                                   # once: PipeWire configs, scripts, webcam module
./build.sh && adb install -r usb-audio-bridge.apk
phone-bridge                                    # phone unlocked, cable plugged in
```

## App (`src/`, `res/`)

All text lives in `res/values/strings.xml`; add `res/values-xx/strings.xml` for another language.

- One big **Start/Stop** and a status card that says what's wrong and what to do.
- **PC audio → phone**: mute, volume (0–200%), which phone output to play on, live measured
  delay, spectrogram. Warns when Bluetooth is the output (it alone adds ~150–250 ms).
- **Phone mic → PC**: level meter, mute (also from the notification), volume, input device,
  mic mode (standard / voice call with echo cancelling / raw / ...), spectrogram.
- **Phone audio → PC**: the phone's own sound (music, videos, games) on the PC speakers
  (Android 10+). Android asks for the screen-capture permission each session; apps that block
  capture (calls, some DRM video) stay silent. The PC's audio playing on the phone is excluded,
  so nothing loops.
- **Phone camera → PC**: front/back switch (live, no reconnect), preview, resolution, frame
  rate, quality, rotation (auto follows how the phone is held) and mirror.
- **Latency presets** (Lowest / Balanced / Stable) with fine-tune sliders.
- Changing a setting restarts only the stream it affects.
- Runs as a foreground service: the screen can turn off.

Builds without Gradle (Android SDK with any recent `platforms;android-NN` + `build-tools`,
the NDK, cmake, JDK 17+). `build.sh` picks the newest installed ones and, the first time,
downloads libopus (checksum-pinned) into `.cache/` and builds it for arm64 and armv7.

### Latency and quality

- Output: the delay sitting in the `AudioTrack` queue is kept around the buffer setting. Clock
  drift between PC and phone is absorbed by dropping/repeating **one frame** per 5 ms (inaudible)
  instead of cutting whole chunks; a burst is cut right away; if the queue runs dry the cushion
  is rebuilt. The app shows the real delay to the speaker (from `AudioTrack.getTimestamp`).
- PipeWire quantum for the phone sink lowered to 256 samples (5.3 ms).
- Camera: the camera renders straight into the hardware encoder (no copies); encoder set to
  realtime / low latency, no B-frames, stabilization off. On the PC, ffmpeg decodes with frame
  threading off and an access-unit delimiter after every frame, so a frame shows ~1 frame after
  it arrives instead of ~14.

## PC (`pc/`)

`pc/install.sh` copies:

- `pipewire/20-phone-mic.conf` — virtual microphone **"Phone mic (USB)"**.
- `pipewire/21-phone-speaker.conf` — sink **"Phone speaker (USB)"** served on `127.0.0.1:12345`.
- `pipewire/22-phone-mic-server.conf` — receives the phone mic on `127.0.0.1:12346`.
- `bin/phone-bridge`, `bin/phone-cam`, `bin/phone-relay` (and the legacy `phone-mic` /
  `phone-speaker`) to `~/.local/bin`.

The phone-audio receiver (port 12348) isn't a config file: `phone-bridge` loads it while running,
pointed at the speakers you had before, so the phone's sound never goes back to the phone.

> The PipeWire config parser **does not accept non-ASCII characters, not even in comments**.

### Camera

Needs `ffmpeg`, `python3` and the `v4l2loopback` module (Arch: `v4l2loopback-dkms`).
`install.sh` offers to load it now and at boot. `phone-cam` holds the webcam device open the whole
session (black frames while the phone isn't sending), so apps don't lose the camera when you
switch front/back or turn the phone.

## Usage

```sh
phone-bridge                  # output + mic (+ camera receiver if v4l2loopback is loaded)
phone-bridge --cam front      # also start the front camera (or: --cam back)
phone-bridge -b 40 -m 7       # 40 ms buffer, mic in voice-call mode (echo cancelling)
phone-bridge --no-mic         # only PC audio -> phone
phone-bridge --wifi -a        # over Wi-Fi, plus the phone's sound on the PC speakers
phone-bridge --bt             # Bluetooth direct
phone-bridge pin              # the PIN to type in the app
phone-bridge stop             # stop and put the audio back where it was
phone-bridge ports 23456 23457 23458   # if 12345/12346/12347 are taken
```

`phone-bridge` sets up the `adb reverse` tunnels, opens the app, waits for the connections and
makes `phone_speaker` / `phone_mic` the system defaults; on exit it restores your previous
output and input. In games/Discord the input is **"Phone mic (USB)"**, the camera is the
v4l2loopback device.

`pc/bin/phone-mic` and `pc/bin/phone-speaker` are the old versions (mic via `scrcpy`, output via
Simple Protocol Player), kept as an alternative.

## Latency notes

Bluetooth (A2DP/AAC) alone costs ~150–250 ms on Android; the app adds a few ms on top. For truly
low delay: wired headphones on the phone and the **Lowest** preset.
