# v0.3 recording calibration

Supplied recording: Level 225, game v1.0.75, 720 x 1624 portrait, approximately 324 seconds.

Observed behavior:
- The food picture uses a 32 x 32 lattice; the earlier Level 224 capture uses 36 x 36.
- Ants collect matching exposed food through cleared paths and can travel around the perimeter to the crown.
- Counts and occupied slots change while ants are carrying tiles; prediction is made after a two-frame movement check.
- Depleted columns disappear and the remaining queue recenters. Late queue rows can be empty, while lock icons represent an unreadable box rather than an absent box.
- One Pink 20 batch in the mid-level section opens both sides of the face. The nearby-target preview agrees with 19 of its 20 observed removed positions.

Changes validated against local frames:
- Adaptive lattice frequency and cell pitch, including partly cleared boards.
- Floor/shadow rejection and irregular-pixel rejection.
- Magenta/red label separation.
- Four-, three-, and two-column layouts, missing tail boxes, and unknown lock counts.
- Empty waiting trays and active/still movement checks, excluding banner ads.

The simulator approximates target order and interleaves active colonies. Exact ant routing/reservations and connected-box release behavior were not established from this recording. Existing conservative linked-group checks are retained.

Original capture and recording frames are local-only fixtures, excluded from the public repository. Their checks inject known OCR tokens; device OCR and overlay behavior still need a phone run.

## Earlier calibration history

# V0.1 calibration notes

Reference screen size: 681 x 1536 portrait.

Normalized board bounds used by the analyzer:
- left 0.116 W
- right 0.885 W
- top 0.158 H
- bottom 0.456 H

The reference image resolves cleanly as a 36 x 36 tile field. Center-color clustering finds the expected palette including cream, cyan, red, purple, teal, hot pink, light pink, orange, yellow, tan, dark green, and bright green.

The reachability model removes a target color recursively only when a tile touches the outside edge or a cleared region connected to the outside. This is intentionally conservative for slot safety.

Reference opening-state reachability includes approximately:
- cream: 212
- cyan: 144
- teal: 139
- red: 72
- purple: 55
- orange: 1
- light pink: 0
- bright green: 0

Top-row matching on the supplied Level 224 screenshot should map:
- box 1 Pink 16 -> light pink, not safe
- box 2 Green 6 -> bright green, not safe
- box 3 Cream 51 -> cream, safe
- box 4 Orange 27 -> orange, not safe
