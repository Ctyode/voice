package com.example.gendercolorvoice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

class MicAnalyzer(
    private val context: Context,
    private val sampleRate: Int = 44100,
    private val onResult: (f0Hz: Float, resonance01: Float, confidence: Float) -> Unit,
    private val onFrame: ((x: FloatArray, n: Int, f0: Float, conf: Float, f1: Float, f2: Float, f3: Float, res01: Float) -> Unit)? = null
) {
    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = max(minBuf, 2048)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )

        audioRecord?.startRecording()
        running = true
        thread = Thread { loop(bufSize) }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
        audioRecord?.apply {
            try { stop() } catch (_: Throwable) {}
            release()
        }
        audioRecord = null
    }

    private fun loop(bufSize: Int) {
        val buf = ShortArray(bufSize)
        val floatBuf = FloatArray(bufSize)
        val window = hannWindow(bufSize)
        val resonance = ResonanceEstimator(context)
        val emaF0 = Ema(0.15f)
        val emaRes = Ema(0.15f)
        val noiseEma = Ema(0.05f)

        while (running) {
            val ar = audioRecord ?: break
            val n = ar.read(buf, 0, buf.size)
            if (n <= 0) continue

            var energy = 0f
            for (i in 0 until n) {
                val v = buf[i] / 32768f
                energy += v * v
                floatBuf[i] = v * window[i]
            }
            val rms = kotlin.math.sqrt(energy / n)

            val (f0, conf) = detectPitch(floatBuf, n, sampleRate)

            // Adaptive noise gate + voiced check (less strict)
            if (conf < 0.45f) { noiseEma.update(rms); continue }
            val baseline = noiseEma.value().takeIf { it > 0f } ?: (rms * 0.4f)
            val gate = baseline * 1.4f
            if (rms < gate) continue

            val resAll = resonance.estimateAll(floatBuf, n, sampleRate, f0)
            val res01 = resAll.resonance01
            if (f0 > 0) {
                val f0s = emaF0.update(f0)
                val ress = emaRes.update(res01)
                onResult(f0s, ress, conf)
                onFrame?.invoke(floatBuf.copyOf(n), n, f0s, conf, resAll.f1, resAll.f2, resAll.f3, ress)
            }
        }
    }

    private fun hannWindow(n: Int): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) {
            w[i] = (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (n - 1))).toFloat()
        }
        return w
    }

    // Lightweight autocorrelation-based pitch detection with primitive confidence
    private fun detectPitch(x: FloatArray, n: Int, sr: Int): Pair<Float, Float> {
        val minF = 60f
        val maxF = 500f
        val minLag = floor(sr / maxF).toInt()
        val maxLag = floor(sr / minF).toInt()
        val ac = FloatArray(maxLag + 1)

        // Normalize energy
        var energy = 0f
        for (i in 0 until n) energy += x[i] * x[i]
        if (energy < 1e-6) return 0f to 0f

        for (lag in minLag..maxLag) {
            var s = 0f
            var c = 0
            var i = 0
            while (i + lag < n) {
                s += x[i] * x[i + lag]
                c++
                i++
            }
            ac[lag] = if (c > 0) s / c else 0f
        }

        // Find max peak beyond zero-lag
        var bestLag = -1
        var bestVal = -1e9f
        for (lag in minLag..maxLag) {
            if (ac[lag] > bestVal) {
                bestVal = ac[lag]
                bestLag = lag
            }
        }

        if (bestLag <= 0) return 0f to 0f

        // Parabolic interpolation around bestLag for sub-sample accuracy
        val l1 = max(minLag, bestLag - 1)
        val l2 = bestLag
        val l3 = min(maxLag, bestLag + 1)
        val y1 = ac[l1]
        val y2 = ac[l2]
        val y3 = ac[l3]
        val denom = (y1 - 2 * y2 + y3)
        val shift = if (abs(denom) > 1e-9) 0.5f * (y1 - y3) / denom else 0f
        val refinedLag = bestLag + shift

        val f0 = sr / refinedLag

        // crude confidence: normalized peak against energy
        val conf = ((y2 - 0f) / (energy / n)).coerceIn(0f, 1f)
        return f0.toFloat() to conf
    }
}
