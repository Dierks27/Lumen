package com.lilahcraft.lumen.entity;

import net.minecraft.block.BlockState;
import net.minecraft.block.Fertilizable;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/** The Minecraft side of {@link BlockMatcher}: turns a BlockState into the strings it matches on. */
public final class BlockStates {

    private BlockStates() {
    }

    public static String id(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).toString();
    }

    public static String displayName(BlockState state) {
        return state.getBlock().getName().getString();
    }

    /** Every property as name -> value text, e.g. age -> "3", facing -> "north". */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Map<String, String> props(BlockState state) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getEntries().entrySet()) {
            Property property = entry.getKey();
            out.put(property.getName(), property.name(entry.getValue()));
        }
        return out;
    }

    /** The largest value of each integer property, so "age=max" can be resolved. */
    public static Map<String, Integer> intMax(BlockState state) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Property<?> property : state.getEntries().keySet()) {
            if (property instanceof IntProperty intProperty) {
                int max = Integer.MIN_VALUE;
                for (Integer value : intProperty.getValues()) {
                    max = Math.max(max, value);
                }
                out.put(property.getName(), max);
            }
        }
        return out;
    }

    /** The block's own answer to "can this still grow", or null when it has no opinion. */
    @Nullable
    public static Boolean stillGrowing(WorldView world, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof Fertilizable fertilizable)) {
            return null;
        }
        try {
            return fertilizable.isFertilizable(world, pos, state, false);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static boolean isRipe(WorldView world, BlockPos pos, BlockState state) {
        return BlockMatcher.isRipe(props(state), intMax(state), stillGrowing(world, pos, state));
    }

    public static boolean matches(BlockMatcher.Spec spec, WorldView world, BlockPos pos, BlockState state) {
        return BlockMatcher.matches(spec, id(state), displayName(state), props(state), intMax(state),
                stillGrowing(world, pos, state));
    }

    /** "minecraft:sweet_berry_bush[age=3]" - what /lumen look shows and a taught skill stores. */
    public static String describe(BlockState state) {
        Map<String, String> props = props(state);
        if (props.isEmpty()) {
            return id(state);
        }
        StringBuilder out = new StringBuilder(id(state)).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(e.getKey()).append('=').append(e.getValue());
        }
        return out.append(']').toString();
    }

    /** Whether the block carries any growth signal at all - worth pointing out to the player. */
    public static boolean hasGrowthSignal(BlockState state) {
        if (state.getBlock() instanceof Fertilizable) {
            return true;
        }
        Map<String, String> props = props(state);
        for (String key : BlockMatcher.GROWTH_PROPERTIES) {
            if (props.containsKey(key)) {
                return true;
            }
        }
        return false;
    }
}
