package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class KoreanSpeechRecognizer(
    context: Context,
    private val onPartialText: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
    private val onErrorText: (String) -> Unit,
) : SpeechInputController {
    companion object {
        private const val TAG = "KoreanSpeechRecognizer"
        // The recognizer captures zero audio during this gap. Every restart mid-utterance
        // (silence threshold trip, transient error) drops whatever the user says in this window
        // entirely -- which is also why the very start of a fresh utterance is disproportionately
        // likely to be misheard (it often lands right after a restart). Kept small but nonzero:
        // 0ms risks ERROR_RECOGNIZER_BUSY from calling startListening before the previous
        // session has fully torn down.
        private const val RESTART_DELAY_MS = 50L
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile
    private var running = false

    @Volatile
    private var suspended = false

    override fun start() {
        if (running) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onErrorText("Android 음성인식을 사용할 수 없습니다.")
            return
        }
        running = true
        mainHandler.post {
            createRecognizer()
            listen()
        }
    }

    override fun setSuspended(value: Boolean) {
        if (suspended == value) return
        suspended = value
        mainHandler.post {
            if (value) {
                speechRecognizer?.cancel()
            } else if (running) {
                scheduleRestart()
            }
        }
    }

    override fun stop() {
        running = false
        suspended = false
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun createRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    bestResult(partialResults)?.let(onPartialText)
                }

                override fun onResults(results: Bundle?) {
                    bestResult(results)?.let { text ->
                        onFinalText(text)
                    }
                    scheduleRestart()
                }

                override fun onError(error: Int) {
                    Log.d(TAG, "Speech recognizer error=$error")
                    scheduleRestart()
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun listen() {
        if (!running || suspended) return
        val recognizer = speechRecognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREA.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.KOREA.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // Bumped from 2_800/2_000: a mid-sentence "음..." hesitation was long enough to
            // trip the old thresholds, splitting one utterance into two separate recognitions
            // that arrive too far apart for handleRecognizedSpeech's merge window to catch.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4_200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_200L)
        }
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Korean STT: ${e.message}")
            scheduleRestart()
        }
    }

    private fun scheduleRestart() {
        if (!running || suspended) return
        mainHandler.postDelayed({
            if (running && !suspended) {
                listen()
            }
        }, RESTART_DELAY_MS)
    }

    private fun bestResult(bundle: Bundle?): String? {
        val text = bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            .orEmpty()
            .firstOrNull()
            ?.trim()
        return text?.takeIf { it.isNotBlank() }
    }
}
