# WildEar Android UI/UX Spec

## 1. Product goal

WildEar is an Android field-recording app for wildlife sounds. The app records environmental audio, detects possible species, lets the user review detections, and prepares selected observations for iNaturalist submission.

The app should feel like a clean field-biologist tool, not like a debug dashboard. It should be fast, calm, readable outdoors, and convenient on a phone screen.

Core flow:

```text
Record audio
→ See live detections
→ Review recording
→ Select species
→ Submit selected observations
```

The visual reference is closer to Merlin Bird ID than to a technical ML interface, but WildEar must stay broader than birds: use “wildlife”, “sounds”, and “detections”, not “birds” as the default language.

---

## 2. Design principles

### One main action per screen

Each screen should have one obvious primary action:

```text
Recordings screen → Record
Recording screen  → Stop
Review screen     → Submit selected
```

Avoid screens where delete, save, select, model details, denoise, and playback compete visually.

### Compact rows instead of large cards

Species detections should be shown as compact list rows, not giant cards. A user should see 4–6 detections on one phone screen.

### Keep numeric confidence visible

Do not hide probability. Numeric confidence is more useful than vague words such as “Likely” or “Uncertain”.

Preferred compact row:

```text
[photo] Eurasian Hoopoe              80%  ×8
        Upupa epops
```

Where:

```text
80% = model confidence
×8  = detected in 8 audio fragments
```

Use the multiplication sign `×`, not Latin `x`.

### Hide model internals by default

Do not show `BirdNET 80%`, `Perch 52%`, `YAMNet gate`, etc. in the main list.

Main UI:

```text
Eurasian Hoopoe              80%  ×8
Upupa epops
```

Details bottom sheet:

```text
BirdNET: 80%
Perch: 52%
YAMNet biological gate: passed
Fragments: 8
Time ranges: 00:12–00:15, 00:31–00:34...
```

---

## 3. Visual style

Target feeling:

```text
calm · natural · field-science · trustworthy · minimal · readable outdoors
```

Recommended style:

- Use Material 3 and Jetpack Compose.
- Prefer dark theme by default for recording screens.
- Use a dark olive/charcoal background, but reduce visual heaviness.
- Use one main accent color: soft green / mint.
- Use red only for destructive or stop actions.
- Avoid oversized labels and oversized cards.
- Avoid showing many species thumbnails in one row.
- Use rounded cards lightly, not huge blocky panels.
- Make typography smaller and more hierarchical.

Suggested typography:

```text
Screen title:          28–32sp
Timer:                 36–44sp
Species common name:   20–24sp
Latin name:            15–17sp, italic or medium gray
Metadata:              14–16sp
Confidence:            18–22sp
```

---

## 4. Recordings screen

Purpose: show saved recordings and let the user start a new one.

Current problem: cards are too tall; multiple thumbnails create clutter.

Preferred layout:

```text
WildEar                                      ⚙

Today

[mini spectrogram] Eurasian Hoopoe             1:24
                   May 3, 06:08 · 3 detections
                   Not submitted

[mini spectrogram] Common Woodpigeon           3:30
                   May 3, 06:10 · 5 detections
                   Needs review

Yesterday

[mini spectrogram] Nothing detected            0:29

                                      [🎙 Record]
```

Notes:

- One row per recording.
- Use a mini spectrogram or one thumbnail, not 3–4 species images.
- Show status: `Needs review`, `Submitted`, `Not submitted`.
- Use a floating record button at bottom-right.
- Bottom navigation may stay, but labels should not be cut off.

---

## 5. Recording screen

Purpose: record audio and show live detection.

Preferred layout:

```text
×  Recording

0:36                         GPS ±21 m

[wide spectrogram]

Listening for wildlife...

Possible matches

[photo] Collared Dove                  43%  ×1
        Streptopelia decaocto

[photo] Pale-legged Leaf Warbler        36%  ×1
        Phylloscopus tenellipes

                  [Stop]
```

If there are no detections:

```text
Listening for wildlife...
```

If analysis is delayed:

```text
Analysis catching up...
```

Avoid:

```text
Listening for birds...
```

because WildEar is not bird-only.

The stop button should be visually clear but not comically large. The current pink stop button is too large and too soft-looking. Use a standard circular red button with white square icon.

---

## 6. Review screen

Purpose: listen again, inspect spectrogram, select species, submit.

Preferred layout:

```text
Review

May 3, 06:10
Lefkoşa · 3:30 · GPS ±24 m

[▶ Play]  0:00 / 3:30        [Denoise off]

[spectrogram]

Detected wildlife

☑ Eurasian Hoopoe                  80%  ×8
  Upupa epops

☐ House Sparrow                    62%  ×11
  Passer domesticus

☐ Laughing Dove                    45%  ×3
  Streptopelia senegalensis

[Submit 1 selected]
```

Important details:

- Checkbox should be on the left, because selection is the main action.
- Confidence and fragment count should be on the right.
- Use `×8`, not `8 audio fragments`.
- Button text should change dynamically:
  - `Select species`
  - `Submit 1 selected`
  - `Submit 3 selected`

Avoid a top-right `Save` button as the main action. It is ambiguous. Use a bottom CTA.

---

## 7. Species row component

Use this component everywhere: live detections, review, history details.

Default row:

```text
[checkbox/photo] Common name              80%  ×8
                 Latin name
```

Examples:

```text
☑ Eurasian Hoopoe                         80%  ×8
  Upupa epops

☐ House Sparrow                           62%  ×11
  Passer domesticus

☐ Laughing Dove                           45%  ×3
  Streptopelia senegalensis
```

If the common name is missing, do not duplicate Latin name twice.

Bad:

```text
Passer domesticus
Passer domesticus
```

Good:

```text
House Sparrow
Passer domesticus
```

Fallback:

```text
Unknown species
Passer domesticus
```

or simply:

```text
Passer domesticus
```

---

## 8. Denoise UX

Rename `Denoise` to something less destructive:

```text
Playback denoise
```

Tooltip:

```text
Makes listening easier. Original recording is kept.
```

Do not imply that denoise improves scientific accuracy unless tested.

Possible states:

```text
Denoise off
Denoise playback
Denoise experimental
```

Default:

```text
Off for model input.
Optional for playback.
```

---

## 9. Final desired look

Review:

```text
Review

May 3, 06:10
Lefkoşa · 3:30 · GPS ±24 m

[▶ Play] 0:00 / 3:30       [Playback denoise off]

[clear spectrogram, 0–10 kHz default]

Detected wildlife

☑ Eurasian Hoopoe                         80%  ×8
  Upupa epops

☐ House Sparrow                           62%  ×11
  Passer domesticus

☐ Laughing Dove                           45%  ×3
  Streptopelia senegalensis

[Submit 1 selected]
```

Live recording:

```text
× Recording

0:36                         GPS ±21 m

[clear live spectrogram]

Listening for wildlife...

Possible matches

Collared Dove                         43%  ×1
Streptopelia decaocto

[Stop]
```

The main goal is to make the app feel simple and field-ready, while keeping useful technical numbers available without overwhelming the user.
