package com.lilahcraft.lumen.entity;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
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
 * <p>Works off {@link Inventory} rather than {@code ChestBlockEntity}, so modded
 * crates, barrels and storage blocks are searched too. Containers are read out of
 * each chunk's block entity map instead of by scanning blocks, which keeps a 16
 * block search to a few hundred lookups rather than tens of thousands.
 */
public final class ChestFinder {

    private ChestFinder() {
    }

    /** How much of what: "10 stone" -> 10 and "stone". */
    public record Request(int count, String query) {
    }

    /**
     * Reads a quantity off the front of a request.
     *
     * <p>Handles "10 stone", "a stack of cobblestone", "all the sticks", "half a stack
     * of iron" - the ways someone actually asks - and falls back to {@code defaultCount}
     * when no amount is given.
     */
    public static Request parseRequest(String argument, int defaultCount) {
        String text = argument == null ? "" : argument.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return new Request(defaultCount, "");
        }
        Matcher all = Pattern.compile("^all(?:\\s+(?:of\\s+)?(?:the\\s+)?)?(.*)$").matcher(text);
        if (all.matches()) {
            return new Request(Integer.MAX_VALUE, all.group(1).trim());
        }
        Matcher halfStack = Pattern.compile("^half\\s+(?:a\\s+|the\\s+)?stacks?\\s+(?:of\\s+)?(.*)$")
                .matcher(text);
        if (halfStack.matches()) {
            return new Request(32, halfStack.group(1).trim());
        }
        Matcher stack = Pattern.compile("^(?:a\\s+|one\\s+)?stacks?\\s+(?:of\\s+)?(.*)$").matcher(text);
        if (stack.matches()) {
            return new Request(64, stack.group(1).trim());
        }
        Matcher counted = Pattern.compile("^(\\d{1,5})\\s+(?:of\\s+)?(.*)$").matcher(text);
        if (counted.matches()) {
            int count = Math.max(1, Integer.parseInt(counted.group(1)));
            return new Request(count, counted.group(2).trim());
        }
        return new Request(defaultCount, text);
    }

    /** A container that holds a match, and whether the match was on the exact item id. */
    public record Match(BlockPos pos, boolean exact, double distanceSquared) {
    }

    @Nullable
    public static Match findContainerWith(ServerWorld world, BlockPos center, double radius, String query,
                                          Set<BlockPos> exclude) {
        if (query == null || query.isBlank() || radius <= 0.0D) {
            return null;
        }
        List<Match> matches = new ArrayList<>();
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
                    if (distanceSquared > radiusSquared || exclude.contains(pos)
                            || !(entry.getValue() instanceof Inventory inventory)) {
                        continue;
                    }
                    Boolean exact = bestMatchIn(inventory, query);
                    if (exact != null) {
                        matches.add(new Match(pos.toImmutable(), exact, distanceSquared));
                    }
                }
            }
        }
        // An exact item id beats a fuzzy one; among equals, the closest wins.
        return matches.stream()
                .min(Comparator.comparing(Match::exact).reversed()
                        .thenComparingDouble(Match::distanceSquared))
                .orElse(null);
    }

    /**
     * Fills {@code searchable} and {@code skipped} with the block ids of nearby block
     * entities, split by whether Lumen can read them. Modded storage that does not
     * implement {@link Inventory} lands in skipped - which is exactly the list needed
     * to decide what to add support for.
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
                    if (entry.getValue() instanceof Inventory) {
                        searchable.add(id);
                    } else {
                        skipped.add(id);
                    }
                }
            }
        }
    }

    /** Whether this container holds anything matching the query right now. */
    public static boolean containsMatch(Inventory inventory, String query) {
        return bestMatchIn(inventory, query) != null;
    }

    /** @return TRUE for an exact id match, FALSE for a fuzzy one, null for no match */
    @Nullable
    private static Boolean bestMatchIn(Inventory inventory, String query) {
        boolean fuzzy = false;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (isExactMatch(stack, query)) {
                return Boolean.TRUE;
            }
            if (matches(stack, query)) {
                fuzzy = true;
            }
        }
        return fuzzy ? Boolean.FALSE : null;
    }

    public static boolean isExactMatch(ItemStack stack, String query) {
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id.getPath().equals(normalise(query));
    }

    /** Loose match so "iron" finds iron ingots and "red wool" finds red_wool. */
    public static boolean matches(ItemStack stack, String query) {
        if (stack.isEmpty() || query == null || query.isBlank()) {
            return false;
        }
        String normalised = normalise(query);
        Identifier id = Registries.ITEM.getId(stack.getItem());
        if (id.getPath().contains(normalised)) {
            return true;
        }
        return stack.getName().getString().toLowerCase(Locale.ROOT)
                .contains(query.trim().toLowerCase(Locale.ROOT));
    }

    private static String normalise(String query) {
        return query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
