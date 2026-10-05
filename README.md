# CF Helper v0.2

Android overlay helper for Colony Flow. Tap the game yourself; the helper reads a screen capture and suggests one column at a time.

## New in v0.2

- Reads the 4 columns and all 3 visible rows, including each number and color.
- Detects visible vertical and horizontal connectors and treats connected blocks as one selection group.
- Reads waiting-slot numbers/colors; unreadable occupied slots remain reserved.
- Looks up to 4 clicks ahead using the visible queues and the sampled board.
- Shows **LIKELY CLEAR NOW**, **TEMPORARY PARK**, or **WAIT / RESCAN**.
- Temporary parking is recommended only when a simulated recovery exists within 2 more clicks. The recovery must also clear any newly selected helper blocks.
- Checks both collection directions to reject routes that only work with one simulated tile order.
- Shows the return route, estimated remaining quota, peak slots, and an assessment of each column.
- Keeps 1 slot free by default; uncheck that preference to allow all 5 slots.
- Shows a non-touchable column arrow for 8 seconds. Hide the panel to see the game.
- Runs planning in the background and blocks overlapping analyses.

## Install and use

1. Install the APK and open CF Helper.
2. Tap START FLOATING HELPER; grant Display over other apps and screen capture.
3. Select **Entire screen** if Android offers screen-sharing choices.
4. Open Colony Flow and wait for the board to become still.
5. Tap the CF bubble, then Analyze 3 rows + links.
6. Check that the detected queue numbers and colors match the game.
7. Tap only the recommended front block, wait for the ants, then Analyze again.

Debug builds may have a different signing certificate from an older installation. If Android refuses the update, uninstall the older CF Helper first, then install this build.

## Meaning of parking advice

A return of “2 more clicks” is an estimate in game moves, not seconds. The planner checks the slot capacity **before** sending a linked group and does not count the same tiles twice. It reserves every member of a linked group until all members finish, which is conservative if the game frees members individually.

A displayed route is a preview from this screenshot. Re-analyze after every click because the actual ant collection order, newly revealed boxes, and animations can change it.

## Scope and limitations

- Calibrated to the 36 x 36 board and 4-column portrait layout in the supplied Level 224 / v1.0.75 screenshot.
- OCR and connector detection are estimates. Read confidence refers to the visible numbers/colors, not a guarantee that a whole level is solvable.
- Only visible connector bars can be detected. Links hidden under buttons or continuing off-screen may not be verifiable.
- Unreadable numbers, off-screen linked partners that are detected, and moves without a short recovery are excluded.
- No prediction invents the color/count of an unseen fourth row.
- Not a full-level optimal solver. “No short return route” does not prove a level is impossible.
- No game patch, auto-tap, Accessibility service, or screenshot upload code.

## Verification

The planner and pixel interpretation are plain Java, so checks run without Android:

    javac -d .planner-checks app/src/main/java/com/colonyhelper/overlay/MovePlanner.java app/src/main/java/com/colonyhelper/overlay/BoardVision.java tests/PlannerChecks.java
    java -ea -cp .planner-checks com.colonyhelper.overlay.PlannerChecks

Checks cover full trays, linked capacity, shared color supply, short/unsupported parking, and covered partners. Optional local reference-image checks also cover waiting boxes, connectors, unreadable numbers, and multiple resolutions. The reference capture is not included in the repository. Reference-image tests supply known OCR token positions; they validate pixel interpretation and planning, not ML Kit accuracy on a phone.

## Build

Android Gradle Plugin 8.7.3, Gradle 8.9, Android SDK 35, Java 17. On-device OCR uses ML Kit text-recognition 16.0.1.

GitHub Actions builds on pushes to main or via Build Android APK -> Run workflow. It runs the planner checks, Android compilation, and lint, then uploads cf-helper-debug-apk containing app-debug.apk.
