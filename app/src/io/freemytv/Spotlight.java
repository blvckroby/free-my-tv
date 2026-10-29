package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * The title card over the whole screen (like Nuvio): after the trailer has played a moment in its card it grows to full
 * screen, and the title's logo, year, length and age rating, ratings, genres, plot, the "Watch on" button and its
 * networks come in from below one after another on the left. Without a trailer the title's backdrop stays behind,
 * slowly zooming. Two views: `back` (the backdrop, under the trailer) and `front` (shade and text, over it).
 * Drawn by hand like the home screen; measurements in the 1920×1080 space.
 */
final class Spotlight {
    private static final long GROW_MS = 800, SHRINK_MS = 500, ITEM_MS = 550, KEN_BURNS_MS = 26_000;
    private static final float[] DELAY = {150, 270, 360, 440, 520, 600, 680};
    private static final float COL_X = 80, COL_W = 860, COL_BOTTOM = 96;

    final View back, front;
    private Recs.Title title;
    private String name = "", play = "";
    private boolean open, trailerUnder;
    private long animAt, openedAt;
    private float animFrom, animTo;
    private float k = 1;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private TextPaint nameText, facts, cert, tag, value, genres, plot, button, hint, netLabel;
    private StaticLayout plotLayout;
    private Shader shadeLeft, shadeBottom, shadeTop;
    private final RectF r = new RectF();
    /** Each rating source's logo in its brand colors (app/assets/ratings, from Simple Icons), as in the preview. */
    private final java.util.Map<String, Bitmap> rateIcons = new java.util.HashMap<>();
    private final Path triangle = new Path();
    private final android.graphics.Matrix matrix = new android.graphics.Matrix();

    Spotlight(Context c) {
        for (String source : new String[] {"imdb", "tmdb", "trakt", "tomatoes", "popcorn", "metacritic", "letterboxd"}) {
            try (java.io.InputStream in = c.getAssets().open("ratings/" + source + ".png")) {
                Bitmap b = BitmapFactory.decodeStream(in);
                if (b != null) rateIcons.put(source, b);
            } catch (java.io.IOException ignored) {
                // no logo: its colored tag instead
            }
        }
        back = new View(c) {
            @Override protected void onDraw(Canvas canvas) { drawBack(canvas); }
        };
        front = new View(c) {
            @Override protected void onDraw(Canvas canvas) { drawFront(canvas); }
            @Override protected void onSizeChanged(int w, int h, int ow, int oh) { sized(w, h); }
        };
        for (View v : new View[] {back, front}) {
            v.setVisibility(View.INVISIBLE);
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        stroke.setStyle(Paint.Style.STROKE);
    }

    boolean isOpen() {
        return open;
    }

    /** 0 (the trailer in its card, nothing shown) to 1 (full screen), eased. */
    float progress() {
        long dt = SystemClock.uptimeMillis() - animAt;
        float t = Ui.clamp(dt / (float) (animTo > animFrom ? GROW_MS : SHRINK_MS));
        float e = 1 - (float) Math.pow(1 - t, 3);
        return animFrom + (animTo - animFrom) * e;
    }

    private boolean animating() {
        return SystemClock.uptimeMillis() - animAt < Math.max(GROW_MS, SHRINK_MS);
    }

    void open(Recs.Pick pick, Recs.Title t, String app, boolean withTrailer) {
        title = t;
        name = pick.title == null ? "" : pick.title;
        play = app == null || app.isEmpty() ? Ui.tr("Abrir", "Open", "Abrir", "Apri") : Ui.tr("Assistir no ", "Watch on ", "Ver en ", "Guarda su ") + app;
        trailerUnder = withTrailer;
        plotLayout = null;
        animate(1);
        open = true;
        openedAt = SystemClock.uptimeMillis();
        back.setVisibility(View.VISIBLE);
        front.setVisibility(View.VISIBLE);
        refresh();
    }

    void close() {
        if (!open) return;
        open = false;
        animate(0);
        refresh();
    }

    /** The trailer under the card ended or failed: the backdrop shows again. */
    void trailerGone() {
        trailerUnder = false;
        refresh();
    }

    private void animate(float to) {
        animFrom = progress();
        animTo = to;
        animAt = SystemClock.uptimeMillis();
    }

    private void refresh() {
        back.postInvalidateOnAnimation();
        front.postInvalidateOnAnimation();
    }

    private void sized(int w, int h) {
        k = w / 1920f;
        nameText = text(Ui.displaySemibold, 84, 0xFFFFFFFF);
        nameText.setShadowLayer(30 * k, 0, 4 * k, 0x99000000);
        facts = text(Ui.semibold, 25, 0xFFFFFFFF);
        cert = text(Ui.semibold, 19, 0xD9FFFFFF);
        tag = text(Ui.semibold, 15, 0xFF000000);
        tag.setTextAlign(Paint.Align.CENTER);
        value = text(Ui.semibold, 24, 0xFFFFFFFF);
        genres = text(Ui.semibold, 22, 0x9EFFFFFF);
        plot = text(Ui.regular, 27, 0xEBFFFFFF);
        button = text(Ui.semibold, 25, 0xFF111111);
        hint = text(Ui.medium, 21, 0x99FFFFFF);
        netLabel = text(Ui.semibold, 22, 0xFFFFFFFF);
        shadeLeft = new LinearGradient(0, 0, w, 0, new int[] {0xF004060A, 0xD104060A, 0x5904060A, 0x0004060A},
                new float[] {0, 0.3f, 0.55f, 0.72f}, Shader.TileMode.CLAMP);
        shadeBottom = new LinearGradient(0, h, 0, h * 0.65f, 0xBF04060A, 0x0004060A, Shader.TileMode.CLAMP);
        shadeTop = new LinearGradient(0, 0, 0, h * 0.18f, 0x8004060A, 0x0004060A, Shader.TileMode.CLAMP);
        plotLayout = null;
    }

    private TextPaint text(android.graphics.Typeface face, float size, int color) {
        return new TextPaint(Ui.text(face, size * k, color));
    }

    // MARK: - Drawing

    private void drawBack(Canvas c) {
        float p = progress();
        if (p <= 0.001f && !open) {
            back.setVisibility(View.INVISIBLE);
            return;
        }
        int w = back.getWidth(), h = back.getHeight();
        fill.setShader(null);
        fill.setColor(0xFF05070C);
        fill.setAlpha(Math.round(255 * p));
        c.drawRect(0, 0, w, h, fill);
        Bitmap b = title == null ? null : title.backdrop;
        if (b != null) {
            // Center-crop; without a trailer over it, a slow zoom back and forth.
            float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
            float zoom = 1.02f;
            if (!trailerUnder) {
                float t = ((SystemClock.uptimeMillis() - openedAt) % (2 * KEN_BURNS_MS)) / (float) KEN_BURNS_MS;
                float tri = t < 1 ? t : 2 - t;
                zoom += 0.1f * (tri * tri * (3 - 2 * tri));
            }
            matrix.setScale(s * zoom, s * zoom);
            matrix.postTranslate((w - b.getWidth() * s * zoom) / 2, (h - b.getHeight() * s * zoom) / 2);
            image.setAlpha(Math.round(255 * p));
            c.drawBitmap(b, matrix, image);
        }
        if (animating() || (open && !trailerUnder)) back.postInvalidateOnAnimation();
    }

    private void drawFront(Canvas c) {
        float p = progress();
        if ((p <= 0.001f && !open) || nameText == null) {
            if (!open) front.setVisibility(View.INVISIBLE);
            return;
        }
        int w = front.getWidth(), h = front.getHeight();
        int a = Math.round(255 * Ui.clamp(open ? (p - 0.3f) / 0.7f : p));
        fill.setColor(0xFFFFFFFF);
        fill.setAlpha(a);
        fill.setShader(shadeLeft);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(shadeBottom);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(shadeTop);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        float x = COL_X * k, width = COL_W * k;
        Recs.Title t = title;
        if (plotLayout == null && t != null && !t.plot.isEmpty()) {
            plotLayout = StaticLayout.Builder.obtain(t.plot, 0, t.plot.length(), plot, Math.round(width))
                    .setLineSpacing(0, 1.32f).setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).build();
        }
        // Heights from the bottom up, so the column sits on the bottom margin whatever it holds.
        float logoH = 190 * k, factsH = 30 * k, ratesH = t != null && !t.ratings.isEmpty() ? 30 * k : 0;
        float genresH = t != null && !t.genres.isEmpty() ? 27 * k : 0, plotH = plotLayout == null ? 0 : plotLayout.getHeight();
        float actsH = 68 * k, netsH = t != null && !t.nets.isEmpty() ? 56 * k : 0;
        float total = logoH + 30 * k + factsH + (ratesH > 0 ? 22 * k + ratesH : 0) + (genresH > 0 ? 22 * k + genresH : 0)
                + (plotH > 0 ? 18 * k + plotH : 0) + 34 * k + actsH + (netsH > 0 ? 30 * k + netsH : 0);
        float y = h - COL_BOTTOM * k - total;
        long since = SystemClock.uptimeMillis() - openedAt;

        // 1. The logo (bottom left of its box), or the name.
        float e = item(0, since, a);
        Bitmap logo = t == null ? null : t.logo;
        if (logo != null) {
            float s = Math.min(620 * k / logo.getWidth(), logoH / logo.getHeight());
            float lw = logo.getWidth() * s, lh = logo.getHeight() * s;
            r.set(x, y + logoH - lh, x + lw, y + logoH);
            r.offset(0, lift(e));
            image.setAlpha(Math.round(a * e));
            c.drawBitmap(logo, null, r, image);
        } else {
            nameText.setAlpha(Math.round(a * e));
            c.drawText(TextUtils.ellipsize(name, nameText, width, TextUtils.TruncateAt.END).toString(), x, y + logoH - 20 * k + lift(e), nameText);
        }
        y += logoH + 30 * k;

        // 2. Year, length, age rating.
        e = item(1, since, a);
        float fx = x, base = y + factsH - 4 * k + lift(e);
        facts.setAlpha(Math.round(a * e));
        if (t != null) {
            for (String s : new String[] {t.year, t.length}) {
                if (s.isEmpty()) continue;
                c.drawText(s, fx, base, facts);
                fx += facts.measureText(s) + 22 * k;
            }
            if (!t.cert.isEmpty()) {
                float cw = cert.measureText(t.cert) + 20 * k;
                r.set(fx, base - 24 * k, fx + cw, base + 6 * k);
                stroke.setStrokeWidth(2 * k);
                stroke.setColor(0x8CFFFFFF);
                stroke.setAlpha(Math.round(0.55f * a * e));
                c.drawRoundRect(r, 8 * k, 8 * k, stroke);
                cert.setAlpha(Math.round(0.85f * a * e));
                c.drawText(t.cert, fx + 10 * k, base - 2 * k, cert);
            }
        }
        y += factsH;

        // 3. Ratings: each source's logo (or its tag), then the score in its color.
        if (ratesH > 0) {
            y += 22 * k;
            e = item(2, since, a);
            float rx = x, rb = y + 24 * k + lift(e);
            for (String[] rating : t.ratings) {
                int color = rateColor(rating[0]);
                Bitmap icon = rateIcons.get(rating[0]);
                if (icon != null) {         // the logo, 26 px tall (at most 52 wide), then the score
                    float s = Math.min(26 * k / icon.getHeight(), 52 * k / icon.getWidth());
                    float iw = icon.getWidth() * s, ih = icon.getHeight() * s;
                    r.set(rx, rb - 9 * k - ih / 2, rx + iw, rb - 9 * k + ih / 2);
                    image.setAlpha(Math.round(a * e));
                    c.drawBitmap(icon, null, r, image);
                } else {
                    String label = rateLabel(rating[0]);
                    float tw = tag.measureText(label) + 14 * k;
                    r.set(rx, rb - 21 * k, rx + tw, rb + 3 * k);
                    fill.setColor(color);
                    fill.setAlpha(Math.round(a * e));
                    c.drawRoundRect(r, 5 * k, 5 * k, fill);
                    tag.setColor(rateInk(rating[0]));
                    tag.setAlpha(Math.round(a * e));
                    c.drawText(label, r.centerX(), rb - 3 * k, tag);
                }
                value.setColor(color);
                value.setAlpha(Math.round(a * e));
                c.drawText(rating[1], r.right + 9 * k, rb, value);
                rx = r.right + 9 * k + value.measureText(rating[1]) + 22 * k;
            }
            y += ratesH;
        }

        // 4. Genres.
        if (genresH > 0) {
            y += 22 * k;
            e = item(3, since, a);
            genres.setAlpha(Math.round(0.62f * a * e));
            c.drawText(TextUtils.join(" • ", t.genres), x, y + 22 * k + lift(e), genres);
            y += genresH;
        }

        // 5. The plot (up to four lines).
        if (plotH > 0) {
            y += 18 * k;
            e = item(4, since, a);
            plot.setAlpha(Math.round(0.92f * a * e));
            c.save();
            c.translate(x, y + lift(e));
            plotLayout.draw(c);
            c.restore();
            y += plotH;
        }

        // 6. "Watch on …" and the hint.
        y += 34 * k;
        e = item(5, since, a);
        float bw = button.measureText(play) + 96 * k, by = y + lift(e);
        r.set(x, by, x + bw, by + actsH);
        fill.setColor(0xFFFFFFFF);
        fill.setAlpha(Math.round(a * e));
        c.drawRoundRect(r, actsH / 2, actsH / 2, fill);
        float tx = x + 38 * k, cy = by + actsH / 2;
        triangle.reset();
        triangle.moveTo(tx, cy - 11 * k);
        triangle.lineTo(tx + 19 * k, cy);
        triangle.lineTo(tx, cy + 11 * k);
        triangle.close();
        fill.setColor(0xFF111111);
        fill.setAlpha(Math.round(a * e));
        c.drawPath(triangle, fill);
        button.setAlpha(Math.round(a * e));
        c.drawText(play, tx + 34 * k, cy + Ui.halfCap(button), button);
        hint.setAlpha(Math.round(0.6f * a * e));
        c.drawText(Ui.tr("Voltar fecha", "Back closes", "Atrás cierra", "Indietro chiude"), r.right + 22 * k, cy + Ui.halfCap(hint), hint);
        y += actsH;

        // 7. Networks (shows) or the services that stream it (films), on light tiles.
        if (netsH > 0) {
            y += 30 * k;
            e = item(6, since, a);
            float nb = y + lift(e), nx = x;
            netLabel.setAlpha(Math.round(a * e));
            c.drawText(t.netLabel, nx, nb + netsH / 2 + Ui.halfCap(netLabel), netLabel);
            nx += netLabel.measureText(t.netLabel) + 20 * k;
            for (Bitmap n : t.nets) {
                float s = Math.min(130 * k / n.getWidth(), 36 * k / n.getHeight());
                float lw = n.getWidth() * s, lh = n.getHeight() * s, tw = Math.max(96 * k, lw + 32 * k);
                r.set(nx, nb, nx + tw, nb + netsH);
                fill.setColor(0xE0FFFFFF);
                fill.setAlpha(Math.round(0.88f * a * e));
                c.drawRoundRect(r, 12 * k, 12 * k, fill);
                r.set(nx + (tw - lw) / 2, nb + (netsH - lh) / 2, nx + (tw + lw) / 2, nb + (netsH + lh) / 2);
                image.setAlpha(Math.round(a * e));
                c.drawBitmap(n, null, r, image);
                nx += tw + 12 * k;
            }
        }
        if (animating() || (open && since < DELAY[DELAY.length - 1] + ITEM_MS + 100)) front.postInvalidateOnAnimation();
    }

    /** How far item `i` has come in (0 to 1), one after another; all in at once while closing. */
    private float item(int i, long since, int alpha) {
        if (!open) return 1;
        float t = Ui.clamp((since - DELAY[i]) / (float) ITEM_MS);
        return 1 - (float) Math.pow(1 - t, 3);
    }

    /** Coming in from 18 px below. */
    private float lift(float e) {
        return 18 * k * (1 - e);
    }

    private static int rateColor(String source) {
        switch (source) {
            case "imdb": return 0xFFF5C518;
            case "tmdb": return 0xFF01B4E4;
            case "trakt": return 0xFFED1C24;
            case "tomatoes":
            case "popcorn": return 0xFFFA320A;
            case "metacritic": return 0xFFFFCC34;
            case "letterboxd": return 0xFF00E054;
            default: return 0xFFFFFFFF;
        }
    }

    private static int rateInk(String source) {
        return source.equals("trakt") || source.equals("tomatoes") || source.equals("popcorn") ? 0xFFFFFFFF : 0xFF000000;
    }

    private static String rateLabel(String source) {
        switch (source) {
            case "imdb": return "IMDb";
            case "tmdb": return "TMDB";
            case "trakt": return "Trakt";
            case "tomatoes": return "RT";
            case "popcorn": return "Popcorn";
            case "metacritic": return "Metacritic";
            case "letterboxd": return "Letterboxd";
            default: return source;
        }
    }
}
