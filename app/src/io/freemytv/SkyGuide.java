package io.freemytv;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Who shows each match in Italy, from Sky's TV guide (apid.sky.it, the one behind guidatv.sky.it, no key): it also
 * lists DAZN 1-2 and the free channels. A match on a channel at its kick-off is shown there; where the guide says
 * nothing, this season's rights decide. Only with the screen in Italian or the country set to Italy.
 */
final class SkyGuide {
    /** {Sky channel id, name, kind} (kind: sky, dazn, free). */
    private static final Object[][] CHANNELS = {
        {11346, "Sky Sport Uno", "sky"}, {9113, "Sky Sport Calcio", "sky"}, {9103, "Sky Sport Max", "sky"},
        {9093, "Sky Sport Arena", "sky"}, {10013, "Sky Sport 4K", "sky"}, {11237, "Sky Sport Tennis", "sky"},
        {9096, "Sky Sport F1", "sky"}, {9102, "Sky Sport MotoGP", "sky"}, {11497, "Sky Sport 251", "sky"},
        {11496, "Sky Sport 252", "sky"}, {11494, "Sky Sport 253", "sky"}, {11490, "Sky Sport 254", "sky"},
        {11491, "Sky Sport 255", "sky"}, {11492, "Sky Sport 256", "sky"}, {11386, "Sky Sport 257", "sky"},
        {9046, "Sky Sport 258", "sky"}, {8613, "Sky Sport 259", "sky"}, {11402, "DAZN 1", "dazn"}, {11401, "DAZN 2", "dazn"},
        {8195, "TV8", "free"}, {899, "Rai 1", "free"}, {898, "Rai 2", "free"}, {807, "Rai Sport", "free"},
        {10354, "Canale 5", "free"}, {10454, "Italia 1", "free"},
    };
    private static final long EVERY = 30 * 60_000L;

    private static final class Show {
        String name, kind;
        long start, end;
        String text;
    }

    private static final class Day {
        volatile boolean loading = true, ok;
        final List<Show> shows = new ArrayList<>();
        long at;
    }

    private static SkyGuide instance;

    static synchronized SkyGuide get() {
        if (instance == null) instance = new SkyGuide();
        return instance;
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Long, Day> days = new HashMap<>();
    private final List<Runnable> waiting = new ArrayList<>();

    static boolean useful() {
        return Ui.it || "IT".equalsIgnoreCase(Config.TMDB_REGION.trim());
    }

    /**
     * Short labels for where to watch `e` (DAZN, SKY, PRIME, TV8...), at most two; empty outside Italy. While the
     * day's guide loads, this season's rights; `changed` runs (once) when it arrives.
     */
    List<String> badges(Sports.Event e, Runnable changed) {
        List<String> out = new ArrayList<>();
        if (e == null || !useful()) return out;
        List<Show> found = channels(e, changed);
        // Serie A and LaLiga: always on DAZN (all ten matches), plus Sky's channel for its three.
        if (e.sport == Sports.SERIE_A || e.sport == Sports.LALIGA) add(out, "DAZN");
        if (found != null) for (Show s : found) add(out, label(s));
        // The Champions League match Sky doesn't have (Wednesday's big one) is on Prime Video.
        if (found != null && e.sport == Sports.CHAMPIONS && !out.contains("SKY")) add(out, "PRIME");
        if ((found == null || found.isEmpty()) && out.isEmpty()) {
            String usual = e.sport == Sports.LIGUE1 ? "L1+" : e.sport == Sports.LALIGA ? "DAZN" : "SKY";
            add(out, usual);
        }
        return out.size() > 2 ? out.subList(0, 2) : out;
    }

    private static void add(List<String> out, String label) {
        if (!out.contains(label)) out.add(label);
    }

    private static String label(Show s) {
        return s.kind.equals("dazn") ? "DAZN" : s.kind.equals("sky") ? "SKY" : s.name.toUpperCase(Locale.ROOT);
    }

    /** The channels that have `e` on at its start (not the build-up or a replay), or null while the guide loads. */
    private List<Show> channels(Sports.Event e, Runnable changed) {
        Day d = day(e.start, changed);
        if (d.loading || !d.ok) return d.loading ? null : new ArrayList<>();
        List<Show> out = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        boolean sides = !e.home.isEmpty() && !e.away.isEmpty();
        List<String> home = sides ? keys(e.home) : null, away = sides ? keys(e.away) : null;
        synchronized (d.shows) {
            for (Show s : d.shows) {
                if (s.start > e.start + 20 * 60_000L || s.end < e.start + 30 * 60_000L) continue;
                if (s.text.matches(".*\\b(replica|highlights|sintesi|differita)\\b.*")) continue;
                boolean hit;
                if (sides) hit = has(s.text, home) && has(s.text, away);
                else if (e.sport == Sports.F1) hit = s.text.matches(".*(formula 1|\\bf1\\b|gran premio|\\bgp\\b).*") && !s.name.equals("Sky Sport MotoGP");
                else if (e.sport == Sports.MOTO) hit = s.text.matches(".*(motogp|gran premio|\\bgp\\b).*") && !s.name.equals("Sky Sport F1");
                else hit = false;
                if (hit && seen.put(s.name, true) == null) out.add(s);
            }
        }
        return out;
    }

    private Day day(long t, Runnable changed) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(t);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        final long key = cal.getTimeInMillis();
        long now = System.currentTimeMillis();
        synchronized (days) {
            Day d = days.get(key);
            if (d != null && (d.loading || now - d.at < EVERY)) {
                if (d.loading && changed != null && !waiting.contains(changed)) waiting.add(changed);
                return d;
            }
            Day fresh = new Day();
            fresh.at = now;
            if (d != null) {           // refreshing: keep showing the old one meanwhile
                fresh.loading = false;
                fresh.ok = d.ok;
                fresh.shows.addAll(d.shows);
            }
            days.put(key, fresh);
            if (changed != null && !waiting.contains(changed)) waiting.add(changed);
            io.execute(() -> load(fresh, key));
            return fresh;
        }
    }

    private void load(Day d, long dayStart) {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        String base = "https://apid.sky.it/gtv/v1/events?from=" + iso.format(new Date(dayStart)) + "&to="
                + iso.format(new Date(dayStart + 86_400_000L)) + "&pageSize=999&pageNum=0&env=DTH&channels=";
        List<Show> shows = new ArrayList<>();
        String error = "";
        // All channels in one request; when the answer doesn't say each show's channel, one channel at a time.
        StringBuilder ids = new StringBuilder();
        for (Object[] c : CHANNELS) ids.append(ids.length() == 0 ? "" : ",").append(c[0]);
        try {
            take(get(base + ids), null, shows);
        } catch (Exception e) {
            error = e.getMessage();
        }
        if (shows.isEmpty()) {
            for (Object[] c : CHANNELS) {
                try {
                    take(get(base + c[0]), c, shows);
                } catch (Exception e) {
                    error = e.getMessage();
                }
            }
        }
        synchronized (d.shows) {
            if (!shows.isEmpty() || d.shows.isEmpty()) {
                d.shows.clear();
                d.shows.addAll(shows);
            }
        }
        d.ok = !d.shows.isEmpty();
        d.loading = false;
        if (shows.isEmpty()) AppLog.w("Sky guide: " + (error.isEmpty() ? "no shows" : error));
        main.post(() -> {
            List<Runnable> run;
            synchronized (days) {
                run = new ArrayList<>(waiting);
                waiting.clear();
            }
            for (Runnable r : run) r.run();
        });
    }

    private static void take(JSONObject r, Object[] fixed, List<Show> out) {
        JSONArray events = r.optJSONArray("events");
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject x = events.optJSONObject(i);
            if (x == null) continue;
            Object[] ch = fixed;
            if (ch == null) {
                JSONObject c = x.optJSONObject("channel");
                int id = c == null ? 0 : c.optInt("id", c.optInt("channelId"));
                for (Object[] candidate : CHANNELS) if ((Integer) candidate[0] == id) ch = candidate;
            }
            if (ch == null) continue;
            Show s = new Show();
            s.name = (String) ch[1];
            s.kind = (String) ch[2];
            s.start = parse(x.optString("starttime"));
            s.end = parse(x.optString("endtime"));
            s.text = norm(x.optString("eventTitle") + " " + x.optString("epgEventTitle") + " " + x.optString("eventSynopsis"));
            if (s.start > 0 && s.end > 0) out.add(s);
        }
    }

    private static long parse(String t) {
        if (t == null || t.length() < 19) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            long ms = f.parse(t.substring(0, 19)).getTime();
            // An offset like +01:00 after the seconds.
            String rest = t.substring(19).replaceAll("\\.\\d+", "");
            if (rest.matches("[+-]\\d{2}:?\\d{2}")) {
                int sign = rest.charAt(0) == '-' ? -1 : 1;
                String digits = rest.substring(1).replace(":", "");
                ms -= sign * (Integer.parseInt(digits.substring(0, 2)) * 60 + Integer.parseInt(digits.substring(2, 4))) * 60_000L;
            }
            return ms;
        } catch (Exception e) {
            return 0;
        }
    }

    private static JSONObject get(String url) throws Exception {
        // First as iptv-org's grabber asks (a plain request), then as Sky's own site does.
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36");
            if (attempt == 1) {
                c.setRequestProperty("Origin", "https://guidatv.sky.it");
                c.setRequestProperty("Referer", "https://guidatv.sky.it/");
            }
            try {
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("HTTP " + code);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[16384];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    return new JSONObject(out.toString("UTF-8"));
                }
            } catch (Exception e) {
                last = e;
            } finally {
                c.disconnect();
            }
        }
        throw last;
    }

    /** The words that recognize a team or player in a show's title ("inter", "juventus", "sinner"). */
    private static final Map<String, String> ALIAS = new HashMap<>();
    static {
        ALIAS.put("internazionale", "inter");
        ALIAS.put("hellas", "verona");
        ALIAS.put("man", "manchester");
        ALIAS.put("utd", "united");
        ALIAS.put("psg", "paris");
        ALIAS.put("munchen", "monaco");
        ALIAS.put("munich", "monaco");
    }

    private static List<String> keys(String name) {
        List<String> out = new ArrayList<>();
        for (String w : norm(name).split(" ")) {
            if (w.length() >= 4 || w.matches("roma|lazio|como|pisa|milan|inter|psg|ajax|lens|lyon|nice|bodo")) out.add(w);
        }
        List<String> extra = new ArrayList<>();
        for (String w : out) if (ALIAS.containsKey(w)) extra.add(ALIAS.get(w));
        out.addAll(extra);
        return out;
    }

    private static boolean has(String text, List<String> words) {
        String t = " " + text + " ";
        for (String w : words) if (t.contains(" " + w + " ")) return true;
        return false;
    }

    private static String norm(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
