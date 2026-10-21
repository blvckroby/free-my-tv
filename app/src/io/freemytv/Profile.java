package io.freemytv;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import java.util.Locale;

/**
 * The Google account signed in on the TV, for the profile button. Android gives apps the account's address, not its
 * photo (that needs Google sign-in), so the button shows the initial on a color picked from the address.
 */
final class Profile {
    private Profile() {}

    static final int[] COLORS = {0xFF4285F4, 0xFFDB4437, 0xFF0F9D58, 0xFFF4B400, 0xFF8E44AD, 0xFFE67E22, 0xFF16A085};

    static volatile String email = "", initial = "";
    static volatile int color = COLORS[0];
    /** The picture from config.properties (profile_photo), as a square SIZE px bitmap, and the paint that draws it round. */
    static final int SIZE = 256;
    static volatile Paint photo;

    /** Reads the account again (cheap; at start and when coming back, as the permission may have just been given). */
    static void refresh(Context c) {
        String found = "";
        if (c.checkSelfPermission(Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED) {
            try {
                Account[] accounts = AccountManager.get(c).getAccountsByType("com.google");
                if (accounts.length > 0) found = accounts[0].name;
            } catch (RuntimeException e) {
                AppLog.w("account: " + e.getMessage());
            }
        }
        if (found.isEmpty()) found = Config.PROFILE_NAME.trim();
        email = found;
        initial = found.isEmpty() ? "" : found.substring(0, 1).toUpperCase(Locale.ROOT);
        color = COLORS[Math.abs(found.toLowerCase(Locale.ROOT).hashCode()) % COLORS.length];
    }

    static boolean granted(Context c) {
        return c.checkSelfPermission(Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Loads the picture from config.properties once (kept in the cache folder), then calls `done` on this thread. */
    static void loadPhoto(Context c, Runnable done) {
        String url = Config.PROFILE_PHOTO.trim();
        if (url.isEmpty() || photo != null) return;
        new Thread(() -> {
            File f = new File(c.getCacheDir(), "profile-" + Integer.toHexString(url.hashCode()) + ".img");
            try {
                if (!f.exists()) {
                    HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
                    h.setConnectTimeout(8000);
                    h.setReadTimeout(15000);
                    try (InputStream in = h.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                        byte[] buffer = new byte[16384];
                        int n;
                        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    } finally {
                        h.disconnect();
                    }
                }
                Bitmap src = BitmapFactory.decodeFile(f.getPath());
                if (src == null) throw new java.io.IOException("unreadable picture");
                int side = Math.min(src.getWidth(), src.getHeight());
                Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
                Bitmap b = Bitmap.createScaledBitmap(square, SIZE, SIZE, true);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                p.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                photo = p;
                done.run();
            } catch (java.io.IOException | RuntimeException e) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                AppLog.w("profile picture: " + e.getMessage());
            }
        }, "profile-photo").start();
    }

    /** Draws the picture as a disc of radius r around (cx, cy). */
    static void drawPhoto(Canvas c, Paint p, float cx, float cy, float r) {
        c.save();
        c.translate(cx - r, cy - r);
        c.scale(2 * r / SIZE, 2 * r / SIZE);
        c.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, p);
        c.restore();
    }
}
