package com.blaze.agent.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class LLMRouter(
    private val context: Context,
    private val geminiApiKey: String,
    private val bonsaiServerUrl: String = "http://127.0.0.1:8080"
) {
    companion object {
        private const val TAG = "BlazeLLMRouter"
        private const val GEMINI_MODEL = "gemini-2.0-flash"
        private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun complete(prompt: String): String = withContext(Dispatchers.IO) {
        if (isOnline()) { Log.d(TAG, "→ Gemini"); geminiComplete(prompt) }
        else { Log.d(TAG, "→ Bonsai"); bonsaiComplete(prompt) }
    }

    suspend fun completeWithImage(prompt: String, imageBytes: ByteArray): String = withContext(Dispatchers.IO) {
        if (isOnline()) { Log.d(TAG, "→ Gemini Vision"); geminiCompleteWithImage(prompt, imageBytes) }
        else { Log.d(TAG, "→ Bonsai (text-only fallback)"); bonsaiComplete(prompt) }
    }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun geminiComplete(prompt: String): String {
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply { put(JSONObject().apply { put("text", prompt) }) })
                })
            })
            put("generationConfig", JSONObject().apply { put("temperature", 0.3); put("maxOutputTokens", 1024) })
        }
        val request = Request.Builder()
            .url("$GEMINI_BASE_URL/$GEMINI_MODEL:generateContent?key=$geminiApiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return executeRequest(request) { r ->
            JSONObject(r).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        }
    }

    private fun geminiCompleteWithImage(prompt: String, imageBytes: ByteArray): String {
        val b64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("inlineData", JSONObject().apply { put("mimeType", "image/png"); put("data", b64) }) })
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
        }
        val request = Request.Builder()
            .url("$GEMINI_BASE_URL/$GEMINI_MODEL:generateContent?key=$geminiApiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return executeRequest(request) { r ->
            JSONObject(r).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        }
    }

    private fun bonsaiComplete(prompt: String): String {
        val body = JSONObject().apply {
            put("model", "bonsai")
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", "You are Blaze, an AI agent that controls Android phones. Be concise and precise.") })
                put(JSONObject().apply { put("role", "user"); put("content", prompt) })
            })
            put("temperature", 0.3); put("max_tokens", 512); put("stream", false)
        }
        val request = Request.Builder()
            .url("$bonsaiServerUrl/v1/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return executeRequest(request) { r ->
            JSONObject(r).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }
    }

    private fun executeRequest(request: Request, parse: (String) -> String): String {
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.body?.string()}")
                parse(response.body?.string() ?: throw IOException("Empty response"))
            }
        } catch (e: Exception) { Log.e(TAG, "LLM request failed", e); throw e }
    }
}
