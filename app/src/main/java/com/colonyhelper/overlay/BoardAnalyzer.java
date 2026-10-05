package com.colonyhelper.overlay;

import android.graphics.Bitmap;
import android.graphics.Rect;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BoardAnalyzer {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    public interface Callback { void onResult(AnalysisResult result); }
    public static final class AnalysisResult {
        public final String headline, detail;
        public final int column;
        public final boolean temporaryPark;
        public AnalysisResult(String headline, String detail) { this(headline, detail, -1, false); }
        AnalysisResult(String headline, String detail, int column, boolean temporaryPark) {
            this.headline = headline; this.detail = detail;
            this.column = column; this.temporaryPark = temporaryPark;
        }
    }
    private BoardAnalyzer() {}

    public static void analyze(Bitmap bitmap, boolean reserveOne, Callback callback) {
        if (bitmap == null || bitmap.getWidth() < 300 || bitmap.getHeight() < 600 || bitmap.getHeight() < bitmap.getWidth() * 1.5) {
            callback.onResult(new AnalysisResult("Use portrait mode", "Keep the whole game visible, then Analyze again."));
            return;
        }
        int offsetY = (int) (bitmap.getHeight() * .595);
        int cropHeight = Math.min(bitmap.getHeight() - offsetY, (int) (bitmap.getHeight() * .290));
        Bitmap crop = Bitmap.createBitmap(bitmap, 0, offsetY, bitmap.getWidth(), cropHeight);
        float scale = Math.min(2f, 1400f / crop.getWidth());
        Bitmap ocr = Bitmap.createScaledBitmap(crop, Math.round(crop.getWidth() * scale), Math.round(crop.getHeight() * scale), true);
        if (crop != ocr) crop.recycle();
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(InputImage.fromBitmap(ocr, 0))
                .addOnSuccessListener(text -> {
                    List<BoardVision.Token> tokens = tokens(text, scale, offsetY);
                    recognizer.close(); ocr.recycle();
                    WORKER.execute(() -> {
                        Bitmap image = bitmap;
                        try {
                            if (bitmap.getWidth() > 1080) image = Bitmap.createScaledBitmap(bitmap, 1080,
                                    Math.round(bitmap.getHeight() * (1080f / bitmap.getWidth())), true);
                            float resize = image.getWidth() / (float) bitmap.getWidth();
                            List<BoardVision.Token> adjusted = new ArrayList<>();
                            for (BoardVision.Token t : tokens) adjusted.add(new BoardVision.Token(t.text,
                                    Math.round(t.left * resize), Math.round(t.top * resize),
                                    Math.round(t.right * resize), Math.round(t.bottom * resize)));
                            int[] pixels = new int[image.getWidth() * image.getHeight()];
                            image.getPixels(pixels, 0, image.getWidth(), 0, 0, image.getWidth(), image.getHeight());
                            BoardVision.Reading reading = BoardVision.read(image.getWidth(), image.getHeight(), pixels, adjusted);
                            if (reading.presentCount < 12 || reading.palette.length == 0) {
                                callback.onResult(new AnalysisResult("Board not detected", "Keep the whole portrait game visible and wait for the board to stop moving, then rescan."));
                            } else {
                                callback.onResult(format(reading, MovePlanner.analyze(reading.snapshot(reserveOne)), reserveOne));
                            }
                        } catch (RuntimeException error) {
                            callback.onResult(new AnalysisResult("Please rescan", "Could not make a reliable reading: " + error.getClass().getSimpleName()));
                        } finally {
                            if (image != bitmap) image.recycle();
                        }
                    });
                })
                .addOnFailureListener(error -> {
                    recognizer.close(); ocr.recycle();
                    callback.onResult(new AnalysisResult("Numbers not readable", "Wait until all three rows are still, then Analyze again."));
                });
    }

    private static List<BoardVision.Token> tokens(Text text, float scale, int offsetY) {
        List<BoardVision.Token> tokens = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines())
            for (Text.Element element : line.getElements()) {
                Rect r = element.getBoundingBox();
                if (r != null) tokens.add(new BoardVision.Token(element.getText(), Math.round(r.left / scale),
                        Math.round(r.top / scale) + offsetY, Math.round(r.right / scale), Math.round(r.bottom / scale) + offsetY));
            }
        return tokens;
    }

    private static AnalysisResult format(BoardVision.Reading reading, MovePlanner.Plan plan, boolean reserveOne) {
        StringBuilder detail = new StringBuilder();
        MovePlanner.Candidate best = plan.best;
        String headline;
        if (best == null) {
            headline = plan.slotsUsed >= 5 ? "WAIT - all 5 slots occupied" : "WAIT / RESCAN - no short recovery route";
            detail.append("Let any active ants finish. No reliable short recovery was found in these visible rows.\n\n");
        } else {
            headline = "COLUMN " + (best.column + 1) + " - " + best.label();
            detail.append(best.immediate ? "LIKELY CLEAR NOW" : "TEMPORARY PARK")
                    .append("\nTap the front block in column ").append(best.column + 1).append(".\n");
            if (best.group.size() > 1) detail.append("Linked group: ").append(best.group.size()).append(" blocks enter together.\n");
            detail.append("Estimated peak: ").append(best.peakSlots).append("/5 slots.\n");
            if (!best.immediate) {
                detail.append("Expected recovery: ").append(best.followups).append(" more click(s).\n");
                for (String parked : best.parked) detail.append("Parked: ").append(parked).append(".\n");
            }
            detail.append("\n").append(best.immediate ? "PREVIEW (rescan after each click)" : "RETURN ROUTE (rescan after each click)").append("\n");
            for (int i = 0; i < best.route.size(); i++) {
                MovePlanner.Box b = best.route.get(i);
                detail.append(i + 1).append(". Column ").append(b.column + 1).append(": ").append(groupLabel(reading, b.group)).append("\n");
            }
            detail.append("\n");
        }
        detail.append("SLOTS: ").append(plan.slotsUsed).append("/5").append(reserveOne ? " | keeping 1 free" : " | all 5 allowed")
                .append("\nREAD CONFIDENCE: ").append(reading.confidence).append(" (numbers/colors)")
                .append("\n\nQUEUE (row 1 -> row 2 -> row 3)\n");
        for (int c = 0; c < reading.columns.length; c++) {
            detail.append("C").append(c + 1).append(": ");
            MovePlanner.Box[] column = reading.columns[c];
            for (int r = 0; r < column.length; r++) {
                if (r > 0) detail.append(column[r - 1].group == column[r].group ? " <-> " : " -> ");
                detail.append(column[r].label());
            }
            detail.append("\n");
        }
        detail.append("\nCOLUMN CHECK\n");
        for (MovePlanner.Candidate candidate : plan.candidates) {
            detail.append("C").append(candidate.column + 1).append(": ");
            if (candidate.recommended) detail.append(candidate.reason).append("; peak ").append(candidate.peakSlots).append("/5");
            else detail.append(candidate.reason);
            detail.append("\n");
        }
        for (String issue : reading.issues) detail.append("\n").append(issue);
        detail.append("\n\nEstimates use the 3 visible rows and up to 4 clicks. Parking advice needs a return within 2 more clicks. " +
                "Wait for ants to settle, then rescan after ONE move. Hidden boxes, covered links, and animation can change the result.");
        return new AnalysisResult(headline, detail.toString(), best == null ? -1 : best.column, best != null && !best.immediate);
    }

    private static String groupLabel(BoardVision.Reading reading, int group) {
        StringBuilder s = new StringBuilder();
        for (MovePlanner.Box[] column : reading.columns) for (MovePlanner.Box b : column) if (b.group == group) {
            if (s.length() > 0) s.append(" + ");
            s.append(b.label());
        }
        return s.toString();
    }
}
