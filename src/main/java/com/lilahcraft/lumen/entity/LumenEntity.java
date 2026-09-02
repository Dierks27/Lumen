package com.lilahcraft.lumen.entity;

import com.lilahcraft.lumen.Lumen;
import com.lilahcraft.lumen.LumenConfig;
import com.lilahcraft.lumen.entity.goal.LumenFollowGoal;
import com.lilahcraft.lumen.entity.goal.LumenGoToGoal;
import com.lilahcraft.lumen.entity.goal.LumenWanderGoal;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.EscapeDangerGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Lumen's body.
 *
 * <p>Deliberately does NOT register an entity type of its own. A server-side-only
 * mod that adds an entity type gives connecting clients a raw entity id they cannot
 * resolve; instead Lumen borrows a vanilla type (default {@code minecraft:villager})
 * so that unmodified clients spawn and render something sensible, while all of the
 * behaviour below runs only on the server. The flip side is that Lumen must never be
 * written to disk as that vanilla type, hence {@link #saveSelfNbt(NbtCompound)}.
 */
public class LumenEntity extends PathAwareEntity {

    public enum Mode {
        /** Hang around, wander a bit. */
        IDLE,
        /** Keep up with a specific player. */
        FOLLOW,
        /** Walk to a fixed position, then go back to idling. */
        GO_TO
    }

    private Mode mode;
    private UUID followTarget;
    private BlockPos destination;

    public LumenEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
        super(entityType, world);
        this.setPersistent();
        this.setPathfindingPenalty(PathNodeType.WATER, 0.0F);
        this.setCanPickUpLoot(false);
    }

    /**
     * Builds a Lumen wearing the configured appearance, falling back to a villager if
     * the configured id is unknown or is not a living entity.
     */
    public static LumenEntity create(ServerWorld world, LumenConfig config) {
        LumenEntity entity;
        try {
            entity = new LumenEntity(resolveAppearance(config.appearanceEntity), world);
        } catch (RuntimeException e) {
            Lumen.LOGGER.warn("appearanceEntity '{}' is not usable ({}), falling back to minecraft:villager",
                    config.appearanceEntity, e.toString());
            entity = new LumenEntity(villager(), world);
        }
        entity.applyConfig(config);
        return entity;
    }

    @SuppressWarnings("unchecked")
    private static EntityType<? extends PathAwareEntity> resolveAppearance(String id) {
        try {
            Identifier identifier = new Identifier(id);
            if (Registries.ENTITY_TYPE.containsId(identifier)) {
                return (EntityType<? extends PathAwareEntity>) (EntityType<?>) Registries.ENTITY_TYPE.get(identifier);
            }
            Lumen.LOGGER.warn("Unknown appearanceEntity '{}', falling back to minecraft:villager", id);
        } catch (RuntimeException e) {
            Lumen.LOGGER.warn("Invalid appearanceEntity '{}': {}", id, e.toString());
        }
        return villager();
    }

    @SuppressWarnings("unchecked")
    private static EntityType<? extends PathAwareEntity> villager() {
        return (EntityType<? extends PathAwareEntity>) (EntityType<?>) EntityType.VILLAGER;
    }

    /** Re-applies name, health and speed. Safe to call on a live entity after /lumen reload. */
    public void applyConfig(LumenConfig config) {
        this.setCustomName(Text.literal(config.companionName).formatted(Formatting.AQUA));
        this.setCustomNameVisible(true);

        EntityAttributeInstance health = this.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(config.maxHealth);
            if (this.getHealth() > this.getMaxHealth() || this.getHealth() <= 0.0F) {
                this.setHealth(this.getMaxHealth());
            }
        }
        EntityAttributeInstance speed = this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(config.movementSpeed);
        }
        EntityAttributeInstance range = this.getAttributeInstance(EntityAttributes.GENERIC_FOLLOW_RANGE);
        if (range != null) {
            range.setBaseValue(config.followRange);
        }
    }

    @Override
    protected void initGoals() {
        // NOTE: MobEntity's constructor calls this before our own fields are assigned,
        // so nothing here may read instance state. The goals only use getters, which
        // are written to tolerate being called during construction.
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new EscapeDangerGoal(this, 1.3D));
        this.goalSelector.add(2, new LumenFollowGoal(this));
        this.goalSelector.add(3, new LumenGoToGoal(this));
        this.goalSelector.add(6, new LumenWanderGoal(this, 0.7D));
        this.goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
        this.goalSelector.add(8, new LookAroundGoal(this));
    }

    // ---------------------------------------------------------------- behaviour

    public Mode getMode() {
        // May be queried before the constructor body has run - see initGoals().
        return mode == null ? Mode.IDLE : mode;
    }

    public void followPlayer(PlayerEntity player) {
        this.mode = Mode.FOLLOW;
        this.followTarget = player.getUuid();
        this.destination = null;
    }

    public void goTo(BlockPos pos) {
        this.mode = Mode.GO_TO;
        this.destination = pos;
        this.followTarget = null;
    }

    public void stopAndIdle() {
        this.mode = Mode.IDLE;
        this.followTarget = null;
        this.destination = null;
        this.getNavigation().stop();
    }

    @Nullable
    public PlayerEntity getFollowTarget() {
        if (getMode() != Mode.FOLLOW || followTarget == null) {
            return null;
        }
        PlayerEntity player = this.getWorld().getPlayerByUuid(followTarget);
        return player != null && player.isAlive() && player.getWorld() == this.getWorld() ? player : null;
    }

    @Nullable
    public BlockPos getDestination() {
        return getMode() == Mode.GO_TO ? destination : null;
    }

    /** Human readable state, fed to the model as part of the world snapshot. */
    public String describeActivity() {
        switch (getMode()) {
            case FOLLOW -> {
                PlayerEntity target = getFollowTarget();
                return target == null ? "standing around" : "following " + target.getName().getString();
            }
            case GO_TO -> {
                BlockPos pos = getDestination();
                return pos == null ? "standing around"
                        : "walking to " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
            }
            default -> {
                return "standing around";
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.getWorld().isClient()) {
            return;
        }
        // Drop stale goals so the model is never told it is following someone who left.
        if (mode == Mode.FOLLOW && getFollowTarget() == null) {
            stopAndIdle();
        } else if (mode == Mode.GO_TO && destination != null
                && this.getBlockPos().isWithinDistance(destination, 2.0D)) {
            stopAndIdle();
        }
    }

    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (!this.getWorld().isClient() && hand == Hand.MAIN_HAND) {
            LumenConfig config = Lumen.config();
            player.sendMessage(Text.literal(config.companionName + " is " + describeActivity() + ".")
                    .formatted(Formatting.GRAY), false);
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }

    // ------------------------------------------------------------- persistence

    /**
     * Never write Lumen to the region file. It is wearing a borrowed vanilla type, so
     * a saved Lumen would come back after a restart as a real villager. Lumen is
     * respawned with {@code /lumen spawn} instead.
     */
    @Override
    public boolean saveSelfNbt(NbtCompound nbt) {
        return false;
    }

    @Override
    public boolean canImmediatelyDespawn(double distanceSquared) {
        return false;
    }

    /**
     * Portals would recreate Lumen through {@code EntityType.create()}, which would
     * hand back a plain villager. Lumen stays put and is re-spawned on the other side.
     */
    @Override
    public boolean canUsePortals() {
        return false;
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        Lumen.manager().onEntityRemoved(this);
    }

    // ------------------------------------------------------------------ sounds

    @Nullable
    @Override
    protected SoundEvent getAmbientSound() {
        // Villager "hmm" noises would give the borrowed appearance away.
        return null;
    }
}
