package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The day's sporting events, from yesterday to six days ahead. Days are glass pills across the top (the chosen one is a
 * white slider), the events a list on the left, and the chosen event a big panel on the right with its crests, its
 * clock or countdown and the competition's logo; the glow behind takes the competition's color. Left and right change
 * the day, up and down move along the events, OK opens the sports app, Back closes. Drawn by hand like the home screen.
 */
final class SportsView extends View {
    private static final int FIRST_DAY = -1, LAST_DAY = 6;

    private static final class Row {
        Sports.Event event;
        String comp, home, away, title, place;      // cut to fit, on first draw
        boolean cut;
    }

    private final Sports sports;
    private final float k;
    private final List<Row> rows = new ArrayList<>();
    private int dayOffset, selected;
    private float scroll, scrollTarget, hlY, sliderX, sliderW, glowMix = 1;
    private int glowFrom, glowTo = -1;
    private boolean loading = true, pendingLogos, started;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint title, count, pill, time, name, small, compCaps, big, bigWord, bigDim, panelName, raceName, chip, help, button;
    private final RectF box = new RectF(), tmp = new RectF();
    private final Path panelClip = new Path();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", java.util.Locale.US);
    private final SimpleDateFormat dayFormat;
    private final String[] dayLabels = new String[LAST_DAY - FIRST_DAY + 1];
    private final float[] pillX = new float[dayLabels.length + 1];
    private final Shader[] glowBig = new Shader[10], glowSmall = new Shader[10], panelTint = new Shader[10];
    private Shader background, panelShade;

    SportsView(Context c) {
        super(c);
        sports = Sports.get(c);
        k = c.getResources().getDisplayMetrics().widthPixels / 1920f;
        setFocusable(true);
        title = new TextPaint(Ui.text(Ui.displaySemibold, 60 * k, 0xFFFFFFFF));
        count = new TextPaint(Ui.text(Ui.displayMedium, 24 * k, 0x8CFFFFFF));
        pill = new TextPaint(Ui.text(Ui.semibold, 24 * k, 0xB3FFFFFF));
        time = new TextPaint(Ui.numbers(Ui.semibold, 28 * k, 0xFFFFFFFF));
        name = new TextPaint(Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF));
        small = new TextPaint(Ui.text(Ui.medium, 22 * k, 0x99FFFFFF));
        compCaps = new TextPaint(Ui.text(Ui.semibold, 17 * k, 0x99FFFFFF));
        compCaps.setLetterSpacing(0.08f);
        big = new TextPaint(Ui.numbers(Ui.displaySemibold, 52 * k, 0xFFFFFFFF));
        bigWord = new TextPaint(Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF));
        bigDim = new TextPaint(Ui.text(Ui.semibold, 22 * k, 0xCCFFFFFF));
        bigDim.setLetterSpacing(0.1f);
        panelName = new TextPaint(Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF));
        raceName = new TextPaint(Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF));
        chip = new TextPaint(Ui.text(Ui.semibold, 18 * k, 0xFFFFFFFF));
        chip.setLetterSpacing(0.1f);
        help = new TextPaint(Ui.text(Ui.medium, 22 * k, 0x73FFFFFF));
        button = new TextPaint(Ui.text(Ui.semibold, 24 * k, 0xFF101014));
        stroke.setStyle(Paint.Style.STROKE);
        dayFormat = new SimpleDateFormat("EEE d", Ui.locale());
        for (int i = 0; i < dayLabels.length; i++) dayLabels[i] = dayLabel(FIRST_DAY + i);
        float x = 0;
        for (int i = 0; i < dayLabels.length; i++) {
            pillX[i] = x;
            x += Math.max(112 * k, pill.measureText(dayLabels[i]) + 48 * k) + 10 * k;
        }
        pillX[dayLabels.length] = x;
        reload();
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String dayLabel(int off) {
        if (off == 0) return Ui.tr("Hoje", "Today", "Hoy", "Oggi");
        if (off == 1) return Ui.tr("Amanhã", "Tomorrow", "Mañana", "Domani");
        if (off == -1) return Ui.tr("Ontem", "Yesterday", "Ayer", "Ieri");
        return cap(dayFormat.format(new Date(Sports.dayStart(off))).replace(".", ""));
    }

    private long day() {
        return Sports.dayStart(dayOffset);
    }

    /** Asks for the day's events and builds the list from what arrived. */
    private void reload() {
        List<Sports.Event> list = sports.agenda(day(), () -> reload());
        loading = list == null;
        rows.clear();
        if (list != null) {
            for (int sport : Sports.ORDER) {
                for (Sports.Event e : list) {
                    if (e.sport != sport) continue;
                    Row r = new Row();
                    r.event = e;
                    rows.add(r);
                }
            }
        }
        selected = Math.max(0, Math.min(selected, rows.size() - 1));
        scrollTarget = Math.min(scrollTarget, Math.max(0, contentHeight() - viewport()));
        postInvalidateOnAnimation();
    }

    private float contentHeight() {
        return rows.size() * 120 * k;
    }

    private float viewport() {
        return 744 * k;
    }

    boolean onKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                selected = Math.min(rows.size() - 1, selected + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                selected = Math.max(0, selected - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                int next = dayOffset + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                if (next < FIRST_DAY || next > LAST_DAY) return true;
                dayOffset = next;
                selected = 0;
                scroll = scrollTarget = 0;
                reload();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                openApp();
                return true;
            default:
                return false;
        }
        // keep the selected row near the middle of the list
        float top = selected * 120 * k;
        scrollTarget = Math.max(0, Math.min(top - (viewport() - 108 * k) / 2, contentHeight() - viewport()));
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

    private static int accent(int sport) {
        switch (sport) {
            case Sports.SERIE_A: return 0x38A8BC;
            case Sports.CHAMPIONS: return 0x4A6CF7;
            case Sports.EUROPA: return 0xE8650A;
            case Sports.PREMIER: return 0xA64CC8;
            case Sports.LALIGA: return 0xEE8707;
            case Sports.BUNDES: return 0xD3010C;
            case Sports.LIGUE1: return 0x2F6BFF;
            case Sports.F1: return 0xE10600;
            case Sports.MOTO: return 0xE8590C;
            default: return 0x2E8B57;
        }
    }

    private void buildShaders(int w, int h) {
        background = new LinearGradient(0, 0, 0, h, 0xFF0E0E14, 0xFF050507, Shader.TileMode.CLAMP);
        panelShade = new LinearGradient(0, 0, 0, 728 * k, 0x00000000, 0x99000000, Shader.TileMode.CLAMP);
        for (int s = 0; s < glowBig.length; s++) {
            int c = accent(s);
            glowBig[s] = new RadialGradient(w * 0.8f, h * 0.42f, 900 * k, 0x57000000 | c, c, Shader.TileMode.CLAMP);
            glowSmall[s] = new RadialGradient(0, 0, 760 * k, 0x2E000000 | c, c, Shader.TileMode.CLAMP);
            panelTint[s] = new LinearGradient(0, 0, 0, 728 * k, 0x99000000 | c, 0x33000000 | c, Shader.TileMode.CLAMP);
        }
    }

    private void drawGlow(Canvas c, int sport, int alpha, int w, int h) {
        if (sport < 0 || alpha <= 0) return;
        // the shaders fade to their own color at 0 alpha: draw them with the alpha of the transition
        fill.setAlpha(alpha);
        fill.setShader(glowBig[sport]);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(glowSmall[sport]);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
        fill.setAlpha(255);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (background == null) buildShaders(w, h);
        boolean animating = false;
        fill.setShader(background);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        Row sel = selected < rows.size() ? rows.get(selected) : null;
        int sport = sel == null ? -1 : sel.event.sport;
        if (sport != glowTo) {
            glowFrom = glowTo;
            glowTo = sport;
            glowMix = started ? 0 : 1;
        }
        started = true;
        if (glowMix < 1) {
            glowMix = Math.min(1, glowMix + 0.06f);
            animating = true;
        }
        drawGlow(c, glowFrom, (int) (255 * (1 - glowMix)), w, h);
        drawGlow(c, glowTo, (int) (255 * glowMix), w, h);

        float left = 80 * k;
        c.drawText(Ui.tr("Esportes", "Sports", "Deportes", "Sport"), left, 108 * k, title);
        if (!loading) {
            String n = rows.size() == 1 ? Ui.tr("1 evento", "1 event", "1 evento", "1 evento")
                    : rows.size() + Ui.tr(" eventos", " events", " eventos", " eventi");
            c.drawText(n, left + title.measureText(Ui.tr("Esportes", "Sports", "Deportes", "Sport")) + 24 * k, 108 * k, count);
        }

        animating |= drawDays(c, left);

        pendingLogos = false;
        scroll += (scrollTarget - scroll) * 0.25f;
        if (Math.abs(scrollTarget - scroll) > 0.5f) animating = true;
        else scroll = scrollTarget;
        float hlTarget = selected * 120 * k;
        hlY += (hlTarget - hlY) * 0.3f;
        if (Math.abs(hlTarget - hlY) > 0.5f) animating = true;
        else hlY = hlTarget;

        float listTop = 254 * k, listW = 1096 * k;
        c.save();
        c.clipRect(left - 4 * k, listTop, left + listW + 4 * k, listTop + 744 * k);
        if (rows.isEmpty()) {
            box.set(left, listTop + 8 * k, left + listW, listTop + 228 * k);
            fill.setColor(0x0FFFFFFF);
            c.drawRoundRect(box, 28 * k, 28 * k, fill);
            c.drawText(loading ? Ui.tr("Carregando os eventos…", "Loading the events…", "Cargando los eventos…", "Carico gli eventi…")
                            : Ui.tr("Nenhum evento neste dia.", "No events on this day.", "No hay eventos este día.", "Nessun evento in questo giorno."),
                    left + 40 * k, listTop + 110 * k, name);
            if (!loading) c.drawText(Ui.tr("◀ ▶ para ver outro dia", "◀ ▶ to see another day", "◀ ▶ para ver otro día", "◀ ▶ per vedere un altro giorno"),
                    left + 40 * k, listTop + 160 * k, small);
        } else {
            float base = listTop + 8 * k - scroll;
            box.set(left, base + hlY, left + listW, base + hlY + 108 * k);
            fill.setColor(0x26FFFFFF);
            c.drawRoundRect(box, 26 * k, 26 * k, fill);
            stroke.setStrokeWidth(2 * k);
            stroke.setColor(0x66FFFFFF);
            tmp.set(box);
            tmp.inset(k, k);
            c.drawRoundRect(tmp, 25 * k, 25 * k, stroke);
            long now = System.currentTimeMillis();
            for (int i = 0; i < rows.size(); i++) {
                float y = base + i * 120 * k;
                if (y + 108 * k < listTop || y > listTop + 744 * k) continue;
                drawEvent(c, rows.get(i), left, listW, y, i == selected, now);
            }
        }
        c.restore();

        if (sel != null) drawPanel(c, sel, 1216 * k, 262 * k, 624 * k, 728 * k);

        String opener = sports.opener()[1];
        c.drawText(Ui.tr("◀ ▶ dia   ▲ ▼ evento   OK abre ", "◀ ▶ day   ▲ ▼ event   OK opens ", "◀ ▶ día   ▲ ▼ evento   OK abre ",
                "◀ ▶ giorno   ▲ ▼ evento   OK apre ") + opener, left, h - 30 * k, help);

        if (animating || loading) postInvalidateOnAnimation();
        else if (pendingLogos) postInvalidateDelayed(700);      // logos still arriving
        else postInvalidateDelayed(20_000);                     // the countdown
    }

    /** The day pills with the white slider behind the chosen one. Returns true while the slider moves. */
    private boolean drawDays(Canvas c, float left) {
        float top = 152 * k, hgt = 56 * k;
        int sel = dayOffset - FIRST_DAY;
        float tx = pillX[sel], tw = pillX[sel + 1] - pillX[sel] - 10 * k;
        if (sliderW == 0) {
            sliderX = tx;
            sliderW = tw;
        }
        sliderX += (tx - sliderX) * 0.3f;
        sliderW += (tw - sliderW) * 0.3f;
        boolean moving = Math.abs(tx - sliderX) > 0.5f || Math.abs(tw - sliderW) > 0.5f;
        if (!moving) {
            sliderX = tx;
            sliderW = tw;
        }
        for (int i = 0; i < dayLabels.length; i++) {
            float w = pillX[i + 1] - pillX[i] - 10 * k;
            box.set(left + pillX[i], top, left + pillX[i] + w, top + hgt);
            fill.setColor(0x14FFFFFF);
            c.drawRoundRect(box, hgt / 2, hgt / 2, fill);
        }
        box.set(left + sliderX, top, left + sliderX + sliderW, top + hgt);
        fill.setColor(0xFFFFFFFF);
        c.drawRoundRect(box, hgt / 2, hgt / 2, fill);
        for (int i = 0; i < dayLabels.length; i++) {
            float w = pillX[i + 1] - pillX[i] - 10 * k;
            pill.setColor(i == sel ? 0xFF101014 : 0xB3FFFFFF);
            centered(c, dayLabels[i], left + pillX[i] + w / 2, top + hgt / 2 + Ui.halfCap(pill), pill);
        }
        return moving;
    }

    private void drawEvent(Canvas c, Row r, float left, float width, float y, boolean on, long now) {
        Sports.Event e = r.event;
        if (!on) {
            box.set(left, y, left + width, y + 108 * k);
            fill.setColor(0x0DFFFFFF);
            c.drawRoundRect(box, 26 * k, 26 * k, fill);
        }
        boolean over = now >= e.start + e.minutes * 60_000L || e.done;
        int alpha = over && !on ? 0x73 : 0xFF;
        fill.setColor(alpha << 24 | accent(e.sport));
        box.set(left + 18 * k, y + 30 * k, left + 24 * k, y + 78 * k);
        c.drawRoundRect(box, 3 * k, 3 * k, fill);
        time.setAlpha(alpha);
        name.setAlpha(alpha);
        compCaps.setAlpha(alpha * 0x99 / 255);
        if (e.live) {
            liveChip(c, left + 44 * k, y + 22 * k, 30 * k, chip, true);
        } else {
            c.drawText(clock.format(new Date(e.start)), left + 44 * k, y + 30 * k + Ui.cap(time), time);
        }
        if (!r.cut) {
            r.cut = true;
            r.comp = TextUtils.ellipsize(e.competition.toUpperCase(Ui.locale()), compCaps, 170 * k, TextUtils.TruncateAt.END).toString();
            r.home = TextUtils.ellipsize(e.home, name, 298 * k, TextUtils.TruncateAt.END).toString();
            r.away = TextUtils.ellipsize(e.away, name, 298 * k, TextUtils.TruncateAt.END).toString();
            r.title = TextUtils.ellipsize(e.title, name, 824 * k, TextUtils.TruncateAt.END).toString();
            r.place = TextUtils.ellipsize(e.place, small, 824 * k, TextUtils.TruncateAt.END).toString();
        }
        c.drawText(r.comp, left + 44 * k, y + 72 * k + Ui.cap(compCaps), compCaps);
        float mid = y + 54 * k;
        if (!e.home.isEmpty() && !e.away.isEmpty()) {
            name.setTextAlign(Paint.Align.RIGHT);
            c.drawText(r.home, left + 538 * k, mid + Ui.halfCap(name), name);
            name.setTextAlign(Paint.Align.LEFT);
            c.drawText(r.away, left + 766 * k, mid + Ui.halfCap(name), name);
            crest(c, e.logoHome, e.home, left + 581 * k, mid, 50 * k);
            crest(c, e.logoAway, e.away, left + 723 * k, mid, 50 * k);
            small.setAlpha(alpha * 0x73 / 255);
            centered(c, "vs", left + 652 * k, mid + Ui.halfCap(small), small);
            small.setAlpha(0x99);
        } else {
            c.drawText(r.title, left + 240 * k, y + 30 * k + Ui.cap(name), name);
            if (!e.place.isEmpty()) c.drawText(r.place, left + 240 * k, y + 66 * k + Ui.cap(small), small);
        }
        time.setAlpha(255);
        name.setAlpha(255);
    }

    // crest centered at (cx, cy); a circle with initials while there is none
    private void crest(Canvas c, String url, String team, float cx, float cy, float size) {
        Bitmap logo = sports.bitmap(url, team);
        if (logo == null && url != null && !url.isEmpty()) pendingLogos = true;
        if (logo != null) {
            float scale = Math.min(size / logo.getWidth(), size / logo.getHeight());
            float lw = logo.getWidth() * scale, lh = logo.getHeight() * scale;
            tmp.set(cx - lw / 2, cy - lh / 2, cx + lw / 2, cy + lh / 2);
            c.drawBitmap(logo, null, tmp, logoPaint);
        } else if (team != null && !team.isEmpty()) {
            fill.setColor(0x24FFFFFF);
            c.drawCircle(cx, cy, size * 0.42f, fill);
            small.setAlpha(0xE6);
            centered(c, team.substring(0, Math.min(2, team.length())).toUpperCase(Ui.locale()), cx, cy + Ui.halfCap(small), small);
            small.setAlpha(0x99);
        }
    }

    private void drawPanel(Canvas c, Row r, float x, float y, float w, float h) {
        Sports.Event e = r.event;
        c.save();
        c.translate(x, y);
        panelClip.reset();
        box.set(0, 0, w, h);
        panelClip.addRoundRect(box, 34 * k, 34 * k, Path.Direction.CW);
        c.clipPath(panelClip);
        fill.setColor(0x14FFFFFF);
        c.drawRect(box, fill);
        fill.setShader(panelTint[e.sport]);
        c.drawRect(box, fill);
        fill.setShader(panelShade);
        c.drawRect(box, fill);
        fill.setShader(null);

        long now = System.currentTimeMillis();
        boolean over = e.done || now >= e.start + e.minutes * 60_000L;
        boolean twoSides = !e.home.isEmpty() && !e.away.isEmpty();
        String clockText = e.live ? (twoSides && now > e.start ? (now - e.start) / 60_000L + "'" : Ui.tr("AO VIVO", "LIVE", "EN VIVO", "IN DIRETTA"))
                : clock.format(new Date(e.start));
        Bitmap logo = sports.bitmap(sports.logoFor(e), e.competition, 512);
        if (logo == null && !sports.logoFor(e).isEmpty()) pendingLogos = true;
        float cx = w / 2;
        if (twoSides) {
            drawLogoFit(c, logo, e.competition, cx, 48 * k, 300 * k, 72 * k);
            crest(c, e.logoHome, e.home, 57 * k + 70 * k, 330 * k, 140 * k);
            crest(c, e.logoAway, e.away, w - 57 * k - 70 * k, 330 * k, 140 * k);
            panelName.setAlpha(255);
            String hn = TextUtils.ellipsize(e.home, panelName, 230 * k, TextUtils.TruncateAt.END).toString();
            String an = TextUtils.ellipsize(e.away, panelName, 230 * k, TextUtils.TruncateAt.END).toString();
            centered(c, hn, 12 * k + 115 * k, 422 * k + Ui.cap(panelName), panelName);
            centered(c, an, w - 12 * k - 115 * k, 422 * k + Ui.cap(panelName), panelName);
            board(c, cx, 330 * k, e, clockText, now, over);
        } else {
            boolean f1 = e.sport == Sports.F1;
            if (!f1) drawLogoFit(c, logo, e.competition, cx, 48 * k, 300 * k, 72 * k);
            TextPaint face = raceFace(e.sport);
            String t = e.title.replace(" Grand Prix", "");
            if (face != raceName) t = t.toUpperCase(Ui.locale());
            float yy = (f1 ? 96 : 176) * k;
            for (String line : wrap(t, face, w - 80 * k, 3)) {
                centered(c, line, cx, yy + Ui.cap(face), face);
                yy += 40 * k * 1.12f;
            }
            yy += 40 * k;
            if (e.live) {
                liveChip(c, cx - 70 * k, yy, 30 * k, chip, false);
                yy += 30 * k + 18 * k;
            } else {
                centered(c, dayWord(e.start).toUpperCase(Ui.locale()), cx, yy + Ui.cap(bigDim), bigDim);
                yy += 18 * k + 18 * k;
            }
            TextPaint bp = clockText.matches("[0-9:'+]+") ? big : bigWord;
            centered(c, clockText, cx, yy + Ui.cap(bp), bp);
            yy += bp.getTextSize() * 1.0f + 16 * k;
            String sub = e.live ? "" : over ? finished() : countdown(e.start, now);
            if (!sub.isEmpty()) {
                centered(c, sub, cx, yy + Ui.cap(small), small);
                yy += 46 * k;
            }
            if (!e.place.isEmpty()) centered(c, TextUtils.ellipsize(e.place, small, w - 80 * k, TextUtils.TruncateAt.END).toString(), cx, yy + 30 * k + Ui.cap(small), small);
            if (f1) drawLogoFit(c, logo, e.competition, cx, h - 190 * k, 300 * k, 60 * k);
        }
        String opener = sports.opener()[1];
        if (!opener.isEmpty()) {
            String label = Ui.tr("Abrir ", "Open ", "Abrir ", "Apri ") + opener;
            float bw = button.measureText(label) + 72 * k;
            box.set(cx - bw / 2, h - 108 * k, cx + bw / 2, h - 44 * k);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(box, 32 * k, 32 * k, fill);
            centered(c, label, cx, h - 76 * k + Ui.halfCap(button), button);
        }
        stroke.setStrokeWidth(1.5f * k);
        stroke.setColor(0x33FFFFFF);
        tmp.set(0, 0, w, h);
        tmp.inset(0.75f * k, 0.75f * k);
        c.drawRoundRect(tmp, 34 * k, 34 * k, stroke);
        c.restore();
    }

    private TextPaint f1Paint, mgpPaint;

    // F1 and MotoGP names are set in their own fonts
    private TextPaint raceFace(int sport) {
        if (sport == Sports.F1 && Ui.f1 != null) {
            if (f1Paint == null) {
                f1Paint = new TextPaint(raceName);
                f1Paint.setTypeface(Ui.f1);
            }
            return f1Paint;
        }
        if (sport == Sports.MOTO && Ui.mgp != null) {
            if (mgpPaint == null) {
                mgpPaint = new TextPaint(raceName);
                mgpPaint.setTypeface(Ui.mgp);
            }
            return mgpPaint;
        }
        return raceName;
    }

    /** Day word, clock or minute and countdown, centered on cy. */
    private void board(Canvas c, float cx, float cy, Sports.Event e, String clockText, long now, boolean over) {
        TextPaint bp = clockText.matches("[0-9:'+]+") ? big : bigWord;
        if (e.live) {
            liveChip(c, cx - 56 * k, cy - 92 * k, 30 * k, chip, false);
        } else {
            centered(c, dayWord(e.start).toUpperCase(Ui.locale()), cx, cy - 74 * k + Ui.cap(bigDim), bigDim);
        }
        centered(c, clockText, cx, cy + (bp == big ? 26 : 16) * k - (bp == big ? 38 : 30) * k + Ui.cap(bp), bp);
        String sub = e.live ? "" : over ? finished() : countdown(e.start, now);
        if (!sub.isEmpty()) centered(c, sub, cx, cy + 54 * k + Ui.cap(small), small);
    }

    /** The competition's logo in a box centered on cx (its name when it has none yet). */
    private void drawLogoFit(Canvas c, Bitmap logo, String comp, float cx, float top, float maxW, float maxH) {
        if (logo != null) {
            float scale = Math.min(maxW / logo.getWidth(), maxH / logo.getHeight());
            float lw = logo.getWidth() * scale, lh = logo.getHeight() * scale;
            tmp.set(cx - lw / 2, top + (maxH - lh) / 2, cx + lw / 2, top + (maxH + lh) / 2);
            c.drawBitmap(logo, null, tmp, logoPaint);
        } else {
            centered(c, comp.toUpperCase(Ui.locale()), cx, top + maxH / 2 + Ui.halfCap(bigDim), bigDim);
        }
    }

    private void liveChip(Canvas c, float x, float y, float h, TextPaint p, boolean small) {
        String label = Ui.tr("AO VIVO", "LIVE", "EN VIVO", "IN DIRETTA");
        float w = p.measureText(label) + 44 * k;
        box.set(x, y, x + w, y + h);
        fill.setColor(0xFFFF3B3B);
        c.drawRoundRect(box, h / 2, h / 2, fill);
        fill.setColor(0xFFFFFFFF);
        fill.setAlpha(0x4D + (int) (0xB3 * (0.5f + 0.5f * Math.sin(System.currentTimeMillis() / 1600.0 * 2 * Math.PI))));
        c.drawCircle(x + 18 * k, y + h / 2, 5 * k, fill);
        fill.setAlpha(255);
        c.drawText(label, x + 32 * k, y + h / 2 + Ui.halfCap(p), p);
        postInvalidateDelayed(80);
    }

    private static void centered(Canvas c, String s, float cx, float baseline, Paint p) {
        c.drawText(s, cx - p.measureText(s) / 2, baseline, p);
    }

    private static List<String> wrap(String text, TextPaint p, float width, int maxLines) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String tryLine = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && p.measureText(tryLine) > width) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(tryLine);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        while (lines.size() > maxLines) {
            String last = lines.remove(lines.size() - 1);
            lines.set(lines.size() - 1, lines.get(lines.size() - 1) + " " + last);
        }
        for (int i = 0; i < lines.size(); i++) {
            lines.set(i, TextUtils.ellipsize(lines.get(i), p, width, TextUtils.TruncateAt.END).toString());
        }
        return lines;
    }

    private String finished() {
        return Ui.tr("Encerrado", "Finished", "Terminado", "Finito");
    }

    private String dayWord(long start) {
        long diff = Math.floorDiv(start - Sports.dayStart(0), 86_400_000L);
        if (diff == 0) return Ui.tr("Hoje", "Today", "Hoy", "Oggi");
        if (diff == 1) return Ui.tr("Amanhã", "Tomorrow", "Mañana", "Domani");
        if (diff == -1) return Ui.tr("Ontem", "Yesterday", "Ayer", "Ieri");
        return cap(dayFormat.format(new Date(start)).replace(".", ""));
    }

    private String countdown(long start, long now) {
        long min = (start - now) / 60_000L;
        if (min < 1) return Ui.tr("começa agora", "starting now", "empieza ya", "sta per iniziare");
        String in = Ui.tr("em ", "in ", "en ", "tra ");
        if (min < 60) return in + min + " min";
        if (min < 24 * 60) return in + min / 60 + " h" + (min % 60 == 0 ? "" : String.format(java.util.Locale.US, " %02d min", min % 60));
        long days = min / (24 * 60);
        return in + days + (days == 1 ? Ui.tr(" dia", " day", " día", " giorno") : Ui.tr(" dias", " days", " días", " giorni"));
    }
}
