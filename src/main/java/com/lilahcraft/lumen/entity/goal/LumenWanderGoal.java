package com.lilahcraft.lumen.entity.goal;

import com.lilahcraft.lumen.entity.LumenEntity;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;

/** Vanilla wandering, but only while Lumen has nothing else to do. */
public class LumenWanderGoal extends WanderAroundFarGoal {

    private final LumenEntity lumen;

    public LumenWanderGoal(LumenEntity lumen, double speed) {
        super(lumen, speed);
        this.lumen = lumen;
    }

    @Override
    public boolean canStart() {
        return lumen.getMode() == LumenEntity.Mode.IDLE && super.canStart();
    }

    @Override
    public boolean shouldContinue() {
        return lumen.getMode() == LumenEntity.Mode.IDLE && super.shouldContinue();
    }
}
