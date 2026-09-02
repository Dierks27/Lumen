package com.lilahcraft.lumen.entity.goal;

import com.lilahcraft.lumen.Lumen;
import com.lilahcraft.lumen.LumenConfig;
import com.lilahcraft.lumen.entity.LumenEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.Box;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Walks over to dropped items and collects them. Sits below the follow and attack
 * goals, so Lumen only detours for loot when it is not busy keeping up or fighting.
 */
public class LumenPickUpItemGoal extends Goal {

    private final LumenEntity lumen;
    private ItemEntity target;
    private int repathCountdown;

    public LumenPickUpItemGoal(LumenEntity lumen) {
        this.lumen = lumen;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        LumenConfig config = Lumen.config();
        if (!config.pickUpItems || config.pickUpRadius <= 0.0D) {
            return false;
        }
        this.target = findNearestItem(config.pickUpRadius);
        return this.target != null;
    }

    @Override
    public boolean shouldContinue() {
        LumenConfig config = Lumen.config();
        if (target == null || !target.isAlive() || target.isRemoved()) {
            return false;
        }
        // A little slack over the search radius so Lumen finishes a trip it started.
        double limit = config.pickUpRadius * 1.5D;
        return lumen.squaredDistanceTo(target) <= limit * limit;
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
        // The item itself is collected by LumenEntity#tick once Lumen is on top of it.
        lumen.getNavigation().startMovingTo(target, 1.0D);
    }

    private ItemEntity findNearestItem(double radius) {
        Box box = lumen.getBoundingBox().expand(radius);
        List<ItemEntity> items = lumen.getWorld().getEntitiesByClass(ItemEntity.class, box,
                item -> item.isAlive() && !item.cannotPickup());
        return items.stream()
                .min(Comparator.comparingDouble(lumen::squaredDistanceTo))
                .orElse(null);
    }
}
