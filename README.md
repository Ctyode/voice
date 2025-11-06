GenderColorVoice (Android)

What it does
- Captures microphone audio in real time.
- Estimates fundamental frequency (pitch) with a lightweight autocorrelation.
- Maps pitch to a masculinity↔femininity score and colors the whole screen accordingly.

How to run
1) Open the folder in Android Studio (Giraffe+ recommended).
2) Let Gradle sync; build and run on a device.
3) Grant microphone permission when asked.

Notes on the metric
- This initial build uses only F0 (pitch) to compute a 0..1 score where 0 is more masculine and 1 is more feminine. It interpolates on a log-frequency scale roughly between 110 Hz and 220 Hz.
- The original visualization also considers a resonance/formant axis. If you want parity, we can extend this to estimate resonance (e.g., spectral centroid) and use the full 2D palette.

Files of interest
- app/src/main/java/com/example/gendercolorvoice/MicAnalyzer.kt: audio capture + pitch detection.
- app/src/main/java/com/example/gendercolorvoice/PitchToGender.kt: pitch→score mapping.
- app/src/main/java/com/example/gendercolorvoice/ColorMapper.kt: score→color mapping.
- app/src/main/java/com/example/gendercolorvoice/SolidColorView.kt: single full-screen color view.

