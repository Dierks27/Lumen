package com.lilahcraft.lumen.entity;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a nearby container holding what somebody asked for.
 *
 * <p>Containers are read out of each chunk's block entity map instead of by scanning
 * blocks, which keeps a 48 block search to a few hundred lookups rather than tens of
 * thousands. Whether a block entity counts as a container is decided by
 * {@link ContainerAccess}, so modded storage networks are included.
 */
public final class ChestFinder {

    private ChestFinder() {
    }

    // ------------------------------------------------------------- quantities

    /**
     * How much of what.
     *
     * <p>{@code count} is a plain number of items. {@code stacks} is non-zero when the
     * amount was given in stacks, and has to be resolved against the item itself: a
     * stack is as many as fit in one slot, which is 64 for most things but 16 for ender
     * pearls and eggs, and 1 for tools and armour. {@code explicit} says whether an
     * amount was actually written down, as opposed to falling back to the default -
     * the difference matters when the model drops the number the player said.
     */
    public record Request(int count, double stacks, String query, boolean explicit) {

        public Request(int count, double stacks, String query) {
            this(count, stacks, query, true);
        }

        /** The same request, for a different item. */
        public Request withQuery(String newQuery) {
            return new Request(count, stacks, newQuery, explicit);
        }

        /** This request's amount applied to {@code other}'s item. */
        public Request applyAmountTo(Request other) {
            return new Request(count, stacks, other.query(), explicit);
        }

        public boolean isEverything() {
            return count == Integer.MAX_VALUE;
        }
    }

    private static final Pattern ALL = Pattern.compile("^all(?:\\s+(?:of\\s+)?(?:the\\s+)?)?(.*)$");
    private static final Pattern HALF_STACK =
            Pattern.compile("^half\\s+(?:a\\s+|the\\s+)?stacks?\\s+(?:of\\s+)?(.*)$");
    private static final Pattern ONE_STACK = Pattern.compile("^(?:a\\s+|one\\s+)?stacks?\\s+(?:of\\s+)?(.*)$");
    private static final Pattern STACKS_OF = Pattern.compile("^(\\d{1,3})\\s+stacks?\\s+(?:of\\s+)?(.*)$");
    private static final Pattern DOZEN = Pattern.compile("^(?:a\\s+)?dozen\\s+(?:of\\s+)?(.*)$");
    private static final Pattern COUPLE = Pattern.compile("^(?:a\\s+)?couple\\s+(?:of\\s+)?(.*)$");
    private static final Pattern FEW = Pattern.compile("^(?:a\\s+)?few\\s+(?:of\\s+)?(.*)$");
    private static final Pattern COUNTED = Pattern.compile("^(\\d{1,5})\\s*x?\\s+(?:of\\s+)?(.*)$");
    private static final Pattern TRAILING = Pattern.compile("^(.*?\\S)\\s*(?:x\\s*|\\(\\s*)?(\\d{1,5})\\)?$");

    /**
     * Reads a quantity off a request.
     *
     * <p>Handles "10 stone", "12x redstone", "redstone x12", "a stack of cobblestone",
     * "a dozen wool", "all the sticks", "half a stack of iron" - the ways someone
     * actually asks - and falls back to {@code defaultCount} when no amount is given.
     */
    public static Request parseRequest(String argument, int defaultCount) {
        String text = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        text = text.replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return new Request(defaultCount, 0.0D, "", false);
        }
        Matcher all = ALL.matcher(text);
        if (all.matches()) {
            return new Request(Integer.MAX_VALUE, 0.0D, all.group(1).trim(), true);
        }
        Matcher halfStack = HALF_STACK.matcher(text);
        if (halfStack.matches()) {
            return new Request(0, 0.5D, halfStack.group(1).trim(), true);
        }
        Matcher stack = ONE_STACK.matcher(text);
        if (stack.matches()) {
            return new Request(0, 1.0D, stack.group(1).trim(), true);
        }
        Matcher stacksOf = STACKS_OF.matcher(text);
        if (stacksOf.matches()) {
            return new Request(0, Integer.parseInt(stacksOf.group(1)), stacksOf.group(2).trim(), true);
        }
        Matcher dozen = DOZEN.matcher(text);
        if (dozen.matches()) {
            return new Request(12, 0.0D, dozen.group(1).trim(), true);
        }
        Matcher couple = COUPLE.matcher(text);
        if (couple.matches()) {
            return new Request(2, 0.0D, couple.group(1).trim(), true);
        }
        Matcher few = FEW.matcher(text);
        if (few.matches()) {
            return new Request(3, 0.0D, few.group(1).trim(), true);
        }
        Matcher counted = COUNTED.matcher(text);
        if (counted.matches()) {
            int count = Math.max(1, Integer.parseInt(counted.group(1)));
            return new Request(count, 0.0D, counted.group(2).trim(), true);
        }
        Matcher trailing = TRAILING.matcher(text);
        if (trailing.matches()) {
            int count = Math.max(1, Integer.parseInt(trailing.group(2)));
            return new Request(count, 0.0D, trailing.group(1).trim(), true);
        }
        return new Request(defaultCount, 0.0D, text, false);
    }

    private static final Pattern ANY_ALL = Pattern.compile("\\ball\\s+(?:of\\s+)?(?:the|my|your|our)?\\s*[a-z]");
    private static final Pattern ANY_HALF_STACK = Pattern.compile("\\bhalf\\s+(?:a|the)?\\s*stacks?\\b");
    private static final Pattern ANY_STACKS = Pattern.compile("\\b(\\d{1,3})\\s+stacks?\\b");
    private static final Pattern ANY_STACK = Pattern.compile("\\b(?:a|one|another)\\s+stacks?\\b|\\bstacks?\\s+of\\b");
    private static final Pattern ANY_DOZEN = Pattern.compile("\\b(?:a\\s+)?dozen\\b");
    private static final Pattern ANY_COUPLE = Pattern.compile("\\ba\\s+couple\\b");
    private static final Pattern ANY_FEW = Pattern.compile("\\ba\\s+few\\b");
    private static final Pattern ANY_NUMBER = Pattern.compile("(?<![\\w:.-])(\\d{1,5})\\s*x?(?=\\s+[a-z]|$)");

    /**
     * Finds an amount anywhere in a sentence, for when the model's command dropped
     * the number the player said: "grab me 12 redstone" came back as "find redstone".
     *
     * @return a request whose query is empty, carrying only the amount, or null when
     *         the sentence names no amount
     */
    @Nullable
    public static Request quantityIn(String sentence) {
        if (sentence == null) {
            return null;
        }
        String text = sentence.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return null;
        }
        if (ANY_ALL.matcher(text).find()) {
            return new Request(Integer.MAX_VALUE, 0.0D, "", true);
        }
        if (ANY_HALF_STACK.matcher(text).find()) {
            return new Request(0, 0.5D, "", true);
        }
        Matcher stacks = ANY_STACKS.matcher(text);
        if (stacks.find()) {
            return new Request(0, Integer.parseInt(stacks.group(1)), "", true);
        }
        if (ANY_STACK.matcher(text).find()) {
            return new Request(0, 1.0D, "", true);
        }
        if (ANY_DOZEN.matcher(text).find()) {
            return new Request(12, 0.0D, "", true);
        }
        if (ANY_COUPLE.matcher(text).find()) {
            return new Request(2, 0.0D, "", true);
        }
        if (ANY_FEW.matcher(text).find()) {
            return new Request(3, 0.0D, "", true);
        }
        Matcher number = ANY_NUMBER.matcher(text);
        if (number.find()) {
            return new Request(Math.max(1, Integer.parseInt(number.group(1))), 0.0D, "", true);
        }
        return null;
    }

    // --------------------------------------------------------------- matching

    /** No match at all. */
    public static final int NO_MATCH = 0;
    /** The query appears somewhere inside the name: "cobble" in cobblestone. */
    public static final int SUBSTRING_MATCH = 1;
    /** Every word of the query is a whole word of the name: "oak log" for dark_oak_log. */
    public static final int WORD_MATCH = 2;
    /** The query is the item's id or full name: "stone" is minecraft:stone, not cobblestone. */
    public static final int EXACT_MATCH = 3;

    /**
     * How well a stack answers to a query.
     *
     * <p>Tiered, because "stone" plainly means stone when there is stone to be had, and
     * only means cobblestone when there is not. v0.5 used one substring test for
     * everything and brought back 30 stone and 42 cobblestone for "a stack of stone".
     */
    public static int matchScore(ItemStack stack, String query) {
        if (stack == null || stack.isEmpty() || query == null || query.isBlank()) {
            return NO_MATCH;
        }
        String normalised = normalise(query);
        Identifier id = Registries.ITEM.getId(stack.getItem());
        String path = id.getPath();
        String displayName = stack.getName().getString().trim().toLowerCase(Locale.ROOT);
        String plainQuery = query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (path.equals(normalised) || displayName.equals(plainQuery)
                || singular(path).equals(singular(normalised))) {
            return EXACT_MATCH;
        }
        if (wordsMatch(normalised, path) || wordsMatch(normalised, displayName)) {
            return WORD_MATCH;
        }
        if (path.contains(normalised) || displayName.contains(plainQuery)) {
            return SUBSTRING_MATCH;
        }
        return NO_MATCH;
    }

    public static boolean isExactMatch(ItemStack stack, String query) {
        return matchScore(stack, query) == EXACT_MATCH;
    }

    /** Loose match: anything above {@link #NO_MATCH}. */
    public static boolean matches(ItemStack stack, String query) {
        return matchScore(stack, query) > NO_MATCH;
    }

    /** Every word in the query is a whole word of the name, plurals ignored. */
    static boolean wordsMatch(String query, String name) {
        String[] queryWords = words(query);
        if (queryWords.length == 0) {
            return false;
        }
        String[] nameWords = words(name);
        for (String wanted : queryWords) {
            boolean found = false;
            String wantedSingular = singular(wanted);
            for (String present : nameWords) {
                if (present.equals(wanted) || singular(present).equals(wantedSingular)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static String[] words(String text) {
        String cleaned = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        return cleaned.isEmpty() ? new String[0] : cleaned.split(" ");
    }

    /** "torches" -> "torch", "logs" -> "log", "berries" -> "berry", "glass" -> "glass". */
    static String singular(String word) {
        if (word.endsWith("ies") && word.length() > 4) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes") || word.endsWith("sses")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 3) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    /** The best score any stack in {@code contents} gets against {@code query}. */
    public static int bestScore(List<ItemStack> contents, String query) {
        int best = NO_MATCH;
        for (ItemStack stack : contents) {
            best = Math.max(best, matchScore(stack, query));
            if (best == EXACT_MATCH) {
                break;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- search

    /** A container that holds a match, how good the match is, and how far away it is. */
    public record Match(BlockPos pos, int score, double distanceSquared) {
    }

    /**
     * Every container within {@code radius} holding something that matches, best
     * first: by match tier, then by distance. The caller walks the list and takes the
     * first one it can actually get to.
     *
     * @param minScore the weakest match still worth listing - once Lumen has real stone
     *                 in hand, cobblestone elsewhere stops counting
     * @param sameAs   an item already taken this errand; a container holding it is
     *                 scored as an exact match so the order stays consistent
     */
    public static List<Match> findContainersWith(ServerWorld world, BlockPos center, double radius, String query,
                                                 int minScore, @Nullable ItemStack sameAs, Set<BlockPos> exclude) {
        List<Match> matches = new ArrayList<>();
        if (query == null || query.isBlank() || radius <= 0.0D) {
            return matches;
        }
        double radiusSquared = radius * radius;
        int chunkRadius = ((int) radius >> 4) + 1;
        ChunkPos origin = new ChunkPos(center);

        for (int cx = origin.x - chunkRadius; cx <= origin.x + chunkRadius; cx++) {
            for (int cz = origin.z - chunkRadius; cz <= origin.z + chunkRadius; cz++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz, false);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = entry.getKey();
                    double distanceSquared = pos.getSquaredDistance(center);
                    if (distanceSquared > radiusSquared || exclude.contains(pos)) {
                        continue;
                    }
                    ContainerAccess access = ContainerAccess.at(world, pos);
                    if (access == null) {
                        continue;
                    }
                    int score = scoreContainer(access.contents(), query, sameAs);
                    if (score >= Math.max(SUBSTRING_MATCH, minScore)) {
                        matches.add(new Match(pos.toImmutable(), score, distanceSquared));
                    }
                }
            }
        }
        matches.sort(Comparator.comparingInt(Match::score).reversed()
                .thenComparingDouble(Match::distanceSquared));
        return matches;
    }

    /** Best tier in the container, with an item already in hand counting as exact. */
    public static int scoreContainer(List<ItemStack> contents, String query, @Nullable ItemStack sameAs) {
        int best = NO_MATCH;
        for (ItemStack stack : contents) {
            if (sameAs != null && ItemStack.canCombine(stack, sameAs)) {
                return EXACT_MATCH;
            }
            best = Math.max(best, matchScore(stack, query));
        }
        return best;
    }

    /**
     * Fills {@code searchable} and {@code skipped} with the block ids of nearby block
     * entities, split by whether Lumen can read them. Whatever lands in skipped is
     * exactly the list needed to decide what to add support for.
     */
    public static void describeNearby(ServerWorld world, BlockPos center, double radius,
                                      List<String> searchable, List<String> skipped) {
        double radiusSquared = radius * radius;
        int chunkRadius = ((int) radius >> 4) + 1;
        ChunkPos origin = new ChunkPos(center);
        for (int cx = origin.x - chunkRadius; cx <= origin.x + chunkRadius; cx++) {
            for (int cz = origin.z - chunkRadius; cz <= origin.z + chunkRadius; cz++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz, false);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    if (entry.getKey().getSquaredDistance(center) > radiusSquared) {
                        continue;
                    }
                    String id = Registries.BLOCK.getId(
                            world.getBlockState(entry.getKey()).getBlock()).toString();
                    if (ContainerAccess.isSearchable(world, entry.getKey(), entry.getValue())) {
                        searchable.add(id);
                    } else {
                        skipped.add(id);
                    }
                }
            }
        }
    }

    /** Whether this container holds anything matching the query right now. */
    public static boolean containsMatch(@Nullable ContainerAccess access, String query, int minScore) {
        return access != null && bestScore(access.contents(), query) >= Math.max(SUBSTRING_MATCH, minScore);
    }

    /**
     * Words that mean "everything" rather than naming an item: "give me my stuff",
     * "hand it all back", "drop everything". Null counts as everything too.
     */
    public static boolean meansEverything(@Nullable String query) {
        if (query == null) {
            return true;
        }
        String rest = query.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\b(everything|all|it|them|stuff|things|items|inventory|loot|gear|back|"
                        + "my|your|the|of|that|those|these|please|now|here|over|me|us)\\b", " ")
                .replaceAll("[^a-z0-9]+", " ").trim();
        return rest.isEmpty();
    }

    /** "12 redstone", "a stack of oak planks", "all the sticks" - for traces and chat. */
    public static String describeRequest(Request request) {
        if (request.isEverything()) {
            return "all the " + request.query();
        }
        if (request.stacks() > 0.0D) {
            if (request.stacks() == 0.5D) {
                return "half a stack of " + request.query();
            }
            if (request.stacks() == 1.0D) {
                return "a stack of " + request.query();
            }
            return ((int) request.stacks()) + " stacks of " + request.query();
        }
        return request.count() + " " + request.query();
    }

    /** Plain words for a stack, for chat: "white wool", "dark oak log". */
    public static String plainName(ItemStack stack) {
        return stack.getName().getString().toLowerCase(Locale.ROOT);
    }

    static String normalise(String query) {
        return query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").replace(' ', '_');
    }
}
