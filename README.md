# USB Audio Bridge

Use an Android phone as the PC's sound card **and webcam over the USB cable**: the PC's audio
plays on the phone (and from there on Bluetooth or wired headphones), the phone mic becomes a
microphone on the PC, and the phone camera (front or back) becomes a webcam.
Made for a machine with no Bluetooth and no analog output.

```
PC                                     USB cable (adb reverse)             Phone (app)
phone_speaker ── TCP 127.0.0.1:12345 ─────────────────────────────▶ AudioTrack ─▶ headphones
phone_mic     ◀─ TCP 127.0.0.1:12346 ◀───────────────────────────── AudioRecord ◀ mic
/dev/videoN   ◀─ phone-cam :12347    ◀── H.264 ──────────────────── Camera2 + HW encoder
```

Audio format is fixed: PCM S16LE 48 kHz (stereo out, mono mic). Fixed on purpose: a player at
the wrong rate pitches the audio down (48k played at 44.1k).

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
- **Phone camera → PC**: front/back switch (live, no reconnect), preview, resolution, frame
  rate, quality, rotation (auto follows how the phone is held) and mirror.
- **Latency presets** (Lowest / Balanced / Stable) with fine-tune sliders.
- Changing a setting restarts only the stream it affects.
- Runs as a foreground service: the screen can turn off.

Builds without Gradle (Android SDK with any recent `platforms;android-NN` + `build-tools`,
JDK 17+). `build.sh` picks the newest installed ones.

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
- `bin/phone-bridge`, `bin/phone-cam` (and the legacy `phone-mic` / `phone-speaker`) to `~/.local/bin`.

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
