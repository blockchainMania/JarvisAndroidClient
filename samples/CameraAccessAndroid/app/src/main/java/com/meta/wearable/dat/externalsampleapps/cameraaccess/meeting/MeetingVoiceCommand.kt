package com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting

enum class MeetingVoiceCommand {
    START,
    STOP,
}

object MeetingVoiceCommandParser {
    // Public so the "what can I say" screen can list the real trigger phrases instead of a
    // hand-written copy that silently drifts as these are tuned. Matching is done on text with
    // whitespace and punctuation stripped, so these are written without spaces.
    val START_PHRASES = listOf(
        "회의시작", "녹음시작", "회의녹음시작", "회의기록", "회의를기록",
        "이회의를기록", "미팅시작", "미팅녹음", "미팅기록",
    )

    val STOP_PHRASES = listOf(
        "회의녹음끝", "회의녹음종료", "미팅녹음끝", "미팅녹음종료",
        "녹음종료", "녹음끝", "녹음그만", "기록종료", "기록끝",
    )

    /** Any of these next to a meeting/recording word also ends a recording. */
    val STOP_WORDS = listOf("종료", "끝", "중지", "정지", "그만", "멈춰", "마무리", "끝내")

    private val MEETING_WORDS = listOf("회의", "미팅")
    private val RECORDING_WORDS = listOf("녹음", "기록")

    fun parse(text: String): MeetingVoiceCommand? {
        val normalized = text
            .lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[^가-힣a-z0-9]"), "")

        val hasMeetingWord = MEETING_WORDS.any { normalized.contains(it) }
        val hasRecordingWord = RECORDING_WORDS.any { normalized.contains(it) }
        val hasStopWord = STOP_WORDS.any { normalized.contains(it) }

        return when {
            START_PHRASES.any { normalized.contains(it) } -> MeetingVoiceCommand.START
            STOP_PHRASES.any { normalized.contains(it) } ||
                (hasMeetingWord && hasStopWord) ||
                (hasRecordingWord && hasStopWord) -> MeetingVoiceCommand.STOP
            else -> null
        }
    }
}
