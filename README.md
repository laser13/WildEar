# Wild Ear

![CI](https://github.com/laser13/WildEar/actions/workflows/ci.yml/badge.svg)

Wild Ear is an Android app for recording wildlife sounds, classifying them on-device, and uploading confirmed observations to iNaturalist after human review.

Download the latest APK from [GitHub Releases](https://github.com/laser13/WildEar/releases/latest).

## What It Does

- Records bird and wildlife audio on your phone.
- Runs on-device classification so you can review detections without sending audio to a server first.
- Lets you confirm observations before upload.
- Supports a release flow that publishes installable APKs on GitHub Releases.

## Install

1. Open the latest GitHub Release: [`Latest release`](https://github.com/laser13/WildEar/releases/latest).
2. Download the attached `app-release.apk` file.
3. On your Android phone, allow installs from your browser or file manager if prompted.
4. Open the downloaded APK and confirm the installation.

## Releases

Signed release APKs are published on GitHub Releases. The latest downloadable build is attached to the newest release as `app-release.apk`.

## Build

```bash
./gradlew assembleDebug --no-daemon
./gradlew assembleRelease --no-daemon
```

## Documents

- [`docs/initial-plans.md`](docs/initial-plans.md) — initial side-by-side outline of the private and public tracks. Used as input for the design spec.
- `docs/superpowers/specs/` — design specs.
- `docs/superpowers/plans/` — implementation plans.
