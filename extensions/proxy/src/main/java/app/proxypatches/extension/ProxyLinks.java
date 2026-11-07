package app.proxypatches.extension;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Browser for Morphe's "Open links externally" (Proxy settings → Browser for external links).
 * Morphe opens links in the system default browser; if the user picked another browser here,
 * the link goes to that one instead. The picked browser uses its own connection, not this app's
 * proxy.
 * <p>
 * Hooked (by the patch) just before Morphe's OpenLinksExternallyPatch starts the browser.
 */
public final class ProxyLinks {
    private static final String TAG = "ProxyPatch";
    static final String MORPHE_OPEN_EXTERNALLY = "app.morphe.extension.reddit.patches.OpenLinksExternallyPatch";

    private ProxyLinks() {}

    /** A browser installed on the phone. */
    public static final class Browser {
        public final String packageName;
        public final String label;

        Browser(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    /**
     * Called (by the patch) just before Morphe's "Open links externally" starts the browser.
     * Sends the link to the picked browser, if one is picked and still installed; otherwise the
     * system default opens it, as before.
     */
    @SuppressWarnings("unused")
    public static void redirectBrowserIntent(Intent intent) {
        try {
            if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
            Uri data = intent.getData();
            if (data == null || !isWeb(data) || intent.getPackage() != null || intent.getComponent() != null) return;
            String pkg = ProxySettings.linkBrowser();
            Context ctx = ProxySettings.context();
            if (pkg.isEmpty() || ctx == null || !isInstalled(ctx, pkg)) return;
            intent.setPackage(pkg);
            Log.i(TAG, "external link sent to " + pkg);
        } catch (Throwable t) {
            Log.w(TAG, "redirectBrowserIntent failed: " + t);
        }
    }

    /** True if Morphe's "Open links externally" patch is in this app. */
    static boolean morpheOpenExternallyIncluded() {
        try {
            Class.forName(MORPHE_OPEN_EXTERNALLY);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Installed apps that can open https links, sorted by name (this app excluded). */
    public static List<Browser> installedBrowsers(Context context) {
        List<Browser> out = new ArrayList<>();
        try {
            PackageManager pm = context.getPackageManager();
            Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                    .addCategory(Intent.CATEGORY_BROWSABLE);
            List<ResolveInfo> found = pm.queryIntentActivities(probe, PackageManager.MATCH_ALL);
            List<String> seen = new ArrayList<>();
            for (ResolveInfo r : found) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(context.getPackageName()) || seen.contains(pkg)) continue;
                seen.add(pkg);
                out.add(new Browser(pkg, String.valueOf(r.loadLabel(pm))));
            }
            Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        } catch (Throwable t) {
            Log.w(TAG, "listing browsers failed: " + t);
        }
        return out;
    }

    static boolean isInstalled(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Label of an installed app, or the package name if unknown. */
    static String labelOf(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable t) {
            return pkg;
        }
    }

    private static boolean isWeb(Uri uri) {
        String s = uri.getScheme();
        return s != null && (s.equalsIgnoreCase("http") || s.equalsIgnoreCase("https"));
    }
}
