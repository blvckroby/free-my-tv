package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sports on the Explore tab: the next match or race worth showing, from free sources.
 *  - Serie A and Champions League: football-data.org (needs config.properties "football_key", free);
 *  - Formula 1: Jolpica (the Ergast successor, no key);
 *  - MotoGP and tennis finals: TheSportsDB's free key (partial calendars).
 * One event at most becomes the scoreboard (a Recs.Pick of kind "sport"), up to 4 days ahead, with the next ones beside it;
 * films and shows stay in their tabs. Which app opens it comes from config.properties "sports_apps" (first one installed).
 */
final class Sports {
    private static final long EVERY = 30 * 60_000L, FAST = 5 * 60_000L, AHEAD = 4 * 86_400_000L, WEEK = 7 * 86_400_000L;
    static final int SERIE_A = 0, CHAMPIONS = 1, EUROPA = 2, F1 = 3, MOTO = 4, TENNIS = 5, PREMIER = 6, LALIGA = 7, BUNDES = 8, LIGUE1 = 9;   // 0 to 5 also the priority order
    private static final int[] ACCENT = {0xFF0B5FA5, 0xFF1B2A8A, 0xFFE8650A, 0xFF14141C, 0xFF0E0E12, 0xFF2E8B57, 0xFF37003C, 0xFFEE8707, 0xFFD3010C, 0xFF091C3E};

    private static Sports instance;

    static synchronized Sports get(Context c) {
        if (instance == null) instance = new Sports(c.getApplicationContext());
        return instance;
    }

    /** One match, race or session. */
    static final class Event {
        int sport, italian;      // italian: 3 for an Italian club, rider, driver or player (the screen is in Italian)
        long start, minutes;     // minutes: how long it lasts, to move on to the next one when it is over
        String competition, title, home = "", away = "", logoHome = "", logoAway = "", logoComp = "", art = "", country = "", place = "";
        boolean live, done;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService art = Executors.newSingleThreadExecutor();     // logos, apart from the schedule fetch
    private final java.util.Map<String, android.graphics.Bitmap> bitmaps = new java.util.LinkedHashMap<>();
    private final java.util.Set<String> loading = new java.util.HashSet<>();
    private final java.util.Map<String, Long> failedAtMap = new java.util.HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile List<Event> events = new ArrayList<>();
    private volatile long fetchedAt;
    private volatile boolean fetching;
    /** A source failed on the last look (no internet, a refusal): look again sooner. */
    private volatile boolean partial;
    /** Bump when the sources or how they are read change (the saved events are dropped and fetched again). */
    private static final int SOURCES_VERSION = 2;

    private Sports(Context c) {
        context = c;
        cache = c.getSharedPreferences("sports", Context.MODE_PRIVATE);
        // A new football key or a new way of reading the sources: what was saved is stale, fetch again right away.
        String stamp = SOURCES_VERSION + ":" + Config.FOOTBALL_KEY.trim().hashCode();
        if (!stamp.equals(cache.getString("stamp", ""))) {
            cache.edit().remove("events2").remove("at2").putString("stamp", stamp).apply();
        }
        try {
            fetchedAt = cache.getLong("at2", 0);
            JSONArray a = new JSONArray(cache.getString("events2", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Event e = event(o.getInt("sport"), o.getLong("start"), o.getString("competition"), o.getString("title"));
                e.minutes = o.optLong("minutes", 120);
                e.italian = o.optInt("italian");
                e.live = o.optBoolean("live");
                e.home = o.optString("home");
                e.away = o.optString("away");
                e.logoHome = o.optString("logoHome");
                e.logoAway = o.optString("logoAway");
                e.logoComp = o.optString("logoComp");
                e.art = o.optString("art");
                e.country = o.optString("country");
                e.place = o.optString("place");
                events.add(e);
            }
        } catch (Exception ignored) {
        }
    }

    private static Event event(int sport, long start, String competition, String title) {
        Event e = new Event();
        e.sport = sport;
        e.start = start;
        e.competition = competition;
        e.title = title;
        return e;
    }

    /**
     * The Explore card for the event that is on now or next, or null. One that has finished gives way to the next at
     * once. With the screen in Italian (or the country Italy), Italian clubs, riders, drivers and players come first
     * among events at the same time.
     */
    Recs.Pick pick() {
        return pick(null);
    }

    /** The scoreboard's event: `chosen` (picked from the "Next" list) while it isn't over, otherwise the best one. */
    Recs.Pick pick(Event chosen) {
        if (!Config.SPORTS) return null;
        long now = System.currentTimeMillis();
        if (chosen != null && !chosen.done && now < chosen.start + chosen.minutes * 60_000L) return pickFor(chosen, now);
        boolean italy = Ui.it || "IT".equalsIgnoreCase(Config.TMDB_REGION.trim());
        Event best = null;
        for (Event e : events) {
            if (e.done || now >= e.start + e.minutes * 60_000L || e.start > now + AHEAD) continue;
            if (best == null || before(e, best, now, italy)) best = e;
        }
        return best == null ? null : pickFor(best, now);
    }

    /** The "Next" list beside the scoreboard: up to `max` events of the coming days, in the scoreboard's order. */
    List<Recs.Pick> next(Event exclude, int max) {
        List<Recs.Pick> out = new ArrayList<>();
        if (!Config.SPORTS) return out;
        long now = System.currentTimeMillis();
        boolean italy = Ui.it || "IT".equalsIgnoreCase(Config.TMDB_REGION.trim());
        List<Event> list = new ArrayList<>();
        for (Event e : events) {
            if (e == exclude || e.done || now >= e.start + e.minutes * 60_000L || e.start > now + 4 * 86_400_000L) continue;
            list.add(e);
        }
        java.util.Collections.sort(list, (a, b) -> a == b ? 0 : before(a, b, now, italy) ? -1 : 1);
        for (int i = 0; i < list.size() && out.size() < max; i++) {
            Recs.Pick p = pickFor(list.get(i), now);
            p.kind = "row";
            out.add(p);
        }
        return out;
    }

    /** File names (without extension) of each competition's picture in app/assets/sports and logo in app/assets/sports/logos. */
    private static final String[] ASSET = {"seriea", "champions", "europa", "f1", "motogp", "tennis", "premier", "laliga", "bundesliga", "ligue1"};
    private static final String[] LOGO_ASSET = {"seriea", "champions", "europa", "formula1", "motogp", "tennis", "premier", "laliga", "bundesliga", "ligue1"};
    /** The dot beside each competition in the "Next" list. */
    private static final int[] DOT = {0xFF38A8BC, 0xFF5B7BFF, 0xFFF07A1A, 0xFFE10600, 0xFFE8590C, 0xFF3BAA6C, 0xFFA64CC8, 0xFFEE8707, 0xFFFF2D3A, 0xFF2F6BFF};
    private java.util.Set<String> sportAssets, logoAssets;

    /** "asset:sports/<dir>/<name>.<ext>" for the first of webp, jpg, png that the app has, or "". The listing is read once. */
    private String asset(boolean logo, String name) {
        if (sportAssets == null) {
            sportAssets = list("sports");
            logoAssets = list("sports/logos");
        }
        java.util.Set<String> have = logo ? logoAssets : sportAssets;
        for (String ext : logo ? new String[] {"png", "webp"} : new String[] {"webp", "jpg", "png"}) {
            if (have.contains(name + "." + ext)) return "asset:sports/" + (logo ? "logos/" : "") + name + "." + ext;
        }
        return "";
    }

    private java.util.Set<String> list(String dir) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            String[] names = context.getAssets().list(dir);
            if (names != null) java.util.Collections.addAll(out, names);
        } catch (IOException ignored) {
        }
        return out;
    }

    /** The card for one event, with the scoreboard's lines worked out for `now`. */
    private Recs.Pick pickFor(Event best, long now) {
        String[] opener = opener();
        String pkg = opener[0], app = opener[1];
        Recs.Pick p = new Recs.Pick();
        p.kind = "sport";
        p.home = best.home;
        p.away = best.away;
        p.logoHome = best.logoHome;
        p.logoAway = best.logoAway;
        // The person's own logo (app/assets/sports/logos) wins over the one found online; it keeps its colors.
        String ownLogo = asset(true, LOGO_ASSET[best.sport]);
        p.logoComp = ownLogo.isEmpty() ? best.logoComp : ownLogo;
        // The competition's own picture is chosen here, not saved with the event, so a new picture applies at once:
        // the link in config.properties, then the picture in app/assets/sports, then the one found online.
        String bundled = asset(false, ASSET[best.sport]);
        p.art = best.sport == CHAMPIONS ? art(Config.CHAMPIONS_ART, bundled)
                : best.sport == EUROPA ? art(Config.EUROPA_ART, bundled)
                : best.sport == SERIE_A ? art(Config.SERIEA_ART, bundled)
                : !bundled.isEmpty() ? bundled : best.sport == MOTO ? MOTOGP_BAR : best.art;
        p.title = best.title;
        p.accent = ACCENT[best.sport];
        p.flat = best.sport == F1 || best.sport == MOTO;     // Formula 1 and MotoGP: a flat color with their artwork over it
        p.face = best.sport == F1 ? 1 : best.sport == MOTO ? 2 : 0;
        if (best.sport == SERIE_A) {      // Serie A: a vertical gradient, light blue at the bottom (under its picture)
            p.gradTop = 0xFF28519F;
            p.gradBottom = 0xFF38A8BC;
        }
        p.place = best.place;
        p.flag = best.country.isEmpty() ? "" : "https://flagcdn.com/w160/" + best.country + ".png";
        p.label = best.competition;
        p.pkg = pkg.isEmpty() ? null : pkg;
        p.detailed = true;
        p.meta = when(best.start, now) + (app.isEmpty() ? "" : " · " + app);
        board(p, best, now);
        p.id = (p.title + p.meta + p.board + p.boardSub).hashCode();
        return p;
    }

    /** The scoreboard's lines: before the start, the kick-off time and a countdown; during it, the (estimated) minute. */
    private static void board(Recs.Pick p, Event e, long now) {
        java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("HH:mm", Locale.US);
        p.start = e.start;
        p.event = e;
        p.dot = DOT[e.sport];
        p.clock = hm.format(new Date(e.start));
        p.day = day(e.start, now);
        p.live = e.start <= now;
        int cut = e.title.lastIndexOf(" · ");
        p.raceName = cut < 0 ? e.title : e.title.substring(0, cut);
        p.session = cut < 0 ? "" : e.title.substring(cut + 3);
        boolean race = e.sport == F1 || e.sport == MOTO;
        boolean football = !race && e.sport != TENNIS;
        p.homeShort = e.sport == TENNIS ? surname(e.home) : e.home;
        p.awayShort = e.sport == TENNIS ? surname(e.away) : e.away;
        p.raceLine = p.session.isEmpty() ? p.place : p.place.isEmpty() ? p.session : p.session + ", " + p.place;
        if (!p.live) {
            p.board = p.clock;
            p.boardTop = football ? Ui.tr("Pontapé inicial", "Kick-off", "Saque inicial", "Calcio d'inizio") : Ui.tr("Início", "Start", "Inicio", "Inizio");
            p.boardSub = countdown(e.start - now);
            return;
        }
        int m = (int) ((now - e.start) / 60_000L);
        p.boardTop = Ui.tr("Começou às ", "Started at ", "Empezó a las ", "Iniziata alle ") + p.clock;
        if (!football) {
            p.board = race ? "LIVE" : Ui.tr("Ao vivo", "Live", "En vivo", "In corso");
            p.boardSub = race ? Ui.tr("Ao vivo", "Live now", "En vivo", "In corso") : "";
            return;
        }
        // Football: 45 minutes, about 16 of break, then the second half (the free sources give no live clock).
        if (m < 46) {
            p.board = Math.max(1, m) + "'";
            p.boardSub = Ui.tr("Primeiro tempo", "First half", "Primer tiempo", "Primo tempo");
        } else if (m < 62) {
            p.board = Ui.tr("Intervalo", "Half-time", "Descanso", "Intervallo");
            p.boardSub = "";
        } else {
            p.board = m - 17 > 90 ? "90'+" : (m - 17) + "'";
            p.boardSub = Ui.tr("Segundo tempo", "Second half", "Segundo tiempo", "Secondo tempo");
        }
    }

    private static String surname(String name) {
        String n = name == null ? "" : name.trim();
        int sp = n.lastIndexOf(' ');
        return sp < 0 ? n : n.substring(sp + 1);
    }

    /** "in 4 h 12 min", "in 42 min", "in 2 days". */
    private static String countdown(long ms) {
        long m = Math.max(0, Math.round(ms / 60_000.0));
        if (m < 1) return Ui.tr("já vai começar", "starting soon", "a punto de empezar", "tra poco");
        String in = Ui.tr("em ", "in ", "en ", "tra ");
        if (m < 60) return in + m + " min";
        long h = m / 60;
        if (h < 24) return in + h + " h " + (m % 60 < 10 ? "0" : "") + (m % 60) + " min";
        long d = Math.round(h / 24.0);
        return d == 1 ? Ui.tr("amanhã", "in a day", "en un día", "tra un giorno") : in + d + " " + Ui.tr("dias", "days", "días", "giorni");
    }

    /** "Today", "Tomorrow" or the weekday, capitalized. */
    private static String day(long start, long now) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        a.setTimeInMillis(now);
        b.setTimeInMillis(start);
        if (a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR) && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)) {
            return Ui.tr("Hoje", "Today", "Hoy", "Oggi");
        }
        a.add(Calendar.DAY_OF_YEAR, 1);
        if (a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR) && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)) {
            return Ui.tr("Amanhã", "Tomorrow", "Mañana", "Domani");
        }
        String w = new SimpleDateFormat("EEEE", Ui.locale()).format(new Date(start));
        return w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    /** Whether `a` should be shown before `b`: what is on now first, then by start (half-hour slots), then by who plays. */
    private static boolean before(Event a, Event b, long now, boolean italy) {
        boolean aLive = a.start <= now, bLive = b.start <= now;
        if (aLive != bLive) return aLive;
        if (!aLive) {
            long slotA = a.start / (30 * 60_000L), slotB = b.start / (30 * 60_000L);
            if (slotA != slotB) return slotA < slotB;
        }
        int ia = italy ? a.italian : 0, ib = italy ? b.italian : 0;
        if (ia != ib) return ia > ib;
        if (a.sport != b.sport) return a.sport < b.sport;
        return a.start < b.start;
    }

    /** {package, name} of the first app of config "sports_apps" that is installed, or {"", ""}. */
    String[] opener() {
        android.content.pm.PackageManager pm = context.getPackageManager();
        for (String candidate : Config.SPORTS_APPS.split(",")) {
            String name = candidate.trim();
            if (name.isEmpty() || HomeApps.launchIntent(pm, name) == null) continue;
            try {
                return new String[] {name, String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(name, 0)))};
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return new String[] {name, ""};
            }
        }
        return new String[] {"", ""};
    }

    private static String when(long start, long now) {
        if (start <= now) return Ui.tr("Ao vivo", "Live now", "En vivo", "In diretta");
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        a.setTimeInMillis(now);
        b.setTimeInMillis(start);
        String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(start));
        if (a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR) && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)) {
            return Ui.tr("Hoje", "Today", "Hoy", "Oggi") + " " + time;
        }
        a.add(Calendar.DAY_OF_YEAR, 1);
        if (a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR) && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)) {
            return Ui.tr("Amanhã", "Tomorrow", "Mañana", "Domani") + " " + time;
        }
        return new SimpleDateFormat("EEE", Ui.locale()).format(new Date(start)) + " " + time;
    }

    /** Fetches again if the last look is older than 30 minutes; tells the home screen when it has something new. */
    void refreshIfStale() {
        if (!Config.SPORTS || fetching) return;
        long now = System.currentTimeMillis();
        boolean close = false;     // something is about to start or on: look more often (scores, finished matches)
        for (Event e : events) close |= now >= e.start - 15 * 60_000L && now < e.start + e.minutes * 60_000L;
        long every = close ? FAST : events.isEmpty() || partial ? 10 * 60_000L : EVERY;   // nothing yet, or a source failed
        if (now - fetchedAt < every && now >= fetchedAt) return;
        fetching = true;
        io.execute(() -> {
            boolean failed = false;
            try {
            List<Event> found = new ArrayList<>();
            long from = System.currentTimeMillis();
            String key = Config.FOOTBALL_KEY.trim();
            if (!key.isEmpty()) {
                try {
                    football(found, key, from);
                } catch (Exception e) {
                    AppLog.w("football: " + e.getMessage());
                    failed = true;
                }
            }
            try {
                formula1(found);
            } catch (Exception e) {
                AppLog.w("formula 1: " + e.getMessage());
                failed = true;
            }
            for (String sport : new String[] {"Motorsport", "Tennis", "Soccer"}) {
                try {
                    sportsDb(found, sport);
                } catch (Exception e) {
                    AppLog.w("sports (" + sport + "): " + e.getMessage());
                    failed = true;
                }
            }
            int withLogos = 0;
            for (Event e : found) if (!e.logoComp.isEmpty()) withLogos++;
            AppLog.w("sports: " + found.size() + " events, " + withLogos + " with a competition logo, football key "
                    + (key.isEmpty() ? "missing" : "present"));
            if (!found.isEmpty()) {     // all sources failing (no internet) keeps what we had
                events = found;
                save(found);
            }
            fetchedAt = System.currentTimeMillis();
            main.post(() -> Recs.get(context).changed());
            } finally {
                partial = failed;
                fetching = false;
                fetchedAt = Math.max(fetchedAt, System.currentTimeMillis());
            }
        });
    }

    private void save(List<Event> list) {
        try {
            JSONArray a = new JSONArray();
            for (Event e : list) {
                a.put(new JSONObject().put("sport", e.sport).put("start", e.start).put("competition", e.competition)
                        .put("title", e.title).put("minutes", e.minutes).put("italian", e.italian).put("live", e.live)
                        .put("home", e.home).put("away", e.away).put("logoHome", e.logoHome).put("logoAway", e.logoAway)
                        .put("logoComp", e.logoComp).put("art", e.art).put("country", e.country).put("place", e.place));
            }
            cache.edit().putString("events2", a.toString()).putLong("at2", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {
        }
    }

    // MARK: - Sources

    private static final String[] ITALIAN_PLAYERS = {"sinner", "musetti", "berrettini", "paolini", "cobolli", "arnaldi",
            "darderi", "errani", "cocciaretto", "sonego", "fognini", "bellucci", "nardi", "passaro", "bronzetti", "trevisan"};

    private void football(List<Event> out, String key, long from) throws Exception {
        java.util.Set<String> italian = serieATeams(key);
        String url = "https://api.football-data.org/v4/matches?competitions=SA,CL&dateFrom=" + day(from - 86_400_000L)
                + "&dateTo=" + day(from + 5 * 86_400_000L);
        JSONArray matches = new JSONObject(get(url, key)).getJSONArray("matches");
        for (int i = 0; i < matches.length(); i++) {
            JSONObject m = matches.getJSONObject(i);
            JSONObject comp = m.getJSONObject("competition");
            String code = comp.optString("code");
            if (!code.equals("SA") && !code.equals("CL")) continue;
            String status = m.optString("status");
            long start = utc(m.getString("utcDate"));
            if (start == 0 || status.equals("POSTPONED") || status.equals("CANCELLED") || status.equals("SUSPENDED")) continue;
            JSONObject home = m.getJSONObject("homeTeam"), away = m.getJSONObject("awayTeam");
            Event e = event(code.equals("SA") ? SERIE_A : CHAMPIONS, start,
                    code.equals("SA") ? "Serie A" : "Champions League", team(home) + " – " + team(away));
            e.minutes = 115;
            e.done = status.equals("FINISHED") || status.equals("AWARDED");
            e.live = status.equals("IN_PLAY") || status.equals("PAUSED");
            e.home = team(home);
            e.away = team(away);
            e.logoHome = home.optString("crest");
            e.logoAway = away.optString("crest");
            String[] info = code.equals("SA") ? leagueInfo(4332, "serie a") : leagueInfo(4480, "champions");
            e.logoComp = info[0].isEmpty() ? comp.optString("emblem") : info[0];
            e.art = code.equals("CL") ? art(Config.CHAMPIONS_ART, "asset:sports/champions.webp") : "";
            e.italian = italian.contains(home.optString("id")) || italian.contains(away.optString("id")) ? 3 : 0;
            out.add(e);
        }
    }

    /** Ids of the Serie A clubs (the Italian ones in the Champions League too), kept for a week. */
    private java.util.Set<String> serieATeams(String key) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        long saved = cache.getLong("teamsAt", 0);
        String list = cache.getString("teams", "");
        if (list.isEmpty() || System.currentTimeMillis() - saved > WEEK) {
            try {
                JSONArray teams = new JSONObject(get("https://api.football-data.org/v4/competitions/SA/teams", key)).getJSONArray("teams");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < teams.length(); i++) sb.append(i == 0 ? "" : ",").append(teams.getJSONObject(i).optInt("id"));
                list = sb.toString();
                cache.edit().putString("teams", list).putLong("teamsAt", System.currentTimeMillis()).apply();
            } catch (Exception e) {
                AppLog.w("Serie A teams: " + e.getMessage());
            }
        }
        for (String id : list.split(",")) if (!id.isEmpty()) ids.add(id);
        return ids;
    }

    private static String team(JSONObject t) {
        String name = t.optString("shortName", "");
        return name.isEmpty() || "null".equals(name) ? t.optString("name") : name;
    }

    private void formula1(List<Event> out) throws Exception {
        JSONObject race = new JSONObject(get("https://api.jolpi.ca/ergast/f1/current/next.json", null))
                .getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races").getJSONObject(0);
        String name = race.optString("raceName");
        String[] info = leagueInfo(4370, "formula");
        Object[][] sessions = {{"Qualifying", Ui.tr("Classificação", "Qualifying", "Clasificación", "Qualifiche"), 75L},
                {"Sprint", "Sprint", 60L}, {"", Ui.tr("Corrida", "Race", "Carrera", "Gara"), 120L}};
        for (Object[] s : sessions) {
            String part = (String) s[0];
            JSONObject o = part.isEmpty() ? race : race.optJSONObject(part);
            if (o == null) continue;
            long start = utc(o.optString("date") + "T" + o.optString("time", "12:00:00Z"));
            if (start == 0) continue;
            Event e = event(F1, start, "Formula 1", name + " · " + s[1]);
            e.minutes = (Long) s[2];
            e.italian = 3;      // Ferrari (and an Italian driver on the grid)
            JSONObject where = race.optJSONObject("Circuit") == null ? null : race.getJSONObject("Circuit").optJSONObject("Location");
            if (where != null) {
                e.country = iso(where.optString("country"));
                e.place = where.optString("locality");
            }
            if (e.country.isEmpty()) e.country = iso(name);       // "Italian Grand Prix"
            e.logoComp = info[0];
            e.art = info[1];
            out.add(e);
        }
    }

    private static final String[] ITALIAN_CLUBS = {"inter", "milan", "juventus", "napoli", "roma", "lazio", "atalanta",
            "fiorentina", "bologna", "torino", "genoa", "udinese"};

    /** TheSportsDB's day listing for a sport over the next days: MotoGP, tennis finals and the Europa League. */
    private void sportsDb(List<Event> out, String sport) throws Exception {
        boolean tennis = sport.equals("Tennis"), soccer = sport.equals("Soccer");
        String[] moto = sport.equals("Motorsport") ? leagueInfo(4407, "moto") : new String[] {"", ""};
        String[] europa = soccer ? leagueInfo(4481, "europa") : new String[] {"", ""};
        // Serie A and the Champions League come from football-data (with the crests); when it gave none (no key, or
        // it refused), they come from this listing too, so the scoreboard still has them.
        boolean footballMissing = true;
        for (Event e : out) if (e.sport == SERIE_A || e.sport == CHAMPIONS) footballMissing = false;
        String[] serieA = soccer && footballMissing ? leagueInfo(4332, "serie a") : new String[] {"", ""};
        String[] champions = soccer && footballMissing ? leagueInfo(4480, "champions") : new String[] {"", ""};
        for (int d = 0; d < 4; d++) {      // today and the next 3 days (4 requests per sport: the free key has a rate limit)
            long when = System.currentTimeMillis() + d * 86_400_000L;
            JSONObject o = new JSONObject(get("https://www.thesportsdb.com/api/v1/json/" + SPORTSDB_KEY + "/eventsday.php?d=" + day(when)
                    + "&s=" + sport, null));
            JSONArray list = o.optJSONArray("events");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject j = list.getJSONObject(i);
                String league = j.optString("strLeague"), title = j.optString("strEvent");
                String lower = league.toLowerCase(Locale.ROOT);
                String text = (league + " " + title + " " + j.optString("strRound")).toLowerCase(Locale.ROOT);
                int kind = -1;
                if (soccer) {
                    boolean women = lower.contains("women") || lower.contains("conference");
                    if (!women && lower.contains("europa league")) kind = EUROPA;
                    else if (!women && footballMissing && lower.contains("italian serie a")) kind = SERIE_A;
                    else if (!women && footballMissing && lower.contains("uefa champions league")) kind = CHAMPIONS;
                } else if (tennis) {
                    if (text.contains("final") && (text.contains("atp") || text.contains("wta") || text.contains("grand slam"))) kind = TENNIS;
                } else if (lower.contains("motogp")) {
                    kind = MOTO;
                }
                if (kind < 0) continue;
                long start = utc(j.optString("strTimestamp"));
                if (start == 0) continue;
                Event e = event(kind, start, kind == SERIE_A ? "Serie A" : kind == CHAMPIONS ? "Champions League"
                        : kind == EUROPA ? "Europa League" : tennis ? "Tennis" : "MotoGP", title);
                e.minutes = soccer ? 115 : tennis ? 180 : 75;
                String[] info = kind == SERIE_A ? serieA : kind == CHAMPIONS ? champions : soccer ? europa : moto;
                e.logoComp = !info[0].isEmpty() ? info[0] : clean(j.optString("strLeagueBadge"));
                e.art = soccer ? "" : !info[1].isEmpty() ? info[1] : clean(j.optString("strThumb"));   // football: the app's picture
                if (soccer || tennis) {
                    e.home = j.optString("strHomeTeam");
                    e.away = j.optString("strAwayTeam");
                    e.logoHome = clean(j.optString("strHomeTeamBadge"));
                    e.logoAway = clean(j.optString("strAwayTeamBadge"));
                    e.italian = soccer ? (italianClub(e.home + " " + e.away) ? 3 : 0) : (italianPlayer(e.home + " " + e.away) ? 3 : 0);
                } else {
                    e.italian = 3;     // Ducati and the Italian riders
                    e.country = iso(j.optString("strCountry") + " " + title + " " + j.optString("strVenue") + " " + j.optString("strCity"));
                    e.place = !clean(j.optString("strCity")).isEmpty() ? j.optString("strCity") : clean(j.optString("strVenue"));
                }
                out.add(e);
            }
        }
    }

    private static boolean italianClub(String names) {
        String n = names.toLowerCase(Locale.ROOT);
        for (String p : ITALIAN_CLUBS) if (n.contains(p)) return true;
        return false;
    }

    private static final String MOTOGP_BAR = "https://www.motogp.com/resources/v8.28.8/i/svg-files/elements/slim-bar-background.svg";

    private static final String[][] COUNTRIES = {{"italy", "it"}, {"italia", "it"}, {"italian", "it"}, {"emilia", "it"}, {"mugello", "it"},
            {"monza", "it"}, {"misano", "it"}, {"imola", "it"}, {"san marino", "sm"}, {"spain", "es"}, {"spagna", "es"}, {"spanish", "es"},
            {"catalan", "es"}, {"catalunya", "es"}, {"aragon", "es"}, {"valencia", "es"}, {"jerez", "es"}, {"france", "fr"}, {"francia", "fr"},
            {"french", "fr"}, {"germany", "de"}, {"german", "de"}, {"great britain", "gb"}, {"united kingdom", "gb"}, {"british", "gb"},
            {"silverstone", "gb"}, {"netherlands", "nl"}, {"dutch", "nl"}, {"belgium", "be"}, {"belgian", "be"}, {"austria", "at"},
            {"austrian", "at"}, {"hungary", "hu"}, {"hungarian", "hu"}, {"czech", "cz"}, {"portugal", "pt"}, {"portuguese", "pt"},
            {"qatar", "qa"}, {"thai", "th"}, {"malaysia", "my"}, {"japan", "jp"}, {"australia", "au"}, {"indonesia", "id"}, {"india", "in"},
            {"argentin", "ar"}, {"united states", "us"}, {"usa", "us"}, {"america", "us"}, {"miami", "us"}, {"las vegas", "us"},
            {"brazil", "br"}, {"mexic", "mx"}, {"canad", "ca"}, {"monaco", "mc"}, {"azerbaijan", "az"}, {"singapore", "sg"},
            {"saudi", "sa"}, {"bahrain", "bh"}, {"emirates", "ae"}, {"uae", "ae"}, {"abu dhabi", "ae"}, {"china", "cn"}, {"chinese", "cn"},
            {"korea", "kr"}, {"turk", "tr"}, {"kazakhstan", "kz"}, {"finland", "fi"}, {"sweden", "se"}, {"switzerland", "ch"},
            {"vietnam", "vn"}, {"russia", "ru"}};

    /** ISO code (lowercase, for the flag) of a country named in English, or "". */
    private static String iso(String country) {
        String c = country == null ? "" : country.toLowerCase(Locale.ROOT);
        for (String[] k : COUNTRIES) if (c.contains(k[0])) return k[1];
        return "";
    }

    /** The artwork from config.properties if given, else the picture that comes with the app. */
    private static String art(String configured, String bundled) {
        return configured == null || configured.trim().isEmpty() ? bundled : configured.trim();
    }

    private static boolean italianPlayer(String names) {
        String n = names.toLowerCase(Locale.ROOT);
        for (String p : ITALIAN_PLAYERS) if (n.contains(p)) return true;
        return false;
    }

    private static String clean(String url) {
        return url == null || url.isEmpty() || "null".equals(url) ? "" : url;
    }

    /**
     * {logo, picture} of a league from TheSportsDB by id, only if its name contains `must` (so a wrong id gives
     * nothing); kept for a week. The picture is the league's wide artwork, the card's background.
     */
    private String[] leagueInfo(int id, String must) {
        String key = "league" + id;
        String saved = cache.getString(key, null);
        if (saved != null && !saved.equals("|") && System.currentTimeMillis() - cache.getLong(key + "At", 0) < WEEK) return split(saved);
        String logo = "", art = "";
        try {
            JSONArray leagues = new JSONObject(get("https://www.thesportsdb.com/api/v1/json/" + SPORTSDB_KEY + "/lookupleague.php?id=" + id, null))
                    .optJSONArray("leagues");
            JSONObject l = leagues == null ? null : leagues.optJSONObject(0);
            if (l != null && l.optString("strLeague").toLowerCase(Locale.ROOT).contains(must)) {
                logo = clean(l.optString("strBadge"));
                if (logo.isEmpty()) logo = clean(l.optString("strLogo"));
                art = clean(l.optString("strFanart1"));
                if (art.isEmpty()) art = clean(l.optString("strBanner"));
            }
        } catch (Exception e) {
            AppLog.w("league " + id + ": " + e.getMessage());
            return saved == null ? new String[] {"", ""} : split(saved);
        }
        if (!logo.isEmpty() || !art.isEmpty()) {      // an empty answer is not worth keeping: ask again next time
            cache.edit().putString(key, logo + "|" + art).putLong(key + "At", System.currentTimeMillis()).apply();
        } else {
            AppLog.w("league " + id + ": no logo in the answer");
        }
        return new String[] {logo, art};
    }

    private static String[] split(String saved) {
        String[] parts = saved.split("\\|", -1);
        return new String[] {parts.length > 0 ? parts[0] : "", parts.length > 1 ? parts[1] : ""};
    }

    /** A club's badge (PNG) from TheSportsDB by name: the way back when its crest won't load. */
    private String badgeFor(String name) {
        String key = "badge:" + name.toLowerCase(Locale.ROOT);
        String saved = cache.getString(key, null);
        if (saved != null && !saved.isEmpty()) return saved;
        String badge = "";
        try {
            JSONArray teams = new JSONObject(get("https://www.thesportsdb.com/api/v1/json/" + SPORTSDB_KEY + "/searchteams.php?t="
                    + java.net.URLEncoder.encode(name, "UTF-8"), null)).optJSONArray("teams");
            for (int i = 0; teams != null && i < teams.length() && badge.isEmpty(); i++) {
                JSONObject t = teams.getJSONObject(i);
                if (t.optString("strSport").equals("Soccer")) badge = clean(t.optString("strBadge"));
            }
        } catch (Exception e) {
            AppLog.w("badge " + name + ": " + e.getMessage());
            return "";
        }
        if (!badge.isEmpty()) cache.edit().putString(key, badge).apply();
        return badge;
    }

    // MARK: - The day's agenda (the Sport button)

    /** Names of the competitions, by sport id, and the order they are listed in. */
    static final String[] NAMES = {"Serie A", "Champions League", "Europa League", "Formula 1", "MotoGP", "Tennis",
            "Premier League", "LaLiga", "Bundesliga", "Ligue 1"};
    static final int[] ORDER = {SERIE_A, CHAMPIONS, EUROPA, PREMIER, LALIGA, BUNDES, LIGUE1, F1, MOTO, TENNIS};

    private final ExecutorService agendaIo = Executors.newSingleThreadExecutor();
    private final java.util.Map<String, List<Event>> agendas = new java.util.HashMap<>();
    private final java.util.Map<String, Long> agendaAt = new java.util.HashMap<>();
    private final java.util.Set<String> agendaLoading = new java.util.HashSet<>();

    private static String localDay(long at) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return f.format(new Date(at));
    }

    /** Midnight (local) of the day `offset` days from today. */
    static long dayStart(int offset) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, offset);
        return c.getTimeInMillis();
    }

    /**
     * Every event of one day (local time), in time order, or null while it is being fetched (`changed` runs, on the
     * main thread, when it arrives). Kept for 15 minutes.
     */
    List<Event> agenda(long start, Runnable changed) {
        String key = localDay(start);
        synchronized (agendas) {
            List<Event> have = agendas.get(key);
            Long at = agendaAt.get(key);
            if (have != null && at != null && System.currentTimeMillis() - at < 15 * 60_000L) return have;
            if (!agendaLoading.add(key)) return have;
        }
        agendaIo.execute(() -> {
            List<Event> found = new ArrayList<>();
            try {
                found = fetchAgenda(start);
            } catch (RuntimeException e) {
                AppLog.w("agenda: " + e.getMessage());
            }
            synchronized (agendas) {
                agendaLoading.remove(key);
                agendas.put(key, found);
                agendaAt.put(key, System.currentTimeMillis());
            }
            main.post(changed);
        });
        synchronized (agendas) {
            return agendas.get(key);
        }
    }

    private List<Event> fetchAgenda(long start) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(start);
        cal.add(Calendar.DAY_OF_YEAR, 1);
        long end = cal.getTimeInMillis();
        List<Event> raw = new ArrayList<>();
        String key = Config.FOOTBALL_KEY.trim();
        boolean football = false;
        if (!key.isEmpty()) {
            try {
                footballDay(raw, key, start, end);
                football = true;
            } catch (Exception e) {
                AppLog.w("agenda football: " + e.getMessage());
            }
        }
        try {
            formula1Season(raw);
        } catch (Exception e) {
            AppLog.w("agenda formula 1: " + e.getMessage());
        }
        for (String sport : new String[] {"Soccer", "Motorsport", "Tennis"}) {
            for (long day : new long[] {start, end}) {      // TheSportsDB lists by UTC date: look at both
                try {
                    dayListing(raw, sport, day, football);
                } catch (Exception e) {
                    AppLog.w("agenda " + sport + ": " + e.getMessage());
                }
            }
        }
        List<Event> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Event e : raw) {
            if (e.start < start || e.start >= end) continue;
            if (seen.add(e.sport + "|" + e.title + "|" + e.home + "|" + e.away + "|" + e.start)) out.add(e);
        }
        java.util.Collections.sort(out, (a, b) -> a.start != b.start ? Long.compare(a.start, b.start) : Integer.compare(b.italian, a.italian));
        return out;
    }

    private void footballDay(List<Event> out, String key, long start, long end) throws Exception {
        java.util.Set<String> italian = serieATeams(key);
        String url = "https://api.football-data.org/v4/matches?competitions=SA,CL,PL,PD,BL1,FL1&dateFrom=" + day(start - 86_400_000L)
                + "&dateTo=" + day(end + 86_400_000L);
        JSONArray matches = new JSONObject(get(url, key)).getJSONArray("matches");
        for (int i = 0; i < matches.length(); i++) {
            JSONObject m = matches.getJSONObject(i);
            JSONObject comp = m.getJSONObject("competition");
            String code = comp.optString("code");
            int sport = code.equals("SA") ? SERIE_A : code.equals("CL") ? CHAMPIONS : code.equals("PL") ? PREMIER
                    : code.equals("PD") ? LALIGA : code.equals("BL1") ? BUNDES : code.equals("FL1") ? LIGUE1 : -1;
            long at = utc(m.optString("utcDate"));
            String status = m.optString("status");
            if (sport < 0 || at == 0 || status.equals("POSTPONED") || status.equals("CANCELLED")) continue;
            JSONObject home = m.getJSONObject("homeTeam"), away = m.getJSONObject("awayTeam");
            Event e = event(sport, at, NAMES[sport], team(home) + " – " + team(away));
            e.minutes = 115;
            e.home = team(home);
            e.away = team(away);
            e.logoHome = home.optString("crest");
            e.logoAway = away.optString("crest");
            e.logoComp = comp.optString("emblem");
            e.italian = italian.contains(home.optString("id")) || italian.contains(away.optString("id")) ? 3 : 0;
            out.add(e);
        }
    }

    /** The whole Formula 1 season (every session of every weekend), from Jolpica. */
    private void formula1Season(List<Event> out) throws Exception {
        JSONArray races = new JSONObject(get("https://api.jolpi.ca/ergast/f1/current.json", null))
                .getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races");
        String[][] sessions = {{"FirstPractice", Ui.tr("Treino livre 1", "Free practice 1", "Libres 1", "Prove libere 1")},
                {"SecondPractice", Ui.tr("Treino livre 2", "Free practice 2", "Libres 2", "Prove libere 2")},
                {"ThirdPractice", Ui.tr("Treino livre 3", "Free practice 3", "Libres 3", "Prove libere 3")},
                {"SprintQualifying", Ui.tr("Classificação sprint", "Sprint qualifying", "Clasificación sprint", "Qualifiche sprint")}, {"Sprint", "Sprint"}, {"Qualifying", Ui.tr("Classificação", "Qualifying", "Clasificación", "Qualifiche")},
                {"", Ui.tr("Corrida", "Race", "Carrera", "Gara")}};
        for (int i = 0; i < races.length(); i++) {
            JSONObject race = races.getJSONObject(i);
            JSONObject where = race.optJSONObject("Circuit") == null ? null : race.getJSONObject("Circuit").optJSONObject("Location");
            for (String[] s : sessions) {
                JSONObject o = s[0].isEmpty() ? race : race.optJSONObject(s[0]);
                if (o == null) continue;
                long at = utc(o.optString("date") + "T" + o.optString("time", "12:00:00Z"));
                if (at == 0) continue;
                Event e = event(F1, at, "Formula 1", race.optString("raceName") + " · " + s[1]);
                e.minutes = s[0].isEmpty() ? 120 : 75;
                e.italian = 3;
                if (where != null) {
                    e.country = iso(where.optString("country"));
                    e.place = where.optString("locality");
                }
                if (e.country.isEmpty()) e.country = iso(race.optString("raceName"));
                out.add(e);
            }
        }
    }

    private static final String[][] LEAGUES = {{"italian serie a", "0"}, {"uefa champions league", "1"}, {"uefa europa league", "2"},
            {"english premier league", "6"}, {"spanish la liga", "7"}, {"german bundesliga", "8"}, {"french ligue 1", "9"}};

    /** One day of TheSportsDB's listing for a sport: the leagues in LEAGUES, MotoGP, ATP and WTA tennis. */
    private void dayListing(List<Event> out, String sport, long day, boolean footballKnown) throws Exception {
        JSONArray list = new JSONObject(get("https://www.thesportsdb.com/api/v1/json/" + SPORTSDB_KEY + "/eventsday.php?d=" + day(day) + "&s=" + sport, null))
                .optJSONArray("events");
        int tennisCount = 0;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject j = list.getJSONObject(i);
            String league = j.optString("strLeague"), lower = league.toLowerCase(Locale.ROOT), title = j.optString("strEvent");
            long at = utc(j.optString("strTimestamp"));
            if (at == 0) continue;
            int kind = -1;
            if (sport.equals("Soccer")) {
                for (String[] l : LEAGUES) {
                    if (lower.contains(l[0]) && !lower.contains("women") && !lower.contains("conference")) kind = Integer.parseInt(l[1]);
                }
                if (kind >= 0 && footballKnown && kind != EUROPA) continue;      // football-data already gave those, with crests
            } else if (sport.equals("Motorsport")) {
                if (lower.contains("motogp") && !lower.contains("moto2") && !lower.contains("moto3")) kind = MOTO;
            } else if ((lower.contains("atp") || lower.contains("wta") || lower.contains("grand slam")) && tennisCount < 14) {
                kind = TENNIS;
                tennisCount++;
            }
            if (kind < 0) continue;
            Event e = event(kind, at, NAMES[kind], title);
            e.minutes = kind == TENNIS ? 180 : kind == MOTO ? 75 : 115;
            e.logoComp = clean(j.optString("strLeagueBadge"));
            if (kind != MOTO) {
                e.home = j.optString("strHomeTeam");
                e.away = j.optString("strAwayTeam");
                e.logoHome = clean(j.optString("strHomeTeamBadge"));
                e.logoAway = clean(j.optString("strAwayTeamBadge"));
                if (e.home.isEmpty() || e.away.isEmpty()) {
                    e.home = "";
                    e.away = "";
                }
            } else {
                e.country = iso(j.optString("strCountry") + " " + title + " " + j.optString("strVenue") + " " + j.optString("strCity"));
                e.place = !clean(j.optString("strCity")).isEmpty() ? j.optString("strCity") : clean(j.optString("strVenue"));
            }
            e.italian = kind == MOTO ? 3 : kind == TENNIS ? (italianPlayer(e.home + " " + e.away) ? 3 : 0) : italianClub(e.home + " " + e.away) ? 3 : 0;
            out.add(e);
        }
    }

    // MARK: - Logos

    /** The logo at `url` if it is ready (otherwise starts loading it and the home screen redraws when it arrives). */
    android.graphics.Bitmap bitmap(String url, String club) {
        return bitmap(url, club, 256);
    }

    /** Same, with the size SVGs are drawn at (256 for logos and flags, 1280 for a background). */
    android.graphics.Bitmap bitmap(String url, String club, int box) {
        if (url == null || url.isEmpty()) return null;
        synchronized (bitmaps) {
            android.graphics.Bitmap b = bitmaps.get(url);
            if (b != null) return b;
            Long failedAt = failedAtMap.get(url);
            if ((failedAt != null && System.currentTimeMillis() - failedAt < 10 * 60_000L) || !loading.add(url)) return null;
        }
        art.execute(() -> {
            android.graphics.Bitmap b = null;
            try {
                b = loadLogo(url, box);
            } catch (IOException | RuntimeException e) {
                AppLog.w("logo " + url + ": " + e.getMessage());
                String other = club == null || club.isEmpty() ? "" : badgeFor(club);
                if (!other.isEmpty()) {
                    try {
                        b = loadLogo(other, box);
                    } catch (IOException | RuntimeException again) {
                        AppLog.w("badge " + other + ": " + again.getMessage());
                    }
                }
            }
            synchronized (bitmaps) {
                loading.remove(url);
                if (b == null) {
                    failedAtMap.put(url, System.currentTimeMillis());
                } else {
                    bitmaps.put(url, b);
                    while (bitmaps.size() > 16) bitmaps.remove(bitmaps.keySet().iterator().next());
                }
            }
            if (b != null) main.post(() -> Recs.get(context).changed());
        });
        return null;
    }

    /** A picture inside the app (assets), reduced to at most 1280 px wide. */
    private android.graphics.Bitmap decodeAsset(String path) throws IOException {
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getAssets().open(path)) {
            android.graphics.BitmapFactory.decodeStream(in, null, bounds);
        }
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= 1280) opts.inSampleSize *= 2;
        try (InputStream in = context.getAssets().open(path)) {
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeStream(in, null, opts);
            if (b == null) throw new IOException("unreadable " + path);
            return b;
        }
    }

    /** Downloads (SVG crests go through an image proxy that returns a PNG) and decodes, keeping the file for later. */
    private android.graphics.Bitmap loadLogo(String url, int box) throws IOException {
        if (url.startsWith("asset:")) return decodeAsset(url.substring(6));
        java.io.File dir = new java.io.File(context.getCacheDir(), "sports");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        java.io.File f = new java.io.File(dir, Integer.toHexString(url.hashCode()) + "-" + box + ".png");
        if (!f.exists()) {
            boolean svg = url.toLowerCase(Locale.ROOT).endsWith(".svg");
            // SVGs can't be drawn by Android: an image service turns them into PNGs (two addresses of the same one).
            String[] tries = svg ? new String[] {"https://wsrv.nl/?w=" + box + "&h=" + box + "&fit=inside&output=png&url=" + java.net.URLEncoder.encode(url, "UTF-8"),
                    "https://images.weserv.nl/?w=" + box + "&h=" + box + "&fit=inside&output=png&url=" + java.net.URLEncoder.encode(url, "UTF-8")}
                    : new String[] {url};
            IOException last = null;
            for (String from : tries) {
                try {
                    download(from, f);
                    last = null;
                    break;
                } catch (IOException e) {
                    last = e;
                    AppLog.w("download " + from + ": " + e.getMessage());
                }
            }
            if (last != null) throw last;
        }
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getPath(), bounds);
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= 1280) opts.inSampleSize *= 2;    // artwork can be huge
        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath(), opts);
        if (b == null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            throw new IOException("unreadable logo");
        }
        return b;
    }

    /** One download, like a browser asking for a picture; the file appears only when it is complete. */
    private static void download(String from, java.io.File f) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(from).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        // Some sites (UEFA, news sites) refuse downloads that don't look like a browser asking for a picture.
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36");
        c.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
        c.setRequestProperty("Referer", new URL(from).getProtocol() + "://" + new URL(from).getHost() + "/");
        java.io.File part = new java.io.File(f.getPath() + ".part");
        try (InputStream in = c.getInputStream(); java.io.FileOutputStream o = new java.io.FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) o.write(buffer, 0, n);
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the logo");
    }

    // MARK: - Helpers

    /** TheSportsDB's free key ("3" was the old one; "123" is the current one, v1 only). */
    private static final String SPORTSDB_KEY = "123";

    /** TheSportsDB answered 429 (too many requests): no more requests to it until then. */
    private static volatile long sportsDbPausedUntil;

    private static String get(String url, String token) throws IOException {
        boolean sportsDb = url.contains("thesportsdb.com");
        if (sportsDb) {
            long wait = sportsDbPausedUntil - System.currentTimeMillis();
            if (wait > 0) throw new IOException("TheSportsDB paused for " + (wait / 60_000 + 1) + " min (too many requests)");
            try {
                Thread.sleep(400);      // spread the requests out: its free key allows only a few per second
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        // Sites behind Cloudflare (TheSportsDB) answer 403 to Android's own "Dalvik" client; ask like a TV browser.
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36");
        if (token != null) c.setRequestProperty("X-Auth-Token", token);
        try {
            int code = c.getResponseCode();
            if (code == 429 && sportsDb) sportsDbPausedUntil = System.currentTimeMillis() + 15 * 60_000L;
            if (code != 200) throw new IOException("HTTP " + code + reason(c));
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** The server's own words for a refusal (football-data says why), for the log; never the request or its key. */
    private static String reason(HttpURLConnection c) {
        try (InputStream err = c.getErrorStream()) {
            if (err == null) return "";
            String body = read(err);
            String message = "";
            try {
                message = new JSONObject(body).optString("message", "");
            } catch (Exception ignored) {
            }
            if (message.isEmpty()) message = body.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
            return message.isEmpty() ? "" : " (" + (message.length() > 160 ? message.substring(0, 160) + "…" : message) + ")";
        } catch (IOException e) {
            return "";
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        in.close();
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static String day(long at) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(at));
    }

    /** "2026-10-21T19:00:00Z" (or without the Z, or with fractions) in UTC, as milliseconds; 0 if unreadable. */
    private static long utc(String text) {
        if (text == null || text.length() < 19) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(text.substring(0, 19)).getTime();
        } catch (java.text.ParseException e) {
            return 0;
        }
    }
}
