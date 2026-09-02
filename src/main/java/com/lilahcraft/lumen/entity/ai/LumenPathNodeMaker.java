package com.lilahcraft.lumen.entity.ai;

import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

/**
 * Vanilla land pathfinding, with one relaxation for modded blocks.
 *
 * <p>Vanilla decides passability through {@code AbstractBlock#canPathfindThrough},
 * which mods routinely leave at a default that reports their block as solid even
 * when it has no collision box at all - decorative clutter, plants, cables, pipes.
 * Those blocks come back {@link PathNodeType#BLOCKED} and a room full of them has no
 * route through it.
 *
 * <p>So: if the pathfinder says BLOCKED but the block cannot actually be collided
 * with, treat it as open. Nothing else changes, and the check is a single
 * non-recursive shape lookup - unlike the recursive walkability checks that made
 * Automatone blow the stack on modded blocks.
 */
public class LumenPathNodeMaker extends LandPathNodeMaker {

    @Override
    public PathNodeType getDefaultNodeType(BlockView world, int x, int y, int z) {
        PathNodeType type = super.getDefaultNodeType(world, x, y, z);
        if (type != PathNodeType.BLOCKED) {
            return type;
        }
        return isPhysicallyPassable(world, x, y, z) ? PathNodeType.OPEN : type;
    }

    /** True when the block has no collision box, whatever it claims about pathfinding. */
    public static boolean isPhysicallyPassable(BlockView world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }
}
