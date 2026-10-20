package io.freemytv;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.content.pm.PackageManager;

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
        email = found;
        initial = found.isEmpty() ? "" : found.substring(0, 1).toUpperCase(Locale.ROOT);
        color = COLORS[Math.abs(found.toLowerCase(Locale.ROOT).hashCode()) % COLORS.length];
    }

    static boolean granted(Context c) {
        return c.checkSelfPermission(Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED;
    }
}
