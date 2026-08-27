package com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini

import com.meta.wearable.dat.externalsampleapps.cameraaccess.meeting.MeetingVoiceCommandParser
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolDeclarations
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.userFacingToolLabel

/**
 * The list of things a user can actually say, assembled from the code that decides: the registered
 * tool declarations, the meeting-command parser's own phrase lists, the wake word constant, and
 * the confirm/cancel phrases.
 *
 * Deliberately not a separate hand-written page. Tools and keyword lists have been added and
 * retuned repeatedly here, and a help page that lies is worse than none -- so every list on this
 * screen is the same list the runtime matches against. The per-tool examples are the one thing
 * written for humans rather than scraped, but they live inside the tool's own declaration (see
 * ToolDeclarations.decl) so a new tool cannot be added without them being right there.
 */
object VoiceCommandCatalog {

    data class Entry(
        val title: String,
        val examples: List<String>,
        val detail: String? = null,
    )

    data class Section(
        val title: String,
        val note: String? = null,
        val entries: List<Entry>,
    )

    fun sections(): List<Section> = listOf(
        Section(
            title = "부르기",
            note = "세션이 꺼져 있을 때는 먼저 이름을 부르세요.",
            entries = listOf(
                Entry(
                    title = "깨우기",
                    examples = listOf("${WakeWordMonitor.WAKE_WORD}, 이거 뭐야?"),
                    detail = "\"${WakeWordMonitor.WAKE_WORD}\"로 시작하면 바로 이어서 말해도 됩니다.",
                )
            ),
        ),
        Section(
            title = "무엇을 시킬 수 있나",
            note = "각 기능은 도구 선언에 함께 적힌 예시에서 가져옵니다.",
            entries = toolEntries(),
        ),
        Section(
            title = "회의 기록",
            note = "이 명령은 AI를 거치지 않고 말이 감지되는 즉시 실행됩니다.",
            entries = listOf(
                Entry(
                    title = "녹음 시작",
                    examples = MeetingVoiceCommandParser.START_PHRASES.take(4),
                ),
                Entry(
                    title = "녹음 종료",
                    examples = MeetingVoiceCommandParser.STOP_PHRASES.take(4),
                    detail = "\"회의\"나 \"녹음\"에 " +
                        MeetingVoiceCommandParser.STOP_WORDS.take(4).joinToString("·") +
                        " 같은 말을 붙여도 종료됩니다.",
                ),
            ),
        ),
        Section(
            title = "확인 대답",
            note = "저장이나 전화처럼 되돌리기 어려운 일은 실행 전에 한 번 물어봅니다.",
            entries = listOf(
                Entry(title = "승인", examples = GeminiSessionViewModel.CONFIRM_PHRASES.take(8)),
                Entry(title = "취소", examples = GeminiSessionViewModel.CANCEL_PHRASES.take(6)),
            ),
        ),
    )

    /**
     * One entry per exposed tool, using the examples declared next to that tool.
     *
     * An earlier version scraped quoted fragments out of each tool's `description`. That looked
     * drift-proof but produced a help screen that was actively wrong: seven of sixteen tools --
     * including 전화/문자/메일/검색 -- carried no quoted example at all and vanished, while prose
     * fragments like "," leaked in as if they were things to say. Descriptions are written to
     * steer the model, so they are the wrong source. ToolDeclarations.decl() now takes
     * userExamples alongside the tool, which keeps declaration and help text in one place.
     */
    private fun toolEntries(): List<Entry> {
        val declarations = ToolDeclarations.allDeclarationsJSON()
        // Several tools share a user-facing label on purpose (save_memory and save_life_memory
        // are both "기억 저장"), so merge by label -- two identically titled cards would read as
        // a bug rather than as one capability with more ways to ask for it.
        val byLabel = LinkedHashMap<String, MutableList<String>>()
        for (i in 0 until declarations.length()) {
            val name = declarations.getJSONObject(i).optString("name")
            if (name.isBlank() || name in ToolDeclarations.INTERNAL_TOOL_NAMES) continue
            val examples = ToolDeclarations.userExamples(name)
            if (examples.isEmpty()) continue
            byLabel.getOrPut(name.userFacingToolLabel()) { mutableListOf() }.addAll(examples)
        }
        return byLabel.map { (label, examples) -> Entry(label, examples.distinct()) }
    }

}
