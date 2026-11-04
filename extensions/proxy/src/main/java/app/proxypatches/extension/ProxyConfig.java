package app.proxypatches.extension;

/**
 * Starting values from the patch options in Morphe Manager. The "Proxy" patch replaces the body
 * of each method with the user's values. They are only used until the user saves settings in
 * the in-app "Proxy settings" screen (see {@link ProxySettings}).
 */
@SuppressWarnings("unused")
public final class ProxyConfig {
    private ProxyConfig() {}

    /** Proxy hostname or IP address, or empty if not set at patch time. */
    public static String host() {
        return "";
    }

    /** Proxy port. */
    public static int port() {
        return 443;
    }

    /** True if the connection to the proxy itself is TLS ("HTTPS proxy"). */
    public static boolean useTls() {
        return true;
    }

    /** Proxy username, or empty. */
    public static String username() {
        return "";
    }

    /** Proxy password, or empty. */
    public static String password() {
        return "";
    }
}
