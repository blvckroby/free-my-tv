package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * The sports calendar: a strip of days on top, the day's events in start order on the left (one card each, crests
 * facing each other), and the selected one big on the right (its competition's picture and logo, the scoreboard, the
 * app that shows it). The light behind takes the selected competition's color. Left and right change the day, up and
 * down move along the events, OK opens the sports app, Back closes. Drawn by hand like the home screen.
 */
final class SportsView extends View {
    private static final class Row {
        Sports.Event event;
        String time, crestHome, crestAway, comp;
        int color;
    }

    private static final int FIRST_DAY = -1, LAST_DAY = 6;
    // Layout, in 1920 × 1080 units.
    private static final float SIDE = 80, LIST_TOP = 262, LIST_R = 1176, ROW_H = 108, ROW_GAP = 12;
    private static final float PANEL_L = 1216, PANEL_TOP = 262, PANEL_BOTTOM = 990;

    private final Sports sports;
    private final float k;
    private final List<Row> rows = new ArrayList<>();
    private int dayOffset, selected;
    private float scroll, scrollTarget, highlight = -1;
    private boolean loading = true, pendingLogos;
    private Recs.Pick card;                 // the selected event, as the big card
    private int glow = 0xFF2E8B57, glowTarget = 0xFF2E8B57;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title, count, pill, pillOn, time, comp, team, teamRight, versus, event, place, hint;
    private final TextPaint pBoard, pWord, pTop, pSub, pTeam, pComp, pOpen, live;
    private final RectF box = new RectF(), tmp = new RectF();
    private final Rect src = new Rect();
    private final Path clip = new Path();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", java.util.Locale.US);
    private final SimpleDateFormat weekday;
    private String[] dayNames = new String[LAST_DAY - FIRST_DAY + 1];
    private final float[] pillX = new float[dayNames.length], pillW = new float[dayNames.length];
    private float dayPos;                   // the white pill slides between days
    private Shader shade;
    private String appName = "";            // the sports app OK opens (asked once per day shown)

    SportsView(Context c) {
        super(c);
        sports = Sports.get(c);
        k = c.getResources().getDisplayMetrics().widthPixels / 1920f;
        setFocusable(true);
        weekday = new SimpleDateFormat("EEE d", Ui.locale());
        title = paint(Ui.displaySemibold, 60, 0xFFFFFFFF);
        count = paint(Ui.medium, 24, 0x8CFFFFFF);
        pill = paint(Ui.semibold, 24, 0xB3FFFFFF);
        pill.setTextAlign(Paint.Align.CENTER);
        pillOn = paint(Ui.semibold, 24, 0xFF101014);
        pillOn.setTextAlign(Paint.Align.CENTER);
        time = new TextPaint(Ui.numbers(Ui.displaySemibold, 36 * k, 0xFFFFFFFF));
        comp = paint(Ui.semibold, 17, 0x99FFFFFF);
        comp.setLetterSpacing(0.08f);
        team = paint(Ui.semibold, 28, 0xFFFFFFFF);
        teamRight = paint(Ui.semibold, 28, 0xFFFFFFFF);
        teamRight.setTextAlign(Paint.Align.RIGHT);
        versus = paint(Ui.medium, 22, 0x73FFFFFF);
        versus.setTextAlign(Paint.Align.CENTER);
        event = paint(Ui.semibold, 28, 0xFFFFFFFF);
        place = paint(Ui.medium, 22, 0x99FFFFFF);
        hint = paint(Ui.medium, 22, 0x73FFFFFF);
        live = paint(Ui.semibold, 18, 0xFFFFFFFF);
        live.setTextAlign(Paint.Align.CENTER);
        live.setLetterSpacing(0.1f);
        pBoard = new TextPaint(Ui.numbers(Ui.displaySemibold, 66 * k, 0xFFFFFFFF));
        pBoard.setTextAlign(Paint.Align.CENTER);
        pBoard.setLetterSpacing(-0.03f);
        pWord = paint(Ui.displaySemibold, 48, 0xFFFFFFFF);
        pWord.setTextAlign(Paint.Align.CENTER);
        pTop = paint(Ui.semibold, 22, 0xCCFFFFFF);
        pTop.setTextAlign(Paint.Align.CENTER);
        pTop.setLetterSpacing(0.1f);
        pSub = paint(Ui.medium, 24, 0xB3FFFFFF);
        pSub.setTextAlign(Paint.Align.CENTER);
        pTeam = paint(Ui.semibold, 28, 0xFFFFFFFF);
        pTeam.setTextAlign(Paint.Align.CENTER);
        pComp = paint(Ui.semibold, 20, 0xB3FFFFFF);
        pComp.setTextAlign(Paint.Align.CENTER);
        pComp.setLetterSpacing(0.12f);
        pOpen = paint(Ui.semibold, 24, 0xFF101014);
        pOpen.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
        reload();
    }

    private TextPaint paint(android.graphics.Typeface face, float size, int color) {
        return new TextPaint(Ui.text(face, size * k, color));
    }

    private long day() {
        return Sports.dayStart(dayOffset);
    }

    /** Asks for the day's events and builds the list from what arrived. */
    private void reload() {
        List<Sports.Event> list = sports.agenda(day(), () -> reload());
        loading = list == null;
        appName = sports.opener()[1];
        rows.clear();
        if (list != null) {
            for (Sports.Event e : list) {
                Row r = new Row();
                r.event = e;
                r.time = clock.format(new Date(e.start));
                r.crestHome = sports.crest(e.home, e.logoHome);
                r.crestAway = sports.crest(e.away, e.logoAway);
                r.comp = e.competition == null ? "" : e.competition.toUpperCase(Ui.locale());
                r.color = accent(e.sport);
                rows.add(r);
            }
        }
        selected = Math.max(0, Math.min(selected, rows.size() - 1));
        scrollTarget = Math.max(0, Math.min(scrollTarget, contentHeight() - viewport()));
        choose();
        postInvalidateOnAnimation();
    }

    /** The selected event becomes the big card, and its color the light behind. */
    private void choose() {
        if (selected < rows.size()) {
            Row r = rows.get(selected);
            card = sports.card(r.event);
            glowTarget = r.color;
        } else {
            card = null;
        }
    }

    private float rowTop(int index) {
        return index * (ROW_H + ROW_GAP) * k;
    }

    private float contentHeight() {
        return rowTop(rows.size());
    }

    private float viewport() {
        return (PANEL_BOTTOM - LIST_TOP) * k;
    }

    boolean onKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (selected + 1 < rows.size()) selected++;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (selected > 0) selected--;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int next = Math.max(FIRST_DAY, Math.min(LAST_DAY, dayOffset + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
                if (next == dayOffset) return true;
                dayOffset = next;
                selected = 0;
                scrollTarget = 0;
                highlight = -1;
                reload();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                openApp();
                return true;
            default:
                return false;
        }
        choose();
        float top = rowTop(selected), bottom = top + ROW_H * k;
        if (top < scrollTarget) scrollTarget = Math.max(0, top);
        if (bottom > scrollTarget + viewport()) scrollTarget = bottom - viewport();
        postInvalidateOnAnimation();
        return true;
    }

    private void openApp() {
        String[] opener = sports.opener();
        if (opener[0].isEmpty()) return;
        Intent launch = HomeApps.launchIntent(getContext().getPackageManager(), opener[0]);
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(launch);
        } catch (RuntimeException e) {
            AppLog.w("couldn't open " + opener[0] + ": " + e.getMessage());
        }
    }

    private String dayName(int offset) {
        if (offset == 0) return Ui.tr("Hoje", "Today", "Hoy", "Oggi");
        if (offset == 1) return Ui.tr("Amanhã", "Tomorrow", "Mañana", "Domani");
        if (offset == -1) return Ui.tr("Ontem", "Yesterday", "Ayer", "Ieri");
        String d = weekday.format(new Date(Sports.dayStart(offset))).replace(".", "");
        return d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1);
    }

    /** Vivid colors for the light, the bars and the dots (the competitions' own colors are too dark to glow). */
    private static int accent(int sport) {
        switch (sport) {
            case Sports.SERIE_A: return 0xFF2E8FD8;
            case Sports.CHAMPIONS: return 0xFF4A6CF7;
            case Sports.EUROPA: return 0xFFF07A1A;
            case Sports.PREMIER: return 0xFFA64CC8;
            case Sports.LALIGA: return 0xFFFF6B3D;
            case Sports.BUNDES: return 0xFFE5262E;
            case Sports.LIGUE1: return 0xFF2F6BFF;
            case Sports.F1: return 0xFFE10600;
            case Sports.MOTO: return 0xFFFF5A1F;
            default: return 0xFF3BAA6C;
        }
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        pendingLogos = false;
        boolean moving = step();
        drawBackground(c, w, h);
        drawTop(c, w);
        drawList(c, h);
        drawPanel(c);
        String opener = appName;
        c.drawText(Ui.tr("◀ ▶ dia   ▲ ▼ evento   OK abre ", "◀ ▶ day   ▲ ▼ event   OK opens ", "◀ ▶ día   ▲ ▼ evento   OK abre ",
                "◀ ▶ giorno   ▲ ▼ evento   OK apre ") + opener, SIDE * k, h - 36 * k, hint);
        boolean anyLive = false;
        for (Row r : rows) anyLive |= r.event.live;
        if (moving || loading || anyLive || (card != null && card.live)) postInvalidateOnAnimation();   // the live dot breathes
        else if (pendingLogos) postInvalidateDelayed(600);
    }

    /** Eases the scroll, the highlight, the day pill and the light's color; true while any still moves. */
    private boolean step() {
        scroll += (scrollTarget - scroll) * 0.22f;
        if (Math.abs(scrollTarget - scroll) < 0.5f) scroll = scrollTarget;
        float aim = rowTop(selected);
        if (highlight < 0) highlight = aim;
        highlight += (aim - highlight) * 0.28f;
        if (Math.abs(aim - highlight) < 0.5f) highlight = aim;
        float dayAim = dayOffset - FIRST_DAY;
        dayPos += (dayAim - dayPos) * 0.25f;
        if (Math.abs(dayAim - dayPos) < 0.005f) dayPos = dayAim;
        glow = mix(glow, glowTarget, 0.12f);
        return scroll != scrollTarget || highlight != aim || dayPos != dayAim || glow != glowTarget;
    }

    private void drawBackground(Canvas c, int w, int h) {
        if (shade == null) shade = new LinearGradient(0, 0, 0, h, 0xFF0E0E14, 0xFF050507, Shader.TileMode.CLAMP);
        fill.setShader(shade);
        c.drawRect(0, 0, w, h, fill);
        // The selected competition's color, as a soft light from behind the big card and from the top left.
        fill.setShader(new RadialGradient(w * 0.8f, h * 0.42f, 900 * k, Ui.alpha(glow, 0.34f), Ui.alpha(glow, 0), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(new RadialGradient(0, 0, 760 * k, Ui.alpha(glow, 0.18f), Ui.alpha(glow, 0), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
    }

    private void drawTop(Canvas c, int w) {
        float left = SIDE * k;
        c.drawText(Ui.tr("Esportes", "Sports", "Deportes", "Sport"), left, 112 * k, title);
        String n = loading && rows.isEmpty() ? "" : rows.size() == 1 ? Ui.tr("1 evento", "1 event", "1 evento", "1 evento")
                : rows.size() + " " + Ui.tr("eventos", "events", "eventos", "eventi");
        c.drawText(n, left + title.measureText(Ui.tr("Esportes", "Sports", "Deportes", "Sport")) + 24 * k, 112 * k, count);
        // The days: glass pills, the chosen one white (it slides from day to day).
        float y = 152 * k, ph = 56 * k, x = left;
        for (int i = 0; i < dayNames.length; i++) {
            dayNames[i] = dayName(FIRST_DAY + i);
            pillW[i] = Math.max(112 * k, pill.measureText(dayNames[i]) + 48 * k);
            pillX[i] = x;
            x += pillW[i] + 10 * k;
        }
        for (int i = 0; i < dayNames.length; i++) {
            box.set(pillX[i], y, pillX[i] + pillW[i], y + ph);
            fill.setColor(0x14FFFFFF);
            c.drawRoundRect(box, ph / 2, ph / 2, fill);
        }
        int a = (int) Math.floor(dayPos), b = Math.min(dayNames.length - 1, a + 1);
        float t = dayPos - a;
        float sx = pillX[a] + (pillX[b] - pillX[a]) * t, sw = pillW[a] + (pillW[b] - pillW[a]) * t;
        box.set(sx, y, sx + sw, y + ph);
        fill.setColor(0xFFFFFFFF);
        c.drawRoundRect(box, ph / 2, ph / 2, fill);
        for (int i = 0; i < dayNames.length; i++) {
            boolean on = i == dayOffset - FIRST_DAY;
            float base = y + ph / 2 + Ui.halfCap(pill);
            c.drawText(dayNames[i], pillX[i] + pillW[i] / 2, base, on ? pillOn : pill);
        }
    }

    private void drawList(Canvas c, int h) {
        float left = SIDE * k, right = LIST_R * k, top = LIST_TOP * k;
        c.save();
        c.clipRect(0, top - 8 * k, right + 20 * k, PANEL_BOTTOM * k + 8 * k);
        if (rows.isEmpty()) {
            box.set(left, top, right, top + 220 * k);
            fill.setColor(0x0FFFFFFF);
            c.drawRoundRect(box, 28 * k, 28 * k, fill);
            String msg = loading ? Ui.tr("Carregando os eventos…", "Loading the events…", "Cargando los eventos…", "Carico gli eventi…")
                    : Ui.tr("Nenhum evento neste dia.", "No events on this day.", "No hay eventos este día.", "Nessun evento in questo giorno.");
            c.drawText(msg, left + 40 * k, box.centerY() + Ui.halfCap(event), event);
            if (!loading) c.drawText(Ui.tr("◀ ▶ para ver outro dia", "◀ ▶ to see another day", "◀ ▶ para ver otro día", "◀ ▶ per vedere un altro giorno"),
                    left + 40 * k, box.centerY() + 48 * k, place);
        }
        // The highlight slides from row to row, under the cards' content.
        if (!rows.isEmpty()) {
            float hy = top + highlight - scroll;
            box.set(left, hy, right, hy + ROW_H * k);
            fill.setColor(0x26FFFFFF);
            c.drawRoundRect(box, 26 * k, 26 * k, fill);
            stroke.setStrokeWidth(2 * k);
            stroke.setColor(0x66FFFFFF);
            c.drawRoundRect(box, 26 * k, 26 * k, stroke);
        }
        for (int i = 0; i < rows.size(); i++) {
            float y = top + rowTop(i) - scroll;
            if (y + ROW_H * k < top - 10 * k || y > h) continue;
            drawRow(c, rows.get(i), left, right, y, i == selected);
        }
        c.restore();
    }

    private void drawRow(Canvas c, Row r, float left, float right, float y, boolean on) {
        Sports.Event e = r.event;
        float rh = ROW_H * k, cy = y + rh / 2;
        if (!on) {
            box.set(left, y, right, y + rh);
            fill.setColor(0x0DFFFFFF);
            c.drawRoundRect(box, 26 * k, 26 * k, fill);
        }
        float dim = e.done ? 0.45f : 1;
        // The competition's color, as a short bar on the left.
        fill.setColor(Ui.alpha(r.color, dim));
        box.set(left + 18 * k, cy - 24 * k, left + 24 * k, cy + 24 * k);
        c.drawRoundRect(box, 3 * k, 3 * k, fill);
        float tx = left + 44 * k;
        if (e.live) {
            drawLive(c, tx, cy - 32 * k);
        } else {
            time.setAlpha(Math.round(255 * dim));
            c.drawText(r.time, tx, cy + 2 * k, time);
            time.setAlpha(255);
        }
        comp.setAlpha(Math.round(153 * dim));
        c.drawText(TextUtils.ellipsize(r.comp, comp, 170 * k, TextUtils.TruncateAt.END).toString(), tx, cy + 34 * k, comp);
        comp.setAlpha(153);
        float x0 = left + 240 * k, x1 = right - 32 * k;
        if (!e.home.isEmpty() && !e.away.isEmpty()) {
            // Home on the left facing away on the right, crests next to the "vs" in the middle.
            float mid = (x0 + x1) / 2, crest = 50 * k, gap = 46 * k;
            drawCrest(c, r.crestHome, e.home, mid - gap - crest, cy, crest, dim);
            drawCrest(c, r.crestAway, e.away, mid + gap, cy, crest, dim);
            c.drawText("vs", mid, cy + Ui.halfCap(versus), versus);
            float room = mid - gap - crest - 18 * k - x0;
            teamRight.setAlpha(Math.round(255 * dim));
            team.setAlpha(Math.round(255 * dim));
            c.drawText(TextUtils.ellipsize(e.home, teamRight, room, TextUtils.TruncateAt.END).toString(), mid - gap - crest - 18 * k, cy + Ui.halfCap(teamRight), teamRight);
            c.drawText(TextUtils.ellipsize(e.away, team, room, TextUtils.TruncateAt.END).toString(), mid + gap + crest + 18 * k, cy + Ui.halfCap(team), team);
            teamRight.setAlpha(255);
            team.setAlpha(255);
        } else {
            event.setAlpha(Math.round(255 * dim));
            c.drawText(TextUtils.ellipsize(e.title == null ? "" : e.title, event, x1 - x0, TextUtils.TruncateAt.END).toString(), x0, cy - 2 * k, event);
            event.setAlpha(255);
            if (!e.place.isEmpty()) c.drawText(TextUtils.ellipsize(e.place, place, x1 - x0, TextUtils.TruncateAt.END).toString(), x0, cy + 32 * k, place);
        }
    }

    /** The red LIVE pill, its dot breathing. */
    private void drawLive(Canvas c, float x, float y) {
        float pw = 86 * k, ph = 30 * k;
        box.set(x, y, x + pw, y + ph);
        fill.setColor(0xFFE5262E);
        c.drawRoundRect(box, ph / 2, ph / 2, fill);
        float beat = 0.5f + 0.5f * (float) Math.sin(SystemClock.uptimeMillis() / 260.0);
        fill.setColor(Ui.alpha(0xFFFFFFFF, 0.45f + 0.55f * beat));
        c.drawCircle(x + 16 * k, y + ph / 2, 5 * k, fill);
        c.drawText("LIVE", x + pw / 2 + 9 * k, y + ph / 2 + Ui.halfCap(live), live);
    }

    private void drawCrest(Canvas c, String url, String club, float x, float cy, float size, float alpha) {
        Bitmap logo = sports.bitmap(url, club);
        if (logo == null) {
            if (url != null && !url.isEmpty()) pendingLogos = true;
            fill.setColor(0x1AFFFFFF);
            c.drawCircle(x + size / 2, cy, size * 0.42f, fill);
            return;
        }
        float scale = Math.min(size / logo.getWidth(), size / logo.getHeight());
        float lw = logo.getWidth() * scale, lh = logo.getHeight() * scale;
        tmp.set(x + (size - lw) / 2, cy - lh / 2, x + (size + lw) / 2, cy + lh / 2);
        image.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(logo, null, tmp, image);
        image.setAlpha(255);
    }

    // MARK: - The big card

    private void drawPanel(Canvas c) {
        float l = PANEL_L * k, r = getWidth() - SIDE * k, t = PANEL_TOP * k, b = PANEL_BOTTOM * k, rad = 34 * k;
        box.set(l, t, r, b);
        fill.setColor(0x14FFFFFF);
        c.drawRoundRect(box, rad, rad, fill);
        if (card == null) return;
        Recs.Pick p = card;
        // The competition's picture, filling the card, darker toward the bottom where the text is.
        Bitmap art = p.art == null || p.art.isEmpty() ? null : sports.bitmap(p.art, null, 1280);
        if (art == null && p.art != null && !p.art.isEmpty()) pendingLogos = true;
        c.save();
        clip.reset();
        clip.addRoundRect(box, rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        fill.setShader(new LinearGradient(0, t, 0, b, mix(p.accent, 0xFF000000, 0.15f), mix(p.accent, 0xFF000000, 0.7f), Shader.TileMode.CLAMP));
        c.drawRect(box, fill);
        fill.setShader(null);
        if (art != null) {
            float ratio = box.width() / box.height();
            int sw = art.getWidth(), sh = art.getHeight();
            if ((float) sw / sh > ratio) sw = Math.round(sh * ratio); else sh = Math.round(sw / ratio);
            src.set((art.getWidth() - sw) / 2, (art.getHeight() - sh) / 2, (art.getWidth() + sw) / 2, (art.getHeight() + sh) / 2);
            image.setAlpha(p.flat ? 255 : 150);
            c.drawBitmap(art, src, box, image);
            image.setAlpha(255);
        }
        fill.setShader(new LinearGradient(0, t, 0, b, new int[] {0x33000000, 0x1A000000, 0xB3000000},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(box, fill);
        fill.setShader(null);
        c.restore();
        stroke.setStrokeWidth(1.5f * k);
        stroke.setColor(0x33FFFFFF);
        c.drawRoundRect(box, rad, rad, stroke);

        float mx = box.centerX();
        // The competition: its logo in its own colors, or its name.
        Bitmap logo = p.logoComp == null || p.logoComp.isEmpty() ? null : sports.bitmap(p.logoComp, null);
        if (logo == null && p.logoComp != null && !p.logoComp.isEmpty()) pendingLogos = true;
        if (logo != null) {
            float lh = 72 * k, scale = Math.min(lh / logo.getHeight(), 300 * k / logo.getWidth());
            float lw = logo.getWidth() * scale;
            tmp.set(mx - lw / 2, t + 48 * k, mx + lw / 2, t + 48 * k + logo.getHeight() * scale);
            c.drawBitmap(logo, null, tmp, image);
        } else {
            c.drawText(p.label.toUpperCase(Ui.locale()), mx, t + 92 * k, pComp);
        }

        float cy = t + 330 * k;
        if (!p.home.isEmpty() && !p.away.isEmpty()) {
            float size = 140 * k, off = 185 * k;
            drawCrest(c, p.logoHome, p.home, mx - off - size / 2, cy, size, 1);
            drawCrest(c, p.logoAway, p.away, mx + off - size / 2, cy, size, 1);
            float nw = 230 * k;
            c.drawText(TextUtils.ellipsize(name(p.homeShort, p.home), pTeam, nw, TextUtils.TruncateAt.END).toString(), mx - off, cy + size / 2 + 44 * k, pTeam);
            c.drawText(TextUtils.ellipsize(name(p.awayShort, p.away), pTeam, nw, TextUtils.TruncateAt.END).toString(), mx + off, cy + size / 2 + 44 * k, pTeam);
            drawBoard(c, p, mx, cy);
        } else {
            String race = !p.raceName.isEmpty() ? p.raceName : p.title;
            c.drawText(TextUtils.ellipsize(race == null ? "" : race, pWord, box.width() - 80 * k, TextUtils.TruncateAt.END).toString(), mx, cy - 40 * k, pWord);
            if (!p.session.isEmpty()) c.drawText(p.session.toUpperCase(Ui.locale()), mx, cy + 6 * k, pTop);
            drawBoard(c, p, mx, cy + 110 * k);
            if (!p.place.isEmpty()) c.drawText(p.place, mx, cy + 230 * k, pSub);
        }

        // Where to watch it.
        String app = appName;
        if (!app.isEmpty()) {
            String label = Ui.tr("Abrir ", "Open ", "Abrir ", "Apri ") + app;
            float bw = pOpen.measureText(label) + 72 * k, bh = 64 * k, by = b - 44 * k - bh;
            box.set(mx - bw / 2, by, mx + bw / 2, by + bh);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(box, bh / 2, bh / 2, fill);
            c.drawText(label, mx, by + bh / 2 + Ui.halfCap(pOpen), pOpen);
        }
    }

    /** Between the crests: the day above, the time (or the minute) big, the countdown below. */
    private void drawBoard(Canvas c, Recs.Pick p, float mx, float cy) {
        if (p.live) {
            drawLive(c, mx - 43 * k, cy - 92 * k);
        } else if (!p.boardTop.isEmpty()) {
            c.drawText(p.boardTop.toUpperCase(Ui.locale()), mx, cy - 58 * k, pTop);
        }
        boolean word = p.board.length() > 0 && !Character.isDigit(p.board.charAt(0));
        c.drawText(p.board, mx, cy + (word ? 16 : 26) * k, word ? pWord : pBoard);
        if (!p.boardSub.isEmpty()) c.drawText(p.boardSub, mx, cy + 72 * k, pSub);
    }

    private static String name(String shortName, String full) {
        return shortName != null && !shortName.isEmpty() ? shortName : full;
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t), g = Math.round(ag + (bg - ag) * t), bl = Math.round(ab + (bb - ab) * t);
        if (t < 1 && r == ar && g == ag && bl == ab && (a & 0xFFFFFF) != (b & 0xFFFFFF)) {
            r += Integer.signum(br - ar);       // always a step closer, so the fade ends
            g += Integer.signum(bg - ag);
            bl += Integer.signum(bb - ab);
        }
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
