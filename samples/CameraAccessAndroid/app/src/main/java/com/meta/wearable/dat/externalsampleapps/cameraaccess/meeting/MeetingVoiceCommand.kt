package com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting

enum class MeetingVoiceCommand {
    START,
    STOP,
}

object MeetingVoiceCommandParser {
    fun parse(text: String): MeetingVoiceCommand? {
        val normalized = text
            .lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[^가-힣a-z0-9]"), "")

        return when {
            normalized.contains("회의시작") ||
                normalized.contains("녹음시작") ||
                normalized.contains("회의녹음시작") -> MeetingVoiceCommand.START
            normalized.contains("회의끝") ||
                normalized.contains("회의종료") ||
                normalized.contains("녹음끝") ||
                normalized.contains("녹음종료") ||
                normalized.contains("회의녹음끝") ||
                normalized.contains("회의녹음종료") -> MeetingVoiceCommand.STOP
            else -> null
        }
    }
}
