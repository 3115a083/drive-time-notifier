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
import org.json.JSONArray
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credential headers for an exact configured HTTPS base URL. Never exported or logged. */
data class EndpointHeader(val name: String, val value: String)
object EndpointHeaderPolicy {
    fun valid(name: String, value: String): Boolean = name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,80}")) &&
        name.lowercase() !in setOf("host", "connection", "content-length", "content-type", "transfer-encoding", "cookie", "proxy-authorization", "user-agent", "accept-encoding") &&
        value.isNotBlank() && value.length in 1..4096 && value.all { it.code in 32..126 }
    fun valid(headers: List<EndpointHeader>): Boolean = headers.all { valid(it.name, it.value) } &&
        headers.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size == headers.size
    fun applies(base: String, target: String): Boolean {
        val a = base.toHttpUrl(); val b = target.toHttpUrl()
        return a.isHttps && b.isHttps && a.host == b.host && a.port == b.port &&
            a.username.isEmpty() && a.password.isEmpty() && b.username.isEmpty() && b.password.isEmpty() &&
            (b.encodedPath == a.encodedPath || b.encodedPath.startsWith(a.encodedPath.trimEnd('/') + "/"))
    }
}

object EndpointSecurity {
    private var app: android.app.Application? = null
    private val secrets=java.util.Collections.synchronizedSet(mutableSetOf<String>())
    fun redact(value:String):String = synchronized(secrets) { secrets.sortedByDescending { it.length }.fold(value) { text,secret -> text.replace(secret,"[redacted]") } }
    private fun rememberSecret(value:String) { secrets.add(value); value.substringAfter(' ',"").takeIf { it.length>=4 }?.let {secrets.add(it)} }
    fun initialize(context: Context) { app = context.applicationContext as android.app.Application }
    private val prefs get() = requireNotNull(app).getSharedPreferences("endpoint_headers", Context.MODE_PRIVATE)
    private fun storageKey(endpoint:String)=digest(HttpsEndpoint.normalize(endpoint) ?: endpoint)
    fun configured(endpoint:String)=app!=null && prefs.contains(storageKey(endpoint))
    fun fingerprint(endpoint: String): String = digest(endpoint + "|" + headers(endpoint).orEmpty().joinToString("|") { it.name + ":" + it.value } + "|" +
        (if(app==null)0 else prefs.getLong(storageKey(endpoint)+":revision",0)))
    // Compatibility reader for callers written before multiple headers were supported.
    fun header(endpoint: String): EndpointHeader? = headers(endpoint)?.firstOrNull()
    fun headers(endpoint: String): List<EndpointHeader>? {
        if (app == null) return null
        val raw = prefs.getString(storageKey(endpoint), null) ?: return null
        return runCatching {
            val json = JSONObject(decryptDraft(raw))
            val array = json.optJSONArray("headers")
            val result = if (array == null) listOf(EndpointHeader(json.getString("name"), json.getString("value")))
                else (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    EndpointHeader(item.getString("name"), item.getString("value"))
                }
            require(result.isNotEmpty() && EndpointHeaderPolicy.valid(result))
            result.onEach { rememberSecret(it.value) }
        }.getOrNull()
    }
    fun save(endpoint: String, header: EndpointHeader?) = saveHeaders(endpoint, listOfNotNull(header))
    @Synchronized fun saveHeaders(endpoint: String, headers: List<EndpointHeader>) {
        require(HttpsEndpoint.normalize(endpoint) != null)
        require(EndpointHeaderPolicy.valid(headers))
        if (headers.isEmpty()) prefs.edit().remove(storageKey(endpoint)).apply()
        else {
            headers.forEach { rememberSecret(it.value) }
            val array = JSONArray().apply { headers.forEach { put(JSONObject().put("name", it.name).put("value", it.value)) } }
            prefs.edit().putString(storageKey(endpoint), encryptDraft(JSONObject().put("headers", array).toString())).apply()
        }
        prefs.edit().putLong(storageKey(endpoint)+":revision", prefs.getLong(storageKey(endpoint)+":revision",0)+1).apply()
        ServiceMonitor.changed()
    }
    // Also used for Compose saved-state drafts so credentials never enter a plain Bundle.
    internal fun encryptDraft(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    internal fun decryptDraft(raw: String): String {
        val parts = raw.split(':', limit=2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
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
        if(endpoint!=null && !EndpointHeaderPolicy.applies(endpoint,request.url.toString())) throw java.io.IOException("Endpoint scope mismatch.")
        val headers = endpoint?.let(::headers)
        if(endpoint!=null && configured(endpoint) && headers==null) throw java.io.IOException("Endpoint credentials unavailable. Save authentication again.")
        val builder = request.newBuilder()
        headers.orEmpty().forEach { builder.header(it.name, it.value) }
        chain.proceed(builder.build())
    }
}
fun OkHttpClient.Builder.endpointSecurity(): OkHttpClient.Builder = followRedirects(false).followSslRedirects(false).addInterceptor(EndpointSecurity.interceptor)
fun okhttp3.Request.Builder.endpointScope(endpoint: String): okhttp3.Request.Builder = tag(EndpointSecurity.Scope::class.java, EndpointSecurity.Scope(endpoint))
fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

/** Persistent health never contains URLs, credentials, response bodies or calendar contents. */
data class ServiceObservation(val valid: Boolean, val durationMillis: Long, val checkedAt: Long)
object ServiceMonitor {
    private var app: android.app.Application? = null
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    fun initialize(context: Context) { app = context.applicationContext as android.app.Application }
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
