package com.example.gendercolorvoice

import android.content.Context
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class ResFeatures(val resonance01: Float, val f1: Float, val f2: Float, val f3: Float)

class ResonanceEstimator(private val context: Context) {
    // We follow the project’s resonance mapping based on F2 and F3 z-scores:
    // resonance = clamp(0,1, ((w2*zF2 + w3*zF3) + 2) / 4)
    // Using weights from ui/voice-graph.js: 0.7321 for F2 and 0.2679 for F3.
    private val w2 = 0.7321428571428571f
    private val w3 = 0.26785714285714285f

    private var refMeanF2 = 1500f
    private var refStdF2 = 350f
    private var refMeanF3 = 2500f
    private var refStdF3 = 350f

    init {
        // Load stats.json from assets and compute global averages across phonemes
        try {
            context.assets.open("stats.json").bufferedReader().use { r ->
                val txt = r.readText()
                if (txt.isNullOrBlank() || txt.trimStart().firstOrNull() != '{') return@use
                val obj = JSONObject(txt)
                var sumM2 = 0.0; var sumS2 = 0.0; var c2 = 0
                var sumM3 = 0.0; var sumS3 = 0.0; var c3 = 0
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = obj.getJSONArray(key)
                    if (arr.length() > 3) {
                        val f2 = arr.getJSONObject(2)
                        val f3 = arr.getJSONObject(3)
                        sumM2 += f2.getDouble("mean"); sumS2 += f2.getDouble("stdev"); c2 += 1
                        sumM3 += f3.getDouble("mean"); sumS3 += f3.getDouble("stdev"); c3 += 1
                    }
                }
                if (c2 > 0 && c3 > 0) {
                    refMeanF2 = (sumM2 / c2).toFloat()
                    refStdF2  = (sumS2 / c2).toFloat()
                    refMeanF3 = (sumM3 / c3).toFloat()
                    refStdF3  = (sumS3 / c3).toFloat()
                }
            }
        } catch (_: Throwable) {}
    }

    private var lpcOrder = 12
    private val emaRes = Ema(0.2f)

    fun estimateAll(xIn: FloatArray, n: Int, sr: Int, f0Hz: Float? = null): ResFeatures {
        // Pre-emphasis and resample (simple decimation by 2 if needed)
        val targetSr = 22050
        val decim = max(1, floor(sr.toFloat() / targetSr).toInt())
        val m = n / decim
        if (m < 256) return ResFeatures(0.5f, 500f, 1500f, 2500f)
        val x = FloatArray(m)
        var j = 0
        var prev = 0f
        for (i in 0 until m) {
            val s = xIn[j]
            val y = s - 0.97f * prev
            prev = s
            x[i] = y
            j += decim
        }

        // Window
        val win = hann(m)
        for (i in 0 until m) x[i] *= win[i]

        val order = lpcOrder
        val ac = autocorr(x, order + 1)
        if (ac[0] <= 0f) return ResFeatures(0.5f, 500f, 1500f, 2500f)
        val a = levinsonDurbin(ac, order)

        // Evaluate LPC spectrum and find prominent peaks to estimate formants
        val fMax = 5000f
        val nfft = 2048
        val spec = DoubleArray(nfft / 2)
        var bestPeaks = mutableListOf<Pair<Int, Double>>()
        for (k in 1 until nfft / 2) {
            val w = 2.0 * Math.PI * k / nfft
            var denomRe = 1.0
            var denomIm = 0.0
            for (p in 1..order) {
                denomRe += a[p] * cos(-w * p)
                denomIm += a[p] * kotlin.math.sin(-w * p)
            }
            var mag = 1.0 / (denomRe * denomRe + denomIm * denomIm)
            // Suppress harmonic bins near multiples of f0 to reduce voicing leakage
            val f0 = f0Hz ?: 0f
            if (f0 > 40f) {
                val hz = targetSr.toFloat() * k / nfft
                val kH = (hz / f0).toInt()
                if (kH >= 1 && kotlin.math.abs(hz - kH * f0) < f0 * 0.08f) {
                    mag *= 0.15
                }
            }
            spec[k] = mag
        }
        // Simple peak picking
        var peaks = mutableListOf<Int>()
        for (k in 2 until nfft / 2 - 2) {
            val v = spec[k]
            if (v > spec[k - 1] && v > spec[k + 1] && v > spec[k - 2] && v > spec[k + 2]) {
                peaks.add(k)
            }
        }
        // Convert to Hz and keep within 200..5000
        val hzPerBin = targetSr.toFloat() / nfft
        val candidates = peaks.map { it to (it * hzPerBin) }.filter { it.second in 200f..fMax }
        fun pickInRange(lo: Float, hi: Float, used: MutableSet<Int>): Float? {
            var best: Pair<Int, Double>? = null
            for ((idx, hz) in candidates) {
                if (hz < lo || hz > hi || used.contains(idx)) continue
                val v = spec[idx]
                if (best == null || v > best!!.second) best = idx to v
            }
            return best?.let { used.add(it.first); it.first * hzPerBin }
        }
        val used = mutableSetOf<Int>()
        val f1 = pickInRange(200f, 900f, used) ?: 500f
        val f2 = pickInRange(800f, 2500f, used) ?: 1500f
        val f3 = pickInRange(1800f, 4000f, used) ?: 2500f

        val z2 = ((f2 - refMeanF2) / refStdF2)
        val z3 = ((f3 - refMeanF3) / refStdF3)
        val z = w2 * z2 + w3 * z3
        val resonance = ((z + 2f) / 4f).coerceIn(0f, 1f)
        val res01 = emaRes.update(resonance)
        return ResFeatures(res01, f1, f2, f3)
    }

    fun estimate(xIn: FloatArray, n: Int, sr: Int, f0Hz: Float? = null): Float =
        estimateAll(xIn, n, sr, f0Hz).resonance01

    private fun hann(n: Int): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) w[i] = (0.5 - 0.5 * cos(2.0 * Math.PI * i / (n - 1))).toFloat()
        return w
    }

    private fun autocorr(x: FloatArray, lags: Int): FloatArray {
        val n = x.size
        val r = FloatArray(lags)
        for (lag in 0 until lags) {
            var s = 0f
            var i = 0
            while (i + lag < n) { s += x[i] * x[i + lag]; i++ }
            r[lag] = s
        }
        return r
    }

    private fun levinsonDurbin(r: FloatArray, order: Int): DoubleArray {
        val a = DoubleArray(order + 1)
        val e = DoubleArray(order + 1)
        val k = DoubleArray(order + 1)
        a[0] = 1.0
        e[0] = r[0].toDouble()
        for (i in 1..order) {
            var acc = 0.0
            for (j in 1 until i) acc += a[j] * r[i - j]
            k[i] = (r[i] - acc) / e[i - 1]
            a[i] = k[i]
            for (j in 1 until i) a[j] = a[j] - k[i] * a[i - j]
            e[i] = (1.0 - k[i] * k[i]) * e[i - 1]
        }
        return a
    }
}
