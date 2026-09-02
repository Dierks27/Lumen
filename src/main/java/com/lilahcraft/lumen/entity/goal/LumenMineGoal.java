package com.lilahcraft.lumen.entity.goal;

import com.lilahcraft.lumen.Lumen;
import com.lilahcraft.lumen.entity.LumenEntity;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.EnumSet;

/**
 * Walks to the block Lumen was asked to mine and breaks it, at roughly the speed a
 * player with the same tool would manage.
 */
public class LumenMineGoal extends Goal {

    /** Close enough to swing at it. */
    private static final double REACH = 4.0D;

    private final LumenEntity lumen;
    private BlockPos target;
    private int progressTicks;
    private int requiredTicks;
    private int lastStage = -1;
    private int repathCountdown;
    private int giveUpTicks;

    public LumenMineGoal(LumenEntity lumen) {
        this.lumen = lumen;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    @Override
    public boolean canStart() {
        this.target = lumen.getMineTarget();
        return this.target != null;
    }

    @Override
    public boolean shouldContinue() {
        BlockPos current = lumen.getMineTarget();
        return current != null && giveUpTicks < 20 * 120;
    }

    @Override
    public void start() {
        this.progressTicks = 0;
        this.requiredTicks = 0;
        this.repathCountdown = 0;
        this.giveUpTicks = 0;
        this.lastStage = -1;
    }

    @Override
    public void stop() {
        clearBreakingOverlay();
        this.target = null;
        lumen.getNavigation().stop();
    }

    @Override
    public boolean shouldRunEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        this.giveUpTicks++;
        BlockPos current = lumen.getMineTarget();
        if (current == null) {
            return;
        }
        if (!current.equals(target)) {
            // Moved on to the next block in the seam.
            this.target = current;
            resetProgress();
        }

        lumen.getLookControl().lookAt(Vec3d.ofCenter(target));

        if (!lumen.getBlockPos().isWithinDistance(target, REACH)) {
            resetProgress();
            if (--this.repathCountdown > 0) {
                return;
            }
            this.repathCountdown = this.getTickCount(15);
            boolean moving = lumen.moveToBlock(target, Lumen.config().followSpeedMultiplier);
            if (!moving && lumen.getNavigation().isIdle() && giveUpTicks > 60) {
                lumen.finishMining();
            }
            return;
        }

        lumen.getNavigation().stop();
        if (requiredTicks <= 0) {
            this.requiredTicks = ticksToBreak();
        }
        this.progressTicks++;
        showBreakingOverlay();

        if (this.progressTicks >= this.requiredTicks) {
            resetProgress();
            String problem = lumen.breakTargetBlock();
            if (problem != null) {
                Lumen.broadcast(lumen.getWorld().getServer(), problem);
            }
        }
    }

    /** Roughly the player formula: hardness against the held tool's speed. */
    private int ticksToBreak() {
        if (!(lumen.getWorld() instanceof ServerWorld world)) {
            return 20;
        }
        BlockState state = world.getBlockState(target);
        float hardness = state.getHardness(world, target);
        if (hardness < 0.0F) {
            return 20;
        }
        ItemStack tool = lumen.getMainHandStack();
        float speed = Math.max(1.0F, tool.getMiningSpeedMultiplier(state));
        return (int) Math.max(5.0F, (hardness * 30.0F) / speed);
    }

    /** Drives the vanilla block-cracking overlay so players can see it working. */
    private void showBreakingOverlay() {
        if (requiredTicks <= 0 || !(lumen.getWorld() instanceof ServerWorld world)) {
            return;
        }
        int stage = Math.min(9, (progressTicks * 10) / requiredTicks);
        if (stage != lastStage) {
            world.setBlockBreakingInfo(lumen.getId(), target, stage);
            this.lastStage = stage;
        }
    }

    private void clearBreakingOverlay() {
        if (target != null && lumen.getWorld() instanceof ServerWorld world) {
            world.setBlockBreakingInfo(lumen.getId(), target, -1);
        }
        this.lastStage = -1;
    }

    private void resetProgress() {
        clearBreakingOverlay();
        this.progressTicks = 0;
        this.requiredTicks = 0;
    }
}
