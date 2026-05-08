# Sound2iNat Spec 1 — MVP acceptance report

**Status:** TEMPLATE — fill in after the manual run on Poco X3.

**Date of run:** _TBD_
**Tester:** _TBD_
**Device:** Poco X3 (model: _TBD_, Android version: _TBD_, MIUI: _TBD_)
**APK build:** `app/build/outputs/apk/debug/app-debug.apk` (size: _TBD_ MB, commit: _TBD_)
**Model:** BirdNET v2.4 (`birdnet_v2_4.tflite`, 49.3 MB, FP32, 6522 labels)

---

## §11 — 12-step acceptance scenario

Tick each line on completion. If a step fails, capture the failure mode and the
fix (or workaround) before continuing.

| # | Step | Result | Notes |
|---|---|---|---|
| 1 | Install debug APK via `adb install -r app/build/outputs/apk/debug/app-debug.apk` | ☐ | |
| 2 | First launch → grant microphone + location permissions | ☐ | |
| 3 | Settings → Install model → see license sheet → confirm → reach Ready (≈49 MB) | ☐ | Time to reach Ready: _TBD_ s |
| 4 | Tap Record → Recording shows timer, RMS, GPS fix (or `No GPS` after 15 s) | ☐ | GPS fix? Y/N. Time: _TBD_ s |
| 5 | Play a known bird recording on a separate speaker → tap Stop | ☐ | Reference clip used: _TBD_ |
| 6 | Review opens immediately; species populated within ≤20 s for a 30 s clip | ☐ | Inference time: _TBD_ s |
| 7 | Waveform + mel-spectrogram render; play-cursor moves on both; overlay tap → highlight + seek; species-row tap → 800 ms overlay flash | ☐ | |
| 8 | Toggle ≥1 species → Save → Home shows `REVIEWED` row with top-1 species | ☐ | |
| 9 | Re-open the draft → saved selection still ticked | ☐ | |
| 10 | Delete from Review → row gone from Home; `<uuid>.wav` absent under `filesDir/recordings/` | ☐ | Verified via `adb shell run-as`: _TBD_ |
| 11 | Force-stop + relaunch → drafts and statuses persist | ☐ | |
| 12 | Wi-Fi + mobile data OFF → record → analyze → save end-to-end | ☐ | |

---

## Quantitative measurements

Record the values measured during the run.

| Metric | Value | How measured |
|---|---|---|
| APK size (debug) | _TBD_ MB | `ls -l app-debug.apk` |
| Inference time, 30 s clip | _TBD_ s | Stopwatch from Stop → species visible |
| Peak native heap during analysis | _TBD_ MB | Android Studio Profiler → Memory → Native |
| Spectrogram first-render latency | _TBD_ s | Stopwatch from Review open → spectrogram visible |
| Spectrogram cache hit latency (second open) | _TBD_ ms | Should be near-instant |
| Cold-start time (Home rendered) | _TBD_ ms | `adb shell am start` + wall clock |
| BirdNET v2.4 SHA-256 verified post-install | ☐ | Cached values match `MODEL_SPIKE.md` |

---

## Issues found and fixes

List every defect encountered and how it was resolved before signing off.

| # | Step | Issue | Severity | Fix | Commit |
|---|---|---|---|---|---|
| 1 | _TBD_ | _TBD_ | _TBD_ | _TBD_ | _TBD_ |

---

## Sign-off

- [ ] All 12 acceptance steps pass on Poco X3.
- [ ] Quantitative table filled in with real numbers.
- [ ] All discovered defects are either fixed or documented as known limitations.
- [ ] `LICENSE_NOTES.md` matches the in-app license string for BirdNET v2.4.

**Tester signature:** _TBD_
**Date:** _TBD_
