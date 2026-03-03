package com.jarvis.voiceagent.data

import com.jarvis.voiceagent.network.GeminiApiService
import com.jarvis.voiceagent.network.GeminiContent
import com.jarvis.voiceagent.network.GeminiPart
import com.jarvis.voiceagent.network.GeminiRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class GeminiRepository(private val apiKey: String) {

    private val apiService: GeminiApiService by lazy {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GeminiApiService::class.java)
    }

    suspend fun getAssistantReply(conversationHistory: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Missing Gemini API key. Add GEMINI_API_KEY to local.properties." }

        val systemPrompt = "You are Jarvis, a helpful voice assistant. Keep responses conversational and concise (2-3 sentences max) since they will be spoken aloud. Be friendly and natural."
        val contents = buildList {
            add(GeminiContent(role = "user", parts = listOf(GeminiPart(text = systemPrompt))))
            conversationHistory.forEach { message ->
                val role = if (message.isUser) "user" else "model"
                add(GeminiContent(role = role, parts = listOf(GeminiPart(text = message.text))))
            }
        }

        val response = apiService.generateContent(
            apiKey = apiKey,
            request = GeminiRequest(contents = contents)
        )

        response.candidates
            ?.firstOrNull()
            ?.content
            ?.parts
            ?.joinToString(separator = " ") { it.text }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "I didn't catch that clearly. Could you try again?"
    }
}

data class ChatMessage(
    val text: String,
    val isUser: Boolean
)
