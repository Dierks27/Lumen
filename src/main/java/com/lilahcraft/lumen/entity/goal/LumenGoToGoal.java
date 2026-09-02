package com.lilahcraft.lumen.entity.goal;

import com.lilahcraft.lumen.Lumen;
import com.lilahcraft.lumen.entity.LumenEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.BlockPos;

import java.util.EnumSet;

/** Walks to a fixed block position, e.g. from the "come" command. */
public class LumenGoToGoal extends Goal {

    private final LumenEntity lumen;
    private BlockPos destination;
    private int repathCountdown;
    private int giveUpTicks;
    private int noRouteRounds;

    public LumenGoToGoal(LumenEntity lumen) {
        this.lumen = lumen;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        BlockPos target = lumen.getDestination();
        if (target == null || lumen.getBlockPos().isWithinDistance(target, 2.0D)) {
            return false;
        }
        this.destination = target;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        BlockPos current = lumen.getDestination();
        return current != null && current.equals(destination)
                && giveUpTicks < 20 * 60
                && !lumen.getBlockPos().isWithinDistance(destination, 2.0D);
    }

    @Override
    public void start() {
        this.repathCountdown = 0;
        this.giveUpTicks = 0;
        this.noRouteRounds = 0;
    }

    @Override
    public void stop() {
        this.destination = null;
        lumen.getNavigation().stop();
    }

    @Override
    public boolean shouldRunEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        this.giveUpTicks++;
        if (destination == null) {
            return;
        }
        if (--this.repathCountdown > 0) {
            return;
        }
        this.repathCountdown = this.getTickCount(20);

        boolean moving = lumen.moveToBlock(destination, Lumen.config().followSpeedMultiplier);
        if (moving) {
            this.noRouteRounds = 0;
            return;
        }
        if (++this.noRouteRounds < 3) {
            return;
        }
        // No route at all. "Come" is a request to be there, so warp if it is close
        // enough to be reasonable, and give up honestly otherwise.
        double warp = Lumen.config().teleportDistance;
        if (!lumen.getBlockPos().isWithinDistance(destination, warp) || !lumen.teleportNear(destination)) {
            lumen.stopAndIdle();
        }
    }
}
