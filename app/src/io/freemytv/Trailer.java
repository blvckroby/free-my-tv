package io.freemytv;

import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The focused title's trailer, playing inside its cover after about 1 s on it, then grown into the title card
 * (Spotlight) by HomeView with place().
 *
 * Android's own video player on a SurfaceView, not a WebView: on MediaTek TVs (TCL Google TV) the video decoder
 * sends its picture to the TV's hardware video plane, which the TV places only for SurfaceViews; a WebView got empty
 * placeholder buffers (sound, no picture). YouTube's streams come from its player API as its Android VR app asks
 * for them (direct links, no signature to work out): the best H.264 video up to 1080p and the AAC audio, in two
 * players kept in step, or the single 360p file with both when that's all there is.
 */
final class Trailer {
    private static final long PREPARE_MS = 250, NO_SHOW_MS = 15_000, SYNC_MS = 4000;
    private static final ExecutorService ASK = Executors.newSingleThreadExecutor();
    private static final String VR_VERSION = "1.62.27";
    private static final String VR_AGENT = "com.google.android.apps.youtube.vr.oculus/" + VR_VERSION
            + " (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";
    private static volatile int screenHeight = 1080;

    private final HomeView host;
    private final Recs recs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final RectF rect = new RectF();
    private SurfaceView surface;
    private boolean surfaceReady;
    private MediaPlayer video, audio;
    private boolean videoPrepared, audioPrepared, started;
    private boolean playing;        // the picture is on screen
    private String wanted;          // "kind:id" of the focused title, or null
    private Recs.Pick wantedPick;
    private int generation;         // bumped on every stop: late answers from an older title are dropped

    Trailer(HomeView host, Recs recs) {
        this.host = host;
        this.recs = recs;
    }

    /** The title focused now (null: none). A new one stops the old trailer and starts the count again. */
    void want(Recs.Pick p) {
        String key = p == null || p.id == 0 ? null : p.kind + ":" + p.id;
        if (key == null ? wanted == null : key.equals(wanted)) return;
        stop();
        wanted = key;
        wantedPick = p;
        if (key != null && Prefs.trailers()) ui.postDelayed(prepare, PREPARE_MS);
    }

    private final Runnable prepare = new Runnable() {
        @Override public void run() {
            final int gen = generation;
            final Recs.Pick p = wantedPick;
            if (host.getHeight() > 0) screenHeight = host.getHeight();
            ASK.execute(new Runnable() {
                @Override public void run() {
                    String found = recs.trailer(p);
                    String[] ids = found == null || found.isEmpty() ? new String[0] : found.split(",");
                    String[] streams = null;
                    for (String id : ids) {
                        if (gen != generation) return;
                        try {
                            streams = streams(id.replaceAll("[^A-Za-z0-9_-]", ""));
                            AppLog.w("Trailer: " + id + (streams[1] == null ? " as one " + streams[2] + "p file" : " as H.264 " + streams[2] + "p + audio"));
                            break;
                        } catch (Exception e) {
                            AppLog.w("Trailer: " + id + ": " + e.getMessage());
                        }
                    }
                    if (ids.length == 0) AppLog.w("Trailer: none on TMDB for " + wanted);
                    final String[] got = streams;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (gen != generation) return;
                            if (got == null) {
                                host.trailerNone(p);        // the title card opens with the backdrop
                                return;
                            }
                            play(got, 0);
                        }
                    });
                }
            });
        }
    };

    /** {video URL, audio URL or null, height}: from YouTube's player API, as its Android VR app asks. */
    private static String[] streams(String id) throws Exception {
        JSONObject client = new JSONObject().put("clientName", "ANDROID_VR").put("clientVersion", VR_VERSION)
                .put("deviceMake", "Oculus").put("deviceModel", "Quest 3").put("androidSdkVersion", 32)
                .put("osName", "Android").put("osVersion", "12L")
                .put("hl", Ui.locale().getLanguage()).put("gl", region());
        JSONObject body = new JSONObject().put("context", new JSONObject().put("client", client))
                .put("videoId", id).put("contentCheckOk", true).put("racyCheckOk", true);
        HttpURLConnection c = (HttpURLConnection) new URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false").openConnection();
        JSONObject r;
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", VR_AGENT);
            c.setRequestProperty("X-YouTube-Client-Name", "28");
            c.setRequestProperty("X-YouTube-Client-Version", VR_VERSION);
            c.setRequestProperty("Origin", "https://www.youtube.com");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes("UTF-8"));
            }
            if (c.getResponseCode() != 200) throw new Exception("YouTube answered HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream all = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) all.write(buffer, 0, n);
                r = new JSONObject(all.toString("UTF-8"));
            }
        } finally {
            c.disconnect();
        }
        JSONObject status = r.optJSONObject("playabilityStatus");
        String state = status == null ? "?" : status.optString("status");
        if (!"OK".equals(state)) throw new Exception("not playable (" + state + ": " + (status == null ? "" : status.optString("reason")) + ")");
        JSONObject data = r.optJSONObject("streamingData");
        if (data == null) throw new Exception("no streams");
        int screen = Math.min(1080, Math.max(720, screenHeight));
        String bestVideo = null, bestAudio = null;
        int bestHeight = 0, bestBitrate = 0;
        JSONArray adaptive = data.optJSONArray("adaptiveFormats");
        for (int i = 0; adaptive != null && i < adaptive.length(); i++) {
            JSONObject f = adaptive.optJSONObject(i);
            if (f == null || f.optString("url").isEmpty()) continue;
            String mime = f.optString("mimeType");
            if (mime.startsWith("video/mp4") && mime.contains("avc1")) {
                int h = f.optInt("height");
                if (h <= screen && h > bestHeight) {
                    bestHeight = h;
                    bestVideo = f.optString("url");
                }
            } else if (mime.startsWith("audio/mp4")) {
                int b = f.optInt("bitrate");
                if (b > bestBitrate) {
                    bestBitrate = b;
                    bestAudio = f.optString("url");
                }
            }
        }
        if (bestVideo != null && bestAudio != null && bestHeight >= 480) {
            return new String[] {bestVideo, bestAudio, String.valueOf(bestHeight), single(data)};
        }
        String one = single(data);
        if (one != null) return new String[] {one, null, "360", null};
        if (bestVideo != null && bestAudio != null) return new String[] {bestVideo, bestAudio, String.valueOf(bestHeight), null};
        throw new Exception("no H.264 stream");
    }

    /** The single file with picture and sound (360p), or null. */
    private static String single(JSONObject data) {
        JSONArray single = data.optJSONArray("formats");
        for (int i = 0; single != null && i < single.length(); i++) {
            JSONObject f = single.optJSONObject(i);
            if (f != null && !f.optString("url").isEmpty() && f.optString("mimeType").startsWith("video/mp4")) return f.optString("url");
        }
        return null;
    }

    private static String region() {
        String r = Config.TMDB_REGION.trim();
        return r.length() == 2 ? r.toUpperCase(java.util.Locale.ROOT) : Ui.locale().getCountry();
    }

    /** Both players prepare unseen; the picture goes on its SurfaceView once they're ready. */
    private void play(final String[] streams, final int waits) {
        if (!host.trailerRect(rect)) {
            // The card is still opening from poster to cover: a moment later (for up to 2 s).
            if (waits > 13) return;
            final int gen = generation;
            ui.postDelayed(new Runnable() {
                @Override public void run() { if (gen == generation) play(streams, waits + 1); }
            }, 150);
            return;
        }
        final int gen = generation;
        boolean muted = !Prefs.trailerSound();
        current = streams;
        try {
            video = player(streams[0], gen, true);
            if (streams[1] != null) {
                video.setVolume(0, 0);              // video-only file: the sound is the other player's
                if (!muted) audio = player(streams[1], gen, false);
                else audioPrepared = true;
            } else {
                audioPrepared = true;
                if (muted) video.setVolume(0, 0);
            }
        } catch (Exception e) {
            AppLog.w("Trailer: player: " + e);
            Recs.Pick p = wantedPick;
            stop();
            host.trailerNone(p);
            return;
        }
        ui.postDelayed(giveUp, NO_SHOW_MS);
    }

    private String[] current;       // the streams playing now ({video, audio, height, single file})

    private MediaPlayer player(String url, final int gen, final boolean isVideo) throws Exception {
        MediaPlayer m = new MediaPlayer();
        m.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", VR_AGENT);
        m.setDataSource(host.getContext(), Uri.parse(url), headers);
        m.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer mp) {
                if (gen != generation) return;
                if (isVideo) videoPrepared = true;
                else audioPrepared = true;
                maybeStart();
            }
        });
        m.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                if (gen != generation) return true;
                AppLog.w("Trailer: " + (isVideo ? "video" : "audio") + " error " + what + "/" + extra);
                String[] s = current;
                Recs.Pick p = wantedPick;
                boolean was = playing;
                if (!was && s != null && s[1] != null && s[3] != null) {
                    // The two separate streams didn't play here: the single 360p file instead.
                    AppLog.w("Trailer: trying the single 360p file");
                    stopPlayers();
                    play(new String[] {s[3], null, "360", null}, 0);
                    return true;
                }
                stop();
                if (!was) host.trailerNone(p);
                return true;
            }
        });
        if (isVideo) {
            m.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (gen == generation && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) shown();
                    return false;
                }
            });
            m.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) {
                    if (gen == generation) stop();
                }
            });
        }
        m.prepareAsync();
        return m;
    }

    /** Both ready: the SurfaceView appears (its surface comes a moment later), then both start together. */
    private void maybeStart() {
        if (!videoPrepared || !audioPrepared || started || video == null) return;
        if (surface == null) create();
        if (surface.getVisibility() != View.VISIBLE) {
            measure();
            layout();
            surface.setAlpha(0f);
            surface.setVisibility(View.VISIBLE);
        }
        if (!surfaceReady) return;          // surfaceCreated calls again
        started = true;
        try {
            video.setDisplay(surface.getHolder());
            video.start();
            if (audio != null) audio.start();
        } catch (IllegalStateException e) {
            AppLog.w("Trailer: start: " + e);
            return;
        }
        ui.postDelayed(sync, SYNC_MS);
        ui.postDelayed(showAnyway, 1500);
        AppLog.w("Trailer: started for " + wanted);
    }

    /** The audio player follows the video one (they drift apart a little over a trailer). */
    private final Runnable sync = new Runnable() {
        @Override public void run() {
            if (video == null || !started) return;
            try {
                if (audio != null && video.isPlaying()) {
                    int v = video.getCurrentPosition(), a = audio.getCurrentPosition();
                    if (Math.abs(v - a) > 150) audio.seekTo(v + 60);
                }
            } catch (IllegalStateException ignored) {
                // stopped meanwhile
            }
            ui.postDelayed(this, SYNC_MS);
        }
    };

    /** Some TVs never say "first frame rendered" for their hardware plane: playing is enough. */
    private final Runnable showAnyway = new Runnable() {
        @Override public void run() {
            try {
                if (video != null && video.isPlaying()) shown();
            } catch (IllegalStateException ignored) {
                // stopped meanwhile
            }
        }
    };

    private void shown() {
        ui.removeCallbacks(giveUp);
        if (playing || surface == null) return;
        playing = true;
        surface.animate().alpha(1f).setDuration(400).start();
        AppLog.w("Trailer: playing at " + surface.getLeft() + "," + surface.getTop() + " " + surface.getWidth() + "x" + surface.getHeight());
        host.trailerPlaying();
    }

    private final Runnable giveUp = new Runnable() {
        @Override public void run() {
            AppLog.w("Trailer: didn't start in time for " + wanted);
            Recs.Pick p = wantedPick;
            stop();
            host.trailerNone(p);
        }
    };

    private void stopPlayers() {
        ui.removeCallbacks(giveUp);
        ui.removeCallbacks(sync);
        ui.removeCallbacks(showAnyway);
        for (MediaPlayer m : new MediaPlayer[] {video, audio}) {
            if (m == null) continue;
            try {
                m.release();
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        video = audio = null;
        videoPrepared = audioPrepared = started = false;
    }

    /** Stops what plays (or is about to) right away; the cover shows again. */
    void stop() {
        generation++;
        ui.removeCallbacks(prepare);
        stopPlayers();
        current = null;
        if (playing) {
            playing = false;
            host.trailerGone();
        }
        if (surface != null && surface.getVisibility() != View.GONE) {
            surface.animate().cancel();
            surface.setVisibility(View.GONE);
        }
    }

    /** It plays and shows (the title card can grow it). */
    boolean playing() {
        return playing;
    }

    /** Moves it (the title card grows it to full screen and back). */
    void place(RectF to) {
        if (surface == null || surface.getVisibility() == View.GONE) return;
        if (Math.abs(rect.left - to.left) < 0.5f && Math.abs(rect.top - to.top) < 0.5f
                && Math.abs(rect.right - to.right) < 0.5f && Math.abs(rect.bottom - to.bottom) < 0.5f) return;
        rect.set(to);
        measure();
        layout();
    }

    /** The video's corner radius: a SurfaceView keeps square corners. */
    void round(float radius) {
    }

    /** The home screen went away: the players and the view too. */
    void release() {
        stop();
        wanted = null;
        wantedPick = null;
        if (surface == null) return;
        host.removeView(surface);
        surface = null;
        surfaceReady = false;
    }

    // Called from HomeView's onMeasure and onLayout.
    void measure() {
        if (surface == null) return;
        surface.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(rect.width())), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(rect.height())), View.MeasureSpec.EXACTLY));
    }

    void layout() {
        if (surface == null) return;
        int l = Math.round(rect.left), t = Math.round(rect.top);
        surface.layout(l, t, l + surface.getMeasuredWidth(), t + surface.getMeasuredHeight());
    }

    private void create() {
        surface = new SurfaceView(host.getContext());
        surface.setFocusable(false);
        surface.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                surfaceReady = true;
                maybeStart();
            }

            @Override public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
            }

            @Override public void surfaceDestroyed(SurfaceHolder h) {
                surfaceReady = false;
                if (video != null) {
                    try {
                        video.setDisplay(null);
                    } catch (RuntimeException ignored) {
                        // released
                    }
                }
            }
        });
        surface.setVisibility(View.GONE);
        host.addTrailerView(surface);
    }
}
