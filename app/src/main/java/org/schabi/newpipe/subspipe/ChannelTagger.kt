/*
 * SubsPipe: asks an AI model to write topic tags for subscribed channels.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.subspipe

import android.content.Context
import androidx.preference.PreferenceManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns "channel name + about text + recent titles" into topic tags such as
 * "woodworking, carpentry, furniture", so a search for a topic finds every video of a
 * channel about it, even when the titles never say the word.
 *
 * Works with a Google Gemini key (free from aistudio.google.com) or a Claude key from
 * console.anthropic.com; the kind is told apart by how the key starts. Only channel
 * names, about texts and titles are sent, once per channel.
 */
object ChannelTagger {
    const val AI_KEY_PREF = "subspipe_ai_key"

    private const val GEMINI_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent"
    private const val CLAUDE_URL = "https://api.anthropic.com/v1/messages"
    private const val CLAUDE_MODEL = "claude-haiku-5-5"

    enum class Provider(val label: String) { GEMINI("Gemini"), CLAUDE("Claude") }

    data class Channel(val url: String, val name: String, val about: String, val titles: List<String>)

    fun apiKey(context: Context): String? = PreferenceManager
        .getDefaultSharedPreferences(context)
        .getString(AI_KEY_PREF, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun provider(apiKey: String): Provider = if (apiKey.startsWith("sk-ant-")) Provider.CLAUDE else Provider.GEMINI

    /**
     * @return tags for each channel, keyed by [Channel.url]; channels the model skipped
     * are left out
     * @throws IOException with a readable message if the request fails
     */
    fun tag(apiKey: String, channels: List<Channel>): Map<String, List<String>> {
        if (channels.isEmpty()) {
            return emptyMap()
        }
        val reply = when (provider(apiKey)) {
            Provider.GEMINI -> askGemini(apiKey, prompt(channels))
            Provider.CLAUDE -> askClaude(apiKey, prompt(channels))
        }
        val json = try {
            JSONObject(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1))
        } catch (e: Exception) {
            throw IOException("The AI's answer wasn't readable: ${reply.take(120)}")
        }
        val result = HashMap<String, List<String>>()
        channels.forEachIndexed { index, channel ->
            val array = json.optJSONArray((index + 1).toString()) ?: return@forEachIndexed
            val tags = (0 until array.length())
                .map { array.optString(it).trim().lowercase() }
                .filter { it.isNotEmpty() && it.length <= 40 }
                .distinct()
            if (tags.isNotEmpty()) {
                result[channel.url] = tags
            }
        }
        return result
    }

    private fun prompt(channels: List<Channel>): String = buildString {
        append(
            "You label YouTube channels with topic tags for a personal search box. " +
                "For each numbered channel below, give 6 to 12 short lowercase tags: what the " +
                "channel is mainly about, the broader category, the specific subjects it " +
                "covers, and the words someone would type to find it, including common " +
                "synonyms (for example: woodworking, woodwork, carpentry, furniture making). " +
                "Don't use the channel's name as a tag, and avoid empty tags like \"vlog\" " +
                "or \"youtube\" unless that's really all it is. Reply with only JSON in this " +
                "shape: {\"1\": [\"tag\", \"tag\"], \"2\": [\"tag\"]}\n\n"
        )
        channels.forEachIndexed { index, channel ->
            append("Channel ").append(index + 1).append(": ").append(channel.name).append('\n')
            if (channel.about.isNotBlank()) {
                append("About: ").append(channel.about.replace('\n', ' ').take(300)).append('\n')
            }
            if (channel.titles.isNotEmpty()) {
                append("Recent videos: ")
                append(channel.titles.joinToString(" | ") { it.take(80) })
                append('\n')
            }
            append('\n')
        }
    }

    private fun askGemini(apiKey: String, prompt: String): String {
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        val reply = post(GEMINI_URL, mapOf("x-goog-api-key" to apiKey), body)
        return reply.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .getString("text")
    }

    private fun askClaude(apiKey: String, prompt: String): String {
        val body = JSONObject()
            .put("model", CLAUDE_MODEL)
            .put("max_tokens", 4096)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
            )
        val reply = post(
            CLAUDE_URL,
            mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
            body
        )
        val content = reply.getJSONArray("content")
        return (0 until content.length())
            .map { content.getJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString("") { it.optString("text") }
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 90_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("HTTP $code: ${errorMessage(text)}")
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    /** Pulls the "message" out of an API error reply, or falls back to the raw text. */
    private fun errorMessage(text: String): String = try {
        JSONObject(text).optJSONObject("error")?.optString("message")
            ?.takeIf { it.isNotEmpty() } ?: text.take(200)
    } catch (e: Exception) {
        text.take(200)
    }
}
