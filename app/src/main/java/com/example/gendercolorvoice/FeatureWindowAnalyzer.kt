package com.example.gendercolorvoice

import kotlin.math.*

data class WindowDecision(
    val lowF0Count: Int,
    val highF0Count: Int,
    val lowF0Hit: Boolean,
    val highF0Hit: Boolean
)

data class WindowFeatures(
    val f0Valid: List<Float>,
    val f1: Float, val f2: Float, val f3: Float,
    val deltaF: Float,
    val vtlDeltaF: Float,
    val vtlFm: Float,
    val h1MinusH2: Float,
    val ehfOverElf: Float,
    val scHz: Float,
    val prosodyRangeSt: Float,
    val avgPitch01: Float,
    val avgRes01: Float,
    val cppDb: Float? = null,
    val hnrDb: Float? = null,
    val decision: WindowDecision
)

class FeatureWindowAnalyzer(
    private val sampleRate: Int,
    private val onWindow: (WindowFeatures) -> Unit,
    private val cfg: AppConfig = AppConfig(
        bands = BandsCfg(1800f, 5000f),
        f0 = F0ZoneCfg(165f, 180f),
        zones = ZonesCfg(0.05f, -0.2f, -0.2f, 0.08f, 0.08f, 0.35f, 3),
        score = ScoreCfg(0f, 0.35f, 3),
        zonesDiag = null,
        uiLines = null,
        female = RulesFemaleCfg(1000f,16.5f,1700f,8f,1.1f,1800f,10f,12f,18f,5),
        male = RulesMaleCfg(850f,18f,1500f,5f,0.7f,1500f,8f,12f,20f,5)
    )
) {
    private val windowMs = 500
    private val hopMs = 250
    private val windowSamples = (sampleRate * windowMs) / 1000
    private val hopSamples = (sampleRate * hopMs) / 1000
    private val buffer = FloatArray(windowSamples * 2)
    private var bufLen = 0
    private val f0Track = ArrayList<Float>()
    private val formantF2 = ArrayList<Float>()
    private val formantF3 = ArrayList<Float>()
    private val formantF1 = ArrayList<Float>()

    // Simple Hann window and FFT reusable arrays
    private val nfft = 2048
    private val hann = FloatArray(nfft) { i -> (0.5 - 0.5 * cos(2.0 * Math.PI * i / (nfft - 1))).toFloat() }
    private val re = FloatArray(nfft)
    private val im = FloatArray(nfft)
    private val resSamples = ArrayList<Float>()

    fun addFrame(x: FloatArray, n: Int, f0: Float, conf: Float, f1: Float, f2: Float, f3: Float, res01: Float) {
        // append to buffer
        val copyN = min(n, buffer.size - bufLen)
        System.arraycopy(x, 0, buffer, bufLen, copyN)
        bufLen += copyN

        if (conf > 0.6f && f0 in 60f..500f) f0Track.add(f0)
        if (f1 > 0 && f2 > 0 && f3 > 0) { formantF1.add(f1); formantF2.add(f2); formantF3.add(f3) }
        // keep resonance samples parallel to f0
        if (res01 in 0f..1f) resSamples.add(res01)

        while (bufLen >= windowSamples) {
            analyzeWindow(buffer, 0, windowSamples)
            // shift by hop
            val keep = bufLen - hopSamples
            if (keep > 0) System.arraycopy(buffer, hopSamples, buffer, 0, keep)
            bufLen = keep.coerceAtLeast(0)
            // reset per-window tracks
            f0Track.clear(); formantF1.clear(); formantF2.clear(); formantF3.clear(); resSamples.clear()
        }
    }

    private fun analyzeWindow(x: FloatArray, off: Int, len: Int) {
        // PSD by averaging several frames across the window
        val hop = nfft / 2
        var pos = 0
        var sumPsd = FloatArray(nfft/2) { 0f }
        var frames = 0
        while (pos + nfft <= len) {
            // windowed copy
            for (i in 0 until nfft) { re[i] = x[off + pos + i] * hann[i]; im[i] = 0f }
            fft(re, im)
            for (k in 0 until nfft/2) {
                val p = re[k]*re[k] + im[k]*im[k]
                sumPsd[k] += p
            }
            frames++
            pos += hop
        }
        if (frames == 0) return
        for (k in 0 until nfft/2) {
            sumPsd[k] = (sumPsd[k] / frames.toFloat())
        }

        val hzPerBin = sampleRate.toFloat() / nfft
        val splitBin = (cfg.bands.splitHz / hzPerBin).toInt().coerceIn(1, nfft/2-1)
        val scMaxBin = (cfg.bands.scMaxHz / hzPerBin).toInt().coerceAtMost(nfft/2-1)

        var eLF = 0.0
        var eHF = 0.0
        var num = 0.0
        var den = 0.0
        for (k in 1..scMaxBin) {
            val p = sumPsd[k].toDouble()
            val f = k * hzPerBin
            if (k <= splitBin) eLF += p else eHF += p
            num += f * p
            den += p
        }
        val ehfelf = if (eLF > 1e-12) (eHF / eLF).toFloat() else Float.POSITIVE_INFINITY
        val sc = if (den > 0) (num / den).toFloat() else 0f

        // H1-H2 from spectrum
        val f0 = if (f0Track.isNotEmpty()) f0Track.median() else 0f
        val h1h2 = if (f0 > 0) {
            val a1 = peakDbNear(sumPsd, f0, hzPerBin)
            val a2 = peakDbNear(sumPsd, 2*f0, hzPerBin)
            a1 - a2
        } else 0f

        // ΔF and VTL
        val f1m = formantF1.median().ifNaN(500f)
        val f2m = formantF2.median().ifNaN(1500f)
        val f3m = formantF3.median().ifNaN(2500f)
        val deltaF = (((f2m - f1m) + (f3m - f2m)) / 2f).coerceAtLeast(1f)
        val c = 34300f // cm/s
        val vtlDeltaF = c / (2f * deltaF)
        val vtlFm = listOf(1,2,3).map { m -> ((2*m-1) * c) / (4f * when(m){1->f1m;2->f2m;else->f3m}) }
            .median().ifNaN(vtlDeltaF)

        // Prosodic range in semitones
        val pr = if (f0Track.size >= 2) {
            val fmax = f0Track.maxOrNull() ?: 0f
            val fmin = f0Track.minOrNull() ?: 0f
            if (fmax>0 && fmin>0) (12.0 * kotlin.math.log2((fmax/fmin).toDouble())).toFloat() else 0f
        } else 0f

        val avgPitch01 = if (f0Track.isNotEmpty()) {
            val s = f0Track.map { com.example.gendercolorvoice.PitchToGender.scoreFromF0(it) }.average().toFloat()
            s.coerceIn(0f,1f)
        } else 0f
        val avgRes01 = if (resSamples.isNotEmpty()) resSamples.average().toFloat().coerceIn(0f,1f) else 0.5f

        // Rules
        val lowF0 = f0 in 0f..cfg.f0.lowMaxHz
        val highF0 = f0 >= cfg.f0.highMinHz
        val feminineLow = listOf(
            deltaF >= cfg.female.deltaFMin,
            vtlDeltaF <= cfg.female.vtlMax,
            f2m >= cfg.female.f2Min,
            h1h2 >= cfg.female.h1h2Min,
            (ehfelf >= cfg.female.ehfLfMin) || (sc >= cfg.female.scMin),
            pr >= cfg.female.prosodyMin,
            true // placeholder for CPP/HNR
        ).count { it }
        val masculineHigh = listOf(
            deltaF <= cfg.male.deltaFMax,
            vtlDeltaF >= cfg.male.vtlMin,
            f2m <= cfg.male.f2Max,
            h1h2 <= cfg.male.h1h2Max,
            (ehfelf <= cfg.male.ehfLfMax) || (sc <= cfg.male.scMax),
            pr <= cfg.male.prosodyMax,
            true // placeholder for CPP/HNR
        ).count { it }

        val decision = WindowDecision(
            lowF0Count = feminineLow,
            highF0Count = masculineHigh,
            lowF0Hit = lowF0 && feminineLow >= cfg.female.needTrueAtLeast,
            highF0Hit = highF0 && masculineHigh >= cfg.male.needTrueAtLeast
        )

        onWindow(
            WindowFeatures(
                f0Track.toList(), f1m, f2m, f3m,
                deltaF, vtlDeltaF, vtlFm,
                h1h2, ehfelf, sc, pr,
                avgPitch01, avgRes01,
                null, null,
                decision
            )
        )
    }

    private fun peakDbNear(psd: FloatArray, targetHz: Float, hzPerBin: Float): Float {
        var idx = (targetHz / hzPerBin).roundToInt().coerceIn(1, psd.size-2)
        // local quadratic interpolation around idx
        val y1 = ln(psd[idx-1].toDouble().coerceAtLeast(1e-12)).toFloat()
        val y2 = ln(psd[idx].toDouble().coerceAtLeast(1e-12)).toFloat()
        val y3 = ln(psd[idx+1].toDouble().coerceAtLeast(1e-12)).toFloat()
        val denom = (y1 - 2*y2 + y3)
        val shift = if (abs(denom) > 1e-6) 0.5f * (y1 - y3) / denom else 0f
        val peak = y2 - 0.25f * (y1 - y3) * shift
        return (10.0 * ln(10.0) * peak / ln(10.0)).toFloat() // already ln power -> convert to dB via 10*log10
    }

    // In-place radix-2 FFT
    private fun fft(r: FloatArray, im: FloatArray) {
        val n = r.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val tr = r[i]; val ti = im[i]; r[i]=r[j]; im[i]=im[j]; r[j]=tr; im[j]=ti }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wlenCos = cos(ang).toFloat()
            val wlenSin = sin(ang).toFloat()
            for (i in 0 until n step len) {
                var wCos = 1f; var wSin = 0f
                for (k in 0 until len/2) {
                    val uRe = r[i+k]; val uIm = im[i+k]
                    val vRe = r[i+k+len/2]*wCos - im[i+k+len/2]*wSin
                    val vIm = r[i+k+len/2]*wSin + im[i+k+len/2]*wCos
                    r[i+k] = uRe + vRe; im[i+k] = uIm + vIm
                    r[i+k+len/2] = uRe - vRe; im[i+k+len/2] = uIm - vIm
                    val nwCos = wCos * wlenCos - wSin * wlenSin
                    val nwSin = wCos * wlenSin + wSin * wlenCos
                    wCos = nwCos; wSin = nwSin
                }
            }
            len = len shl 1
        }
    }
}

private fun List<Float>.median(): Float {
    if (isEmpty()) return Float.NaN
    val arr = this.sorted()
    val m = arr.size/2
    return if (arr.size % 2 == 1) arr[m] else ((arr[m-1]+arr[m])/2f)
}

private fun Float.ifNaN(v: Float): Float = if (this.isNaN()) v else this
