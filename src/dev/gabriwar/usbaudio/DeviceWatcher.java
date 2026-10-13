package dev.gabriwar.usbaudio;

import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;

/** Avisa quando entra/sai dispositivo de audio (fone BT conectou, cabo saiu...). */
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
