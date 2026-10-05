package com.colonyhelper.overlay;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Local-only checks on frames extracted from the supplied Level 225 recording. */
public final class VideoChecks {
    private static int passed;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        passed++;
    }
    private static BufferedImage frame(File directory, int second) throws Exception {
        return ImageIO.read(new File(directory, String.format("t%03d.jpg", second)));
    }
    private static BoardVision.Reading read(BufferedImage image, int[][] counts) {
        int w = image.getWidth(), h = image.getHeight(), n = counts[0].length;
        float[] ys = {.703f,.779f,.838f};
        List<BoardVision.Token> tokens = new ArrayList<>();
        for (int r = 0; r < counts.length; r++) for (int c = 0; c < n; c++) if (counts[r][c] > 0) {
            int x = Math.round((.5f + (c - (n - 1) / 2f) * .154f) * w), y = Math.round(ys[r] * h);
            tokens.add(new BoardVision.Token(Integer.toString(counts[r][c]), x-19, y-18, x+19, y+18));
        }
        return BoardVision.read(w,h,image.getRGB(0,0,w,h,null,0,w),tokens);
    }
    private static int count(BoardVision.Reading reading, int color) {
        int n = 0; for (int cell : reading.cells) if (cell == color) n++;
        return n;
    }
    private static int[] small(BufferedImage image) {
        BufferedImage out = new BufferedImage(180,406,BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image,0,0,180,406,null);g.dispose();
        return out.getRGB(0,0,180,406,null,0,180);
    }
    public static void main(String[] args) throws Exception {
        File directory = new File(args.length > 0 ? args[0] : "tests/fixtures/video225");
        if (!directory.isDirectory()) { System.out.println("Video checks require local recording frames."); return; }
        BoardVision.Reading start = read(frame(directory,0),new int[][]{{20,20,20,20},{17,10,20,20},{20,13,20,20}});
        BoardVision.Reading middle = read(frame(directory,205),new int[][]{{20,10,20},{20,13,20},{14,12,6}});
        for (BoardVision.Reading r : new BoardVision.Reading[]{start,middle}) {
            System.out.println("Grid " + r.gridSize + ", reliable=" + r.gridReliable + ", cells=" + r.presentCount +
                    ", columns=" + r.columns.length + ", boxes=" + r.boxesRead + ", slots=" + r.occupiedSlots);
            for (int c = 0; c < r.columns.length; c++) {
                System.out.print("C"+(c+1)+" at "+r.columnX[c]+": ");
                for (MovePlanner.Box b : r.columns[c]) System.out.print(b.label()+"; ");
                System.out.println();
            }
            long t = System.nanoTime();MovePlanner.Plan p = MovePlanner.analyze(r.snapshot(true));
            System.out.println("Plan ms="+(System.nanoTime()-t)/1_000_000+", best="+(p.best==null ? "none" : p.best.column+1));
            for (MovePlanner.Candidate c : p.candidates)
                System.out.println("C"+(c.column+1)+": "+c.reason+", left="+c.remainingAfterFirst+"/"+c.requested);
        }
        check(start.gridReliable && start.gridSize==32,"Recording opening has a 32x32 grid");
        check(middle.gridReliable && middle.gridSize==32,"Grid stays 32x32 after most lower tiles are cleared");
        check(start.columns.length==4 && start.numbersRead==12,"Opening has four columns and twelve queue numbers");
        check(middle.columns.length==3 && middle.numbersRead==9,"Depleted queue is recentered into three columns");
        check(Math.abs(middle.columnX[0]-.346)<.006,"Arrow follows the new leftmost column");
        check(start.occupiedSlots==0 && middle.occupiedSlots==0,"Still reference frames have empty waiting slots");
        check(count(middle,middle.columns[0][0].color)>=95 && count(middle,middle.columns[0][0].color)<=105,
                "Empty-board shadows are not extra pink tiles");
        MovePlanner.Plan opening = MovePlanner.analyze(start.snapshot(true));
        check(opening.best!=null && opening.best.column==2 && opening.best.immediate,"Cyan 20 opening clears as recorded");
        check(opening.best.requested==20 && opening.best.remainingAfterFirst==0,"Opening collection accounts for the whole quota");
        int[] later = read(frame(directory,250),new int[][]{{20,10,20},{14,13,20},{20,12,6}}).cells;
        int pink = middle.columns[0][0].color;
        int[] projected = MovePlanner.projectCells(middle.cells,32,32,pink,20,false);
        // Compare cleared positions only: palette indices can differ between captures.
        int overlap = 0, removed = 0;
        for (int i = 0; i < projected.length; i++) if (middle.cells[i]==pink && projected[i]<0) {
            removed++; if (later[i]<0) overlap++;
        }
        check(removed==20,"Pink 20 preview collects exactly twenty tiles");
        System.out.println("Pink 20 overlap="+overlap+"/20");
        check(overlap>=16,"Nearby-target preview agrees with most observed Pink 20 removals");
        check(!BoardVision.isMoving(180,406,small(frame(directory,0)),small(frame(directory,1))),"Animated keys alone do not trigger WAIT");
        check(!BoardVision.isMoving(180,406,small(frame(directory,205)),small(frame(directory,206))),"Still mid-level gameplay passes movement check");
        check(BoardVision.isMoving(180,406,small(frame(directory,5)),small(frame(directory,6))),"Collecting ants trigger WAIT");
        check(BoardVision.isMoving(180,406,small(frame(directory,300)),small(frame(directory,301))),"Concurrent colonies trigger WAIT");
        BoardVision.Reading sparse = read(frame(directory,150),new int[][]{{20,10,20,20},{20,13,20,20},{20,12,-1,20}});
        check(sparse.columns.length==4 && sparse.boxesRead==11 && sparse.columns[2].length==2,
                "A missing tail box is not invented while four columns remain");
        BoardVision.Reading late = read(frame(directory,315),new int[][]{{20,20},{20,15},{-1,-1}});
        check(late.columns.length==2 && late.boxesRead==4 && late.numbersRead==4,
                "Last two columns have only two visible queue rows");
        check(Math.abs(late.columnX[0]-.423)<.006 && Math.abs(late.columnX[1]-.577)<.006,
                "Two-column target positions stay centered");
        BoardVision.Reading locked = read(frame(directory,300),new int[][]{{18,20},{-1,6},{5,20}});
        check(locked.columns.length==2 && locked.boxesRead==6 && locked.numbersRead==5,
                "A lock without a count remains an unknown box instead of disappearing");
        check(locked.columns[0][1].count<0,"Locked box quota is not guessed");
        System.out.println("PASS: "+passed+" local recording outcome checks; Pink 20 target overlap="+overlap+"/20.");
    }
}
