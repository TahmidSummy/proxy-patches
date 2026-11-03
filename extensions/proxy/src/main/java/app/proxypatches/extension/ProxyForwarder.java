package app.proxypatches.extension;

import android.app.Application;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Local forwarder that makes the whole app use an upstream HTTP or HTTPS (TLS) proxy,
 * optionally with Basic authentication.
 * <p>
 * Listens on 127.0.0.1:&lt;random&gt;. For every client connection it opens a connection to the
 * upstream proxy (TLS with certificate + hostname verification if {@link ProxyConfig#useTls()}),
 * forwards the client's proxy request with a Proxy-Authorization header added, and pipes bytes.
 * The app's default ProxySelector, the http(s).proxyHost/Port system properties and the WebView
 * proxy override all point at the local listener. There is no direct fallback: if the proxy is
 * unreachable, requests fail instead of leaking.
 */
@SuppressWarnings("unused")
public final class ProxyForwarder {
    private static final String TAG = "ProxyPatch";
    private static final int MAX_HEADER = 64 * 1024;

    private static boolean started;
    private static volatile int localPort = -1;
    private static volatile ProxySelector selector;

    private ProxyForwarder() {}

    /** Called by the patch at the very start of Application.attachBaseContext. Never throws. */
    public static synchronized void start() {
        if (started) return;
        started = true;
        try {
            if (ProxyConfig.host().isEmpty() || ProxyConfig.port() <= 0) {
                Log.e(TAG, "No proxy configured; not starting");
                return;
            }
            final ServerSocket server = new ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"));
            localPort = server.getLocalPort();
            selector = new LocalSelector(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", localPort)));
            apply();
            daemon("proxy-accept", () -> acceptLoop(server));
            daemon("proxy-watch", () -> {
                while (true) {
                    try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
                    try { apply(); } catch (Throwable ignored) {}
                }
            });
            scheduleWebViewOverride();
        } catch (Throwable t) {
            Log.e(TAG, "start failed", t);
        }
    }

    public static int localPort() { return localPort; }

    /** Points Java networking (and Cronet, via the patched ProxyChangeListener) at the forwarder. */
    static void apply() {
        String p = String.valueOf(localPort);
        setIfDifferent("http.proxyHost", "127.0.0.1");
        setIfDifferent("http.proxyPort", p);
        setIfDifferent("https.proxyHost", "127.0.0.1");
        setIfDifferent("https.proxyPort", p);
        setIfDifferent("http.nonProxyHosts", "localhost|127.*|[::1]");
        System.clearProperty("socksProxyHost");
        if (ProxySelector.getDefault() != selector) ProxySelector.setDefault(selector);
    }

    private static void setIfDifferent(String k, String v) {
        if (!v.equals(System.getProperty(k))) System.setProperty(k, v);
    }

    static final class LocalSelector extends ProxySelector {
        private final List<Proxy> viaLocal;

        LocalSelector(Proxy local) { viaLocal = Collections.singletonList(local); }

        @Override
        public List<Proxy> select(URI uri) {
            if (uri == null) throw new IllegalArgumentException("uri == null");
            String host = uri.getHost();
            if (host != null) {
                String h = host.toLowerCase();
                if (h.equals("localhost") || h.startsWith("127.") || h.equals("::1") || h.equals("[::1]")) {
                    return Collections.singletonList(Proxy.NO_PROXY);
                }
            }
            return viaLocal;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {}
    }

    // ---- WebView ---------------------------------------------------------------------------
    // WebView runs its own Chromium (from the WebView APK) that ignores Java proxy settings.
    // Use WebView's proxy-override API (what androidx.webkit.ProxyController calls) through the
    // support-library boundary, so this works whether or not the app bundles androidx.webkit.

    private static void scheduleWebViewOverride() {
        try {
            // Main process only: WebView refuses to share its data directory across processes.
            if (Build.VERSION.SDK_INT < 28) return;
            String proc = Application.getProcessName();
            if (proc == null || proc.indexOf(':') >= 0) return;
            new Handler(Looper.getMainLooper()).post(ProxyForwarder::applyWebViewOverride);
        } catch (Throwable t) {
            Log.w(TAG, "webview schedule failed", t);
        }
    }

    static void applyWebViewOverride() {
        try {
            ClassLoader cl = WebView.getWebViewClassLoader();
            Object factory = Class.forName("org.chromium.support_lib_glue.SupportLibReflectionUtil", true, cl)
                    .getDeclaredMethod("createWebViewProviderFactory").invoke(null);
            Method getPc = Class.forName(
                    "org.chromium.support_lib_boundary.WebViewProviderFactoryBoundaryInterface", false, cl)
                    .getMethod("getProxyController");
            Object controller = ((InvocationHandler) factory).invoke(null, getPc, null);

            Method set4 = null, set5 = null;
            for (Method m : Class.forName(
                    "org.chromium.support_lib_boundary.ProxyControllerBoundaryInterface", false, cl).getMethods()) {
                if (!m.getName().equals("setProxyOverride")) continue;
                if (m.getParameterTypes().length == 4) set4 = m;
                if (m.getParameterTypes().length == 5) set5 = m;
            }
            String[][] rules = {{"*", "http://127.0.0.1:" + localPort}};
            String[] bypass = new String[0];
            Runnable done = () -> Log.i(TAG, "WebView proxy override active on port " + localPort);
            Executor direct = Runnable::run;
            InvocationHandler pc = (InvocationHandler) controller;
            if (set5 != null) pc.invoke(null, set5, new Object[]{rules, bypass, done, direct, Boolean.FALSE});
            else if (set4 != null) pc.invoke(null, set4, new Object[]{rules, bypass, done, direct});
            else Log.w(TAG, "WebView has no setProxyOverride");
        } catch (Throwable t) {
            Throwable c = t;
            while (c instanceof InvocationTargetException && c.getCause() != null) c = c.getCause();
            Log.w(TAG, "WebView proxy override failed: " + c);
        }
    }

    // ---- Forwarding ------------------------------------------------------------------------

    private static void acceptLoop(ServerSocket server) {
        while (true) {
            try {
                final Socket client = server.accept();
                daemon("proxy-conn", () -> handle(client));
            } catch (Throwable t) {
                Log.w(TAG, "accept: " + t);
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
            }
        }
    }

    private static void handle(Socket client) {
        Socket upstream = null;
        try {
            client.setTcpNoDelay(true);
            byte[] head = readHeader(client.getInputStream());
            if (head == null) return;

            String header = new String(head, StandardCharsets.ISO_8859_1);
            boolean isConnect = header.regionMatches(true, 0, "CONNECT ", 0, 8);
            byte[] rewritten = rewrite(header, isConnect).getBytes(StandardCharsets.ISO_8859_1);

            upstream = openUpstream();
            OutputStream uout = upstream.getOutputStream();
            uout.write(rewritten);
            uout.flush();

            final Socket c = client;
            final Socket u = upstream;
            Thread up = daemon("proxy-up", () -> pipe(c, u));
            pipe(u, c);
            up.join(30000);
        } catch (Throwable t) {
            Log.w(TAG, "conn: " + t);
        } finally {
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    static Socket openUpstream() throws IOException {
        String host = ProxyConfig.host();
        int port = ProxyConfig.port();
        Socket raw = new Socket();
        raw.setTcpNoDelay(true);
        raw.connect(new InetSocketAddress(host, port), 15000);
        if (!ProxyConfig.useTls()) return raw;

        SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(raw, host, port, true);
        SSLParameters params = s.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS"); // verify the certificate matches the host
        s.setSSLParameters(params);
        s.setSoTimeout(30000);
        s.startHandshake();
        s.setSoTimeout(0);
        return s;
    }

    /** Reads up to and including the blank line ending the request header. */
    private static byte[] readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int state = 0;
        while (buf.size() < MAX_HEADER) {
            int b = in.read();
            if (b < 0) return null;
            buf.write(b);
            if (b == '\r' && (state == 0 || state == 2)) state++;
            else if (b == '\n' && state == 1) state = 2;
            else if (b == '\n' && state == 3) return buf.toByteArray();
            else state = (b == '\r') ? 1 : 0;
        }
        return null;
    }

    static String rewrite(String header, boolean isConnect) {
        String auth = ProxyConfig.authBase64();
        String[] lines = header.split("\r\n");
        StringBuilder sb = new StringBuilder(header.length() + 128);
        sb.append(lines[0]).append("\r\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase();
            if (lower.startsWith("proxy-authorization:")) continue;
            if (!isConnect && (lower.startsWith("proxy-connection:") || lower.startsWith("connection:"))) continue;
            sb.append(line).append("\r\n");
        }
        if (!auth.isEmpty()) sb.append("Proxy-Authorization: Basic ").append(auth).append("\r\n");
        // Plain-HTTP requests: one request per connection so each one carries auth.
        if (!isConnect) sb.append("Connection: close\r\n");
        sb.append("\r\n");
        return sb.toString();
    }

    private static void pipe(Socket from, Socket to) {
        byte[] buf = new byte[16384];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void closeQuietly(Socket s) {
        if (s != null) try { s.close(); } catch (Throwable ignored) {}
    }
}
