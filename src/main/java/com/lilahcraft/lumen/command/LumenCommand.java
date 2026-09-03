package com.lilahcraft.lumen.command;

import com.lilahcraft.lumen.Lumen;
import com.lilahcraft.lumen.LumenConfig;
import com.lilahcraft.lumen.brain.LumenBrain;
import com.lilahcraft.lumen.entity.ChestFinder;
import com.lilahcraft.lumen.entity.LumenEntity;
import com.lilahcraft.lumen.entity.LumenTask;
import com.lilahcraft.lumen.brain.Phrasing;
import com.lilahcraft.lumen.memory.LumenMemory;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Everything under {@code /lumen}. */
public final class LumenCommand {

    private LumenCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("lumen")
                .executes(LumenCommand::status)
                .then(CommandManager.literal("spawn")
                        .requires(LumenCommand::isAdmin)
                        .executes(LumenCommand::spawn))
                .then(CommandManager.literal("despawn")
                        .requires(LumenCommand::isAdmin)
                        .executes(LumenCommand::despawn))
                .then(CommandManager.literal("reload")
                        .requires(LumenCommand::isAdmin)
                        .executes(LumenCommand::reload))
                .then(CommandManager.literal("status")
                        .executes(LumenCommand::status))
                .then(CommandManager.literal("say")
                        .then(CommandManager.argument("message", StringArgumentType.greedyString())
                                .executes(LumenCommand::say)))
                .then(CommandManager.literal("come")
                        .executes(LumenCommand::come))
                .then(CommandManager.literal("stay")
                        .executes(LumenCommand::stay))
                .then(CommandManager.literal("here")
                        .executes(LumenCommand::here))
                .then(CommandManager.literal("inventory")
                        .executes(LumenCommand::inventory))
                .then(CommandManager.literal("drop")
                        .executes(LumenCommand::drop))
                .then(CommandManager.literal("give")
                        .executes(LumenCommand::drop)
                        .then(CommandManager.argument("item", StringArgumentType.greedyString())
                                .executes(LumenCommand::give)))
                .then(CommandManager.literal("why")
                        .executes(LumenCommand::why))
                .then(CommandManager.literal("debug")
                        .executes(LumenCommand::debug))
                .then(CommandManager.literal("containers")
                        .executes(LumenCommand::containers))
                .then(CommandManager.literal("memory")
                        .executes(LumenCommand::memory))
                .then(CommandManager.literal("forget")
                        // Bare "forget" wipes everything and is checked for admin inside;
                        // a child node cannot be more permissive than a gated parent.
                        .executes(LumenCommand::forget)
                        .then(CommandManager.argument("place", StringArgumentType.greedyString())
                                .executes(LumenCommand::forgetPlace)))
                .then(CommandManager.literal("remember")
                        .then(CommandManager.argument("name", StringArgumentType.greedyString())
                                .executes(LumenCommand::remember)))
                .then(CommandManager.literal("go")
                        .then(CommandManager.argument("place", StringArgumentType.greedyString())
                                .executes(LumenCommand::go)))
                .then(CommandManager.literal("queue")
                        .executes(LumenCommand::queue))
                .then(CommandManager.literal("cancel")
                        .executes(LumenCommand::cancel))
                .then(CommandManager.literal("continue")
                        .executes(LumenCommand::resume))
                .then(CommandManager.literal("find")
                        .then(CommandManager.argument("item", StringArgumentType.greedyString())
                                .executes(LumenCommand::find)))
                .then(CommandManager.literal("mine")
                        .then(CommandManager.argument("block", StringArgumentType.greedyString())
                                .executes(LumenCommand::mine)))
                .then(CommandManager.literal("follow")
                        .executes(context -> follow(context, context.getSource().getPlayerOrThrow()))
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(context -> follow(context,
                                        EntityArgumentType.getPlayer(context, "player"))))));
    }

    private static boolean isAdmin(ServerCommandSource source) {
        return source.hasPermissionLevel(Lumen.config().adminPermissionLevel);
    }

    private static int spawn(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        LumenConfig config = Lumen.config();

        LumenEntity lumen = Lumen.manager().spawn(player.getServerWorld(), player.getPos(), player.getYaw(), config);
        if (lumen == null) {
            source.sendError(Text.literal("Could not spawn " + config.companionName + " here."));
            return 0;
        }
        lumen.followPlayer(player);
        source.sendFeedback(() -> Text.literal(config.companionName + " is here.").formatted(Formatting.AQUA), true);
        return 1;
    }

    private static int despawn(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        LumenConfig config = Lumen.config();
        if (!Lumen.manager().despawn(source.getServer())) {
            source.sendError(Text.literal(config.companionName + " is not spawned."));
            return 0;
        }
        source.sendFeedback(() -> Text.literal(config.companionName + " is gone.").formatted(Formatting.GRAY), true);
        return 1;
    }

    private static int reload(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        LumenConfig config = Lumen.reloadConfig();
        LumenEntity lumen = Lumen.manager().get(source.getServer());
        if (lumen != null) {
            lumen.applyConfig(config);
        }
        source.sendFeedback(() -> Text.literal("Reloaded " + LumenConfig.path().getFileName() + ".")
                .formatted(Formatting.GRAY), true);
        return 1;
    }

    private static int status(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        LumenConfig config = Lumen.config();
        LumenEntity lumen = Lumen.manager().get(source.getServer());

        source.sendFeedback(() -> Text.literal(config.companionName).formatted(Formatting.AQUA)
                .append(Text.literal(" - " + config.model + " @ " + config.ollamaUrl).formatted(Formatting.GRAY)), false);
        if (lumen == null) {
            source.sendFeedback(() -> Text.literal("Not spawned. Use /lumen spawn.").formatted(Formatting.GRAY), false);
        } else {
            source.sendFeedback(() -> Text.literal("Currently " + lumen.describeActivity()
                            + " at " + lumen.getBlockPos().toShortString()
                            + " (" + Math.round(lumen.getHealth()) + "/" + Math.round(lumen.getMaxHealth())
                            + " hp, " + lumen.countCarriedStacks() + " stacks carried)")
                    .formatted(Formatting.GRAY), false);
        }
        source.sendFeedback(() -> Text.literal("LLM " + (config.enabled ? "enabled" : "disabled")
                + ", trigger: " + config.chatTrigger
                + (Lumen.brain().isBusy() ? ", thinking..." : "")).formatted(Formatting.DARK_GRAY), false);
        return 1;
    }

    private static int say(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        String message = StringArgumentType.getString(context, "message");
        Lumen.brain().onPlayerMessage(source.getServer(), player, message, true);
        return 1;
    }

    private static int come(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        LumenEntity lumen = requireLumen(source);
        if (lumen == null) {
            return 0;
        }
        boolean paused = lumen.currentTask() != null;
        lumen.pauseForPlayer();
        lumen.goTo(player.getBlockPos());
        if (paused) {
            source.sendFeedback(() -> Text.literal(Lumen.config().companionName
                    + " paused its errand. /lumen continue resumes it.").formatted(Formatting.GRAY), false);
        }
        return 1;
    }

    private static int stay(CommandContext<ServerCommandSource> context) {
        LumenEntity lumen = requireLumen(context.getSource());
        if (lumen == null) {
            return 0;
        }
        int dropped = lumen.cancelAll();
        if (dropped > 0) {
            context.getSource().sendFeedback(() -> Text.literal("Dropped " + dropped + " task(s).")
                    .formatted(Formatting.GRAY), false);
        }
        return 1;
    }

    private static int queue(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        LumenEntity lumen = requireLumen(source);
        if (lumen == null) {
            return 0;
        }
        List<String> lines = lumen.describeQueue();
        if (lines.isEmpty()) {
            source.sendFeedback(() -> Text.literal(Lumen.config().companionName + " has nothing lined up.")
                    .formatted(Formatting.GRAY), false);
            return 1;
        }
        source.sendFeedback(() -> Text.literal(Lumen.config().companionName + "'s list:").formatted(Formatting.AQUA), false);
        for (String line : lines) {
            source.sendFeedback(() -> Text.literal("  " + line).formatted(Formatting.GRAY), false);
        }
        if (lumen.currentTask() == null && lumen.queuedCount() > 0) {
            source.sendFeedback(() -> Text.literal("  (paused - /lumen continue to carry on)")
                    .formatted(Formatting.DARK_GRAY), false);
        }
        return 1;
    }

    private static int cancel(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        LumenEntity lumen = requireLumen(source);
        if (lumen == null) {
            return 0;
        }
        int dropped = lumen.cancelAll();
        source.sendFeedback(() -> Text.literal(dropped == 0 ? "Nothing to cancel." : "Cancelled " + dropped + " task(s).")
                .formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int resume(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        LumenEntity lumen = requireLumen(source);
        if (lumen == null) {
            return 0;
        }
        if (!lumen.resume()) {
            source.sendFeedback(() -> Text.literal(lumen.isOnErrand() ? "Already busy." : "Nothing was waiting.")
                    .formatted(Formatting.GRAY), false);
            return 0;
        }
        LumenTask task = lumen.currentTask();
        source.sendFeedback(() -> Text.literal(Lumen.config().companionName + " resumes: "
                + (task == null ? "?" : task.describe())).formatted(Formatting.AQUA), false);
        return 1;
    }

    private static int remember(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        String name = LumenMemory.cleanPlaceName(Phrasing.placeNameFromRemember(
                StringArgumentType.getString(context, "name")));
        if (name.isEmpty()) {
            source.sendError(Text.literal("Give the place a name: /lumen remember hops room"));
            return 0;
        }
        LumenMemory.KnownPlace place = Lumen.memory().rememberPlace(name, player.getWorld().getRegistryKey().getValue(),
                player.getBlockPos(), LumenMemory.DEFAULT_PLACE_RADIUS, player.getName().getString());
        source.sendFeedback(() -> Text.literal("Remembered \"" + place.name + "\" at " + place.pos().toShortString()
                + " (radius " + place.radius + ").").formatted(Formatting.AQUA), true);
        return 1;
    }

    private static int go(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        LumenEntity lumen = requireLumen(source);
        if (lumen == null) {
            return 0;
        }
        String query = StringArgumentType.getString(context, "place");
        LumenMemory.KnownPlace place = Lumen.memory().findPlace(query, player.getWorld().getRegistryKey().getValue());
        if (place == null) {
            source.sendError(Text.literal("No place called \"" + query + "\". Stand there and /lumen remember <name>."));
            return 0;
        }
        LumenEntity.Submission result = lumen.submit(new LumenTask.GoTo(player.getUuid(), place.pos(), place.name));
        return reportSubmission(source, result, lumen, "goes to the " + place.name);
    }

    private static int forgetPlace(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String query = StringArgumentType.getString(context, "place");
        if (!Lumen.memory().forgetPlace(query)) {
            source.sendError(Text.literal("No place called \"" + query + "\"."));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("Forgot the place \"" + query + "\".").formatted(Formatting.GRAY), true);
        return 1;
    }

    /** Feedback for a task that was submitted from a slash command. */
    private static int reportSubmission(ServerCommandSource source, LumenEntity.Submission result, LumenEntity lumen,
                                        String started) {
        String name = Lumen.config().companionName;
        switch (result) {
            case STARTED -> {
                source.sendFeedback(() -> Text.literal(name + " " + started + ".").formatted(Formatting.AQUA), false);
                return 1;
            }
            case QUEUED -> {
                source.sendFeedback(() -> Text.literal(name + " will do that after what it is doing now ("
                        + lumen.queuedCount() + " waiting).").formatted(Formatting.AQUA), false);
                return 1;
            }
            default -> {
                source.sendError(Text.literal(name + ": " + lumen.lastTaskNote()));
                return 0;
            }
        }
    }

    /** Escape hatch for the times pathfinding loses: warp Lumen to the player. */
    private static int here(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        LumenEntity lumen = requireLumen(source);
        if (lumen == null) {
            return 0;
        }
        if (!lumen.teleportNear(player.getBlockPos())) {
            source.sendError(Text.literal("No room around you for "
                    + Lumen.config().companionName + " to stand."));
            return 0;
        }
        lumen.followPlayer(player);
        source.sendFeedback(() -> Text.literal(Lumen.config().companionName + " is right here.")
                .formatted(Formatting.AQUA), false);
        return 1;
    }

    private static int inventory(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        LumenEntity lumen = requireLumen(source);
        if (lumen == null) {
            return 0;
        }
        SimpleInventory carried = lumen.getInventory();
        List<String> lines = new ArrayList<>();
        for (int slot = 0; slot < carried.size(); slot++) {
            ItemStack stack = carried.getStack(slot);
            if (!stack.isEmpty()) {
                lines.add(stack.getCount() + "x " + stack.getName().getString());
            }
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack equipped = lumen.getEquippedStack(slot);
            if (!equipped.isEmpty()) {
                lines.add(equipped.getName().getString() + " (" + slot.getName() + ")");
            }
        }
        String summary = lines.isEmpty() ? "nothing at all" : String.join(", ", lines);
        source.sendFeedback(() -> Text.literal(Lumen.config().companionName + " is carrying " + summary)
                .formatted(Formatting.GRAY), false);
        return 1;
    }

    /**
     * Shows what the model last said and what became of it. "The command did not
     * execute" and "the model never sent one" look identical from chat; this tells
     * them apart.
     */
    private static int debug(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        LumenBrain.CommandTrace trace = Lumen.brain().lastTrace();
        String raw = Lumen.brain().lastRawContent();

        source.sendFeedback(() -> Text.literal("Last model reply: "
                + (raw == null ? "(none yet)" : abbreviate(raw))).formatted(Formatting.DARK_GRAY), false);
        if (trace == null) {
            source.sendFeedback(() -> Text.literal("No command has been routed yet.")
                    .formatted(Formatting.GRAY), false);
        } else {
            source.sendFeedback(() -> Text.literal("command field: \"" + trace.raw() + "\"")
                    .formatted(Formatting.GRAY), false);
            source.sendFeedback(() -> Text.literal("understood as: \"" + trace.parsed() + "\"")
                    .formatted(Formatting.GRAY), false);
            source.sendFeedback(() -> Text.literal("result: " + trace.outcome())
                    .formatted(Formatting.AQUA), false);
        }
        source.sendFeedback(() -> Text.literal(Lumen.brain().isBusy()
                ? "A request is in flight right now." : "Idle, no request in flight.")
                .formatted(Formatting.DARK_GRAY), false);
        return 1;
    }

    /**
     * Lists nearby containers and whether Lumen can search them. Modded storage that
     * does not implement Inventory shows up here as unsearchable, which is the
     * information needed to decide what to support next.
     */
    private static int containers(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        LumenEntity lumen = requireLumen(source);
        if (lumen == null) {
            return 0;
        }
        if (!(lumen.getWorld() instanceof ServerWorld world)) {
            return 0;
        }
        double radius = Lumen.config().chestSearchRadius;
        List<String> searchable = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        ChestFinder.describeNearby(world, lumen.getBlockPos(), radius, searchable, skipped);

        source.sendFeedback(() -> Text.literal("Within " + Math.round(radius) + " blocks: "
                + searchable.size() + " searchable, " + skipped.size() + " not")
                .formatted(Formatting.AQUA), false);
        if (!searchable.isEmpty()) {
            source.sendFeedback(() -> Text.literal("  searchable: "
                    + String.join(", ", searchable.stream().distinct().limit(8).toList()))
                    .formatted(Formatting.GRAY), false);
        }
        if (!skipped.isEmpty()) {
            source.sendFeedback(() -> Text.literal("  NOT searchable: "
                    + String.join(", ", skipped.stream().distinct().limit(8).toList()))
                    .formatted(Formatting.YELLOW), false);
        }
        return 1;
    }

    private static String abbreviate(String value) {
        String flat = value.replace('\n', ' ').trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 157) + "...";
    }

    /**
     * Hands everything back: pack, worn gear and anything not yet delivered. Straight
     * into the player's inventory - on the ground it was Lumen's again within a tick.
     */
    private static int drop(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        return handOver(context, null);
    }

    /** Hands one kind of thing back: {@code /lumen give sword}. */
    private static int give(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        return handOver(context, StringArgumentType.getString(context, "item"));
    }

    private static int handOver(CommandContext<ServerCommandSource> context, String item)
            throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        LumenEntity lumen = requireLumen(source);
        if (lumen == null) {
            return 0;
        }
        String name = Lumen.config().companionName;
        boolean everything = ChestFinder.meansEverything(item);
        if (!everything && !lumen.isCarrying(item)) {
            source.sendError(Text.literal(name + " has no " + item.trim() + "."));
            return 0;
        }
        LumenEntity.HandoverResult result = lumen.requestHandover(player, everything ? null : item);
        if (result == null) {
            source.sendFeedback(() -> Text.literal(name + " is coming over with "
                    + (everything ? "everything." : "the " + item.trim() + ".")).formatted(Formatting.AQUA), false);
            return 1;
        }
        if (result.stacks() == 0) {
            source.sendFeedback(() -> Text.literal(name + " has nothing to give back.")
                    .formatted(Formatting.AQUA), false);
            return 1;
        }
        source.sendFeedback(() -> Text.literal(name + " hands over " + result.stacks() + " stack(s)"
                + (result.what().isEmpty() ? "" : " - " + result.what())
                + (result.droppedSome() ? ". Your inventory is full, so some of it is on the ground." : "."))
                .formatted(Formatting.AQUA), false);
        return 1;
    }

    private static int memory(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        List<String> places = Lumen.memory().placeLines(15);
        List<String> lines = Lumen.memory().lines(15);
        if (lines.isEmpty() && places.isEmpty()) {
            source.sendFeedback(() -> Text.literal(Lumen.config().companionName
                    + " has not found anything worth remembering yet.").formatted(Formatting.GRAY), false);
            return 1;
        }
        if (!places.isEmpty()) {
            source.sendFeedback(() -> Text.literal(Lumen.config().companionName + " knows "
                    + Lumen.memory().placeCount() + " named place(s):").formatted(Formatting.AQUA), false);
            for (String line : places) {
                source.sendFeedback(() -> Text.literal("  " + line).formatted(Formatting.GRAY), false);
            }
        }
        if (!lines.isEmpty()) {
            source.sendFeedback(() -> Text.literal(Lumen.config().companionName + " remembers "
                    + Lumen.memory().size() + " container(s):").formatted(Formatting.AQUA), false);
            for (String line : lines) {
                source.sendFeedback(() -> Text.literal("  " + line).formatted(Formatting.GRAY), false);
            }
        }
        return 1;
    }

    private static int forget(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        if (!isAdmin(source)) {
            source.sendError(Text.literal("Only an operator can clear everything. /lumen forget <place> forgets one place."));
            return 0;
        }
        int had = Lumen.memory().size() + Lumen.memory().placeCount();
        Lumen.memory().clear();
        source.sendFeedback(() -> Text.literal("Cleared " + had + " remembered container(s) and place(s).")
                .formatted(Formatting.GRAY), true);
        return 1;
    }

    private static int find(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        LumenEntity lumen = requireLumen(source);
        if (lumen == null) {
            return 0;
        }
        String query = StringArgumentType.getString(context, "item");
        if (!Lumen.config().allowChestAccess) {
            source.sendError(Text.literal("Chest access is turned off in the config."));
            return 0;
        }
        Phrasing.PlaceRef ref = Phrasing.splitPlaceReference(query);
        LumenMemory.KnownPlace place = ref.place() == null ? null
                : Lumen.memory().findPlace(ref.place(), player.getWorld().getRegistryKey().getValue());
        String itemText = place == null ? query : ref.rest();
        ChestFinder.Request request = ChestFinder.parseRequest(itemText, Lumen.config().defaultFetchCount);
        LumenEntity.Submission result = lumen.submit(new LumenTask.Fetch(player.getUuid(), request,
                place == null ? null : place.pos(), place == null ? null : place.name));
        return reportSubmission(source, result, lumen, "goes looking for " + ChestFinder.describeRequest(request)
                + (place == null ? "" : " around the " + place.name));
    }

    private static int mine(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        LumenEntity lumen = requireLumen(source);
        if (lumen == null) {
            return 0;
        }
        if (!Lumen.config().allowMining) {
            source.sendError(Text.literal("Mining is turned off in the config."));
            return 0;
        }
        String query = StringArgumentType.getString(context, "block");
        Phrasing.PlaceRef ref = Phrasing.splitPlaceReference(query);
        LumenMemory.KnownPlace place = ref.place() == null ? null
                : Lumen.memory().findPlace(ref.place(), player.getWorld().getRegistryKey().getValue());
        String blockText = place == null ? query : ref.rest();
        LumenEntity.Submission result = lumen.submit(new LumenTask.Mine(player.getUuid(), blockText,
                place == null ? null : place.pos(), place == null ? null : place.name));
        return reportSubmission(source, result, lumen, "goes off to mine " + blockText
                + (place == null ? "" : " near the " + place.name));
    }

    /**
     * Explains why Lumen is not going anywhere.
     *
     * <p>Runs a real path test to wherever Lumen is trying to get to and reports where
     * the path actually ends, then names the blocks around Lumen's feet by the verdict
     * vanilla pathfinding gives each one. Slabs, stairs and carpets are called out as
     * half blocks it steps over rather than lumped in with walls - v0.5 listed the
     * slab floor Lumen was standing on as "solid", which read as the culprit.
     */
    private static int why(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        LumenEntity lumen = requireLumen(source);
        if (lumen == null) {
            return 0;
        }
        World world = lumen.getWorld();
        BlockPos origin = lumen.getBlockPos();
        // Vanilla plans from floor(y + 0.5): standing on a bottom slab at 97.5 means
        // the node is 98, the air above the slab, not the slab block itself.
        int nodeY = MathHelper.floor(lumen.getY() + 0.5D);
        boolean idle = lumen.getNavigation().isIdle();

        source.sendFeedback(() -> Text.literal(Lumen.config().companionName + " is "
                + lumen.describeActivity() + "; navigation is "
                + (idle ? "idle (no path)" : "walking a path")
                + " at " + origin.toShortString() + " (y=" + String.format("%.2f", lumen.getY())
                + ", " + (lumen.isOnGround() ? "on the ground" : "not on the ground") + ")")
                .formatted(Formatting.GRAY), false);

        // The path test: the honest answer to "can it get there".
        BlockPos target = lumen.currentTarget();
        String targetLabel = "its target";
        if (target == null && source.getEntity() instanceof ServerPlayerEntity player
                && player.getWorld() == world) {
            target = player.getBlockPos();
            targetLabel = "you";
        }
        if (target != null) {
            BlockPos goal = lumen.canStandAt(target) ? target : lumen.findApproach(target);
            BlockPos finalGoal = goal == null ? target : goal;
            String label = targetLabel;
            if (!lumen.isOnGround()) {
                source.sendFeedback(() -> Text.literal("Path test skipped: it is not on the ground, and vanilla "
                        + "will not plan a path mid-air.").formatted(Formatting.YELLOW), false);
            } else {
                Path path = lumen.getNavigation().findPathTo(finalGoal, 1);
                if (path == null) {
                    source.sendFeedback(() -> Text.literal("Path test to " + label + " at "
                            + finalGoal.toShortString() + ": NO PATH at all.").formatted(Formatting.YELLOW), false);
                } else {
                    PathNode end = path.getEnd();
                    String endText = end == null ? "nowhere" : end.x + ", " + end.y + ", " + end.z;
                    double short_ = end == null ? -1 : Math.sqrt(finalGoal.getSquaredDistance(
                            new BlockPos(end.x, end.y, end.z)));
                    source.sendFeedback(() -> Text.literal("Path test to " + label + " at "
                            + finalGoal.toShortString() + ": " + path.getLength() + " node(s), "
                            + (path.reachesTarget() ? "reaches it" : "does NOT reach it")
                            + ", ends at " + endText
                            + (path.reachesTarget() || short_ < 0 ? "" : " (" + Math.round(short_) + " blocks short)"))
                            .formatted(path.reachesTarget() ? Formatting.GREEN : Formatting.YELLOW), false);
                    if (!path.reachesTarget() && end != null) {
                        describeSurroundings(source, world, new BlockPos(end.x, end.y, end.z),
                                "Where the path gives up");
                    }
                }
            }
        }
        describeSurroundings(source, world, new BlockPos(origin.getX(), nodeY, origin.getZ()), "Around its feet");
        return 1;
    }

    /** Names the blocks around {@code center} by what the pathfinder makes of them. */
    private static void describeSurroundings(ServerCommandSource source, World world, BlockPos center,
                                             String heading) {
        Set<String> blockers = new LinkedHashSet<>();
        Set<String> steps = new LinkedHashSet<>();
        Set<String> passable = new LinkedHashSet<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    String id = Registries.BLOCK.getId(state.getBlock()).toString();
                    PathNodeType type = LandPathNodeMaker.getLandNodeType(world, new BlockPos.Mutable(
                            pos.getX(), pos.getY(), pos.getZ()));
                    VoxelShape shape = state.getCollisionShape(world, pos);
                    switch (type) {
                        case BLOCKED -> {
                            if (shape.isEmpty()) {
                                passable.add(id + " (no collision - Lumen passes it anyway)");
                            } else if (shape.getMax(Direction.Axis.Y) <= 0.6D) {
                                steps.add(id + " (half block - steps onto it)");
                            } else {
                                blockers.add(id + " (solid)");
                            }
                        }
                        case FENCE -> {
                            if (state.getBlock() instanceof net.minecraft.block.FenceGateBlock) {
                                steps.add(id + " (gate - Lumen opens it)");
                            } else {
                                blockers.add(id + " (fence or wall - cannot cross)");
                            }
                        }
                        case DOOR_IRON_CLOSED -> blockers.add(id + " (iron door - cannot open)");
                        case DOOR_WOOD_CLOSED, DOOR_OPEN, WALKABLE_DOOR -> steps.add(id + " (door - opens it)");
                        default -> {
                            String name = type.name().toLowerCase(java.util.Locale.ROOT);
                            if (type == PathNodeType.LAVA || name.startsWith("damage")) {
                                blockers.add(id + " (avoids: " + name + ")");
                            } else {
                                passable.add(id + " (" + name + ")");
                            }
                        }
                    }
                }
            }
        }
        if (blockers.isEmpty() && steps.isEmpty()) {
            source.sendFeedback(() -> Text.literal(heading + " (" + center.toShortString()
                    + "): nothing is blocking movement.").formatted(Formatting.DARK_GRAY), false);
            return;
        }
        source.sendFeedback(() -> Text.literal(heading + " (" + center.toShortString() + "):")
                .formatted(Formatting.DARK_GRAY), false);
        if (!blockers.isEmpty()) {
            source.sendFeedback(() -> Text.literal("  impassable (walls are normal; containers are approached "
                    + "from the side): " + blockers.stream().limit(10).reduce((a, b) -> a + ", " + b).orElse(""))
                    .formatted(Formatting.DARK_GRAY), false);
        }
        if (!steps.isEmpty()) {
            source.sendFeedback(() -> Text.literal("  walkable: "
                    + steps.stream().limit(10).reduce((a, b) -> a + ", " + b).orElse(""))
                    .formatted(Formatting.DARK_GRAY), false);
        }
    }

    private static int follow(CommandContext<ServerCommandSource> context, ServerPlayerEntity target) {
        LumenEntity lumen = requireLumen(context.getSource());
        if (lumen == null) {
            return 0;
        }
        lumen.pauseForPlayer();
        lumen.followPlayer(target);
        return 1;
    }

    private static LumenEntity requireLumen(ServerCommandSource source) {
        LumenEntity lumen = Lumen.manager().get(source.getServer());
        if (lumen == null) {
            source.sendError(Text.literal(Lumen.config().companionName + " is not spawned. Use /lumen spawn."));
        }
        return lumen;
    }
}
