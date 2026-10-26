package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
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
 * Every sporting event of one day, by competition: Serie A, Champions, Europa League, Premier League, LaLiga,
 * Bundesliga, Ligue 1, Formula 1, MotoGP and tennis, whichever have something that day. Left and right change the day,
 * up and down move along the events, OK opens the sports app, Back closes. Drawn by hand like the home screen.
 */
final class SportsView extends View {
    private static final class Row {
        String header;           // a competition's name, or null for an event
        Sports.Event event;
        int sport;
    }

    private final Sports sports;
    private final float k;
    private final List<Row> rows = new ArrayList<>();
    private int dayOffset, selected;
    private float scroll, scrollTarget;
    private boolean loading = true;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final PorterDuffColorFilter white = new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
    private final TextPaint title, date, header, time, name, dim, hint;
    private final RectF box = new RectF(), tmp = new RectF();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", java.util.Locale.US);
    private Shader background;
    private boolean pendingLogos;

    SportsView(Context c) {
        super(c);
        sports = Sports.get(c);
        k = c.getResources().getDisplayMetrics().widthPixels / 1920f;
        setFocusable(true);
        title = new TextPaint(Ui.text(Ui.displaySemibold, 70 * k, 0xFFFFFFFF));
        date = new TextPaint(Ui.text(Ui.displayMedium, 40 * k, 0xE6FFFFFF));
        header = new TextPaint(Ui.text(Ui.semibold, 28 * k, 0xCCFFFFFF));
        header.setLetterSpacing(0.12f);
        time = new TextPaint(Ui.numbers(Ui.displayMedium, 36 * k, 0xFFFFFFFF));
        name = new TextPaint(Ui.text(Ui.semibold, 32 * k, 0xFFFFFFFF));
        dim = new TextPaint(Ui.text(Ui.medium, 28 * k, 0x99FFFFFF));
        hint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0x80FFFFFF));
        reload();
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
                Row h = null;
                for (Sports.Event e : list) {
                    if (e.sport != sport) continue;
                    if (h == null) {
                        h = new Row();
                        h.header = e.competition;
                        h.sport = sport;
                        rows.add(h);
                    }
                    Row r = new Row();
                    r.event = e;
                    r.sport = sport;
                    rows.add(r);
                }
            }
        }
        selected = Math.min(selected, Math.max(0, rows.size() - 1));
        if (selected < rows.size() && rows.get(selected).header != null) selected = next(selected, 1);
        scrollTarget = Math.min(scrollTarget, Math.max(0, contentHeight() - viewport()));
        postInvalidateOnAnimation();
    }

    private int next(int from, int dir) {
        for (int i = from + dir; i >= 0 && i < rows.size(); i += dir) if (rows.get(i).header == null) return i;
        return from;
    }

    private float rowTop(int index) {
        float y = 0;
        for (int i = 0; i < index; i++) y += rows.get(i).header != null ? 74 * k : 92 * k;
        return y;
    }

    private float contentHeight() {
        return rowTop(rows.size()) + 40 * k;
    }

    private float viewport() {
        return getHeight() - 250 * k;
    }

    boolean onKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                selected = next(selected, 1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                selected = next(selected, -1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                dayOffset--;
                selected = 0;
                scrollTarget = 0;
                reload();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                dayOffset++;
                selected = 0;
                scrollTarget = 0;
                reload();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                openApp();
                return true;
            default:
                return false;
        }
        // keep the selected row in view
        float top = rowTop(selected), bottom = top + 92 * k;
        if (top < scrollTarget) scrollTarget = Math.max(0, top - 74 * k);
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

    private String dayLabel() {
        String d = Ui.date(day());
        if (dayOffset == 0) return Ui.tr("Hoje · ", "Today · ", "Hoy · ", "Oggi · ") + d;
        if (dayOffset == 1) return Ui.tr("Amanhã · ", "Tomorrow · ", "Mañana · ", "Domani · ") + d;
        if (dayOffset == -1) return Ui.tr("Ontem · ", "Yesterday · ", "Ayer · ", "Ieri · ") + d;
        return d;
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (background == null) background = new LinearGradient(0, 0, 0, h, 0xFF16161C, 0xFF07070A, Shader.TileMode.CLAMP);
        fill.setShader(background);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
        float left = 80 * k, right = w - 80 * k;
        c.drawText(Ui.tr("Esportes", "Sports", "Deportes", "Sport"), left, 130 * k, title);
        String label = "‹  " + dayLabel() + "  ›";
        c.drawText(label, right - date.measureText(label), 124 * k, date);

        pendingLogos = false;
        scroll += (scrollTarget - scroll) * 0.25f;
        boolean moving = Math.abs(scrollTarget - scroll) > 0.5f;
        if (!moving) scroll = scrollTarget;
        float top = 190 * k;
        c.save();
        c.clipRect(0, top, w, h - 60 * k);
        if (rows.isEmpty()) {
            String msg = loading ? Ui.tr("Carregando os eventos…", "Loading the events…", "Cargando los eventos…", "Carico gli eventi…")
                    : Ui.tr("Nenhum evento neste dia.", "No events on this day.", "No hay eventos este día.", "Nessun evento in questo giorno.");
            c.drawText(msg, left, top + 120 * k, dim);
        }
        float y = top - scroll;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            float rh = r.header != null ? 74 * k : 92 * k;
            if (y + rh > top - 10 && y < h) {
                if (r.header != null) drawHeader(c, r, left, y, rh);
                else drawEvent(c, r.event, left, right, y, rh, i == selected);
            }
            y += rh;
        }
        c.restore();
        String opener = sports.opener()[1];
        c.drawText(Ui.tr("◀ ▶ muda o dia   ▲ ▼ escolhe   OK abre ", "◀ ▶ change day   ▲ ▼ choose   OK opens ", "◀ ▶ cambia el día   ▲ ▼ elige   OK abre ",
                "◀ ▶ cambia giorno   ▲ ▼ scegli   OK apre ") + (opener.isEmpty() ? "" : opener), left, h - 22 * k, hint);
        if (moving || loading) postInvalidateOnAnimation();
        else if (pendingLogos) postInvalidateDelayed(700);      // logos still arriving
    }

    private void drawHeader(Canvas c, Row r, float left, float y, float rh) {
        fill.setColor(0xFF000000 | accent(r.sport));
        box.set(left, y + rh - 30 * k, left + 8 * k, y + rh - 4 * k);
        c.drawRoundRect(box, 4 * k, 4 * k, fill);
        c.drawText(r.header.toUpperCase(Ui.locale()), left + 24 * k, y + rh - 10 * k, header);
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

    private void drawEvent(Canvas c, Sports.Event e, float left, float right, float y, float rh, boolean on) {
        if (on) {
            box.set(left - 14 * k, y + 4 * k, right + 14 * k, y + rh - 4 * k);
            fill.setColor(0x2EFFFFFF);
            c.drawRoundRect(box, 20 * k, 20 * k, fill);
        }
        float mid = y + rh / 2 + Ui.halfCap(time);
        c.drawText(clock.format(new Date(e.start)), left + 6 * k, mid, time);
        float x = left + 170 * k, max = right - x;
        if (!e.home.isEmpty() && !e.away.isEmpty()) {
            float half = max / 2 - 40 * k;
            drawSide(c, e.logoHome, e.home, x, y, rh, half, false);
            c.drawText("–", x + half + 40 * k - name.measureText("–") / 2, mid, dim);
            drawSide(c, e.logoAway, e.away, x + half + 80 * k, y, rh, half, false);
        } else {
            String text = e.title + (e.place.isEmpty() ? "" : "  ·  " + e.place);
            c.drawText(TextUtils.ellipsize(text, name, max, TextUtils.TruncateAt.END).toString(), x, mid, name);
        }
    }

    private void drawSide(Canvas c, String logoUrl, String team, float x, float y, float rh, float width, boolean unused) {
        float size = 52 * k, cy = y + rh / 2;
        Bitmap logo = sports.bitmap(logoUrl, team);
        if (logo == null && logoUrl != null && !logoUrl.isEmpty()) pendingLogos = true;
        if (logo != null) {
            float scale = Math.min(size / logo.getWidth(), size / logo.getHeight());
            float lw = logo.getWidth() * scale, lh = logo.getHeight() * scale;
            tmp.set(x + (size - lw) / 2, cy - lh / 2, x + (size + lw) / 2, cy + lh / 2);
            c.drawBitmap(logo, null, tmp, logoPaint);
        }
        c.drawText(TextUtils.ellipsize(team, name, width - size - 16 * k, TextUtils.TruncateAt.END).toString(), x + size + 16 * k,
                cy + Ui.halfCap(name), name);
    }
}
