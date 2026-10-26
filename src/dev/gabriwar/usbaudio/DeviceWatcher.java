package dev.gabriwar.usbaudio;

import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;

/** Fires when an audio device comes or goes (BT headset connected, cable unplugged...). */
final class DeviceWatcher extends AudioDeviceCallback {
    private final Runnable onChange;

    DeviceWatcher(Runnable onChange) {
        this.onChange = onChange;
    }

    @Override
    public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
        onChange.run();
    }

    @Override
    public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
        onChange.run();
    }
}
