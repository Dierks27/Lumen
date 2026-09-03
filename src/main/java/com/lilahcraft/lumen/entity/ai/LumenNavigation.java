package com.lilahcraft.lumen.entity.ai;

import com.lilahcraft.lumen.Lumen;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.ai.pathing.PathNodeNavigator;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.world.World;

/**
 * Ground navigation that uses {@link LumenPathNodeMaker} instead of the vanilla one, with
 * a bigger search budget.
 *
 * <p>Vanilla gives a mob {@code followRange * 16} nodes to find a path - 768 for Lumen.
 * A* spends them heading straight for the target, so when the target is one floor
 * down the search is exhausted pressing against the floor before it ever finds the
 * stairs across the room, and the answer comes back "no path". That is the whole of
 * "I can't reach the hops" from the roof. Lumen is one entity, so it can afford to
 * search {@code pathSearchEffort} times harder.
 */
public class LumenNavigation extends MobNavigation {

    public LumenNavigation(MobEntity entity, World world) {
        super(entity, world);
    }

    @Override
    protected PathNodeNavigator createPathNodeNavigator(int range) {
        this.nodeMaker = new LumenPathNodeMaker();
        this.nodeMaker.setCanEnterOpenDoors(true);
        int effort = Math.max(1, Math.min(64, Lumen.config().pathSearchEffort));
        int budget = (int) Math.min(65536L, (long) Math.max(range, 1) * effort);
        return new PathNodeNavigator(this.nodeMaker, budget);
    }
}
