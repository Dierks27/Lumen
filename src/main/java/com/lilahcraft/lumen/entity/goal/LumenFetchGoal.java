package com.lilahcraft.lumen.entity.goal;

import com.lilahcraft.lumen.Lumen;
import com.lilahcraft.lumen.entity.LumenEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.BlockPos;

import java.util.EnumSet;

/** Walks Lumen to the container it is fetching from, then empties the match out of it. */
public class LumenFetchGoal extends Goal {

    private final LumenEntity lumen;
    private BlockPos chest;
    private int repathCountdown;
    private int giveUpTicks;
    /** Consecutive attempts that produced no path going anywhere. */
    private int noRouteRounds;

    public LumenFetchGoal(LumenEntity lumen) {
        this.lumen = lumen;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        this.chest = lumen.getFetchChest();
        return this.chest != null;
    }

    @Override
    public boolean shouldContinue() {
        BlockPos current = lumen.getFetchChest();
        return current != null && current.equals(chest) && giveUpTicks < 20 * 90;
    }

    @Override
    public void start() {
        this.repathCountdown = 0;
        this.giveUpTicks = 0;
        this.noRouteRounds = 0;
    }

    @Override
    public void stop() {
        this.chest = null;
        lumen.getNavigation().stop();
    }

    @Override
    public boolean shouldRunEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        this.giveUpTicks++;
        if (chest == null) {
            return;
        }
        // Standing on a neighbouring block leaves Lumen ~1.5 blocks away diagonally.
        if (lumen.getBlockPos().isWithinDistance(chest, 3.0D)) {
            lumen.collectFromChest();
            return;
        }
        if (--this.repathCountdown > 0) {
            return;
        }
        this.repathCountdown = this.getTickCount(15);
        // A chest is a solid block: pathing at it finds no node and reports failure.
        boolean moving = lumen.moveToBlock(chest, Lumen.config().followSpeedMultiplier);
        if (moving) {
            this.noRouteRounds = 0;
            return;
        }
        // Three rounds with nowhere to go is a verdict, not a hiccup. Move on to the
        // next container rather than standing here in silence.
        if (++this.noRouteRounds >= 3 || (lumen.getNavigation().isIdle() && giveUpTicks > 60)) {
            lumen.fetchUnreachable();
        }
    }
}
