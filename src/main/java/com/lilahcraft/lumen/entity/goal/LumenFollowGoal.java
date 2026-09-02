package com.lilahcraft.lumen.entity.goal;

import com.lilahcraft.lumen.Lumen;
import com.lilahcraft.lumen.LumenConfig;
import com.lilahcraft.lumen.entity.LumenEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.player.PlayerEntity;

import java.util.EnumSet;

/**
 * Walks after {@link LumenEntity#getFollowTarget()} using vanilla navigation, and
 * warps if the player gets far enough away that pathing has clearly lost them
 * (elytra, boats, a nether portal, a 300 block staircase).
 */
public class LumenFollowGoal extends Goal {

    private final LumenEntity lumen;
    private PlayerEntity target;
    private int repathCountdown;

    public LumenFollowGoal(LumenEntity lumen) {
        this.lumen = lumen;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        PlayerEntity candidate = lumen.getFollowTarget();
        if (candidate == null) {
            return false;
        }
        LumenConfig config = Lumen.config();
        if (lumen.squaredDistanceTo(candidate) < config.followStartDistance * config.followStartDistance) {
            return false;
        }
        this.target = candidate;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        if (target == null || lumen.getFollowTarget() != target) {
            return false;
        }
        LumenConfig config = Lumen.config();
        return lumen.squaredDistanceTo(target) > config.followStopDistance * config.followStopDistance;
    }

    @Override
    public void start() {
        this.repathCountdown = 0;
    }

    @Override
    public void stop() {
        this.target = null;
        lumen.getNavigation().stop();
    }

    @Override
    public boolean shouldRunEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (target == null) {
            return;
        }
        lumen.getLookControl().lookAt(target, 10.0F, (float) lumen.getMaxLookPitchChange());

        if (--this.repathCountdown > 0) {
            return;
        }
        this.repathCountdown = this.getTickCount(10);

        LumenConfig config = Lumen.config();
        double distanceSquared = lumen.squaredDistanceTo(target);
        if (distanceSquared > config.teleportDistance * config.teleportDistance) {
            lumen.teleportNear(target.getBlockPos());
            return;
        }

        EntityNavigation navigation = lumen.getNavigation();
        if (!navigation.startMovingTo(target, config.followSpeedMultiplier) && !lumen.hasVehicle()) {
            // Nothing walkable between here and there; only warp once it is far enough
            // away to be obviously stuck rather than briefly blocked by a fence.
            if (distanceSquared > 12.0D * 12.0D) {
                lumen.teleportNear(target.getBlockPos());
            }
        }
    }
}
