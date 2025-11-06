package com.example.gendercolorvoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Offline analyzer for audio files producing per-window frames and summary JSON.
 * Supports 16-bit PCM mono/stereo WAV. FLAC not supported in this minimal build.
 */
class VoiceAnalyzer(private val context: Context, private val cfg: AppConfig = AppConfig.load(context)) {
    data class FrameResult(
        val timeSec: Double,
        val f0: Float,
        val f1: Float, val f2: Float, val f3: Float,
        val vtl: Float,
        val deltaF: Float,
        val h1h2: Float,
        val ehfLf: Float,
        val sc: Float,
        val pr: Float,
        val sFemale: Int,
        val sMale: Int,
        val g: Float,
        val normPitchY: Float,
        val normScoreX: Float,
        val zone: String
    )

    data class Result(val frames: List<FrameResult>, val summary: Map<String, Double>) {
        fun toJson(): String {
            val root = JSONObject()
            val arr = JSONArray()
            for (f in frames) {
                val o = JSONObject()
                o.put("time_s", f.timeSec)
                o.put("F0", f.f0)
                o.put("F1", f.f1)
                o.put("F2", f.f2)
                o.put("F3", f.f3)
                o.put("VTL", f.vtl)
                o.put("deltaF", f.deltaF)
                o.put("H1_H2", f.h1h2)
                o.put("EHF_LF", f.ehfLf)
                o.put("SC", f.sc)
                o.put("PR", f.pr)
                o.put("S_female", f.sFemale)
                o.put("S_male", f.sMale)
                o.put("G", f.g)
                o.put("norm_pitch_y", f.normPitchY)
                o.put("norm_score_x", f.normScoreX)
                o.put("zone", f.zone)
                arr.put(o)
            }
            root.put("frames", arr)
            val sum = JSONObject()
            for ((k, v) in summary) sum.put(k, v)
            root.put("summary", sum)
            return root.toString()
        }
    }

    fun analyzeFile(audioPath: String, windowMs: Int = 500, hopMs: Int = 250): Result {
        val (sr, mono) = readWavPcm16(audioPath)
        require(sr > 0 && mono.isNotEmpty()) { "Unsupported or empty WAV: $audioPath" }

        val analyzerFrames = mutableListOf<WindowFeatures>()
        val windowSamples = (sr.toLong() * windowMs / 1000L).toInt()
        val hopSamples = (sr.toLong() * hopMs / 1000L).toInt().coerceAtLeast(1)
        val resonance = ResonanceEstimator(context)
        val fwin = FeatureWindowAnalyzer(sampleRate = sr, onWindow = { wf -> analyzerFrames.add(wf) }, cfg = cfg)

        // Use same pitch and formant path as live MicAnalyzer
        var i = 0
        val buf = FloatArray(2048)
        val window = hannWindow(buf.size)
        var tSamplesProcessed = 0
        var tWindowStart = 0
        var f0ema = Ema(0.15f)
        var rema = Ema(0.15f)

        while (i < mono.size) {
            val n = min(buf.size, mono.size - i)
            for (k in 0 until n) buf[k] = mono[i + k] * window[k]
            val (f0, conf) = detectPitch(buf, n, sr)
            val rf = resonance.estimateAll(buf, n, sr, f0)
            val res01 = rf.resonance01
            if (f0 > 0 && conf > 0.45f) {
                val f0s = f0ema.update(f0)
                val rs = rema.update(res01)
                fwin.addFrame(buf, n, f0s, conf, rf.f1, rf.f2, rf.f3, rs)
            }
            i += n
            tSamplesProcessed += n
            if (tSamplesProcessed - tWindowStart >= hopSamples) {
                tWindowStart = tSamplesProcessed
            }
        }

        // Build frames JSON from analyzerFrames
        val frames = mutableListOf<FrameResult>()
        var timeS = 0.0
        val bias = cfg.score?.bias ?: cfg.zones.bias
        val clip = cfg.score?.gradientClip ?: cfg.zones.gradientClip
        analyzerFrames.forEach { wf ->
            val f0 = wf.f0Valid.median().ifNaN(0f)
            val f1 = wf.f1; val f2 = wf.f2; val f3 = wf.f3
            val vtl = wf.vtlDeltaF
            val deltaF = wf.deltaF
            val h1h2 = wf.h1MinusH2
            val ehf = wf.ehfOverElf
            val sc = wf.scHz
            val pr = wf.prosodyRangeSt
            val sFemale = wf.decision.lowF0Count
            val sMale = wf.decision.highF0Count
            val g = (sFemale - sMale).toFloat() + bias
            val y = PitchToGender.scoreFromF0(max(80f, min(300f, f0)))
            val x = ((g + clip) / (2f * clip)).coerceIn(0f, 1f)
            val zone = classify(x, y)
            frames.add(FrameResult(timeS, f0, f1, f2, f3, vtl, deltaF, h1h2, ehf, sc, pr, sFemale, sMale, g, y, x, zone))
            timeS += hopMs / 1000.0
        }

        val counts = frames.groupingBy { it.zone }.eachCount()
        val total = frames.size.coerceAtLeast(1)
        val summary = mapOf(
            "female_percent" to (100.0 * (counts["female"] ?: 0) / total),
            "androgynous_percent" to (100.0 * (counts["androgynous"] ?: 0) / total),
            "male_percent" to (100.0 * (counts["male"] ?: 0) / total)
        )
        return Result(frames, summary)
    }

    private fun classify(xRes01: Float, yPitch01: Float): String {
        val z = cfg.zonesDiag
        val bias = cfg.score?.bias ?: cfg.zones.bias
        val xc = 0.5f + bias
        val yTop0 = 1f - yPitch01
        return if (z != null) {
            val male = (xc + z.maleBase + z.maleSlope * yTop0)
            val high = (xc + z.androHighBase + z.androHighSlope * yTop0)
            when {
                xRes01 <= male -> "male"
                xRes01 <= high -> "androgynous"
                else -> "female"
            }
        } else {
            val male = (xc + cfg.zones.maleMax)
            val femMin = (xc + cfg.zones.femaleMin)
            when {
                xRes01 <= male -> "male"
                xRes01 <= femMin -> "androgynous"
                else -> "female"
            }
        }
    }

    // WAV loader (16-bit PCM). Returns Pair(sampleRate, monoFloats)
    private fun readWavPcm16(path: String): Pair<Int, FloatArray> {
        val file = File(path)
        val bytes = file.readBytes()
        if (bytes.size < 44) return 0 to FloatArray(0)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // RIFF header
        if (bb.getInt(0) != 0x46464952) return 0 to FloatArray(0) // 'RIFF'
        if (bb.getInt(8) != 0x45564157) return 0 to FloatArray(0) // 'WAVE'
        var off = 12
        var fmtFound = false
        var dataOff = -1
        var dataLen = 0
        var sr = 0
        var ch = 1
        var bits = 16
        while (off + 8 <= bytes.size) {
            val tag = bb.getInt(off); val len = bb.getInt(off + 4); val next = off + 8 + len
            if (tag == 0x20746d66) { // 'fmt '
                fmtFound = true
                val audioFormat = bb.getShort(off + 8).toInt()
                ch = bb.getShort(off + 10).toInt()
                sr = bb.getInt(off + 12)
                bits = bb.getShort(off + 22).toInt()
                if (audioFormat != 1 || bits != 16) return 0 to FloatArray(0)
            } else if (tag == 0x61746164) { // 'data'
                dataOff = off + 8
                dataLen = len
            }
            off = next
        }
        if (!fmtFound || dataOff < 0 || dataLen <= 0) return 0 to FloatArray(0)
        val samples = dataLen / (2 * ch)
        val out = FloatArray(samples)
        var idx = 0
        var p = dataOff
        while (idx < samples && p + 2 * ch <= bytes.size) {
            var acc = 0f
            for (c in 0 until ch) {
                val s = ByteBuffer.wrap(bytes, p + 2 * c, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                acc += (s / 32768f)
            }
            out[idx] = acc / ch
            p += 2 * ch
            idx++
        }
        return sr to out
    }

    private fun hannWindow(n: Int): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) w[i] = (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (n - 1))).toFloat()
        return w
    }

    private fun detectPitch(x: FloatArray, n: Int, sr: Int): Pair<Float, Float> {
        // Same as MicAnalyzer.detectPitch (lightweight autocorrelation)
        val minF = 60f
        val maxF = 500f
        val minLag = kotlin.math.floor(sr / maxF).toInt()
        val maxLag = kotlin.math.floor(sr / minF).toInt()
        val ac = FloatArray(maxLag + 1)
        var energy = 0f
        for (i in 0 until n) energy += x[i] * x[i]
        if (energy < 1e-6) return 0f to 0f
        for (lag in minLag..maxLag) {
            var s = 0f; var c = 0; var i0 = 0
            while (i0 + lag < n) { s += x[i0] * x[i0 + lag]; c++; i0++ }
            ac[lag] = if (c > 0) s / c else 0f
        }
        var bestLag = -1; var bestVal = -1e9f
        for (lag in minLag..maxLag) if (ac[lag] > bestVal) { bestVal = ac[lag]; bestLag = lag }
        if (bestLag <= 0) return 0f to 0f
        val l1 = max(minLag, bestLag - 1); val l2 = bestLag; val l3 = min(maxLag, bestLag + 1)
        val y1 = ac[l1]; val y2 = ac[l2]; val y3 = ac[l3]
        val denom = (y1 - 2 * y2 + y3)
        val shift = if (kotlin.math.abs(denom) > 1e-9) 0.5f * (y1 - y3) / denom else 0f
        val refinedLag = bestLag + shift
        val f0 = sr / refinedLag
        val conf = ((y2 - 0f) / (energy / n)).coerceIn(0f, 1f)
        return f0.toFloat() to conf
    }
}

private fun List<Float>.median(): Float {
    if (isEmpty()) return Float.NaN
    val arr = this.sorted()
    val m = arr.size / 2
    return if (arr.size % 2 == 1) arr[m] else ((arr[m - 1] + arr[m]) / 2f)
}

private fun Float.ifNaN(v: Float): Float = if (this.isNaN()) v else this

