package io.freemytv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.util.List;

/**
 * The trailer over the selected card: a WebView with our own small page around the YouTube (or Vimeo) player.
 *  - The page has an https address of its own (BASE): YouTube refuses an embed loaded with no page around it
 *    ("error 153"), which is what a bare loadUrl of the embed address does.
 *  - It starts muted (never blocked), then the player turns the sound up and the subtitles off as soon as it plays.
 *  - It is shown only while it really plays (1.5 s in, once the title overlay is gone), hidden while it pauses or
 *    loads, and it fades out 6 s before the end so the end screen with other videos never shows.
 *  - A video its owner keeps off other sites (YouTube errors 101/150) or that doesn't start passes to the next one,
 *    up to three; then the listener hears why.
 */
final class Trailer {
    interface Listener {
        /** The video is on screen. */
        void onShown();
        /** No video could play (reason for the person) or the last one ended. */
        void onGone(String reason);
    }

    private static final String BASE = "https://io.freemytv/";
    private static final long SHOW_AFTER = 1500, FALLBACK = 6000, WATCHDOG = 9000, FADE = 600;

    final WebView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private List<Recs.Video> videos;
    private int index, last = -1;
    private volatile int generation;     // read by the page's thread: messages from an older page are dropped
    private boolean shown, heard, sound, wide, showPending;
    private float radius;
    private String failure = "";
    private final Runnable showNow = () -> { showPending = false; show(); };
    private final Runnable fallback = () -> { if (!heard) show(); };
    private final Runnable watchdog = () -> { if (heard && !shown && last != 1) next(Ui.tr("não começa", "won't start", "no empieza", "non parte")); };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    Trailer(Context c, Listener listener) {
        this.listener = listener;
        view = new WebView(c);
        view.setBackgroundColor(0xFF000000);
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        view.setClickable(false);
        view.setVisibility(View.GONE);
        view.setAlpha(0);
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(true);
        view.getSettings().setMediaPlaybackRequiresUserGesture(false);
        view.addJavascriptInterface(new Bridge(), "FMTV");
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        view.setClipToOutline(true);
    }

    boolean active() {
        return videos != null;
    }

    boolean shown() {
        return shown;
    }

    /** Plays the first of `list` that works (sound: turn the sound up once it plays). */
    void play(List<Recs.Video> list, boolean withSound) {
        stop();
        if (list == null || list.isEmpty()) return;
        videos = list;
        sound = withSound;
        index = 0;
        failure = "";
        view.setVisibility(View.VISIBLE);
        load();
    }

    void stop() {
        generation++;
        cancelTimers();
        boolean wasActive = videos != null;
        videos = null;
        shown = false;
        view.animate().cancel();
        view.setAlpha(0);
        if (wasActive) {
            view.loadUrl("about:blank");
            view.setVisibility(View.GONE);
        }
    }

    void destroy() {
        stop();
        view.destroy();
    }

    /** Corner radius (full screen: none); a wider crop hides the player's own overlays in the card. */
    void shape(float cornerRadius, boolean fullScreen) {
        if (cornerRadius != radius) {
            radius = cornerRadius;
            view.invalidateOutline();
        }
        if (fullScreen != wide) {
            wide = fullScreen;
            if (active()) view.evaluateJavascript("crop(" + (wide ? 6 : 12) + ")", null);
        }
    }

    private void cancelTimers() {
        main.removeCallbacks(showNow);
        showPending = false;
        main.removeCallbacks(fallback);
        main.removeCallbacks(watchdog);
    }

    private void load() {
        generation++;
        cancelTimers();
        heard = false;
        shown = false;
        last = -1;
        view.animate().cancel();
        view.setAlpha(0);
        Recs.Video v = videos.get(index);
        view.loadDataWithBaseURL(BASE, page(v, sound, wide ? 6 : 12), "text/html", "utf-8", null);
        main.postDelayed(fallback, FALLBACK);
        main.postDelayed(watchdog, WATCHDOG);
    }

    private void show() {
        if (shown || videos == null) return;
        shown = true;
        main.removeCallbacks(fallback);
        view.animate().alpha(1).setDuration(FADE).start();
        listener.onShown();
    }

    /** This video won't do (why, or null: it simply ended): the next one, or nothing left. */
    private void next(String why) {
        if (videos == null) return;
        if (why != null) {
            failure = why;
            AppLog.w("trailer " + videos.get(index).key + ": " + why);
        }
        if (why != null && index + 1 < videos.size()) {
            index++;
            load();
            return;
        }
        final int g = ++generation;
        cancelTimers();
        view.animate().alpha(0).setDuration(FADE).withEndAction(() -> {
            if (g != generation) return;
            String reason = why != null ? failure : null;
            stop();
            listener.onGone(reason);
        }).start();
    }

    /** From the page: the player's state (YouTube's numbers: -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering). */
    private void onState(int state, double time, double duration) {
        if (videos == null) return;
        heard = true;
        last = state;
        if (state == 0) {
            next(null);
            return;
        }
        if (state == 1) {
            if (!shown) {
                if (!showPending) {
                    showPending = true;
                    main.postDelayed(showNow, SHOW_AFTER);
                }
            } else if (view.getAlpha() < 1) {
                view.animate().alpha(1).setDuration(FADE).start();
            }
        } else if ((state == 2 || state == 3) && shown) {
            view.animate().alpha(0).setDuration(FADE).start();
        }
        if (shown && duration > 0 && time > 0 && duration - time < 6) next(null);
    }

    private void onError(int code) {
        if (videos == null) return;
        String why;
        switch (code) {
            case 100: why = Ui.tr("vídeo removido ou privado", "video removed or private", "vídeo eliminado o privado", "video rimosso o privato"); break;
            case 101:
            case 150: why = Ui.tr("o dono não deixa ver fora do YouTube", "its owner keeps it on YouTube only",
                    "su dueño no deja verlo fuera de YouTube", "il proprietario non permette di vederlo fuori da YouTube"); break;
            case 152:
            case 153: why = Ui.tr("o YouTube recusou o player", "YouTube refused the player", "YouTube rechazó el reproductor", "YouTube ha rifiutato il player"); break;
            default: why = "error " + code;
        }
        next(why);
    }

    private final class Bridge {
        @JavascriptInterface public void state(int state, double time, double duration) {
            final int g = generation;
            main.post(() -> { if (g == generation) onState(state, time, duration); });
        }

        @JavascriptInterface public void error(int code) {
            final int g = generation;
            main.post(() -> { if (g == generation) onError(code); });
        }
    }

    /** The page around the player: muted start, sound and no subtitles once it plays, the state sent to FMTV. */
    private static String page(Recs.Video v, boolean sound, int crop) {
        String head = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}"
                + "#p{position:absolute;border:0;pointer-events:none}</style></head><body><div id=p></div><script>"
                + "var SOUND=" + sound + ";function crop(c){var p=document.getElementById('p');p.style.left=p.style.top=(-c)+'%';"
                + "p.style.width=p.style.height=(100+2*c)+'%';}crop(" + crop + ");";
        if ("Vimeo".equals(v.site)) {
            // Background mode: no title, logo, bar or controls (always muted); with sound, the cleanest normal player.
            String src = "https://player.vimeo.com/video/" + v.key + (sound
                    ? "?autoplay=1&controls=0&title=0&byline=0&portrait=0&dnt=1&texttrack=false"
                    : "?background=1&autoplay=1&loop=0&dnt=1");
            return head + "</script><script src='https://player.vimeo.com/api/player.js'></script><script>"
                    + "var f=document.createElement('iframe');f.id='p';f.allow='autoplay';f.src='" + src + "';"
                    + "document.body.replaceChild(f,document.getElementById('p'));crop(" + crop + ");"
                    + "var pl=new Vimeo.Player(f),t=0,d=0;"
                    + "pl.on('play',function(){FMTV.state(1,t,d)});pl.on('pause',function(){FMTV.state(2,t,d)});"
                    + "pl.on('bufferstart',function(){FMTV.state(3,t,d)});pl.on('bufferend',function(){FMTV.state(1,t,d)});"
                    + "pl.on('ended',function(){FMTV.state(0,t,d)});pl.on('error',function(){FMTV.error(5)});"
                    + "pl.on('timeupdate',function(e){t=e.seconds;d=e.duration;FMTV.state(1,t,d)});"
                    + "pl.disableTextTrack().catch(function(){});"
                    + "</script></body></html>";
        }
        return head
                + "var pl,tuned=false,gave=false;"
                + "function onYouTubeIframeAPIReady(){pl=new YT.Player('p',{host:'https://www.youtube-nocookie.com',videoId:'" + v.key + "',"
                + "playerVars:{autoplay:1,mute:1,controls:0,disablekb:1,fs:0,rel:0,iv_load_policy:3,cc_load_policy:0,"
                + "cc_lang_pref:'none',playsinline:1,origin:'" + BASE.substring(0, BASE.length() - 1) + "'},"
                + "events:{onReady:function(e){e.target.mute();e.target.playVideo();crop(" + crop + ");},"
                + "onStateChange:function(e){st(e.data)},onError:function(e){FMTV.error(e.data)}}});"
                + "setInterval(function(){if(pl&&pl.getPlayerState)FMTV.state(pl.getPlayerState(),pl.getCurrentTime()||0,pl.getDuration()||0)},500);}"
                + "function st(s){if(s===1&&!tuned){tuned=true;try{pl.unloadModule('captions');pl.unloadModule('cc');}catch(e){}"
                + "if(SOUND){pl.unMute();pl.setVolume(100);}}"
                // Paused right after the sound went up: this player doesn't allow sound here, so muted it goes on.
                + "if(s===2&&tuned&&SOUND&&!gave&&pl.getCurrentTime()<8){gave=true;pl.mute();pl.playVideo();return;}"
                + "FMTV.state(s,pl.getCurrentTime()||0,pl.getDuration()||0);}"
                + "</script><script src='https://www.youtube.com/iframe_api'></script></body></html>";
    }
}
