package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

/**
 * Detects utterances that need a fresh camera frame to answer correctly
 * ("what's in front of me", "check again"), so the app can capture and attach
 * an image up front instead of waiting on the model to decide whether to call
 * capture_current_view. Without this, the model sometimes answers the first
 * ambiguous vision question from stale context (hallucinating) and only gets
 * it right once the user explicitly asks it to look again.
 */
object VisionQuestionDetector {
    private val KEYWORDS = listOf(
        "이게뭐",
        "이거뭐",
        "이건뭐",
        "이게뭔지",
        "이거뭔지",
        "지금보이는",
        "지금보고있는",
        "지금보는",
        "앞에있는",
        "뭐가보여",
        "뭐보여",
        "다시확인",
        "다시봐",
        "다시한번봐",
        "다시한번확인",
    )

    fun matches(text: String): Boolean {
        val normalized = text.replace(Regex("\\s+"), "")
        return KEYWORDS.any { normalized.contains(it) }
    }
}
