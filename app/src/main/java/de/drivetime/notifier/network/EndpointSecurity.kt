package de.drivetime.notifier.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One optional credential header per exact configured HTTPS base URL. Never exported or logged. */
data class EndpointHeader(val name: String, val value: String)
object EndpointHeaderPolicy {
    fun valid(name: String, value: String): Boolean = name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,80}")) &&
        name.lowercase() !in setOf("host", "connection", "content-length", "content-type", "transfer-encoding", "cookie", "proxy-authorization", "user-agent", "accept-encoding") &&
        value.isNotBlank() && value.length in 1..4096 && value.all { it.code in 32..126 }
    fun applies(base: String, target: String): Boolean {
        val a = base.toHttpUrl(); val b = target.toHttpUrl()
        return a.isHttps && b.isHttps && a.host == b.host && a.port == b.port &&
            a.username.isEmpty() && a.password.isEmpty() && b.username.isEmpty() && b.password.isEmpty() &&
            (b.encodedPath == a.encodedPath || b.encodedPath.startsWith(a.encodedPath.trimEnd('/') + "/"))
    }
}

object EndpointSecurity {
    private var app: Context? = null
    private val secrets=java.util.Collections.synchronizedSet(mutableSetOf<String>())
    fun redact(value:String):String = synchronized(secrets) { secrets.sortedByDescending { it.length }.fold(value) { text,secret -> text.replace(secret,"[redacted]") } }
    private fun rememberSecret(value:String) { secrets.add(value); value.substringAfter(' ',"").takeIf { it.length>=4 }?.let {secrets.add(it)} }
    fun initialize(context: Context) { app = context.applicationContext }
    private val prefs get() = requireNotNull(app).getSharedPreferences("endpoint_headers", Context.MODE_PRIVATE)
    fun fingerprint(endpoint: String): String = digest(endpoint + "|" + header(endpoint)?.let { it.name + ":" + it.value }.orEmpty() + "|" +
        (if(app==null)0 else prefs.getLong(digest(endpoint)+":revision",0)))
    fun header(endpoint: String): EndpointHeader? {
        if (app == null) return null
        val raw = prefs.getString(digest(endpoint), null) ?: return null
        return runCatching {
            val parts = raw.split(':', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            val json = JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
            EndpointHeader(json.getString("name"), json.getString("value")).also { rememberSecret(it.value) }
        }.getOrNull()
    }
    fun save(endpoint: String, header: EndpointHeader?) {
        require(HttpsEndpoint.normalize(endpoint) == endpoint)
        if (header == null) prefs.edit().remove(digest(endpoint)).apply()
        else {
            require(EndpointHeaderPolicy.valid(header.name, header.value))
            rememberSecret(header.value)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
            val plain = JSONObject().put("name", header.name).put("value", header.value).toString().toByteArray()
            val raw = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP)
            prefs.edit().putString(digest(endpoint), raw).apply()
        }
        prefs.edit().putLong(digest(endpoint)+":revision", prefs.getLong(digest(endpoint)+":revision",0)+1).apply()
        ServiceMonitor.changed()
    }
    private fun key(): SecretKey {
        val alias = "endpoint_header_auth_v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    data class Scope(val endpoint: String)
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val endpoint = request.tag(Scope::class.java)?.endpoint
        val header = endpoint?.takeIf { EndpointHeaderPolicy.applies(it, request.url.toString()) }?.let(::header)
        chain.proceed(if (header == null) request else request.newBuilder().header(header.name, header.value).build())
    }
}
fun OkHttpClient.Builder.endpointSecurity(): OkHttpClient.Builder = addInterceptor(EndpointSecurity.interceptor)
fun okhttp3.Request.Builder.endpointScope(endpoint: String): okhttp3.Request.Builder = tag(EndpointSecurity.Scope::class.java, EndpointSecurity.Scope(endpoint))
fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

/** Persistent health never contains URLs, credentials, response bodies or calendar contents. */
data class ServiceObservation(val valid: Boolean, val durationMillis: Long, val checkedAt: Long)
object ServiceMonitor {
    private var app: Context? = null
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    fun initialize(context: Context) { app = context.applicationContext }
    @Synchronized fun changed() { revision.value++ }
    private val prefs get() = app?.getSharedPreferences("service_observations", Context.MODE_PRIVATE)
    fun endpointId(kind: String, endpoint: String) = kind.lowercase() + ":" + digest(endpoint)
    fun read(id: String, fingerprint: String): ServiceObservation? {
        val p = prefs ?: return null
        if (p.getString("$id:fp", null) != fingerprint) return null
        return ServiceObservation(p.getBoolean("$id:ok", false), p.getLong("$id:ms", 0), p.getLong("$id:at", 0))
    }
    @Synchronized fun invalidate(id:String) { prefs?.edit()?.remove("$id:fp")?.apply(); changed() }
    @Synchronized fun record(id: String, fingerprint: String, valid: Boolean, duration: Long, startedAt: Long = System.currentTimeMillis()) {
        val p = prefs ?: return
        if (p.getLong("$id:at", 0) > startedAt) return
        p.edit().putString("$id:fp", fingerprint).putBoolean("$id:ok", valid)
            .putLong("$id:ms", duration.coerceAtLeast(0)).putLong("$id:at", startedAt).apply()
        changed()
    }
}
