package com.colonyhelper.overlay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Pixel and OCR geometry shared by Android and the reference-image checks. */
public final class BoardVision {
    private static final float[] ROW_Y = {.707f, .779f, .841f};
    private static final float[] SLOT_X = {.191f, .345f, .500f, .654f, .808f};

    public static final class Token {
        public final String text;
        public final int left, top, right, bottom;
        public Token(String text, int left, int top, int right, int bottom) {
            this.text = text; this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        int x() { return (left + right) / 2; }
        int y() { return (top + bottom) / 2; }
    }

    public static final class Reading {
        public int[] cells, palette;
        public float[] columnX;
        public MovePlanner.Box[][] columns;
        public final List<MovePlanner.Work> active = new ArrayList<>();
        public final List<String> issues = new ArrayList<>();
        public int opaqueSlots, occupiedSlots, numbersRead, presentCount, linksRead, unknownColors;
        public int gridSize, boxesRead;
        public boolean gridReliable, queueStable = true;
        public String confidence;
        public MovePlanner.Snapshot snapshot(boolean reserveOne) {
            return new MovePlanner.Snapshot(gridSize, gridSize, cells, columns, active, opaqueSlots, reserveOne);
        }
    }

    private final int width, height;
    private final int[] pixels;
    private final List<Token> tokens;
    private BoardVision(int width, int height, int[] pixels, List<Token> tokens) {
        this.width = width; this.height = height; this.pixels = pixels; this.tokens = tokens;
    }

    public static Reading read(int width, int height, int[] pixels, List<Token> tokens) {
        if (width < 300 || height < 600 || pixels.length != width * height)
            throw new IllegalArgumentException("Capture is too small or empty");
        return new BoardVision(width, height, pixels, tokens).read();
    }

    private Reading read() {
        Reading out = new Reading();
        Grid grid = detectGrid();
        out.gridSize = grid.size;
        out.gridReliable = grid.reliable;
        int[] colors = new int[grid.size * grid.size];
        boolean[] present = new boolean[colors.length];
        double left = (int) (width * .116), top = (int) (height * .158);
        double cw = grid.cellWidth, ch = grid.cellHeight;
        int background = backgroundColor();
        for (int r = 0; r < grid.size; r++) for (int c = 0; c < grid.size; c++) {
            double xa = left + c * cw, ya = top + r * ch;
            int x = (int) Math.round(xa + cw / 2), y = (int) Math.round(ya + ch / 2);
            int rgb = pixel(x, y), i = r * grid.size + c;
            colors[i] = rgb;
            double contrast = Math.max(Math.max(distance(rgb, pixel((int) Math.round(xa + cw * .12), y)),
                    distance(rgb, pixel((int) Math.round(xa + cw * .88), y))),
                    Math.max(distance(rgb, pixel(x, (int) Math.round(ya + ch * .12))),
                            distance(rgb, pixel(x, (int) Math.round(ya + ch * .88)))));
            // A neighboring tile's shadow is not another tile on the beige board floor.
            double interior = Math.max(Math.max(distance(rgb, pixel((int) Math.round(xa + cw * .32), y)),
                    distance(rgb, pixel((int) Math.round(xa + cw * .68), y))),
                    Math.max(distance(rgb, pixel(x, (int) Math.round(ya + ch * .32))),
                            distance(rgb, pixel(x, (int) Math.round(ya + ch * .68)))));
            present[i] = contrast >= 8 && interior < 38 && !looksLikeFloor(rgb, background);
            if (present[i]) out.presentCount++;
        }
        out.palette = palette(colors, present);
        out.cells = new int[colors.length];
        for (int i = 0; i < colors.length; i++) out.cells[i] = present[i] ? nearestRgb(colors[i], out.palette) : -1;

        float[] rows = ROW_Y.clone();
        // Use OCR medians to absorb small vertical shifts, without moving a row on one bad token.
        for (int r = 0; r < 3; r++) {
            List<Integer> ys = new ArrayList<>();
            for (Token token : tokens) if (number(token.text) > 0 && token.x() > width * .15 &&
                    token.x() < width * .85 && Math.abs(token.y() / (float) height - rows[r]) < .028f &&
                    token.right - token.left < width * .14) ys.add(token.y());
            if (ys.size() >= 2) { Collections.sort(ys); rows[r] = ys.get(ys.size() / 2) / (float) height; }
        }
        float[] xs = detectColumns(rows);
        out.columnX = xs;
        int nc = xs.length;
        int[] counts = new int[3 * nc], boxColors = new int[3 * nc], group = new int[3 * nc];
        boolean[] hidden = new boolean[3 * nc], boxes = new boolean[3 * nc];
        Arrays.fill(counts, -1); Arrays.fill(boxColors, -1);
        for (int r = 0; r < 3; r++) for (int c = 0; c < nc; c++) {
            int id = r * nc + c; group[id] = id;
            Token token = numberToken(xs[c], rows[r], .060f, .026f);
            boxes[id] = token != null || boxPresent(xs[c], rows[r]);
            if (!boxes[id]) continue;
            out.boxesRead++;
            if (token != null) { counts[id] = number(token.text); out.numbersRead++; }
            int rgb = faceColor(xs[c], rows[r], .056f, .024f, .012f);
            boxColors[id] = nearestHsv(rgb, out.palette);
            if (boxColors[id] < 0) out.unknownColors++;
        }

        // A connector must contrast with the nearby background, not merely be colorful.
        for (int r = 0; r < 2; r++) for (int c = 0; c < nc; c++) {
            float y = r == 0 ? rows[r] + (rows[r + 1] - rows[r]) * .62f :
                    rows[r] + (rows[r + 1] - rows[r]) * .50f;
            if (boxes[r * nc + c] && boxes[(r + 1) * nc + c] && bridge(xs[c], y, true)) {
                union(group, r * nc + c, (r + 1) * nc + c); out.linksRead++;
            }
        }
        for (int r = 0; r < 3; r++) for (int c = 0; c < nc - 1; c++) {
            float x = (xs[c] + xs[c + 1]) / 2;
            if (boxes[r * nc + c] && boxes[r * nc + c + 1] && bridge(x, rows[r], false)) {
                union(group, r * nc + c, r * nc + c + 1); out.linksRead++;
            }
        }
        // Only flag an off-screen continuation if its narrow colored bar is still visible.
        for (int c = 0; c < nc; c++) {
            float y = rows[2] + .035f;
            int continuationColor = nearestHsv(pixel((int) (xs[c] * width), (int) (y * height)), out.palette);
            if (boxes[2 * nc + c] && continuationColor == boxColors[2 * nc + c] &&
                    bridge(xs[c], y, true)) hidden[2 * nc + c] = true;
        }
        out.columns = new MovePlanner.Box[nc][];
        for (int c = 0; c < nc; c++) {
            List<MovePlanner.Box> column = new ArrayList<>();
            boolean gap = false;
            for (int r = 0; r < 3; r++) {
                int id = r * nc + c, color = boxColors[id];
                if (!boxes[id]) { gap = true; continue; }
                if (gap) out.queueStable = false;
                column.add(new MovePlanner.Box(id, r, c, color, counts[id],
                        color >= 0 ? colorName(out.palette[color]) : "Unknown color", find(group, id), hidden[id]));
            }
            out.columns[c] = column.toArray(new MovePlanner.Box[0]);
        }

        int[] slotColors = new int[5], slotCounts = new int[5], slotGroups = new int[5];
        boolean[] occupiedSlots = new boolean[5];
        Arrays.fill(slotColors, -1); Arrays.fill(slotCounts, -1);
        for (int s = 0; s < 5; s++) {
            slotGroups[s] = s;
            Token token = numberToken(SLOT_X[s], .632f, .055f, .029f);
            boolean occupied = token != null || slotColored(SLOT_X[s]);
            if (!occupied) continue;
            occupiedSlots[s] = true;
            out.occupiedSlots++;
            slotColors[s] = nearestHsv(faceColor(SLOT_X[s], .632f, .045f, .022f, .022f), out.palette);
            slotCounts[s] = token == null ? -1 : number(token.text);
        }
        for (int s = 0; s < 4; s++) if (occupiedSlots[s] && occupiedSlots[s + 1] &&
                bridge((SLOT_X[s] + SLOT_X[s + 1]) / 2, .632f, false)) union(slotGroups, s, s + 1);
        for (int s = 0; s < 5; s++) if (occupiedSlots[s]) {
            int groupId = find(slotGroups, s);
            boolean knownGroup = true;
            for (int other = 0; other < 5; other++) if (occupiedSlots[other] && find(slotGroups, other) == groupId &&
                    (slotCounts[other] <= 0 || slotColors[other] < 0)) knownGroup = false;
            if (knownGroup) out.active.add(new MovePlanner.Work(-1 - s, slotColors[s], slotCounts[s], -100 - groupId));
            else out.opaqueSlots++;
        }
        if (!out.gridReliable) out.issues.add("Tile spacing is uncertain; wait for a clear board capture and rescan.");
        if (!out.queueStable) out.issues.add("Queue rows appear to be moving; wait and rescan.");
        if (out.numbersRead < out.boxesRead) out.issues.add((out.boxesRead - out.numbersRead) + " queue number(s) unreadable; those moves are excluded.");
        if (out.opaqueSlots > 0) out.issues.add(out.opaqueSlots + " occupied slot(s) unreadable; their space stays reserved.");
        if (out.unknownColors > 0) out.issues.add("Some box colors do not match the sampled board; rescan if labels look wrong.");
        out.confidence = out.gridReliable && out.queueStable && out.numbersRead == out.boxesRead &&
                out.boxesRead > 0 && out.opaqueSlots == 0 && out.unknownColors == 0 ? "HIGH" :
                out.numbersRead >= Math.max(1, out.boxesRead * 2 / 3) ? "MEDIUM" : "LOW";
        return out;
    }

    private static final class Grid {
        int size;
        double cellWidth, cellHeight;
        boolean reliable;
    }

    private Grid detectGrid() {
        int left = (int) (width * .116), right = (int) (width * .885);
        int top = (int) (height * .158), bottom = (int) (height * .456);
        double[] px = new double[right - left - 1], py = new double[bottom - top - 1];
        // Regular bevel lines reveal the lattice even after parts of the picture are cleared.
        for (int x = left; x < right - 1; x++) for (int y = top; y < bottom; y += 3)
            px[x - left] += distance(pixel(x, y), pixel(x + 1, y));
        for (int y = top; y < bottom - 1; y++) for (int x = left; x < right; x += 3)
            py[y - top] += distance(pixel(x, y), pixel(x, y + 1));
        center(px); center(py);
        int n = 16; double best = -1, second = -1;
        for (int candidate = 16; candidate <= 64; candidate++) {
            double score = strength(px, candidate) + strength(py, candidate);
            if (score > best) { second = best; best = score; n = candidate; }
            else second = Math.max(second, score);
        }
        double fx = refine(px, n), fy = refine(py, n);
        Grid out = new Grid(); out.size = n;
        out.cellWidth = px.length / fx; out.cellHeight = py.length / fy;
        out.reliable = strength(px, fx) >= .28 && strength(py, fy) >= .23 && best > second * 1.15;
        return out;
    }

    private static void center(double[] profile) {
        double mean = Arrays.stream(profile).average().orElse(0);
        for (int i = 0; i < profile.length; i++) profile[i] -= mean;
    }

    private static double strength(double[] profile, double frequency) {
        double re = 0, im = 0, scale = 0;
        for (int i = 0; i < profile.length; i++) {
            double angle = 2 * Math.PI * frequency * i / profile.length;
            re += profile[i] * Math.cos(angle); im += profile[i] * Math.sin(angle);
            scale += Math.abs(profile[i]);
        }
        return scale < 1 ? 0 : Math.hypot(re, im) / scale;
    }

    private static double refine(double[] profile, int size) {
        double best = -1, frequency = size;
        for (int k = -12; k <= 12; k++) {
            double f = size + k * .05, s = strength(profile, f);
            if (s > best) { best = s; frequency = f; }
        }
        return frequency;
    }

    private int backgroundColor() {
        List<Integer> rs = new ArrayList<>(), gs = new ArrayList<>(), bs = new ArrayList<>();
        for (int y = (int) (height * .464); y < height * .474; y += 2)
            for (int x = (int) (width * .25); x < width * .75; x += 4) {
                int p = pixel(x, y); rs.add(red(p)); gs.add(green(p)); bs.add(blue(p));
            }
        Collections.sort(rs); Collections.sort(gs); Collections.sort(bs);
        return rgb(rs.get(rs.size()/2), gs.get(gs.size()/2), bs.get(bs.size()/2));
    }

    private static boolean looksLikeFloor(int color, int background) {
        int dr = red(color) - red(background), dg = green(color) - green(background), db = blue(color) - blue(background);
        return distance(color, background) <= 26 || Math.max(dr, Math.max(dg, db)) - Math.min(dr, Math.min(dg, db)) < 16;
    }

    private float[] detectColumns(float[] rows) {
        float[] best = null; double bestScore = -Double.MAX_VALUE;
        for (int n = 1; n <= 4; n++) {
            float[] xs = new float[n];
            for (int c = 0; c < n; c++) xs[c] = .5f + (c - (n - 1) / 2f) * .154f;
            double score = 0;
            for (Token t : tokens) if (number(t.text) > 0 && t.right - t.left < width * .14 &&
                    t.x() > width * .15 && t.x() < width * .85) {
                boolean inRow = false;
                for (float y : rows) if (Math.abs(t.y() / (float) height - y) < .028f) inRow = true;
                if (!inRow) continue;
                float d = 1;
                for (float x : xs) d = Math.min(d, Math.abs(t.x() / (float) width - x));
                score += d < .045f ? 8 : -8;
            }
            for (float x : xs) score += boxPresent(x, rows[0]) ? 4 : -5;
            if (score > bestScore) { bestScore = score; best = xs; }
        }
        for (int c = 0; c < best.length; c++) {
            List<Integer> coordinates = new ArrayList<>();
            for (float y : rows) {
                Token t = numberToken(best[c], y, .045f, .026f);
                if (t != null) coordinates.add(t.x());
            }
            if (coordinates.size() >= 2) {
                Collections.sort(coordinates);
                best[c] = coordinates.get(coordinates.size()/2) / (float) width;
            }
        }
        return best;
    }

    private boolean boxPresent(float x, float y) {
        int face = faceColor(x, y, .040f, .013f, .014f);
        int a = pixel((int) ((x - .077f) * width), (int) (y * height));
        int b = pixel((int) ((x + .077f) * width), (int) (y * height));
        double da = distance(face, a), db = distance(face, b);
        return Math.min(da, db) > 22 && Math.max(da, db) > 38;
    }

    /** Compare only gameplay regions: the banner ad and floating helper are outside them. */
    public static boolean isMoving(int w, int h, int[] before, int[] after) {
        if (before.length != w*h || after.length != w*h) throw new IllegalArgumentException("Invalid frame pair");
        return changed(w,h,before,after,.065,.15,.935,.58) > .002 ||
                changed(w,h,before,after,.12,.60,.86,.66) > .006 ||
                changed(w,h,before,after,.17,.676,.83,.858) > .010;
    }

    private static double changed(int w, int h, int[] a, int[] b, double l, double t, double r, double bottom) {
        int changes = 0, total = 0;
        for (int y = (int)(h*t); y < h*bottom; y++) for (int x = (int)(w*l); x < w*r; x++) {
            if (distance(a[y*w+x], b[y*w+x]) > 45) changes++;
            total++;
        }
        return total == 0 ? 0 : changes / (double) total;
    }

    private Token numberToken(float x, float y, float halfWidth, float halfHeight) {
        Token best = null;
        double bestScore = -1;
        for (Token token : tokens) {
            if (number(token.text) < 1) continue;
            float dx = Math.abs(token.x() / (float) width - x), dy = Math.abs(token.y() / (float) height - y);
            if (dx > halfWidth || dy > halfHeight || token.right - token.left > width * .14) continue;
            double score = (token.right - token.left) * (token.bottom - token.top) /
                    (1.0 + dx * 20 + dy * 30);
            if (score > bestScore) { best = token; bestScore = score; }
        }
        return best;
    }

    private static int number(String text) {
        String value = text.trim();
        if (!value.matches("[0-9]{1,3}")) return -1;
        try { int n = Integer.parseInt(value); return n > 0 && n <= 999 ? n : -1; }
        catch (NumberFormatException ex) { return -1; }
    }

    private boolean slotColored(float centerX) {
        int occupied = 0, total = 0;
        for (int y = -2; y <= 2; y++) for (int x = -3; x <= 3; x++) {
            float[] hsv = hsv(pixel((int) (width * (centerX + x * .012)), (int) (height * (.632 + y * .006))));
            if (!(hsv[1] < .10 && hsv[2] > .80)) occupied++;
            total++;
        }
        return occupied > total * .24;
    }

    private int faceColor(float cx, float cy, float rx, float above, float below) {
        List<Integer> rs = new ArrayList<>(), gs = new ArrayList<>(), bs = new ArrayList<>();
        List<Integer> rawR = new ArrayList<>(), rawG = new ArrayList<>(), rawB = new ArrayList<>();
        int left = (int) ((cx - rx) * width), right = (int) ((cx + rx) * width);
        int top = (int) ((cy - above) * height), bottom = (int) ((cy + below) * height);
        int step = Math.max(1, Math.min(right - left, bottom - top) / 26);
        for (int y = top; y < bottom; y += step) for (int x = left; x < right; x += step) {
            int rgb = pixel(x, y); float[] hsv = hsv(rgb);
            rawR.add(red(rgb)); rawG.add(green(rgb)); rawB.add(blue(rgb));
            if (hsv[2] < .34 || hsv[2] > .96 && hsv[1] < .10 || hsv[1] < .10) continue;
            rs.add(red(rgb)); gs.add(green(rgb)); bs.add(blue(rgb));
        }
        if (rs.size() < rawR.size() * .30) { rs = rawR; gs = rawG; bs = rawB; }
        if (rs.isEmpty()) return pixel((int) (cx * width), (int) (cy * height));
        Collections.sort(rs); Collections.sort(gs); Collections.sort(bs);
        int mid = rs.size() / 2;
        return rgb(rs.get(mid), gs.get(mid), bs.get(mid));
    }

    private boolean bridge(float x, float y, boolean vertical) {
        int hits = 0;
        for (int n = -2; n <= 2; n++) {
            int cx = (int) (x * width), cy = (int) (y * height);
            if (vertical) cy += n * Math.max(1, height / 900);
            else cx += n * Math.max(1, width / 500);
            int center = pixel(cx, cy);
            int a = vertical ? pixel(cx - (int) (width * .062), cy) : pixel(cx, cy - (int) (height * .026));
            int b = vertical ? pixel(cx + (int) (width * .062), cy) : pixel(cx, cy + (int) (height * .026));
            float[] hsv = hsv(center);
            if (hsv[1] > .35 && hsv[2] > .35 && distance(center, a) > 65 && distance(center, b) > 65) hits++;
        }
        return hits >= 4;
    }

    private static int find(int[] groups, int id) {
        while (groups[id] != id) { groups[id] = groups[groups[id]]; id = groups[id]; }
        return id;
    }
    private static void union(int[] groups, int a, int b) {
        int x = find(groups, a), y = find(groups, b);
        groups[Math.max(x, y)] = Math.min(x, y);
    }

    private static int[] palette(int[] colors, boolean[] present) {
        List<Integer> points = new ArrayList<>();
        for (int i = 0; i < colors.length; i++) if (present[i]) points.add(colors[i]);
        if (points.isEmpty()) return new int[0];
        List<Integer> initial = new ArrayList<>(); initial.add(points.get(0));
        while (initial.size() < 16) {
            double farthest = -1; int pick = points.get(0);
            for (int p : points) {
                double nearest = Double.MAX_VALUE;
                for (int center : initial) nearest = Math.min(nearest, distance(p, center));
                if (nearest > farthest) { farthest = nearest; pick = p; }
            }
            if (farthest < 36) break;
            initial.add(pick);
        }
        int[] centers = initial.stream().mapToInt(Integer::intValue).toArray();
        int[] assignment = new int[points.size()]; Arrays.fill(assignment, -1);
        for (int iteration = 0; iteration < 16; iteration++) {
            long[] sr = new long[centers.length], sg = new long[centers.length], sb = new long[centers.length];
            int[] n = new int[centers.length]; boolean changed = false;
            for (int i = 0; i < points.size(); i++) {
                int p = points.get(i), c = nearestRgb(p, centers);
                if (assignment[i] != c) { assignment[i] = c; changed = true; }
                sr[c] += red(p); sg[c] += green(p); sb[c] += blue(p); n[c]++;
            }
            for (int c = 0; c < centers.length; c++) if (n[c] > 0)
                centers[c] = rgb((int) (sr[c] / n[c]), (int) (sg[c] / n[c]), (int) (sb[c] / n[c]));
            if (!changed && iteration > 1) break;
        }
        return centers;
    }

    private static int nearestRgb(int rgb, int[] centers) {
        int best = -1; double d = Double.MAX_VALUE;
        for (int i = 0; i < centers.length; i++) if (distance(rgb, centers[i]) < d) {
            d = distance(rgb, centers[i]); best = i;
        }
        return best;
    }

    private static int nearestHsv(int rgb, int[] centers) {
        float[] a = hsv(rgb); double bestDistance = Double.MAX_VALUE; int best = -1;
        for (int i = 0; i < centers.length; i++) {
            float[] b = hsv(centers[i]);
            if (a[1] < .10 && b[1] > .22 || a[2] < .25 && b[2] > .35) continue;
            double dh = Math.abs(a[0] - b[0]); dh = Math.min(dh, 1 - dh);
            double d = distance(rgb, centers[i]) / 120.0 + dh * 2.5 +
                    Math.abs(a[1] - b[1]) * .20 + Math.abs(a[2] - b[2]) * .10;
            if (d < bestDistance) { best = i; bestDistance = d; }
        }
        return bestDistance <= 1.45 ? best : -1;
    }

    public static String colorName(int rgb) {
        float[] hsv = hsv(rgb); float h = hsv[0] * 360, s = hsv[1], v = hsv[2];
        if (v < .22) return "Black";
        if (s < .12) return v > .82 ? "White" : "Gray";
        if (h < 12 || h >= 348) return s < .50 ? "Pink" : "Red";
        if (h < 27) return "Orange";
        if (h < 40 && s > .70 && v > .84) return "Orange";
        if (h < 60) return v < .82 ? "Tan" : s < .58 ? "Cream" : "Yellow";
        if (h < 85) return "Lime";
        if (h < 160) return v < .62 ? "Dark green" : "Green";
        if (h < 185) return "Teal";
        if (h < 220) return "Cyan";
        if (h < 255) return "Blue";
        if (h < 310) return "Purple";
        if (h < 335 && s > .40) return "Magenta";
        return s < .52 ? "Pink" : "Red";
    }

    private int pixel(int x, int y) {
        x = Math.max(0, Math.min(width - 1, x)); y = Math.max(0, Math.min(height - 1, y));
        return pixels[y * width + x];
    }
    private static int red(int rgb) { return rgb >> 16 & 255; }
    private static int green(int rgb) { return rgb >> 8 & 255; }
    private static int blue(int rgb) { return rgb & 255; }
    private static int rgb(int r, int g, int b) { return 0xff000000 | r << 16 | g << 8 | b; }
    private static double distance(int a, int b) {
        int r = red(a) - red(b), g = green(a) - green(b), blue = blue(a) - blue(b);
        return Math.sqrt(r * r + g * g + blue * blue);
    }
    private static float[] hsv(int rgb) {
        float r = red(rgb) / 255f, g = green(rgb) / 255f, b = blue(rgb) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min, h = 0;
        if (d > 0) {
            if (max == r) h = ((g - b) / d) % 6;
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6; if (h < 0) h += 1;
        }
        return new float[]{h, max == 0 ? 0 : d / max, max};
    }
}
