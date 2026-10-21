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

        btnPrev.setOnClickListener(v -> { if (current != null) current.getTransportControls().skipToPrevious(); });
        btnNext.setOnClickListener(v -> { if (current != null) current.getTransportControls().skipToNext(); });
        btnPlay.setOnClickListener(v -> togglePlay());
        card.setOnClickListener(v -> onCardTap());
        tint(null);
    }

    public void onResume() {
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
        if (current == null) { onCardTap(); return; }
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

    // ---- rendering ----

    private void renderNeedsAccess() {
        srcIcon.setImageDrawable(null);
        srcName.setText("");
        title.setText(R.string.media_needs_access);
        artist.setText(R.string.media_tap_to_grant);
        art.setImageResource(R.drawable.ic_media_fallback);
        progress.setProgress(0);
        btnPlay.setImageResource(R.drawable.ic_play);
        tint(null);
    }

    private void render() {
        if (needsAccess) { renderNeedsAccess(); return; }
        if (current == null) {
            srcIcon.setImageDrawable(null);
            srcName.setText("");
            title.setText(R.string.nothing_playing);
            artist.setText(R.string.open_media_hint);
            art.setImageResource(R.drawable.ic_media_fallback);
            progress.setProgress(0);
            btnPlay.setImageResource(R.drawable.ic_play);
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
        if (bmp != null) art.setImageBitmap(bmp); else art.setImageResource(R.drawable.ic_media_fallback);
        tint(bmp);

        PlaybackState s = current.getPlaybackState();
        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        renderProgress();
    }

    private void renderProgress() {
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

    /** Card background: gradient from the album art's dominant colour, or the default purple/teal. */
    private void tint(Bitmap bmp) {
        int start, end;
        if (bmp != null) {
            int c = ArtColor.dominant(bmp, act.getColor(R.color.media_grad_start));
            start = withAlpha(ArtColor.toneDown(c, 0.80f), 0x8C);
            end = withAlpha(ArtColor.toneDown(c, 0.45f), 0x73);
        } else {
            start = act.getColor(R.color.media_grad_start);
            end = act.getColor(R.color.media_grad_end);
        }
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{start, end});
        g.setCornerRadius(24f * density);
        g.setStroke(Math.round(density), act.getColor(R.color.glass_stroke));
        card.setBackground(g);
    }

    private static int withAlpha(int c, int alpha) {
        return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c));
    }
}
