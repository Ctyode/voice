package com.example.gendercolorvoice

import android.content.Context
import org.json.JSONObject

data class SvgMappingAnchors(
    val maleX: Float, val maleY: Float,
    val femaleX: Float, val femaleY: Float
)

data class SvgMappingRadii(
    val maleRx: Float, val maleRy: Float,
    val femaleRx: Float, val femaleRy: Float
)

data class PitchNormRange(val minHz: Float, val maxHz: Float)

data class SvgCanvasMapping(val x: Float, val y: Float, val width: Float, val height: Float)

data class SvgMappingInfo(
    val pitchRange: PitchNormRange?,
    val anchors: SvgMappingAnchors?,
    val radii: SvgMappingRadii?,
    val canvas: SvgCanvasMapping?
) {
    companion object {
        fun load(context: Context): SvgMappingInfo? {
            return try {
                val txt = context.assets.open("svg_mapping_info.json").bufferedReader().use { it.readText() }
                val o = JSONObject(txt)

                // pitch normalization (optional)
                val pitch = o.optJSONObject("normalization")?.optJSONObject("pitch_y")
                val pitchRange = pitch?.let {
                    val min = it.optDouble("f0_min_hz", Double.NaN).toFloat()
                    val max = it.optDouble("f0_max_hz", Double.NaN).toFloat()
                    if (min.isFinite() && max.isFinite() && max > min) PitchNormRange(min, max) else null
                }

                // anchors: prefer recommended_centers_for_voice_csv, else anchors_svg_as_is
                fun readAnchors(key: String): SvgMappingAnchors? {
                    val a = o.optJSONObject(key) ?: return null
                    val f = a.optJSONObject("female")?.optJSONObject("norm")
                    val m = a.optJSONObject("male")?.optJSONObject("norm")
                    if (f != null && m != null) {
                        return SvgMappingAnchors(
                            maleX = m.optDouble("x", Double.NaN).toFloat(),
                            maleY = m.optDouble("y", Double.NaN).toFloat(),
                            femaleX = f.optDouble("x", Double.NaN).toFloat(),
                            femaleY = f.optDouble("y", Double.NaN).toFloat()
                        )
                    }
                    return null
                }
                val anchors = readAnchors("recommended_centers_for_voice_csv")
                    ?: readAnchors("anchors_svg_as_is")

                // radii: allow a single normalized rx/ry common for both
                val radiiNorm = o.optJSONObject("ellipses_norm_radii")
                val radii = if (radiiNorm != null) {
                    val rx = radiiNorm.optDouble("rx", Double.NaN).toFloat()
                    val ry = radiiNorm.optDouble("ry", Double.NaN).toFloat()
                    if (rx.isFinite() && ry.isFinite()) SvgMappingRadii(rx, ry, rx, ry) else null
                } else null

                // canvas from svg_space => whole area from (0,0)
                val canvas = o.optJSONObject("svg_space")?.let { s ->
                    val w = s.optDouble("width", 0.0).toFloat()
                    val h = s.optDouble("height", 0.0).toFloat()
                    if (w > 0f && h > 0f) SvgCanvasMapping(0f, 0f, w, h) else null
                }

                SvgMappingInfo(pitchRange, anchors, radii, canvas)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
