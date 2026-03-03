package com.jarvis.voiceagent.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.voiceagent.data.ChatMessage
import com.jarvis.voiceagent.data.GeminiRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class VoiceUiState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR
}

class MainViewModel(private val repository: GeminiRepository) : ViewModel() {

    private val conversationHistory = mutableListOf<ChatMessage>()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _voiceState = MutableStateFlow(VoiceUiState.IDLE)
    val voiceState: StateFlow<VoiceUiState> = _voiceState.asStateFlow()

    private val _assistantReplies = MutableSharedFlow<String>()
    val assistantReplies: SharedFlow<String> = _assistantReplies.asSharedFlow()

    fun setVoiceState(state: VoiceUiState) {
        _voiceState.value = state
    }

    fun sendMessage(userText: String) {
        val cleaned = userText.trim()
        if (cleaned.isEmpty()) return

        viewModelScope.launch {
            appendMessage(ChatMessage(cleaned, true))
            _voiceState.value = VoiceUiState.THINKING

            runCatching { repository.getAssistantReply(conversationHistory) }
                .onSuccess { reply ->
                    appendMessage(ChatMessage(reply, false))
                    _assistantReplies.emit(reply)
                }
                .onFailure {
                    _voiceState.value = VoiceUiState.ERROR
                    val fallback = "Sorry, I had trouble connecting. Please try again."
                    appendMessage(ChatMessage(fallback, false))
                    _assistantReplies.emit(fallback)
                }
        }
    }

    private fun appendMessage(message: ChatMessage) {
        conversationHistory.add(message)
        _messages.value = conversationHistory.toList()
    }
}
