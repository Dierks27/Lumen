package com.lilahcraft.lumen.command;

import com.lilahcraft.lumen.Lumen;
import com.lilahcraft.lumen.LumenConfig;
import com.lilahcraft.lumen.brain.LumenBrain;
import com.lilahcraft.lumen.entity.ChestFinder;
import com.lilahcraft.lumen.entity.LumenEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.ai.pathing.NavigationType;
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
                .then(CommandManager.literal("why")
                        .executes(LumenCommand::why))
                .then(CommandManager.literal("debug")
                        .executes(LumenCommand::debug))
                .then(CommandManager.literal("containers")
                        .executes(LumenCommand::containers))
                .then(CommandManager.literal("memory")
                        .executes(LumenCommand::memory))
                .then(CommandManager.literal("forget")
                        .requires(LumenCommand::isAdmin)
                        .executes(LumenCommand::forget))
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
        lumen.goTo(player.getBlockPos());
        return 1;
    }

    private static int stay(CommandContext<ServerCommandSource> context) {
        LumenEntity lumen = requireLumen(context.getSource());
        if (lumen == null) {
            return 0;
        }
        lumen.stopAndIdle();
        return 1;
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

    /** Hands everything back: pack, worn gear and anything not yet delivered. */
    private static int drop(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        LumenEntity lumen = requireLumen(source);
        if (lumen == null) {
            return 0;
        }
        int dropped = lumen.dropEverything();
        source.sendFeedback(() -> Text.literal(Lumen.config().companionName
                + (dropped == 0 ? " has nothing to give back." : " drops " + dropped + " stack(s)."))
                .formatted(Formatting.AQUA), false);
        return 1;
    }

    private static int memory(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        List<String> lines = Lumen.memory().lines(15);
        if (lines.isEmpty()) {
            source.sendFeedback(() -> Text.literal(Lumen.config().companionName
                    + " has not found anything worth remembering yet.").formatted(Formatting.GRAY), false);
            return 1;
        }
        source.sendFeedback(() -> Text.literal(Lumen.config().companionName + " remembers "
                + Lumen.memory().size() + " place(s):").formatted(Formatting.AQUA), false);
        for (String line : lines) {
            source.sendFeedback(() -> Text.literal("  " + line).formatted(Formatting.GRAY), false);
        }
        return 1;
    }

    private static int forget(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        int had = Lumen.memory().size();
        Lumen.memory().clear();
        source.sendFeedback(() -> Text.literal("Cleared " + had + " remembered place(s).")
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
        if (!lumen.startFetch(player, query)) {
            source.sendError(Text.literal("No container within "
                    + Math.round(Lumen.config().chestSearchRadius) + " blocks has \"" + query + "\"."));
            return 0;
        }
        source.sendFeedback(() -> Text.literal(Lumen.config().companionName
                + " goes looking for " + query + ".").formatted(Formatting.AQUA), false);
        return 1;
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
        String refusal = lumen.startMining(player, query);
        if (refusal != null) {
            source.sendError(Text.literal(Lumen.config().companionName + ": " + refusal));
            return 0;
        }
        source.sendFeedback(() -> Text.literal(Lumen.config().companionName
                + " goes off to mine " + query + ".").formatted(Formatting.AQUA), false);
        return 1;
    }

    /**
     * Explains why Lumen is not going anywhere, and names the blocks around it that
     * vanilla pathfinding refuses to route through. In a modded pack that list is the
     * fastest way to find out which mod's blocks are the problem.
     */
    private static int why(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        LumenEntity lumen = requireLumen(source);
        if (lumen == null) {
            return 0;
        }
        World world = lumen.getWorld();
        BlockPos origin = lumen.getBlockPos();
        boolean idle = lumen.getNavigation().isIdle();

        source.sendFeedback(() -> Text.literal(Lumen.config().companionName + " is "
                + lumen.describeActivity() + "; navigation is "
                + (idle ? "idle (no path)" : "walking a path")
                + " at " + origin.toShortString()).formatted(Formatting.GRAY), false);

        Set<String> blockers = new LinkedHashSet<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir() || state.canPathfindThrough(world, pos, NavigationType.LAND)) {
                        continue;
                    }
                    boolean walkThroughAnyway = state.getCollisionShape(world, pos).isEmpty();
                    blockers.add(Registries.BLOCK.getId(state.getBlock())
                            + (walkThroughAnyway ? " (no collision - Lumen passes it anyway)" : " (solid)"));
                }
            }
        }
        // "solid" on a wall block is correct and expected; it is only worth reporting
        // so an actual obstruction can be told apart from ordinary geometry.
        String summary = blockers.isEmpty()
                ? "Nothing within 2 blocks is blocking movement."
                : "Not walkable through (solid ones are normal walls, and containers are "
                        + "approached from the side rather than entered): "
                        + blockers.stream().limit(10).reduce((a, b) -> a + ", " + b).orElse("");
        source.sendFeedback(() -> Text.literal(summary).formatted(Formatting.DARK_GRAY), false);
        return 1;
    }

    private static int follow(CommandContext<ServerCommandSource> context, ServerPlayerEntity target) {
        LumenEntity lumen = requireLumen(context.getSource());
        if (lumen == null) {
            return 0;
        }
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
