package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The focused title's trailer, playing inside its cover after 5 s on it (TMDB's YouTube trailer, the TV's language
 * first). One small WebView, made the first time and destroyed when the home screen goes away; moving off the title
 * stops it at once. Muted unless "Trailer sound" is on in Home Settings.
 */
final class Trailer {
    private static final long WAIT_MS = 5000, NO_SHOW_MS = 15_000;
    private static final ExecutorService ASK = Executors.newSingleThreadExecutor();

    private final HomeView host;
    private final Recs recs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final RectF rect = new RectF();
    private WebView web;
    private boolean broken;         // no WebView on this TV: never try again
    private String wanted;          // "kind:id" of the focused title, or null
    private Recs.Pick wantedPick;
    private int generation;         // bumped on every stop: late answers from an older title are dropped

    Trailer(HomeView host, Recs recs) {
        this.host = host;
        this.recs = recs;
    }

    /** The title focused now (null: none). A new one stops the old trailer and starts the 5 s count again. */
    void want(Recs.Pick p) {
        String key = p == null || p.id == 0 ? null : p.kind + ":" + p.id;
        if (key == null ? wanted == null : key.equals(wanted)) return;
        stop();
        wanted = key;
        wantedPick = p;
        if (key != null && !broken && Prefs.trailers()) ui.postDelayed(start, WAIT_MS);
    }

    private final Runnable start = new Runnable() {
        @Override public void run() {
            final int gen = generation;
            final Recs.Pick p = wantedPick;
            ASK.execute(new Runnable() {
                @Override public void run() {
                    final String video = recs.trailer(p);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (gen == generation && video != null && !video.isEmpty()) play(video, gen);
                        }
                    });
                }
            });
        }
    };

    private final Runnable giveUp = new Runnable() {
        @Override public void run() { hide(); }
    };

    private void play(String video, int gen) {
        if (!host.trailerRect(rect) || (web == null && !create())) return;
        web.animate().cancel();
        web.setAlpha(0);
        web.setVisibility(View.VISIBLE);
        measure();
        layout();
        web.invalidateOutline();
        web.loadDataWithBaseURL("https://freemytv.io/", page(video, gen, !Prefs.trailerSound()), "text/html", "utf-8", null);
        ui.postDelayed(giveUp, NO_SHOW_MS);      // blocked outside YouTube, or too slow: the cover stays
        AppLog.w("Trailer " + video + " for " + wanted);
    }

    private void state(int gen, String s) {
        if (gen != generation || web == null) return;
        if ("playing".equals(s)) {
            ui.removeCallbacks(giveUp);
            web.animate().alpha(1).setDuration(600).start();
        } else if ("ended".equals(s) || "error".equals(s)) {
            if ("error".equals(s)) AppLog.w("Trailer not playable here: " + wanted);
            ui.removeCallbacks(giveUp);
            generation++;
            web.animate().alpha(0).setDuration(450).withEndAction(new Runnable() {
                @Override public void run() { hide(); }
            }).start();
        }
    }

    /** Stops what plays (or is about to) right away; the cover shows again. */
    void stop() {
        generation++;
        ui.removeCallbacks(start);
        hide();
    }

    private void hide() {
        ui.removeCallbacks(giveUp);
        if (web == null || web.getVisibility() == View.GONE) return;
        web.animate().cancel();
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
            AppLog.w("No WebView for trailers: " + t);
            return false;
        }
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);   // no grey play icon before it starts
            }
        });
        web.addJavascriptInterface(new Bridge(this), "FMT");
        final float radius = host.trailerRadius();
        web.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        web.setClipToOutline(true);
        web.setVisibility(View.GONE);
        host.addTrailerView(web);
        return true;
    }

    /** The page's messages ("playing", "ended", "error"), on the WebView's thread. */
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
     * If the TV refuses to start it with sound, it starts muted.
     */
    private static String page(String video, int gen, boolean muted) {
        String id = video.replaceAll("[^A-Za-z0-9_-]", "");
        String hl = Ui.locale().getLanguage();
        int m = muted ? 1 : 0;
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;overflow:hidden;background:#000}"
                + "#p{position:absolute;left:-12%;top:-12%;width:124%;height:124%;border:0;pointer-events:none}</style></head>"
                + "<body><div id='p'></div><script>"
                + "var G=" + gen + ",M=" + m + ",player;"
                + "function s(x){try{FMT.state(G,x)}catch(e){}}"
                + "var t=document.createElement('script');t.src='https://www.youtube.com/iframe_api';"
                + "t.onerror=function(){s('error')};document.head.appendChild(t);"
                + "function onYouTubeIframeAPIReady(){player=new YT.Player('p',{videoId:'" + id + "',"
                + "host:'https://www.youtube-nocookie.com',"
                + "playerVars:{autoplay:1,mute:M,controls:0,disablekb:1,fs:0,rel:0,iv_load_policy:3,modestbranding:1,"
                + "playsinline:1,cc_load_policy:0,hl:'" + hl + "',origin:'https://freemytv.io'},"
                + "events:{onReady:function(e){if(M)e.target.mute();else e.target.unMute();e.target.playVideo();"
                + "setTimeout(function(){if(player.getPlayerState()!=1){player.mute();player.playVideo();}},2500);},"
                + "onStateChange:function(e){if(e.data==1)s('playing');else if(e.data==0)s('ended');},"
                + "onError:function(){s('error')}}});}"
                + "</script></body></html>";
    }
}
