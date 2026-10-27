package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The focused title's trailer, playing inside its cover after 3 s on it (TMDB's YouTube trailers, the TV's language
 * first). One small WebView, made the first time and destroyed when the home screen goes away; moving off the title
 * stops it at once. Muted unless "Trailer sound" is on in Home Settings.
 *
 * At 0.8 s the trailers are looked up and YouTube's embed page loads, unseen (with a Referer: without one YouTube
 * refuses embeds in apps, errors 152 and 153). A script then hides YouTube's own chrome and watches the video
 * itself; at 3 s it plays and fades in. A trailer YouTube won't play outside its app gives way to the next one.
 * The fade is done by the page: view alpha or an outline clip on the WebView left the video black on TVs.
 */
final class Trailer {
    private static final long PREPARE_MS = 800, PLAY_MS = 3000, NO_SHOW_MS = 15_000;
    private static final String SITE = "https://freemytv.io/";
    private static final ExecutorService ASK = Executors.newSingleThreadExecutor();

    private final HomeView host;
    private final Recs recs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final RectF rect = new RectF();
    private WebView web;
    private boolean broken;         // no WebView on this TV: never try again
    private boolean ready, going;   // the video element is hooked; it was asked to play
    private String wanted;          // "kind:id" of the focused title, or null
    private Recs.Pick wantedPick;
    private String[] videos = new String[0];
    private int video;              // which of `videos` is loaded
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
                    final String found = recs.trailer(p);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (gen != generation) return;
                            if (found == null || found.isEmpty()) {
                                ui.removeCallbacks(go);
                                return;
                            }
                            videos = found.split(",");
                            video = 0;
                            load();
                        }
                    });
                }
            });
        }
    };

    private final Runnable go = new Runnable() {
        @Override public void run() {
            going = true;
            if (ready && web != null) web.evaluateJavascript("window.fmtGo&&fmtGo()", null);
            ui.removeCallbacks(giveUp);
            ui.postDelayed(giveUp, NO_SHOW_MS);  // too slow, or refused: the cover stays
        }
    };

    private final Runnable giveUp = new Runnable() {
        @Override public void run() {
            AppLog.w("Trailer: didn't start in time for " + wanted);
            hide();
        }
    };

    private void load() {
        if (!host.trailerRect(rect) || (web == null && !create())) {
            AppLog.w("Trailer: no place for it (" + wanted + ")");
            ui.removeCallbacks(go);
            return;
        }
        ready = false;
        measure();
        layout();
        String id = videos[video].replaceAll("[^A-Za-z0-9_-]", "");
        String url = "https://www.youtube-nocookie.com/embed/" + id + "?autoplay=0&mute=1&controls=0&disablekb=1&fs=0&rel=0"
                + "&iv_load_policy=3&modestbranding=1&playsinline=1&cc_load_policy=0&hl=" + Ui.locale().getLanguage()
                + "&origin=" + Uri.encode(SITE.substring(0, SITE.length() - 1));
        web.setVisibility(View.INVISIBLE);      // shown once the page is dressed and transparent
        web.loadUrl(url, Collections.singletonMap("Referer", SITE));
        AppLog.w("Trailer: loading " + id + " for " + wanted);
    }

    /** Runs in YouTube's page once it has loaded: hides its chrome, finds the video, reports what it does. */
    private String script() {
        boolean muted = !Prefs.trailerSound();
        // The cover's rounded corners, in the page's pixels.
        int radius = Math.round(host.trailerRadius() / host.getResources().getDisplayMetrics().density);
        return "(function(){if(window.fmtG==" + generation + ")return;window.fmtG=" + generation + ";"
                + "var G=" + generation + ",M=" + (muted ? "true" : "false") + ",said={};"
                + "function s(x){if(said[x])return;said[x]=1;try{FMT.state(G,x)}catch(e){}}"
                + "var st=document.createElement('style');st.textContent='html,body{background:transparent!important;overflow:hidden!important}"
                + "html{opacity:0;transition:opacity .6s ease}html.fmt-on{opacity:1}"
                + ".ytp-chrome-top,.ytp-chrome-bottom,.ytp-gradient-top,.ytp-gradient-bottom,.ytp-watermark,.ytp-pause-overlay,"
                + ".ytp-large-play-button,.ytp-spinner,.ytp-cued-thumbnail-overlay,.ytp-paid-content-overlay,.ytp-ce-element,"
                + ".ytp-impression-link,.ytp-youtube-button,.ytp-show-cards-title,.ytp-title,.ytp-endscreen-content,"
                + ".ytp-contextmenu,.branding-img{display:none!important}"
                + ".html5-video-player,#player{background:#000!important;border-radius:" + radius + "px!important;overflow:hidden!important}"
                + "video{object-fit:cover!important}';"
                + "document.head.appendChild(st);"
                + "function err(){var e=document.querySelector('.ytp-error');"
                + "if(e&&e.offsetParent!==null){s('error '+(e.innerText||'').replace(/\\s+/g,' ').slice(0,90));return true}return false}"
                + "function hook(){if(err())return;var v=document.querySelector('video');if(!v){setTimeout(hook,200);return}"
                + "window.fmtV=v;v.muted=M;"
                + "v.addEventListener('playing',function(){document.documentElement.classList.add('fmt-on');s('playing')});"
                + "v.addEventListener('ended',function(){document.documentElement.classList.remove('fmt-on');s('ended')});"
                + "s('ready')}"
                + "window.fmtGo=function(){var v=window.fmtV;if(!v){setTimeout(fmtGo,200);return}v.muted=M;"
                + "var p=v.play();if(p&&p.catch)p.catch(function(){v.muted=true;v.play()});"
                + "setTimeout(function(){if(v.paused){var b=document.querySelector('.ytp-large-play-button');if(b)b.click();"
                + "else{v.muted=true;v.play()}}},1500)};"
                + "setInterval(err,1000);hook();})()";
    }

    private void state(int gen, String s) {
        if (gen != generation || web == null) return;
        AppLog.w("Trailer: " + s);
        if ("ready".equals(s)) {
            ready = true;
            web.setVisibility(View.VISIBLE);    // still transparent: the page fades in when the video plays
            if (going) web.evaluateJavascript("fmtGo()", null);
        } else if ("playing".equals(s)) {
            ui.removeCallbacks(giveUp);
        } else if (s.startsWith("error")) {
            if (video + 1 < videos.length) {    // this one won't play outside YouTube: the next one
                generation++;
                video++;
                load();
                if (going) ui.post(go);
                return;
            }
            finish();
        } else if ("ended".equals(s)) {
            finish();
        }
    }

    /** Over (or nothing playable): fades out and gives the cover back, without trying this title again. */
    private void finish() {
        ui.removeCallbacks(giveUp);
        ui.removeCallbacks(go);
        generation++;
        ui.postDelayed(new Runnable() {     // after the page's fade out
            @Override public void run() { hide(); }
        }, 500);
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
        ready = going = false;
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
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                return !"https".equals(scheme) && !"http".equals(scheme);   // no jumps into other apps
            }

            @Override public void onPageFinished(WebView v, String url) {
                if (url == null || url.startsWith("about:")) return;
                if (url.contains("consent.")) {
                    AppLog.w("Trailer: YouTube asks for cookie consent, can't play here");
                    finish();
                    return;
                }
                v.evaluateJavascript(script(), null);
            }

            @Override public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse e) {
                if (r.isForMainFrame()) AppLog.w("Trailer: HTTP " + e.getStatusCode() + " for " + r.getUrl().getHost());
            }
        });
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

    /** The page's messages ("ready", "playing", "ended", "error …"), on the WebView's thread. */
    static final class Bridge {
        private final Trailer owner;

        Bridge(Trailer owner) { this.owner = owner; }

        @JavascriptInterface public void state(final int gen, final String s) {
            owner.ui.post(new Runnable() {
                @Override public void run() { owner.state(gen, s); }
            });
        }
    }
}
