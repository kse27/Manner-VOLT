package com.mannervolt.vehicle

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 운전 중 음성 안내 (FR-13.2). Pleos(Gleo AI) TTS를 쓴다. Pleos 차량과 에뮬레이터에는 표준 TextToSpeech 엔진이 없다.
 * 초기화에 실패해도 앱은 동작하고, 마지막 안내 문장은 화면에 함께 보여 준다.
 */
class VoiceGuide(context: Context) {
    private val tts = runCatching { TextToSpeech(context.applicationContext, Mode.ON_DEVICE) }.getOrNull()
    @Volatile private var ready = false
    private val _last = MutableStateFlow<String?>(null)
    /** 마지막으로 읽은 문장 (화면의 음성 표시용) */
    val last: StateFlow<String?> = _last.asStateFlow()

    init {
        runCatching {
            tts?.initialize(object : OnInitializeListener {
                override fun onSuccess() { ready = true }
                override fun onFailure(p0: ErrorType) { Log.w(TAG, "TTS init failed: $p0") }
            })
            tts?.addEventListener(object : EventListener {
                override fun onStart() {}
                override fun onReady() {}
                override fun onDone() {}
                override fun onError(p0: ErrorType) { Log.w(TAG, "TTS error: $p0") }
                override fun onStop() {}
                override fun onUpdatedRms(p0: Double) {}
            })
        }.onFailure { Log.w(TAG, "TTS unavailable: ${it.javaClass.simpleName}") }
    }

    fun speak(text: String) {
        _last.value = text
        if (!ready) return
        runCatching {
            tts?.setLocale(Locale.KOREA) // ko-KR (국가 코드가 없으면 엔진이 거부한다)
            tts?.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            tts?.speak(text, true)
        }.onFailure { Log.w(TAG, "TTS speak failed: ${it.javaClass.simpleName}") }
    }

    fun clear() { _last.value = null }

    fun release() { runCatching { tts?.stop(); tts?.release() } }

    private companion object { const val TAG = "MannerVoltVoice" }
}
