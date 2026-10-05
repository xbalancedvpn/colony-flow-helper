# CF Helper v0.3

Android overlay helper for Colony Flow. The helper reads a screen capture and suggests one column at a time. Tap the game yourself.

## New in v0.3

- Detects tile spacing instead of assuming every board is 36 x 36. Validated on the supplied 36 x 36 Level 224 capture and 32 x 32 Level 225 recording.
- Estimates ant targets by walking through cleared cells from the nest, including paths around the picture perimeter. Nearby targets are prioritized; opposite tie priorities and concurrent colony orders are checked.
- Repeats collection as removed tiles open new paths, stopping at the selected box quota or when no matching target is reachable.
- Shows estimated collection and remaining count for each clickable column, plus remaining counts for readable boxes already waiting in slots.
- Detects 1-4 centered columns as depleted stacks disappear, and positions the column arrow at the detected location. Missing tail rows are omitted; unreadable or locked boxes stay unknown.
- Rejects beige floor shadows and irregular key/ant pixels as food tiles. Distinguishes magenta from red.
- Compares two gameplay frames 700 ms apart before OCR. Shows WAIT when ants or boxes are moving; banner-ad changes are ignored.

## Planning and parking

- Reads up to 3 visible queue rows, their numbers/colors, visible connector bars, and the five waiting slots.
- Connected boxes enter as one selection group; slot capacity is checked before that group enters.
- Looks up to 4 clicks ahead using the sampled board and visible queues.
- A new temporary park needs a simulated recovery within 2 more clicks. Every newly selected helper group must also finish along that route.
- Keeps 1 slot free by default. Uncheck that preference to permit all five slots.
- Shows a non-touchable arrow for 8 seconds. Hide the panel to see the game.
- Runs pixel interpretation and planning in the background, and prevents overlapping analyses.

## Install and use

1. Install the APK and open CF Helper.
2. Tap START FLOATING HELPER; grant Display over other apps and screen capture.
3. Select **Entire screen** if Android offers screen-sharing choices.
4. Open Colony Flow and wait for the ants and queue to finish moving.
5. Tap the CF bubble, then Analyze 3 rows + links.
6. Check the detected queue labels, numbers, grid size, and column positions.
7. Tap one recommended front block. Wait for the ants, then Analyze again.

Columns are numbered left to right in the current visible layout. If four columns shrink to three, the helper renumbers the three remaining visible columns.

The build caches its debug signing key for later test updates. If Android refuses an update because the signing key differs, uninstall the older helper and install the new APK.

## Meaning of the prediction

“Likely clear after ants finish” means the simulation reaches a zero count without another tap. It does not mean the animation finishes immediately. The game count can include tiles already being carried, which is why an active trip is unsuitable for a fresh prediction.

A displayed recovery of “2 more clicks” is measured in game moves, not seconds. The model shares finite tile supply across active colonies and reserves every member of a linked group until the entire group finishes. This is conservative if the game frees linked members individually.

A preview is tied to the current capture. Re-analyze after every click because actual target priority, newly revealed boxes, or covered links can change later moves.

## Scope and limitations

- Uses the supplied portrait game layout. Tile frequency searches 16-64 cells per axis and expects a square board. Only 32 x 32 and 36 x 36 were validated on actual captures.
- It is an estimate of game behavior, not the game's internal movement engine. It does not predict travel time, ant speed, exact diagonal paths, or every target reservation/order.
- A local Pink 20 comparison matched 19 of 20 predicted cleared positions. This validates that one observed batch; it is not an overall accuracy rate or a guarantee.
- The two-frame check catches visible movement. Very small changes or pauses between trips may still require the user to wait and rescan.
- OCR, geometry, and connector detection can be imperfect. Read confidence covers capture interpretation, not whether a whole level is solvable.
- Only visible connector bars can be detected. Covered or off-screen partners may be unverifiable; detected off-screen links and unreadable counts are excluded.
- Locked boxes remain unknown until their number/color is visible. Key-unlock rules and hidden fourth-row contents are not invented.
- This is a bounded planner, not a full-level optimal solver. No short return route does not prove a level is impossible.
- No game patch, auto-tap, Accessibility service, or screenshot upload code.

## Verification

The planner and pixel reader are plain Java:

    javac -d .planner-checks app/src/main/java/com/colonyhelper/overlay/MovePlanner.java app/src/main/java/com/colonyhelper/overlay/BoardVision.java tests/*.java
    java -ea -cp .planner-checks com.colonyhelper.overlay.PlannerChecks
    java -ea -cp .planner-checks com.colonyhelper.overlay.VideoChecks

The first command checks full trays, linked entry capacity, shared supply, short parking, nearby targets, perimeter access, enclosed pockets, movement regions, color labels, and synthetic grid/queue layouts. If the local Level 224 capture exists, it also checks its pixel interpretation and resizes.

VideoChecks uses local Level 225 frames to check lattice detection, recentered/sparse queues, unknown locks, movement gating, collection quotas, and the observed Pink 20 batch. Local capture checks use supplied ground-truth OCR token positions; they do not validate ML Kit accuracy on an Android phone.

Reference images and recording frames stay local in the ignored tests/fixtures folder. GitHub Actions runs the core outcome checks without those images, then Android compilation and lint, and uploads cf-helper-debug-apk.

## Build

Android Gradle Plugin 8.7.3, Gradle 8.9, Android SDK 35, Java 17. On-device OCR uses ML Kit text-recognition 16.0.1.
