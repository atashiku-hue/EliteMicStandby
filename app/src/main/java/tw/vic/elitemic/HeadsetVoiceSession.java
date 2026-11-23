package tw.vic.elitemic;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.os.SystemClock;
import java.util.List;

/** Requests the headset's HFP voice session. Never opens an Android recorder. */
final class HeadsetVoiceSession implements BluetoothProfile.ServiceListener {
    enum State { UNAVAILABLE, CONNECTING, CONNECTED, EXTERNAL, BUSY }
    private static final long PROFILE_GRACE_MS = 2200;
    private static final long AUDIO_GRACE_MS = 6000;
    private final BluetoothAdapter adapter;
    private final Runnable changed;
    private final boolean directVoiceAllowed;
    private BluetoothHeadset profile;
    private BluetoothDevice attempted;
    private BluetoothDevice owned;
    private boolean connectedOnce, closed, binding;
    private long profileDeadline, audioDeadline, connectedSince;
    private boolean recoveryUsed;
    private static final long STABLE_AUDIO_MS = 30000;

    boolean ownsSession() { return owned != null; }

    // A rapid reconnect may be complete by the time the queued device snapshot is read.
    boolean disconnected(BluetoothDevice device) {
        if (device == null || !device.equals(attempted)) return false;
        owned = attempted = null;
        connectedOnce = recoveryUsed = false;
        return true;
    }

    HeadsetVoiceSession(BluetoothAdapter adapter, Runnable changed, boolean directVoiceAllowed) {
        this.directVoiceAllowed=directVoiceAllowed;
        this.adapter = adapter;
        this.changed = changed;
    }
    void bind(Context context) {
        if (adapter == null || closed || binding || profile != null) return;
        profileDeadline = SystemClock.elapsedRealtime() + PROFILE_GRACE_MS;
        binding = adapter.getProfileProxy(context, this, BluetoothProfile.HEADSET);
    }
    @Override public void onServiceConnected(int id, BluetoothProfile proxy) {
        if (id != BluetoothProfile.HEADSET) return;
        if (closed) {
            adapter.closeProfileProxy(id, proxy);
            return;
        }
        profile = (BluetoothHeadset) proxy;
        changed.run();
    }
    @Override public void onServiceDisconnected(int id) {
        if (id != BluetoothProfile.HEADSET) return;
        profile = null;
        owned = attempted = null;
        connectedOnce = recoveryUsed = false;
        changed.run();
    }
    private static BluetoothDevice match(List<BluetoothDevice> devices, AudioDeviceInfo route) {
        String address = route == null ? "" : route.getAddress();
        if (address != null && BluetoothAdapter.checkBluetoothAddress(address.toUpperCase(java.util.Locale.ROOT))
                && !"02:00:00:00:00:00".equals(address) && !"00:00:00:00:00:00".equals(address)) {
            for (BluetoothDevice device : devices)
                if (address.equalsIgnoreCase(device.getAddress())) return device;
            return null;
        }
        return devices.size() == 1 ? devices.get(0) : null;
    }

    boolean matchesConnectedHeadset(BluetoothDevice device, AudioDeviceInfo route) {
        try {
            return device != null && profile != null && device.equals(match(profile.getConnectedDevices(), route));
        } catch (SecurityException | IllegalStateException unavailable) {
            return false;
        }
    }

    boolean audioConnected(AudioDeviceInfo route) {
        if (profile == null) return false;
        try {
            BluetoothDevice device = match(profile.getConnectedDevices(), route);
            return device != null && profile.isAudioConnected(device);
        } catch (SecurityException revoked) {
            return false;
        }
    }

    State evaluate(AudioDeviceInfo route) {
        if (profile == null) return binding && SystemClock.elapsedRealtime() < profileDeadline
            ? State.CONNECTING : State.UNAVAILABLE;
        try {
            List<BluetoothDevice> connected = profile.getConnectedDevices();
            BluetoothDevice device = match(connected, route);
            for (BluetoothDevice other : connected) {
                if (!other.equals(device) && !other.equals(owned) && profile.isAudioConnected(other)) {
                    reset();
                    return State.BUSY;
                }
            }
            if (device == null) {
                reset();
                return State.UNAVAILABLE;
            }
            if (attempted != null && !device.equals(attempted)) reset();
            if (profile.isAudioConnected(device)) {
                if (!device.equals(owned)) return State.EXTERNAL;
                if (!connectedOnce) connectedSince = SystemClock.elapsedRealtime();
                connectedOnce = true;
                return State.CONNECTED;
            }
            if (device.equals(owned)) {
                if (!connectedOnce && SystemClock.elapsedRealtime() < audioDeadline) return State.CONNECTING;
                if (!connectedOnce) {
                    releaseOwned();
                    return State.UNAVAILABLE;
                }
                // The confirmed SCO transport has already ended; an extra stop could race the new request.
                owned = null;
                connectedOnce = false;
                if (recoveryUsed && SystemClock.elapsedRealtime() - connectedSince < STABLE_AUDIO_MS)
                    return State.UNAVAILABLE;
                recoveryUsed = true;
                attempted = null;
            }
            if (!directVoiceAllowed || device.equals(attempted)) return State.UNAVAILABLE;
            attempted = device;
            if (!profile.isVoiceRecognitionSupported(device) || !startOnSupportedPlatform(device))
                return State.UNAVAILABLE;
            owned = device;
            audioDeadline = SystemClock.elapsedRealtime() + AUDIO_GRACE_MS;
            return State.CONNECTING;
        } catch (SecurityException denied) {
            reset();
            return State.UNAVAILABLE;
        }
    }
    // Android 16 changed the API contract to BLUETOOTH_CONNECT. The compileSdk 34
    // lint metadata also lists MODIFY_PHONE_STATE. Never call this on Android 15 or earlier.
    @android.annotation.SuppressLint("MissingPermission")
    private boolean startOnSupportedPlatform(BluetoothDevice device) {
        return directVoiceAllowed && profile.startVoiceRecognition(device);
    }
    private void releaseOwned() {
        BluetoothDevice device = owned;
        owned = null;
        connectedOnce = false;
        if (device != null && profile != null) {
            try { profile.stopVoiceRecognition(device); } catch (SecurityException | IllegalStateException unavailable) { }
        }
    }
    void reset() {
        releaseOwned();
        attempted = null;
        recoveryUsed = false;
    }
    void close() {
        closed = true;
        reset();
        if (profile != null && adapter != null) adapter.closeProfileProxy(BluetoothProfile.HEADSET, profile);
        profile = null;
    }
}