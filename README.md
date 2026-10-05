# CF Helper v0.1

A separate Android overlay helper for Colony Flow. It does not patch Colony Flow and does not auto-tap the game.

## What v0.1 does

- Starts a movable `CF` floating bubble over the game.
- Uses Android MediaProjection only after the user grants screen-capture permission.
- Hides its own overlay before each capture so it does not contaminate the screenshot.
- Reads the four currently visible Colony Flow box numbers with on-device ML Kit OCR.
- Samples the 36 x 36 board and groups tile colors locally on the phone.
- Detects open paths from the board edge and previously cleared cells.
- Estimates how many tiles of each visible box color are reachable right now.
- Marks a visible box SAFE only when the current reachable count is at least the number on that box.
- Recommends one move, then asks for a new analysis after the board changes.

## Level 224 screenshot used for the first calibration

The supplied screenshot is game version `v1.0.75 - 7076527378` and shows these top boxes:

- Pink 16
- Green 6
- Pale yellow / cream 51
- Orange 27

The current board geometry test finds roughly 212 immediately reachable pale-yellow / cream tiles, while the other three top colors do not have enough open tiles. Therefore the expected first recommendation for that exact screenshot is:

`SAFE PICK: Cream 51` (third visible box)

The helper intentionally analyzes the screenshot instead of trusting the level number because Colony Flow updates can reshuffle level layouts.

## Build in Android Studio

1. Open this folder as an Android Studio project.
2. Let Android Studio install Android SDK 35 if it is missing.
3. Sync Gradle. The project uses Android Gradle Plugin 8.7.3 and Java 17.
4. Build `app` and install the debug APK on the Android phone.
5. Open CF Helper and tap `START FLOATING HELPER`.
6. Grant `Display over other apps` and screen capture.
7. Open Colony Flow, tap the floating `CF` bubble, then `Analyze current board`.

ML Kit text recognition is bundled through Gradle dependency `com.google.mlkit:text-recognition:16.0.1`.

## Build using GitHub Actions

The repository includes `.github/workflows/build-apk.yml`. If this folder is pushed to a GitHub repository, run the `Build Android APK` workflow. The resulting `cf-helper-debug-apk` artifact contains `app-debug.apk`.

## Current scope and limitations

- Portrait layout is tuned to the same Colony Flow UI proportions as the provided Level 224 screenshot.
- The board is currently modeled as a 36 x 36 grid.
- V0.1 is a move-by-move safety assistant, not a full multi-move optimal solver yet.
- Special mechanics such as linked boxes, locks, question-mark boxes, or unusual layouts need additional detectors in the next version.
- If no visible box can fully clear, the app may show a cautious progress pick. Re-analyze immediately after that move.
- The app never presses Colony Flow controls automatically.

## Privacy

Screenshots are processed on the device for board color analysis. OCR uses the ML Kit on-device text recognizer. CF Helper does not contain code to upload screenshots to a server.
