package com.example.gendercolorvoice

import android.content.Context
import org.json.JSONObject

data class BandsCfg(val splitHz: Float, val scMaxHz: Float)
data class F0ZoneCfg(val lowMaxHz: Float, val highMinHz: Float)
data class ZonesCfg(
    val bias: Float,
    val maleMax: Float,
    val androLow: Float,
    val androHigh: Float,
    val femaleMin: Float,
    val gradientClip: Float,
    val hysteresisWindows: Int
)
data class ScoreCfg(val bias: Float, val gradientClip: Float, val hysteresisWindows: Int)
data class ZonesDiagonalCfg(
    val yNormF0MinHz: Float,
    val yNormF0MaxHz: Float,
    val maleBase: Float,
    val maleSlope: Float,
    val androHighBase: Float,
    val androHighSlope: Float,
    val useMaleAsAndroLow: Boolean
)
data class UiLinesCfg(
    val bias: Float?,
    val maleBase: Float,
    val maleSlope: Float,
    val androHighBase: Float,
    val androHighSlope: Float
)
data class RulesFemaleCfg(
    val deltaFMin: Float, val vtlMax: Float, val f2Min: Float, val h1h2Min: Float,
    val ehfLfMin: Float, val scMin: Float, val prosodyMin: Float,
    val cppMax: Float, val hnrMax: Float, val needTrueAtLeast: Int
)
data class RulesMaleCfg(
    val deltaFMax: Float, val vtlMin: Float, val f2Max: Float, val h1h2Max: Float,
    val ehfLfMax: Float, val scMax: Float, val prosodyMax: Float,
    val cppMin: Float, val hnrMin: Float, val needTrueAtLeast: Int
)

data class MFBasic(
    val F0mean: Float, val F0range: Pair<Float, Float>,
    val VTLmean: Float, val VTLrange: Pair<Float, Float>,
    val F1: Float, val F2: Float, val F3: Float, val F4: Float,
    val deltaF: Float, val H1H2: Float, val HNR: Float, val CPP: Float,
    val SC: Float, val E_HF_LF: Float
)
data class AcousticMeans(val male: MFBasic, val female: MFBasic, val ratio: Map<String, Float>)

data class FormantSet(val F1: Float, val F2: Float, val F3: Float)
data class VowelFormants(val male: FormantSet, val female: FormantSet)

data class DerivedStats(
    val expectedVtlDiffPercent: Float,
    val expectedFormantShiftPercent: Float,
    val expectedF0ratio: Float,
    val expectedH1H2diffDb: Float,
    val expectedHNRdiffDb: Float
)

// Optional SVG layout metadata to align the marker with a background SVG
data class SvgCanvas(val x: Float, val y: Float, val width: Float, val height: Float)
data class SvgAxesPitch(val x: Float, val y1: Float, val y2: Float)
data class SvgAxesResonance(val y: Float, val x1: Float, val x2: Float)
data class SvgAxes(val pitch: SvgAxesPitch, val resonance: SvgAxesResonance)
data class SvgLayout(val canvas: SvgCanvas, val axes: SvgAxes)

// Resonance X mapping from brightness components
data class ResWeights(
    val hfLf: Float, val sc: Float, val vtlInv: Float, val deltaF: Float, val h1h2: Float
)
data class ResRanges(
    val hfLfMin: Float, val hfLfMax: Float,
    val scMinHz: Float, val scMaxHz: Float,
    val vtlMinCm: Float, val vtlMaxCm: Float,
    val deltaFMinHz: Float, val deltaFMaxHz: Float,
    val h1h2MinDb: Float, val h1h2MaxDb: Float
)
data class ResonanceAxisCfg(
    val useBrightnessForX: Boolean,
    val weights: ResWeights,
    val ranges: ResRanges,
    val hfLfLog10: Boolean = false,
    val overrides: ResOverrides? = null,
    val gainX: Float = 1.0f,
    val nonlinear: NonlinearCfg? = null
)

data class ResDarkGate(val hfLfMax: Float, val scMaxHz: Float, val xMax: Float)
data class ResOverrides(val darkGate: ResDarkGate?)

// Optional nonlinear shaping per component
data class NonlinSpec(val type: String, val k: Float, val mid: Float)
data class NonlinearCfg(
    val hfLfLeft: NonlinSpec?,
    val scLeft: NonlinSpec?,
    val h1h2Left: NonlinSpec?,
    val vtlRight: NonlinSpec?,
    val dFRight: NonlinSpec?
)

    data class AppConfig(
    val bands: BandsCfg,
    val f0: F0ZoneCfg,
    val zones: ZonesCfg,
    val score: ScoreCfg?,
    val zonesDiag: ZonesDiagonalCfg?,
    val uiLines: UiLinesCfg?,
    val female: RulesFemaleCfg,
    val male: RulesMaleCfg,
    val acousticMeans: AcousticMeans? = null,
    val vowelFormants: Map<String, VowelFormants>? = null,
    val derived: DerivedStats? = null,
    val svg: SvgLayout? = null,
    val resAxis: ResonanceAxisCfg? = null
) {
    companion object {
        fun load(context: Context): AppConfig {
            return try {
                val txt = context.assets.open("config.json").bufferedReader().use { it.readText() }
                parse(JSONObject(txt))
            } catch (_: Throwable) {
                // Safe defaults matching current code behavior
                AppConfig(
                    bands = BandsCfg(1800f, 5000f),
                    f0 = F0ZoneCfg(165f, 180f),
                    zones = ZonesCfg(0.05f, -0.2f, -0.2f, 0.08f, 0.08f, 0.35f, 3),
                    score = ScoreCfg(0.0f, 0.35f, 3),
                    zonesDiag = null,
                    uiLines = null,
                    female = RulesFemaleCfg(1000f, 16.5f, 1700f, 8f, 1.1f, 1800f, 10f, 12f, 18f, 5),
                    male = RulesMaleCfg(850f, 18f, 1500f, 5f, 0.7f, 1500f, 8f, 12f, 20f, 5),
                    acousticMeans = null,
                    vowelFormants = null,
                    derived = null
                )
            }
        }

        private fun parse(obj: JSONObject): AppConfig {
            fun bands(): BandsCfg { val o = obj.getJSONObject("bands");
                return BandsCfg(o.getDouble("split_hz").toFloat(), o.getDouble("sc_max_hz").toFloat()) }
            fun f0(): F0ZoneCfg { val o = obj.getJSONObject("f0_zone");
                return F0ZoneCfg(o.getDouble("low_max_hz").toFloat(), o.getDouble("high_min_hz").toFloat()) }
            fun zones(): ZonesCfg {
                return if (obj.has("zones")) {
                    val o = obj.getJSONObject("zones");
                    ZonesCfg(
                        o.getDouble("bias").toFloat(),
                        o.getDouble("male_max").toFloat(),
                        o.getDouble("androgynous_low").toFloat(),
                        o.getDouble("androgynous_high").toFloat(),
                        o.getDouble("female_min").toFloat(),
                        o.getDouble("gradient_clip").toFloat(),
                        o.getInt("hysteresis_windows")
                    )
                } else ZonesCfg(0f, -0.2f, -0.2f, 0.08f, 0.08f, 0.35f, 3)
            }
            fun score(): ScoreCfg? = if (obj.has("score")) {
                val o = obj.getJSONObject("score");
                ScoreCfg(o.getDouble("bias").toFloat(), o.getDouble("gradient_clip").toFloat(), o.getInt("hysteresis_windows"))
            } else null
            fun zonesDiag(): ZonesDiagonalCfg? = if (obj.has("zones_diagonal")) {
                val o = obj.getJSONObject("zones_diagonal");
                ZonesDiagonalCfg(
                    o.getDouble("y_norm_f0_min_hz").toFloat(),
                    o.getDouble("y_norm_f0_max_hz").toFloat(),
                    o.getDouble("male_max_base").toFloat(),
                    o.getDouble("male_max_slope").toFloat(),
                    o.getDouble("andro_high_base").toFloat(),
                    o.getDouble("andro_high_slope").toFloat(),
                    o.optBoolean("use_male_as_andro_low", true)
                )
            } else null

            fun uiLines(): UiLinesCfg? = if (obj.has("ui_lines")) {
                val o = obj.getJSONObject("ui_lines")
                UiLinesCfg(
                    bias = if (o.has("bias")) o.getDouble("bias").toFloat() else null,
                    maleBase = o.getDouble("male_base").toFloat(),
                    maleSlope = o.getDouble("male_slope").toFloat(),
                    androHighBase = o.getDouble("andro_high_base").toFloat(),
                    androHighSlope = o.getDouble("andro_high_slope").toFloat()
                )
            } else null

            fun means(): AcousticMeans? = if (obj.has("acoustic_means")) {
                val o = obj.getJSONObject("acoustic_means")
                fun mf(s: String): MFBasic {
                    val m = o.getJSONObject(s)
                    fun pair(a: String): Pair<Float, Float> { val arr = m.getJSONArray(a); return arr.getDouble(0).toFloat() to arr.getDouble(1).toFloat() }
                    return MFBasic(
                        F0mean = m.getDouble("F0_mean_Hz").toFloat(), F0range = pair("F0_range_Hz"),
                        VTLmean = m.getDouble("VTL_mean_cm").toFloat(), VTLrange = pair("VTL_range_cm"),
                        F1 = m.getDouble("F1_mean_Hz").toFloat(), F2 = m.getDouble("F2_mean_Hz").toFloat(), F3 = m.getDouble("F3_mean_Hz").toFloat(), F4 = m.getDouble("F4_mean_Hz").toFloat(),
                        deltaF = m.getDouble("deltaF_mean_Hz").toFloat(), H1H2 = m.getDouble("H1_H2_mean_dB").toFloat(),
                        HNR = m.getDouble("HNR_mean_dB").toFloat(), CPP = m.getDouble("CPP_mean_dB").toFloat(),
                        SC = m.getDouble("SC_mean_Hz").toFloat(), E_HF_LF = m.getDouble("E_HF_LF_ratio").toFloat()
                    )
                }
                val ratio = if (o.has("ratio_female_to_male")) {
                    val r = o.getJSONObject("ratio_female_to_male")
                    r.keys().asSequence().associateWith { k -> r.getDouble(k).toFloat() }
                } else emptyMap()
                AcousticMeans(mf("male"), mf("female"), ratio)
            } else null

            fun vowel(): Map<String, VowelFormants>? = if (obj.has("formant_ranges_by_vowel")) {
                val out = mutableMapOf<String, VowelFormants>()
                val root = obj.getJSONObject("formant_ranges_by_vowel")
                val it = root.keys()
                while (it.hasNext()) {
                    val key = it.next()
                    val vo = root.getJSONObject(key)
                    fun fs(which: String): FormantSet {
                        val o = vo.getJSONObject(which)
                        return FormantSet(o.getDouble("F1").toFloat(), o.getDouble("F2").toFloat(), o.getDouble("F3").toFloat())
                    }
                    out[key] = VowelFormants(fs("male"), fs("female"))
                }
                out
            } else null

            fun derived(): DerivedStats? = if (obj.has("derived_statistics")) {
                val d = obj.getJSONObject("derived_statistics")
                DerivedStats(
                    d.getDouble("expected_vtl_difference_percent").toFloat(),
                    d.getDouble("expected_formant_shift_percent").toFloat(),
                    d.getDouble("expected_F0_ratio").toFloat(),
                    d.getDouble("expected_H1_H2_difference_dB").toFloat(),
                    d.getDouble("expected_HNR_difference_dB").toFloat()
                )
            } else null
            fun female(): RulesFemaleCfg { val o = obj.getJSONObject("rules_female_lowF0");
                return RulesFemaleCfg(
                    o.getDouble("deltaF_min_hz").toFloat(),
                    o.getDouble("vtl_max_cm").toFloat(),
                    o.getDouble("F2_min_hz").toFloat(),
                    o.getDouble("H1_H2_min_db").toFloat(),
                    o.getDouble("EHF_LF_min").toFloat(),
                    o.getDouble("SC_min_hz").toFloat(),
                    o.getDouble("prosody_min_semitones").toFloat(),
                    o.getDouble("CPP_max_db").toFloat(),
                    o.getDouble("HNR_max_db").toFloat(),
                    o.getInt("need_true_at_least")
                )
            }
            fun male(): RulesMaleCfg { val o = obj.getJSONObject("rules_male_highF0");
                return RulesMaleCfg(
                    o.getDouble("deltaF_max_hz").toFloat(),
                    o.getDouble("vtl_min_cm").toFloat(),
                    o.getDouble("F2_max_hz").toFloat(),
                    o.getDouble("H1_H2_max_db").toFloat(),
                    o.getDouble("EHF_LF_max").toFloat(),
                    o.getDouble("SC_max_hz").toFloat(),
                    o.getDouble("prosody_max_semitones").toFloat(),
                    o.getDouble("CPP_min_db").toFloat(),
                    o.getDouble("HNR_min_db").toFloat(),
                    o.getInt("need_true_at_least")
                )
            }
            fun svg(): SvgLayout? {
                if (!obj.has("svg_layout")) return null
                val s = obj.getJSONObject("svg_layout")
                val c = s.getJSONObject("canvas")
                val a = s.getJSONObject("axes")
                val p = a.getJSONObject("pitch")
                val r = a.getJSONObject("resonance")
                return SvgLayout(
                    canvas = SvgCanvas(
                        c.getDouble("x").toFloat(), c.getDouble("y").toFloat(),
                        c.getDouble("width").toFloat(), c.getDouble("height").toFloat()
                    ),
                    axes = SvgAxes(
                        pitch = SvgAxesPitch(
                            p.getDouble("x").toFloat(), p.getDouble("y1").toFloat(), p.getDouble("y2").toFloat()
                        ),
                        resonance = SvgAxesResonance(
                            r.getDouble("y").toFloat(), r.getDouble("x1").toFloat(), r.getDouble("x2").toFloat()
                        )
                    )
                )
            }
            fun resAxis(): ResonanceAxisCfg? {
                if (!obj.has("resonance_axis")) return null
                val o = obj.getJSONObject("resonance_axis")
                val w = o.getJSONObject("weights")
                val ranges = o.getJSONObject("ranges")
                val overrides = if (o.has("overrides")) {
                    val oo = o.getJSONObject("overrides")
                    val dg = if (oo.has("dark_gate")) {
                        val d = oo.getJSONObject("dark_gate")
                        ResDarkGate(
                            d.getDouble("hf_lf_max").toFloat(),
                            d.getDouble("sc_max_hz").toFloat(),
                            d.getDouble("x_max").toFloat()
                        )
                    } else null
                    ResOverrides(dg)
                } else null
                // nonlinear
                val nl = if (o.has("nonlinear")) {
                    val no = o.getJSONObject("nonlinear")
                    fun spec(name: String): NonlinSpec? {
                        if (!no.has(name)) return null
                        val so = no.getJSONObject(name)
                        return NonlinSpec(
                            so.getString("type"),
                            so.getDouble("k").toFloat(),
                            so.getDouble("mid").toFloat()
                        )
                    }
                    NonlinearCfg(
                        hfLfLeft = spec("hf_lf_left"),
                        scLeft = spec("sc_left"),
                        h1h2Left = spec("h1h2_left"),
                        vtlRight = spec("vtl_right"),
                        dFRight = spec("dF_right")
                    )
                } else null
                return ResonanceAxisCfg(
                    useBrightnessForX = o.optBoolean("use_brightness_for_x", false),
                    weights = ResWeights(
                        w.getDouble("hf_lf").toFloat(),
                        w.getDouble("sc").toFloat(),
                        w.getDouble("vtl_inv").toFloat(),
                        w.getDouble("deltaF").toFloat(),
                        w.getDouble("h1_h2").toFloat()
                    ),
                    ranges = ResRanges(
                        ranges.getDouble("hf_lf_min").toFloat(), ranges.getDouble("hf_lf_max").toFloat(),
                        ranges.getDouble("sc_min_hz").toFloat(), ranges.getDouble("sc_max_hz").toFloat(),
                        ranges.getDouble("vtl_min_cm").toFloat(), ranges.getDouble("vtl_max_cm").toFloat(),
                        ranges.getDouble("deltaF_min_hz").toFloat(), ranges.getDouble("deltaF_max_hz").toFloat(),
                        ranges.getDouble("h1h2_min_db").toFloat(), ranges.getDouble("h1h2_max_db").toFloat()
                    ),
                    hfLfLog10 = o.optBoolean("hf_lf_log10", false),
                    overrides = overrides,
                    gainX = o.optDouble("gain_x", 1.0).toFloat(),
                    nonlinear = nl
                )
            }
            return AppConfig(bands(), f0(), zones(), score(), zonesDiag(), uiLines(), female(), male(), means(), vowel(), derived(), svg(), resAxis())
        }
    }
}
