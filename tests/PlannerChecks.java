package com.colonyhelper.overlay;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;

/** Outcome checks on hand-built puzzles and the actual Level 224 reference capture. */
public final class PlannerChecks {
    private static int passed;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }
    private static MovePlanner.Box b(int id, int r, int c, int color, int n, int group) {
        return new MovePlanner.Box(id, r, c, color, n, "Color" + color, group, false);
    }
    private static MovePlanner.Snapshot puzzle(int width, int[] cells, MovePlanner.Box[][] columns, int opaque, boolean reserve) {
        return new MovePlanner.Snapshot(width, cells.length / width, cells, columns, Collections.emptyList(), opaque, reserve);
    }
    private static int[] ring(int n, int outside, int inside) {
        int[] cells = new int[n * n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++)
            cells[y * n + x] = x == 0 || y == 0 || x == n - 1 || y == n - 1 ? outside : inside;
        return cells;
    }
    private static void fill(int[] pixels, int w, int h, float left, float top, float right, float bottom, int color) {
        for (int y = (int)(top*h); y < (int)(bottom*h); y++)
            for (int x = (int)(left*w); x < (int)(right*w); x++) pixels[y*w+x] = color;
    }

    public static void main(String[] args) throws Exception {
        int[] small = ring(3, 0, 1);
        MovePlanner.Box a = b(0, 0, 0, 0, 8, 0);
        MovePlanner.Box[][] one = {{a}};
        check(MovePlanner.analyze(puzzle(3, small, one, 0, true)).best.immediate, "Exposed color clears");
        check(MovePlanner.analyze(puzzle(3, small, one, 5, false)).best == null, "Full tray cannot accept a pick");
        check(MovePlanner.analyze(puzzle(3, small, one, 4, true)).best == null, "One-slot reserve is enforced");
        check(MovePlanner.analyze(puzzle(3, small, one, 4, false)).best.peakSlots == 5, "Balanced mode can use final slot");

        MovePlanner.Box[][] linked = {{b(0, 0, 0, 0, 4, 0), b(1, 1, 0, 0, 4, 0)}};
        check(MovePlanner.analyze(puzzle(3, small, linked, 4, false)).best == null, "Linked pair needs two free slots before either can clear");
        check(MovePlanner.analyze(puzzle(3, small, linked, 0, true)).best.peakSlots == 2, "Linked pair enters together");
        MovePlanner.Box[][] oversubscribed = {{b(0, 0, 0, 0, 5, 0), b(1, 1, 0, 0, 5, 0)}};
        check(MovePlanner.analyze(puzzle(3, small, oversubscribed, 0, true)).best == null, "Shared color supply cannot be counted twice");

        MovePlanner.Box[][] mixed = {{b(0, 0, 0, 0, 8, 0), b(1, 1, 0, 1, 1, 0)}};
        check(MovePlanner.analyze(puzzle(3, small, mixed, 0, true)).best.immediate, "Linked colors can open each other's paths");
        MovePlanner.Box[][] incomplete = {{b(0, 0, 0, 0, 8, 0), b(1, 1, 0, 1, 2, 0)}};
        check(MovePlanner.analyze(puzzle(3, small, incomplete, 0, true)).best == null, "Finishing one linked member is not a complete recovery");

        MovePlanner.Box[][] park = {{b(0, 0, 0, 1, 1, 0), b(1, 1, 0, 0, 8, 1)}};
        MovePlanner.Candidate p = MovePlanner.analyze(puzzle(3, small, park, 0, true)).best;
        check(p != null && !p.immediate && p.followups == 1, "Buried block has one-click recovery through next row");
        check(p.route.size() == 2 && p.route.get(1).column == 0, "Recovery identifies the column and next block");
        check(p.remainingAfterFirst == 1 && p.peakSlots == 2, "Parking exposes remaining quota and peak occupancy");
        check(Arrays.equals(small, ring(3, 0, 1)), "Planning does not mutate the input board");
        check(MovePlanner.analyze(puzzle(3, small, park, 3, true)).best == null, "Do not park if reserve leaves no room for recovery");
        check(MovePlanner.analyze(puzzle(3, small, park, 3, false)).best.followups == 1, "Recovery can use fifth slot only when reserve is disabled");

        MovePlanner.Box[][] noReturn = {{b(0, 0, 0, 1, 1, 0), b(1, 1, 0, 1, 1, 1)}};
        check(MovePlanner.analyze(puzzle(3, small, noReturn, 0, true)).best == null, "Reject a buried color without a visible return route");
        MovePlanner.Box[][] unknown = {{b(0, 0, 0, 0, -1, 0)}};
        check(MovePlanner.analyze(puzzle(3, small, unknown, 0, true)).best == null, "Unknown numbers are not guessed");
        MovePlanner.Box[][] hidden = {{new MovePlanner.Box(0, 0, 0, 0, 8, "A", 0, true)}};
        check(MovePlanner.analyze(puzzle(3, small, hidden, 0, true)).best == null, "Off-screen linked partner prevents a blind pick");
        MovePlanner.Box[][] covered = {{b(0, 0, 0, 0, 4, 0)}, {b(1, 0, 1, 0, 1, 1), b(2, 1, 1, 0, 4, 0)}};
        check(MovePlanner.analyze(puzzle(3, small, covered, 0, true)).candidates.get(0).reason.contains("covered"), "A linked partner blocked by another head cannot be selected");

        int[] layers = ring(7, 0, 1);
        for (int y = 2; y <= 4; y++) for (int x = 2; x <= 4; x++) layers[y * 7 + x] = 2;
        MovePlanner.Box[][] threeRows = {{b(0, 0, 0, 2, 9, 0), b(1, 1, 0, 1, 16, 1), b(2, 2, 0, 0, 24, 2)}};
        MovePlanner.Candidate recovered = MovePlanner.analyze(puzzle(7, layers, threeRows, 0, true)).best;
        check(recovered != null && recovered.followups == 2 && recovered.route.size() == 3, "Third row can recover an initially blocked front box");
        check(recovered.peakSlots == 3, "Two temporarily parked groups leave two actual slots free");

        int[] enclosed = {0,0,0,0,0, 0,1,1,1,0, 0,1,-1,1,0, 0,1,1,1,0, 0,0,0,0,0};
        check(MovePlanner.reachable(enclosed, 5, 5, 1) == 0, "An enclosed empty pocket is not an outside path");
        check(MovePlanner.reachable(new int[]{-1,1,-1,-1}, 2, 2, 1) == 1, "Cleared paths connect to the boundary");
        MovePlanner.Work existing = new MovePlanner.Work(-1, 0, 8, -1);
        MovePlanner.Snapshot live = new MovePlanner.Snapshot(3, 3, small, new MovePlanner.Box[][]{{b(0,0,0,1,1,0)}},
                Collections.singletonList(existing), 0, true);
        check(MovePlanner.analyze(live).best.immediate, "Existing slot colors participate in the simulation");
        check(existing.remaining == 8, "Planning does not change observed slot quotas");

        String reference = args.length > 0 ? args[0] : "tests/fixtures/level224.jpg";
        if (!new File(reference).isFile()) {
            System.out.println("PASS: " + passed + " planner outcome checks. Reference-image checks require the local test capture.");
            return;
        }
        BufferedImage image = ImageIO.read(new File(reference));
        int w = image.getWidth(), h = image.getHeight();
        int[] pixels = image.getRGB(0,0,w,h,null,0,w);
        int[][] counts = {{16,6,51,27},{43,24,22,38},{15,24,10,26}};
        float[] xs = {.269f,.423f,.577f,.730f}, ys = {.704f,.776f,.839f};
        List<BoardVision.Token> tokens = new ArrayList<>();
        for (int r = 0; r < 3; r++) for (int c = 0; c < 4; c++) {
            int x = (int)(xs[c]*w), y = (int)(ys[r]*h);
            tokens.add(new BoardVision.Token(Integer.toString(counts[r][c]),x-22,y-19,x+22,y+19));
        }
        BoardVision.Reading reading = BoardVision.read(w,h,pixels,tokens);
        for (int c = 0; c < 4; c++) {
            System.out.print("Column " + (c+1) + ": ");
            for (MovePlanner.Box box : reading.columns[c]) System.out.print(box.label()+" [g="+box.group+",hidden="+box.hiddenLink+"] ");
            System.out.println();
        }
        check(reading.numbersRead == 12, "All three rows in reference geometry are read");
        check(reading.occupiedSlots == 0, "Reference has five empty waiting slots");
        check(reading.columns[1][0].group == reading.columns[1][1].group, "Reference Green 6 and Cyan 24 are a linked pair");
        check(reading.columns[1][1].group != reading.columns[1][2].group, "Next Cyan 24 is not merged without a connecting bar");
        check(reading.linksRead == 1, "Reference contains exactly one visible connector");
        check(reading.columns[2][0].name.equals("Cream"), "Reference Cream 51 has the correct board color");
        check(reading.columns[2][1].name.equals("Purple"), "Reference Purple 22 is not mistaken for Magenta");
        check(reading.columns[3][0].name.equals("Orange"), "Reference Orange 27 is not mistaken for Yellow");
        check(reading.columns[3][1].name.equals("Teal"), "Reference Teal 38 is not mistaken for Cyan");
        check(!reading.columns[1][2].hiddenLink, "Booster artwork is not mistaken for a queue connector");
        MovePlanner.Plan referencePlan = MovePlanner.analyze(reading.snapshot(true));
        check(referencePlan.best != null && referencePlan.best.column == 2 && referencePlan.best.immediate,
                "Reference opening selects Column 3 Cream 51");
        for (int targetWidth : new int[]{720, 1080}) {
            int targetHeight = Math.round(h * (targetWidth / (float) w));
            BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics = resized.createGraphics();
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null); graphics.dispose();
            float factor = targetWidth / (float) w;
            List<BoardVision.Token> scaledTokens = new ArrayList<>();
            for (BoardVision.Token t : tokens) scaledTokens.add(new BoardVision.Token(t.text,
                    Math.round(t.left * factor), Math.round(t.top * factor), Math.round(t.right * factor), Math.round(t.bottom * factor)));
            BoardVision.Reading scaled = BoardVision.read(targetWidth, targetHeight,
                    resized.getRGB(0,0,targetWidth,targetHeight,null,0,targetWidth), scaledTokens);
            check(scaled.numbersRead == 12 && scaled.linksRead == 1, "All rows and connector survive capture resize " + targetWidth);
            check(MovePlanner.analyze(scaled.snapshot(true)).best.column == 2, "Opening is stable at capture width " + targetWidth);
        }
        List<BoardVision.Token> missing = new ArrayList<>(tokens);
        missing.remove(2);
        BoardVision.Reading incompleteReading = BoardVision.read(w,h,pixels,missing);
        check(incompleteReading.numbersRead == 11 && incompleteReading.confidence.equals("MEDIUM"), "Missing OCR is disclosed in reading confidence");
        check(!MovePlanner.analyze(incompleteReading.snapshot(true)).candidates.get(2).recommended, "Unreadable front Cream count is never invented");

        int[] waitingPixels = pixels.clone();
        fill(waitingPixels,w,h,.137f,.607f,.245f,.657f,0xff42d3ed);
        fill(waitingPixels,w,h,.291f,.607f,.399f,.657f,0xff42d3ed);
        fill(waitingPixels,w,h,.244f,.626f,.292f,.638f,0xff32d2f5);
        List<BoardVision.Token> waitingTokens = new ArrayList<>(tokens);
        int sx = (int)(.191f*w), sy = (int)(.632f*h), sx2 = (int)(.345f*w);
        waitingTokens.add(new BoardVision.Token("3",sx-12,sy-16,sx+12,sy+16));
        waitingTokens.add(new BoardVision.Token("4",sx2-12,sy-16,sx2+12,sy+16));
        BoardVision.Reading waiting = BoardVision.read(w,h,waitingPixels,waitingTokens);
        check(waiting.occupiedSlots == 2 && waiting.active.size() == 2 && waiting.opaqueSlots == 0, "Colored waiting boxes and their remaining numbers are read");
        check(waiting.active.get(0).group == waiting.active.get(1).group, "Visible waiting-slot link keeps a group together");
        waitingTokens.remove(waitingTokens.size()-1);
        BoardVision.Reading unknownPartner = BoardVision.read(w,h,waitingPixels,waitingTokens);
        check(unknownPartner.active.isEmpty() && unknownPartner.opaqueSlots == 2, "Unknown linked waiting partner reserves both slots");

        int[] horizontalPixels = pixels.clone();
        fill(horizontalPixels,w,h,.324f,.699f,.368f,.709f,0xff32d2f5);
        BoardVision.Reading horizontal = BoardVision.read(w,h,horizontalPixels,tokens);
        check(horizontal.columns[0][0].group == horizontal.columns[1][0].group, "Horizontal queue connector is detected");
        check(horizontal.columns[0][0].group == horizontal.columns[1][1].group, "Horizontal and vertical connectors form one group");

        System.out.println("PASS: " + passed + " outcome checks; reference recommendation: Column " + (referencePlan.best.column+1));
    }
}
