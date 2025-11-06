package com.example.gendercolorvoice

object BrightnessMapper {
    data class Components(
        val bHf: Float, val bSc: Float, val bVtl: Float, val bDf: Float, val bH12: Float,
        val r: Float
    )

    private fun norm(v: Float, a: Float, b: Float): Float {
        if (b <= a) return 0f
        return ((v - a) / (b - a)).coerceIn(0f, 1f)
    }

    fun computeX01(resCfg: ResonanceAxisCfg, wf: WindowFeatures): Pair<Float, Components> {
        val r = resCfg.ranges; val w = resCfg.weights
        val bHfRaw = wf.ehfOverElf
        val bHf  = if (resCfg.hfLfLog10) {
            val eps = 1e-6f
            val v = kotlin.math.log10(kotlin.math.max(eps, bHfRaw))
            val a = kotlin.math.log10(kotlin.math.max(eps, r.hfLfMin))
            val b = kotlin.math.log10(kotlin.math.max(eps, r.hfLfMax))
            norm(v, a, b)
        } else {
            norm(bHfRaw, r.hfLfMin, r.hfLfMax)
        }
        val bScRaw = wf.scHz
        val bSc  = norm(bScRaw, r.scMinHz, r.scMaxHz)
        val bVtlRaw = wf.vtlDeltaF // convert using 1/vtl for normalization
        val bVtl = norm(1f / bVtlRaw, 1f / r.vtlMaxCm, 1f / r.vtlMinCm)
        val bDfRaw = wf.deltaF
        val bDf  = norm(bDfRaw, r.deltaFMinHz, r.deltaFMaxHz)
        val bH12Raw = kotlin.math.max(0f, wf.h1MinusH2)
        val bH12 = norm(bH12Raw, r.h1h2MinDb, r.h1h2MaxDb)

        // Optional nonlinear shaping around provided midpoints
        fun sigm(v: Float, k: Float, t: Float): Float = (1f / (1f + kotlin.math.exp((-k) * (v - t)))).coerceIn(0f,1f)
        fun invSigm(v: Float, k: Float, t: Float): Float = (1f - sigm(v, k, t)).coerceIn(0f,1f)
        val nl = resCfg.nonlinear
        val bHfNL = nl?.hfLfLeft?.let { s ->
            val t = if (resCfg.hfLfLog10) {
                val eps = 1e-6f
                val mid = kotlin.math.log10(kotlin.math.max(eps, s.mid))
                val a = kotlin.math.log10(kotlin.math.max(eps, r.hfLfMin))
                val b = kotlin.math.log10(kotlin.math.max(eps, r.hfLfMax))
                norm(mid, a, b)
            } else norm(s.mid, r.hfLfMin, r.hfLfMax)
            when (s.type) {
                "inv_sigmoid" -> invSigm(bHf, s.k, t)
                "sigmoid" -> sigm(bHf, s.k, t)
                else -> bHf
            }
        } ?: bHf
        val bScNL = nl?.scLeft?.let { s ->
            val t = norm(s.mid, r.scMinHz, r.scMaxHz)
            when (s.type) {
                "inv_sigmoid" -> invSigm(bSc, s.k, t)
                "sigmoid" -> sigm(bSc, s.k, t)
                else -> bSc
            }
        } ?: bSc
        val bH12NL = nl?.h1h2Left?.let { s ->
            val t = norm(s.mid, r.h1h2MinDb, r.h1h2MaxDb)
            when (s.type) {
                "inv_sigmoid" -> invSigm(bH12, s.k, t)
                "sigmoid" -> sigm(bH12, s.k, t)
                else -> bH12
            }
        } ?: bH12
        val bVtlNL = nl?.vtlRight?.let { s ->
            // vtlRight defined in cm; our normalization uses 1/vtl
            val t = norm(1f / s.mid, 1f / r.vtlMaxCm, 1f / r.vtlMinCm)
            when (s.type) {
                "sigmoid" -> sigm(bVtl, s.k, t)
                "inv_sigmoid" -> invSigm(bVtl, s.k, t)
                else -> bVtl
            }
        } ?: bVtl
        val bDfNL = nl?.dFRight?.let { s ->
            val t = norm(s.mid, r.deltaFMinHz, r.deltaFMaxHz)
            when (s.type) {
                "sigmoid" -> sigm(bDf, s.k, t)
                "inv_sigmoid" -> invSigm(bDf, s.k, t)
                else -> bDf
            }
        } ?: bDf

        var R = (w.hfLf * bHfNL + w.sc * bScNL + w.vtlInv * bVtlNL + w.deltaF * bDfNL + w.h1h2 * bH12NL).coerceIn(0f,1f)
        resCfg.overrides?.darkGate?.let { dg ->
            if (wf.ehfOverElf <= dg.hfLfMax && wf.scHz <= dg.scMaxHz) {
                R = kotlin.math.min(R, dg.xMax)
            }
        }
        return R to Components(bHf, bSc, bVtl, bDf, bH12, R)
    }
}
