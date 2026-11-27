package com.openband.app.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** An AI service. All of them speak the same "OpenAI-compatible" chat protocol, so one client serves every choice. */
enum class AiProvider(val label: String, val baseUrl: String, val keyPage: String, val privacy: String) {
    GEMINI(
        "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "https://aistudio.google.com/apikey",
        "On the free quota Google may use what is sent to improve its products, and people may review it.",
    ),
    GROQ(
        "Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys",
        "Has a free tier with limits. Read Groq's current data terms before sending health figures.",
    ),
    OPENROUTER(
        "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys",
        "Has free models with a daily limit. Many free models are run by companies that may train on what you send.",
    ),
    CUSTOM(
        "Other", "", "",
        "Any OpenAI-compatible service (paid or your own server). Its own terms apply.",
    ),
}

/** The user's AI choices. API keys are encrypted with a key that never leaves this phone's secure hardware store. */
class AiSettings(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)

    var provider: AiProvider
        get() = runCatching { AiProvider.valueOf(p.getString("provider", "")!!) }.getOrDefault(AiProvider.GEMINI)
        set(v) = p.edit().putString("provider", v.name).apply()
    var customUrl: String
        get() = p.getString("url", "") ?: ""
        set(v) = p.edit().putString("url", v.trim()).apply()
    var language: String
        get() = p.getString("language", "Gujarati") ?: "Gujarati"
        set(v) = p.edit().putString("language", v).apply()
    var includeProfile: Boolean
        get() = p.getBoolean("profile", false)
        set(v) = p.edit().putBoolean("profile", v).apply()

    /** The user agreed to send the summary to this provider (asked again for a different one). */
    var consent: Boolean
        get() = p.getBoolean("consent_${provider.name}_${baseUrl.hashCode()}", false)
        set(v) = p.edit().putBoolean("consent_${provider.name}_${baseUrl.hashCode()}", v).apply()

    var model: String
        get() = p.getString("model_${provider.name}", "") ?: ""
        set(v) = p.edit().putString("model_${provider.name}", v).apply()

    /** True when the user picked a model by hand; otherwise the best working one is chosen for each question. */
    var manualModel: Boolean
        get() = p.getBoolean("manual_model", false)
        set(v) = p.edit().putBoolean("manual_model", v).apply()

    /** Models that answered a test message, best first. Only these are offered and used. */
    var working: List<String>
        get() = p.getString("working_${provider.name}", "")!!.split('\n').filter { it.isNotEmpty() }
        set(v) = p.edit().putString("working_${provider.name}", v.joinToString("\n")).putLong("checked_${provider.name}", System.currentTimeMillis()).apply()
    val checkedAt: Long get() = p.getLong("checked_${provider.name}", 0)

    val baseUrl: String get() = (if (provider == AiProvider.CUSTOM) customUrl else provider.baseUrl).trimEnd('/')

    /** Keys are stored per service (and per address for "Other"), so one service never receives another's key. */
    private fun slot() = "key_${provider.name}" + if (provider == AiProvider.CUSTOM) "_${baseUrl.hashCode()}" else ""

    var apiKey: String
        get() = p.getString(slot(), null)?.let { runCatching { decrypt(it) }.getOrNull() } ?: ""
        set(v) {
            if (v.isBlank()) p.edit().remove(slot()).apply() else p.edit().putString(slot(), encrypt(v.trim())).apply()
        }

    val ready: Boolean get() = apiKey.isNotEmpty() && model.isNotEmpty() && model in working && baseUrl.startsWith("https://")

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("openband_ai", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder("openband_ai", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
            )
        }.generateKey()
    }

    private fun encrypt(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(s: String): String {
        val (iv, data) = s.split(':')
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))) }
        return String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)))
    }
}

class AiException(val code: Int, message: String) : IOException(message)

object AiClient {

    class Turn(val role: String, val text: String)

    private suspend fun call(s: AiSettings, path: String, body: JSONObject?, timeout: Int = 90_000): JSONObject = withContext(Dispatchers.IO) {
        val base = s.baseUrl
        if (!base.startsWith("https://")) throw AiException(0, "The service address must start with https://")
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = timeout
            // never follow a redirect: it could carry the key to another address
            c.instanceFollowRedirects = false
            c.setRequestProperty("Authorization", "Bearer ${s.apiKey}")
            c.setRequestProperty("User-Agent", "OpenBand")
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw AiException(code, explain(code, text))
            JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    private fun explain(code: Int, text: String): String {
        // errors come as {"error":{"message":..}} or, from some services, wrapped in a list
        val detail = runCatching {
            val o = if (text.trimStart().startsWith("[")) JSONArray(text).getJSONObject(0) else JSONObject(text)
            o.getJSONObject("error").getString("message")
        }.getOrDefault("").take(200)
        val plain = when (code) {
            401, 403 -> "The service did not accept the key."
            402 -> "This model needs payment."
            404 -> "This model is not available."
            429 -> "The free limit is used up for now."
            in 500..599 -> "The service is busy or this model is down."
            else -> "The request failed ($code)."
        }
        return if (detail.isEmpty()) plain else "$plain\n$detail"
    }

    private val SKIP = listOf("embed", "tts", "image", "audio", "whisper", "guard", "live", "veo", "imagen", "aqa", "moderation", "lyria", "robotics", "orpheus", "rerank", "ocr", "computer", "deep-research", "customtools")

    /** Chat models this key can see. For OpenRouter only the ones that cost nothing. */
    suspend fun models(s: AiSettings): List<String> {
        val data = call(s, "/models", null).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").removePrefix("models/")
            if (id.isEmpty() || SKIP.any { id.contains(it, ignoreCase = true) }) return@mapNotNull null
            if (o.has("active") && !o.optBoolean("active", true)) return@mapNotNull null
            if (s.provider == AiProvider.OPENROUTER) {
                val price = o.optJSONObject("pricing")
                val free = id.endsWith(":free") || id == "openrouter/free" ||
                    (price != null && price.optString("prompt") == "0" && price.optString("completion") == "0")
                if (!free) return@mapNotNull null
            }
            id
        }.distinct()
    }

    /** Most promising first: current general models, newest version before older, small and preview ones last. */
    fun rank(provider: AiProvider, models: List<String>): List<String> {
        fun version(id: String) = Regex("(\\d+(?:\\.\\d+)?)").find(id)?.value?.toDoubleOrNull() ?: 0.0
        fun score(id: String): Double {
            var n = 0.0
            when (provider) {
                AiProvider.GEMINI -> {
                    if (id.contains("flash")) n += 100
                    if (id.endsWith("-latest")) n += 60
                    if (id.startsWith("gemini")) n += 40 + version(id)
                    if (id.contains("pro")) n -= 80          // normally not part of the free quota
                }
                AiProvider.GROQ -> { if (id.contains("gpt-oss-120b")) n += 100; if (id.contains("70b") || id.contains("llama")) n += 40; if (id.contains("qwen")) n += 30 }
                // a named strong model gives steadier answers than the "any free model" router, which stays as a fallback
                AiProvider.OPENROUTER -> {
                    if (id == "openrouter/free") n += 20
                    if (listOf("llama", "gemma", "gpt-oss", "qwen", "mistral", "deepseek", "nemotron", "glm").any { id.contains(it) }) n += 40
                    if (id.contains("120b")) n += 25 else if (id.contains("70b")) n += 18 else if (id.contains("31b") || id.contains("27b") || id.contains("32b")) n += 10
                    if (id.contains("code") || id.contains("vision") || id.contains("-vl")) n -= 30
                }
                AiProvider.CUSTOM -> n += version(id)
            }
            // small "lite" models give noticeably weaker answers: use them only when nothing bigger answers
            if (id.contains("lite") || id.contains("nano") || id.contains("-8b") || id.contains("mini")) n -= if (id.startsWith("gemini") || provider != AiProvider.GEMINI) 90 else 10
            if (id.contains("preview") || id.contains("exp")) n -= 30
            return n
        }
        return models.sortedByDescending { score(it) }
    }

    private suspend fun send(s: AiSettings, model: String, turns: List<Turn>, timeout: Int): String {
        val messages = JSONArray()
        turns.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val res = call(s, "/chat/completions", JSONObject().put("model", model).put("messages", messages).put("temperature", 0.3), timeout)
        val out = res.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        if (out.isBlank() || out == "null") throw AiException(204, "The model returned an empty answer.")
        return plain(out)
    }

    /**
     * Sends a one-word test to the most promising models and keeps those that answer. No health data is involved.
     * Stops after [want] working ones, [limit] tries, or repeated "limit reached" replies.
     */
    suspend fun findWorking(s: AiSettings, want: Int = 5, limit: Int = 14, progress: (String) -> Unit): List<String> {
        val candidates = rank(s.provider, models(s)).take(limit)
        val ok = ArrayList<String>()
        var limited = 0
        var lastError: AiException? = null
        for ((i, m) in candidates.withIndex()) {
            progress("Testing ${i + 1} of ${candidates.size}: $m (${ok.size} working)")
            try {
                send(s, m, listOf(Turn("user", "Reply with the single word OK.")), 30_000)
                ok += m; limited = 0
            } catch (e: AiException) {
                if (e.code == 401 || e.code == 403 && ok.isEmpty() && i >= 2) throw e
                lastError = e
                if (e.code == 429) limited++ else limited = 0
            } catch (e: IOException) {
                lastError = AiException(0, e.message ?: "No connection")
            }
            if (ok.size >= want || limited >= 3) break
            kotlinx.coroutines.delay(900)
        }
        if (ok.isEmpty()) throw lastError ?: AiException(0, "No model answered.")
        s.working = ok
        if (s.model !in ok || !s.manualModel) s.model = rank(s.provider, ok).first()
        return ok
    }

    /**
     * Answers with the chosen model; if that one is down, limited or gone, moves on to the next working model.
     * Returns the answer and the model that gave it.
     */
    suspend fun chat(s: AiSettings, turns: List<Turn>): Pair<String, String> {
        val ranked = rank(s.provider, s.working)
        val order = (if (s.manualModel) listOf(s.model) + ranked else ranked + s.model).filter { it.isNotEmpty() }.distinct()
        var last: Exception? = null
        for (m in order) {
            try {
                val reply = send(s, m, turns, 60_000)
                if (m != s.model && !s.manualModel) s.model = m
                return reply to m
            } catch (e: AiException) {
                if (e.code == 401) throw e
                last = e
                // a model that has disappeared or become paid is dropped for good
                if (e.code == 404 || e.code == 402) s.working = s.working - m
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: AiException(0, "No working model. Open AI settings and test again.")
    }

    /** The app shows plain text, so Markdown marks are removed. */
    fun plain(t: String): String = t
        .replace(Regex("(?s)<think>.*?</think>"), "")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        .replace("**", "").replace("__", "")
        .replace(Regex("(?m)^(\\s*)[*-]\\s+"), "$1• ")
        .trim()
}
