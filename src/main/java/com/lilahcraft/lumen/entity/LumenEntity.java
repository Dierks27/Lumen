package com.lilahcraft.lumen.entity;

import com.lilahcraft.lumen.Lumen;
import com.lilahcraft.lumen.LumenConfig;
import com.lilahcraft.lumen.entity.goal.LumenFetchGoal;
import com.lilahcraft.lumen.entity.goal.LumenFollowGoal;
import com.lilahcraft.lumen.entity.goal.LumenGoToGoal;
import com.lilahcraft.lumen.entity.goal.LumenPickUpItemGoal;
import com.lilahcraft.lumen.entity.goal.LumenWanderGoal;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.EscapeDangerGoal;
import net.minecraft.entity.ai.goal.LongDoorInteractGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import com.lilahcraft.lumen.entity.ai.LumenNavigation;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
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
        /** Hang around, wander a bit, pick things up. */
        IDLE,
        /** Keep up with a specific player. */
        FOLLOW,
        /** Walk to a fixed position, then go back to idling. */
        GO_TO,
        /** Walk to a container, take what was asked for, then bring it back. */
        FETCH
    }

    private Mode mode;
    private UUID followTarget;
    private BlockPos destination;

    // Lazily built: MobEntity's constructor runs initGoals() before our field
    // initialisers, so anything a goal might touch has to be created on demand.
    private SimpleInventory inventory;

    private BlockPos fetchChest;
    private String fetchQuery;
    private UUID deliverTo;
    private final List<ItemStack> pendingDelivery = new ArrayList<>();

    private Vec3d lastProgressPos;
    private int stuckTicks;
    private int pickUpCooldown;

    public LumenEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
        super(entityType, world);
        this.setPersistent();
        this.setCanPickUpLoot(false); // we run our own pickup, vanilla's would equip junk
        this.setPathfindingPenalty(PathNodeType.WATER, 0.0F);
        this.setPathfindingPenalty(PathNodeType.DOOR_WOOD_CLOSED, 0.0F);
        this.setPathfindingPenalty(PathNodeType.DOOR_OPEN, 0.0F);
        this.setPathfindingPenalty(PathNodeType.DOOR_IRON_CLOSED, -1.0F); // cannot open these
        this.setPathfindingPenalty(PathNodeType.DANGER_FIRE, 12.0F);
        this.setPathfindingPenalty(PathNodeType.DAMAGE_FIRE, 24.0F);
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
        if (config.dropInventoryOnDeath) {
            // Gear a player handed over should come back, not vanish on a 8.5% roll.
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                this.setEquipmentDropChance(slot, 1.0F);
            }
        }
    }

    @Override
    protected EntityNavigation createNavigation(World world) {
        MobNavigation navigation = new LumenNavigation(this, world);
        navigation.setCanSwim(true);
        // Closed wooden doors become passable for the pathfinder; the door goal below
        // does the actual opening. Without both, Lumen stops dead at every doorway.
        navigation.setCanPathThroughDoors(Lumen.config().canOpenDoors);
        return navigation;
    }

    @Override
    protected void initGoals() {
        // NOTE: MobEntity's constructor calls this before our own fields are assigned,
        // so nothing here may read instance state. The goals only use getters, which
        // are written to tolerate being called during construction.
        this.goalSelector.add(0, new SwimGoal(this));
        if (Lumen.config().canOpenDoors) {
            this.goalSelector.add(1, new LongDoorInteractGoal(this, false));
        }
        this.goalSelector.add(1, new EscapeDangerGoal(this, 1.3D));
        this.goalSelector.add(2, new LumenFollowGoal(this));
        this.goalSelector.add(3, new LumenGoToGoal(this));
        this.goalSelector.add(3, new LumenFetchGoal(this));
        this.goalSelector.add(4, new MeleeAttackGoal(this, 1.2D, true));
        this.goalSelector.add(5, new LumenPickUpItemGoal(this));
        this.goalSelector.add(6, new LumenWanderGoal(this, 0.7D));
        this.goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
        this.goalSelector.add(8, new LookAroundGoal(this));

        this.targetSelector.add(1, new RevengeGoal(this));
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, HostileEntity.class, 10, true, false,
                this::shouldDefendAgainst));
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
        this.stuckTicks = 0;
    }

    public void goTo(BlockPos pos) {
        this.mode = Mode.GO_TO;
        this.destination = pos;
        this.followTarget = null;
        this.stuckTicks = 0;
    }

    public void stopAndIdle() {
        this.mode = Mode.IDLE;
        this.followTarget = null;
        this.destination = null;
        this.fetchChest = null;
        this.fetchQuery = null;
        this.stuckTicks = 0;
        this.getNavigation().stop();
        // pendingDelivery survives: whatever Lumen fetched still belongs to whoever
        // asked for it, and tick() hands it over as soon as they are close enough.
    }

    /**
     * Sends Lumen off to a nearby container that holds {@code query}.
     *
     * @return false when nothing nearby has it, so the caller can say so
     */
    public boolean startFetch(PlayerEntity requester, String query) {
        LumenConfig config = Lumen.config();
        if (!config.allowChestAccess || !(this.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        ChestFinder.Match match = ChestFinder.findContainerWith(
                world, this.getBlockPos(), config.chestSearchRadius, query);
        if (match == null) {
            return false;
        }
        this.mode = Mode.FETCH;
        this.fetchChest = match.pos();
        this.fetchQuery = query;
        this.deliverTo = requester.getUuid();
        this.followTarget = null;
        this.destination = null;
        this.stuckTicks = 0;
        return true;
    }

    @Nullable
    public BlockPos getFetchChest() {
        return getMode() == Mode.FETCH ? fetchChest : null;
    }

    public List<ItemStack> getPendingDelivery() {
        return pendingDelivery;
    }

    /**
     * Empties the matching items out of the container Lumen walked to, then heads back
     * to whoever asked. Called by the fetch goal once Lumen is standing at the chest.
     */
    public void collectFromChest() {
        LumenConfig config = Lumen.config();
        BlockPos chest = this.fetchChest;
        String query = this.fetchQuery;
        PlayerEntity requester = deliverTo == null ? null : this.getWorld().getPlayerByUuid(deliverTo);
        this.fetchChest = null;
        this.fetchQuery = null;
        if (chest == null || query == null) {
            stopAndIdle();
            return;
        }

        int taken = 0;
        if (this.getWorld().getBlockEntity(chest) instanceof Inventory inventory) {
            this.getWorld().playSound(null, chest, SoundEvents.BLOCK_CHEST_OPEN,
                    SoundCategory.BLOCKS, 0.6F, 1.0F);
            for (int slot = 0; slot < inventory.size() && taken < config.maxFetchStacks; slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (ChestFinder.matches(stack, query)) {
                    pendingDelivery.add(inventory.removeStack(slot));
                    taken++;
                }
            }
            inventory.markDirty();
            this.getWorld().playSound(null, chest, SoundEvents.BLOCK_CHEST_CLOSE,
                    SoundCategory.BLOCKS, 0.6F, 1.0F);
        }
        // Taking from someone's storage is worth an audit line in the server log.
        Lumen.LOGGER.info("Lumen took {} stack(s) matching '{}' from the container at {} for {}",
                taken, query, chest.toShortString(),
                requester == null ? "nobody" : requester.getName().getString());

        if (requester != null) {
            followPlayer(requester);
        } else {
            stopAndIdle();
        }
    }

    /** Hands fetched items over once Lumen is back beside whoever asked for them. */
    private void deliverIfClose() {
        if (pendingDelivery.isEmpty() || deliverTo == null) {
            return;
        }
        PlayerEntity requester = this.getWorld().getPlayerByUuid(deliverTo);
        if (requester == null || requester.getWorld() != this.getWorld()
                || this.squaredDistanceTo(requester) > 9.0D) {
            return;
        }
        int delivered = 0;
        for (ItemStack stack : pendingDelivery) {
            if (!stack.isEmpty()) {
                this.dropStack(stack);
                delivered += stack.getCount();
            }
        }
        pendingDelivery.clear();
        this.deliverTo = null;
        if (delivered > 0) {
            requester.sendMessage(Text.literal(Lumen.config().companionName + " drops "
                    + delivered + " item(s) for you.").formatted(Formatting.AQUA), false);
        }
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
            case FETCH -> {
                return fetchQuery == null ? "standing around"
                        : "going to a nearby container to fetch " + fetchQuery;
            }
            default -> {
                return "standing around";
            }
        }
    }

    // ---------------------------------------------------------------- inventory

    public SimpleInventory getInventory() {
        if (inventory == null) {
            inventory = new SimpleInventory(Math.max(1, Lumen.config().inventorySize));
        }
        return inventory;
    }

    /** "3 items" style summary for /lumen status and the world snapshot. */
    public int countCarriedStacks() {
        SimpleInventory inv = getInventory();
        int used = 0;
        for (int slot = 0; slot < inv.size(); slot++) {
            if (!inv.getStack(slot).isEmpty()) {
                used++;
            }
        }
        return used;
    }

    /**
     * Moves a stack into Lumen's pack.
     *
     * @return how many items were actually taken
     */
    public int give(ItemStack offered) {
        if (offered.isEmpty()) {
            return 0;
        }
        int before = offered.getCount();
        ItemStack remainder = getInventory().addStack(offered.copy());
        int accepted = before - (remainder.isEmpty() ? 0 : remainder.getCount());
        if (accepted > 0) {
            equipBetterGear();
        }
        return accepted;
    }

    /**
     * Wears or wields anything in the pack that beats what is already equipped. The
     * displaced item goes back into the pack rather than being dropped.
     */
    private void equipBetterGear() {
        SimpleInventory inv = getInventory();
        for (int slot = 0; slot < inv.size(); slot++) {
            ItemStack candidate = inv.getStack(slot);
            // Single items only: gear comes in stacks of one, and this keeps the
            // swap below from silently eating the rest of a stack.
            if (candidate.isEmpty() || candidate.getCount() != 1) {
                continue;
            }
            EquipmentSlot target = getPreferredEquipmentSlot(candidate);
            if (target == EquipmentSlot.MAINHAND && attackBonus(candidate) <= 0.0D) {
                continue; // don't walk around holding a stack of dirt
            }
            ItemStack current = this.getEquippedStack(target);
            if (!current.isEmpty() && !this.prefersNewEquipment(candidate, current)) {
                continue;
            }
            inv.removeStack(slot);
            this.equipStack(target, candidate);
            if (!current.isEmpty()) {
                inv.setStack(slot, current);
            }
        }
    }

    /** Attack damage an item adds in the main hand. Reads modifiers, so modded weapons count. */
    private static double attackBonus(ItemStack stack) {
        double bonus = 0.0D;
        for (EntityAttributeModifier modifier : stack.getAttributeModifiers(EquipmentSlot.MAINHAND)
                .get(EntityAttributes.GENERIC_ATTACK_DAMAGE)) {
            if (modifier.getOperation() == EntityAttributeModifier.Operation.ADDITION) {
                bonus += modifier.getValue();
            }
        }
        return bonus;
    }

    /** Picks up anything Lumen is standing on top of. */
    private void collectNearbyItems() {
        LumenConfig config = Lumen.config();
        if (!config.pickUpItems || --this.pickUpCooldown > 0) {
            return;
        }
        this.pickUpCooldown = 10;
        Box box = this.getBoundingBox().expand(1.0D, 0.5D, 1.0D);
        List<ItemEntity> items = this.getWorld().getEntitiesByClass(ItemEntity.class, box,
                item -> item.isAlive() && !item.cannotPickup());
        for (ItemEntity item : items) {
            ItemStack stack = item.getStack();
            int accepted = give(stack);
            if (accepted > 0) {
                this.sendPickup(item, accepted);
                stack.decrement(accepted);
                if (stack.isEmpty()) {
                    item.discard();
                }
            }
        }
    }

    // ------------------------------------------------------------------- combat

    /** Only fight things that threaten Lumen or the player it is looking after. */
    private boolean shouldDefendAgainst(@Nullable LivingEntity candidate) {
        LumenConfig config = Lumen.config();
        if (!config.combat || candidate == null || !candidate.isAlive()) {
            return false;
        }
        double radiusSquared = config.defendRadius * config.defendRadius;
        if (this.squaredDistanceTo(candidate) <= radiusSquared) {
            return true;
        }
        PlayerEntity guarded = getFollowTarget();
        return guarded != null && candidate.squaredDistanceTo(guarded) <= radiusSquared;
    }

    /**
     * Villagers have no attack damage attribute, and Lumen is wearing a villager, so
     * the vanilla path through {@code getAttributeValue(GENERIC_ATTACK_DAMAGE)} would
     * throw. Damage comes from config plus whatever is in Lumen's hand instead.
     */
    @Override
    public boolean tryAttack(Entity target) {
        float damage = (float) (Lumen.config().attackDamage + attackBonus(this.getMainHandStack()));
        boolean hit = target.damage(this.getDamageSources().mobAttack(this), damage);
        if (hit) {
            this.applyDamageEffects(this, target);
        }
        this.swingHand(Hand.MAIN_HAND);
        return hit;
    }

    // --------------------------------------------------------- stuck detection

    /**
     * Modded blocks and awkward geometry strand vanilla navigation. Rather than
     * needing a despawn/respawn, notice that Lumen wants to move and is not moving,
     * re-path, and warp as a last resort.
     */
    private void updateStuckState() {
        LumenConfig config = Lumen.config();
        BlockPos anchor = stuckAnchor();
        if (anchor == null) {
            this.stuckTicks = 0;
            this.lastProgressPos = this.getPos();
            return;
        }
        if (this.lastProgressPos == null || this.getPos().squaredDistanceTo(this.lastProgressPos) > 0.35D) {
            this.lastProgressPos = this.getPos();
            this.stuckTicks = 0;
            return;
        }
        this.stuckTicks++;
        if (this.stuckTicks == config.stuckRepathTicks) {
            this.getNavigation().recalculatePath();
            this.getJumpControl().setActive();
        } else if (this.stuckTicks >= config.stuckTeleportTicks) {
            Lumen.LOGGER.debug("Lumen stuck for {} ticks, warping to its target", this.stuckTicks);
            this.stuckTicks = 0;
            teleportNear(anchor);
        }
    }

    /**
     * Where Lumen is trying to get to, or null when it has no reason to be moving -
     * standing next to the player it is following is not being stuck.
     */
    @Nullable
    private BlockPos stuckAnchor() {
        LumenConfig config = Lumen.config();
        switch (getMode()) {
            case FOLLOW -> {
                PlayerEntity target = getFollowTarget();
                if (target == null) {
                    return null;
                }
                double startSquared = config.followStartDistance * config.followStartDistance;
                return this.squaredDistanceTo(target) > startSquared ? target.getBlockPos() : null;
            }
            case GO_TO -> {
                BlockPos target = getDestination();
                return target != null && !this.getBlockPos().isWithinDistance(target, 2.0D) ? target : null;
            }
            case FETCH -> {
                return fetchChest != null && !this.getBlockPos().isWithinDistance(fetchChest, 2.5D)
                        ? fetchChest : null;
            }
            default -> {
                return null;
            }
        }
    }

    /** Puts Lumen on a free block near {@code base}. No-op if nowhere sensible is free. */
    public boolean teleportNear(BlockPos base) {
        for (int attempt = 0; attempt < 16; attempt++) {
            int dx = this.getRandom().nextInt(5) - 2;
            int dy = this.getRandom().nextInt(3) - 1;
            int dz = this.getRandom().nextInt(5) - 2;
            BlockPos candidate = base.add(dx, dy, dz);
            if (isFreeToStandIn(candidate)) {
                this.getNavigation().stop();
                this.refreshPositionAndAngles(candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D,
                        this.getYaw(), this.getPitch());
                this.lastProgressPos = this.getPos();
                this.stuckTicks = 0;
                return true;
            }
        }
        return false;
    }

    private boolean isFreeToStandIn(BlockPos pos) {
        World world = this.getWorld();
        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false;
        }
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
                && world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty()
                && !world.getBlockState(pos.down()).getCollisionShape(world, pos.down()).isEmpty();
    }

    // --------------------------------------------------------------- lifecycle

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
        updateStuckState();
        collectNearbyItems();
        deliverIfClose();
    }

    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (this.getWorld().isClient() || hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }
        LumenConfig config = Lumen.config();
        ItemStack offered = player.getStackInHand(hand);

        if (!offered.isEmpty() && config.acceptItemsFromPlayers) {
            int accepted = give(offered);
            if (accepted <= 0) {
                player.sendMessage(Text.literal(config.companionName + " has no room for that.")
                        .formatted(Formatting.GRAY), false);
                return ActionResult.SUCCESS;
            }
            String takenName = offered.getName().getString(); // read before the stack shrinks away
            if (!player.getAbilities().creativeMode) {
                offered.decrement(accepted);
            }
            player.sendMessage(Text.literal(config.companionName + " takes " + accepted + "x "
                    + takenName + ".").formatted(Formatting.AQUA), false);
            return ActionResult.SUCCESS;
        }

        player.sendMessage(Text.literal(config.companionName + " is " + describeActivity()
                + " (carrying " + countCarriedStacks() + " stacks).").formatted(Formatting.GRAY), false);
        return ActionResult.SUCCESS;
    }

    @Override
    protected void dropInventory() {
        super.dropInventory();
        if (!Lumen.config().dropInventoryOnDeath) {
            return;
        }
        SimpleInventory inv = getInventory();
        for (int slot = 0; slot < inv.size(); slot++) {
            ItemStack stack = inv.getStack(slot);
            if (!stack.isEmpty()) {
                this.dropStack(stack);
            }
        }
        inv.clear();
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
