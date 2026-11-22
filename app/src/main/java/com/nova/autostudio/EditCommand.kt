package com.nova.autostudio

internal data class EditCommand(
    val durationMs: Long?,
    val portrait: Boolean?,
    val fps: Int?,
    val syncToBeats: Boolean,
    val cutFirst: Boolean,
) {
    companion object {
        fun parse(input: String): EditCommand {
            val text = input.lowercase().map { character ->
                character.digitToIntOrNull()?.digitToChar() ?: character
            }.joinToString("")
            val seconds = Regex("""(\d{1,3})\s*(?:seconds?\b|secs?\b|s\b|ثواني|ثوان|ثانية|تانية)""")
                .find(text)?.groupValues?.get(1)?.toLong()
            require(seconds == null || seconds in 1..180) { "Choose a duration from 1 to 180 seconds" }
            val portrait = when {
                "9:16" in text || "portrait" in text || "vertical" in text || "بالطول" in text -> true
                "16:9" in text || "landscape" in text || "بالعرض" in text -> false
                else -> null
            }
            val fps = Regex("""\b(30|60)\s*fps\b""").find(text)?.groupValues?.get(1)?.toInt()
            val beats = listOf("beat", "إيقاع", "ايقاع", "ريتم").any(text::contains)
            val cut = listOf("trim", "قص", "قطع").any(text::contains) && !beats
            require(seconds != null || portrait != null || fps != null || beats) {
                "Try «دير ليا 15 ثانية بالطول مع الإيقاع» or «قص أول 3 ثواني»"
            }
            require(!cut || seconds != null) { "Specify how many seconds to keep" }
            return EditCommand(seconds?.times(1000), portrait, fps, beats, cut)
        }
    }
}

internal fun planMontage(clips: List<StudioClip>, duration: Long?, beats: List<Long>): List<StudioClip> {
    val total = duration ?: clips.sumOf { it.endMs - it.startMs }
    val available = clips.sumOf { clip ->
        if (clip.mime.startsWith("image/")) total else clip.durationMs - clip.startMs
    }
    require(total <= available) { "Add more media or choose a shorter duration" }

    val plan = mutableListOf<StudioClip>()
    var position = 0L
    clips.forEachIndexed { index, clip ->
        val remaining = total - position
        if (remaining <= 0) return@forEachIndexed
        val capacity = if (clip.mime.startsWith("image/")) total else clip.durationMs - clip.startMs
        val following = clips.drop(index + 1).sumOf {
            if (it.mime.startsWith("image/")) total else it.durationMs - it.startMs
        }
        val maximum = minOf(capacity, remaining)
        val minimum = maxOf(minOf(250L, maximum), remaining - following)
        val target = (remaining / (clips.size - index)).coerceIn(minimum, maximum)
        val length = if (index == clips.lastIndex) {
            remaining
        } else {
            beats.asSequence().map { it - position }.filter { it in minimum..maximum }
                .minByOrNull { kotlin.math.abs(it - target) } ?: target
        }
        plan.add(clip.copy(endMs = clip.startMs + length))
        position += length
    }
    return plan
}
