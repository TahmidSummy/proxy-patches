package app.proxypatches.extension;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;

/**
 * Runtime proxy settings, stored in the app's private SharedPreferences ("proxy_patch").
 * Falls back to the patch-time options ({@link ProxyConfig}) until the user saves settings.
 * Read on every new upstream connection, so saved changes apply immediately.
 */
public final class ProxySettings {
    private static final String TAG = "ProxyPatch";
    private static final String PREFS = "proxy_patch";
    private static final String SHORTCUT_ID = "proxy_patch_settings";

    /** Immutable snapshot of the settings. */
    public static final class Values {
        public final String host;
        public final int port;
        public final boolean useTls;
        public final String username;
        public final String password;
        /** "Run without proxy": connect directly instead of through the proxy. */
        public final boolean direct;

        public Values(String host, int port, boolean useTls, String username, String password, boolean direct) {
            this.host = host == null ? "" : host.trim();
            this.port = port;
            this.useTls = useTls;
            this.username = username == null ? "" : username;
            this.password = password == null ? "" : password;
            this.direct = direct;
        }

        public boolean isConfigured() {
            return !host.isEmpty() && port > 0 && port <= 65535;
        }

        /** base64("user:pass"), or empty for no authentication. */
        public String authBase64() {
            if (username.isEmpty() && password.isEmpty()) return "";
            byte[] raw = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
            return Base64.encodeToString(raw, Base64.NO_WRAP);
        }
    }

    @SuppressLint("StaticFieldLeak")
    private static volatile Context appContext;
    private static volatile Values current = defaults();
    private static boolean hintShown;

    private ProxySettings() {}

    static Values defaults() {
        return new Values(ProxyConfig.host(), ProxyConfig.port(), ProxyConfig.useTls(),
                ProxyConfig.username(), ProxyConfig.password(), false);
    }

    /** Loads saved settings. Called once at app start. */
    static void init(Context context) {
        appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        current = load(appContext);
    }

    public static Values get() {
        return current;
    }

    static Values load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean("saved", false)) return defaults();
        return new Values(p.getString("host", ""), p.getInt("port", 443), p.getBoolean("tls", true),
                p.getString("username", ""), p.getString("password", ""), p.getBoolean("direct", false));
    }

    public static void save(Context context, Values v) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("saved", true)
                .putString("host", v.host)
                .putInt("port", v.port)
                .putBoolean("tls", v.useTls)
                .putString("username", v.username)
                .putString("password", v.password)
                .putBoolean("direct", v.direct)
                .commit();
        current = v;
        hintShown = false;
    }

    // ---- Entry points ----------------------------------------------------------------------

    private static Intent settingsIntent(Context context) {
        return new Intent(Intent.ACTION_VIEW)
                .setClassName(context.getPackageName(), ProxySettingsActivity.class.getName());
    }

    /** Adds "Proxy settings" to the app icon's long-press menu (main process only). */
    static void publishShortcut(Context context) {
        try {
            if (Build.VERSION.SDK_INT < 28) return;
            String proc = Application.getProcessName();
            if (proc == null || proc.indexOf(':') >= 0) return;
            ShortcutManager sm = context.getSystemService(ShortcutManager.class);
            if (sm == null) return;
            ShortcutInfo info = new ShortcutInfo.Builder(context, SHORTCUT_ID)
                    .setShortLabel("Proxy settings")
                    .setLongLabel("Proxy settings")
                    .setIcon(Icon.createWithAdaptiveBitmap(iconBitmap()))
                    .setIntent(settingsIntent(context))
                    .setRank(0)
                    .build();
            if (Build.VERSION.SDK_INT >= 30) sm.pushDynamicShortcut(info);
            else sm.addDynamicShortcuts(java.util.Collections.singletonList(info));
        } catch (Throwable t) {
            Log.w(TAG, "shortcut failed: " + t);
        }
    }

    /** Re-publishes the shortcut if the app replaced its dynamic shortcuts (which removes ours). */
    static void ensureShortcut(Context context) {
        try {
            if (Build.VERSION.SDK_INT < 28) return;
            String proc = Application.getProcessName();
            if (proc == null || proc.indexOf(':') >= 0) return;
            ShortcutManager sm = context.getSystemService(ShortcutManager.class);
            if (sm == null) return;
            for (ShortcutInfo s : sm.getDynamicShortcuts()) {
                if (SHORTCUT_ID.equals(s.getId())) return;
            }
            publishShortcut(context);
        } catch (Throwable t) {
            Log.w(TAG, "shortcut check failed: " + t);
        }
    }

    /** Adaptive icon: white "P" on a dark circle-friendly square (108dp canvas). */
    private static Bitmap iconBitmap() {
        int size = 216;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.rgb(0x2B, 0x2F, 0x36));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(size * 0.36f);
        Paint.FontMetrics fm = paint.getFontMetrics();
        c.drawText("P", size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, paint);
        return bmp;
    }

    /** Shows (once) a hint that no proxy is set, since the app is offline until then. */
    static void showNotConfiguredHint() {
        if (hintShown || appContext == null) return;
        hintShown = true;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(appContext,
                        "No proxy set, so the app is offline. Long-press the app icon → Proxy settings " +
                                "to set one, or turn on \"Run without proxy\".",
                        Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
        });
    }
}
