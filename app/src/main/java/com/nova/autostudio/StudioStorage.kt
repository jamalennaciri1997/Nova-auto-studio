package com.nova.autostudio

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

internal data class StudioProject(
    val clips: List<StudioClip> = emptyList(),
    val resources: List<StudioClip> = emptyList(),
    val music: StudioClip? = null,
    val reference: ReferenceStyle? = null,
    val portrait: Boolean = true,
    val fps: Int = 30,
    val referenceAudio: Uri? = null,
)

internal class StudioStorage(context: Context) {
    private val preferences = context.getSharedPreferences("studio_project", Context.MODE_PRIVATE)
    private val styles = context.getSharedPreferences("reference_styles", Context.MODE_PRIVATE)

    fun restore(): StudioProject = runCatching {
        val data = JSONObject(preferences.getString("project", "{}") ?: "{}")
        StudioProject(
            data.optJSONArray("clips").objects().map(::decodeClip),
            data.optJSONArray("resources").objects().map(::decodeClip),
            data.optJSONObject("music")?.let(::decodeClip),
            data.optJSONObject("reference")?.let(::decodeStyle),
            data.optBoolean("portrait", true),
            data.optInt("fps", 30),
            data.optString("referenceAudio").takeIf { it.isNotEmpty() }?.let(Uri::parse),
        )
    }.getOrDefault(StudioProject())

    fun save(project: StudioProject) {
        val data = JSONObject()
            .put("clips", JSONArray(project.clips.map(::encodeClip)))
            .put("resources", JSONArray(project.resources.map(::encodeClip)))
            .put("music", project.music?.let(::encodeClip))
            .put("reference", project.reference?.let(::encodeStyle))
            .put("portrait", project.portrait)
            .put("fps", project.fps)
            .put("referenceAudio", project.referenceAudio?.toString())
        preferences.edit().putString("project", data.toString()).apply()
    }

    fun styleNames(): List<String> = styles.all.keys.sorted()

    fun saveStyle(name: String, style: ReferenceStyle) {
        styles.edit().putString(name, encodeStyle(style).toString()).apply()
    }

    fun loadStyle(name: String): ReferenceStyle? =
        styles.getString(name, null)?.let { runCatching { decodeStyle(JSONObject(it)) }.getOrNull() }

    private fun encodeClip(clip: StudioClip) = JSONObject()
        .put("uri", clip.uri.toString()).put("title", clip.title).put("mime", clip.mime)
        .put("duration", clip.durationMs).put("start", clip.startMs).put("end", clip.endMs)
        .put("impact", clip.impact.toDouble()).put("animate", clip.animate)
        .put("glint", clip.glint?.let(::encodeGlint))

    private fun decodeClip(data: JSONObject) = StudioClip(
        Uri.parse(data.getString("uri")), data.getString("title"), data.getString("mime"),
        data.getLong("duration"), data.getLong("start"), data.getLong("end"),
        data.optDouble("impact", 0.0).toFloat(), data.optBoolean("animate"), data.optJSONObject("glint")?.let(::decodeGlint),
    )

    private fun encodeStyle(style: ReferenceStyle) = JSONObject()
        .put("uri", style.source.toString()).put("audio", style.hasAudio).put("portrait", style.portrait)
        .put("shots", JSONArray(style.shots.map { shot ->
            JSONObject().put("duration", shot.durationMs).put("impact", shot.impact.toDouble())
                .put("glint", shot.glint?.let(::encodeGlint))
        }))

    private fun decodeStyle(data: JSONObject) = ReferenceStyle(
        Uri.parse(data.getString("uri")), data.getBoolean("audio"), data.getBoolean("portrait"),
        data.getJSONArray("shots").objects().map {
            ReferenceShot(it.getLong("duration"), it.getDouble("impact").toFloat(),
                it.optJSONObject("glint")?.let(::decodeGlint))
        },
    )

    private fun encodeGlint(glint: Glint) = JSONObject()
        .put("time", glint.timeMs).put("x", glint.x.toDouble()).put("y", glint.y.toDouble())

    private fun decodeGlint(data: JSONObject) =
        Glint(data.getLong("time"), data.getDouble("x").toFloat(), data.getDouble("y").toFloat())

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else List(length()) { getJSONObject(it) }
}
