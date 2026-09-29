# Chinaskar v1 Easter Egg — Trigger Spec

**Trigger: rapid-tap the Helios logo 5 times within 2 seconds.**

- Where: the Helios launcher/logo mark (wherever the parent wires it — e.g.
  the logo in the app's home/hub header).
- What: 5 consecutive taps on the logo, all inside a 2-second sliding window,
  opens `com.omni.app.gamemaker.easteregg.ChinaskarV1Activity`.
- A 6th+ tap inside the same burst does nothing extra (the detector resets
  after firing); a fresh 5-tap burst re-opens the egg.

## Wiring (parent's job — no existing file was touched)

1. Declare the activity in `AndroidManifest.xml`:
   ```xml
   <activity
       android:name="com.omni.app.gamemaker.easteregg.ChinaskarV1Activity"
       android:exported="false"
       android:configChanges="orientation|screenSize|keyboardHidden" />
   ```
2. On the Helios logo composable:
   ```kotlin
   val onLogoTap = rememberEasterEggTapDetector {
       context.startActivity(Intent(context, ChinaskarV1Activity::class.java))
   }
   IconButton(onClick = onLogoTap) { /* Helios logo */ }
   ```
   (`rememberEasterEggTapDetector` lives in this package; the plain
   `HeliosLogoTapDetector` class works for non-Compose wiring too.)

## Inside the egg

- The game renders with an on-screen label:
  **"CHINASKAR v1 — the original top-down. Easter egg."**
- A small ✕ button (top-right, inside the WebView page) closes the egg.
- System back also exits (standard activity back behavior).
