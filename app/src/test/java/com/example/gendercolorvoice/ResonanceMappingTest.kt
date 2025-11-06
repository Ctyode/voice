package com.example.gendercolorvoice

import org.junit.Assert.assertTrue
import org.junit.Test

class ResonanceMappingTest {
    private val resCfg = ResonanceAxisCfg(
        useBrightnessForX = true,
        weights = ResWeights(hfLf = 0.55f, sc = 0.25f, vtlInv = 0.10f, deltaF = 0.05f, h1h2 = 0.05f),
        ranges = ResRanges(
            hfLfMin = 0.20f, hfLfMax = 1.80f,
            scMinHz = 600f, scMaxHz = 2400f,
            vtlMinCm = 14f, vtlMaxCm = 22f,
            deltaFMinHz = 700f, deltaFMaxHz = 1200f,
            h1h2MinDb = 0f, h1h2MaxDb = 12f
        )
    )

    private fun wf(
        ehfelf: Float, sc: Float, vtl: Float, dF: Float, h1h2: Float
    ): WindowFeatures {
        return WindowFeatures(
            f0Valid = emptyList(),
            f1 = 500f, f2 = 1500f, f3 = 2500f,
            deltaF = dF,
            vtlDeltaF = vtl,
            vtlFm = vtl,
            h1MinusH2 = h1h2,
            ehfOverElf = ehfelf,
            scHz = sc,
            prosodyRangeSt = 0f,
            avgPitch01 = 0.5f,
            avgRes01 = 0.5f,
            decision = WindowDecision(0,0,false,false)
        )
    }

    @Test fun ultra_male_goes_left() {
        // Very dark male: EHF/LF<<, SC low, VTL long, ΔF small-mid, H1-H2 large positive
        val w = wf(ehfelf = 0.01f, sc = 400f, vtl = 22f, dF = 780f, h1h2 = 20f)
        val (x, c) = BrightnessMapper.computeX01(resCfg, w)
        assertTrue("x should be near left: x=$x comps=$c", x < 0.2f)
    }

    @Test fun bright_female_goes_right() {
        val w = wf(ehfelf = 1.6f, sc = 2200f, vtl = 14f, dF = 1100f, h1h2 = 6f)
        val (x, _) = BrightnessMapper.computeX01(resCfg, w)
        assertTrue("x should be near right: x=$x", x > 0.8f)
    }
}

