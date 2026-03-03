package com.jarvis.voiceagent

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.jarvis.voiceagent.data.GeminiRepository
import com.jarvis.voiceagent.databinding.ActivityMainBinding
import com.jarvis.voiceagent.ui.ConversationAdapter
import com.jarvis.voiceagent.viewmodel.MainViewModel
import com.jarvis.voiceagent.viewmodel.MainViewModelFactory
import com.jarvis.voiceagent.viewmodel.VoiceUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechIntent: Intent
    private lateinit var tts: TextToSpeech

    private val conversationAdapter = ConversationAdapter()

    private var isLoopActive = true
    private var isListening = false
    private var speechRestartJob: Job? = null

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(GeminiRepository(BuildConfig.GEMINI_API_KEY))
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startListening()
        } else {
            Toast.makeText(this, "Microphone permission is required for Jarvis.", Toast.LENGTH_LONG).show()
            viewModel.setVoiceState(VoiceUiState.ERROR)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupOrbAnimation()
        setupSpeechRecognizer()
        setupTextToSpeech()
        observeViewModel()

        binding.startButton.setOnClickListener {
            isLoopActive = true
            requestAudioPermissionAndListen()
        }

        binding.stopButton.setOnClickListener {
            isLoopActive = false
            stopListening()
            tts.stop()
            viewModel.setVoiceState(VoiceUiState.IDLE)
        }

        requestAudioPermissionAndListen()
    }

    private fun setupRecyclerView() {
        binding.conversationRecyclerView.apply {
            adapter = conversationAdapter
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = false
            }
        }
    }

    private fun setupOrbAnimation() {
        ObjectAnimator.ofPropertyValuesHolder(
            binding.orbView,
            android.animation.PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.2f),
            android.animation.PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.2f)
        ).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                viewModel.setVoiceState(VoiceUiState.LISTENING)
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                if (!isLoopActive) return

                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_CLIENT -> restartListeningSilently()
                    else -> restartListeningSilently(500)
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val spokenText = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()

                if (spokenText.isNotEmpty()) {
                    viewModel.sendMessage(spokenText)
                } else {
                    restartListeningSilently()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    private fun setupTextToSpeech() {
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setPitch(1.0f)
            tts.setSpeechRate(1.1f)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        if (isLoopActive) {
                            restartListeningSilently(150)
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        if (isLoopActive) {
                            restartListeningSilently(150)
                        }
                    }
                }
            })
        } else {
            Toast.makeText(this, "Text to speech unavailable.", Toast.LENGTH_LONG).show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.messages.collect { messages ->
                conversationAdapter.submitList(messages)
                if (messages.isNotEmpty()) {
                    binding.conversationRecyclerView.scrollToPosition(messages.lastIndex)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.voiceState.collect { state ->
                renderVoiceState(state)
            }
        }

        lifecycleScope.launch {
            viewModel.assistantReplies.collect { reply ->
                viewModel.setVoiceState(VoiceUiState.SPEAKING)
                speakReply(reply)
            }
        }
    }

    private fun renderVoiceState(state: VoiceUiState) {
        val orb = binding.orbView.background as GradientDrawable
        when (state) {
            VoiceUiState.IDLE -> {
                binding.statusText.text = "Idle"
                orb.setColor(ContextCompat.getColor(this, R.color.orb_idle))
            }
            VoiceUiState.LISTENING -> {
                binding.statusText.text = "Listening..."
                orb.setColor(ContextCompat.getColor(this, R.color.orb_listening))
            }
            VoiceUiState.THINKING -> {
                binding.statusText.text = "Thinking..."
                orb.setColor(ContextCompat.getColor(this, R.color.orb_thinking))
            }
            VoiceUiState.SPEAKING -> {
                binding.statusText.text = "Speaking..."
                orb.setColor(ContextCompat.getColor(this, R.color.orb_speaking))
            }
            VoiceUiState.ERROR -> {
                binding.statusText.text = "Error"
                orb.setColor(ContextCompat.getColor(this, R.color.orb_error))
            }
        }
    }

    private fun speakReply(text: String) {
        val utteranceId = UUID.randomUUID().toString()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun requestAudioPermissionAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        if (!isLoopActive || isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition unavailable on this device.", Toast.LENGTH_LONG).show()
            viewModel.setVoiceState(VoiceUiState.ERROR)
            return
        }
        isListening = true
        viewModel.setVoiceState(VoiceUiState.LISTENING)
        speechRecognizer.startListening(speechIntent)
    }

    private fun stopListening() {
        speechRestartJob?.cancel()
        if (isListening) {
            speechRecognizer.stopListening()
            isListening = false
        }
    }

    private fun restartListeningSilently(delayMs: Long = 50) {
        speechRestartJob?.cancel()
        speechRestartJob = lifecycleScope.launch {
            delay(delayMs)
            if (isLoopActive) {
                startListening()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopListening()
    }

    override fun onResume() {
        super.onResume()
        if (isLoopActive) {
            requestAudioPermissionAndListen()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRestartJob?.cancel()
        speechRecognizer.destroy()
        tts.stop()
        tts.shutdown()
    }
}
