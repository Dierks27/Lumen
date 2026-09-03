package com.lilahcraft.lumen.entity;

import com.lilahcraft.lumen.Lumen;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The selection wand: a plain stick with a name and a tag, because Lumen registers no
 * items. Left-click a block for the first corner, right-click for the second. The
 * clicks are intercepted server-side, so nothing is needed on the client and the block
 * is not actually hit.
 */
public final class LumenWand {

    private LumenWand() {
    }

    private static final String TAG = "lumen_wand";

    /** Selections per player: two corners, either may be null until set. */
    private static final Map<UUID, BlockPos[]> SELECTIONS = new HashMap<>();

    public static ItemStack create() {
        ItemStack stick = new ItemStack(Items.STICK);
        stick.getOrCreateNbt().putBoolean(TAG, true);
        stick.setCustomName(Text.literal(Lumen.config().companionName + "'s wand").formatted(Formatting.AQUA));
        return stick;
    }

    public static boolean isWand(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.isOf(Items.STICK)
                && stack.getNbt() != null && stack.getNbt().getBoolean(TAG);
    }

    /** Hooks the two click events. Called once from mod init. */
    public static void register() {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClient() || hand != Hand.MAIN_HAND || !isWand(player.getStackInHand(hand))) {
                return ActionResult.PASS;
            }
            setCorner(player, 0, pos);
            return ActionResult.FAIL; // do not start breaking the block
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient() || hand != Hand.MAIN_HAND || !isWand(player.getStackInHand(hand))) {
                return ActionResult.PASS;
            }
            setCorner(player, 1, hit.getBlockPos());
            return ActionResult.FAIL;
        });
    }

    private static void setCorner(PlayerEntity player, int which, BlockPos pos) {
        BlockPos[] corners = SELECTIONS.computeIfAbsent(player.getUuid(), k -> new BlockPos[2]);
        corners[which] = pos.toImmutable();
        String label = which == 0 ? "first" : "second";
        QuarryPlanner.Region region = selection(player.getUuid());
        String extra = region == null ? "" : " - " + region.sizeX() + "x" + region.sizeZ() + "x" + region.sizeY()
                + " = " + region.volume() + " blocks";
        player.sendMessage(Text.literal(label + " corner " + pos.toShortString() + extra).formatted(Formatting.AQUA), true);
        if (player.getWorld() instanceof ServerWorld world) {
            showSelection(world, player.getUuid());
        }
    }

    /** The selected box, or null until both corners are set. */
    @Nullable
    public static QuarryPlanner.Region selection(UUID player) {
        BlockPos[] corners = SELECTIONS.get(player);
        if (corners == null || corners[0] == null || corners[1] == null) {
            return null;
        }
        return QuarryPlanner.Region.of(corners[0].getX(), corners[0].getY(), corners[0].getZ(),
                corners[1].getX(), corners[1].getY(), corners[1].getZ());
    }

    public static void clear(UUID player) {
        SELECTIONS.remove(player);
    }

    /** Outlines the box with particles for a moment, so a vanilla client can see it. */
    public static void showSelection(ServerWorld world, UUID player) {
        QuarryPlanner.Region r = selection(player);
        if (r == null) {
            return;
        }
        double x0 = r.minX();
        double y0 = r.minY();
        double z0 = r.minZ();
        double x1 = r.maxX() + 1;
        double y1 = r.maxY() + 1;
        double z1 = r.maxZ() + 1;
        double step = Math.max(0.5D, Math.max(r.sizeX(), Math.max(r.sizeY(), r.sizeZ())) / 24.0D);
        int budget = 400;
        double[][] xs = {{x0, x1}};
        for (double y : new double[] {y0, y1}) {
            for (double z : new double[] {z0, z1}) {
                for (double x = x0; x <= x1 && budget-- > 0; x += step) {
                    world.spawnParticles(ParticleTypes.END_ROD, x, y, z, 1, 0, 0, 0, 0);
                }
            }
        }
        for (double x : xs[0]) {
            for (double z : new double[] {z0, z1}) {
                for (double y = y0; y <= y1 && budget-- > 0; y += step) {
                    world.spawnParticles(ParticleTypes.END_ROD, x, y, z, 1, 0, 0, 0, 0);
                }
            }
            for (double y : new double[] {y0, y1}) {
                for (double z = z0; z <= z1 && budget-- > 0; z += step) {
                    world.spawnParticles(ParticleTypes.END_ROD, x, y, z, 1, 0, 0, 0, 0);
                }
            }
        }
    }

    /** Puts a wand in the player's hand or inventory. */
    public static void giveTo(ServerPlayerEntity player) {
        ItemStack wand = create();
        if (!player.getInventory().insertStack(wand)) {
            player.dropItem(wand, false);
        }
    }
}
