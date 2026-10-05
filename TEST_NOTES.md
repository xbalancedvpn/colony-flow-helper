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
