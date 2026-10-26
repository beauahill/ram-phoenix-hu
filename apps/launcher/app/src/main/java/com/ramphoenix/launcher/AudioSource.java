package com.ramphoenix.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * The head unit's audio source and the Bluetooth-music state, over the SYU
 * ToolkitService IPC (decompile of com.syu.bt / com.syu.ms, 2026-10-25):
 *
 *  - MAIN update/cmd code 0 is the "app id" = the unit's audio source:
 *      1 radio · 2 BT phone · 3 BT music · 8 stock music · 9 video · 10 third-party
 *      app · 0 none. cmd(MAIN, 0, {id}) switches: com.syu.ms moves the DSP to that
 *      channel and takes Android audio focus for hardware sources (radio, BT), or
 *      releases it for Android sources. Nothing selects BT music unless an app asks,
 *      which is why a paired phone played silently with our launcher in front.
 *  - BT module (2) cmds: 0 prev · 1 next · 2 play/pause · 3 pause · 27 play;
 *    updates: 0 title · 1 artist · 26 album · 13 play state (1 = playing) ·
 *    28 position s · 2 duration s · 34 A2DP state (2 = connected) · 9 phone state.
 */
public class AudioSource {

    private static final String TAG = "PhoenixLauncher";

    public static final int APP_NONE = 0, APP_RADIO = 1, APP_BTPHONE = 2, APP_BTAV = 3,
            APP_MUSIC = 8, APP_VIDEO = 9, APP_THIRD = 10;

    private static final int BT_PREV = 0, BT_NEXT = 1, BT_PLAYPAUSE = 2, BT_PLAY = 27;
    private static final int U_TITLE = 0, U_ARTIST = 1, U_ALBUM = 26, U_PLAY = 13, U_POS = 28,
            U_DUR = 2, U_A2DP = 34, U_PHONE = 9;

    public interface Listener { void onAudioSourceChanged(); }

    private final SyuIpc ipc;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private boolean connected;

    private int appId = -1;
    // RADIO module (1): update 0 band (0/1 AM, 65536+n FMn), 1 frequency (FM in 10 kHz units,
    // AM in kHz), 21 stereo, 26 RDS station name. cmd 5/6 seek up/down, 0/1 next/prev preset.
    private static final int R_BAND = 0, R_FREQ = 1, R_STEREO = 21, R_PS = 26;
    private int rBand = -1, rFreq = -1;
    private String rPs;
    private String btTitle, btArtist, btAlbum;
    private int btPlay = -1, btPos = -1, btDur = -1, btA2dp = -1, btPhone = -1;

    public AudioSource(Context ctx, Listener l) {
        listener = l;
        ipc = new SyuIpc(ctx);
        ipc.setConnectionListener(c -> { connected = c; notifyChanged(); });
        ipc.subscribe(SyuIpc.MODULE_MAIN, 0, (code, ints, flts, strs) -> {
            if (ints != null && ints.length > 0 && ints[0] != appId) {
                appId = ints[0];
                Log.i(TAG, "audio source app id -> " + appId);
                notifyChanged();
            }
        });
        SyuIpc.UpdateListener bt = (code, ints, flts, strs) -> {
            String s = strs != null && strs.length > 0 ? strs[0] : null;
            int i = ints != null && ints.length > 0 ? ints[0] : -1;
            switch (code) {
                case U_TITLE:  btTitle = s; break;
                case U_ARTIST: btArtist = s; break;
                case U_ALBUM:  btAlbum = s; break;
                case U_PLAY:   btPlay = i; break;
                case U_POS:    btPos = i; break;
                case U_DUR:    btDur = i; break;
                case U_A2DP:   btA2dp = i; break;
                case U_PHONE:  btPhone = i; break;
                default: return;
            }
            notifyChanged();
        };
        for (int c : new int[]{U_TITLE, U_ARTIST, U_ALBUM, U_PLAY, U_POS, U_DUR, U_A2DP, U_PHONE}) {
            ipc.subscribe(SyuIpc.MODULE_BT, c, bt);
        }
        SyuIpc.UpdateListener radio = (code, ints, flts, strs) -> {
            int i = ints != null && ints.length > 0 ? ints[0] : -1;
            switch (code) {
                case R_BAND: rBand = i; break;
                case R_FREQ: rFreq = i; break;
                case R_PS:   rPs = strs != null && strs.length > 0 ? strs[0] : null; break;
                default: return;
            }
            notifyChanged();
        };
        for (int c : new int[]{R_BAND, R_FREQ, R_PS}) ipc.subscribe(SyuIpc.MODULE_RADIO, c, radio);
    }

    private void notifyChanged() { ui.post(listener::onAudioSourceChanged); }

    public void connect() { ipc.connect(); }
    public void disconnect() { ipc.disconnect(); connected = false; }

    public boolean isConnected() { return connected; }
    public int appId() { return appId; }
    public boolean isBtMusic() { return appId == APP_BTAV; }
    public boolean isRadio() { return appId == APP_RADIO; }
    /** True when the unit is routing a hardware source (radio / BT) rather than Android audio. */
    public boolean isHardwareSource() { return appId == APP_RADIO || appId == APP_BTAV || appId == APP_BTPHONE; }

    public String btTitle() { return btTitle; }
    public String btArtist() { return btArtist; }
    public String btAlbum() { return btAlbum; }
    public boolean btPlaying() { return btPlay == 1; }
    public boolean btConnected() { return btA2dp == 2 || btPhone >= 2; }
    public int btPos() { return btPos; }
    public int btDur() { return btDur; }

    /** Make Bluetooth music the unit's audio source and ask the phone to play. */
    public void selectBt() {
        boolean ok = ipc.cmd(SyuIpc.MODULE_MAIN, 0, new int[]{APP_BTAV});
        Log.i(TAG, "select BT music" + (ok ? "" : " FAILED"));
        ui.postDelayed(() -> ipc.cmd(SyuIpc.MODULE_BT, BT_PLAY, null), 400);
    }

    public void selectRadio() {
        boolean ok = ipc.cmd(SyuIpc.MODULE_MAIN, 0, new int[]{APP_RADIO});
        Log.i(TAG, "select radio" + (ok ? "" : " FAILED"));
    }

    /** Hand the DSP back to Android audio (Spotify and friends). */
    public void selectAndroid() {
        boolean ok = ipc.cmd(SyuIpc.MODULE_MAIN, 0, new int[]{APP_THIRD});
        Log.i(TAG, "select android audio" + (ok ? "" : " FAILED"));
    }

    /** "FM 101.9" / "AM 1070", or null before the first push. */
    public String radioLabel() {
        if (rFreq < 0) return null;
        boolean fm = rBand >= 65536;
        if (fm) return String.format(java.util.Locale.US, "FM %.1f", rFreq / 100f);
        return "AM " + rFreq;
    }
    public String radioStation() { return rPs != null && !rPs.trim().isEmpty() ? rPs.trim() : null; }
    public void radioSeekUp() { ipc.cmd(SyuIpc.MODULE_RADIO, 5, null); }
    public void radioSeekDown() { ipc.cmd(SyuIpc.MODULE_RADIO, 6, null); }
    public void radioPresetNext() { ipc.cmd(SyuIpc.MODULE_RADIO, 0, null); }
    public void radioPresetPrev() { ipc.cmd(SyuIpc.MODULE_RADIO, 1, null); }

    public void btPrev() { ipc.cmd(SyuIpc.MODULE_BT, BT_PREV, null); }
    public void btNext() { ipc.cmd(SyuIpc.MODULE_BT, BT_NEXT, null); }
    public void btPlayPause() { ipc.cmd(SyuIpc.MODULE_BT, BT_PLAYPAUSE, null); }
}
