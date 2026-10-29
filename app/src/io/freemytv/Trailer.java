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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The focused title's trailer, playing inside its cover after 1 s on it (TMDB's YouTube trailers, the TV's language
 * first). One small WebView, made the first time and destroyed when the home screen goes away; moving off the title
 * stops it at once. With sound unless "Trailer sound" is off in Home Settings; no subtitles; it fades out 6 s before
 * the end, so YouTube's end screen with other videos never shows. Once it plays, the home screen can grow it into
 * the title card (Spotlight): HomeView moves it with place() and squares its corners with round().
 *
 * At 0.25 s the trailers are looked up and a small page of ours loads, unseen, with YouTube's embed in an iframe (the
 * page's address gives YouTube the Referer it wants: without one it refuses embeds in apps, errors 152 and 153). The
 * player is driven through YouTube's IFrame API messages, as in the HTML preview; it fades in once it plays. A
 * trailer YouTube won't play outside its app gives way to the next one.
 * The fade is done by the page: view alpha or an outline clip on the WebView left the video black on TVs.
 */
final class Trailer {
    private static final long PREPARE_MS = 250, PLAY_MS = 1000, NO_SHOW_MS = 15_000, READY_MS = 8_000;
    private static final String SITE = "https://freemytv.io/";
    private static final ExecutorService ASK = Executors.newSingleThreadExecutor();

    private final HomeView host;
    private final Recs recs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final RectF rect = new RectF();
    private WebView web;
    private boolean broken;         // no WebView on this TV: never try again
    private boolean ready, going;   // the video element is hooked; it was asked to play
    private boolean playing;        // it plays and shows
    private String wanted;          // "kind:id" of the focused title, or null
    private Recs.Pick wantedPick;
    private String[] videos = new String[0];
    private int video;              // which of `videos` is loaded
    private volatile String userAgent;
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
                                host.trailerNone(p);
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
            host.trailerNone(wantedPick);       // the title card opens anyway, with the backdrop
        }
    };

    private int waits;              // tries while the card is still opening into its cover

    private void load() {
        if (!host.trailerRect(rect)) {
            // The card is still opening from poster to cover: try again in a moment (for up to 2 s).
            if (waits++ < 13) {
                final int gen = generation;
                ui.postDelayed(new Runnable() {
                    @Override public void run() { if (gen == generation) load(); }
                }, 150);
                return;
            }
            AppLog.w("Trailer: no place for it (" + wanted + ")");
            ui.removeCallbacks(go);
            return;
        }
        waits = 0;
        if (web == null && !create()) {
            ui.removeCallbacks(go);
            return;
        }
        ready = false;
        measure();
        layout();
        String id = videos[video].replaceAll("[^A-Za-z0-9_-]", "");
        web.setVisibility(View.VISIBLE);        // transparent until the video plays (a hidden page wouldn't autoplay)
        web.loadDataWithBaseURL(SITE, page(id), "text/html", "utf-8", null);
        AppLog.w("Trailer: loading " + id + " for " + wanted);
    }

    /**
     * Our own small page with YouTube's embed in an iframe, as the preview does: the page's address (freemytv.io) is
     * the Referer and origin YouTube wants, and the player is driven by its own messages (the IFrame API's
     * postMessage), which is what YouTube supports. The iframe is 124% big, so its edges (title, logo) fall outside.
     */
    private String page(String id) {
        boolean muted = !Prefs.trailerSound();
        // The cover's rounded corners, in the page's pixels.
        int radius = Math.round(host.trailerRadius() / host.getResources().getDisplayMetrics().density);
        String origin = SITE.substring(0, SITE.length() - 1);
        String src = "https://www.youtube-nocookie.com/embed/" + id + "?autoplay=1&mute=1&enablejsapi=1&controls=0&disablekb=1&fs=0&rel=0"
                + "&iv_load_policy=3&modestbranding=1&playsinline=1&cc_load_policy=0&cc_lang_pref=none&hl=" + Ui.locale().getLanguage()
                + "&origin=" + Uri.encode(origin) + "&widget_referrer=" + Uri.encode(SITE);
        return "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'><style>"
                + "html,body{margin:0;width:100%;height:100%;background:transparent;overflow:hidden}"
                + "#b{position:absolute;left:0;top:0;right:0;bottom:0;background:#000;overflow:hidden;opacity:0;transition:opacity .6s ease;"
                + "border-radius:" + radius + "px;-webkit-transform:translateZ(0)}"
                // 0.99, not 1: fully opaque, the TV's WebView moves the video onto a hardware plane under the app's
                // window, where the cover and the title card hide it (sound, no picture). Below 1 it stays in the page.
                + "#b.on{opacity:.99}"
                + "iframe{position:absolute;left:-12%;top:-12%;width:124%;height:124%;border:0;pointer-events:none}"
                + "#b.full iframe{left:-6%;top:-6%;width:112%;height:112%}"
                + "</style></head><body><div id=b><iframe id=f allow='autoplay; encrypted-media' referrerpolicy='strict-origin-when-cross-origin' src='"
                + src + "'></iframe></div><script>"
                + "var G=" + generation + ",M=" + muted + ",f=document.getElementById('f'),b=document.getElementById('b'),"
                + "said={},heard=0,tuned=0,shown=0,near=0,quiet=0,going=0;"
                + "function s(x){if(said[x])return;said[x]=1;try{FMT.state(G,x)}catch(e){}}"
                + "function cmd(fn,a){try{f.contentWindow.postMessage(JSON.stringify({event:'command',func:fn,args:a||[],id:1,channel:'widget'}),'*')}catch(e){}}"
                + "window.fmtRound=function(r){b.style.borderRadius=r+'px';if(r==0)b.classList.add('full');else b.classList.remove('full')};"
                + "window.fmtGo=function(){going=1;cmd('playVideo')};"
                + "function off(){b.classList.remove('on')}"
                + "addEventListener('message',function(m){if(m.source!==f.contentWindow||typeof m.data!='string')return;"
                + "var d;try{d=JSON.parse(m.data)}catch(e){return}"
                + "if(!heard){heard=1;s('ready')}"
                + "if(d.event=='onError'){s('error '+d.info);return}"
                + "var i=d.info&&typeof d.info=='object'?d.info:{},st=d.event=='onStateChange'?d.info:i.playerState;"
                + "if(typeof st=='number')s('state '+st);"
                // Playing: subtitles off and the sound up the first time; it shows 1.5 s later (YouTube's title gone).
                + "if(st===1&&!near){if(!tuned){tuned=1;cmd('unloadModule',['captions']);cmd('unloadModule',['cc']);"
                + "if(!M){cmd('unMute');cmd('setVolume',[100])}}"
                + "if(!shown){shown=1;setTimeout(function(){if(!near){b.classList.add('on');s('playing')}},1500)}else b.classList.add('on')}"
                // Paused right after the sound went up: not allowed here, so muted again and on it goes.
                + "if(st===2&&tuned&&!M&&!quiet&&!(i.currentTime>8)){quiet=1;s('no sound allowed, muted');cmd('mute');cmd('playVideo');return}"
                + "if(st===2&&going&&!shown)cmd('playVideo');"
                + "if(st===0){near=1;off();s('ended')}"
                // 6 s before the end it fades out: YouTube's end screen (other videos) never shows.
                + "if(!near&&i.duration>20&&i.currentTime&&i.duration-i.currentTime<6){near=1;off();s('ended')}"
                + "});"
                // Until the player answers, it's told we listen (as YouTube's own IFrame API does).
                + "var tries=0;function listen(){if(heard||tries++>60)return;"
                + "try{f.contentWindow.postMessage(JSON.stringify({event:'listening',id:1,channel:'widget'}),'*')}catch(e){}setTimeout(listen,250)}"
                + "f.onload=function(){s('loaded');listen()};"
                + "</script></body></html>";
    }

    private void state(int gen, String s) {
        if (gen != generation || web == null) return;
        AppLog.w("Trailer: " + s);
        // It runs (state 1): it shows by itself 1.5 s later, so the "didn't start in time" count stops here.
        if ("state 1".equals(s)) ui.removeCallbacks(giveUp);
        if ("ready".equals(s)) {
            ready = true;
            // The page can take a few seconds on a TV: from YouTube's first answer it has 8 s more to start.
            ui.removeCallbacks(giveUp);
            ui.postDelayed(giveUp, READY_MS);
            if (going) web.evaluateJavascript("fmtGo()", null);
        } else if ("playing".equals(s)) {
            ui.removeCallbacks(giveUp);
            if (!playing) {
                AppLog.w("Trailer: shown at " + web.getLeft() + "," + web.getTop() + " " + web.getWidth() + "x" + web.getHeight()
                        + (web.getVisibility() == View.VISIBLE ? "" : " (not visible)")
                        + ", GPU " + web.isHardwareAccelerated() + ", layer " + web.getLayerType());
                playing = true;
                host.trailerPlaying();
            }
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
        if (!playing) host.trailerNone(wantedPick);     // none of them played: the title card with the backdrop
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
        waits = 0;
        ui.removeCallbacks(prepare);
        ui.removeCallbacks(go);
        hide();
    }

    private void hide() {
        ui.removeCallbacks(giveUp);
        ready = going = false;
        if (playing) {
            playing = false;
            host.trailerGone();
        }
        if (web == null || web.getVisibility() == View.GONE) return;
        web.setVisibility(View.GONE);
        web.loadUrl("about:blank");     // stops the video and its sound
    }

    /** It plays and shows (the title card can grow it). */
    boolean playing() {
        return playing;
    }

    /** Moves it (the title card grows it to full screen and back). */
    void place(RectF to) {
        if (web == null || web.getVisibility() == View.GONE) return;
        if (Math.abs(rect.left - to.left) < 0.5f && Math.abs(rect.top - to.top) < 0.5f
                && Math.abs(rect.right - to.right) < 0.5f && Math.abs(rect.bottom - to.bottom) < 0.5f) return;
        rect.set(to);
        measure();
        layout();
    }

    /** The video's corner radius, in screen pixels (0 at full screen). */
    void round(float radius) {
        if (web == null || web.getVisibility() == View.GONE) return;
        int px = Math.round(radius / host.getResources().getDisplayMetrics().density);
        web.evaluateJavascript("window.fmtRound&&fmtRound(" + px + ")", null);
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
        for (View v : new View[] {web}) {
            v.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(rect.width())), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(rect.height())), View.MeasureSpec.EXACTLY));
        }
    }

    void layout() {
        if (web == null) return;
        int l = Math.round(rect.left), t = Math.round(rect.top);
        for (View v : new View[] {web}) v.layout(l, t, l + v.getMeasuredWidth(), t + v.getMeasuredHeight());
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
        userAgent = s.getUserAgentString();
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

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return embedPage(r);
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

    /**
     * YouTube picks AV1 or VP9 when the browser says it can decode them; many TVs decode those in hardware only toward
     * their own video player, and inside a WebView the picture stays black while the sound plays (seen on a TCL Google
     * TV: no video layer on screen, the WebView drawing with the GPU, still nothing). So the embed page is fetched here
     * and a first script tells YouTube's player that only H.264 (which every TV shows anywhere) is available.
     * On a background thread (WebView calls it there); anything unexpected lets the WebView load the page itself.
     */
    private WebResourceResponse embedPage(WebResourceRequest r) {
        Uri u = r.getUrl();
        if (!"GET".equals(r.getMethod()) || u.getHost() == null || !u.getHost().endsWith("youtube-nocookie.com")
                || u.getPath() == null || !u.getPath().startsWith("/embed/")) return null;
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(u.toString()).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            for (java.util.Map.Entry<String, String> h : r.getRequestHeaders().entrySet()) {
                c.setRequestProperty(h.getKey(), h.getValue());
            }
            if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
            if (c.getRequestProperty("Referer") == null) c.setRequestProperty("Referer", SITE);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (c.getResponseCode() != 200) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            in.close();
            String html = out.toString("UTF-8");
            // YouTube's page allows only scripts with its nonce: ours carries the same.
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("nonce=\"([^\"]+)\"").matcher(html);
            String nonce = m.find() ? " nonce=\"" + m.group(1) + "\"" : "";
            String script = "<script" + nonce + ">(function(){var bad=/vp0?9|av01|av1\\b/i;"
                    + "function wrap(o){if(!o||!o.isTypeSupported)return;var f=o.isTypeSupported.bind(o);"
                    + "o.isTypeSupported=function(t){return bad.test(t)?false:f(t)}}"
                    + "try{wrap(window.MediaSource)}catch(e){}try{wrap(window.ManagedMediaSource)}catch(e){}"
                    + "try{var p=HTMLMediaElement.prototype,c=p.canPlayType;p.canPlayType=function(t){return bad.test(t)?'':c.call(this,t)}}catch(e){}"
                    + "try{var mc=navigator.mediaCapabilities,d=mc&&mc.decodingInfo&&mc.decodingInfo.bind(mc);if(d)mc.decodingInfo=function(x){"
                    + "var t=x&&x.video&&x.video.contentType||'';return bad.test(t)?Promise.resolve({supported:false,smooth:false,powerEfficient:false}):d(x)}}catch(e){}"
                    + "})()</script>";
            int head = html.indexOf("<head");
            int at = head < 0 ? -1 : html.indexOf('>', head) + 1;
            html = at > 0 ? html.substring(0, at) + script + html.substring(at) : script + html;
            AppLog.w("Trailer: embed page served with H.264 only" + (nonce.isEmpty() ? " (no nonce)" : ""));
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse("text/html", "utf-8", 200, "OK", headers,
                    new java.io.ByteArrayInputStream(html.getBytes("UTF-8")));
        } catch (Exception e) {
            AppLog.w("Trailer: embed page as is (" + e + ")");
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
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
