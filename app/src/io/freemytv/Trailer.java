package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The focused title's trailer, playing inside its cover after 3 s on it (TMDB's YouTube trailer, the TV's language
 * first). One small WebView, made the first time and destroyed when the home screen goes away; moving off the title
 * stops it at once. Muted unless "Trailer sound" is on in Home Settings.
 *
 * At 0.8 s the trailer is looked up and the player loads paused, unseen; at 3 s it plays and fades in. The fade and
 * the rounded corners are done by the page: view alpha or an outline clip on the WebView left the video black on TVs.
 */
final class Trailer {
    private static final long PREPARE_MS = 800, PLAY_MS = 3000, NO_SHOW_MS = 15_000;
    private static final ExecutorService ASK = Executors.newSingleThreadExecutor();

    private final HomeView host;
    private final Recs recs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final RectF rect = new RectF();
    private WebView web;
    private boolean broken;         // no WebView on this TV: never try again
    private boolean loaded;         // the page for the current title is in the WebView
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
        if (key != null && !broken && Prefs.trailers()) {
            ui.postDelayed(prepare, PREPARE_MS);
            ui.postDelayed(go, PLAY_MS);
        }
    }

    private final Runnable prepare = new Runnable() {
        @Override public void run() {
            final int gen = generation;
            final Recs.Pick p = wantedPick;
            ASK.execute(new Runnable() {
                @Override public void run() {
                    final String video = recs.trailer(p);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (gen != generation) return;
                            if (video == null || video.isEmpty()) {
                                ui.removeCallbacks(go);
                                AppLog.w("Trailer: none on TMDB for " + wanted);
                                return;
                            }
                            load(video, gen);
                        }
                    });
                }
            });
        }
    };

    private final Runnable go = new Runnable() {
        @Override public void run() {
            if (web == null || !loaded) {
                ui.postDelayed(this, 250);      // the lookup is still on its way
                return;
            }
            web.evaluateJavascript("go()", null);
            ui.postDelayed(giveUp, NO_SHOW_MS);  // blocked outside YouTube, or too slow: the cover stays
        }
    };

    private final Runnable giveUp = new Runnable() {
        @Override public void run() {
            AppLog.w("Trailer: didn't start in time for " + wanted);
            hide();
        }
    };

    private void load(String video, int gen) {
        if (!host.trailerRect(rect) || (web == null && !create())) {
            ui.removeCallbacks(go);
            return;
        }
        measure();
        layout();
        web.setVisibility(View.VISIBLE);    // transparent until the page fades the video in
        web.loadDataWithBaseURL("https://freemytv.io/", page(video, gen, !Prefs.trailerSound(), host.trailerRadius()),
                "text/html", "utf-8", null);
        loaded = true;
        AppLog.w("Trailer " + video + " for " + wanted);
    }

    private void state(int gen, String s) {
        if (gen != generation || web == null) return;
        AppLog.w("Trailer: " + s);
        if ("playing".equals(s)) {
            ui.removeCallbacks(giveUp);
        } else if ("ended".equals(s) || s.startsWith("error")) {
            ui.removeCallbacks(giveUp);
            ui.removeCallbacks(go);
            generation++;
            ui.postDelayed(new Runnable() {     // after the page's fade out
                @Override public void run() { hide(); }
            }, 500);
        }
    }

    /** Stops what plays (or is about to) right away; the cover shows again. */
    void stop() {
        generation++;
        ui.removeCallbacks(prepare);
        ui.removeCallbacks(go);
        hide();
    }

    private void hide() {
        ui.removeCallbacks(giveUp);
        loaded = false;
        if (web == null || web.getVisibility() == View.GONE) return;
        web.setVisibility(View.GONE);
        web.loadUrl("about:blank");     // stops the video and its sound
    }

    /** The home screen went away: the WebView too (it holds a video decoder and memory). */
    void release() {
        stop();
        wanted = null;
        wantedPick = null;
        if (web == null) return;
        host.removeView(web);
        web.destroy();
        web = null;
    }

    // Called from HomeView's onMeasure and onLayout.
    void measure() {
        if (web == null) return;
        web.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(rect.width())), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(rect.height())), View.MeasureSpec.EXACTLY));
    }

    void layout() {
        if (web == null) return;
        int l = Math.round(rect.left), t = Math.round(rect.top);
        web.layout(l, t, l + web.getMeasuredWidth(), t + web.getMeasuredHeight());
    }

    private boolean create() {
        try {
            try {
                WebView.setDataDirectorySuffix("home");   // the :home process's own WebView data
            } catch (Throwable ignored) {
                // already set, or WebView already started in this process
            }
            web = new WebView(host.getContext());
        } catch (Throwable t) {
            broken = true;
            AppLog.w("Trailer: no WebView on this TV: " + t);
            return false;
        }
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);   // no grey play icon before it starts
            }

            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) AppLog.w("Trailer page: " + m.message());
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(this), "FMT");
        web.setVisibility(View.GONE);
        host.addTrailerView(web);
        return true;
    }

    /** The page's messages ("playing", "ended", "error N"), on the WebView's thread. */
    static final class Bridge {
        private final Trailer owner;

        Bridge(Trailer owner) { this.owner = owner; }

        @JavascriptInterface public void state(final int gen, final String s) {
            owner.ui.post(new Runnable() {
                @Override public void run() { owner.state(gen, s); }
            });
        }
    }

    /**
     * YouTube's player, without its controls, slightly larger than the cover so the title bar and logo fall outside.
     * It loads paused; go() plays it. If the TV refuses to start it with sound, it starts muted.
     */
    private static String page(String video, int gen, boolean muted, float radius) {
        String id = video.replaceAll("[^A-Za-z0-9_-]", "");
        String hl = Ui.locale().getLanguage();
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:transparent}"
                + "#w{position:absolute;left:0;top:0;right:0;bottom:0;overflow:hidden;background:#000;"
                + "border-radius:" + Math.round(radius) + "px;opacity:0;transition:opacity .6s ease}"
                + "#p{position:absolute;left:-12%;top:-12%;width:124%;height:124%;border:0;pointer-events:none}</style></head>"
                + "<body><div id='w'><div id='p'></div></div><script>"
                + "var G=" + gen + ",M=" + (muted ? 1 : 0) + ",player,ready=0,want=0;"
                + "function s(x){try{FMT.state(G,x)}catch(e){}}"
                + "function show(on){document.getElementById('w').style.opacity=on?1:0}"
                + "function start(){if(M)player.mute();else player.unMute();player.playVideo();"
                + "setTimeout(function(){if(player.getPlayerState()!=1){player.mute();player.playVideo();}},2000);}"
                + "function go(){want=1;if(ready)start();}"
                + "var t=document.createElement('script');t.src='https://www.youtube.com/iframe_api';"
                + "t.onerror=function(){s('error script')};document.head.appendChild(t);"
                + "function onYouTubeIframeAPIReady(){player=new YT.Player('p',{videoId:'" + id + "',"
                + "playerVars:{autoplay:0,controls:0,disablekb:1,fs:0,rel:0,iv_load_policy:3,modestbranding:1,"
                + "playsinline:1,cc_load_policy:0,hl:'" + hl + "',origin:'https://freemytv.io'},"
                + "events:{onReady:function(){ready=1;s('ready');if(want)start();},"
                + "onStateChange:function(e){if(e.data==1){show(1);s('playing');}else if(e.data==0){show(0);s('ended');}},"
                + "onError:function(e){show(0);s('error '+e.data);}}});}"
                + "</script></body></html>";
    }
}
