# Review Settings Surface Design

## Goal

Make the review screen feel lighter and more intentional by moving configuration controls out of the main card surface and into a single settings sheet, while preserving the current one-profile workflow:

- the top row becomes cleaner
- playback stays one-tap
- audio and visual tuning remain easy to reach
- the recording metadata stays visible where the user first looks

## What Changes

### Top Bar

- Remove the `Review` title text.
- Keep the back arrow on the left.
- Move the recording metadata into the title area:
  - first line: formatted date and time
  - second line: GPS coordinates when available
  - fallback text for missing location

### Control Row

- Make `Play` icon-only instead of a text button.
- Keep the existing `Share` and `Download` icons.
- Add a `Settings` icon at the end of the row.
- `Settings` opens the configuration sheet.

### Settings Sheet

Use one bottom sheet with two tabs:

- `Audio`
- `Visual`

The sheet should keep draft state locally and apply changes only when the user taps `Apply`.

#### Audio Tab

Keeps the current audio-processing profile controls:

- `Orig`
- `Clean`
- `Clear`
- `Boost`
- high-pass choices
- gain choices
- normalize toggle

These settings continue to control playback, reanalysis, and upload as a single shared profile.

#### Visual Tab

Moves the spectrogram display controls out of the main card:

- palette selection
- frequency range selection
- visual gain / contrast controls

These settings remain preview-only and should not change the underlying audio.

## Recommended Architecture

Use one settings surface, not two separate dialogs.

Implementation shape:

- `ReviewScreen` owns a local `settingsSheetVisible` flag and the currently selected tab.
- `ReviewProcessingBottomSheet` becomes a tabbed sheet with local draft state.
- `ReviewViewModel` keeps the current processing profile and spectrogram config as the source of truth after `Apply`.
- The main review card keeps only the essential viewing and playback surface.

This is the simplest option that keeps the workflow consistent and avoids reintroducing inline control clutter.

## Alternatives Considered

### Separate audio and visual sheets

Pros:

- clearer separation of concerns
- each sheet can stay short

Cons:

- more taps to reach the right control
- the UI feels fragmented

### Keep controls inline on the card

Pros:

- fastest to implement
- no new sheet navigation

Cons:

- the screen stays visually noisy
- controls compete with playback and the spectrogram

The single tabbed settings sheet is the best tradeoff for this screen.

## Expected Behavior

- Tapping `Settings` opens the sheet.
- Switching tabs does not apply changes immediately.
- `Apply` commits the current draft settings and rebuilds the preview as needed.
- `Cancel` dismisses the sheet without changing the active profile.
- `Play` remains immediate and uses the active profile.
- The header now communicates recording metadata instead of repeating the screen name.

## Acceptance Criteria

- The main review screen no longer shows a `Review` title.
- Date/time and GPS are visible in the header area.
- `Play` is icon-only.
- `Settings` appears alongside `Share` and `Download`.
- The settings UI is a single bottom sheet with `Audio` and `Visual` tabs.
- Existing review behavior still works after applying settings.

