package app.proxypatches.patches.reddit.proxy

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.stringOption
import app.proxypatches.patches.shared.Constants.COMPATIBILITY_REDDIT
import java.util.Base64

private const val EXTENSION_FORWARDER = "Lapp/proxypatches/extension/ProxyForwarder;"
private const val EXTENSION_CONFIG = "Lapp/proxypatches/extension/ProxyConfig;"

/** Hostname, IPv4 or bracket-less IPv6. Restricted so it can be embedded in smali safely. */
private val HOST_REGEX = Regex("^[A-Za-z0-9.:\\-]{1,253}$")

@Suppress("unused")
val proxyPatch = bytecodePatch(
    name = "Proxy",
    description = "Routes all of the app's traffic through your HTTP or HTTPS proxy, " +
        "with optional username and password. Nothing goes direct, even if the proxy is down.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    extendWith("extensions/proxy.mpe")

    val proxyHost by stringOption(
        key = "proxyHost",
        default = null,
        title = "Proxy host",
        description = "Hostname or IP address of the proxy, for example proxy.example.com.",
        required = true,
    ) { it != null && HOST_REGEX.matches(it.trim()) }

    val proxyPort by intOption(
        key = "proxyPort",
        default = 443,
        title = "Proxy port",
        description = "Port of the proxy, for example 443 for an HTTPS proxy or 8080 for a plain HTTP proxy.",
        required = true,
    ) { it != null && it in 1..65535 }

    val useTls by booleanOption(
        key = "useTls",
        default = true,
        title = "HTTPS (TLS) proxy",
        description = "On: the connection to the proxy itself is encrypted (an \"HTTPS proxy\", " +
            "such as Caddy forward_proxy). Off: a plain HTTP proxy.",
        required = true,
    )

    val username by stringOption(
        key = "username",
        default = "",
        title = "Username",
        description = "Proxy username. Leave empty if the proxy has no login. " +
            "Stored in plain text inside the patched app, so don't share the patched APK.",
    )

    val password by stringOption(
        key = "password",
        default = "",
        title = "Password",
        description = "Proxy password. Leave empty if the proxy has no login.",
    )

    execute {
        // ---- 1. Fill in the user's settings -------------------------------------------------
        val host = proxyHost!!.trim()
        if (!HOST_REGEX.matches(host)) throw PatchException("Invalid proxy host: $host")
        val port = proxyPort!!
        val user = username.orEmpty()
        val pass = password.orEmpty()
        val auth = if (user.isEmpty() && pass.isEmpty()) "" else
            Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

        val config = mutableClassDefBy(EXTENSION_CONFIG)
        fun body(name: String, smali: String) =
            config.methods.first { it.name == name }.addInstructions(0, smali)

        body("host", "const-string v0, \"$host\"\nreturn-object v0")
        body("port", "const v0, $port\nreturn v0")
        body("useTls", "const/4 v0, ${if (useTls != false) 1 else 0}\nreturn v0")
        body("authBase64", "const-string v0, \"$auth\"\nreturn-object v0")

        // ---- 2. Start the forwarder before anything else in the app ---------------------------
        ApplicationAttachBaseContextFingerprint.method.addInstruction(
            0,
            "invoke-static {}, $EXTENSION_FORWARDER->start()V",
        )

        // ---- 3. Cronet: re-read the (Java) proxy properties instead of Android's system proxy --
        CronetProxySettingsChangedFingerprint.method.addInstruction(0, "const/4 p1, 0x0")

        // ---- 4. Cronet: no DNS lookups of its own --------------------------------------------
        mutableClassDefByOrNull("Lorg/chromium/net/DnsOptions\$Builder;")?.methods
            ?.filter {
                it.name in setOf(
                    "useBuiltInDnsResolver",
                    "enableStaleDns",
                    "persistHostCache",
                    "preestablishConnectionsToStaleDnsResults",
                ) && it.parameterTypes.size == 1 && it.parameterTypes[0] == "Z"
            }
            ?.forEach { it.addInstruction(0, "const/4 p1, 0x0") }

        // ---- 5. Play Services Cronet (reCAPTCHA): report unavailable -> app's own Cronet ------
        PlayServicesCronetProviderIsEnabledFingerprint.methodOrNull?.addInstructions(
            0,
            "const/4 v0, 0x0\nreturn v0",
        )
    }
}
