package com.lilahcraft.lumen.craft;

import com.lilahcraft.lumen.Lumen;
import com.lilahcraft.lumen.entity.ChestFinder;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What smelts into what, read off the server's recipe manager. Every mod's ores,
 * foods and glass are in there already, so Lumen knows raw tin becomes a tin ingot
 * without anyone teaching it - this is the "he should just know how to use a
 * furnace" layer.
 *
 * <p>Three kinds of furnace exist in vanilla and most mods copy them: a furnace
 * takes {@code smelting} recipes, a blast furnace only {@code blasting}, a smoker only
 * {@code smoking}. {@link #recipeFor} is asked with the kind of furnace in front of
 * Lumen so it never loads a fish into a blast furnace.
 */
public final class SmeltPlanner {

    /** Which recipe book a furnace block reads. */
    public enum Kind {
        FURNACE, BLAST_FURNACE, SMOKER;

        /** From the block id path: "blast_furnace", "iron_furnace", "smoker", "kiln". */
        public static Kind fromBlockPath(String path) {
            String p = path.toLowerCase(Locale.ROOT);
            if (p.contains("blast")) {
                return BLAST_FURNACE;
            }
            if (p.contains("smoker")) {
                return SMOKER;
            }
            return FURNACE;
        }
    }

    /** One thing to smelt: what goes in, what comes out, and how long each takes. */
    public record Job(ItemStack input, ItemStack output, int cookTicks) {
        public String inputName() {
            return ChestFinder.plainName(input);
        }

        public String outputName() {
            return ChestFinder.plainName(output);
        }
    }

    private final RecipeManager manager;
    private final DynamicRegistryManager registries;
    private final List<AbstractCookingRecipe> smelting = new ArrayList<>();
    private final List<AbstractCookingRecipe> blasting = new ArrayList<>();
    private final List<AbstractCookingRecipe> smoking = new ArrayList<>();
    private final Map<Item, Integer> fuelTimes;

    private static SmeltPlanner cached;

    public static synchronized SmeltPlanner forServer(MinecraftServer server) {
        RecipeManager manager = server.getRecipeManager();
        if (cached == null || cached.manager != manager) {
            cached = new SmeltPlanner(manager, server.getRegistryManager());
        }
        return cached;
    }

    private SmeltPlanner(RecipeManager manager, DynamicRegistryManager registries) {
        this.manager = manager;
        this.registries = registries;
        for (Recipe<?> recipe : manager.values()) {
            if (!(recipe instanceof AbstractCookingRecipe cooking)) {
                continue;
            }
            RecipeType<?> type = recipe.getType();
            if (type == RecipeType.SMELTING) {
                smelting.add(cooking);
            } else if (type == RecipeType.BLASTING) {
                blasting.add(cooking);
            } else if (type == RecipeType.SMOKING) {
                smoking.add(cooking);
            }
        }
        this.fuelTimes = AbstractFurnaceBlockEntity.createFuelTimeMap();
        Lumen.LOGGER.info("SmeltPlanner: {} smelting, {} blasting, {} smoking recipes; {} fuels",
                smelting.size(), blasting.size(), smoking.size(), fuelTimes.size());
    }

    /** The recipe a furnace of this kind would run on {@code input}, or null. */
    @Nullable
    public AbstractCookingRecipe recipeFor(ItemStack input, Kind kind) {
        if (input.isEmpty()) {
            return null;
        }
        List<AbstractCookingRecipe> book = switch (kind) {
            case BLAST_FURNACE -> blasting;
            case SMOKER -> smoking;
            default -> smelting;
        };
        for (AbstractCookingRecipe recipe : book) {
            List<net.minecraft.recipe.Ingredient> ingredients = recipe.getIngredients();
            if (!ingredients.isEmpty() && ingredients.get(0).test(input)) {
                return recipe;
            }
        }
        return null;
    }

    /** Whether any furnace at all could cook {@code input}. */
    public boolean isSmeltable(ItemStack input) {
        return recipeFor(input, Kind.FURNACE) != null || recipeFor(input, Kind.BLAST_FURNACE) != null
                || recipeFor(input, Kind.SMOKER) != null;
    }

    /** The furnace kinds that accept {@code input}, plain furnace first. */
    public List<Kind> kindsFor(ItemStack input) {
        List<Kind> out = new ArrayList<>();
        for (Kind kind : List.of(Kind.FURNACE, Kind.BLAST_FURNACE, Kind.SMOKER)) {
            if (recipeFor(input, kind) != null) {
                out.add(kind);
            }
        }
        return out;
    }

    @Nullable
    public Job jobFor(ItemStack input, Kind kind) {
        AbstractCookingRecipe recipe = recipeFor(input, kind);
        if (recipe == null) {
            return null;
        }
        ItemStack output = recipe.getOutput(registries);
        return new Job(input.copyWithCount(1), output.isEmpty() ? ItemStack.EMPTY : output.copy(),
                Math.max(1, recipe.getCookTime()));
    }

    /**
     * Picks what in {@code pack} the words mean. "raw iron" names the input; "iron
     * ingots" names what comes out; "everything" or an empty query means anything
     * smeltable. Only smeltable stacks are ever returned.
     *
     * @return matching stacks (references into the pack list as given), nearest match tier first
     */
    public List<ItemStack> pickInputs(List<ItemStack> pack, @Nullable String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<ItemStack> byInput = new ArrayList<>();
        List<ItemStack> byOutput = new ArrayList<>();
        List<ItemStack> any = new ArrayList<>();
        for (ItemStack stack : pack) {
            if (stack.isEmpty() || !isSmeltable(stack)) {
                continue;
            }
            any.add(stack);
            if (q.isEmpty() || ChestFinder.meansEverything(q)) {
                continue;
            }
            if (ChestFinder.matches(stack, q)) {
                byInput.add(stack);
                continue;
            }
            Job job = jobFor(stack, kindsFor(stack).get(0));
            if (job != null && !job.output().isEmpty() && ChestFinder.matches(job.output(), q)) {
                byOutput.add(stack);
            }
        }
        if (q.isEmpty() || ChestFinder.meansEverything(q)) {
            return any;
        }
        return !byInput.isEmpty() ? byInput : byOutput;
    }

    /** Burn time in ticks for one of {@code stack}, or 0 when it is not fuel. */
    public int fuelTicks(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        return fuelTimes.getOrDefault(stack.getItem(), 0);
    }

    public boolean isFuel(ItemStack stack) {
        return fuelTicks(stack) > 0;
    }

    /** Fuel items needed to cook {@code count} of a job, rounded up, at least one. */
    public static int fuelNeeded(int count, int cookTicks, int fuelTicksEach) {
        if (fuelTicksEach <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) Math.ceil((double) count * cookTicks / fuelTicksEach));
    }
}
