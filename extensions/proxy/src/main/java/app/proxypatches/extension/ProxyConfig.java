package app.proxypatches.extension;

/**
 * Proxy settings. The "Proxy" patch replaces the body of each method so it returns the
 * values the user entered in Morphe Manager. The defaults below are never used in a patched app.
 */
@SuppressWarnings("unused")
public final class ProxyConfig {
    private ProxyConfig() {}

    /** Proxy hostname or IP address. */
    public static String host() {
        return "";
    }

    /** Proxy port. */
    public static int port() {
        return 0;
    }

    /** True if the connection to the proxy itself is TLS ("HTTPS proxy"). */
    public static boolean useTls() {
        return true;
    }

    /** base64("username:password"), or empty for no authentication. */
    public static String authBase64() {
        return "";
    }
}
