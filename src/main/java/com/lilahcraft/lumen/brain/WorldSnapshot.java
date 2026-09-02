package com.lilahcraft.lumen.brain;

import com.lilahcraft.lumen.LumenConfig;
import com.lilahcraft.lumen.entity.LumenEntity;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns Lumen's surroundings into the paragraph of plain English that goes into
 * every prompt.
 *
 * <p>This exists because a companion that cannot see anything invents things to talk
 * about. Everything the model is allowed to mention has to appear here first.
 */
public final class WorldSnapshot {

    private WorldSnapshot() {
    }

    /**
     * Terrain that is everywhere and tells you nothing. Anything not on this list is
     * fair game, which is what keeps modded blocks visible to Lumen.
     */
    private static final Set<String> SCENERY = Set.of(
            "stone", "deepslate", "dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol",
            "mycelium", "gravel", "sand", "red_sand", "sandstone", "red_sandstone", "andesite",
            "diorite", "granite", "tuff", "calcite", "smooth_basalt", "basalt", "blackstone",
            "netherrack", "end_stone", "bedrock", "clay", "mud", "soul_sand", "soul_soil",
            "snow", "snow_block", "ice", "packed_ice", "blue_ice", "powder_snow",
            "short_grass", "grass", "tall_grass", "fern", "large_fern", "dead_bush",
            "seagrass", "tall_seagrass", "kelp", "kelp_plant", "vine", "moss_block", "moss_carpet");

    /** Words that make a block worth pointing out, whatever mod it came from. */
    private static final String[] INTERESTING = {
            "ore", "diamond", "emerald", "amethyst", "gold", "copper", "lapis", "redstone",
            "glow", "lamp", "crystal", "beacon", "chest", "barrel", "furnace", "crafting",
            "anvil", "enchant", "brewing", "spawner", "portal", "shulker", "netherite", "quartz"};

    /** Builds the world state paragraph. Must run on the server thread. */
    public static String describe(LumenEntity lumen, LumenConfig config) {
        ServerWorld world = (ServerWorld) lumen.getWorld();
        BlockPos pos = lumen.getBlockPos();
        StringBuilder out = new StringBuilder();

        out.append("[what you can see right now]\n");
        out.append("- You are at ").append(pos.getX()).append(", ").append(pos.getY())
                .append(", ").append(pos.getZ())
                .append(" in ").append(prettyId(world.getRegistryKey().getValue()))
                .append(", in ").append(biomeName(world, pos)).append(".\n");

        // The bed case: Lumen used to walk onto a thing and then ask where it was.
        BlockState floor = world.getBlockState(pos.down());
        BlockState feet = world.getBlockState(pos);
        out.append("- You are standing on ").append(floor.isAir() ? "nothing" : blockName(floor));
        if (!feet.isAir()) {
            out.append(", with your feet inside ").append(blockName(feet));
        }
        out.append(".\n");

        out.append("- It is ").append(timeOfDay(world)).append(", ").append(weather(world));
        if (!world.isSkyVisible(pos)) {
            out.append(pos.getY() < 55 ? ", and you are underground" : ", and you are indoors or under cover");
        }
        out.append(". Light level here is ").append(world.getLightLevel(pos)).append(".\n");

        out.append("- Your health is ").append(Math.round(lumen.getHealth()))
                .append("/").append(Math.round(lumen.getMaxHealth()))
                .append(". You are ").append(lumen.describeActivity()).append(".\n");

        String carrying = describeCarrying(lumen);
        if (carrying != null) {
            out.append("- You are carrying ").append(carrying).append(".\n");
        }

        String looking = lookingAt(lumen, world);
        if (looking != null) {
            out.append("- You are looking at ").append(looking).append(".\n");
        }

        appendEntities(out, lumen, world, config);
        appendBlocks(out, lumen, world, config);
        return out.toString();
    }

    // ------------------------------------------------------------------ pieces

    private static String biomeName(ServerWorld world, BlockPos pos) {
        return world.getBiome(pos).getKey()
                .map(key -> prettyId(key.getValue()))
                .orElse("an unfamiliar biome");
    }

    private static String timeOfDay(ServerWorld world) {
        long time = world.getTimeOfDay() % 24000L;
        if (time < 1000L) return "sunrise";
        if (time < 6000L) return "morning";
        if (time < 11000L) return "midday";
        if (time < 13000L) return "sunset";
        if (time < 18000L) return "night";
        return "the small hours of the night";
    }

    private static String weather(ServerWorld world) {
        if (world.isThundering()) return "there is a thunderstorm";
        if (world.isRaining()) return "it is raining";
        return "the weather is clear";
    }

    private static String lookingAt(LumenEntity lumen, ServerWorld world) {
        HitResult hit = lumen.raycast(6.0D, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos hitPos = ((BlockHitResult) hit).getBlockPos();
        BlockState state = world.getBlockState(hitPos);
        return state.isAir() ? null : blockName(state);
    }

    private static String describeCarrying(LumenEntity lumen) {
        List<String> parts = new ArrayList<>();
        ItemStack held = lumen.getMainHandStack();
        if (!held.isEmpty()) {
            parts.add(held.getName().getString() + " in hand");
        }
        int items = 0;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < lumen.getInventory().size(); slot++) {
            ItemStack stack = lumen.getInventory().getStack(slot);
            if (!stack.isEmpty()) {
                items++;
                counts.merge(stack.getName().getString(), stack.getCount(), Integer::sum);
            }
        }
        if (items > 0) {
            parts.add(joinCounts(counts, 6) + " in your pack");
        }
        Map<String, Integer> delivering = new LinkedHashMap<>();
        for (ItemStack stack : lumen.getPendingDelivery()) {
            if (!stack.isEmpty()) {
                delivering.merge(stack.getName().getString(), stack.getCount(), Integer::sum);
            }
        }
        if (!delivering.isEmpty()) {
            parts.add(joinCounts(delivering, 4) + " to hand over to whoever asked for it");
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static void appendEntities(StringBuilder out, LumenEntity lumen, ServerWorld world, LumenConfig config) {
        if (config.awarenessEntityRadius <= 0.0D) {
            return;
        }
        Box box = lumen.getBoundingBox().expand(config.awarenessEntityRadius);
        List<Entity> nearby = world.getOtherEntities(lumen, box, Entity::isAlive);

        List<String> players = new ArrayList<>();
        Map<String, Integer> hostiles = new TreeMap<>();
        Map<String, Integer> creatures = new TreeMap<>();
        Map<String, Integer> items = new LinkedHashMap<>();
        double nearestHostile = Double.MAX_VALUE;

        for (Entity entity : nearby) {
            double distance = Math.sqrt(entity.squaredDistanceTo(lumen));
            if (entity instanceof PlayerEntity player) {
                if (!player.isSpectator()) {
                    players.add(player.getName().getString() + " (" + Math.round(distance) + " blocks)");
                }
            } else if (entity instanceof HostileEntity) {
                hostiles.merge(entity.getType().getName().getString(), 1, Integer::sum);
                nearestHostile = Math.min(nearestHostile, distance);
            } else if (entity instanceof ItemEntity item) {
                items.merge(item.getStack().getName().getString(), item.getStack().getCount(), Integer::sum);
            } else if (entity instanceof LivingEntity) {
                creatures.merge(entity.getType().getName().getString(), 1, Integer::sum);
            }
        }

        out.append("- Players nearby: ")
                .append(players.isEmpty() ? "nobody" : String.join(", ", players)).append(".\n");
        if (!hostiles.isEmpty()) {
            out.append("- Hostile mobs nearby: ").append(joinCounts(hostiles, 6))
                    .append(" (nearest is ").append(Math.round(nearestHostile)).append(" blocks away).\n");
        }
        if (!creatures.isEmpty()) {
            out.append("- Other creatures nearby: ").append(joinCounts(creatures, 5)).append(".\n");
        }
        if (!items.isEmpty()) {
            out.append("- Items lying on the ground: ").append(joinCounts(items, 5)).append(".\n");
        }
    }

    /**
     * Scans the box around Lumen and reports what is there, putting anything eye
     * catching first. Blocks that glow or hold something are always worth a mention;
     * plain terrain never is.
     */
    private static void appendBlocks(StringBuilder out, LumenEntity lumen, ServerWorld world, LumenConfig config) {
        int radius = config.awarenessBlockRadius;
        int height = config.awarenessBlockHeight;
        if (radius <= 0 || config.maxListedBlockTypes <= 0) {
            return;
        }

        BlockPos origin = lumen.getBlockPos();
        Map<String, Integer> notable = new LinkedHashMap<>();
        Map<String, Integer> ordinary = new LinkedHashMap<>();
        BlockPos.Mutable cursor = new BlockPos.Mutable();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                // Never read through an unloaded chunk: World#getBlockState would
                // load or generate it, on the server thread, once per block.
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int dy = -height; dy <= height; dy++) {
                    cursor.set(x, origin.getY() + dy, z);
                    BlockState state = world.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }
                    Identifier id = Registries.BLOCK.getId(state.getBlock());
                    if (isNotable(state, id)) {
                        notable.merge(blockName(state), 1, Integer::sum);
                    } else if (!SCENERY.contains(id.getPath())) {
                        ordinary.merge(blockName(state), 1, Integer::sum);
                    }
                }
            }
        }

        if (notable.isEmpty() && ordinary.isEmpty()) {
            out.append("- Nothing but plain terrain around you.\n");
            return;
        }
        List<String> parts = new ArrayList<>();
        int budget = config.maxListedBlockTypes;
        String notableText = joinCounts(notable, Math.min(budget, 6));
        if (notableText != null) {
            parts.add(notableText);
            budget -= Math.min(notable.size(), 6);
        }
        String ordinaryText = budget > 0 ? joinCounts(ordinary, budget) : null;
        if (ordinaryText != null) {
            parts.add(ordinaryText);
        }
        out.append("- Blocks around you: ").append(String.join(", ", parts)).append(".\n");
    }

    private static boolean isNotable(BlockState state, Identifier id) {
        if (state.getLuminance() > 0 || state.hasBlockEntity()) {
            return true;
        }
        String path = id.getPath();
        for (String word : INTERESTING) {
            if (path.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String blockName(BlockState state) {
        return state.getBlock().getName().getString().toLowerCase(Locale.ROOT);
    }

    private static String prettyId(Identifier id) {
        String path = id.getPath().replace('_', ' ');
        return "minecraft".equals(id.getNamespace()) ? path : path + " (" + id.getNamespace() + ")";
    }

    /** "oak log x24, chest x2", biggest first, capped. Null when there is nothing. */
    private static String joinCounts(Map<String, Integer> counts, int limit) {
        if (counts.isEmpty() || limit <= 0) {
            return null;
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> entry.getValue() > 1 ? entry.getKey() + " x" + entry.getValue() : entry.getKey())
                .reduce((a, b) -> a + ", " + b)
                .orElse(null);
    }
}
