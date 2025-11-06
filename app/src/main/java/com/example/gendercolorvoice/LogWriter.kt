package com.example.gendercolorvoice

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogWriter(private val context: Context) {
    private var file: File? = null
    private var fw: FileWriter? = null

    fun start(prefix: String = "voice-map"): File? {
        try {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val f = File(dir, "$prefix-$ts.csv")
            fw = FileWriter(f, false)
            fw?.appendLine("t,pitch_hz,resonance01,x01,y01,px,py,chart_l,chart_t,chart_w,chart_h,view_w,view_h,b_hf,b_sc,b_vtl,b_df,b_h12,R")
            fw?.flush()
            file = f
        } catch (_: Throwable) { file = null; fw = null }
        return file
    }

    fun log(
        t: Double,
        pitchHz: Float?,
        resonance01: Float?,
        x01: Float,
        y01: Float,
        px: Float,
        py: Float,
        chartL: Float,
        chartT: Float,
        chartW: Float,
        chartH: Float,
        vw: Int,
        vh: Int,
        bHf: Float? = null,
        bSc: Float? = null,
        bVtl: Float? = null,
        bDf: Float? = null,
        bH12: Float? = null,
        r: Float? = null
    ) {
        try {
            fw?.apply {
                appendLine("$t,${pitchHz ?: ""},${resonance01 ?: ""},$x01,$y01,$px,$py,$chartL,$chartT,$chartW,$chartH,$vw,$vh,${bHf ?: ""},${bSc ?: ""},${bVtl ?: ""},${bDf ?: ""},${bH12 ?: ""},${r ?: ""}")
                flush()
            }
        } catch (_: Throwable) {}
    }

    fun stop() {
        try { fw?.flush(); fw?.close() } catch (_: Throwable) {}
        fw = null
        file = null
    }

    fun currentFile(): File? = file

    fun flush() { try { fw?.flush() } catch (_: Throwable) {} }

    fun latestExisting(): File? {
        val candidates = mutableListOf<File>()
        try {
            val ext = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
            if (ext.exists()) candidates += ext.listFiles()?.toList() ?: emptyList()
        } catch (_: Throwable) {}
        try {
            val `in` = File(context.filesDir, "logs")
            if (`in`.exists()) candidates += `in`.listFiles()?.toList() ?: emptyList()
        } catch (_: Throwable) {}
        return candidates.filter { it.isFile && it.name.endsWith(".csv") }
            .maxByOrNull { it.lastModified() }
    }
}
