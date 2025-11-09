GenderColorVoice (Android)

What it does
- Captures microphone audio in real time.
- Estimates fundamental frequency (pitch) with a lightweight autocorrelation (Y‑axis).
- Estimates resonance/brightness (X‑axis) via logistic regression on spectral features.
- Displays a point on the field (X = resonance, Y = pitch) and colors the background.

How to run
1) Open the folder in Android Studio (Giraffe+ recommended).
2) Let Gradle sync; build and run on a device.
3) Grant microphone permission when asked.

Extras
- Bottom bar shows debug stats and two buttons: Reset and Share log.
- Noise Reduction slider (NR) at the very bottom:
  - 0–100% controls how aggressively background noise is ignored.
  - “active” means the pointer is moving (signal above the threshold), “inactive” — frozen (below threshold).
