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

    /** A container that holds a match, and whether the match was on the exact item id. */
    public record Match(BlockPos pos, boolean exact, double distanceSquared) {
    }

    @Nullable
    public static Match findContainerWith(ServerWorld world, BlockPos center, double radius, String query) {
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
                    if (distanceSquared > radiusSquared || !(entry.getValue() instanceof Inventory inventory)) {
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
