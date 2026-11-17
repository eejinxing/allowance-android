package com.allowance.app.ai

import com.allowance.core.assistant.AiModel
import com.allowance.core.assistant.Intent
import com.allowance.core.assistant.Reader
import com.allowance.core.assistant.Receipt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** Which AI the assistant has: [status] is "off", "loading", "ready" or "error". [model] reads sentences, [vision] reads receipt photos. */
data class AiState(val status: String = "off", val progress: Float = 0f, val text: String = "", val error: String = "", val model: String = "", val vision: String = "") {
    val ready get() = status == "ready"
}

/**
 * The assistant's optional AI: a model served by Ollama on the person's own computer, reached over their home network. The same backend
 * and the same rules as the web app's llm.js. Free by design: no account, no API key, no quota, nothing leaves the person's own devices.
 * The model only READS; amounts and dates come from the words (see [Reader]). If it is off, slow or broken, the built-in rules answer.
 */
class LocalAi(private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(AiState())
    val state = mutable.asStateFlow()
    private val connecting = Mutex()
    private var base = ""

    val ready get() = state.value.ready
    /** True when a model that can read pictures is connected. */
    val canReadReceipts get() = ready && state.value.vision.isNotEmpty()

    // ---------- talking to Ollama ----------
    private class Reply(val code: Int, val body: String) { val ok get() = code in 200..299 }

    private fun call(url: String, body: JsonElement?, timeoutMs: Int): Reply {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = minOf(timeoutMs, 5_000); c.readTimeout = timeoutMs
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Reply(code, text)
        } finally { c.disconnect() }
    }
    private suspend fun post(path: String, body: JsonObject, timeoutMs: Int) = withContext(Dispatchers.IO) { call("$base$path", body, timeoutMs) }

    /** Ollama's own words for what went wrong ({ "error": "…" }), or a plain status line. */
    private fun errorOf(r: Reply): String = runCatching { Json.parseToJsonElement(r.body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: "Ollama answered ${r.code}."

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.mapNotNull { (k, v) ->
        when (v) { null -> null; is JsonElement -> k to v; is String -> k to JsonPrimitive(v); is Number -> k to JsonPrimitive(v); is Boolean -> k to JsonPrimitive(v)
            else -> error("unsupported $v") }
    }.toMap())

    /** The models Ollama has. Throws a readable error when Ollama can't be reached. */
    suspend fun models(url: String): List<AiModel> = withContext(Dispatchers.IO) {
        val r = try { call("${baseOf(url)}/api/tags", null, 4_000) } catch (e: IOException) {
            throw IOException("Can’t reach Ollama at ${baseOf(url)}. Is it running, started with OLLAMA_HOST=0.0.0.0, and is this phone on the same Wi-Fi?")
        }
        if (!r.ok) throw IOException("Ollama answered ${r.code}.")
        Reader.modelsFrom(Json.parseToJsonElement(r.body))
    }

    /**
     * Connects to Ollama and loads the text model into memory first (that can take a minute or two the first time). Returns the models
     * picked, so they can be remembered. Throws with a readable message, which is also kept in [state].
     */
    suspend fun connect(url: String, wantModel: String = "", wantVision: String = ""): AiState = connecting.withLock {
        val b = baseOf(url)
        try {
            mutable.value = AiState("loading", .05f, "Looking for Ollama…")
            val (text, vision) = Reader.pickModels(models(b), wantModel, wantVision)
            if (text == null) throw IOException("Ollama has no text model yet. On your computer run: ollama pull qwen2.5:3b")
            mutable.value = AiState("loading", .2f, "Loading ${text.name} into memory. The first time can take a minute or two…", model = text.name)
            base = b
            val warm = post("/api/generate", obj("model" to text.name, "stream" to false, "keep_alive" to "30m"), 300_000) // an empty prompt just loads the model
            if (!warm.ok) throw IOException("Ollama couldn’t load ${text.name} (${warm.code}).")
            AiState("ready", 1f, model = text.name, vision = vision?.name.orEmpty()).also { mutable.value = it }
        } catch (e: Exception) {
            base = ""
            mutable.value = AiState("error", error = e.message ?: "Couldn’t connect to Ollama.")
            throw e
        }
    }

    /** Lets go of Ollama (the models stay on the computer; Ollama frees the memory by itself). */
    fun disconnect() { base = ""; mutable.value = AiState() }

    /** Asks the model what the sentence means. Null when it isn't ready, is too slow (25 s), or answers something unusable: the rules then answer. */
    suspend fun understand(text: String, today: LocalDate, hints: String = ""): Intent? {
        val model = state.value.model.takeIf { ready } ?: return null
        val body = obj("model" to model, "stream" to false, "keep_alive" to "30m", "think" to false, "format" to Reader.SCHEMA,
            "options" to obj("temperature" to 0, "num_predict" to 120),
            "messages" to JsonArray(listOf(obj("role" to "system", "content" to Reader.systemPrompt(today, hints)), obj("role" to "user", "content" to text))))
        return runCatching {
            val r = post("/api/chat", body, 25_000)
            if (!r.ok) return null
            val content = Json.parseToJsonElement(r.body).jsonObject["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content ?: return null
            Reader.parseReply(content, text, today)
        }.getOrNull()
    }

    private class OutOfMemory(message: String) : IOException(message)

    /** One request to the vision model. [extra] is added to Ollama's options (num_gpu = 0 keeps it off the graphics card). */
    private suspend fun askVision(imageBase64: String, today: LocalDate, extra: Map<String, Int> = emptyMap()): Receipt {
        // keep_alive 0: hand the memory back straight away, so the text model can come back (a laptop GPU can't hold both).
        // num_ctx 3072 is enough for a 1280 px photo plus the question and the answer; the default pushes layers off a small GPU.
        val options = JsonObject(mapOf("temperature" to JsonPrimitive(0), "num_predict" to JsonPrimitive(500), "num_ctx" to JsonPrimitive(3072)) + extra.mapValues { JsonPrimitive(it.value) })
        val r = post("/api/chat", obj("model" to state.value.vision, "stream" to false, "keep_alive" to 0, "think" to false, "format" to Reader.RECEIPT_SCHEMA, "options" to options,
            "messages" to JsonArray(listOf(obj("role" to "user", "content" to Reader.receiptPrompt(today), "images" to JsonArray(listOf(JsonPrimitive(imageBase64))))))), 420_000)
        if (!r.ok) {
            val msg = errorOf(r)
            if (Regex("(?i)memory").containsMatchIn(msg)) throw OutOfMemory("There isn’t enough memory for the vision model. Close programs that use the graphics card (games, video, other AI tools) and try again.")
            throw IOException("Ollama couldn’t read the photo: $msg")
        }
        val form = runCatching { Json.parseToJsonElement(Json.parseToJsonElement(r.body).jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content) }
            .getOrElse { throw IOException("The vision model didn’t return a readable answer. Try again.") }
        return Reader.toReceipt(form, today)
    }

    /** Reads a receipt photo (base64 JPEG) with the vision model on Ollama. It can take a few minutes on a laptop GPU. */
    suspend fun readReceipt(imageBase64: String, today: LocalDate): Receipt {
        check(canReadReceipts) { "No vision model is connected." }
        val text = state.value.model
        // The text model may be holding the graphics memory the vision model needs: let it go first.
        runCatching { post("/api/generate", obj("model" to text, "stream" to false, "keep_alive" to 0), 20_000) }
        try {
            return try { askVision(imageBase64, today) } catch (e: OutOfMemory) { askVision(imageBase64, today, mapOf("num_gpu" to 0)) } // still no room: slower, but on the processor
        } finally {
            scope.launch { runCatching { post("/api/generate", obj("model" to text, "stream" to false, "keep_alive" to "30m"), 300_000) } } // bring the text model back
        }
    }

    companion object {
        /** "192.168.1.5:11434/" → "http://192.168.1.5:11434". */
        fun baseOf(url: String): String {
            val t = url.trim().trimEnd('/')
            return if (Regex("(?i)^https?://").containsMatchIn(t)) t else "http://$t"
        }
    }
}
