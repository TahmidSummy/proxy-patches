# 🧦 Proxy Patches

Patches that route **all** of an app's traffic through **your own HTTP or HTTPS proxy**, with optional username and password. It's built into the app, so there's no VPN app to keep running and no system proxy to switch on.

Currently supported: **Reddit**.

### How to use these patches

Click the button below, or add this repo URL as a patch source in Morphe Manager: https://github.com/TahmidSummy/proxy-patches

<a href="https://morphe.software/add-source?github=TahmidSummy/proxy-patches">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

Then select the **Proxy** patch for Reddit and fill in its options:

| Option | What to enter |
|---|---|
| **Proxy host** | Hostname or IP of your proxy, for example `proxy.example.com` |
| **Proxy port** | For example `443` for an HTTPS proxy or `8080` for a plain HTTP proxy |
| **HTTPS (TLS) proxy** | **On** if the connection to the proxy itself is encrypted (an "HTTPS proxy", such as Caddy `forward_proxy`). **Off** for a plain HTTP proxy. |
| **Username / Password** | Your proxy login, or leave both empty if it has none |

> ⚠️ The username and password are stored in plain text inside the patched app. Don't share your patched APK.

## 🔍 What the patch does

Inside the app it runs a small local forwarder on `127.0.0.1`. Every network stack in the app is pointed at that forwarder. For each connection, the forwarder connects to your proxy (with the certificate and hostname checked, in TLS mode), adds your `Proxy-Authorization` login, and passes the traffic through.

For Reddit it also closes the gaps where traffic would otherwise skip the proxy:

- **Cronet** (Chromium networking, Reddit's main stack) would switch to direct connections and QUIC whenever Android broadcasts a proxy change. It's patched to keep using the proxy.
- **Cronet's own DNS** (built-in resolver and stale/persisted host cache) is turned off.
- **Google Play Services' Cronet**, used by reCAPTCHA, is disabled, so reCAPTCHA uses the app's own patched Cronet.
- **WebView** gets a proxy override.

There is **no fallback to a direct connection**. If your proxy is down, the app has no network rather than connecting directly.

Tested with Reddit 2026.40.0 on Android 17. While scrolling, playing video, switching Wi-Fi to mobile data, in the background, and with the proxy unreachable, no TCP or QUIC traffic went anywhere except the proxy. The only other traffic is the phone's DNS lookup of the proxy's own hostname.

### Limitations

- **YouTube links** inside Reddit open the YouTube app, which isn't covered.
- **Push notifications** come through Google Play Services, outside the app.
- **Only HTTP CONNECT proxies** (HTTP or HTTPS) are supported, not SOCKS.

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->

<!-- Do not modify this section by hand. The patch list is generated when release.yml creates a new release. -->

#### The patch list is shown here after the first release is created.

<!-- PATCHES_END -->

&nbsp;

## 🛠️ Building

You need:

- JDK 17+;
- the Android SDK (`ANDROID_HOME`, with its licenses accepted);
- a GitHub token with `read:packages`, because Morphe's patcher is hosted on GitHub Packages. Set it as `GITHUB_ACTOR`/`GITHUB_TOKEN`, or as `gpr.user`/`gpr.key` in `~/.gradle/gradle.properties`.

```bash
./gradlew :patches:buildAndroid      # -> patches/build/libs/patches-<version>.mpp
./gradlew generatePatchesList        # -> patches-list.json
```

The forwarder source is in [`extensions/proxy`](extensions/proxy/src/main/java/app/proxypatches/extension), and the patch is in [`patches/.../reddit/proxy`](patches/src/main/kotlin/app/proxypatches/patches/reddit/proxy).

## 📜 License

GPLv3, see [LICENSE](LICENSE). This is an independent project built on the [Morphe patches template](https://github.com/MorpheApp/morphe-patches-template), and isn't affiliated with or endorsed by Morphe. See [NOTICE](NOTICE).
