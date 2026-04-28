# Sound2iNat Spec 1 (Private MVP) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Per-task review requirement (from project CLAUDE.md):** before starting any task, read the entire task description critically and list anything that is ambiguous, missing, contradictory, or wrong. If you find issues, propose fixes to the plan first and only proceed once the plan is corrected. Do not start coding while open questions remain.

**Goal:** Ship a sideloaded Android APK that records bird/wildlife sound, classifies it post-hoc with on-device BirdNET TFLite, and saves a reviewed local draft with GPS and timestamp. No upload, no live inference (those are later specs).

**Architecture:** Single-Activity Jetpack Compose app. Logical packages (`recorder`, `inference`, `modelmanager`, `storage`, `location`, `app`) inside one `:app` Gradle module. `core/*` packages do not depend on Compose/Activity. Inference runs on the foreground Review screen (not in a service); recording writes WAV; spectrograms reuse the model's mel preprocessor and are rendered with Compose `Canvas` overlays.

**Tech Stack:** Kotlin · Jetpack Compose · Hilt · Room · DataStore · Coroutines/Flow · OkHttp · JTransforms (FFT) · TensorFlow Lite Android · FusedLocationProvider · JUnit4 · MockWebServer · Roborazzi (optional, stretch).

**Spec:** [`docs/superpowers/specs/2026-04-28-sound2inat-spec1-private-mvp-design.md`](../specs/2026-04-28-sound2inat-spec1-private-mvp-design.md). The plan inherits all decisions D1–D11, the data model, the UI contract, and acceptance criteria from the spec. If the spec and the plan disagree, the spec wins — fix the plan.

---

## Task list (executed in order)

1. **Task 1 — Repo scaffold and CI.** Gradle project, Compose hello-screen, manifest, lint/detekt/ktlint, GitHub Actions.
2. **Task 2 — Model spike (Python, on laptop).** Compare BirdNET reference vs TFLite on the same files. Lock parameters. Output: `docs/private/MODEL_SPIKE.md`, frozen `birdnet_v2_4.tflite` SHA-256 and labels file.
3. **Task 3 — WAV writer and AudioRecord wrapper (`recorder`).** Pure-Kotlin WAV writer with unit tests; coroutine-based `Recorder` returning `RecordingResult`.
4. **Task 4 — Mel-spectrogram preprocessor (`inference`).** STFT via JTransforms, mel filterbank, deterministic output. Unit-tested against synthetic signals and (where possible) the spike's reference numbers.
5. **Task 5 — `DetectionAggregator` (`inference`).** Pure logic: window outputs → per-species summary.
6. **Task 6 — `BirdNetTfliteModel` (`inference`).** Loads `.tflite` via `MappedByteBuffer`, runs interpreter on each window, returns top-K. Unit-tested with a fake `Interpreter`.
7. **Task 7 — `ModelManager` (`modelmanager`).** OkHttp download to `<file>.partial`, SHA-256 verify, atomic rename, state machine, retry. Tested with `MockWebServer`.
8. **Task 8 — Storage (`storage`).** Room entities, DAOs, DB, `DraftRepository`, WAV file ops. In-memory Room tests, FK-cascade tests.
9. **Task 9 — Location (`location`).** `FusedLocationProvider` wrapper with timeout + last-known fallback. Tested with a fake provider.
10. **Task 10 — Hilt + Application + Permissions plumbing (`app`).** `@HiltAndroidApp`, modules, single Activity, Compose Navigation host, runtime-permissions abstraction (no UI yet).
11. **Task 11 — Home screen.** ViewModel + Composable. Drafts list bound to Room flow.
12. **Task 12 — Recording screen.** ViewModel orchestrates `Recorder` + `LocationProvider`. Timer, RMS meter, GPS status, Stop/Cancel.
13. **Task 13 — Review screen, base.** ViewModel. Audio player, species list with checkboxes, Save/Delete. Inference progress UI. **No** waveform/spectrogram yet.
14. **Task 14 — Review screen, waveform + spectrogram.** Cached PNG spectrogram from the same mel pipeline. Synced play-cursor over both.
15. **Task 15 — Review screen, detection overlays + interactivity.** Coloured rectangles on spectrogram, two-way tap binding with species list and player.
16. **Task 16 — Settings screen + Model install UX.** Top-K, min display confidence, model status, Install/Reinstall/Remove with license disclosure.
17. **Task 17 — End-to-end instrumented test.** One on-device test per acceptance §11 happy-path with fakes injected via Hilt test rules.
18. **Task 18 — Manual acceptance run on Poco X3 + reports.** Walk §11 (12 steps), write `docs/private/MVP_REPORT.md` and `docs/LICENSE_NOTES.md`.

Each task ends with a green build, green tests, and a single commit (or a small chain of commits inside the task with the same logical scope). Branches are not mandated; the project owner is the sole developer.

---

## Task 1 — Repo scaffold and CI

**Goal:** Empty Compose project that builds, has lint/detekt/ktlint configured, has GitHub Actions running unit tests on push, and contains an empty Hello-screen Activity. No business logic yet.

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `gradle/libs.versions.toml`
- Create: `gradle.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/sound2inat/app/MainActivity.kt`
- Create: `app/src/main/java/com/sound2inat/app/ui/HelloScreen.kt`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/test/java/com/sound2inat/app/SmokeTest.kt`
- Create: `.gitignore`
- Create: `.github/workflows/ci.yml`
- Create: `detekt.yml`

**Pre-task review (mandatory):** Read this entire task. List any ambiguity (e.g. unstated AGP/Kotlin/Compose-BoM versions, missing JDK assumption). The plan resolves these by pinning current stable in `libs.versions.toml`; if a current stable conflicts with the spec, fix the plan and mention it in the commit.

- [ ] **Step 1 — Add `.gitignore`**

```gitignore
# Android / Gradle
*.iml
.gradle/
local.properties
.idea/
.DS_Store
build/
captures/
.externalNativeBuild/
.cxx/

# Keystores
*.jks
*.keystore

# Local artifacts
/app/release/
/models/*.tflite
/models/*.partial

# Python (used by the model spike, not the app)
__pycache__/
*.pyc
.venv/
```

- [ ] **Step 2 — Pin versions in `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.6.1"
kotlin = "2.0.20"
ksp = "2.0.20-1.0.25"
hilt = "2.52"
hiltNavigation = "1.2.0"
composeBom = "2024.09.03"
activityCompose = "1.9.2"
lifecycle = "2.8.6"
navigation = "2.8.2"
room = "2.6.1"
datastore = "1.1.1"
coroutines = "1.9.0"
okhttp = "4.12.0"
jtransforms = "3.1"
tflite = "2.16.1"
playLocation = "21.3.0"
material3 = "1.3.0"
junit = "4.13.2"
mockk = "1.13.12"
truth = "1.4.4"
turbine = "1.1.0"
mockwebserver = "4.12.0"
robolectric = "4.13"
coreTesting = "2.2.0"
detekt = "1.23.7"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version = "1.13.1" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-material3 = { module = "androidx.compose.material3:material3", version.ref = "material3" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-compiler", version.ref = "hilt" }
hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version.ref = "hiltNavigation" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "mockwebserver" }
jtransforms = { module = "com.github.wendykierp:JTransforms", version.ref = "jtransforms" }
tflite = { module = "org.tensorflow:tensorflow-lite", version.ref = "tflite" }
tflite-support = { module = "org.tensorflow:tensorflow-lite-support", version.ref = "tflite" }
play-services-location = { module = "com.google.android.gms:play-services-location", version.ref = "playLocation" }
junit = { module = "junit:junit", version.ref = "junit" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
truth = { module = "com.google.truth:truth", version.ref = "truth" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-core-testing = { module = "androidx.arch.core:core-testing", version.ref = "coreTesting" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
detekt = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
```

- [ ] **Step 3 — `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "sound2inat"
include(":app")
```

- [ ] **Step 4 — Root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt)
}

detekt {
    config.setFrom(files("$rootDir/detekt.yml"))
    buildUponDefaultConfig = true
    autoCorrect = false
}
```

- [ ] **Step 5 — `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
android.nonFinalResIds=true
```

- [ ] **Step 6 — `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.sound2inat.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sound2inat.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 10000
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1"
        )
    }

    room {
        schemaDirectory("$projectDir/schemas")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.jtransforms)
    implementation(libs.tflite)
    implementation(libs.tflite.support)
    implementation(libs.play.services.location)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.core.testing)
    testImplementation(libs.room.testing)

    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.7")
}
```

> NOTE: the `room { schemaDirectory(...) }` block requires the Room Gradle plugin, which is bundled with the Room artifact via KSP — it works in AGP 8.6+. If your AGP version rejects it, replace with `defaultConfig { javaCompileOptions { annotationProcessorOptions { arguments["room.schemaLocation"] = "$projectDir/schemas" } } }` and report this back as a plan correction.

- [ ] **Step 7 — `AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name=".Sound2iNatApp"
        android:label="@string/app_name"
        android:theme="@style/Theme.Sound2iNat"
        android:allowBackup="false"
        android:supportsRtl="true">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 8 — Stub `Sound2iNatApp` and `MainActivity` + `HelloScreen`**

`app/src/main/java/com/sound2inat/app/Sound2iNatApp.kt`:

```kotlin
package com.sound2inat.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class Sound2iNatApp : Application()
```

`MainActivity.kt`:

```kotlin
package com.sound2inat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.sound2inat.app.ui.HelloScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { Surface { HelloScreen() } }
        }
    }
}
```

`ui/HelloScreen.kt`:

```kotlin
package com.sound2inat.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun HelloScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "sound2inat — scaffold OK")
    }
}
```

- [ ] **Step 9 — `strings.xml`, `themes.xml`**

`strings.xml`:

```xml
<resources>
    <string name="app_name">Sound2iNat</string>
</resources>
```

`themes.xml`:

```xml
<resources>
    <style name="Theme.Sound2iNat" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 10 — Add a smoke unit test**

`app/src/test/java/com/sound2inat/app/SmokeTest.kt`:

```kotlin
package com.sound2inat.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SmokeTest {
    @Test fun `assertions work`() {
        assertThat(1 + 1).isEqualTo(2)
    }
}
```

- [ ] **Step 11 — Add `detekt.yml` (minimal)**

```yaml
build:
  maxIssues: 0
formatting:
  active: true
  android: true
  autoCorrect: true
complexity:
  TooManyFunctions:
    active: false
style:
  MagicNumber:
    active: false
  ReturnCount:
    active: false
```

- [ ] **Step 12 — Run unit tests locally**

```
./gradlew testDebugUnitTest
```

Expected: BUILD SUCCESSFUL with 1 test passing.

- [ ] **Step 13 — Run lint and detekt locally**

```
./gradlew detekt lint
```

Expected: BUILD SUCCESSFUL with no issues.

- [ ] **Step 14 — Run `assembleDebug`**

```
./gradlew assembleDebug
```

Expected: `app/build/outputs/apk/debug/app-debug.apk` produced. APK size is allowed to be large at this stage.

- [ ] **Step 15 — Add GitHub Actions CI**

`.github/workflows/ci.yml`:

```yaml
name: CI
on:
  push:
    branches: ['**']
  pull_request:
    branches: ['**']

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle
      - uses: gradle/actions/setup-gradle@v3
      - name: Detekt + Lint
        run: ./gradlew detekt lint --no-daemon
      - name: Unit tests
        run: ./gradlew testDebugUnitTest --no-daemon
      - name: Assemble debug
        run: ./gradlew assembleDebug --no-daemon
```

- [ ] **Step 16 — Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties \
        gradle/libs.versions.toml app/build.gradle.kts app/src \
        detekt.yml .github/workflows/ci.yml
git commit -m "chore(scaffold): Compose + Hilt skeleton, lint/detekt, CI"
git push
```

Acceptance for Task 1: green CI run on the pushed branch; `app-debug.apk` installs on Poco X3 and shows "sound2inat — scaffold OK".

---

## Task 2 — Model spike (Python, on laptop)

**Goal:** Decide and lock the BirdNET TFLite artifact + parameters used by the Android pipeline. Capture exact preprocessing parameters, expected confidence outputs, and a frozen sample manifest. Output: `docs/private/MODEL_SPIKE.md`, `models/birdnet_v2_4.tflite` reference SHA-256, `models/labels.txt` reference SHA-256, and 3 fixture WAVs that will be re-used by Android tests later.

**Files:**
- Create: `docs/private/MODEL_SPIKE.md`
- Create: `scripts/spike/run_birdnet_reference.py`
- Create: `scripts/spike/run_birdnet_tflite.py`
- Create: `scripts/spike/compare.py`
- Create: `scripts/spike/requirements.txt`
- Create: `scripts/spike/README.md`
- Create (testdata): `app/src/test/resources/spike_fixtures/<3 short WAVs>` — checked into repo (each ≤500 KB).

**Pre-task review:** Read this entire task. Confirm: (a) you have a Python ≥ 3.10 available, (b) you can fetch BirdNET-Analyzer's TFLite weights and labels (public release on GitHub, MIT code / CC BY-NC-SA 4.0 weights), (c) the laptop has enough disk for `tflite-runtime` + scientific stack. If any of these fail, stop and update the plan with a workable alternative (e.g., Colab) before continuing.

- [ ] **Step 1 — Set up Python venv**

```
python3 -m venv .venv
. .venv/bin/activate
pip install -r scripts/spike/requirements.txt
```

`scripts/spike/requirements.txt`:

```
numpy==1.26.4
scipy==1.13.1
librosa==0.10.2
soundfile==0.12.1
tflite-runtime==2.14.0 ; platform_system != "Darwin"
tensorflow==2.16.1 ; platform_system == "Darwin"
```

> NOTE: `tflite-runtime` does not ship Apple Silicon wheels at the time of writing. On macOS we fall back to the full `tensorflow` package, which exposes the same `Interpreter` API. The plan includes a marker so the runner picks the right one.

- [ ] **Step 2 — Pull BirdNET weights**

```
mkdir -p models
curl -L -o models/birdnet_v2_4.tflite \
    "https://github.com/kahst/BirdNET-Analyzer/raw/main/checkpoints/V2.4/BirdNET_GLOBAL_6K_V2.4_Model_FP32.tflite"
curl -L -o models/labels.txt \
    "https://github.com/kahst/BirdNET-Analyzer/raw/main/checkpoints/V2.4/BirdNET_GLOBAL_6K_V2.4_Labels.txt"
sha256sum models/birdnet_v2_4.tflite models/labels.txt > models/SHA256SUMS
```

Record both checksums in `MODEL_SPIKE.md`. Verify the model file size is the expected ~50 MB; if the upstream URL has changed, update the plan with the resolved URL before continuing.

- [ ] **Step 3 — Pick three fixture clips**

Pick three short (5–8 s each) recordings from Xeno-Canto under permissive licences (CC0/CC-BY). Suggested species mix: one clear bird (e.g., Common Chiffchaff), one mixed/multi-species, one with non-bird noise. Save as 16-bit mono 48 kHz WAV in `app/src/test/resources/spike_fixtures/`. Store the source URLs and licences in `MODEL_SPIKE.md`.

- [ ] **Step 4 — Reference run (Python BirdNET-Analyzer)**

`scripts/spike/run_birdnet_reference.py` — uses the upstream BirdNET-Analyzer Python repo as a black-box reference. It can be cloned alongside `sound2inat`:

```python
"""Run BirdNET-Analyzer reference on fixture clips. Outputs JSON per clip."""
from __future__ import annotations
import json
import subprocess
import sys
from pathlib import Path

ANALYZER_DIR = Path(sys.argv[1]).resolve()  # path to a clone of BirdNET-Analyzer
FIXTURES = Path("app/src/test/resources/spike_fixtures").resolve()
OUT = Path("scripts/spike/out/reference"); OUT.mkdir(parents=True, exist_ok=True)

for wav in sorted(FIXTURES.glob("*.wav")):
    out_dir = OUT / wav.stem
    out_dir.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            sys.executable, "-m", "birdnet_analyzer.analyze",
            "--i", str(wav),
            "--o", str(out_dir),
            "--rtype", "csv",
            "--threads", "2",
            "--min_conf", "0.05",
        ],
        cwd=str(ANALYZER_DIR),
        check=True,
    )
```

Run it once:

```
git clone https://github.com/kahst/BirdNET-Analyzer.git ../BirdNET-Analyzer
python scripts/spike/run_birdnet_reference.py ../BirdNET-Analyzer
```

- [ ] **Step 5 — TFLite run from Python**

`scripts/spike/run_birdnet_tflite.py`:

```python
"""Run BirdNET v2.4 TFLite directly with the same preprocessing we'll mirror in Kotlin.

The output is a JSON file per fixture with a list of (window_start_s, top5: [(label, conf)]).
"""
from __future__ import annotations
import json
from pathlib import Path

import numpy as np
import soundfile as sf

try:
    import tflite_runtime.interpreter as tflite_lib
    Interpreter = tflite_lib.Interpreter
except ImportError:
    import tensorflow as tf
    Interpreter = tf.lite.Interpreter

MODEL = Path("models/birdnet_v2_4.tflite")
LABELS = Path("models/labels.txt").read_text().splitlines()
FIXTURES = Path("app/src/test/resources/spike_fixtures")
OUT = Path("scripts/spike/out/tflite"); OUT.mkdir(parents=True, exist_ok=True)

WINDOW_S = 3.0
HOP_S = 1.0
SR = 48_000

def windows(samples: np.ndarray, sr: int) -> list[np.ndarray]:
    win = int(WINDOW_S * sr)
    hop = int(HOP_S * sr)
    starts = list(range(0, max(1, len(samples) - win + 1), hop))
    return [(s / sr, samples[s : s + win]) for s in starts]

def main() -> None:
    interp = Interpreter(model_path=str(MODEL), num_threads=2)
    interp.allocate_tensors()
    in_d = interp.get_input_details()[0]
    out_d = interp.get_output_details()[0]

    for wav in sorted(FIXTURES.glob("*.wav")):
        samples, sr = sf.read(str(wav), dtype="float32")
        assert sr == SR, f"expected {SR} Hz, got {sr}"
        if samples.ndim > 1:
            samples = samples.mean(axis=1)
        results = []
        for start_s, frame in windows(samples, sr):
            x = np.asarray(frame, dtype=np.float32).reshape(in_d["shape"])
            interp.set_tensor(in_d["index"], x)
            interp.invoke()
            probs = interp.get_tensor(out_d["index"])[0]
            top = np.argsort(probs)[-5:][::-1]
            results.append({
                "start_s": float(start_s),
                "top5": [{"label": LABELS[i], "conf": float(probs[i])} for i in top],
            })
        OUT.joinpath(f"{wav.stem}.json").write_text(json.dumps(results, indent=2))

if __name__ == "__main__":
    main()
```

Run:

```
python scripts/spike/run_birdnet_tflite.py
```

Expected: a `*.json` per fixture in `scripts/spike/out/tflite/`. If the model expects raw audio (not mel features) directly — confirm by reading `interp.get_input_details()` and document the expectation. If the model rejects the raw waveform input, the TFLite artifact requires mel features computed externally; document that in `MODEL_SPIKE.md` and adjust Task 4 accordingly.

- [ ] **Step 6 — Compare reference vs TFLite**

`scripts/spike/compare.py`:

```python
"""Compute top-1 confidence delta and top-5 set overlap per window."""
from __future__ import annotations
import csv
import json
from pathlib import Path

REF = Path("scripts/spike/out/reference")
TFL = Path("scripts/spike/out/tflite")

def load_reference(stem: str) -> dict[float, list[tuple[str, float]]]:
    csv_path = next((REF / stem).glob("*.csv"))
    rows = list(csv.DictReader(csv_path.open()))
    by_window: dict[float, list[tuple[str, float]]] = {}
    for r in rows:
        start = float(r["Start (s)"])
        by_window.setdefault(start, []).append(
            (r["Common name"] + " | " + r["Scientific name"], float(r["Confidence"])),
        )
    return by_window

def load_tflite(stem: str) -> dict[float, list[tuple[str, float]]]:
    data = json.loads((TFL / f"{stem}.json").read_text())
    return {
        round(item["start_s"], 3): [(t["label"], t["conf"]) for t in item["top5"]]
        for item in data
    }

def main() -> None:
    for stem_path in TFL.glob("*.json"):
        stem = stem_path.stem
        ref = load_reference(stem)
        tfl = load_tflite(stem)
        print(f"=== {stem} ===")
        for start, ref_top in sorted(ref.items()):
            tfl_top = tfl.get(round(start, 3), [])
            ref_set = {l for l, _ in ref_top[:5]}
            tfl_set = {l for l, _ in tfl_top[:5]}
            overlap = len(ref_set & tfl_set) / max(1, len(ref_set))
            ref_top1 = ref_top[0][1] if ref_top else 0.0
            tfl_top1 = tfl_top[0][1] if tfl_top else 0.0
            print(f"  t={start:5.2f}s  Δtop1={tfl_top1 - ref_top1:+.3f}  "
                  f"top5_overlap={overlap:.0%}")

if __name__ == "__main__":
    main()
```

Acceptance gate (per spec §8): top-1 confidence delta within 0.05; top-5 overlap ≥ 80% averaged across windows. If the gate fails, do not proceed to Android implementation — investigate label format (BirdNET-Analyzer uses `"Common Name_Scientific Name"` — adapt the comparator to a normalised key) and rerun. Document final numbers in `MODEL_SPIKE.md`.

- [ ] **Step 7 — Write `docs/private/MODEL_SPIKE.md`**

The document MUST contain:

1. **Frozen artifact**: model URL, labels URL, both SHA-256 sums, model file size, model expected input shape (from `interp.get_input_details()`), output shape, sample rate (48000), window length (3.0 s), hop (1.0 s).
2. **Fixture manifest**: 3 entries, each with source URL, licence, target species (if known), duration.
3. **Comparison results**: a small table of top-1 delta and top-5 overlap per window per fixture; conclude with avg numbers.
4. **Decision**: "BirdNET v2.4 TFLite is locked for Spec 1" or, if the gate failed, an explicit STOP with the reason.
5. **Notes for Kotlin port**: anything surprising about preprocessing (e.g., does the model take raw audio or precomputed mel; what dtype; is normalisation needed). This becomes the spec for Task 4.

- [ ] **Step 8 — Commit**

```bash
git add docs/private/MODEL_SPIKE.md scripts/spike app/src/test/resources/spike_fixtures models/SHA256SUMS
git commit -m "spike: BirdNET v2.4 TFLite reference comparison + frozen fixtures"
git push
```

Note: do NOT commit the actual `.tflite`/`labels.txt` files — they are downloaded at run time and gated by `.gitignore`. Only the SHA256SUMS file is committed.

Acceptance for Task 2: `MODEL_SPIKE.md` describes a passing comparison (or an explicit STOP), and Task 4 has unambiguous preprocessing parameters to implement.

---

<!-- TASKS:CURSOR -->

