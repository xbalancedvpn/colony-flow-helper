package com.colonyhelper.overlay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A bounded, conservative planner. No Android, OCR, or tap automation dependencies. */
public final class MovePlanner {
    public static final int LOOKAHEAD = 4;
    public static final int MAX_PARK_FOLLOWUPS = 2;
    private static final int BEAM_WIDTH = 12;
    private static final int MAX_NODES_PER_COLUMN = 180;

    public static final class Box {
        public final int id, row, column, color, count, group;
        public final String name;
        public final boolean hiddenLink;

        public Box(int id, int row, int column, int color, int count,
                   String name, int group, boolean hiddenLink) {
            this.id = id; this.row = row; this.column = column;
            this.color = color; this.count = count; this.name = name;
            this.group = group; this.hiddenLink = hiddenLink;
        }

        public String label() { return name + " " + (count > 0 ? count : "?"); }
    }

    public static final class Work {
        public final int id, color, group;
        public int remaining;

        public Work(int id, int color, int remaining, int group) {
            this.id = id; this.color = color; this.remaining = remaining; this.group = group;
        }

        Work copy() { return new Work(id, color, remaining, group); }
    }

    public static final class Snapshot {
        public final int width, height, opaqueSlots;
        public final int[] cells;
        public final Box[][] columns;
        public final List<Work> active;
        public final boolean reserveOne;

        public Snapshot(int width, int height, int[] cells, Box[][] columns,
                        List<Work> active, int opaqueSlots, boolean reserveOne) {
            if (width < 1 || height < 1 || cells.length != width * height)
                throw new IllegalArgumentException("Invalid board size");
            if (opaqueSlots < 0 || opaqueSlots + active.size() > 5)
                throw new IllegalArgumentException("Invalid slot count");
            this.width = width; this.height = height; this.cells = cells.clone();
            this.columns = columns; this.active = active; this.opaqueSlots = opaqueSlots;
            this.reserveOne = reserveOne;
        }
    }

    public static final class Candidate {
        public final int column;
        public final List<Box> group = new ArrayList<>();
        public final List<Box> route = new ArrayList<>();
        public final List<String> parked = new ArrayList<>();
        public final List<Work> waitingAfterFirst = new ArrayList<>();
        public boolean recommended, immediate, simulated;
        public int followups = -1, peakSlots, remainingAfterFirst, requested, removed;
        public String reason = "No short recovery route in the visible rows";
        double score = -Double.MAX_VALUE;

        Candidate(int column) { this.column = column; }
        public String label() {
            StringBuilder s = new StringBuilder();
            for (Box b : group) { if (s.length() > 0) s.append(" + "); s.append(b.label()); }
            return s.toString();
        }
    }

    public static final class Plan {
        public final List<Candidate> candidates;
        public final Candidate best;
        public final int slotsUsed, explored;
        Plan(List<Candidate> candidates, Candidate best, int slotsUsed, int explored) {
            this.candidates = candidates; this.best = best;
            this.slotsUsed = slotsUsed; this.explored = explored;
        }
    }

    private static final class State {
        int[] cells;
        long used;
        final List<Work> active = new ArrayList<>();
        final List<Box> route = new ArrayList<>();
        int peak, removed;
        State copy() {
            State out = new State(); out.cells = cells.clone(); out.used = used;
            for (Work w : active) out.active.add(w.copy());
            out.route.addAll(route); out.peak = peak; out.removed = removed;
            return out;
        }
    }

    private static final class SearchResult { State state; double score; }
    private int explored;
    private final Snapshot input;

    private MovePlanner(Snapshot input) { this.input = input; }

    public static Plan analyze(Snapshot input) { return new MovePlanner(input).run(); }

    private Plan run() {
        State initial = new State(); initial.cells = input.cells.clone();
        for (Work w : input.active) initial.active.add(w.copy());
        initial.peak = slots(initial);
        List<Candidate> candidates = new ArrayList<>();
        for (int c = 0; c < input.columns.length; c++) {
            Box head = head(initial, c);
            if (head == null) continue;
            Candidate candidate = new Candidate(c);
            candidate.group.addAll(group(initial, head));
            for (Box b : candidate.group) if (b.count > 0) candidate.requested += b.count;
            String blocked = blockedReason(initial, head);
            if (blocked != null) { candidate.reason = blocked; candidates.add(candidate); continue; }

            State first = take(initial, head, false);
            State otherFirst = take(initial, head, true);
            candidate.simulated = true;
            candidate.peakSlots = Math.max(first.peak, otherFirst.peak);
            candidate.remainingAfterFirst = Math.max(remaining(first, head.group), remaining(otherFirst, head.group));
            for (Box box : candidate.group) {
                int rem = Math.max(remainingBox(first, box.id), remainingBox(otherFirst, box.id));
                if (rem > 0) candidate.parked.add(box.name + " ~" + rem + " left");
            }
            for (Work work : input.active) candidate.waitingAfterFirst.add(new Work(work.id, work.color,
                    Math.max(remainingBox(first, work.id), remainingBox(otherFirst, work.id)), work.group));
            candidate.immediate = !hasGroup(first, head.group) && !hasGroup(otherFirst, head.group);
            SearchResult result = search(first, initial, head);
            if (result != null) {
                State verified = replay(initial, result.state.route, true);
                candidate.recommended = true;
                candidate.route.addAll(result.state.route);
                candidate.followups = candidate.immediate ? 0 : Math.max(
                        recoveryStep(initial, result.state.route, head.group, false),
                        recoveryStep(initial, result.state.route, head.group, true)) - 1;
                candidate.peakSlots = Math.max(result.state.peak, verified.peak);
                candidate.removed = Math.min(result.state.removed, verified.removed);
                candidate.score = result.score;
                candidate.reason = candidate.immediate ? "Estimated to clear without another click" :
                        "Temporary park; recovery in " + candidate.followups + " more click(s)";
            } else {
                candidate.reason = "Avoid parking: no verified short return route in the visible rows";
            }
            candidates.add(candidate);
        }
        Candidate best = null;
        for (Candidate c : candidates) if (c.recommended && (best == null ||
                (c.immediate && !best.immediate) || (c.immediate == best.immediate && c.score > best.score))) best = c;
        return new Plan(candidates, best, slots(initial), explored);
    }

    private SearchResult search(State first, State initial, Box root) {
        List<State> beam = new ArrayList<>(); beam.add(first);
        SearchResult best = null;
        int nodes = 0;
        for (int depth = 1; depth <= LOOKAHEAD; depth++) {
            List<State> next = new ArrayList<>();
            for (State state : beam) {
                boolean firstClear = !hasGroup(state, root.group);
                if (firstClear) {
                    State other = replay(initial, state.route, true);
                    if (other != null && !hasGroup(other, root.group)) {
                        int a = recoveryStep(initial, state.route, root.group, false);
                        int b = recoveryStep(initial, state.route, root.group, true);
                        boolean parkProof = Math.max(a, b) <= MAX_PARK_FOLLOWUPS + 1 &&
                                !hasNewWork(state) && !hasNewWork(other);
                        if (parkProof) {
                            double score = Math.min(score(state), score(other));
                            if (best == null || score > best.score) {
                                best = new SearchResult(); best.state = state; best.score = score;
                            }
                        }
                    }
                }
                if (depth == LOOKAHEAD) continue;
                Set<Integer> groups = new HashSet<>();
                for (int c = 0; c < input.columns.length; c++) {
                    Box box = head(state, c);
                    if (box == null || !groups.add(box.group) || blockedReason(state, box) != null) continue;
                    if (++nodes > MAX_NODES_PER_COLUMN) break;
                    explored++;
                    next.add(take(state, box, false));
                }
            }
            if (next.isEmpty()) break;
            next.sort(Comparator.comparingDouble((State s) -> -score(s)));
            beam = new ArrayList<>(next.subList(0, Math.min(BEAM_WIDTH, next.size())));
        }
        return best;
    }

    private State replay(State initial, List<Box> route, boolean reverse) {
        State state = initial.copy();
        for (Box box : route) {
            if (blockedReason(state, box) != null) return null;
            state = take(state, box, reverse);
        }
        return state;
    }

    private int recoveryStep(State initial, List<Box> route, int group, boolean reverse) {
        State state = initial.copy();
        for (int i = 0; i < route.size(); i++) {
            state = take(state, route.get(i), reverse);
            if (!hasGroup(state, group)) return i + 1;
        }
        return 999;
    }

    private State take(State before, Box box, boolean reverse) {
        State out = before.copy();
        List<Box> members = group(out, box);
        out.peak = Math.max(out.peak, slots(out) + members.size());
        for (Box b : members) {
            out.used |= 1L << b.id;
            out.active.add(new Work(b.id, b.color, b.count, b.group));
        }
        out.route.add(box);
        settle(out, reverse);
        return out;
    }

    private String blockedReason(State state, Box box) {
        if (used(state, box)) return "Already selected";
        List<Box> members = group(state, box);
        for (Box b : members) {
            if (b.hiddenLink) return "Linked partner continues below the visible rows; rescan first";
            if (b.count <= 0 || b.color < 0) return "A block number or color is unreadable; rescan first";
            for (Box earlier : input.columns[b.column]) {
                if (earlier.row >= b.row) break;
                if (!used(state, earlier) && earlier.group != b.group)
                    return "A connected partner is covered by another block";
            }
        }
        int entry = slots(state) + members.size();
        if (entry > 5) return "Needs " + members.size() + " free slots (only " + (5 - slots(state)) + " free)";
        if (input.reserveOne && entry > 4) return "Would use the last slot; Keep 1 slot free is enabled";
        return null;
    }

    private List<Box> group(State state, Box box) {
        List<Box> members = new ArrayList<>();
        for (Box[] column : input.columns) for (Box b : column)
            if (b.group == box.group && !used(state, b)) members.add(b);
        members.sort(Comparator.comparingInt((Box b) -> b.row).thenComparingInt(b -> b.column));
        return members;
    }

    private Box head(State state, int column) {
        for (Box b : input.columns[column]) if (!used(state, b)) return b;
        return null;
    }

    private boolean used(State state, Box box) { return (state.used & (1L << box.id)) != 0; }
    private int slots(State state) { return input.opaqueSlots + state.active.size(); }
    private boolean hasNewWork(State state) { for (Work w : state.active) if (w.id >= 0) return true; return false; }
    private boolean hasGroup(State state, int group) { for (Work w : state.active) if (w.group == group) return true; return false; }
    private int remaining(State state, int group) { int n = 0; for (Work w : state.active) if (w.group == group) n += w.remaining; return n; }
    private int remainingBox(State state, int id) { for (Work w : state.active) if (w.id == id) return w.remaining; return 0; }

    private double score(State state) {
        int selected = Long.bitCount(state.used);
        int pending = 0;
        for (Work w : state.active) if (w.id >= 0) pending++;
        return (selected - pending) * 150.0 + state.removed * 0.6 -
                state.active.size() * 100.0 - state.peak * 20.0 - state.route.size() * 3.0;
    }

    private void settle(State state, boolean reverse) {
        boolean progress;
        int[] distances = new int[(input.width + 2) * (input.height + 2)], queue = new int[distances.length];
        do {
            progress = false;
            walkingDistances(state.cells, input.width, input.height, distances, queue);
            // Colonies work concurrently. Give each active color one target per round.
            for (int n = 0; n < state.active.size(); n++) {
                Work work = state.active.get(reverse ? state.active.size() - 1 - n : n);
                if (work.color < 0 || work.remaining <= 0) continue;
                int i = nearestTarget(state.cells, input.width, input.height, work.color, distances, reverse);
                if (i >= 0) {
                    state.cells[i] = -1; work.remaining--; state.removed++; progress = true;
                }
            }
        } while (progress);
        Set<Integer> unfinished = new HashSet<>();
        for (Work w : state.active) if (w.remaining > 0) unfinished.add(w.group);
        // Reserve all members of a linked group until every member finishes.
        state.active.removeIf(w -> !unfinished.contains(w.group));
    }

    private static void walkingDistances(int[] cells, int width, int height, int[] distances, int[] queue) {
        int pw = width + 2, ph = height + 2;
        Arrays.fill(distances, -1);
        int first = 0, last = 0, source = (ph - 1) * pw + (width + 1) / 2;
        distances[source] = 0; queue[last++] = source;
        if (width % 2 == 0) { distances[source + 1] = 0; queue[last++] = source + 1; }
        while (first < last) {
            int i = queue[first++], x = i % pw, y = i / pw;
            if (x > 0) last = walk(i - 1, i, cells, width, height, distances, queue, last);
            if (x + 1 < pw) last = walk(i + 1, i, cells, width, height, distances, queue, last);
            if (y > 0) last = walk(i - pw, i, cells, width, height, distances, queue, last);
            if (y + 1 < ph) last = walk(i + pw, i, cells, width, height, distances, queue, last);
        }
    }

    private static int walk(int i, int from, int[] cells, int width, int height,
                            int[] distances, int[] queue, int last) {
        if (distances[i] >= 0) return last;
        int pw = width + 2, x = i % pw - 1, y = i / pw - 1;
        if (x >= 0 && x < width && y >= 0 && y < height && cells[y * width + x] >= 0) return last;
        distances[i] = distances[from] + 1; queue[last++] = i;
        return last;
    }

    private static int nearestTarget(int[] cells, int width, int height, int color, int[] distances, boolean reverse) {
        int best = -1, bestDistance = Integer.MAX_VALUE, bestCenter = Integer.MAX_VALUE;
        int pw = width + 2;
        for (int i = 0; i < cells.length; i++) if (cells[i] == color) {
            int x = i % width, y = i / width, p = (y + 1) * pw + x + 1;
            int d = Integer.MAX_VALUE;
            if (distances[p - 1] >= 0) d = Math.min(d, distances[p - 1] + 1);
            if (distances[p + 1] >= 0) d = Math.min(d, distances[p + 1] + 1);
            if (distances[p - pw] >= 0) d = Math.min(d, distances[p - pw] + 1);
            if (distances[p + pw] >= 0) d = Math.min(d, distances[p + pw] + 1);
            if (d == Integer.MAX_VALUE) continue;
            int center = Math.abs(2 * x - width + 1);
            if (best < 0 || d < bestDistance || d == bestDistance && (center < bestCenter ||
                    center == bestCenter && (y > best / width || y == best / width &&
                            (reverse ? x > best % width : x < best % width)))) {
                best = i; bestDistance = d; bestCenter = center;
            }
        }
        return best;
    }

    /** Local calibration preview: no input mutation, animation timing, or automated tap. */
    static int[] projectCells(int[] original, int width, int height, int color, int count, boolean reverse) {
        Snapshot input = new Snapshot(width, height, original, new Box[0][], Collections.emptyList(), 0, false);
        MovePlanner planner = new MovePlanner(input);
        State state = new State(); state.cells = original.clone();
        state.active.add(new Work(0, color, count, 0));
        planner.settle(state, reverse);
        return state.cells;
    }

    static boolean[] outside(int[] cells, int width, int height) {
        boolean[] result = new boolean[cells.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            if (x == 0 || x == width - 1 || y == 0 || y == height - 1)
                add(y * width + x, cells, result, queue);
        while (!queue.isEmpty()) {
            int i = queue.removeFirst(), x = i % width, y = i / width;
            if (x > 0) add(i - 1, cells, result, queue);
            if (x + 1 < width) add(i + 1, cells, result, queue);
            if (y > 0) add(i - width, cells, result, queue);
            if (y + 1 < height) add(i + width, cells, result, queue);
        }
        return result;
    }

    private static void add(int i, int[] cells, boolean[] open, ArrayDeque<Integer> q) {
        if (cells[i] < 0 && !open[i]) { open[i] = true; q.addLast(i); }
    }

    static boolean exposed(int i, int[] cells, boolean[] outside, int width, int height) {
        int x = i % width, y = i / width;
        if (x == 0 || x == width - 1 || y == 0 || y == height - 1) return true;
        return outside[i - 1] || outside[i + 1] || outside[i - width] || outside[i + width];
    }

    /** Number reachable through this color alone, before another color opens a path. */
    public static int reachable(int[] original, int width, int height, int color) {
        if (color < 0) return 0;
        int[] cells = original.clone();
        int total = 0;
        boolean progress;
        do {
            progress = false;
            boolean[] open = outside(cells, width, height);
            for (int i = 0; i < cells.length; i++) if (cells[i] == color && exposed(i, cells, open, width, height)) {
                cells[i] = -1; total++; progress = true;
            }
        } while (progress);
        return total;
    }
}
