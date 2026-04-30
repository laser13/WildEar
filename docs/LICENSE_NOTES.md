# Sound2iNat — license notes

This document records the licenses of the third-party assets shipped or
downloaded at runtime by the Sound2iNat private MVP (Spec 1). Spec 1 is a
**private, non-public** APK; the public-track redistribution audit is out of
scope and will live in a separate document when the public track starts.

## App code

The Sound2iNat application source code (everything under `app/src/`) is © 2026
Sound2iNat contributors. License: **TBD** (no public release yet).

## Third-party Android libraries

All Android libraries are pulled from Maven Central / Google Maven via Gradle.
The full SBOM is `app/build/reports/dependencies` (run `:app:dependencies`).
Notable licenses below.

| Component | Version | License |
|---|---|---|
| Jetpack Compose UI / Material3 | per `libs.versions.toml` | Apache 2.0 |
| AndroidX (Lifecycle, Navigation, Activity, Room, DataStore) | per `libs.versions.toml` | Apache 2.0 |
| Hilt (Dagger) | 2.52 | Apache 2.0 |
| Kotlin / Kotlinx Coroutines | 2.0.20 / 1.9.x | Apache 2.0 |
| OkHttp | 4.12.0 | Apache 2.0 |
| TensorFlow Lite (Android) + Support | 2.16.1 / 0.4.4 | Apache 2.0 |
| JTransforms | 3.1 | MPL 2.0 / LGPL 2.1+ / GPL 2.0+ (multi-license) |
| Google Play Services Location | per `libs.versions.toml` | Google Play Services SDK Terms |
| JUnit / Truth / Turbine / Robolectric / Mockk / MockWebServer | various | Apache 2.0 / MIT / EPL |

## Models and labels (downloaded at runtime)

Models and labels are NOT bundled in the APK; they are downloaded into the
app's private files dir on user request from Settings. The Settings screen
exposes one section per known model (BirdNET v2.4, Perch v2) and each can be
installed/removed independently. Inference at recording time runs every
installed model in sequence over the same WAV; the Review screen surfaces the
per-model max confidence on each species row.

| Asset | Version | Source URL | SHA-256 | License |
|---|---|---|---|---|
| `BirdNET_GLOBAL_6K_V2.4_Model_FP32.tflite` | 2.4 | `https://github.com/woheller69/whoBIRD-TFlite/raw/master/BirdNET_GLOBAL_6K_V2.4_Model_FP32.tflite` | `55f3e4055b1a13bfa9a2452731d0d34f6a02d6b775a334362665892794165e4c` | **CC BY-NC-SA 4.0** (BirdNET weights — non-commercial, share-alike) |
| `labels_en_uk.txt` | matching v2.4 labels | `https://raw.githubusercontent.com/woheller69/whoBIRD/master/app/src/main/assets/labels_en_uk.txt` | `af05ad18573f6ecdd14b1e457ba9265043ad8b60ec273816660125c82690e693` | MIT (whoBIRD app, code) |
| `perch_v2.tflite` | 2 | `https://huggingface.co/justinchuby/Perch-onnx/resolve/main/perch_v2.tflite` | `15a7497851afcb26cadfb7f537e799bd0e3e0c4f87bcb5211ca3ce88418e25d4` | **Apache 2.0** (Google Perch v2 weights) |
| `labels.csv` (Perch) | matching Perch v2 | `https://huggingface.co/cgeorgiaw/Perch/resolve/main/assets/labels.csv` | `e4d5c0397d8fb08bf90c6b13a34810af53504faad927e472fcc567793c9de057` | Apache 2.0 (mirrored from Google's `cgeorgiaw/Perch` SavedModel release) |

The license string surfaced in Settings before the user confirms the download
matches each model's weights license. Verify on-device before sign-off
(Task 18 §3).

## Public-track preparation (out of scope for Spec 1)

When the project transitions to a public release:

- Replace BirdNET v2.4 with a model whose weights permit unrestricted
  redistribution, OR obtain explicit upstream permission for redistribution.
- Audit every direct and transitive dependency under `app/build/reports/dependencies`.
- Generate a NOTICE file shipped with the APK.
- Consider `R8`/Proguard minification and APK split for size.

These items are tracked in `docs/initial-plans.md` §4.2.
