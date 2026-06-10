package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import com.meta.wearable.dat.externalsampleapps.cameraaccess.settings.SettingsManager

object GeminiConfig {
    const val WEBSOCKET_BASE_URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val MODEL = "models/gemini-3.1-flash-live-preview"

    const val INPUT_AUDIO_SAMPLE_RATE = 16000
    const val OUTPUT_AUDIO_SAMPLE_RATE = 24000
    const val AUDIO_CHANNELS = 1
    const val AUDIO_BITS_PER_SAMPLE = 16

    const val VIDEO_FRAME_INTERVAL_MS = 1000L
    const val VIDEO_JPEG_QUALITY = 50

    val systemInstruction: String
        get() = SettingsManager.geminiSystemPrompt

    val apiKey: String
        get() = SettingsManager.geminiAPIKey

    val openClawHost: String
        get() = SettingsManager.openClawHost

    val openClawPort: Int
        get() = SettingsManager.openClawPort

    val openClawHookToken: String
        get() = SettingsManager.openClawHookToken

    val openClawGatewayToken: String
        get() = SettingsManager.openClawGatewayToken

    // Jarvis Memory API (replaces OpenClaw in this fork)
    val jarvisApiBase: String
        get() = SettingsManager.jarvisApiBase

    val jarvisApiKey: String
        get() = SettingsManager.jarvisApiKey

    val isJarvisConfigured: Boolean
        get() = jarvisApiBase != "http://YOUR_HOST:8000"
                && jarvisApiBase.isNotEmpty()
                && jarvisApiKey != "YOUR_JARVIS_API_KEY"
                && jarvisApiKey.isNotEmpty()

    fun websocketURL(): String? {
        if (apiKey == "YOUR_GEMINI_API_KEY" || apiKey.isEmpty()) return null
        return "$WEBSOCKET_BASE_URL?key=$apiKey"
    }

    val isConfigured: Boolean
        get() = apiKey != "YOUR_GEMINI_API_KEY" && apiKey.isNotEmpty()

    val isOpenClawConfigured: Boolean
        get() = openClawGatewayToken != "YOUR_OPENCLAW_GATEWAY_TOKEN"
                && openClawGatewayToken.isNotEmpty()
                && openClawHost != "http://YOUR_MAC_HOSTNAME.local"
}
