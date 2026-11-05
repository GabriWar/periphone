package dev.gabriwar.periphone;

import android.media.AudioDeviceInfo;
import android.media.projection.MediaProjection;
import android.view.Surface;

import java.net.InetAddress;

/** State shared between the service and the screen (same process). */
final class Stats {
    private Stats() {}

    static volatile boolean running;

    // samples for the spectrograms (after mute/gain: shows what really goes out / comes in)
    static final SpectrumTap spkTap = new SpectrumTap();
    static final SpectrumTap micTap = new SpectrumTap();
    static final SpectrumTap capTap = new SpectrumTap();   // phone audio -> PC

    // mute: the connection stays up, only silence is sent
    static volatile boolean muteSpk;
    static volatile boolean muteMic;

    // software gain, 1.0 = 100%
    static volatile float spkGain = 1f;
    static volatile float micGain = 1f;

    // device chosen on the phone (null = automatic); the pipes switch live
    static volatile AudioDeviceInfo outDevice;
    static volatile AudioDeviceInfo inDevice;

    static volatile boolean spkConnected;
    static volatile long spkBytes;
    static volatile int spkUnderruns;
    static volatile long spkDroppedMs;
    static volatile int spkQueuedMs;      // delay sitting in our AudioTrack queue
    static volatile int spkTargetMs;      // cushion the queue is kept around (adapts on Wi-Fi/Bluetooth)
    static volatile int spkLatencyMs;     // queue + what the Android audio stack reports (-1 = unknown)
    static volatile String spkError = "";

    static volatile boolean micConnected;
    static volatile long micBytes;
    static volatile int micPeak;          // 0..32767, decaying
    static volatile String micError = "";

    // camera: the facing is switched live by the pipe; the preview surface comes from the screen
    static volatile boolean camFront;
    static volatile Surface camPreview;
    static volatile boolean camConnected;
    static volatile long camBytes;
    static volatile long camFrames;
    static volatile String camInfo = "";  // "1280x720 30 fps" of the running stream
    static volatile String camError = "";

    // phone sound -> PC: needs the screen-capture consent (the service holds the projection)
    static volatile MediaProjection projection;
    static volatile boolean capConnected;
    static volatile long capBytes;
    static volatile String capError = "";

    // Wi-Fi / Bluetooth: where the PC was found, and how the last stream got there
    static volatile InetAddress pcAddr;
    static volatile String linkWhere = "";
    // bumped when the PC changed the settings in the background: the screen re-reads them
    static volatile int prefsPushed;
    // PIN mismatches in a row and when to try again (back-off, see Remote.wrongPin)
    static volatile int pinFails;          // in a row (back-off); a success resets it
    static volatile int pinMismatches;     // total since the user last tapped Start / changed settings
    static volatile long pinRetryAt;
}
