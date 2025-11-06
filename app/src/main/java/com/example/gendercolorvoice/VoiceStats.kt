package com.example.gendercolorvoice

import android.content.Context
import org.json.JSONObject

data class VoiceGroupStats(val meanfunHz: Float, val stdMeanfunHz: Float)

data class VoiceStats(
    val female: VoiceGroupStats,
    val male: VoiceGroupStats
) {
    companion object {
        fun load(context: Context): VoiceStats? {
            return try {
                val txt = context.assets.open("voice.json").bufferedReader().use { it.readText() }
                val o = JSONObject(txt)
                val fs = o.getJSONObject("features_summary")
                val meanfun = fs.getJSONObject("meanfun")
                val female = meanfun.getJSONObject("female")
                val male = meanfun.getJSONObject("male")
                VoiceStats(
                    female = VoiceGroupStats(
                        meanfunHz = (female.getDouble("mean").toFloat() * 1000f),
                        stdMeanfunHz = (female.getDouble("std").toFloat() * 1000f)
                    ),
                    male = VoiceGroupStats(
                        meanfunHz = (male.getDouble("mean").toFloat() * 1000f),
                        stdMeanfunHz = (male.getDouble("std").toFloat() * 1000f)
                    )
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}

