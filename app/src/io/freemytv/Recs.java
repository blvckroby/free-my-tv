package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, cover = "", poster = "", logo = "", meta = "", app, pkg;   // backdrop: wide card; poster: tall cards (TMDB)
        boolean detailed;
        int face;               // sport cards: title font (0 normal, 1 Formula 1, 2 MotoGP)
        String flag = "", place = "";   // sport cards (F1, MotoGP): the circuit's country flag (URL) and place
        int gradTop, gradBottom;   // sport cards: a vertical gradient (0 = the usual diagonal one)
        boolean flat;           // sport cards: one flat color instead of a gradient
        int accent;             // sport cards: the competition's color
        String label = "";      // sport cards: the competition's name
        String home = "", away = "", logoHome = "", logoAway = "", logoComp = "", art = "";   // sport cards: teams or athletes, logos (URLs)
        // Sport cards, the scoreboard: the big line (kick-off time or the minute), the lines above and below it, the day
        // ("Today"), the start as HH:mm, the race's name and session, and for the "Next" list the competition's dot color.
        boolean live;
        long start;
        String board = "", boardTop = "", boardSub = "", day = "", clock = "", raceName = "", session = "";
        String homeShort = "", awayShort = "", raceLine = "";   // the "Next" list: surnames for tennis; races: session, place
        int dot;
        Sports.Event event;
        boolean posterClean, coverClean;   // textless images (BetterPoster style): the title's logo goes on top
        boolean top;            // in the country's Top 10 (Movies and Shows tabs): cover and poster, whatever app plays it
        boolean taste;          // picked from the TMDB profile (Explore)
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMAGES = "https://image.tmdb.org/t/p/original";
    private static final String POSTERS = "https://image.tmdb.org/t/p/w780";
    private static final String COVERS = "https://image.tmdb.org/t/p/w1280";
    private static final String LOGOS = "https://image.tmdb.org/t/p/w500";
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** How many titles the home screen's "For You" row shows. */
    static final int FOR_YOU = 10;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 7;
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    static final String[] PERIOD_LABEL = {"PARA ESTA MANHÃ", "PARA ESTA TARDE", "PARA ESTA NOITE"};
    static final String[] PERIOD_LABEL_EN = {"THIS MORNING", "THIS AFTERNOON", "TONIGHT"};
    static final String[] PERIOD_LABEL_IT = {"PER QUESTA MATTINA", "PER QUESTO POMERIGGIO", "PER STASERA"};
    static final String[] PERIOD_LABEL_ES = {"PARA ESTA MAÑANA", "PARA ESTA TARDE", "PARA ESTA NOCHE"};
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    private static final String REGION = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>();   // this hour's, built and ready to draw
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile List<Pick> topMovies = new ArrayList<>(), topShows = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            String tm = cache.getString("topMovies", null), ts = cache.getString("topShows", null);
            if (tm != null) topMovies = parse(new JSONArray(tm));
            if (ts != null) topShows = parse(new JSONArray(ts));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        return (Ui.it ? PERIOD_LABEL_IT : Ui.es ? PERIOD_LABEL_ES : Ui.en ? PERIOD_LABEL_EN : PERIOD_LABEL)[period(System.currentTimeMillis())];
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** This hour's 3, movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    List<Pick> now(String kind) {
        return now(kind, 3);
    }

    /** "For You" row: up to `max` of the list for this hour (same rotation and franchise rule as the three). */
    List<Pick> forYou(int max) {
        return now(null, max);
    }

    private List<Pick> now(String kind, int max) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;    // no TMDB key: no recommendations
        Map<String, Boolean> have = new HashMap<>();   // one look per app, not per title (this runs every 15 s)
        boolean tasteOnly = false;
        if (kind == null) for (Pick p : picks) tasteOnly |= p.taste && p.detailed && p.pkg != null && installed(p.pkg);
        for (Pick p : picks) {
            if (tasteOnly && !p.taste) continue;    // Explore follows the TMDB profile once it has titles to show
            if ((kind == null || kind.equals(p.kind))
                    && (!p.detailed || (p.pkg != null && have.computeIfAbsent(p.pkg, this::installed)))) list.add(p);   // app missing: skip
        }
        if (list.isEmpty()) return list;
        long hour = System.currentTimeMillis() / 3600_000L;
        // Three per hour, never two from the same franchise ("Law & Order" and "Law & Order: Special…").
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < list.size() && out.size() < max; i++) {
            Pick p = list.get((int) ((hour * 3 + i) % list.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = imageKey(p);    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    /** Top 10 picks have their own images (cover plus poster, no wide card): never shared with the same title in Explore. */
    private static String imageKey(Pick p) {
        return (p.top ? "top" : "") + p.backdrop;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    /** The vertical TMDB poster on disk (w780 is plenty for a 336 px card), or null when the title has none. */
    private File posterFile(Pick p) {
        return p.poster.isEmpty() ? null : new File(new File(context.getCacheDir(), "recs"), "poster" + p.poster.replace("/", ""));
    }

    private File logoFile(Pick p) {
        return p.logo.isEmpty() ? null : new File(new File(context.getCacheDir(), "recs"), "logo" + p.logo.replace("/", ""));
    }

    private File coverFile(Pick p) {
        return p.cover.isEmpty() ? null : new File(new File(context.getCacheDir(), "recs"), "cover" + p.cover.replace("/", ""));
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), (p.top ? "top" : "") + p.backdrop.replace("/", ""));
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = sourceStamp();   // a new key, country or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || !listLanguage.equals(Ui.language);
        boolean missing = false;
        for (Pick pick : wantedAll()) missing |= !pick.detailed || !hasImage(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) fetchList(p);
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wantedAll()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (pick.pkg != null || pick.top) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && have.computeIfAbsent(p.pkg, this::installed) && hasImage(p)
                    && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** The country's Top 10 ("movie" or "tv"), in order, for the Movies and Shows tabs. */
    List<Pick> top(String kind) {
        if (!hasKey(context)) return new ArrayList<>();
        return "movie".equals(kind) ? topMovies : topShows;
    }

    /** Everything the home screen needs images for: this hour's picks and both Top 10 lists. */
    List<Pick> wantedAll() {
        List<Pick> out = wanted();
        out.addAll(top("movie"));
        out.addAll(top("tv"));
        return out;
    }

    /** This hour's picks for the three tabs (Explore, Movies, Shows), without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {null, "movie", "tv"}) {
            for (Pick p : kind == null ? forYou(FOR_YOU) : now(kind)) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    /** Something outside Recs (the sports card) changed what Explore shows. */
    void changed() {
        notifyListeners();
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty()) throw new IOException("none of the TV's streaming services are on TMDB");
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String common = "&language=" + lang() + "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8");
        // 1. Trending this week (TMDB goes by what people are watching and searching for right now), only what is
        //    on one of the TV's services: the details say where (and are then ready).
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < hot.length() && i < 20; i++) add(candidates, hot.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg != null) (kind.equals("movie") ? hotMovies : hotShows).add(pick);
            }
        }
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months.
        JSONArray movies = get("/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray shows = get("/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0)
                + common).getJSONArray("results");
        List<Pick> newMovies = new ArrayList<>(), newShows = new ArrayList<>();
        for (int i = 0; i < movies.length(); i++) add(newMovies, movies.getJSONObject(i), "movie");
        for (int i = 0; i < shows.length(); i++) add(newShows, shows.getJSONObject(i), "tv");
        // 3. Acclaimed titles to discover: well rated, with plenty of votes, a different page each day so the same
        //    ones don't come back (what people already watch a lot is in the trending list above).
        int page = 1 + (int) ((System.currentTimeMillis() / 86_400_000L) % 3);
        String best = "&sort_by=vote_average.desc&page=" + page + common.replaceAll("&sort_by=[a-z._]+", "");
        JSONArray bestMovies = get("/discover/movie?vote_count.gte=1500&primary_release_date.gte=" + day(-365 * 8)
                + best).getJSONArray("results");
        JSONArray bestShows = get("/discover/tv?vote_count.gte=500&first_air_date.gte=" + day(-365 * 8)
                + best).getJSONArray("results");
        List<Pick> gemMovies = new ArrayList<>(), gemShows = new ArrayList<>();
        for (int i = 0; i < bestMovies.length(); i++) add(gemMovies, bestMovies.getJSONObject(i), "movie");
        for (int i = 0; i < bestShows.length(); i++) add(gemShows, bestShows.getJSONObject(i), "tv");
        // Each source gets its share (up to 10 trending, 10 new, 10 to discover); movies and shows alternating, no repeats.
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        // Explore follows the TMDB profile (favorites, ratings, watchlist) when config.properties has a session.
        List<Pick> tasteMovies = new ArrayList<>(), tasteShows = new ArrayList<>();
        try {
            fetchTaste(tasteMovies, tasteShows);
        } catch (Exception e) {
            AppLog.w("TMDB profile: " + e.getMessage());   // Explore then goes on with the trending list
        }
        List<Pick> candidates = new ArrayList<>();
        alternate(candidates, new java.util.HashSet<>(), tasteMovies, tasteShows, 24);
        // Only what plays on an app the TV has (the details say where), up to 12.
        List<Pick> mine = new ArrayList<>();
        for (Pick t : candidates) {
            if (mine.size() >= 12) break;
            try {
                fetchDetails(t);
            } catch (Exception e) {
                continue;
            }
            if (t.pkg != null && seen.add(key(t))) {
                t.taste = true;
                mine.add(t);
            }
        }
        list.addAll(mine);
        alternate(list, seen, hotMovies, hotShows, 10);
        alternate(list, seen, newMovies, newShows, 20);
        alternate(list, seen, gemMovies, gemShows, 30);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        topMovies = fetchTop("movie");
        topShows = fetchTop("tv");
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    /**
     * The country's Top 10: what is most popular right now in the region on its streaming services (TMDB has no
     * official per-country chart; its popularity is what people are watching and searching for lately).
     */
    private List<Pick> fetchTop(String kind) throws Exception {
        JSONArray results = get("/discover/" + kind + "?language=" + lang() + "&region=" + REGION + "&watch_region=" + REGION
                + "&with_watch_monetization_types=flatrate%7Cfree%7Cads&include_adult=false&sort_by=popularity.desc"
                + "&vote_count.gte=50").getJSONArray("results");
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < results.length() && out.size() < 10; i++) {
            List<Pick> one = new ArrayList<>();
            add(one, results.getJSONObject(i), kind);
            if (one.isEmpty() || one.get(0).poster.isEmpty()) continue;
            Pick p = one.get(0);
            p.top = true;
            try {
                fetchDetails(p);    // genre, runtime and the app on the TV that plays it, if any
            } catch (Exception ignored) {
            }
            out.add(p);
        }
        return out;
    }

    /**
     * Titles for Explore from the TMDB profile behind config.properties "tmdb_session": the watchlist first, then
     * what TMDB recommends from the favorites and the best rated titles. Nothing the profile has already rated.
     */
    private void fetchTaste(List<Pick> movies, List<Pick> shows) throws Exception {
        String session = Config.TMDB_SESSION.trim();
        if (session.isEmpty()) return;
        String s = "session_id=" + URLEncoder.encode(session, "UTF-8");
        int account = get("/account?" + s).getInt("id");
        java.util.Set<String> known = new java.util.HashSet<>();
        List<String[]> seeds = new ArrayList<>();      // {kind, id}
        for (String kind : new String[] {"movie", "tv"}) {
            String path = kind.equals("movie") ? "movies" : "tv";
            String base = "/account/" + account + "/";
            JSONArray favorites = get(base + "favorite/" + path + "?" + s).getJSONArray("results");
            JSONArray rated = get(base + "rated/" + path + "?" + s).getJSONArray("results");
            JSONArray watchlist = get(base + "watchlist/" + path + "?language=" + lang() + "&" + s).getJSONArray("results");
            for (int i = 0; i < favorites.length(); i++) {
                known.add(kind + ":" + favorites.getJSONObject(i).optInt("id"));
                seeds.add(new String[] {kind, String.valueOf(favorites.getJSONObject(i).optInt("id"))});
            }
            for (int i = 0; i < rated.length(); i++) {
                JSONObject o = rated.getJSONObject(i);
                known.add(kind + ":" + o.optInt("id"));
                if (o.optDouble("rating", 0) >= 8) seeds.add(new String[] {kind, String.valueOf(o.optInt("id"))});
            }
            List<Pick> mine = kind.equals("movie") ? movies : shows;
            for (int i = 0; i < watchlist.length() && mine.size() < 6; i++) add(mine, watchlist.getJSONObject(i), kind);
        }
        // A few seeds, different each day, each with the titles TMDB recommends after it.
        java.util.Collections.shuffle(seeds, new java.util.Random(System.currentTimeMillis() / 86_400_000L));
        for (int i = 0; i < seeds.size() && i < 6; i++) {
            String kind = seeds.get(i)[0];
            JSONArray more = get("/" + kind + "/" + seeds.get(i)[1] + "/recommendations?language=" + lang()).getJSONArray("results");
            List<Pick> mine = kind.equals("movie") ? movies : shows;
            int added = 0;
            for (int j = 0; j < more.length() && added < 4; j++) {
                JSONObject o = more.getJSONObject(j);
                if (known.contains(kind + ":" + o.optInt("id"))) continue;
                int before = mine.size();
                add(mine, o, kind);
                if (mine.size() > before) added++;
            }
        }
    }

    /** Language of titles and genres; the services come from the region. */
    private static String lang() {
        if (Ui.it) return "it-IT";
        if (Ui.es) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        return Ui.en ? "en-US" : "pt-BR";
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        p.cover = backdrop;     // TMDB's own horizontal cover, kept for the wide card (backdrop may be swapped for a textless one)
        String poster = o.optString("poster_path", "");
        p.poster = poster.isEmpty() || "null".equals(poster) ? "" : poster;
        list.add(p);
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images&include_image_language=null," + Ui.language + ",en");
        JSONObject images = o.optJSONObject("images");
        // BetterPoster style: the title's textless background and poster, with its logo (in the chosen language) on top.
        String logo = bestLogo(images), clean = bestImage(images, "backdrops"), cleanPoster = bestImage(images, "posters");
        String best = clean != null ? clean : bestBackdrop(images);
        if (best != null) {
            p.backdrop = best;
            p.cover = best;
            p.coverClean = clean != null && logo != null;
        }
        p.posterClean = cleanPoster != null && logo != null;
        if (p.posterClean) p.poster = cleanPoster;      // without a logo to put on it, the poster keeps its own title
        p.logo = logo == null ? "" : logo;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Série", "Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(Ui.en ? name : genre(name));
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0 && Ui.en) parts.add(minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m");
            else if (minutes > 0) parts.add(minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min");
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0 && Ui.en) parts.add(seasons == 1 ? "1 season" : seasons + " seasons");
            else if (seasons > 0 && Ui.it) parts.add(seasons == 1 ? "1 stagione" : seasons + " stagioni");
            else if (seasons > 0) parts.add(seasons == 1 ? "1 temporada" : seasons + " temporadas");
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        if (p.app != null) parts.add(SERVICE_NAME.get(p.app));
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    /** TV genres that TMDB returns in English even in pt-BR. */
    private static final String[][] GENRES_PT = {
        {"Action & Adventure", "Ação e Aventura"}, {"Sci-Fi & Fantasy", "Ficção Científica e Fantasia"},
        {"War & Politics", "Guerra e Política"}, {"Kids", "Infantil"}, {"News", "Notícias"}, {"Soap", "Novela"},
        {"Talk", "Entrevistas"},
    };
    private static final String[][] GENRES_IT = {
        {"Action & Adventure", "Azione e Avventura"}, {"Sci-Fi & Fantasy", "Fantascienza e Fantasy"},
        {"War & Politics", "Guerra e Politica"}, {"Kids", "Bambini"}, {"News", "Notizie"},
        {"Soap", "Soap opera"}, {"Talk", "Talk show"},
    };
    private static final String[][] GENRES_ES = {
        {"Action & Adventure", "Acción y Aventura"}, {"Sci-Fi & Fantasy", "Ciencia ficción y Fantasía"},
        {"War & Politics", "Guerra y Política"}, {"Kids", "Infantil"}, {"News", "Noticias"},
        {"Soap", "Telenovela"}, {"Talk", "Entrevistas"},
    };

    private static String genre(String name) {
        for (String[] g : Ui.it ? GENRES_IT : Ui.es ? GENRES_ES : GENRES_PT) if (g[0].equalsIgnoreCase(name)) return g[1];
        return name;
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), at the highest resolution
     * (up to 3840, TMDB's 4K) and, on a tie, the highest rated.
     */
    /** The textless image of a kind ("backdrops" or "posters") with the highest resolution and rating, or null. */
    private static String bestImage(JSONObject images, String kind) {
        JSONArray list = images == null ? null : images.optJSONArray(kind);
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty() || !b.isNull("iso_639_1")) continue;
            double score = Math.min(b.optInt("width"), 3840) * 100 + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    /** The title's logo in the language of the screen (then English, then one with no language); PNG only. */
    private static String bestLogo(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("logos");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            String path = b == null ? "" : b.optString("file_path", "");
            if (path.isEmpty() || !path.toLowerCase(Locale.ROOT).endsWith(".png")) continue;
            String lang = b.isNull("iso_639_1") ? "" : b.optString("iso_639_1");
            int rank = lang.equals(Ui.language) ? 3 : lang.equals("en") ? 2 : lang.isEmpty() ? 1 : 0;
            if (rank == 0) continue;
            double score = rank * 1e6 + Math.min(b.optInt("width"), 2000) * 10 + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = path;
            }
        }
        return best;
    }

    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 3840) * 100 + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    private static String[] service(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String[] s : SERVICES) {
            if (s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0])) return s;
        }
        return null;
    }

    private boolean installed(String pkg) {
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private long sourceStamp() {
        long stamp = Config.TMDB_KEY.hashCode() * 31L + REGION.hashCode();
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
        return stamp;
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private String readKey() {
        String key = Config.TMDB_KEY.trim();
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        File f = imageFile(p);
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        if (f.exists()) {
            try {
                downloadPoster(p);   // images saved before the posters existed
            } catch (IOException e) {
                AppLog.w("poster: " + e.getMessage());
            }
            return;
        }
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL((p.top ? COVERS : IMAGES) + p.backdrop).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        try {
            downloadPoster(p);
        } catch (IOException e) {
            AppLog.w("poster: " + e.getMessage());   // the tall cards then use the photo
        }
        prune(f.getParentFile());
    }

    private void downloadPoster(Pick p) throws IOException {
        fetch(posterFile(p), POSTERS + p.poster);
        if (!p.top && !p.cover.equals(p.backdrop)) fetch(coverFile(p), COVERS + p.cover);   // else the main image is the cover
        fetch(logoFile(p), LOGOS + p.logo);
    }

    private static void fetch(File f, String url) throws IOException {
        if (f == null || f.exists()) return;
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
    }

    /** Reads the original (≥ 1920 wide) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(imageKey(p))) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight);
            boolean forLight = light;
            Bitmap poster = null;
            File pf = posterFile(p);
            if (forLight && pf != null && pf.exists()) poster = BitmapFactory.decodeFile(pf.getPath());   // null if unreadable
            Bitmap cover = null;
            File cf = coverFile(p);
            if (forLight && !p.top && !p.cover.equals(p.backdrop) && cf != null && cf.exists()) cover = BitmapFactory.decodeFile(cf.getPath());
            Bitmap logo = null;
            File lf = logoFile(p);
            if (forLight && lf != null && lf.exists()) logo = BitmapFactory.decodeFile(lf.getPath());
            Backdrop backdrop = Backdrop.make(b, forLight, poster, cover, p.top, p.coverClean ? logo : null, p.posterClean ? logo : null);
            if (logo != null) logo.recycle();
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(imageKey(p), backdrop);
                while (images.size() > (forLight ? 30 : 4)) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= 90) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 90; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            cache.edit().putString("picks", toJson(picks)).putString("topMovies", toJson(topMovies))
                    .putString("topShows", toJson(topShows)).putInt("period", period).putLong("listAt", listAt)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION).apply();
        } catch (Exception ignored) {
        }
    }

    private static String toJson(List<Pick> list) throws org.json.JSONException {
        JSONArray a = new JSONArray();
        for (Pick p : list) {
            a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                    .put("cover", p.cover).put("poster", p.poster).put("logo", p.logo)
                    .put("posterClean", p.posterClean).put("coverClean", p.coverClean)
                    .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                    .put("detailed", p.detailed).put("top", p.top).put("taste", p.taste));
        }
        return a.toString();
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.poster = o.optString("poster");
            p.cover = o.optString("cover");
            p.logo = o.optString("logo");
            p.posterClean = o.optBoolean("posterClean");
            p.coverClean = o.optBoolean("coverClean");
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            p.top = o.optBoolean("top");
            p.taste = o.optBoolean("taste");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
