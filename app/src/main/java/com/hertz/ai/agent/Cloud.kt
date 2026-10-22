package com.hertz.ai.agent

import com.hertz.ai.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional "expert" brain in the cloud for questions that are too hard for the on-device model.
 * Works with Claude (Anthropic) or any OpenAI-compatible service, using the user's own API key.
 */
object Cloud {
    const val DEFAULT_CLAUDE_MODEL = "claude-sonnet-5"

    val configured: Boolean get() = Prefs.cloudKey.isNotBlank()

    private const val SYSTEM =
        "You are the expert advisor to Hertz, a phone assistant. Answer accurately and completely but concisely. " +
            "Plain text only, no markdown. If the question asks for writing (an essay, email, code), write it in full."

    fun ask(question: String): String {
        if (!configured) throw IllegalStateException("No cloud brain is set up. Add an API key in Setup > Smarter.")
        return if (Prefs.cloudProvider == "claude") askClaude(question) else askOpenAi(question)
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { String(it.readBytes()) }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("Cloud brain error $code: ${text.take(300)}")
        return JSONObject(text)
    }

    private fun askClaude(question: String): String {
        val body = JSONObject()
            .put("model", Prefs.cloudModel.ifBlank { DEFAULT_CLAUDE_MODEL })
            .put("max_tokens", 2048)
            .put("system", SYSTEM)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", question)))
        val json = post(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to Prefs.cloudKey, "anthropic-version" to "2023-06-01"),
            body,
        )
        val content = json.getJSONArray("content")
        return (0 until content.length()).map { content.getJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString("\n") { it.getString("text") }
            .trim()
    }

    private fun askOpenAi(question: String): String {
        val model = Prefs.cloudModel.ifBlank { throw IllegalStateException("Set a model name for the cloud brain in Setup.") }
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM))
                    .put(JSONObject().put("role", "user").put("content", question))
            )
        val json = post(
            "${Prefs.cloudBaseUrl}/chat/completions",
            mapOf("Authorization" to "Bearer ${Prefs.cloudKey}"),
            body,
        )
        return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
    }
}
