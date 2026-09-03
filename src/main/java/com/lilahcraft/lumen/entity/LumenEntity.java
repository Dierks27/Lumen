package com.lilahcraft.lumen.entity;

import com.lilahcraft.lumen.Lumen;
import com.lilahcraft.lumen.LumenConfig;
import com.lilahcraft.lumen.craft.CraftPlanner;
import com.lilahcraft.lumen.entity.ai.LumenNavigation;
import com.lilahcraft.lumen.entity.goal.LumenFetchGoal;
import com.lilahcraft.lumen.entity.goal.LumenFollowGoal;
import com.lilahcraft.lumen.entity.goal.LumenGoToGoal;
import com.lilahcraft.lumen.entity.goal.LumenMineGoal;
import com.lilahcraft.lumen.entity.goal.LumenPickUpItemGoal;
import com.lilahcraft.lumen.entity.goal.LumenWanderGoal;
import com.lilahcraft.lumen.skill.LumenSkill;
import com.lilahcraft.lumen.memory.LumenMemory;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.EscapeDangerGoal;
import net.minecraft.entity.ai.goal.LongDoorInteractGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.DefaultAttributeRegistry;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.EntityView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>Implements {@link Tameable} so that claim and protection mods which look up a
 * mob's owner - Open Parties and Claims does, through {@code OwnableEntity} - treat
 * what Lumen does as done by the player directing it.
 */
public class LumenEntity extends PathAwareEntity implements NamedScreenHandlerFactory, Tameable {

    public enum Mode {
        /** Hang around, wander a bit, pick things up. */
        IDLE,
        /** Keep up with a specific player. */
        FOLLOW,
        /** Walk to a fixed position, then go back to idling. */
        GO_TO,
        /** Walk to a container, take what was asked for, then bring it back. */
        FETCH,
        /** Break blocks of a requested kind, then bring the haul back. */
        MINE
    }

    /** How many candidate containers get a real path test before Lumen settles. */
    private static final int MAX_PATH_TESTS = 8;

    private Mode mode;
    private UUID followTarget;
    private BlockPos destination;

    /** Whoever spawned Lumen, or last told it what to do. */
    private UUID ownerUuid;

    // Lazily built: MobEntity's constructor runs initGoals() before our field
    // initialisers, so anything a goal might touch has to be created on demand.
    private SimpleInventory inventory;

    /** What Lumen is doing right now, as data, and what is waiting behind it. */
    private LumenTask currentTask;
    private final Deque<LumenTask> taskQueue = new ArrayDeque<>();
    /** Where a fetch or mine searches from when a named place was given; else Lumen's spot. */
    private BlockPos fetchAnchor;
    private BlockPos mineAnchor;

    /** One block a skill or quarry lined up: break it, or right-click it. */
    public record WorkItem(BlockPos pos, boolean interact) {
    }

    /** Blocks lined up by a skill or a quarry, worked through the mine goal in order. */
    private final Deque<WorkItem> work = new ArrayDeque<>();
    private WorkItem currentWork;
    private int workDone;
    private int workSkipped;
    private String workLabel;
    private boolean workCollect;
    private BlockPos workCenter;
    /** A craft that needs a table: done once Lumen has walked to one. */
    private LumenTask.Craft craftAfterArrival;
    private int collectTicks;

    // Survival: a food level that drains with real time, and a name tag that shows health.
    private float foodLevel = 20.0F;
    private int foodTicks;
    private boolean hungerSlowed;
    private String lastNameShown = "";
    private int aggroCooldown;
    private static final UUID HUNGER_SLOW_ID = UUID.fromString("7d4a2c6e-3b1f-4e0a-9c2d-5f6e7a8b9c0d");

    private BlockPos fetchChest;
    private String fetchQuery;
    private UUID deliverTo;
    private final List<ItemStack> pendingDelivery = new ArrayList<>();
    /** Containers already checked on this errand, so a retry does not loop back. */
    private final Set<BlockPos> triedContainers = new HashSet<>();
    private int fetchAttempts;
    /** How many items are still wanted on this errand. */
    private int fetchWanted;
    /** How many were asked for in the first place, for the report at the end. */
    private int fetchRequested;
    /** Non-zero while the amount is still expressed in stacks, pending the real item. */
    private double fetchStacks;
    /** Items taken so far this errand. */
    private int fetchTaken;
    /** The first thing taken this errand: later containers are held to the same item. */
    private ItemStack fetchSample = ItemStack.EMPTY;
    /** The weakest match tier still acceptable: once real stone is in hand, cobble is not. */
    private int fetchMinScore;
    /** A match was seen that Lumen could not path to - worth saying so at the end. */
    private boolean fetchSawUnreachable;
    /** What to hand over once Lumen reaches the player, or null. "*" means everything. */
    private String pendingHandover;

    private BlockPos mineTarget;
    private String mineQuery;
    private int minedCount;
    /** Blocks already broken or ruled out this errand, so the search moves on. */
    private final Set<BlockPos> minedPositions = new HashSet<>();

    private Vec3d lastProgressPos;
    private int stuckTicks;
    private int pickUpCooldown;
    /** Until this tick, nothing on the floor is picked up - it was just handed over. */
    private int pickUpSuppressedUntil;
    /** Items Lumen itself put down, and the tick after which it may forget about them. */
    private final Map<UUID, Integer> ownDrops = new HashMap<>();
    private int eatCooldown;
    private int equipCooldown;
    private BlockPos openedGate;
    private int gateCloseTimer;
    private int gateScanCooldown;
    /** Doors and gates Lumen has walked into, and how many ticks since it was last in them. */
    private final Map<BlockPos, Integer> passages = new HashMap<>();

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
                EntityType<?> type = Registries.ENTITY_TYPE.get(identifier);
                // A mob needs the attributes a mob has. Wearing a type that lacks them
                // (armour stands, boats, anything not living) crashes on the first
                // navigation tick, so it is refused up front.
                if (!DefaultAttributeRegistry.hasDefinitionFor(type)) {
                    Lumen.LOGGER.warn("appearanceEntity '{}' is not a living entity type, falling back to minecraft:villager", id);
                    return villager();
                }
                DefaultAttributeContainer attributes = DefaultAttributeRegistry.get((EntityType<? extends LivingEntity>) type);
                if (!attributes.has(EntityAttributes.GENERIC_FOLLOW_RANGE)
                        || !attributes.has(EntityAttributes.GENERIC_MOVEMENT_SPEED)) {
                    Lumen.LOGGER.warn("appearanceEntity '{}' lacks mob attributes (follow range / speed), "
                            + "falling back to minecraft:villager", id);
                    return villager();
                }
                return (EntityType<? extends PathAwareEntity>) type;
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
        this.lastNameShown = "";
        refreshNameTag(config);
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
            // true: close it again after passing through, rather than leaving it open.
            // Only covers doors Lumen opened itself; doors a player left open are
            // closed by handlePassages() below.
            this.goalSelector.add(1, new LongDoorInteractGoal(this, true));
        }
        this.goalSelector.add(1, new EscapeDangerGoal(this, 1.3D));
        this.goalSelector.add(2, new LumenFollowGoal(this));
        this.goalSelector.add(3, new LumenGoToGoal(this));
        this.goalSelector.add(3, new LumenFetchGoal(this));
        this.goalSelector.add(3, new LumenMineGoal(this));
        // Above the errands: a fight takes the move control, the errand goal is stopped,
        // and once the target is dead the goal selector restarts it from the entity's
        // state - which is "respond to danger, then resume" for free.
        this.goalSelector.add(2, new MeleeAttackGoal(this, 1.2D, true));
        this.goalSelector.add(5, new LumenPickUpItemGoal(this));
        this.goalSelector.add(6, new LumenWanderGoal(this, 0.7D));
        this.goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
        this.goalSelector.add(8, new LookAroundGoal(this));

        // No RevengeGoal. It retaliates against whoever last damaged Lumen, with no
        // filter - so one stray swing from the player during a fight made Lumen turn
        // on them and kill them. Lumen only ever picks its own targets, below.
        this.targetSelector.add(1, new ActiveTargetGoal<>(this, HostileEntity.class, 10, true, false,
                this::shouldDefendAgainst));
    }

    // ------------------------------------------------------------------- owner

    /** Records who Lumen answers to. Set on spawn and refreshed by every instruction. */
    public void setOwner(@Nullable PlayerEntity player) {
        if (player != null) {
            this.ownerUuid = player.getUuid();
        }
    }

    /**
     * The player whose permissions Lumen acts under: whoever gave the current errand,
     * otherwise whoever spawned it. Claim mods read this to decide whether a block
     * Lumen breaks or a chest it opens is allowed.
     */
    @Nullable
    @Override
    public UUID getOwnerUuid() {
        return deliverTo != null ? deliverTo : ownerUuid;
    }

    @Nullable
    @Override
    public LivingEntity getOwner() {
        UUID uuid = getOwnerUuid();
        if (uuid == null || this.getWorld() == null) {
            return null;
        }
        return this.getWorld().getPlayerByUuid(uuid);
    }

    /**
     * The third method of {@code Tameable}, unnamed in the 1.20.1 yarn mappings: the
     * world the owner is looked up in (Mojang's {@code OwnableEntity#level()}).
     */
    @Override
    public EntityView method_48926() {
        return this.getWorld();
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
        setOwner(player);
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
        this.triedContainers.clear();
        this.fetchAttempts = 0;
        this.mineTarget = null;
        this.mineQuery = null;
        this.minedPositions.clear();
        this.fetchStacks = 0.0D;
        this.fetchAnchor = null;
        this.mineAnchor = null;
        clearWork();
        this.craftAfterArrival = null;
        this.stuckTicks = 0;
        this.getNavigation().stop();
        // pendingDelivery survives: whatever Lumen fetched still belongs to whoever
        // asked for it, and tick() hands it over as soon as they are close enough.
        // The task queue survives too - only cancelAll() throws it away.
    }

    // ------------------------------------------------------------------- tasks

    /** What submitting a task did. */
    public enum Submission {
        /** Running now. */
        STARTED,
        /** Behind whatever is running; will start in turn. */
        QUEUED,
        /** Could not start and nothing was queued - the note says why. */
        FAILED
    }

    /** How many tasks may wait. Beyond it the player is told no. */
    public static final int MAX_QUEUE = 8;

    /**
     * Gives Lumen something to do. Runs it now when nothing else is running, otherwise
     * queues it behind the current errand - a second request no longer cancels the first.
     *
     * @return what happened; on FAILED, {@link #lastTaskNote()} says why
     */
    public Submission submit(LumenTask task) {
        if (isBusy()) {
            if (taskQueue.size() >= MAX_QUEUE) {
                this.lastTaskNote = "i've already got " + taskQueue.size() + " things lined up";
                return Submission.FAILED;
            }
            taskQueue.addLast(task);
            return Submission.QUEUED;
        }
        return runTask(task) ? Submission.STARTED : Submission.FAILED;
    }

    private String lastTaskNote = "";

    /**
     * Running a task, or on an errand started some other way. Walking back to the
     * player with goods is not busy: "now mine copper" said then should start, with the
     * goods delivered once everything is done.
     */
    public boolean isBusy() {
        Mode current = getMode();
        return currentTask != null || current == Mode.FETCH || current == Mode.MINE || current == Mode.GO_TO;
    }

    /** Why the last task failed to start, for whoever asked. */
    public String lastTaskNote() {
        return lastTaskNote;
    }

    /** Makes a task the current one and starts it. False when it could not start. */
    private boolean runTask(LumenTask task) {
        this.currentTask = task;
        this.lastTaskNote = "";
        PlayerEntity requester = task.requester() == null ? null : this.getWorld().getPlayerByUuid(task.requester());
        boolean ok;
        if (task instanceof LumenTask.Fetch fetch) {
            if (requester == null) {
                this.lastTaskNote = "whoever asked for that has gone";
                ok = false;
            } else {
                ok = startFetch(requester, fetch.request(), fetch.anchor());
                if (!ok) {
                    this.lastTaskNote = fetchSawUnreachable()
                            ? "i can see some " + fetch.request().query() + " in a container but i can't get to it"
                            : "i can't find any " + fetch.request().query()
                                    + (fetch.anchorName() == null ? " in anything nearby" : " around the " + fetch.anchorName());
                }
            }
        } else if (task instanceof LumenTask.Mine mine) {
            if (requester == null) {
                this.lastTaskNote = "whoever asked for that has gone";
                ok = false;
            } else {
                String refusal = startMining(requester, mine.query(), mine.anchor());
                ok = refusal == null;
                if (!ok) {
                    this.lastTaskNote = refusal;
                }
            }
        } else if (task instanceof LumenTask.GoTo go) {
            goTo(go.pos());
            ok = true;
        } else if (task instanceof LumenTask.Return) {
            if (requester == null) {
                this.lastTaskNote = "whoever asked has gone";
                ok = false;
            } else {
                followPlayer(requester);
                ok = true;
            }
        } else if (task instanceof LumenTask.Handover handover) {
            if (requester == null) {
                this.lastTaskNote = "whoever asked has gone";
                ok = false;
            } else {
                this.pendingHandover = handover.query() == null ? "*" : handover.query();
                this.deliverTo = requester.getUuid();
                followPlayer(requester);
                ok = true;
            }
        } else if (task instanceof LumenTask.Harvest harvest) {
            LumenSkill skill = Lumen.skills().find(harvest.skillName());
            if (requester == null) {
                this.lastTaskNote = "whoever asked has gone";
                ok = false;
            } else if (skill == null) {
                this.lastTaskNote = "i don't know how to " + harvest.skillName();
                ok = false;
            } else {
                String refusal = startSkill(requester, skill, harvest.anchor(), harvest.anchorName());
                ok = refusal == null;
                if (!ok) {
                    this.lastTaskNote = refusal;
                }
            }
        } else if (task instanceof LumenTask.Quarry quarry) {
            if (requester == null) {
                this.lastTaskNote = "whoever asked has gone";
                ok = false;
            } else {
                String refusal = startQuarry(requester, quarry.region());
                ok = refusal == null;
                if (!ok) {
                    this.lastTaskNote = refusal;
                }
            }
        } else if (task instanceof LumenTask.Craft craft) {
            if (requester == null) {
                this.lastTaskNote = "whoever asked has gone";
                ok = false;
            } else {
                ok = beginCraft(requester, craft);
            }
        } else if (task instanceof LumenTask.Collect collect) {
            this.deliverTo = collect.requester();
            this.collectTicks = 0;
            this.mode = Mode.IDLE; // the pick-up goal only runs while idle
            ok = true;
        } else {
            ok = false;
        }
        if (!ok) {
            this.currentTask = null;
        }
        return ok;
    }

    /**
     * The current task is finished (or could not be finished). Starts the next one, or
     * heads back to whoever asked when the queue is empty - which is what finishing an
     * errand always did.
     */
    private void taskDone() {
        LumenTask finished = this.currentTask;
        this.currentTask = null;
        while (!taskQueue.isEmpty()) {
            LumenTask next = taskQueue.pollFirst();
            if (runTask(next)) {
                return;
            }
            // Could not start - say so, and carry on down the list.
            Lumen.broadcast(this.getWorld().getServer(), lastTaskNote.isEmpty()
                    ? "couldn't " + next.describe() : lastTaskNote);
        }
        UUID back = finished != null && finished.requester() != null ? finished.requester() : deliverTo;
        PlayerEntity requester = back == null ? null : this.getWorld().getPlayerByUuid(back);
        if (requester != null) {
            followPlayer(requester);
        } else {
            stopAndIdle();
        }
    }

    /**
     * A direct instruction from the player - "come here", "follow me" - interrupts
     * whatever is running. The errand is not thrown away: it goes to the front of the
     * queue, and "carry on" resumes it. A fetch remembers how much is still owed.
     */
    public void pauseForPlayer() {
        LumenTask running = this.currentTask;
        this.currentTask = null;
        if (running instanceof LumenTask.Fetch fetch && fetchQuery != null) {
            // Nine of twelve already taken: only three are still wanted.
            ChestFinder.Request remaining = fetchTaken > 0 && !fetch.request().isEverything()
                    ? new ChestFinder.Request(Math.max(1, fetchWanted), 0.0D, fetch.request().query(), true)
                    : fetch.request();
            if (fetchWanted > 0 || fetchTaken == 0) {
                taskQueue.addFirst(new LumenTask.Fetch(fetch.requester(), remaining, fetch.anchor(), fetch.anchorName()));
            }
        } else if (running instanceof LumenTask.Mine || running instanceof LumenTask.GoTo
                || running instanceof LumenTask.Harvest || running instanceof LumenTask.Quarry
                || running instanceof LumenTask.Craft) {
            taskQueue.addFirst(running);
        }
        // Handover and Return are about the player anyway; they just happen on arrival.
        if (running instanceof LumenTask.Handover handover) {
            this.pendingHandover = handover.query() == null ? "*" : handover.query();
        }
        clearErrandFields();
    }

    /** Resets the per-errand fields without touching the queue or anything owed. */
    private void clearErrandFields() {
        this.mode = Mode.IDLE;
        this.fetchChest = null;
        this.fetchQuery = null;
        this.fetchStacks = 0.0D;
        this.fetchAnchor = null;
        this.triedContainers.clear();
        this.fetchAttempts = 0;
        this.mineTarget = null;
        this.mineQuery = null;
        this.mineAnchor = null;
        this.minedPositions.clear();
        clearWork();
        this.craftAfterArrival = null;
        this.stuckTicks = 0;
        this.getNavigation().stop();
    }

    private void clearWork() {
        this.work.clear();
        this.currentWork = null;
        this.workLabel = null;
        this.workCollect = false;
        this.workCenter = null;
        this.workDone = 0;
        this.workSkipped = 0;
    }

    /** "Carry on": starts the next queued task. False when there is nothing waiting. */
    public boolean resume() {
        if (currentTask != null || taskQueue.isEmpty()) {
            return false;
        }
        taskDone();
        return currentTask != null;
    }

    /** Drops the current task and everything queued. Goods already fetched are kept for delivery. */
    public int cancelAll() {
        int dropped = taskQueue.size() + (currentTask != null ? 1 : 0);
        taskQueue.clear();
        this.currentTask = null;
        stopAndIdle();
        return dropped;
    }

    @Nullable
    public LumenTask currentTask() {
        return currentTask;
    }

    public int queuedCount() {
        return taskQueue.size();
    }

    /** One line per task, the running one first, for /lumen queue and the snapshot. */
    public List<String> describeQueue() {
        List<String> lines = new ArrayList<>();
        if (currentTask != null) {
            lines.add("now: " + currentTask.describe());
        }
        int i = 1;
        for (LumenTask task : taskQueue) {
            lines.add(i++ + ". " + task.describe());
        }
        return lines;
    }

    /**
     * Sends Lumen off to a nearby container that holds {@code query}.
     *
     * @return false when nothing nearby has it, so the caller can say so
     */
    public boolean startFetch(PlayerEntity requester, String query) {
        return startFetch(requester, ChestFinder.parseRequest(query, Lumen.config().defaultFetchCount));
    }

    /**
     * Sends Lumen off to fetch a parsed request.
     *
     * @return false when nothing nearby has it - {@link #fetchSawUnreachable()} then
     *         says whether that is because there was none, or none Lumen could get to
     */
    public boolean startFetch(PlayerEntity requester, ChestFinder.Request request) {
        return startFetch(requester, request, null);
    }

    /**
     * @param anchor where to search from - a named place - or null for wherever Lumen is
     */
    public boolean startFetch(PlayerEntity requester, ChestFinder.Request request, @Nullable BlockPos anchor) {
        LumenConfig config = Lumen.config();
        if (!config.allowChestAccess || !(this.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (request.query().isEmpty()) {
            return false;
        }
        this.fetchQuery = request.query();
        this.fetchWanted = Math.min(request.count(), config.maxFetchItems);
        this.fetchRequested = request.isEverything() ? Integer.MAX_VALUE : this.fetchWanted;
        this.fetchStacks = request.stacks();
        this.fetchTaken = 0;
        this.fetchSample = ItemStack.EMPTY;
        this.fetchMinScore = ChestFinder.SUBSTRING_MATCH;
        this.fetchSawUnreachable = false;
        this.fetchAnchor = anchor;
        this.deliverTo = requester.getUuid();
        setOwner(requester);
        this.triedContainers.clear();
        this.fetchAttempts = 0;
        this.followTarget = null;
        this.destination = null;
        this.stuckTicks = 0;
        return targetNextContainer(world);
    }

    /** True when the last search found a match Lumen had no path to. */
    public boolean fetchSawUnreachable() {
        return fetchSawUnreachable;
    }

    /**
     * Picks where to look next: somewhere Lumen has found this before, otherwise a
     * fresh search. Remembered spots that no longer hold the item are forgotten as
     * they are ruled out, so the memory stays honest as chests get emptied.
     *
     * <p>Every candidate gets a real path test before it is chosen. A 48 block
     * spherical search finds cabinets on the floor above and chests behind walls,
     * and v0.5 reached straight through the ceiling into them. Now the container
     * must be somewhere Lumen can walk to and stand beside.
     *
     * @return false when there is nowhere left to try
     */
    private boolean targetNextContainer(ServerWorld world) {
        LumenConfig config = Lumen.config();
        Identifier dimension = world.getRegistryKey().getValue();
        ItemStack sameAs = fetchSample.isEmpty() ? null : fetchSample;
        // "from the storage room" searches around the room, not around Lumen.
        BlockPos center = fetchAnchor != null ? fetchAnchor : this.getBlockPos();

        for (BlockPos remembered : Lumen.memory().recall(fetchQuery, dimension, center,
                config.memoryRecallRadius, triedContainers)) {
            if (!world.isChunkLoaded(remembered.getX() >> 4, remembered.getZ() >> 4)) {
                // Cannot check from here, but Lumen knows the way - go and look.
                this.mode = Mode.FETCH;
                this.fetchChest = remembered;
                return true;
            }
            ContainerAccess access = ContainerAccess.at(world, remembered);
            if (access != null && ChestFinder.scoreContainer(access.contents(), fetchQuery, sameAs)
                    >= Math.max(ChestFinder.SUBSTRING_MATCH, fetchMinScore)) {
                if (isReachable(remembered)) {
                    this.mode = Mode.FETCH;
                    this.fetchChest = remembered;
                    return true;
                }
                this.fetchSawUnreachable = true;
                this.triedContainers.add(remembered);
                continue;
            }
            Lumen.memory().forgetContainer(dimension, remembered);
            this.triedContainers.add(remembered);
        }

        List<ChestFinder.Match> matches = ChestFinder.findContainersWith(
                world, center, config.chestSearchRadius, fetchQuery, fetchMinScore, sameAs,
                triedContainers);
        int tested = 0;
        for (ChestFinder.Match match : matches) {
            if (tested++ >= MAX_PATH_TESTS) {
                break;
            }
            if (isReachable(match.pos())) {
                this.mode = Mode.FETCH;
                this.fetchChest = match.pos();
                return true;
            }
            this.fetchSawUnreachable = true;
        }
        return false;
    }

    /**
     * Whether Lumen can actually walk to a spot beside {@code target}, by asking the
     * pathfinder rather than guessing from distance.
     */
    public boolean isReachable(BlockPos target) {
        BlockPos approach = canStandAt(target) ? target : findApproach(target);
        if (approach == null) {
            return false;
        }
        if (this.getBlockPos().isWithinDistance(approach, 1.5D)) {
            return true;
        }
        if (!this.isOnGround()) {
            // The pathfinder refuses to plan mid-air; do not condemn the container for it.
            return true;
        }
        Path path = this.getNavigation().findPathTo(approach, 1);
        if (path == null) {
            return false;
        }
        if (path.reachesTarget()) {
            return true;
        }
        PathNode end = path.getEnd();
        return end != null && approach.isWithinDistance(new BlockPos(end.x, end.y, end.z), 2.0D);
    }

    @Nullable
    public BlockPos getFetchChest() {
        return getMode() == Mode.FETCH ? fetchChest : null;
    }

    public List<ItemStack> getPendingDelivery() {
        return pendingDelivery;
    }

    /** What Lumen is trying to fetch right now, or null. */
    @Nullable
    public String getFetchQuery() {
        return fetchQuery;
    }

    /**
     * Called by the fetch goal when the container it was walking to turned out to be
     * unreachable after all. Moves on to the next candidate rather than idling in
     * silence, which is what v0.5 did.
     */
    public void fetchUnreachable() {
        BlockPos chest = this.fetchChest;
        this.fetchChest = null;
        if (chest == null || !(this.getWorld() instanceof ServerWorld world)) {
            taskDone();
            return;
        }
        this.triedContainers.add(chest);
        this.fetchSawUnreachable = true;
        this.fetchAttempts++;
        if (this.fetchAttempts < 6 && fetchQuery != null && targetNextContainer(world)) {
            return;
        }
        finishFetch(world);
    }

    /**
     * Empties the matching items out of the container Lumen walked to, then heads back
     * to whoever asked. Called by the fetch goal once Lumen is standing at the chest.
     */
    public void collectFromChest() {
        LumenConfig config = Lumen.config();
        BlockPos chest = this.fetchChest;
        String query = this.fetchQuery;
        this.fetchChest = null;
        if (chest == null || query == null || !(this.getWorld() instanceof ServerWorld world)) {
            taskDone();
            return;
        }
        Identifier dimension = world.getRegistryKey().getValue();
        this.triedContainers.add(chest);
        this.fetchAttempts++;

        int taken = 0;
        ContainerAccess access = ContainerAccess.at(world, chest);
        if (access != null) {
            world.playSound(null, chest, SoundEvents.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, 0.6F, 1.0F);
            taken = takeFrom(access, query, dimension, chest, config);
            access.finish();
            world.playSound(null, chest, SoundEvents.BLOCK_CHEST_CLOSE, SoundCategory.BLOCKS, 0.6F, 1.0F);
        }

        PlayerEntity requester = deliverTo == null ? null : world.getPlayerByUuid(deliverTo);
        // Taking from someone's storage is worth an audit line in the server log.
        Lumen.LOGGER.info("Lumen took {} item(s) matching '{}' from the container at {} for {}",
                taken, query, chest.toShortString(),
                requester == null ? "nobody" : requester.getName().getString());

        // Keep going to the next container while there is still some of the order left.
        if (taken > 0 && this.fetchWanted > 0 && this.fetchAttempts < 6 && targetNextContainer(world)) {
            return;
        }
        if (taken == 0) {
            // The memory was stale, or somebody emptied it. Forget it and look elsewhere,
            // but only a few times so a wrong query cannot send Lumen on a tour.
            Lumen.memory().forgetContainer(dimension, chest);
            if (this.fetchAttempts < 4 && targetNextContainer(world)) {
                return;
            }
        }
        finishFetch(world);
    }

    /**
     * Takes what was asked for out of one container, and no more.
     *
     * <p>Only the best match tier present is touched: "stone" takes stone and leaves
     * the cobblestone alone, and only falls back to cobblestone in a container that
     * has no stone at all - and not even then once real stone is already in hand.
     * Within the tier, whatever was taken earlier this errand goes first, so an order
     * for wool does not come back as a rainbow.
     */
    private int takeFrom(ContainerAccess access, String query, Identifier dimension, BlockPos chest,
                         LumenConfig config) {
        List<ItemStack> contents = access.contents();
        ItemStack sameAs = fetchSample.isEmpty() ? null : fetchSample;
        int tier = ChestFinder.scoreContainer(contents, query, sameAs);
        if (tier < Math.max(ChestFinder.SUBSTRING_MATCH, fetchMinScore)) {
            return 0;
        }
        // Distinct item kinds in this container at the winning tier, familiar ones first.
        List<ItemStack> kinds = new ArrayList<>();
        for (ItemStack stack : contents) {
            boolean familiar = sameAs != null && ItemStack.canCombine(stack, sameAs);
            if (!familiar && ChestFinder.matchScore(stack, query) != tier) {
                continue;
            }
            boolean seen = false;
            for (ItemStack kind : kinds) {
                if (ItemStack.canCombine(kind, stack)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                if (familiar) {
                    kinds.add(0, stack);
                } else {
                    kinds.add(stack);
                }
            }
        }

        int taken = 0;
        int remaining = Math.max(1, this.fetchWanted);
        for (ItemStack kind : kinds) {
            if (remaining <= 0) {
                break;
            }
            if (this.fetchStacks > 0.0D) {
                // Now that the actual item is in hand, a "stack" has a real size:
                // 64 for most things, 16 for ender pearls, 1 for a pickaxe.
                remaining = (int) Math.max(1, Math.ceil(this.fetchStacks * kind.getMaxCount()));
                remaining = Math.min(remaining, config.maxFetchItems);
                this.fetchWanted = remaining;
                this.fetchRequested = remaining;
                this.fetchStacks = 0.0D;
            }
            // One stack's worth per call, so a big order comes out as proper stacks.
            for (int round = 0; remaining > 0 && round < 64; round++) {
                ItemStack removed = access.take(kind, remaining);
                if (removed.isEmpty()) {
                    break;
                }
                remaining -= removed.getCount();
                taken += removed.getCount();
                this.fetchTaken += removed.getCount();
                if (this.fetchSample.isEmpty()) {
                    this.fetchSample = removed.copyWithCount(1);
                }
                // Once something real has been taken, nothing weaker counts any more.
                this.fetchMinScore = Math.max(this.fetchMinScore, tier);
                pendingDelivery.add(removed);
                // Learned: this container is where that item lives.
                Lumen.memory().rememberContainer(query, Registries.ITEM.getId(removed.getItem()), dimension, chest);
            }
        }
        this.fetchWanted = remaining;
        return taken;
    }

    /**
     * Ends the errand: says how it went, in words that do not read as failure when
     * nine of twelve were found, then heads back to whoever asked.
     */
    private void finishFetch(ServerWorld world) {
        String query = this.fetchQuery == null ? "that" : this.fetchQuery;
        String what = this.fetchSample.isEmpty() ? query : ChestFinder.plainName(this.fetchSample);
        PlayerEntity requester = deliverTo == null ? null : world.getPlayerByUuid(deliverTo);
        this.fetchQuery = null;
        this.fetchChest = null;
        this.fetchStacks = 0.0D;

        String line;
        if (this.fetchTaken == 0) {
            line = this.fetchSawUnreachable
                    ? "i can see some " + query + " in a container but i can't get to it"
                    : "i couldn't find any " + query + " in anything nearby";
        } else if (this.fetchRequested != Integer.MAX_VALUE && this.fetchTaken < this.fetchRequested) {
            line = "found " + this.fetchTaken + " " + what + " but you asked for " + this.fetchRequested
                    + " - that's all i could find" + (this.fetchSawUnreachable ? " that i could get to" : "")
                    + ". bringing it over";
        } else {
            line = "got " + this.fetchTaken + " " + what + ", bringing it over";
        }
        Lumen.broadcast(world.getServer(), line);
        this.fetchAnchor = null;
        taskDone();
    }

    /**
     * Hands fetched items over once Lumen is back beside whoever asked.
     *
     * <p>They go into the pack rather than onto the ground: dropped items despawn,
     * fall through blocks and get lost. Lumen is a walking chest - right-click to take
     * things out. Only what will not fit is dropped.
     */
    private void deliverIfClose() {
        if (deliverTo == null || (pendingDelivery.isEmpty() && pendingHandover == null)) {
            return;
        }
        // Not while the errand is still running: handing over half an order in passing
        // would forget who asked before the rest was collected.
        if (getMode() == Mode.FETCH || getMode() == Mode.MINE) {
            return;
        }
        PlayerEntity requester = this.getWorld().getPlayerByUuid(deliverTo);
        if (requester == null || requester.getWorld() != this.getWorld()) {
            return;
        }
        // The follow goal stops short of the player, so "close" has to be at least as
        // far as it stops, or Lumen stands 3.5 blocks away holding the goods forever.
        double reach = Math.max(3.0D, Lumen.config().followStartDistance);
        if (this.squaredDistanceTo(requester) > reach * reach) {
            return;
        }
        if (pendingHandover != null) {
            String query = pendingHandover;
            this.pendingHandover = null;
            HandoverResult result = handOver(requester, "*".equals(query) ? null : query);
            Lumen.broadcast(this.getWorld().getServer(), describeHandover(result, "*".equals(query) ? "" : query));
            if (currentTask instanceof LumenTask.Handover) {
                taskDone();
                return;
            }
        }
        if (pendingDelivery.isEmpty()) {
            if (pendingHandover == null) {
                this.deliverTo = null;
            }
            return;
        }
        int stored = 0;
        int dropped = 0;
        int count = 0;
        ItemStack first = ItemStack.EMPTY;
        for (ItemStack stack : pendingDelivery) {
            if (stack.isEmpty()) {
                continue;
            }
            if (first.isEmpty()) {
                first = stack.copyWithCount(1);
            }
            count += stack.getCount();
            ItemStack leftover = getInventory().addStack(stack);
            if (leftover.isEmpty()) {
                stored++;
            } else {
                putDown(leftover, requester);
                dropped++;
            }
        }
        pendingDelivery.clear();
        this.deliverTo = null;
        if (stored > 0 || dropped > 0) {
            String what = first.isEmpty() ? "it" : count + " " + ChestFinder.plainName(first);
            String note = dropped == 0
                    ? "here's your " + what + " - right-click me to take it"
                    : "my pack is full, so some of your " + what + " is on the ground by you";
            requester.sendMessage(Text.literal(Lumen.config().companionName + ": " + note)
                    .formatted(Formatting.AQUA), false);
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

    /** True while Lumen is walking back to somebody with something for them. */
    public boolean isReturningWithGoods() {
        return deliverTo != null && (!pendingDelivery.isEmpty() || pendingHandover != null);
    }

    /** Human readable state, fed to the model as part of the world snapshot. */
    public String describeActivity() {
        String now = describeCurrentActivity();
        if (!taskQueue.isEmpty()) {
            return now + ", with " + taskQueue.size() + " more thing" + (taskQueue.size() == 1 ? "" : "s")
                    + " lined up (next: " + taskQueue.peekFirst().describe() + ")";
        }
        return now;
    }

    private String describeCurrentActivity() {
        switch (getMode()) {
            case FOLLOW -> {
                PlayerEntity target = getFollowTarget();
                if (target == null) {
                    return "standing around";
                }
                if (isReturningWithGoods()) {
                    return "bringing " + describePending() + " back to " + target.getName().getString();
                }
                return "following " + target.getName().getString();
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
            case MINE -> {
                if (workLabel != null) {
                    return workLabel + " (" + workDone + " done, " + work.size() + " to go)";
                }
                return mineQuery == null ? "standing around"
                        : "mining " + mineQuery + " (" + minedCount + " so far)";
            }
            default -> {
                if (currentTask instanceof LumenTask.Collect) {
                    return "picking up the drops";
                }
                return "standing around";
            }
        }
    }

    private String describePending() {
        if (pendingDelivery.isEmpty()) {
            return "something";
        }
        int count = 0;
        for (ItemStack stack : pendingDelivery) {
            count += stack.getCount();
        }
        return count + " " + ChestFinder.plainName(pendingDelivery.get(0));
    }

    // ---------------------------------------------------------------- inventory

    public SimpleInventory getInventory() {
        if (inventory == null) {
            inventory = new SimpleInventory(Math.max(1, Lumen.config().inventorySize));
        }
        return inventory;
    }

    /**
     * Opens Lumen's pack as an ordinary vanilla container screen, with a bottom row
     * showing what it is holding and wearing. Because it is a vanilla screen the
     * client already knows how to draw it, so this works on an unmodified client -
     * the same reason Lumen borrows a vanilla entity type.
     */
    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        LumenPackInventory view = new LumenPackInventory(this, getInventory());
        // The pack is 27 or 45 slots, so with the equipment row the screen is 9x4 or
        // 9x6 - both shapes a vanilla client can draw.
        ScreenHandlerType<GenericContainerScreenHandler> type = view.rows() >= 6
                ? ScreenHandlerType.GENERIC_9X6 : ScreenHandlerType.GENERIC_9X4;
        return new GenericContainerScreenHandler(type, syncId, playerInventory, view, view.rows());
    }

    /** The pack screen, titled so the equipment row explains itself. */
    private void openPack(PlayerEntity player) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(this::createMenu,
                Text.literal(Lumen.config().companionName + " - bottom row: hand, offhand, armour")));
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

    // ------------------------------------------------------------- handing over

    /** What a handover did. */
    public record HandoverResult(int stacks, int items, String what, boolean droppedSome) {
    }

    static boolean meansEverything(@Nullable String query) {
        return ChestFinder.meansEverything(query);
    }

    /**
     * Asks Lumen to hand something over. If the player is close it happens now;
     * otherwise Lumen walks over and does it on arrival, through {@link #deliverIfClose()}.
     *
     * @param query what to hand over, or null / "everything" for the lot
     * @return the result if it happened now, or null if Lumen is on its way
     */
    @Nullable
    public HandoverResult requestHandover(PlayerEntity player, @Nullable String query) {
        String wanted = meansEverything(query) ? null : query.trim();
        double reach = Math.max(3.0D, Lumen.config().followStartDistance);
        if (this.squaredDistanceTo(player) <= reach * reach && player.getWorld() == this.getWorld()) {
            return handOver(player, wanted);
        }
        if (isBusy()) {
            // Mid-errand: walking off now would abandon it, and re-point what it has
            // already collected at a different player. Queue it behind instead.
            taskQueue.addLast(new LumenTask.Handover(player.getUuid(), wanted));
            return null;
        }
        this.pendingHandover = wanted == null ? "*" : wanted;
        this.deliverTo = player.getUuid();
        followPlayer(player);
        return null;
    }

    /** Whether Lumen has anything at all that answers to {@code query} (null for anything). */
    public boolean isCarrying(@Nullable String query) {
        String wanted = meansEverything(query) ? null : query.trim();
        return !carriedMatching(wanted).isEmpty();
    }

    /** One place an item lives on Lumen, so it can be taken from there. */
    private interface Carried {
        ItemStack peek();

        ItemStack remove();
    }

    private List<Carried> carriedMatching(@Nullable String query) {
        List<Carried> all = new ArrayList<>();
        SimpleInventory inv = getInventory();
        for (int slot = 0; slot < inv.size(); slot++) {
            int index = slot;
            all.add(new Carried() {
                public ItemStack peek() {
                    return inv.getStack(index);
                }

                public ItemStack remove() {
                    return inv.removeStack(index);
                }
            });
        }
        for (EquipmentSlot slot : LumenPackInventory.EQUIPMENT) {
            all.add(new Carried() {
                public ItemStack peek() {
                    return getEquippedStack(slot);
                }

                public ItemStack remove() {
                    ItemStack worn = getEquippedStack(slot);
                    equipStack(slot, ItemStack.EMPTY);
                    return worn;
                }
            });
        }
        for (int i = 0; i < pendingDelivery.size(); i++) {
            int index = i;
            all.add(new Carried() {
                public ItemStack peek() {
                    return pendingDelivery.get(index);
                }

                public ItemStack remove() {
                    ItemStack stack = pendingDelivery.get(index);
                    pendingDelivery.set(index, ItemStack.EMPTY);
                    return stack;
                }
            });
        }
        List<Carried> matching = new ArrayList<>();
        if (query == null) {
            for (Carried carried : all) {
                if (!carried.peek().isEmpty()) {
                    matching.add(carried);
                }
            }
            return matching;
        }
        int best = ChestFinder.NO_MATCH;
        for (Carried carried : all) {
            best = Math.max(best, ChestFinder.matchScore(carried.peek(), query));
        }
        if (best == ChestFinder.NO_MATCH) {
            return matching;
        }
        for (Carried carried : all) {
            if (ChestFinder.matchScore(carried.peek(), query) == best) {
                matching.add(carried);
            }
        }
        return matching;
    }

    /**
     * Puts items straight into the player's inventory - never on the ground, where
     * Lumen's own pickup would have them back before the player could bend down.
     * Whatever the player has no room for is set down beside them, and Lumen ignores
     * it. Covers the pack, what is worn and held, and anything fetched but not yet
     * delivered.
     */
    public HandoverResult handOver(PlayerEntity player, @Nullable String query) {
        List<Carried> matching = carriedMatching(query);
        int stacks = 0;
        int items = 0;
        boolean droppedSome = false;
        ItemStack first = ItemStack.EMPTY;
        for (Carried carried : matching) {
            ItemStack stack = carried.remove();
            if (stack.isEmpty()) {
                continue;
            }
            if (first.isEmpty()) {
                first = stack.copyWithCount(1);
            }
            stacks++;
            items += stack.getCount();
            if (!player.getInventory().insertStack(stack) || !stack.isEmpty()) {
                if (!stack.isEmpty()) {
                    putDown(stack, player);
                    droppedSome = true;
                }
            }
        }
        pendingDelivery.removeIf(ItemStack::isEmpty);
        if (pendingDelivery.isEmpty() && pendingHandover == null) {
            this.deliverTo = null;
        }
        String what = first.isEmpty() ? "" : (stacks == 1 && items == 1
                ? ChestFinder.plainName(first) : items + " " + ChestFinder.plainName(first)
                + (stacks > 1 && query == null ? " and more" : ""));
        return new HandoverResult(stacks, items, what, droppedSome);
    }

    /** The chat line for a handover that just happened. */
    public static String describeHandover(HandoverResult result, String query) {
        if (result.stacks() == 0) {
            return query == null || query.isBlank() ? "i'm not carrying anything"
                    : "i don't have any " + query.trim();
        }
        String line = "here you go" + (result.what().isEmpty() ? "" : " - " + result.what());
        if (result.droppedSome()) {
            line += ". your bag is full so some of it's on the ground by you";
        }
        return line;
    }

    /**
     * Sets a stack down at the player's feet in a way Lumen will not pick straight
     * back up: it remembers the item entity, and stops collecting anything at all for
     * a while - the drop merges with others on the floor, changing its identity.
     */
    private void putDown(ItemStack stack, PlayerEntity player) {
        if (stack.isEmpty()) {
            return;
        }
        ItemEntity item = new ItemEntity(player.getWorld(), player.getX(), player.getY() + 0.3D, player.getZ(),
                stack);
        item.setPickupDelay(0);
        item.setOwner(player.getUuid()); // only they can pick it up for a good while
        item.setVelocity(0.0D, 0.1D, 0.0D);
        player.getWorld().spawnEntity(item);
        ignoreDrop(item);
    }

    /** Marks an item on the ground as Lumen's own doing, not loot. */
    public void ignoreDrop(ItemEntity item) {
        this.ownDrops.put(item.getUuid(), this.age + 20 * 60 * 5);
        this.pickUpSuppressedUntil = this.age + 20 * 10;
    }

    /** Whether an item on the ground is fair game, rather than something Lumen just put down. */
    public boolean wantsToPickUp(ItemEntity item) {
        if (!Lumen.config().pickUpItems || !item.isAlive() || item.cannotPickup()) {
            return false;
        }
        if (this.age < this.pickUpSuppressedUntil) {
            return false;
        }
        return !ownDrops.containsKey(item.getUuid());
    }

    /** Picks up anything Lumen is standing on top of. */
    private void collectNearbyItems() {
        LumenConfig config = Lumen.config();
        if (!config.pickUpItems || --this.pickUpCooldown > 0) {
            return;
        }
        this.pickUpCooldown = 10;
        if (!ownDrops.isEmpty()) {
            int now = this.age;
            ownDrops.values().removeIf(expiry -> expiry < now);
        }
        Box box = this.getBoundingBox().expand(1.0D, 0.5D, 1.0D);
        List<ItemEntity> items = this.getWorld().getEntitiesByClass(ItemEntity.class, box, this::wantsToPickUp);
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

    /**
     * Eats something from the pack when hurt. Lumen has no hunger bar, so food is
     * simply a heal - which is the only way it recovers health at all.
     */
    private void eatIfHurt() {
        LumenConfig config = Lumen.config();
        if (!config.eatWhenHurt || --this.eatCooldown > 0) {
            return;
        }
        this.eatCooldown = 40;
        boolean hurt = this.getHealth() < this.getMaxHealth() * config.eatHealthFraction;
        boolean hungry = config.hungerEnabled && this.foodLevel <= 14.0F;
        if (!hurt && !hungry) {
            return;
        }
        SimpleInventory pack = getInventory();
        for (int slot = 0; slot < pack.size(); slot++) {
            ItemStack stack = pack.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            FoodComponent food = stack.getItem().getFoodComponent();
            if (food == null) {
                continue;
            }
            if (hurt) {
                this.heal(Math.max(1.0F, food.getHunger() / 2.0F));
            }
            this.foodLevel = Math.min(20.0F, this.foodLevel + food.getHunger());
            this.playSound(SoundEvents.ENTITY_GENERIC_EAT, 0.8F, 1.0F);
            stack.decrement(1);
            if (stack.isEmpty()) {
                pack.setStack(slot, ItemStack.EMPTY);
            }
            this.eatCooldown = 100;
            return;
        }
    }

    // ------------------------------------------------------------ doors and gates

    /**
     * Opens a fence gate Lumen is walking into. Vanilla mobs cannot do this at all -
     * only the pathfinder relaxation in LumenPathNodeMaker makes a closed gate
     * routable in the first place. Closing is handled by {@link #handlePassages()},
     * with a timer here as a fallback for a gate opened but never walked through.
     */
    private void handleFenceGates() {
        if (!Lumen.config().canOpenDoors) {
            return;
        }
        if (openedGate != null && --this.gateCloseTimer <= 0) {
            if (isInside(openedGate)) {
                this.gateCloseTimer = 10; // still in it; look again shortly
            } else {
                setGateOpen(openedGate, false);
                this.openedGate = null;
            }
        }
        if (this.getNavigation().isIdle() || --this.gateScanCooldown > 0) {
            return;
        }
        this.gateScanCooldown = 5;
        BlockPos origin = this.getBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos candidate = origin.add(dx, dy, dz);
                    if (setGateOpen(candidate, true)) {
                        this.openedGate = candidate;
                        this.gateCloseTimer = 40; // ~2s, long enough to walk through
                        return;
                    }
                }
            }
        }
    }

    /** @return true if this actually was a gate that changed state */
    private boolean setGateOpen(BlockPos pos, boolean open) {
        World world = this.getWorld();
        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof FenceGateBlock) || !state.contains(Properties.OPEN)
                || state.get(Properties.OPEN) == open) {
            return false;
        }
        world.setBlockState(pos, state.with(Properties.OPEN, open), Block.NOTIFY_LISTENERS);
        world.playSound(null, pos,
                open ? SoundEvents.BLOCK_FENCE_GATE_OPEN : SoundEvents.BLOCK_FENCE_GATE_CLOSE,
                SoundCategory.BLOCKS, 1.0F, 1.0F);
        return true;
    }

    /**
     * Closes doors and gates behind Lumen, including ones it did not open.
     *
     * <p>The vanilla door goal only ever closes a door it opened itself, and when
     * Lumen follows a player through a doorway the player opened it. So every door or
     * gate Lumen's body overlaps while moving is noted, and once Lumen is clear of it
     * for half a second - and nobody else is standing in it - it is shut.
     */
    private void handlePassages() {
        if (!Lumen.config().canOpenDoors) {
            return;
        }
        World world = this.getWorld();
        Box box = this.getBoundingBox();
        Set<BlockPos> inside = new HashSet<>();
        for (BlockPos pos : BlockPos.iterate(MathHelper.floor(box.minX), MathHelper.floor(box.minY),
                MathHelper.floor(box.minZ), MathHelper.floor(box.maxX - 1.0E-6D),
                MathHelper.floor(box.maxY - 1.0E-6D), MathHelper.floor(box.maxZ - 1.0E-6D))) {
            BlockPos base = passageBase(world.getBlockState(pos), pos);
            if (base != null) {
                inside.add(base);
            }
        }
        for (BlockPos pos : inside) {
            passages.put(pos, 0);
        }
        if (passages.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<BlockPos, Integer>> iterator = passages.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            BlockPos pos = entry.getKey();
            if (inside.contains(pos)) {
                continue;
            }
            int ticks = entry.getValue() + 1;
            entry.setValue(ticks);
            if (ticks > 100) {
                iterator.remove(); // somebody keeps standing in it; not our problem
                continue;
            }
            if (ticks < 10) {
                continue;
            }
            if (this.squaredDistanceTo(pos.getX() + 0.5D, this.getY(), pos.getZ() + 0.5D) < 1.0D) {
                continue; // still brushing against it
            }
            if (tryClosePassage(pos)) {
                iterator.remove();
            }
        }
    }

    /** The lower door half or the gate block, if this is an open door or gate Lumen can use. */
    @Nullable
    private static BlockPos passageBase(BlockState state, BlockPos pos) {
        if (state.getBlock() instanceof DoorBlock && DoorBlock.canOpenByHand(state)
                && state.contains(DoorBlock.OPEN) && state.get(DoorBlock.OPEN)) {
            return state.contains(DoorBlock.HALF) && state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER
                    ? pos.down().toImmutable() : pos.toImmutable();
        }
        if (state.getBlock() instanceof FenceGateBlock && state.contains(Properties.OPEN)
                && state.get(Properties.OPEN)) {
            return pos.toImmutable();
        }
        return null;
    }

    /** @return true when the passage is closed now, or is gone and can be forgotten */
    private boolean tryClosePassage(BlockPos pos) {
        World world = this.getWorld();
        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return true;
        }
        BlockState state = world.getBlockState(pos);
        boolean door = state.getBlock() instanceof DoorBlock;
        boolean gate = state.getBlock() instanceof FenceGateBlock;
        if (!door && !gate) {
            return true;
        }
        if (!state.contains(Properties.OPEN) || !state.get(Properties.OPEN)) {
            return true; // already shut, by the player or the door goal
        }
        // Never shut it on somebody: the player is usually a step behind or ahead.
        Box doorway = new Box(pos).stretch(0.0D, 1.0D, 0.0D).expand(0.2D, 0.0D, 0.2D);
        if (!world.getOtherEntities(this, doorway, entity -> entity instanceof LivingEntity).isEmpty()) {
            return false;
        }
        if (door) {
            ((DoorBlock) state.getBlock()).setOpen(this, world, state, pos, false);
            return true;
        }
        return setGateOpen(pos, false);
    }

    private boolean isInside(BlockPos pos) {
        return this.getBoundingBox().intersects(new Box(pos));
    }

    /** True while Lumen is on a task a passing remark should not cancel. */
    public boolean isOnErrand() {
        Mode current = getMode();
        return currentTask != null
                || current == Mode.FETCH || current == Mode.MINE || current == Mode.GO_TO
                || (current == Mode.FOLLOW && isReturningWithGoods());
    }

    // ------------------------------------------------------------------- mining

    /**
     * Sends Lumen off to break blocks matching {@code query} and bring them back.
     *
     * @return false when nothing suitable is in range, so the caller can say so
     */
    @Nullable
    public String startMining(PlayerEntity requester, String query) {
        return startMining(requester, query, null);
    }

    /**
     * @param anchor where to look for blocks - a named place - or null for around Lumen
     */
    @Nullable
    public String startMining(PlayerEntity requester, String query, @Nullable BlockPos anchor) {
        LumenConfig config = Lumen.config();
        if (!config.allowMining) {
            return "mining is switched off";
        }
        if (!(this.getWorld() instanceof ServerWorld world)) {
            return "i can't do that here";
        }
        this.mineQuery = query;
        this.mineAnchor = anchor;
        this.deliverTo = requester.getUuid();
        setOwner(requester);
        this.minedCount = 0;
        this.minedPositions.clear();
        this.followTarget = null;
        this.destination = null;
        this.stuckTicks = 0;
        if (!targetNextBlock(world)) {
            this.mineQuery = null;
            return "i can't see any " + query + " around here";
        }
        // Checked before setting out. Walking over, playing the whole break animation
        // and only then discovering the tool is wrong is what v0.3.0 did.
        if (!canHarvest(this.mineTarget)) {
            this.mineTarget = null;
            this.mineQuery = null;
            this.mode = Mode.IDLE;
            return this.getMainHandStack().isEmpty()
                    ? "i'd need a tool for that - put one in my pack"
                    : "what i'm holding won't break that";
        }
        return null;
    }

    private boolean targetNextBlock(ServerWorld world) {
        LumenConfig config = Lumen.config();
        if (workLabel != null) {
            // A skill or quarry: take the next block on the list that is still worth doing.
            this.currentWork = null;
            while (!work.isEmpty()) {
                WorkItem item = work.pollFirst();
                BlockPos pos = item.pos();
                if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                    workSkipped++;
                    continue;
                }
                BlockState state = world.getBlockState(pos);
                if (state.isAir() || state.hasBlockEntity()) {
                    workSkipped++;
                    continue;
                }
                if (!item.interact()) {
                    if (!MineFinder.isMineable(world, state, pos) || touchesFluid(world, pos)) {
                        workSkipped++;
                        continue;
                    }
                }
                if (findApproach(pos) == null && !canStandAt(pos.up())) {
                    workSkipped++;
                    continue;
                }
                this.currentWork = item;
                this.mode = Mode.MINE;
                this.mineTarget = pos;
                return true;
            }
            return false;
        }
        if (mineQuery == null) {
            return false;
        }
        BlockPos next = MineFinder.findNearest(world, this, mineAnchor != null ? mineAnchor : this.getBlockPos(),
                mineQuery, config.miningRadius, config.miningHeight, minedPositions);
        if (next == null) {
            return false;
        }
        this.mode = Mode.MINE;
        this.mineTarget = next;
        return true;
    }

    /** A block with lava or water against it is left alone: breaking it lets the fluid in. */
    private static boolean touchesFluid(ServerWorld world, BlockPos pos) {
        for (Direction side : Direction.values()) {
            if (!world.getFluidState(pos.offset(side)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** True while the block Lumen is heading for is to be right-clicked rather than broken. */
    public boolean currentWorkIsInteract() {
        return currentWork != null && currentWork.interact();
    }

    /**
     * Whether Lumen could actually break {@code pos} with what it is holding. Checked
     * before setting out, so "I need a pickaxe" is said up front rather than after
     * standing there playing the whole break animation.
     */
    public boolean canHarvest(BlockPos pos) {
        if (!(this.getWorld() instanceof ServerWorld world)) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        return !state.isToolRequired() || this.getMainHandStack().isSuitableFor(state);
    }

    @Nullable
    public BlockPos getMineTarget() {
        return getMode() == Mode.MINE ? mineTarget : null;
    }

    /**
     * Breaks the block Lumen has been standing at, banks the drops for delivery, and
     * lines up the next one.
     *
     * @return a message to relay if the errand ended, otherwise null to keep going
     */
    @Nullable
    public String breakTargetBlock() {
        LumenConfig config = Lumen.config();
        BlockPos target = this.mineTarget;
        if (target == null || !(this.getWorld() instanceof ServerWorld world)) {
            finishMining();
            return null;
        }
        this.minedPositions.add(target);
        this.mineTarget = null;

        if (currentWork != null && currentWork.interact()) {
            return interactTarget(world, target);
        }

        BlockState state = world.getBlockState(target);
        if (!MineFinder.isMineable(world, state, target)) {
            return continueOrFinish(world);
        }

        ItemStack tool = this.getMainHandStack();
        if (state.isToolRequired() && !tool.isSuitableFor(state)) {
            String needed = tool.isEmpty() ? "a tool" : "a better tool";
            finishMining();
            return "can't break " + state.getBlock().getName().getString().toLowerCase(Locale.ROOT)
                    + " without " + needed;
        }

        // Order matters: work out the drops, then break, and only bank them if the
        // break actually happened. Banking first meant a break that was refused - no
        // suitable tool, or a protection mod vetoing it - still handed over items,
        // which is items from nothing and the block still standing.
        List<ItemStack> drops = Block.getDroppedStacks(state, world, target,
                world.getBlockEntity(target), this, tool);
        // The break is done in the name of whoever asked. Claim mods check the entity
        // handed to breakBlock: a mob is refused inside a claim, its owner is not.
        PlayerEntity requester = deliverTo == null ? null : world.getPlayerByUuid(deliverTo);
        Entity breaker = requester != null && requester.getWorld() == world ? requester : this;
        if (!world.breakBlock(target, false, breaker)) {
            String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
            Lumen.LOGGER.warn("world.breakBlock refused {} at {} as {} (chunk loaded: {}); "
                            + "drops not banked. Most likely a protection or claim mod.",
                    blockId, target.toShortString(), breaker.getName().getString(),
                    world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4));
            finishMining();
            String blockName = state.getBlock().getName().getString().toLowerCase(Locale.ROOT);
            return requester == null
                    ? "something won't let me break that " + blockName
                    : "something won't let me break that " + blockName + " - i tried as "
                            + requester.getName().getString() + ", so check the claim permissions there";
        }
        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                pendingDelivery.add(drop);
            }
        }
        world.setBlockBreakingInfo(this.getId(), target, -1);
        this.swingHand(Hand.MAIN_HAND);
        if (!tool.isEmpty()) {
            tool.damage(1, this, holder -> holder.sendEquipmentBreakStatus(EquipmentSlot.MAINHAND));
        }
        this.minedCount++;
        this.workDone++;

        // A plain "mine iron" stops at maxMineBlocks; a skill or quarry has its own list.
        if (workLabel == null && this.minedCount >= config.maxMineBlocks) {
            finishMining();
            return null;
        }
        return continueOrFinish(world);
    }

    /**
     * Right-clicks the block for a taught skill. There is no fake player in Fabric API
     * 1.20.1 and a block's use handler needs a real one, so the click is made in the
     * name of whoever asked, exactly as mining inside a claim is. That also means they
     * have to be nearby: a click from across the map would be a strange thing to allow.
     */
    @Nullable
    private String interactTarget(ServerWorld world, BlockPos target) {
        LumenConfig config = Lumen.config();
        if (!config.allowInteract) {
            finishMining();
            return "using blocks is switched off in the config";
        }
        PlayerEntity requester = deliverTo == null ? null : world.getPlayerByUuid(deliverTo);
        if (requester == null || requester.getWorld() != world) {
            finishMining();
            return "i need whoever asked to be around for that";
        }
        if (requester.squaredDistanceTo(Vec3d.ofCenter(target)) > config.interactRange * config.interactRange) {
            finishMining();
            return "come a bit closer - i can only use blocks while you're within "
                    + Math.round(config.interactRange) + " blocks";
        }
        BlockState state = world.getBlockState(target);
        if (state.isAir() || state.hasBlockEntity()) {
            workSkipped++;
            return continueOrFinish(world);
        }
        this.getLookControl().lookAt(Vec3d.ofCenter(target));
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(target), Direction.UP, target, false);
        ActionResult result;
        try {
            result = state.onUse(world, requester, Hand.MAIN_HAND, hit);
        } catch (RuntimeException e) {
            Lumen.LOGGER.warn("Using {} at {} threw {}", BlockStates.id(state), target.toShortString(), e.toString());
            result = ActionResult.FAIL;
        }
        this.swingHand(Hand.MAIN_HAND);
        Lumen.LOGGER.info("Lumen used {} at {} as {}: {}", BlockStates.describe(state), target.toShortString(),
                requester.getName().getString(), result);
        if (result.isAccepted()) {
            this.workDone++;
        } else {
            this.workSkipped++;
        }
        return continueOrFinish(world);
    }

    private String continueOrFinish(ServerWorld world) {
        if (!targetNextBlock(world) || !canHarvest(this.mineTarget)) {
            finishMining();
        }
        return null;
    }

    /** Ends the errand and heads back to whoever asked, carrying the haul. */
    public void finishMining() {
        this.mineTarget = null;
        this.mineQuery = null;
        this.minedPositions.clear();
        this.mineAnchor = null;
        if (this.getWorld() instanceof ServerWorld world) {
            world.setBlockBreakingInfo(this.getId(), this.getBlockPos(), -1);
        }
        if (workLabel != null) {
            String label = workLabel;
            int done = workDone;
            int skipped = workSkipped;
            boolean collect = workCollect && done > 0;
            BlockPos center = workCenter == null ? this.getBlockPos() : workCenter;
            UUID requester = deliverTo;
            clearWork();
            Lumen.broadcast(this.getWorld().getServer(), done == 0
                    ? "couldn't do any of the " + label + (skipped > 0 ? " - " + skipped + " weren't worth it or i couldn't reach them" : "")
                    : label + " done - " + done + " block" + (done == 1 ? "" : "s")
                            + (skipped > 0 ? ", skipped " + skipped : "")
                            + (collect ? ", grabbing the drops" : ""));
            if (collect && requester != null) {
                taskQueue.addFirst(new LumenTask.Collect(requester, center, 10.0D));
            }
        }
        taskDone();
    }

    // -------------------------------------------------------------- skills

    /**
     * Runs a taught skill: finds every block its target matches within its radius, keeps
     * the reachable ones, and works them one by one through the mine goal - breaking or
     * right-clicking as the skill says.
     *
     * @return a refusal to say out loud, or null when Lumen set off
     */
    @Nullable
    public String startSkill(PlayerEntity requester, LumenSkill skill, @Nullable BlockPos anchor, @Nullable String anchorName) {
        LumenConfig config = Lumen.config();
        if (skill.isInteract() && !config.allowInteract) {
            return "using blocks is switched off in the config";
        }
        if (!skill.isInteract() && !config.allowMining) {
            return "mining is switched off";
        }
        if (!(this.getWorld() instanceof ServerWorld world)) {
            return "i can't do that here";
        }
        BlockMatcher.Spec spec = BlockMatcher.parse(skill.target);
        BlockPos center = anchor != null ? anchor : this.getBlockPos();
        int radius = Math.max(2, Math.min(32, skill.radius));
        int height = Math.min(radius, 8);
        List<BlockPos> found = new ArrayList<>();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int dy = -height; dy <= height; dy++) {
                    cursor.set(x, center.getY() + dy, z);
                    BlockState state = world.getBlockState(cursor);
                    if (state.isAir() || state.hasBlockEntity()) {
                        continue;
                    }
                    if (!BlockStates.matches(spec, world, cursor, state)) {
                        continue;
                    }
                    if (!skill.isInteract() && !MineFinder.isMineable(world, state, cursor)) {
                        continue;
                    }
                    found.add(cursor.toImmutable());
                }
            }
        }
        if (found.isEmpty()) {
            return "i can't see any " + skill.target + (anchorName == null ? " around here" : " at the " + anchorName);
        }
        found.sort(java.util.Comparator.comparingDouble(pos -> pos.getSquaredDistance(center)));
        this.deliverTo = requester.getUuid();
        setOwner(requester);
        this.mineQuery = null;
        this.mineAnchor = null;
        this.minedCount = 0;
        this.minedPositions.clear();
        this.followTarget = null;
        this.destination = null;
        this.stuckTicks = 0;
        clearWork();
        int cap = Math.max(1, config.maxSkillBlocks);
        for (BlockPos pos : found) {
            if (work.size() >= cap) {
                break;
            }
            work.addLast(new WorkItem(pos, skill.isInteract()));
        }
        this.workLabel = skill.name;
        this.workCollect = skill.collect;
        this.workCenter = center;
        if (!targetNextBlock(world)) {
            clearWork();
            return "i can see the " + skill.target + " but i can't get to any of it";
        }
        if (!skill.isInteract() && !canHarvest(this.mineTarget)) {
            clearWork();
            this.mineTarget = null;
            this.mode = Mode.IDLE;
            return this.getMainHandStack().isEmpty()
                    ? "i'd need a tool for that - put one in my pack"
                    : "what i'm holding won't break that";
        }
        Lumen.skills().noteUse(skill);
        return null;
    }

    // -------------------------------------------------------------- quarry

    /**
     * Digs out a region, top layer first, with a staircase down to it when it starts
     * below Lumen's feet. Bounded by config, and blocks against lava or water, or
     * holding a block entity, are left standing.
     *
     * @return a refusal to say out loud, or null when Lumen set off
     */
    @Nullable
    public String startQuarry(PlayerEntity requester, QuarryPlanner.Region region) {
        LumenConfig config = Lumen.config();
        if (!config.allowMining) {
            return "mining is switched off";
        }
        if (!config.allowQuarry) {
            return "area mining is switched off in the config";
        }
        if (!(this.getWorld() instanceof ServerWorld world)) {
            return "i can't do that here";
        }
        int maxSize = Math.max(1, config.maxQuarrySize);
        if (region.sizeX() > maxSize || region.sizeZ() > maxSize || region.sizeY() > maxSize) {
            return "that's bigger than i'm allowed - " + maxSize + " blocks a side at most";
        }
        if (region.volume() > config.maxQuarryBlocks) {
            return "that's " + region.volume() + " blocks; i'm capped at " + config.maxQuarryBlocks + " per job";
        }
        int bottom = world.getBottomY() + 1;
        int top = world.getTopY() - 2;
        QuarryPlanner.Region clamped = QuarryPlanner.Region.of(region.minX(), Math.max(bottom, region.minY()), region.minZ(),
                region.maxX(), Math.min(top, region.maxY()), region.maxZ());
        BlockPos feet = this.getBlockPos();
        List<int[]> plan = new ArrayList<>();
        int descent = QuarryPlanner.descent(feet.getY(), clamped);
        if (descent > 1) {
            if (descent > config.maxQuarryDescent) {
                return "that's " + descent + " blocks down; i'll dig down " + config.maxQuarryDescent + " at most";
            }
            int centerX = (clamped.minX() + clamped.maxX()) / 2;
            int centerZ = (clamped.minZ() + clamped.maxZ()) / 2;
            int dx = Integer.compare(centerX, feet.getX());
            int dz = dx == 0 ? Integer.compare(centerZ, feet.getZ()) : 0;
            plan.addAll(QuarryPlanner.staircase(feet.getX(), feet.getY(), feet.getZ(), clamped.maxY() + 1, dx, dz));
        }
        plan.addAll(QuarryPlanner.order(clamped, feet.getX(), feet.getZ()));
        this.deliverTo = requester.getUuid();
        setOwner(requester);
        this.mineQuery = null;
        this.mineAnchor = null;
        this.minedCount = 0;
        this.minedPositions.clear();
        this.followTarget = null;
        this.destination = null;
        this.stuckTicks = 0;
        clearWork();
        for (int[] p : plan) {
            work.addLast(new WorkItem(new BlockPos(p[0], p[1], p[2]), false));
        }
        this.workLabel = "quarry";
        this.workCollect = false; // drops are banked straight into the delivery
        this.workCenter = new BlockPos((clamped.minX() + clamped.maxX()) / 2, clamped.maxY(), (clamped.minZ() + clamped.maxZ()) / 2);
        if (!targetNextBlock(world)) {
            clearWork();
            return "there's nothing in that area i can break";
        }
        if (!canHarvest(this.mineTarget)) {
            clearWork();
            this.mineTarget = null;
            this.mode = Mode.IDLE;
            return this.getMainHandStack().isEmpty()
                    ? "i'd need a pickaxe for that - put one in my pack"
                    : "what i'm holding won't break that";
        }
        Lumen.LOGGER.info("Lumen starts a quarry of {} block(s) ({}) for {}", plan.size(), clamped.describe(),
                requester.getName().getString());
        return null;
    }

    // -------------------------------------------------------------- crafting

    /**
     * Starts a craft task: crafts now when the recipe fits in the 2x2 grid or a table
     * is beside Lumen, otherwise walks to the nearest table first.
     */
    private boolean beginCraft(PlayerEntity requester, LumenTask.Craft craft) {
        LumenConfig config = Lumen.config();
        if (!config.allowCrafting) {
            this.lastTaskNote = "crafting is switched off in the config";
            return false;
        }
        if (!(this.getWorld() instanceof ServerWorld world) || world.getServer() == null) {
            this.lastTaskNote = "i can't do that here";
            return false;
        }
        CraftPlanner planner = CraftPlanner.forServer(world.getServer());
        Item item = planner.findCraftable(craft.query());
        if (item == null) {
            this.lastTaskNote = "i don't know how to make " + craft.query();
            return false;
        }
        CraftPlanner.Plan plan = planner.plan(item, Math.max(1, craft.count()), packContents());
        if (plan.isEmpty()) {
            this.lastTaskNote = "i can't make " + CraftPlanner.name(item) + " - " + plan.missing();
            return false;
        }
        this.deliverTo = requester.getUuid();
        setOwner(requester);
        if (plan.needsTable() && config.craftingNeedsTable && findCraftingTable(world, 3) == null) {
            BlockPos table = findCraftingTable(world, (int) Math.min(48, config.chestSearchRadius));
            if (table == null) {
                this.lastTaskNote = "i'd need a crafting table for " + CraftPlanner.name(item) + " and there isn't one nearby";
                return false;
            }
            BlockPos approach = findApproach(table);
            if (approach == null || !isReachable(table)) {
                this.lastTaskNote = "there's a crafting table at " + table.toShortString() + " but i can't get to it";
                return false;
            }
            this.craftAfterArrival = craft;
            goTo(approach);
            Lumen.broadcast(world.getServer(), "heading to the crafting table for that");
            return true;
        }
        String outcome = performCraft(world, requester, craft, planner, item, plan);
        Lumen.broadcast(world.getServer(), outcome);
        // Done on the spot: hand the result over the way a fetch does.
        this.mode = Mode.IDLE;
        taskDone();
        return true;
    }

    /** Runs the plan against the pack and banks the product for delivery. Returns the chat line. */
    private String performCraft(ServerWorld world, PlayerEntity requester, LumenTask.Craft craft,
                                CraftPlanner planner, Item item, CraftPlanner.Plan plan) {
        int made = planner.execute(plan, getInventory());
        if (made <= 0) {
            return "something didn't add up when i tried to craft " + CraftPlanner.name(item);
        }
        // Move what was asked for out of the pack into the delivery, so it is handed over.
        int wanted = Math.min(made, Math.max(1, craft.count()));
        SimpleInventory pack = getInventory();
        int moved = 0;
        for (int slot = 0; slot < pack.size() && moved < wanted; slot++) {
            ItemStack stack = pack.getStack(slot);
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }
            ItemStack taken = pack.removeStack(slot, Math.min(stack.getCount(), wanted - moved));
            moved += taken.getCount();
            pendingDelivery.add(taken);
        }
        Lumen.LOGGER.info("Lumen crafted {} x {} for {} ({} step(s))", made, CraftPlanner.id(item),
                requester.getName().getString(), plan.steps().size());
        String steps = plan.steps().size() > 1 ? " (" + plan.steps().size() + " steps)" : "";
        return "made " + made + " " + CraftPlanner.name(item) + steps + ", bringing it over";
    }

    /** What is in the pack, as copies, for planning. */
    private List<ItemStack> packContents() {
        List<ItemStack> out = new ArrayList<>();
        SimpleInventory pack = getInventory();
        for (int slot = 0; slot < pack.size(); slot++) {
            ItemStack stack = pack.getStack(slot);
            if (!stack.isEmpty()) {
                out.add(stack.copy());
            }
        }
        return out;
    }

    /** The nearest crafting table (vanilla or a modded workbench) within {@code radius}. */
    @Nullable
    private BlockPos findCraftingTable(ServerWorld world, int radius) {
        BlockPos origin = this.getBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        int height = Math.min(radius, 6);
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (!world.isChunkLoaded((origin.getX() + dx) >> 4, (origin.getZ() + dz) >> 4)) {
                    continue;
                }
                for (int dy = -height; dy <= height; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState state = world.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }
                    String path = Registries.BLOCK.getId(state.getBlock()).getPath();
                    if (!path.contains("crafting_table") && !path.contains("workbench")) {
                        continue;
                    }
                    double distance = cursor.getSquaredDistance(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = cursor.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------- survival

    /**
     * Hunger, a visible health bar, and being a target. A companion that cannot be hurt
     * is a bot; one that can is a party member.
     */
    private void updateSurvival() {
        LumenConfig config = Lumen.config();
        if (config.hungerEnabled) {
            if (++this.foodTicks >= Math.max(20, config.hungerDecaySeconds * 20)) {
                this.foodTicks = 0;
                this.foodLevel = Math.max(0.0F, this.foodLevel - 1.0F);
                if (this.foodLevel == 6.0F || this.foodLevel == 0.0F) {
                    Lumen.broadcast(this.getWorld().getServer(), this.foodLevel == 0.0F
                            ? "i'm starving - any food in my pack would help"
                            : "getting pretty hungry over here");
                }
            }
            boolean slow = this.foodLevel <= 6.0F;
            if (slow != this.hungerSlowed) {
                EntityAttributeInstance speed = this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
                if (speed != null) {
                    speed.removeModifier(HUNGER_SLOW_ID);
                    if (slow) {
                        speed.addTemporaryModifier(new EntityAttributeModifier(HUNGER_SLOW_ID, "lumen hunger",
                                -0.35D, EntityAttributeModifier.Operation.MULTIPLY_TOTAL));
                    }
                }
                this.hungerSlowed = slow;
            }
        } else if (this.foodLevel < 20.0F) {
            this.foodLevel = 20.0F;
        }
        if (this.age % 10 == 0) {
            refreshNameTag(config);
        }
        if (config.hostilesAttackLumen && --this.aggroCooldown <= 0) {
            this.aggroCooldown = 20;
            aggroNearbyHostiles(config);
        }
    }

    /** Hostiles nearby that have nothing better to do come for Lumen, as they would a player. */
    private void aggroNearbyHostiles(LumenConfig config) {
        double radius = config.aggroRadius;
        if (radius <= 0.0D) {
            return;
        }
        Box box = this.getBoundingBox().expand(radius);
        for (HostileEntity hostile : this.getWorld().getEntitiesByClass(HostileEntity.class, box,
                h -> h.isAlive() && h.getTarget() == null && h.canSee(this))) {
            hostile.setTarget(this);
        }
    }

    /** "Lumen ♥14" - health in the name tag, since a vanilla client shows no bar for a mob. */
    private void refreshNameTag(LumenConfig config) {
        String shown = config.companionName;
        if (config.showHealthInName) {
            shown += " \u2665" + Math.round(this.getHealth());
            if (config.hungerEnabled && this.foodLevel <= 6.0F) {
                shown += " hungry";
            }
        }
        if (!shown.equals(this.lastNameShown)) {
            this.lastNameShown = shown;
            this.setCustomName(Text.literal(shown).formatted(Formatting.AQUA));
        }
    }

    public float getFoodLevel() {
        return foodLevel;
    }

    /** "well fed", "hungry", "starving" - for status and the snapshot. */
    public String describeFood() {
        if (!Lumen.config().hungerEnabled) {
            return "not hungry";
        }
        if (foodLevel <= 0.0F) {
            return "starving";
        }
        if (foodLevel <= 6.0F) {
            return "hungry";
        }
        if (foodLevel <= 14.0F) {
            return "peckish";
        }
        return "well fed";
    }

    @Override
    public void onDeath(DamageSource source) {
        super.onDeath(source);
        if (this.getWorld().isClient()) {
            return;
        }
        LumenConfig config = Lumen.config();
        Lumen.memory().noteDeath(System.currentTimeMillis());
        String cause = source.getName();
        String cooldown = config.respawnCooldownMinutes > 0
                ? " - back in " + config.respawnCooldownMinutes + " minutes"
                : "";
        Lumen.broadcast(this.getWorld().getServer(), config.companionName + " is down (" + cause + ")" + cooldown);
        Lumen.LOGGER.info("{} died to {} at {}", config.companionName, cause, this.getBlockPos().toShortString());
    }

    // ------------------------------------------------------------------- combat

    /** Only fight things that threaten Lumen or the player it is looking after. */
    private boolean shouldDefendAgainst(@Nullable LivingEntity candidate) {
        LumenConfig config = Lumen.config();
        if (!config.combat || candidate == null || !candidate.isAlive()) {
            return false;
        }
        if (candidate instanceof PlayerEntity) {
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
     * The single chokepoint for aggro. A player can never become a target, whatever
     * asked for it - a goal, another mod, or a mixin. After v0.3.0 killed a player
     * this is a hard invariant rather than a predicate someone can forget to apply.
     */
    @Override
    public void setTarget(@Nullable LivingEntity target) {
        if (target instanceof PlayerEntity) {
            super.setTarget(null);
            return;
        }
        super.setTarget(target);
    }

    /**
     * Villagers have no attack damage attribute, and Lumen is wearing a villager, so
     * the vanilla path through {@code getAttributeValue(GENERIC_ATTACK_DAMAGE)} would
     * throw. Damage comes from config plus whatever is in Lumen's hand instead.
     */
    @Override
    public boolean tryAttack(Entity target) {
        // Belt and braces: even if something hands us a player, we do not swing at one.
        if (target instanceof PlayerEntity || !Lumen.config().combat) {
            return false;
        }
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
     * re-path, and warp as a last resort. Standing with no path at all counts double,
     * so "there is no route" is answered in a few seconds, not eight.
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
        this.stuckTicks += this.getNavigation().isIdle() ? 2 : 1;
        if (this.stuckTicks == config.stuckRepathTicks || this.stuckTicks == config.stuckRepathTicks + 1) {
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
                return fetchChest != null && !this.getBlockPos().isWithinDistance(fetchChest, 3.0D)
                        ? fetchChest : null;
            }
            case MINE -> {
                return mineTarget != null && !this.getBlockPos().isWithinDistance(mineTarget, 4.0D)
                        ? mineTarget : null;
            }
            default -> {
                return null;
            }
        }
    }

    /** Puts Lumen on a free block near {@code base}. No-op if nowhere sensible is free. */
    public boolean teleportNear(BlockPos base) {
        for (int attempt = 0; attempt < 24; attempt++) {
            int dx = this.getRandom().nextInt(5) - 2;
            int dy = this.getRandom().nextInt(3) - 1;
            int dz = this.getRandom().nextInt(5) - 2;
            BlockPos candidate = base.add(dx, dy, dz);
            if (canStandAt(candidate)) {
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

    /** Somewhere Lumen could actually stand: room for feet and head, solid floor. */
    public boolean canStandAt(BlockPos pos) {
        World world = this.getWorld();
        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false;
        }
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
                && world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty()
                && !world.getBlockState(pos.down()).getCollisionShape(world, pos.down()).isEmpty();
    }

    /**
     * A spot next to {@code target} that Lumen can stand on.
     *
     * <p>Needed because you cannot path *into* a chest, a furnace or any other solid
     * block: the pathfinder finds no node at that position, returns no path, and the
     * caller concludes the target is unreachable. Walking to a neighbour and reaching
     * from there is what a player does.
     *
     * @return the nearest standable neighbour, or null if the target is walled in
     */
    @Nullable
    public BlockPos findApproach(BlockPos target) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dz == 0 && dy == 0) {
                        continue;
                    }
                    BlockPos candidate = target.add(dx, dy, dz);
                    if (!canStandAt(candidate)) {
                        continue;
                    }
                    double distance = candidate.getSquaredDistance(this.getBlockPos());
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Walks toward a block by aiming at a standable neighbour of it.
     *
     * @return false when the navigator could not produce a path that goes anywhere -
     *         either none at all, or one that stops where Lumen already stands
     */
    public boolean moveToBlock(BlockPos target, double speed) {
        // Only detour to a neighbour when the target itself cannot be stood in - a
        // chest, a furnace. v0.4.0 did it for every destination, including ordinary
        // walkable ones, and the nearest standable neighbour is sometimes on the far
        // side of a wall. That is what stopped Lumen finding its way back indoors.
        BlockPos goal = target;
        if (!canStandAt(target)) {
            BlockPos approach = findApproach(target);
            if (approach != null) {
                goal = approach;
            }
        }
        boolean started = this.getNavigation().startMovingTo(
                goal.getX() + 0.5D, goal.getY(), goal.getZ() + 0.5D, speed);
        return started && !pathGoesNowhere();
    }

    /**
     * True when the current path does not reach its target and ends where Lumen
     * already is - the pathfinder's way of saying "no route", which vanilla reports
     * as success. Following it means standing still and looking busy.
     */
    public boolean pathGoesNowhere() {
        Path path = this.getNavigation().getCurrentPath();
        if (path == null) {
            return true;
        }
        if (path.reachesTarget()) {
            return false;
        }
        PathNode end = path.getEnd();
        return end != null && this.getBlockPos().isWithinDistance(new BlockPos(end.x, end.y, end.z), 1.5D);
    }

    /** Where Lumen is currently trying to get to, for /lumen why. Null when idle. */
    @Nullable
    public BlockPos currentTarget() {
        switch (getMode()) {
            case FOLLOW -> {
                PlayerEntity target = getFollowTarget();
                return target == null ? null : target.getBlockPos();
            }
            case GO_TO -> {
                return destination;
            }
            case FETCH -> {
                return fetchChest;
            }
            case MINE -> {
                return mineTarget;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * How much of a block Lumen breaks per tick, by the vanilla formula.
     *
     * <p>{@code BlockState#calcBlockBreakingDelta} takes a PlayerEntity, so it cannot
     * be called directly - this is the same calculation. Because it reads hardness and
     * tool suitability from the block and item themselves, modded tools and modded
     * blocks are handled without knowing anything about them.
     *
     * @return progress per tick, or 0 when the block cannot be broken at all
     */
    public float blockBreakingDelta(BlockState state, BlockPos pos) {
        float hardness = state.getHardness(this.getWorld(), pos);
        if (hardness < 0.0F) {
            return 0.0F;
        }
        ItemStack tool = this.getMainHandStack();
        float speed = tool.getMiningSpeedMultiplier(state);
        if (speed > 1.0F) {
            int efficiency = EnchantmentHelper.getLevel(Enchantments.EFFICIENCY, tool);
            if (efficiency > 0) {
                speed += efficiency * efficiency + 1;
            }
        }
        if (this.hasStatusEffect(StatusEffects.HASTE)) {
            speed *= 1.0F + (this.getStatusEffect(StatusEffects.HASTE).getAmplifier() + 1) * 0.2F;
        }
        if (this.isSubmergedInWater()) {
            speed /= 5.0F;
        }
        if (!this.isOnGround()) {
            speed /= 5.0F;
        }
        boolean harvestable = !state.isToolRequired() || tool.isSuitableFor(state);
        return speed / hardness / (harvestable ? 30.0F : 100.0F);
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
            arrived();
        } else if (mode == Mode.FOLLOW && currentTask instanceof LumenTask.Return) {
            PlayerEntity target = getFollowTarget();
            double reach = Lumen.config().followStartDistance;
            if (target != null && this.squaredDistanceTo(target) <= reach * reach) {
                taskDone();
            }
        }
        // Let go the instant the fight is over, rather than carrying aggro onward.
        LivingEntity currentTarget = this.getTarget();
        if (currentTarget != null && (!currentTarget.isAlive() || currentTarget instanceof PlayerEntity)) {
            this.setTarget(null);
        }
        if (currentTask instanceof LumenTask.Collect collect) {
            // Stay until the drops are picked up, or long enough that they are not coming.
            boolean anyLeft = !this.getWorld().getEntitiesByClass(ItemEntity.class,
                    new Box(collect.center()).expand(collect.radius()), this::wantsToPickUp).isEmpty();
            if (!anyLeft || ++this.collectTicks > 20 * 15) {
                taskDone();
            }
        }
        updateStuckState();
        updateSurvival();
        handleFenceGates();
        handlePassages();
        collectNearbyItems();
        deliverIfClose();
        eatIfHurt();
        // Picks up gear a player just dropped into the screen, without needing a hook
        // on the screen closing.
        if (--this.equipCooldown <= 0) {
            this.equipCooldown = 40;
            equipBetterGear();
        }
    }

    /** Reached a go-to destination: note the visit if it was a named place, then move on. */
    private void arrived() {
        if (craftAfterArrival != null && this.getWorld() instanceof ServerWorld world && world.getServer() != null) {
            LumenTask.Craft craft = this.craftAfterArrival;
            this.craftAfterArrival = null;
            PlayerEntity requester = craft.requester() == null ? null : world.getPlayerByUuid(craft.requester());
            CraftPlanner planner = CraftPlanner.forServer(world.getServer());
            Item item = planner.findCraftable(craft.query());
            CraftPlanner.Plan plan = item == null ? null : planner.plan(item, Math.max(1, craft.count()), packContents());
            if (requester == null || item == null || plan == null || plan.isEmpty()) {
                Lumen.broadcast(world.getServer(), "got to the table but " + (plan == null || plan.isEmpty()
                        ? "i can't make " + craft.query() + " any more" : "whoever asked has gone"));
            } else {
                Lumen.broadcast(world.getServer(), performCraft(world, requester, craft, planner, item, plan));
            }
            this.mode = Mode.IDLE;
            taskDone();
            return;
        }
        if (currentTask instanceof LumenTask.GoTo go && go.placeName() != null
                && this.getWorld() instanceof ServerWorld world) {
            LumenMemory.KnownPlace place = Lumen.memory().findPlace(go.placeName(), world.getRegistryKey().getValue());
            if (place != null) {
                Lumen.memory().notePlaceVisit(place);
            }
        }
        if (currentTask != null) {
            taskDone();
        } else {
            stopAndIdle();
        }
    }

    /** Called by the go-to goal when there is no route and no warp either. */
    public void goToFailed() {
        if (currentTask instanceof LumenTask.GoTo go) {
            Lumen.broadcast(this.getWorld().getServer(), "i can't find a way to " + go.describe().replaceFirst("^go to ", ""));
            taskDone();
        } else {
            stopAndIdle();
        }
    }

    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (this.getWorld().isClient() || hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }
        LumenConfig config = Lumen.config();
        ItemStack offered = player.getStackInHand(hand);

        // Empty handed, or sneaking so you can open it while holding something.
        if (offered.isEmpty() || player.isSneaking()) {
            openPack(player);
            return ActionResult.SUCCESS;
        }

        if (config.acceptItemsFromPlayers) {
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

        openPack(player);
        return ActionResult.SUCCESS;
    }

    /**
     * Puts everything Lumen is carrying on the ground: pack, worn gear and anything
     * fetched but not yet handed over. Used by despawn, which previously deleted the
     * lot. Players get things back through {@link #handOver} instead - this is the
     * path for when there is no player to hand them to.
     *
     * @return how many stacks were dropped
     */
    public int dropEverything() {
        int dropped = 0;
        SimpleInventory pack = getInventory();
        for (int slot = 0; slot < pack.size(); slot++) {
            ItemStack stack = pack.getStack(slot);
            if (!stack.isEmpty()) {
                dropAndIgnore(stack);
                dropped++;
            }
        }
        pack.clear();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack equipped = this.getEquippedStack(slot);
            if (!equipped.isEmpty()) {
                dropAndIgnore(equipped);
                this.equipStack(slot, ItemStack.EMPTY);
                dropped++;
            }
        }
        for (ItemStack stack : pendingDelivery) {
            if (!stack.isEmpty()) {
                dropAndIgnore(stack);
                dropped++;
            }
        }
        pendingDelivery.clear();
        return dropped;
    }

    private void dropAndIgnore(ItemStack stack) {
        ItemEntity item = this.dropStack(stack);
        if (item != null) {
            ignoreDrop(item);
        }
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
