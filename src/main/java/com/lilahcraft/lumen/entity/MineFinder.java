package com.lilahcraft.lumen.entity;

import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Finds a block Lumen was asked to mine, that it can actually get to and break. */
public final class MineFinder {

    private MineFinder() {
    }

    private record Candidate(BlockPos pos, boolean ore, double distanceSquared) {
    }

    /**
     * Nearest block matching {@code query} that Lumen can reach and is allowed to break.
     *
     * <p>Ore is preferred over anything else that merely shares the word, so "mine some
     * iron" heads for iron ore rather than the iron blocks in somebody's wall.
     *
     * @return the block to mine, or null if there is nothing suitable in range
     */
    @Nullable
    public static BlockPos findNearest(ServerWorld world, LumenEntity lumen, String query,
                                       double radius, int height, Set<BlockPos> exclude) {
        String normalised = query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (normalised.isEmpty()) {
            return null;
        }
        boolean queryNamesOre = normalised.contains("ore");
        BlockPos origin = lumen.getBlockPos();
        int reach = (int) Math.ceil(radius);
        List<Candidate> found = new ArrayList<>();
        BlockPos.Mutable cursor = new BlockPos.Mutable();

        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int dy = -height; dy <= height; dy++) {
                    cursor.set(x, origin.getY() + dy, z);
                    BlockState state = world.getBlockState(cursor);
                    if (!isMineable(world, state, cursor)) {
                        continue;
                    }
                    String path = Registries.BLOCK.getId(state.getBlock()).getPath();
                    if (!path.contains(normalised)) {
                        continue;
                    }
                    BlockPos pos = cursor.toImmutable();
                    if (exclude.contains(pos)) {
                        continue;
                    }
                    found.add(new Candidate(pos, queryNamesOre || path.contains("ore"),
                            pos.getSquaredDistance(origin)));
                }
            }
        }

        return found.stream()
                .sorted(Comparator.comparing(Candidate::ore).reversed()
                        .thenComparingDouble(Candidate::distanceSquared))
                // Reachability is the expensive check, so it runs last and only until
                // the first candidate that passes.
                .filter(candidate -> lumen.findApproach(candidate.pos()) != null)
                .map(Candidate::pos)
                .findFirst()
                .orElse(null);
    }

    /** Air, liquids, unbreakable blocks and anything holding items are all off limits. */
    public static boolean isMineable(ServerWorld world, BlockState state, BlockPos pos) {
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        // Never break a container: mining somebody's chest is not a favour.
        if (state.hasBlockEntity()) {
            return false;
        }
        float hardness = state.getHardness(world, pos);
        return hardness >= 0.0F;
    }
}
