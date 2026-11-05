# Periphone

Use an Android phone as the PC's sound card and webcam, over the **USB cable, Wi-Fi or
Bluetooth**:

- the PC's audio plays on the phone (and from there on wired or Bluetooth headphones)
- the phone mic becomes a microphone on the PC
- the phone camera (front or back) becomes a webcam
- the phone's own sound (music, videos, games) plays on the PC speakers

```
                          USB cable (adb reverse)                       Phone (app)
"Phone speaker" ─▶ periphone 127.0.0.1:12345 ──────────────▶ AudioTrack ─▶ headphones
"Phone mic"     ◀─ periphone 127.0.0.1:12346 ◀────────────── AudioRecord ◀ mic
/dev/videoN     ◀─ periphone 127.0.0.1:12347 ◀── H.264 ───── Camera2 + HW encoder
"Phone audio"   ◀─ periphone 127.0.0.1:12348 ◀────────────── playback capture

Wi-Fi / Bluetooth: the same periphone, PIN-checked and encrypted
phone ══ UDP :12350 (audio, 5 ms packets) ═══╗
      ══ TCP :12350 (camera) ════════════════╬══ periphone
      ══ RFCOMM (Bluetooth direct, audio) ═══╝
```

Audio format is fixed: PCM S16LE 48 kHz (stereo, mono mic). Fixed on purpose: a player at the
wrong rate pitches the audio down (48k played at 44.1k).

## Quick start

```sh
pc/periphone/build.sh && pc/periphone/periphone install   # once (or grab the built binary)
./build.sh && adb install -r periphone.apk                # the app
periphone                                                      # window + tray; pick the link there
periphone --wifi --no-tray                                     # or in the terminal
```

## Links

| Link | On the PC | Audio | Camera |
|------|-----------|-------|--------|
| USB cable (default) | `periphone` | raw PCM | yes |
| Wi-Fi | `periphone --wifi` | Opus, 5 ms frames, over UDP | yes (TCP) |
| Bluetooth tethering | `periphone --pan` | Opus, 5 ms frames | low resolutions only |
| Bluetooth direct | `periphone --bt` | Opus, 5 ms frames | no |

USB has the lowest and steadiest delay. On Wi-Fi with both ends on 5 GHz it is close (measured:
0 underruns at the 30 ms buffer). On Wi-Fi and Bluetooth the output buffer adapts: it grows when
the network leaves a gap and shrinks back once it is calm, never below the buffer setting.
Bluetooth adds the radio's own delay, a few tens of ms.

Pick the link in the tray (or with a flag); with adb reaching the phone (cable, or wireless
debugging with `adb pair` / `adb connect`) `periphone` sets the app up and starts it.
Without adb: open the app, pick the link, type the PIN, tap Start.

- **Firewall**: the phone must reach UDP and TCP port 12350 on the PC (`sudo ufw allow 12350`).
  With "PC address" left empty, the app finds the PC with a UDP broadcast.
- **Bluetooth**: pair the phone with the PC first. Tethering needs NetworkManager (`nmcli`) to
  join the phone's network.
- **Codec**: Automatic = Opus on every wireless link: restricted low-delay mode, 5 ms frames,
  ~2.5 ms lookahead. Packets are ~10x smaller than raw PCM (less airtime), lost ones are concealed
  instead of clicking, and each UDP packet also carries the previous chunk, so a single lost
  packet is rebuilt exactly. Raw PCM (`--codec pcm`) skips the codec but clicks on loss, and the
  redundant copy only fits for the mono mic.
- **Staying alive in the background**: the app runs as a foreground service with a wake lock and
  asks once to be exempt from battery optimization; `periphone` also grants that over adb.
  Xiaomi / Oppo / Huawei have their own switch on top: Advanced › "Open the app's settings" ›
  Battery saver: No restrictions, Autostart on, and lock the app in Recents.

### Security (Wi-Fi / Bluetooth)

- **PIN**: `periphone pin` shows it (so does the tray), `periphone pin new` changes it. The check is SPAKE2:
  someone recording the traffic can't brute-force the PIN offline, each guess needs a live
  handshake.
- **Guess limits**: on the PC, every handshake that doesn't finish with the right PIN costs a
  token (8 to start, 1 back every 10 s): about one guess per 10 s, ~2 months on average for a
  6-digit PIN, and `periphone` logs a warning. On the phone, each PIN mismatch doubles the wait
  before the next try (3 s up to ~1 min; reset by a success, a new PIN or Stop/Start), so a fake
  PC answering discovery gets about one guess a minute. A REPLY that doesn't prove the PIN is
  ignored while the real PC still has time to answer, so a forged one can't break the handshake.
- **Encryption** (on by default; `--no-encrypt` or the app switch turns it off): AES-128-GCM on
  every packet, a fresh key per stream from the PIN handshake. Hardware AES on both sides, a few
  µs per packet. With encryption off the PIN is still checked, but the audio travels in the clear
  and could be altered on the network.
- Not covered: someone on the same network can cut a stream for a moment by spoofing the
  keepalive / goodbye packets (the app reconnects by itself), same as Wi-Fi itself can be jammed.

### Making Wi-Fi faster

1. Turn off power saving on the PC's Wi-Fi: `sudo iw dev wlan0 set power_save off` (it parks the
   radio between beacons: tens of ms of jitter). To keep it off on NetworkManager:
   `wifi.powersave = 2` in `/etc/NetworkManager/conf.d/`. The app holds a low-latency Wi-Fi lock
   on the phone (fully effective while the screen is on).
2. **Phone and PC both on 5 GHz.** This makes the biggest difference. Measured here: phone on
   2.4 GHz gave 3 % packet loss and 140 ms spikes (the buffer had to grow past 100 ms); both on
   5 GHz gave 0 % loss, ~4 ms round trip, 0 underruns at the 30 ms buffer. `periphone --wifi`
   and the app warn when either side is on 2.4 GHz. Packets are marked DSCP EF (voice queue on
   WMM routers).
3. PC on Ethernet, phone on Wi-Fi, beats both on Wi-Fi.
4. If it crackles, raise the output buffer (Latency card).

## App (`src/`, `res/`)

All text lives in `res/values/strings.xml`; add `res/values-xx/strings.xml` for another language.

Five tabs, with a status strip and Start/Stop always on top:

- **Home**: the big status and Start/Stop, and one row per stream (connection, PC audio, mic,
  phone audio, camera) with its state, a quick on/off switch and the mic level; tap a row to
  open its tab.
- **Connection**: USB / Wi-Fi / BT net / BT direct, PC address (empty = find it), paired PC for
  Bluetooth direct, PIN (saved when you leave the field, switch tabs or tap Start), encryption,
  codec, "connected over…", 2.4 GHz warning.
- **Audio**:
  - PC audio → phone: mute, volume (0–200%), which phone output to play on, live measured
    delay, spectrogram; warns when Bluetooth headphones are the output (~150–250 ms on their own).
  - Phone mic → PC: level meter, mute (also from the notification), volume, input device, mic mode
    (standard / voice call with echo cancelling / raw / ...), spectrogram.
  - Phone audio → PC: the phone's own sound (Android 10+). Android asks for the screen-capture
    permission each session (`periphone -a` tries to pre-allow it over adb); apps that block
    capture (calls, some DRM video) stay silent; the PC's audio playing on the phone is excluded,
    so nothing loops.
- **Camera**: front/back (live, no reconnect), preview, resolution, frame rate, quality,
  rotation (auto follows how the phone is held), mirror.
- **Settings**: latency presets (Lowest / Balanced / Stable) with fine-tune sliders; advanced:
  keep screen on, background settings, ports, reset (asks first).

Changing a setting restarts only the stream it affects, and says whether it applied now or on the
next Start. Runs as a foreground service: the screen can turn off.

Builds without Gradle: Android SDK (any recent `platforms;android-NN` + `build-tools`), the NDK,
cmake, JDK 17+. `build.sh` picks the newest installed ones and, the first time, downloads libopus
(checksum-pinned) into `.cache/` and builds it for arm64 and armv7.

`debug.keystore` (in the repo, the repo is private) signs the APK. Keep it: an APK signed with
another key can't update the installed app (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`; the only way
out is uninstalling, which loses the app's settings).

### Latency and quality

- Output: the delay sitting in the `AudioTrack` queue is kept around the buffer setting. Clock
  drift between PC and phone is absorbed by dropping/repeating **one frame** per 5 ms (inaudible)
  instead of cutting whole chunks; a burst is cut right away; if the queue runs dry the cushion
  is rebuilt. The app shows the real delay to the speaker (from `AudioTrack.getTimestamp`).
- The PC records the phone sink with a 5 ms latency request (PipeWire lowers its quantum to match).
- Camera: the camera renders straight into the hardware encoder (no copies); encoder set to
  realtime / low latency, no B-frames, stabilization off. On the PC, ffmpeg decodes with frame
  threading off and an access-unit delimiter after every frame, so a frame shows ~1 frame after
  it arrives instead of ~14.

## PC (`pc/periphone`)

One static x86-64 binary (Go, libopus linked in): it runs on any Linux distro, needs no
install step, no PipeWire config and no Python. `pc/periphone/build.sh` builds it (Go 1.24+,
a C compiler, cmake; downloads libopus with a pinned checksum).

- **Sound**: talks to the sound server over the PulseAudio protocol, so it works on PipeWire
  (pipewire-pulse) and plain PulseAudio. While it runs it adds three devices and removes them on
  exit: output **"Phone speaker"** (PC audio → phone), input **"Phone mic"**, and **"Phone audio"**
  (the phone's own sound: apps can record it, and it also plays on your speakers). It makes the
  phone the default output / input and puts your previous ones back on exit; if it ever dies
  without cleaning up, the next start removes the leftovers.
- **Window**: status, the link, each stream with an on/off switch and a live level meter,
  camera, codec / encryption / buffer / mic mode, the PIN (copy, new), whether adb reaches the
  phone, warnings and a log. It is a small web UI on 127.0.0.1, opened as an app window by Chromium /
  Chrome / Brave / Edge / Vivaldi if installed, else in your browser; running `periphone` again
  reopens it. The browser gets a one-time link that sets an HttpOnly cookie (the session token is
  never on a command line or in the log); it only answers connections owned by your own Linux
  user; actions must come from its own origin with a custom header, so neither web pages nor
  other local servers' pages can drive it; other host names are refused.
- **Tray icon**: color = state (gray stopped, amber waiting, green connected, red error); click to
  open the window; the menu switches the link and streams, starts/stops, shows the PIN. Works with
  KDE, XFCE, Cinnamon, sway/waybar...; GNOME needs the AppIndicator extension (the window works
  without a tray). Choices are remembered (`~/.config/periphone/settings.json`).
- **Crash-safe**: the devices it replaced as defaults are written down while it runs; if it is
  killed, the next start removes the leftover devices and puts those defaults back.
- **Optional, only for what needs them**: `adb` (USB, and starting the app on the phone);
  `ffmpeg` + the `v4l2loopback` kernel module (camera:
  `sudo modprobe v4l2loopback exclusive_caps=1 card_label="Phone Camera"`); BlueZ (Bluetooth);
  NetworkManager (`nmcli`, joins Bluetooth tethering).
- `periphone install [--autostart]` copies it to `~/.local/bin`, adds it to the app menu (and to
  login, tray only), and removes the old shell/Python version and its PipeWire configs if present.
  `periphone uninstall` undoes it (settings and PIN stay).
- The camera receiver holds the webcam device open the whole session (black frames while the phone
  isn't sending), so apps don't lose the camera when you switch front/back or turn the phone.

The Wi-Fi / Bluetooth protocol is documented in `src/dev/gabriwar/periphone/Proto.java`
(`pc/periphone/proto.go` must stay in sync).

## Usage

```sh
periphone                   # window + tray icon, detached: closing the terminal or the
                              # window keeps it running in the tray (log: $XDG_RUNTIME_DIR/periphone.log)
periphone --background      # tray only (what the login autostart uses)
periphone --no-tray         # terminal, Ctrl-C to stop
periphone --wifi            # Wi-Fi (also: --usb, --pan [--pan-mac MAC], --bt)
periphone -c front          # also start the front camera (or: -c back)
periphone -a                # also the phone's own sound ("Phone audio")
periphone -b 40 -m 7        # 40 ms buffer, mic in voice-call mode (echo cancelling)
periphone --no-mic          # only PC audio -> phone (also --no-out)
periphone --codec pcm       # raw PCM on Wi-Fi (no codec; clicks if packets get lost)
periphone pin               # the PIN to type in the app (pin new: change it)
periphone stop              # stop and put the audio back where it was
```

With adb reaching the phone (cable or wireless debugging) it sets the app up and starts it. In
games/Discord pick the input **"Phone mic"**; the camera is the v4l2loopback device.

## Latency notes

Bluetooth headphones (A2DP/AAC) alone cost ~150–250 ms on Android; the app adds a few ms on
top. For truly low delay: USB link, wired headphones on the phone and the **Lowest** preset.
