package com.colonyhelper.overlay;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class BoardAnalyzer {
    private static final int GRID = 36;
    private static final int K = 12;

    public interface Callback {
        void onResult(AnalysisResult result);
    }

    public static final class AnalysisResult {
        public final String headline;
        public final String detail;

        public AnalysisResult(String headline, String detail) {
            this.headline = headline;
            this.detail = detail;
        }
    }

    private static final class BoxInfo {
        int index;
        int count = -1;
        int rgb;
        int cluster = -1;
        int reachable = 0;
        String colorName = "Unknown";
    }

    private BoardAnalyzer() {}

    public static void analyze(Bitmap bitmap, Callback callback) {
        if (bitmap == null || bitmap.getWidth() < 300 || bitmap.getHeight() < 600) {
            callback.onResult(new AnalysisResult("Cannot read screen", "Capture is too small or empty."));
            return;
        }

        InputImage image = InputImage.fromBitmap(bitmap, 0);
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(image)
                .addOnSuccessListener(text -> {
                    try {
                        callback.onResult(analyzeWithText(bitmap, text));
                    } catch (Throwable t) {
                        callback.onResult(new AnalysisResult("Analysis error", t.getClass().getSimpleName() + ": " + safe(t.getMessage())));
                    } finally {
                        recognizer.close();
                    }
                })
                .addOnFailureListener(error -> {
                    recognizer.close();
                    callback.onResult(new AnalysisResult("OCR failed", "Board capture worked, but the box numbers could not be read. Tap Analyze again after the screen stops moving."));
                });
    }

    private static AnalysisResult analyzeWithText(Bitmap bitmap, Text text) {
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();

        Rect board = new Rect(
                clamp((int) (w * 0.116f), 0, w - 2),
                clamp((int) (h * 0.158f), 0, h - 2),
                clamp((int) (w * 0.885f), 2, w),
                clamp((int) (h * 0.456f), 2, h)
        );

        CellGrid grid = sampleGrid(bitmap, board);
        if (grid.presentCount < 100) {
            return new AnalysisResult("Board not detected", "Keep Colony Flow fully visible in portrait mode, then tap Analyze again.");
        }

        KMeansResult km = kMeans(grid.colors, grid.present, K);
        int[] reachableByCluster = new int[km.centers.length];
        for (int c = 0; c < km.centers.length; c++) {
            reachableByCluster[c] = countReachableForColor(grid.present, km.labels, c);
        }

        List<BoxInfo> boxes = readTopBoxes(bitmap, text, km.centers);
        int occupiedSlots = estimateOccupiedSlots(bitmap);

        List<BoxInfo> known = new ArrayList<>();
        for (BoxInfo box : boxes) {
            if (box.cluster >= 0 && box.cluster < reachableByCluster.length) {
                box.reachable = reachableByCluster[box.cluster];
            }
            if (box.count > 0 && box.cluster >= 0) {
                known.add(box);
            }
        }

        if (known.isEmpty()) {
            return new AnalysisResult("Could not read the four boxes", "Wait until the game is still, keep the helper panel closed, and tap Analyze again.");
        }

        List<BoxInfo> safe = new ArrayList<>();
        for (BoxInfo box : known) {
            if (box.reachable >= box.count) safe.add(box);
        }

        StringBuilder details = new StringBuilder();
        details.append("Slots used: ").append(occupiedSlots).append("/5\n");
        for (BoxInfo box : known) {
            details.append("Box ").append(box.index + 1)
                    .append(": ").append(box.colorName)
                    .append(" ").append(box.count)
                    .append(" | exposed now ~").append(box.reachable);
            if (box.reachable >= box.count) details.append(" | SAFE");
            details.append("\n");
        }
        details.append("\nRe-analyze after one move so the helper uses the new openings.");

        if (!safe.isEmpty()) {
            Collections.sort(safe, Comparator
                    .comparingInt((BoxInfo b) -> b.count)
                    .thenComparingInt(b -> -b.reachable));
            BoxInfo best = safe.get(0);
            return new AnalysisResult(
                    "SAFE PICK: " + best.colorName + " " + best.count,
                    "Tap the " + ordinal(best.index + 1) + " visible box. It has enough currently reachable tiles to clear without parking in a slot.\n\n" + details
            );
        }

        BoxInfo progress = null;
        double bestRatio = -1;
        for (BoxInfo box : known) {
            if (box.reachable <= 0) continue;
            double ratio = (double) box.reachable / Math.max(1, box.count);
            if (ratio > bestRatio) {
                bestRatio = ratio;
                progress = box;
            }
        }

        if (occupiedSlots >= 4) {
            return new AnalysisResult(
                    "STOP - no guaranteed safe box",
                    "Four or more slots look occupied and none of the visible boxes can fully clear right now. Let the ants finish, then Analyze again.\n\n" + details
            );
        }

        if (progress != null) {
            return new AnalysisResult(
                    "CAUTIOUS PICK: " + progress.colorName + " " + progress.count,
                    "No visible box is guaranteed to finish immediately. If the board is not still moving, the best progress pick is the " + ordinal(progress.index + 1) + " box because about " + progress.reachable + " matching tiles are reachable now. It may stay in a slot, so Analyze again immediately after tapping it.\n\n" + details
            );
        }

        return new AnalysisResult(
                "WAIT - no visible color is open",
                "The visible boxes currently look blocked. Let active ants finish or wait for the board animation to stop, then Analyze again.\n\n" + details
        );
    }

    private static final class CellGrid {
        boolean[][] present = new boolean[GRID][GRID];
        int[][] colors = new int[GRID][GRID];
        int presentCount;
    }

    private static CellGrid sampleGrid(Bitmap bitmap, Rect board) {
        CellGrid out = new CellGrid();
        double cw = board.width() / (double) GRID;
        double ch = board.height() / (double) GRID;

        for (int r = 0; r < GRID; r++) {
            for (int c = 0; c < GRID; c++) {
                double xa = board.left + c * cw;
                double xb = board.left + (c + 1) * cw;
                double ya = board.top + r * ch;
                double yb = board.top + (r + 1) * ch;

                int cx = clamp((int) Math.round((xa + xb) * 0.5), 0, bitmap.getWidth() - 1);
                int cy = clamp((int) Math.round((ya + yb) * 0.5), 0, bitmap.getHeight() - 1);
                int center = bitmap.getPixel(cx, cy);
                out.colors[r][c] = center;

                int xL = clamp((int) Math.round(xa + (xb - xa) * 0.12), 0, bitmap.getWidth() - 1);
                int xR = clamp((int) Math.round(xa + (xb - xa) * 0.88), 0, bitmap.getWidth() - 1);
                int yT = clamp((int) Math.round(ya + (yb - ya) * 0.12), 0, bitmap.getHeight() - 1);
                int yB = clamp((int) Math.round(ya + (yb - ya) * 0.88), 0, bitmap.getHeight() - 1);

                double contrast = 0;
                contrast = Math.max(contrast, rgbDistance(center, bitmap.getPixel(xL, cy)));
                contrast = Math.max(contrast, rgbDistance(center, bitmap.getPixel(xR, cy)));
                contrast = Math.max(contrast, rgbDistance(center, bitmap.getPixel(cx, yT)));
                contrast = Math.max(contrast, rgbDistance(center, bitmap.getPixel(cx, yB)));

                // Raised Colony Flow tiles have a visible bevel/grid edge. Cleared cells are much flatter.
                out.present[r][c] = contrast >= 8.0;
                if (out.present[r][c]) out.presentCount++;
            }
        }
        return out;
    }

    private static final class KMeansResult {
        int[] centers;
        int[][] labels;
    }

    private static KMeansResult kMeans(int[][] colorGrid, boolean[][] present, int requestedK) {
        List<Integer> points = new ArrayList<>();
        for (int r = 0; r < GRID; r++) {
            for (int c = 0; c < GRID; c++) {
                if (present[r][c]) points.add(colorGrid[r][c]);
            }
        }

        int k = Math.max(1, Math.min(requestedK, points.size()));
        int[] centers = new int[k];
        centers[0] = points.get(0);

        for (int i = 1; i < k; i++) {
            double best = -1;
            int pick = points.get((i * points.size()) / k);
            for (int p : points) {
                double nearest = Double.MAX_VALUE;
                for (int j = 0; j < i; j++) {
                    nearest = Math.min(nearest, rgbDistance(p, centers[j]));
                }
                if (nearest > best) {
                    best = nearest;
                    pick = p;
                }
            }
            centers[i] = pick;
        }

        int[] assignment = new int[points.size()];
        Arrays.fill(assignment, -1);
        for (int iteration = 0; iteration < 16; iteration++) {
            long[] sr = new long[k];
            long[] sg = new long[k];
            long[] sb = new long[k];
            int[] n = new int[k];
            boolean changed = false;

            for (int i = 0; i < points.size(); i++) {
                int p = points.get(i);
                int bestCluster = nearestCluster(p, centers);
                if (assignment[i] != bestCluster) {
                    assignment[i] = bestCluster;
                    changed = true;
                }
                sr[bestCluster] += Color.red(p);
                sg[bestCluster] += Color.green(p);
                sb[bestCluster] += Color.blue(p);
                n[bestCluster]++;
            }

            for (int c = 0; c < k; c++) {
                if (n[c] > 0) {
                    centers[c] = Color.rgb(
                            (int) (sr[c] / n[c]),
                            (int) (sg[c] / n[c]),
                            (int) (sb[c] / n[c])
                    );
                }
            }
            if (!changed && iteration > 1) break;
        }

        int[][] labels = new int[GRID][GRID];
        for (int[] row : labels) Arrays.fill(row, -1);
        for (int r = 0; r < GRID; r++) {
            for (int c = 0; c < GRID; c++) {
                if (present[r][c]) labels[r][c] = nearestCluster(colorGrid[r][c], centers);
            }
        }

        KMeansResult out = new KMeansResult();
        out.centers = centers;
        out.labels = labels;
        return out;
    }

    private static int countReachableForColor(boolean[][] originalPresent, int[][] labels, int targetCluster) {
        boolean[][] present = new boolean[GRID][GRID];
        for (int r = 0; r < GRID; r++) {
            System.arraycopy(originalPresent[r], 0, present[r], 0, GRID);
        }

        int removed = 0;
        boolean changed;
        do {
            boolean[][] outside = floodOpenFromBoundary(present);
            List<int[]> take = new ArrayList<>();
            for (int r = 0; r < GRID; r++) {
                for (int c = 0; c < GRID; c++) {
                    if (!present[r][c] || labels[r][c] != targetCluster) continue;
                    if (touchesOutside(r, c, present, outside)) take.add(new int[]{r, c});
                }
            }
            changed = !take.isEmpty();
            for (int[] p : take) {
                if (present[p[0]][p[1]]) {
                    present[p[0]][p[1]] = false;
                    removed++;
                }
            }
        } while (changed);

        return removed;
    }

    private static boolean[][] floodOpenFromBoundary(boolean[][] present) {
        boolean[][] open = new boolean[GRID][GRID];
        ArrayDeque<int[]> q = new ArrayDeque<>();

        for (int i = 0; i < GRID; i++) {
            addOpenIfEmpty(0, i, present, open, q);
            addOpenIfEmpty(GRID - 1, i, present, open, q);
            addOpenIfEmpty(i, 0, present, open, q);
            addOpenIfEmpty(i, GRID - 1, present, open, q);
        }

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        while (!q.isEmpty()) {
            int[] p = q.removeFirst();
            for (int[] d : dirs) {
                int nr = p[0] + d[0];
                int nc = p[1] + d[1];
                if (nr >= 0 && nr < GRID && nc >= 0 && nc < GRID && !present[nr][nc] && !open[nr][nc]) {
                    open[nr][nc] = true;
                    q.addLast(new int[]{nr, nc});
                }
            }
        }
        return open;
    }

    private static void addOpenIfEmpty(int r, int c, boolean[][] present, boolean[][] open, ArrayDeque<int[]> q) {
        if (!present[r][c] && !open[r][c]) {
            open[r][c] = true;
            q.addLast(new int[]{r, c});
        }
    }

    private static boolean touchesOutside(int r, int c, boolean[][] present, boolean[][] outside) {
        if (r == 0 || c == 0 || r == GRID - 1 || c == GRID - 1) return true;
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] d : dirs) {
            int nr = r + d[0];
            int nc = c + d[1];
            if (!present[nr][nc] && outside[nr][nc]) return true;
        }
        return false;
    }

    private static List<BoxInfo> readTopBoxes(Bitmap bitmap, Text text, int[] boardCenters) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        float[] xCenters = {0.269f, 0.423f, 0.577f, 0.730f};
        List<BoxInfo> boxes = new ArrayList<>();

        for (int i = 0; i < xCenters.length; i++) {
            BoxInfo b = new BoxInfo();
            b.index = i;
            int left = (int) (w * (xCenters[i] - 0.057f));
            int right = (int) (w * (xCenters[i] + 0.057f));
            int top = (int) (h * 0.680f);
            int bottom = (int) (h * 0.726f);
            b.rgb = dominantFaceColor(bitmap, new Rect(left, top, right, bottom));
            b.cluster = nearestClusterHsv(b.rgb, boardCenters);
            if (b.cluster >= 0 && b.cluster < boardCenters.length) {
                b.colorName = colorName(boardCenters[b.cluster]);
            }
            b.count = findNumberInZone(text, new Rect(
                    (int) (w * (xCenters[i] - 0.050f)),
                    (int) (h * 0.682f),
                    (int) (w * (xCenters[i] + 0.050f)),
                    (int) (h * 0.735f)
            ));
            boxes.add(b);
        }
        return boxes;
    }

    private static int findNumberInZone(Text text, Rect zone) {
        int bestValue = -1;
        int bestArea = -1;
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    Rect box = element.getBoundingBox();
                    if (box == null) continue;
                    int cx = box.centerX();
                    int cy = box.centerY();
                    if (!zone.contains(cx, cy)) continue;
                    String digits = element.getText().replaceAll("[^0-9]", "");
                    if (digits.isEmpty()) continue;
                    try {
                        int value = Integer.parseInt(digits);
                        if (value <= 0 || value > 999) continue;
                        int area = box.width() * box.height();
                        if (area > bestArea) {
                            bestArea = area;
                            bestValue = value;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return bestValue;
    }

    private static int dominantFaceColor(Bitmap bitmap, Rect raw) {
        Rect r = new Rect(
                clamp(raw.left, 0, bitmap.getWidth() - 1),
                clamp(raw.top, 0, bitmap.getHeight() - 1),
                clamp(raw.right, 1, bitmap.getWidth()),
                clamp(raw.bottom, 1, bitmap.getHeight())
        );
        List<Integer> rs = new ArrayList<>();
        List<Integer> gs = new ArrayList<>();
        List<Integer> bs = new ArrayList<>();
        float[] hsv = new float[3];

        int step = Math.max(1, Math.min(r.width(), r.height()) / 30);
        for (int y = r.top; y < r.bottom; y += step) {
            for (int x = r.left; x < r.right; x += step) {
                int p = bitmap.getPixel(x, y);
                Color.colorToHSV(p, hsv);
                float s = hsv[1];
                float v = hsv[2];
                if (v < 0.38f) continue;
                if (v > 0.965f && s < 0.10f) continue;
                if (s < 0.14f) continue;
                rs.add(Color.red(p));
                gs.add(Color.green(p));
                bs.add(Color.blue(p));
            }
        }

        if (rs.isEmpty()) {
            int cx = (r.left + r.right) / 2;
            int cy = (r.top + r.bottom) / 2;
            return bitmap.getPixel(cx, cy);
        }
        Collections.sort(rs);
        Collections.sort(gs);
        Collections.sort(bs);
        int mid = rs.size() / 2;
        return Color.rgb(rs.get(mid), gs.get(mid), bs.get(mid));
    }

    private static int estimateOccupiedSlots(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        float[] xs = {0.191f, 0.345f, 0.500f, 0.654f, 0.808f};
        int occupied = 0;
        float[] hsv = new float[3];

        for (float xf : xs) {
            int cx = clamp((int) (w * xf), 0, w - 1);
            int cy = clamp((int) (h * 0.632f), 0, h - 1);
            int p = bitmap.getPixel(cx, cy);
            Color.colorToHSV(p, hsv);
            boolean looksWhite = hsv[1] < 0.10f && hsv[2] > 0.80f;
            if (!looksWhite) occupied++;
        }
        return occupied;
    }

    private static int nearestCluster(int rgb, int[] centers) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < centers.length; i++) {
            double d = rgbDistance(rgb, centers[i]);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private static int nearestClusterHsv(int rgb, int[] centers) {
        float[] a = new float[3];
        float[] b = new float[3];
        Color.colorToHSV(rgb, a);
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < centers.length; i++) {
            Color.colorToHSV(centers[i], b);
            double dh = Math.abs(a[0] - b[0]) / 360.0;
            dh = Math.min(dh, 1.0 - dh);
            double ds = Math.abs(a[1] - b[1]);
            double dv = Math.abs(a[2] - b[2]);
            double d = dh * 3.0 + ds * 0.5 + dv * 0.15;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private static String colorName(int rgb) {
        float[] hsv = new float[3];
        Color.colorToHSV(rgb, hsv);
        float h = hsv[0];
        float s = hsv[1];
        float v = hsv[2];

        if (s < 0.12f && v > 0.82f) return "White";
        if (h < 12 || h >= 348) return s < 0.45f ? "Pink" : "Red";
        if (h < 25) return "Orange";
        if (h < 52) return s < 0.52f ? "Cream" : "Yellow";
        if (h < 85) return "Lime";
        if (h < 155) return "Green";
        if (h < 195) return "Teal";
        if (h < 220) return "Cyan";
        if (h < 255) return "Blue";
        if (h < 292) return "Purple";
        if (h < 330) return "Magenta";
        return "Pink";
    }

    private static double rgbDistance(int a, int b) {
        int dr = Color.red(a) - Color.red(b);
        int dg = Color.green(a) - Color.green(b);
        int db = Color.blue(a) - Color.blue(b);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static String ordinal(int n) {
        switch (n) {
            case 1: return "1st";
            case 2: return "2nd";
            case 3: return "3rd";
            default: return n + "th";
        }
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
