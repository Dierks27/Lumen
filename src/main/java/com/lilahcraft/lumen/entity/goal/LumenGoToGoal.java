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
        if (!moving && lumen.getNavigation().isIdle()) {
            // Unreachable - stop pretending and go back to idling.
            lumen.stopAndIdle();
        }
    }
}
