package com.ramphoenix.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.List;

/**
 * Now-playing card. Follows whichever media session is playing (Spotify, the
 * SYU radio/music apps, anything that publishes a MediaSession), shows art,
 * title, artist, a progress bar and prev/play/next, and tints the card from
 * the album art the way the CarPlay-style reference does.
 *
 * Requires the notification-listener grant (see MediaListenerService); until
 * it is granted the card explains that and opens the settings page on tap.
 */
public class MediaWidget {

    private final Activity act;
    private final View card;
    private final ImageView art, srcIcon, btnPrev, btnPlay, btnNext;
    private final TextView srcName, title, artist;
    private final ProgressBar progress;

    private final MediaSessionManager msm;
    private final ComponentName listener;
    private MediaController current;
    private boolean needsAccess;
    private final float density;
    /** The unit's own audio source (radio / BT music / Android) over the SYU IPC. */
    private final AudioSource src;
    private final TextView pillBt, pillRadio, pillApps;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            renderProgress();
            ui.postDelayed(this, 1000);
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            this::bind;

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { render(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { render(); }
        @Override public void onSessionDestroyed() { refresh(); }
    };

    public MediaWidget(Activity act, View card) {
        this.act = act;
        this.card = card;
        density = act.getResources().getDisplayMetrics().density;
        art = card.findViewById(R.id.media_art);
        srcIcon = card.findViewById(R.id.media_src_icon);
        srcName = card.findViewById(R.id.media_src_name);
        title = card.findViewById(R.id.media_title);
        artist = card.findViewById(R.id.media_artist);
        progress = card.findViewById(R.id.media_progress);
        btnPrev = card.findViewById(R.id.media_prev);
        btnPlay = card.findViewById(R.id.media_play);
        btnNext = card.findViewById(R.id.media_next);

        msm = (MediaSessionManager) act.getSystemService(Context.MEDIA_SESSION_SERVICE);
        listener = new ComponentName(act, MediaListenerService.class);

        src = new AudioSource(act, this::render);
        pillBt = card.findViewById(R.id.media_src_bt);
        pillRadio = card.findViewById(R.id.media_src_radio);
        pillApps = card.findViewById(R.id.media_src_apps);
        btnPrev.setOnClickListener(v -> {
            if (src.isBtMusic()) src.btPrev();
            else if (src.isRadio()) src.radioSeekDown();
            else if (current != null) current.getTransportControls().skipToPrevious();
        });
        btnNext.setOnClickListener(v -> {
            if (src.isBtMusic()) src.btNext();
            else if (src.isRadio()) src.radioSeekUp();
            else if (current != null) current.getTransportControls().skipToNext();
        });
        btnPlay.setOnClickListener(v -> togglePlay());
        // Beau, 2026-10-25: the card is a controller, not a shortcut. A tap only
        // fixes the notification-access grant when that is missing; long-press
        // opens the source app for the rare case the card cannot do something.
        card.setOnClickListener(v -> { if (needsAccess) onCardTap(); });
        card.setOnLongClickListener(v -> { onCardTap(); return true; });

        pillBt.setOnClickListener(v -> src.selectBt());
        pillRadio.setOnClickListener(v -> src.selectRadio());
        pillApps.setOnClickListener(v -> src.selectAndroid());
        tint(null);
    }

    public void onResume() {
        src.connect();
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listener);
            needsAccess = false;
            bind(msm.getActiveSessions(listener));
        } catch (SecurityException e) {
            needsAccess = true;
            renderNeedsAccess();
        }
        ui.removeCallbacks(progressTick);
        ui.post(progressTick);
    }

    public void onPause() {
        src.disconnect();
        ui.removeCallbacks(progressTick);
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Exception ignored) {}
        setController(null);
    }

    private void refresh() {
        try { bind(msm.getActiveSessions(listener)); } catch (SecurityException ignored) {}
    }

    /** Prefer the session that is actually playing; otherwise the most recent. */
    private void bind(List<MediaController> sessions) {
        MediaController pick = null;
        if (sessions != null) {
            for (MediaController c : sessions) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
            }
            if (pick == null && !sessions.isEmpty()) pick = sessions.get(0);
        }
        setController(pick);
        render();
    }

    private void setController(MediaController c) {
        if (current != null && (c == null || !sameSession(current, c))) {
            current.unregisterCallback(cb);
            current = null;
        }
        if (c != null && current == null) {
            current = c;
            current.registerCallback(cb, ui);
        }
    }

    private static boolean sameSession(MediaController a, MediaController b) {
        return a.getSessionToken().equals(b.getSessionToken());
    }

    private void togglePlay() {
        if (src.isBtMusic()) { src.btPlayPause(); return; }
        if (current == null) {
            // Nothing has a session yet: if a phone is paired, start BT music;
            // otherwise ask the last media app to resume via the media button.
            if (src.isConnected() && src.btConnected()) { src.selectBt(); return; }
            android.media.AudioManager am = (android.media.AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                android.view.KeyEvent k = new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY);
                am.dispatchMediaKeyEvent(k);
                am.dispatchMediaKeyEvent(android.view.KeyEvent.changeAction(k, android.view.KeyEvent.ACTION_UP));
            }
            return;
        }
        PlaybackState s = current.getPlaybackState();
        if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
            current.getTransportControls().pause();
        } else {
            current.getTransportControls().play();
        }
    }

    private void onCardTap() {
        if (needsAccess) {
            try {
                act.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {}
            return;
        }
        if (current != null) {
            Intent i = act.getPackageManager().getLaunchIntentForPackage(current.getPackageName());
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); act.startActivity(i); return; }
        }
        Intent music = Dock.musicIntent(act);
        if (music != null) { music.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); act.startActivity(music); }
    }

    /** SPLIT pane (large) vs FULL-mode card (small): scale the text and controls. */
    public void setLarge(boolean large) {
        // SPLIT pane: cap the art so the text and controls keep most of the width
        // (Beau, 2026-10-25: the BT glyph filled the whole pane).
        ViewGroup.LayoutParams ap = art.getLayoutParams();
        ap.height = large ? dp(150) : ViewGroup.LayoutParams.MATCH_PARENT;
        art.setLayoutParams(ap);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, large ? 30 : 22);
        artist.setTextSize(TypedValue.COMPLEX_UNIT_SP, large ? 20 : 14);
        srcName.setTextSize(TypedValue.COMPLEX_UNIT_SP, large ? 16 : 13);
        int play = dp(large ? 68 : 56), side = dp(large ? 52 : 44);
        ViewGroup.LayoutParams lp = btnPlay.getLayoutParams();
        lp.width = play; lp.height = play; btnPlay.setLayoutParams(lp);
        int pad = dp(large ? 14 : 12);
        btnPlay.setPadding(pad, pad, pad, pad);
        for (ImageView b : new ImageView[]{btnPrev, btnNext}) {
            ViewGroup.LayoutParams p = b.getLayoutParams();
            p.width = side; p.height = side; b.setLayoutParams(p);
        }
        ViewGroup.MarginLayoutParams pp = (ViewGroup.MarginLayoutParams) progress.getLayoutParams();
        pp.topMargin = dp(large ? 22 : 10); pp.height = dp(large ? 6 : 4); progress.setLayoutParams(pp);
        View controls = card.findViewById(R.id.media_controls);
        ViewGroup.MarginLayoutParams cp = (ViewGroup.MarginLayoutParams) controls.getLayoutParams();
        cp.topMargin = dp(large ? 22 : 10); controls.setLayoutParams(cp);
        ViewGroup.MarginLayoutParams ip = (ViewGroup.MarginLayoutParams) card.findViewById(R.id.media_info).getLayoutParams();
        ip.leftMargin = dp(large ? 24 : 17); ip.rightMargin = dp(large ? 64 : 6);
        card.findViewById(R.id.media_info).setLayoutParams(ip);
        int cpad = dp(large ? 24 : 14);
        card.setPadding(cpad, cpad, cpad, cpad);
    }

    private int dp(int v) { return Math.round(v * density); }

    // ---- rendering ----

    private void renderNeedsAccess() {
        srcIcon.setImageDrawable(null);
        srcName.setText("");
        title.setText(R.string.media_needs_access);
        artist.setText(R.string.media_tap_to_grant);
        art.setImageResource(R.drawable.ic_media_fallback);
        progress.setProgress(0);
        btnPlay.setImageResource(R.drawable.ic_m_play_arrow);
        tint(null);
    }

    /** Album art fills the tile; a glyph (BT / radio / fallback) sits small in the middle of it. */
    private void setArt(Bitmap bmp, int glyphRes) {
        if (bmp != null) {
            art.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            art.setPadding(0, 0, 0, 0);
            art.setImageBitmap(bmp);
        } else {
            art.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            int p = Math.max(dp(18), art.getHeight() / 3);
            art.setPadding(p, p, p, p);
            art.setImageResource(glyphRes);
        }
    }

    private void renderPills() {
        boolean ipc = src.isConnected();
        for (TextView p : new TextView[]{pillBt, pillRadio, pillApps}) p.setAlpha(ipc ? 1f : 0.4f);
        pillBt.setSelected(src.isBtMusic());
        pillRadio.setSelected(src.isRadio());
        pillApps.setSelected(ipc && !src.isHardwareSource());
    }

    /** Bluetooth music from the phone: metadata and transport come over the BT module, not a MediaSession. */
    private void renderBt() {
        srcIcon.setImageResource(R.drawable.ic_bluetooth);
        srcName.setText(R.string.bluetooth_audio);
        String t = src.btTitle(), a = src.btArtist();
        if (!src.btConnected()) {
            title.setText(R.string.bt_not_connected);
            artist.setText("");
        } else {
            title.setText(t != null && !t.isEmpty() ? t : act.getString(R.string.bluetooth_audio));
            artist.setText(a != null ? a : act.getString(R.string.bt_connected_hint));
        }
        setArt(null, R.drawable.ic_bluetooth);
        tint(null);
        btnPlay.setImageResource(src.btPlaying() ? R.drawable.ic_m_pause : R.drawable.ic_m_play_arrow);
        renderProgress();
    }

    private void renderRadio() {
        srcIcon.setImageResource(R.drawable.ic_radio);
        srcName.setText(R.string.radio);
        String f = src.radioLabel(), ps = src.radioStation();
        title.setText(f != null ? f : act.getString(R.string.radio));
        artist.setText(ps != null ? ps : act.getString(R.string.radio_hint));
        setArt(null, R.drawable.ic_radio);
        tint(null);
        btnPlay.setImageResource(R.drawable.ic_m_pause);
        progress.setProgress(0);
    }

    private void render() {
        renderPills();
        if (src.isBtMusic()) { renderBt(); return; }
        if (src.isRadio()) { renderRadio(); return; }
        if (needsAccess) { renderNeedsAccess(); return; }
        if (current == null) {
            srcIcon.setImageDrawable(null);
            srcName.setText("");
            title.setText(R.string.nothing_playing);
            artist.setText(R.string.open_media_hint);
            setArt(null, R.drawable.ic_media_fallback);
            progress.setProgress(0);
            btnPlay.setImageResource(R.drawable.ic_m_play_arrow);
            tint(null);
            return;
        }

        // source app
        String pkg = current.getPackageName();
        PackageManager pm = act.getPackageManager();
        try {
            srcIcon.setImageDrawable(pm.getApplicationIcon(pkg));
            srcName.setText(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            srcIcon.setImageDrawable(null);
            srcName.setText(pkg);
        }

        // track
        MediaMetadata md = current.getMetadata();
        String t = str(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String a = str(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        title.setText(t != null ? t : act.getString(R.string.nothing_playing));
        artist.setText(a != null ? a : "");

        Bitmap bmp = null;
        if (md != null) {
            bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        setArt(bmp, R.drawable.ic_media_fallback);
        tint(bmp);

        PlaybackState s = current.getPlaybackState();
        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        btnPlay.setImageResource(playing ? R.drawable.ic_m_pause : R.drawable.ic_m_play_arrow);
        renderProgress();
    }

    private void renderProgress() {
        if (src.isBtMusic()) {
            int pos = src.btPos(), dur = src.btDur();
            progress.setProgress(dur > 0 && pos >= 0 ? (int) Math.min(1000L, pos * 1000L / dur) : 0);
            return;
        }
        if (current == null) { progress.setProgress(0); return; }
        MediaMetadata md = current.getMetadata();
        PlaybackState s = current.getPlaybackState();
        long dur = md != null ? md.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        if (s == null || dur <= 0) { progress.setProgress(0); return; }
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        pos = Math.max(0, Math.min(pos, dur));
        progress.setProgress((int) (pos * 1000 / dur));
    }

    private static String str(MediaMetadata md, String... keys) {
        if (md == null) return null;
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return null;
    }

    /** Card background: the same glass as every other card (Beau, 2026-10-25: the
     *  purple gradient did not fit the dashboard). Art colour is no longer used. */
    private void tint(Bitmap bmp) {
        card.setBackgroundResource(R.drawable.glass_card);
    }

    private static int withAlpha(int c, int alpha) {
        return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c));
    }
}
