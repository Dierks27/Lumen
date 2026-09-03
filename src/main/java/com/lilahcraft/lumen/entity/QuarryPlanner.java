package com.lilahcraft.lumen.entity;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns "mine out a 20x20x2 at level 2" into a region and an order to dig it in.
 * Pure Java, unit tested; the entity turns the positions into work.
 */
public final class QuarryPlanner {

    private QuarryPlanner() {
    }

    /** An axis-aligned box of blocks, inclusive. */
    public record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public static Region of(int x1, int y1, int z1, int x2, int y2, int z2) {
            return new Region(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                    Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
        }

        public int sizeX() {
            return maxX - minX + 1;
        }

        public int sizeY() {
            return maxY - minY + 1;
        }

        public int sizeZ() {
            return maxZ - minZ + 1;
        }

        public long volume() {
            return (long) sizeX() * sizeY() * sizeZ();
        }

        public String describe() {
            return sizeX() + "x" + sizeZ() + "x" + sizeY() + " from " + minX + "," + minY + "," + minZ
                    + " to " + maxX + "," + maxY + "," + maxZ;
        }
    }

    /**
     * What a sentence asked for. {@code targetY} is the TOP layer to dig, or null for
     * "start under my feet". {@code selection} means "use the wand selection".
     */
    public record Spec(int sizeX, int sizeZ, int height, @Nullable Integer targetY, boolean selection) {
        public boolean hasSize() {
            return sizeX > 0 && sizeZ > 0 && height > 0;
        }
    }

    private static final Pattern DIMS3 = Pattern.compile("\\b(\\d{1,3})\\s*(?:x|by|\\*)\\s*(\\d{1,3})\\s*(?:x|by|\\*)\\s*(\\d{1,3})\\b");
    private static final Pattern DIMS2 = Pattern.compile("\\b(\\d{1,3})\\s*(?:x|by|\\*)\\s*(\\d{1,3})\\b");
    private static final Pattern TALL = Pattern.compile("\\b(\\d{1,2})\\s*(?:blocks?\\s+)?(?:tall|high|deep|down)\\b");
    private static final Pattern LEVEL = Pattern.compile("\\b(?:level|y\\s*=?|at\\s+y|height)\\s*(-?\\d{1,3})\\b");
    private static final Pattern SELECTION = Pattern.compile("\\b(selection|selected|the\\s+area\\s+i\\s+marked|marked\\s+area|wand)\\b");
    private static final Pattern QUARRY_WORDS = Pattern.compile("\\b(out|quarry|area|region|clear|room|hole|pit|shaft|strip)\\b");

    /** Y levels people name. */
    static final int BEDROCK_LEVEL = -60;
    static final int DIAMOND_LEVEL = -58;

    /**
     * Reads a region request. Returns null when the sentence does not describe one -
     * "mine copper" stays an ordinary mine.
     */
    @Nullable
    public static Spec parse(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        boolean selection = SELECTION.matcher(lower).find();
        int sx = 0;
        int sz = 0;
        int h = 0;
        Matcher d3 = DIMS3.matcher(lower);
        Matcher d2 = DIMS2.matcher(lower);
        if (d3.find()) {
            sx = Integer.parseInt(d3.group(1));
            sz = Integer.parseInt(d3.group(2));
            h = Integer.parseInt(d3.group(3));
        } else if (d2.find()) {
            sx = Integer.parseInt(d2.group(1));
            sz = Integer.parseInt(d2.group(2));
            h = 1;
            Matcher tall = TALL.matcher(lower);
            if (tall.find()) {
                h = Integer.parseInt(tall.group(1));
            }
        }
        Integer targetY = null;
        Matcher level = LEVEL.matcher(lower);
        if (level.find()) {
            targetY = Integer.parseInt(level.group(1));
        } else if (lower.contains("bedrock")) {
            targetY = BEDROCK_LEVEL;
        } else if (lower.contains("diamond level") || lower.contains("diamond height")) {
            targetY = DIAMOND_LEVEL;
        }
        if (!selection && (sx == 0 || sz == 0)) {
            return null;
        }
        return new Spec(sx, sz, Math.max(1, h), targetY, selection);
    }

    /** True when the words around a mine request say "dig an area", even without numbers. */
    public static boolean soundsLikeQuarry(@Nullable String text) {
        return text != null && (parse(text) != null || QUARRY_WORDS.matcher(text.toLowerCase(Locale.ROOT)).find());
    }

    /**
     * The region for a sized request, centred on the anchor horizontally. The top layer
     * is {@code targetY} when given, else the block under the anchor's feet.
     */
    public static Region regionAround(int anchorX, int anchorFeetY, int anchorZ, Spec spec) {
        int top = spec.targetY() != null ? spec.targetY() : anchorFeetY - 1;
        int halfX = spec.sizeX() / 2;
        int halfZ = spec.sizeZ() / 2;
        int minX = anchorX - halfX;
        int minZ = anchorZ - halfZ;
        return Region.of(minX, top, minZ, minX + spec.sizeX() - 1, top - spec.height() + 1, minZ + spec.sizeZ() - 1);
    }

    /**
     * Digging order: top layer first, rows in a serpentine so Lumen never crosses the
     * whole floor to reach the next block, each row starting nearest the anchor.
     * Positions are {x, y, z}.
     */
    public static List<int[]> order(Region region, int anchorX, int anchorZ) {
        List<int[]> out = new ArrayList<>();
        boolean startAtMinX = Math.abs(anchorX - region.minX()) <= Math.abs(anchorX - region.maxX());
        boolean startAtMinZ = Math.abs(anchorZ - region.minZ()) <= Math.abs(anchorZ - region.maxZ());
        for (int y = region.maxY(); y >= region.minY(); y--) {
            int rows = region.sizeZ();
            for (int r = 0; r < rows; r++) {
                int z = startAtMinZ ? region.minZ() + r : region.maxZ() - r;
                boolean forward = (r % 2 == 0) == startAtMinX;
                for (int c = 0; c < region.sizeX(); c++) {
                    int x = forward ? region.minX() + c : region.maxX() - c;
                    out.add(new int[] {x, y, z});
                }
            }
        }
        return out;
    }

    /**
     * A one-wide staircase from standing at {@code (x, feetY, z)} down to feet at
     * {@code toFeetY}, stepping one block along {@code (dx, dz)} per step. Each step
     * clears the foot block and the head block. Positions are {x, y, z}, in the order to
     * break them.
     */
    public static List<int[]> staircase(int x, int feetY, int z, int toFeetY, int dx, int dz) {
        List<int[]> out = new ArrayList<>();
        if (dx == 0 && dz == 0) {
            dx = 1;
        }
        int cx = x;
        int cz = z;
        for (int y = feetY - 1; y >= toFeetY; y--) {
            cx += dx;
            cz += dz;
            out.add(new int[] {cx, y + 1, cz}); // head first, so the space is open before stepping down
            out.add(new int[] {cx, y, cz});
        }
        return out;
    }

    /** How far the top of the region is below the anchor's feet, for deciding on a staircase. */
    public static int descent(int anchorFeetY, Region region) {
        return anchorFeetY - 1 - region.maxY();
    }
}
