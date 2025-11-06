package com.example.gendercolorvoice

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import android.view.Gravity
import android.webkit.WebView

class MainActivity : ComponentActivity() {

    private lateinit var mic: MicAnalyzer
    private lateinit var fieldView: GradientFieldView
    private lateinit var overlay: LabelsOverlayView
    private lateinit var recorder: SessionRecorder
    private lateinit var logger: LogWriter
    private var windowAnalyzer: FeatureWindowAnalyzer? = null
    private val windowLog = mutableListOf<WindowFeatures>()
    private var latestWindow: WindowFeatures? = null
    private lateinit var cfg: AppConfig
    private val recentClasses = ArrayDeque<String>()
    private var svgMapping: SvgMappingInfo? = null
    private var voiceStats: VoiceStats? = null
    private val recentBrightness = ArrayDeque<Float>()
    private fun pushBrightness(v: Float) {
        recentBrightness.addLast(v)
        while (recentBrightness.size > 20) recentBrightness.removeFirst()
    }
    private fun brightnessStd(): Float {
        if (recentBrightness.size < 5) return Float.POSITIVE_INFINITY
        val m = recentBrightness.average().toFloat()
        var s = 0f
        for (v in recentBrightness) { val d = v - m; s += d*d }
        return kotlin.math.sqrt(s / recentBrightness.size)
    }

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startAudio() else stopAudio()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fieldView = GradientFieldView(this)
        overlay = LabelsOverlayView(this)
        recorder = SessionRecorder { overlay.maleXAtLowPitch }
        logger = LogWriter(this)
        val root = FrameLayout(this)

        // Background: load SVG from assets into a WebView
        val bgWebView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.apply {
                builtInZoomControls = false
                displayZoomControls = false
                allowFileAccess = true
            }
            val html = """
                <html>
                  <head>
                    <meta name='viewport' content='width=device-width, initial-scale=1.0, user-scalable=no' />
                    <style>
                      html, body { height:100%; margin:0; padding:0; background:transparent; }
                      img.bg { position:fixed; left:0; top:0; width:100%; height:100%;
                               object-fit:contain; object-position:center top; }
                    </style>
                  </head>
                  <body>
                    <img class='bg' src='Scheme2.svg' />
                  </body>
                </html>
            """.trimIndent()
            loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
        }

        // Order: background SVG, then marker/trail, then overlay text/controls
        root.addView(bgWebView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(fieldView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Controls overlay (Record/Stop, Play) + status
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0x66FFFFFF)
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnRecord = Button(this).apply { text = "Record" }
        val btnPlay = Button(this).apply { text = "Play" }
        val btnReset = Button(this).apply { text = "Reset" }
        val btnShare = Button(this).apply { text = "Share log" }
        val status = TextView(this).apply { text = "" }
        controls.addView(btnRecord)
        controls.addView(btnPlay)
        controls.addView(btnReset)
        controls.addView(btnShare)
        controls.addView(status)

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 32
        }
        root.addView(controls, lp)
        setContentView(root)

        // Load and apply config
        cfg = AppConfig.load(this)
        overlay.applyZonesFromConfig(cfg)
        // Load svg_mapping_info.json for data-driven anchors and pitch range
        svgMapping = SvgMappingInfo.load(this)
        // Prefer mapping’s canvas if present, else config’s SVG layout
        if (svgMapping?.canvas != null) fieldView.applySvgLayout(svgMapping) else fieldView.applySvgLayout(cfg)
        // If config defines y_norm_f0 range, use it to normalize pitch scores
        cfg.zonesDiag?.let { z ->
            PitchToGender.setRange(z.yNormF0MinHz, z.yNormF0MaxHz)
        }
        // Override pitch normalization if provided by mapping (explicit 80–300 Hz)
        svgMapping?.pitchRange?.let { pr -> PitchToGender.setRange(pr.minHz, pr.maxHz) }
        // Load curated dataset stats (voice.json) — highest priority
        voiceStats = VoiceStats.load(this)
        voiceStats?.let { vs ->
            // Robust range from pooled groups: [min(mean-2σ), max(mean+2σ)]
            val lo = listOf(vs.female.meanfunHz - 2f*vs.female.stdMeanfunHz, vs.male.meanfunHz - 2f*vs.male.stdMeanfunHz).minOrNull() ?: 80f
            val hi = listOf(vs.female.meanfunHz + 2f*vs.female.stdMeanfunHz, vs.male.meanfunHz + 2f*vs.male.stdMeanfunHz).maxOrNull() ?: 300f
            val minHz = lo.coerceIn(40f, 200f)
            val maxHz = hi.coerceIn(220f, 500f).coerceAtLeast(minHz + 40f)
            PitchToGender.setRange(minHz, maxHz)
        }
        // Optionally refine pitch range from a dataset (voice.csv) if present — lower priority than voice.json
        if (voiceStats == null) trySetPitchRangeFromVoiceCsv()
        logger.start()

        mic = MicAnalyzer(
            context = this,
            onResult = { f0Hz, resonance01, confidence ->
                val pitch01 = PitchToGender.scoreFromF0(f0Hz)
                val bm = if (cfg.resAxis?.useBrightnessForX == true && latestWindow != null) {
                    val base = cfg.resAxis!!
                    val dyn = cfg.score?.let { sc ->
                        val so = try { // bias_dynamic may be optional
                            val cls = sc::class
                            null
                        } catch (_: Throwable) { null }
                        null
                    }
                    // Scale VTL/ΔF weights for low F0 guard if configured
                    val resCfg = run {
                        val bd = try {
                            val o = org.json.JSONObject(this.assets.open("config.json").bufferedReader().use { it.readText() })
                            val score = o.optJSONObject("score")
                            val bdj = score?.optJSONObject("bias_dynamic")
                            bdj
                        } catch (_: Throwable) { null }
                        if (bd != null && bd.optBoolean("enabled", false)) {
                            val fLow = bd.optDouble("f0_low_hz", 60.0).toFloat()
                            val fHigh = bd.optDouble("f0_high_hz", 150.0).toFloat()
                            val scale = bd.optDouble("scale_vtl_dF", 0.5).toFloat()
                            if (f0Hz in fLow..fHigh) {
                                base.copy(weights = ResWeights(
                                    hfLf = base.weights.hfLf,
                                    sc = base.weights.sc,
                                    vtlInv = base.weights.vtlInv * scale,
                                    deltaF = base.weights.deltaF * scale,
                                    h1h2 = base.weights.h1h2
                                ))
                            } else base
                        } else base
                    }
                    BrightnessMapper.computeX01(resCfg, latestWindow!!)
                } else null
                val xFromBrightness = bm?.first
                if (xFromBrightness != null) pushBrightness(xFromBrightness)
                val stdB = brightnessStd()
                // Adaptive blend: if brightness varies too little, lean on resonance01 more
                val alpha = when {
                    xFromBrightness == null -> 0f
                    stdB.isFinite().not() -> 0.6f
                    stdB < 0.03f -> 0.2f
                    stdB < 0.06f -> 0.45f
                    else -> 0.7f
                }
                var x01 = if (xFromBrightness != null) (alpha * xFromBrightness + (1 - alpha) * resonance01) else resonance01
                // Apply global X gain from config (x' = 0.5 + gain*(x-0.5))
                cfg.resAxis?.let { rx ->
                    val g = rx.gainX
                    if (g != 1f) {
                        x01 = (0.5f + g * (x01 - 0.5f)).coerceIn(0f,1f)
                    }
                }
                // Bias dynamic: advanced formula
                try {
                    val root = org.json.JSONObject(this.assets.open("config.json").bufferedReader().use { it.readText() })
                    val score = root.optJSONObject("score")
                    val bd = score?.optJSONObject("bias_dynamic")
                    if (bd != null && bd.optBoolean("enabled", false)) {
                        val params = bd.optJSONObject("params")
                        // y_low
                        fun sigm(v: Float, k: Float, t: Float): Float = (1f / (1f + kotlin.math.exp((-k) * (v - t)))).coerceIn(0f,1f)
                        fun invSigm(v: Float, k: Float, t: Float): Float = (1f - sigm(v, k, t)).coerceIn(0f,1f)
                        val ySpec = params?.optJSONObject("y_low")
                        val yk = ySpec?.optDouble("k", 8.0)?.toFloat() ?: 8f
                        val ymid = ySpec?.optDouble("mid", 0.30)?.toFloat() ?: 0.30f
                        val yLow = invSigm(pitch01, yk, ymid)
                        val k1 = params?.optDouble("k1", 0.10)?.toFloat() ?: 0.10f
                        // geom_male criteria from latestWindow
                        val gmSpec = params?.optJSONObject("geom_male")
                        val gmAll = gmSpec?.optJSONArray("all")
                        var gm = 0f
                        if (latestWindow != null && gmAll != null) {
                            var ok = true
                            for (i in 0 until gmAll.length()) {
                                val cond = gmAll.getJSONObject(i)
                                val metric = cond.getString("metric")
                                val op = cond.getString("op")
                                val value = cond.getDouble("value").toFloat()
                                val v = when (metric) {
                                    "VTL" -> latestWindow!!.vtlDeltaF
                                    "deltaF" -> latestWindow!!.deltaF
                                    else -> Float.NaN
                                }
                                ok = ok && when (op) {
                                    ">=", "=>" -> v >= value
                                    "<=", "=<" -> v <= value
                                    ">" -> v > value
                                    "<" -> v < value
                                    "==" -> kotlin.math.abs(v - value) < 1e-3
                                    else -> false
                                }
                                if (!ok) break
                            }
                            gm = if (ok) 1f else gmSpec?.optDouble("else", 0.0)?.toFloat() ?: 0f
                        }
                        val k2 = params?.optDouble("k2", 0.10)?.toFloat() ?: 0.10f
                        val shift = (k1 * yLow + k2 * gm)
                        x01 = (x01 - shift).coerceIn(0f,1f)
                    }
                } catch (_: Throwable) {}
                // Hard floor: ensure minimum X when all conditions met
                try {
                    val root = org.json.JSONObject(this.assets.open("config.json").bufferedReader().use { it.readText() })
                    val rx = root.optJSONObject("resonance_axis")
                    val hf = rx?.optJSONObject("hard_floor")
                    if (hf != null && hf.optBoolean("enabled", false)) {
                        val rules = hf.optJSONArray("x_min_when")
                        if (rules != null) {
                            for (i in 0 until rules.length()) {
                                val rule = rules.getJSONObject(i)
                                val all = rule.optJSONArray("all")
                                var ok = latestWindow != null
                                if (ok && all != null) {
                                    for (j in 0 until all.length()) {
                                        val cond = all.getJSONObject(j)
                                        val metric = cond.getString("metric")
                                        val op = cond.getString("op")
                                        val value = cond.getDouble("value").toFloat()
                                        val v = when (metric) {
                                            "F0" -> f0Hz
                                            "SC" -> latestWindow!!.scHz
                                            "EHF_LF" -> latestWindow!!.ehfOverElf
                                            else -> Float.NaN
                                        }
                                        ok = ok && when (op) {
                                            ">=", "=>" -> v >= value
                                            "<=", "=<" -> v <= value
                                            ">" -> v > value
                                            "<" -> v < value
                                            "==" -> kotlin.math.abs(v - value) < 1e-3
                                            else -> false
                                        }
                                        if (!ok) break
                                    }
                                }
                                if (ok) {
                                    val xmin = rule.optDouble("x_min", 0.0).toFloat()
                                    x01 = kotlin.math.max(x01, xmin)
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
                runOnUiThread {
                    fieldView.setPoint(x01, pitch01)
                    // Logging with mapped pixels and chart rect
                    val p01 = fieldView.getPoint01()
                    val px = fieldView.map01ToPx(p01.first, p01.second)
                    val r = fieldView.getChartRect()
                    logger.log(
                        t = System.currentTimeMillis() / 1000.0,
                        pitchHz = f0Hz,
                        resonance01 = x01,
                        x01 = p01.first,
                        y01 = p01.second,
                        px = px.first,
                        py = px.second,
                        chartL = r.left,
                        chartT = r.top,
                        chartW = r.width(),
                        chartH = r.height(),
                        vw = fieldView.width,
                        vh = fieldView.height,
                        bHf = bm?.second?.bHf,
                        bSc = bm?.second?.bSc,
                        bVtl = bm?.second?.bVtl,
                        bDf = bm?.second?.bDf,
                        bH12 = bm?.second?.bH12,
                        r = bm?.second?.r
                    )
                    recorder.onPoint(x01, pitch01, confidence)
                    val cls = classify(x01, pitch01)
                    applyCategoryWithHysteresis(cls)
                }
            },
            onFrame = { x, n, f0, conf, f1, f2, f3, res01 ->
                windowAnalyzer?.addFrame(x, n, f0, conf, f1, f2, f3, res01)
            }
        )
        windowAnalyzer = FeatureWindowAnalyzer(sampleRate = 44100, onWindow = { wf ->
            // Show compact summary on overlay
            overlay.statsExtra = "ΔF=${wf.deltaF.toInt()}Hz, VTL=${String.format("%.1f", wf.vtlDeltaF)}cm, PR=${String.format("%.1f", wf.prosodyRangeSt)}st, SC=${wf.scHz.toInt()}Hz | L:${wf.decision.lowF0Count}/7 ${if (wf.decision.lowF0Hit) "✓" else ""} H:${wf.decision.highF0Count}/7 ${if (wf.decision.highF0Hit) "✓" else ""}"
            // Detailed brightness breakdown if enabled
            if (cfg.resAxis?.useBrightnessForX == true) {
                fun norm(v: Float, a: Float, b: Float): Float { if (b <= a) return 0f; return ((v - a) / (b - a)).coerceIn(0f,1f) }
                val r = cfg.resAxis!!.ranges; val w = cfg.resAxis!!.weights
                val b_hf  = norm(wf.ehfOverElf, r.hfLfMin, r.hfLfMax)
                val b_sc  = norm(wf.scHz, r.scMinHz, r.scMaxHz)
                val b_vtl = norm(1f / wf.vtlDeltaF, 1f / r.vtlMaxCm, 1f / r.vtlMinCm)
                val b_df  = norm(wf.deltaF, r.deltaFMinHz, r.deltaFMaxHz)
                val b_h12 = norm(kotlin.math.max(0f, wf.h1MinusH2), r.h1h2MinDb, r.h1h2MaxDb)
                val R = (w.hfLf*b_hf + w.sc*b_sc + w.vtlInv*b_vtl + w.deltaF*b_df + w.h1h2*b_h12).coerceIn(0f,1f)
                overlay.liveLines = listOf(
                    "F0 n=${wf.f0Valid.size} | SC=${wf.scHz.toInt()}Hz EHF/LF=${String.format("%.2f", wf.ehfOverElf)}",
                    "b_hf=${String.format("%.2f", b_hf)} b_sc=${String.format("%.2f", b_sc)} b_vtl=${String.format("%.2f", b_vtl)}",
                    "b_df=${String.format("%.2f", b_df)} b_h12=${String.format("%.2f", b_h12)} R=${String.format("%.2f", R)} X=${String.format("%.2f", R)}"
                )
            } else {
                overlay.liveLines = listOf(
                    "F0 valid n=${wf.f0Valid.size}",
                    "F1=${wf.f1.toInt()} F2=${wf.f2.toInt()} F3=${wf.f3.toInt()} Hz",
                    "EHF/LF=${String.format("%.2f", wf.ehfOverElf)} H1-H2=${String.format("%.1f", wf.h1MinusH2)} dB"
                )
            }
            latestWindow = wf
            windowLog.add(wf)
        }, cfg = cfg)

        // Button handlers
        btnRecord.setOnClickListener {
            if (!recorder.recording()) {
                recorder.start()
                overlay.stats = null
                overlay.debugLines = null
                status.text = "Recording…"
                btnRecord.text = "Stop"
                windowLog.clear()
            } else {
                val seq = recorder.stop()
                val st = recorder.computeStats(seq)
                overlay.stats = st
                status.text = "Recorded ${seq.size} pts"
                btnRecord.text = "Record"
                // Build debug ranges from windowLog
                if (windowLog.isNotEmpty()) {
                    val f0all = windowLog.flatMap { it.f0Valid }
                    fun Float.format1() = String.format("%.1f", this)
                    fun Float.format0() = String.format("%d", this.toInt())
                    val lines = mutableListOf<String>()
                    if (f0all.isNotEmpty()) lines += "F0: ${ (f0all.minOrNull()?:0f).format0()}..${(f0all.maxOrNull()?:0f).format0()} Hz"
                    lines += "F1: ${windowLog.minOf { it.f1 }.format0()}..${windowLog.maxOf { it.f1 }.format0()} Hz"
                    lines += "F2: ${windowLog.minOf { it.f2 }.format0()}..${windowLog.maxOf { it.f2 }.format0()} Hz"
                    lines += "F3: ${windowLog.minOf { it.f3 }.format0()}..${windowLog.maxOf { it.f3 }.format0()} Hz"
                    lines += "ΔF: ${windowLog.minOf { it.deltaF }.format0()}..${windowLog.maxOf { it.deltaF }.format0()} Hz"
                    lines += "VTL: ${windowLog.minOf { it.vtlDeltaF }.format1()}..${windowLog.maxOf { it.vtlDeltaF }.format1()} cm"
                    lines += "EHF/LF: ${windowLog.minOf { it.ehfOverElf }.format1()}..${windowLog.maxOf { it.ehfOverElf }.format1()}"
                    lines += "SC: ${windowLog.minOf { it.scHz }.format0()}..${windowLog.maxOf { it.scHz }.format0()} Hz"
                    lines += "PR: ${windowLog.minOf { it.prosodyRangeSt }.format1()}..${windowLog.maxOf { it.prosodyRangeSt }.format1()} st"
                    lines += "L-rule count: ${windowLog.minOf { it.decision.lowF0Count }}..${windowLog.maxOf { it.decision.lowF0Count }}"
                    lines += "H-rule count: ${windowLog.minOf { it.decision.highF0Count }}..${windowLog.maxOf { it.decision.highF0Count }}"
                    overlay.debugLines = lines
                }
            }
        }
        btnPlay.setOnClickListener {
            val seq = recorder.last()
            if (seq.isEmpty()) { status.text = "No data"; return@setOnClickListener }
            overlay.stats = recorder.computeStats(seq)
            status.text = "Playing…"
            recorder.play(seq, fieldView) {
                status.text = "Done"
            }
        }

        btnReset.setOnClickListener {
            // Clear trail and on-screen stats; keep audio state
            fieldView.clearTrail()
            overlay.stats = null
            overlay.debugLines = null
            overlay.liveLines = null
            overlay.categoryText = null
            recorder.clear()
            status.text = "Cleared"
        }

        btnShare.setOnClickListener {
            // Ensure file exists; fallback to latest from logs dir
            logger.flush()
            val f = logger.currentFile() ?: logger.latestExisting()
            if (f == null || !f.exists()) { status.text = "No log yet"; return@setOnClickListener }
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    this.packageName + ".fileprovider",
                    f
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/*"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Voice map log")
                    putExtra(android.content.Intent.EXTRA_TEXT, f.name)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(android.content.Intent.createChooser(intent, "Share log"))
            } catch (e: Throwable) {
                status.text = "Share failed: ${'$'}{e.message}"
            }
        }
    }

    private fun trySetPitchRangeFromVoiceCsv() {
        // Supports Kaggle Voice Gender (voice.csv) with columns including meanfun (kHz) and label
        try {
            val am = assets
            val exists = am.list("")?.any { it.equals("voice.csv", ignoreCase = true) } == true
            if (!exists) return
            val lines = am.open("voice.csv").bufferedReader().use { it.readLines() }
            if (lines.isEmpty()) return
            val header = lines.first().split(',')
            val idxMeanfun = header.indexOfFirst { it.trim().equals("meanfun", ignoreCase = true) }
            val idxLabel = header.indexOfFirst { it.trim().equals("label", ignoreCase = true) }
            if (idxMeanfun < 0 || idxLabel < 0) return
            val male = ArrayList<Float>()
            val female = ArrayList<Float>()
            for (i in 1 until lines.size) {
                val row = lines[i].split(',')
                if (row.size <= idxLabel || row.size <= idxMeanfun) continue
                val mf = row[idxMeanfun].toFloatOrNull() ?: continue
                val hz = (mf * 1000f) // dataset uses kHz; convert to Hz
                when (row[idxLabel].trim().lowercase()) {
                    "male" -> male.add(hz)
                    "female" -> female.add(hz)
                }
            }
            fun pct(ls: List<Float>, p: Float): Float? {
                if (ls.isEmpty()) return null
                val s = ls.sorted()
                val idx = ((s.size - 1) * p).coerceIn(0f, (s.size - 1).toFloat())
                val i0 = idx.toInt()
                val i1 = kotlin.math.min(i0 + 1, s.lastIndex)
                val t = idx - i0
                return s[i0] * (1 - t) + s[i1] * t
            }
            val all = (male + female)
            if (all.size < 10) return
            // Robust range: 5th..95th percentile of all samples
            val minHz = pct(all, 0.05f) ?: return
            val maxHz = pct(all, 0.95f) ?: return
            PitchToGender.setRange(minHz, maxHz)
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun classify(xRes01: Float, yPitch01: Float): String {
        // If mapping provides male/female anchors and ellipse radii, classify by proximity
        svgMapping?.let { m ->
            val a = m.anchors
            val r = m.radii
            if (a != null && r != null) {
                // yPitch01 is 0..1 with top=1; anchors use y normalized (top high): mapping’s y appears to be top-high already.
                val dxM = (xRes01 - a.maleX) / (r.maleRx.coerceAtLeast(1e-3f))
                val dyM = ((yPitch01) - a.maleY) / (r.maleRy.coerceAtLeast(1e-3f))
                val d2M = dxM*dxM + dyM*dyM
                val dxF = (xRes01 - a.femaleX) / (r.femaleRx.coerceAtLeast(1e-3f))
                val dyF = ((yPitch01) - a.femaleY) / (r.femaleRy.coerceAtLeast(1e-3f))
                val d2F = dxF*dxF + dyF*dyF
                // Androgynous band near the mid-line when scores are similar
                val ratio = if (d2M > 0f && d2F > 0f) (kotlin.math.min(d2M, d2F) / kotlin.math.max(d2M, d2F)) else 0f
                return when {
                    ratio > 0.7f -> "androgynous"
                    d2M <= d2F -> "male"
                    else -> "female"
                }
            }
        }
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

    private fun applyCategoryWithHysteresis(newCls: String) {
        val win = cfg.score?.hysteresisWindows ?: cfg.zones.hysteresisWindows
        recentClasses.addLast(newCls)
        while (recentClasses.size > win) recentClasses.removeFirst()
        val counts = recentClasses.groupingBy { it }.eachCount()
        val stable = counts.maxByOrNull { it.value }?.key ?: newCls
        overlay.categoryText = when (stable) {
            "male" -> "Male"
            "androgynous" -> "Androgynous"
            else -> "Female"
        }
    }

    override fun onStart() {
        super.onStart()
        ensurePermissionAndStart()
    }

    override fun onStop() {
        super.onStop()
        stopAudio()
    }

    private fun ensurePermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startAudio() else requestPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startAudio() {
        mic.start()
    }

    private fun stopAudio() {
        mic.stop()
        // no-op; field retains last state
        logger.stop()
    }
}
