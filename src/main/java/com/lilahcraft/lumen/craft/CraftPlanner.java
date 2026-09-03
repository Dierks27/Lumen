package com.lilahcraft.lumen.craft;

import com.lilahcraft.lumen.Lumen;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Works out how to craft something from what is in Lumen's pack, recursively:
 * "sticks" with no planks but a log in the pack is log -> planks -> sticks.
 *
 * <p>Recipes are read from the server's recipe manager, so modded recipes are known
 * without knowing the mods. Shaped recipes are treated by ingredient counts rather than
 * by solving the grid: the output of a crafting recipe does not depend on where in the
 * grid the ingredients sit, only on which ingredients are consumed, so a simulation
 * that consumes one matching item per ingredient produces exactly what the table would.
 */
public final class CraftPlanner {

    /** One recipe to run, this many times. */
    public record Step(CraftingRecipe recipe, int times, ItemStack output) {
        public String describe() {
            return times + "x " + output.getName().getString().toLowerCase(Locale.ROOT)
                    + (output.getCount() > 1 ? " (" + output.getCount() * times + ")" : "");
        }
    }

    /** The plan, or why there is none. */
    public record Plan(List<Step> steps, boolean needsTable, @Nullable String missing) {
        public boolean isEmpty() {
            return steps.isEmpty();
        }
    }

    private static final int MAX_DEPTH = 4;
    private static final int MAX_ALTERNATIVES = 4;
    private static final int MAX_STEPS = 24;

    private final RecipeManager manager;
    private final DynamicRegistryManager registries;
    private final Map<Item, List<CraftingRecipe>> byOutput = new HashMap<>();

    private static CraftPlanner cached;

    public static synchronized CraftPlanner forServer(MinecraftServer server) {
        RecipeManager manager = server.getRecipeManager();
        if (cached == null || cached.manager != manager) {
            cached = new CraftPlanner(manager, server.getRegistryManager());
        }
        return cached;
    }

    private CraftPlanner(RecipeManager manager, DynamicRegistryManager registries) {
        this.manager = manager;
        this.registries = registries;
        int count = 0;
        for (Recipe<?> recipe : manager.values()) {
            if (recipe.getType() != RecipeType.CRAFTING || !(recipe instanceof CraftingRecipe crafting)) {
                continue;
            }
            ItemStack output;
            try {
                output = recipe.getOutput(registries);
            } catch (RuntimeException e) {
                continue; // special recipes (dyeing, fireworks) have no fixed output
            }
            if (output == null || output.isEmpty()) {
                continue;
            }
            byOutput.computeIfAbsent(output.getItem(), k -> new ArrayList<>()).add(crafting);
            count++;
        }
        Lumen.LOGGER.info("Indexed {} crafting recipe(s) for {} item(s)", count, byOutput.size());
    }

    /** Items that can be crafted, matching a plain-words query - for "make me some sticks". */
    @Nullable
    public Item findCraftable(String query) {
        Item best = null;
        int bestScore = 0;
        for (Item item : byOutput.keySet()) {
            int score = com.lilahcraft.lumen.entity.ChestFinder.matchScore(new ItemStack(item), query);
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return best;
    }

    /**
     * Plans {@code count} of {@code target} from {@code have}. The list is not modified;
     * the plan's steps, run in order against the real pack, consume what the plan assumed.
     */
    public Plan plan(Item target, int count, List<ItemStack> have) {
        List<ItemStack> sim = new ArrayList<>();
        for (ItemStack stack : have) {
            if (!stack.isEmpty()) {
                sim.add(stack.copy());
            }
        }
        List<Step> steps = new ArrayList<>();
        String[] missing = new String[1];
        boolean ok = planInto(target, count, sim, steps, new HashSet<>(), 0, missing);
        boolean needsTable = false;
        for (Step step : steps) {
            if (!step.recipe().fits(2, 2)) {
                needsTable = true;
            }
        }
        if (!ok) {
            return new Plan(List.of(), needsTable, missing[0] == null ? "no recipe i know makes that" : missing[0]);
        }
        return new Plan(steps, needsTable, null);
    }

    private boolean planInto(Item target, int count, List<ItemStack> sim, List<Step> steps,
                             Set<Item> onPath, int depth, String[] missing) {
        if (depth > MAX_DEPTH || steps.size() > MAX_STEPS || onPath.contains(target)) {
            return false;
        }
        List<CraftingRecipe> recipes = byOutput.get(target);
        if (recipes == null || recipes.isEmpty()) {
            if (missing[0] == null) {
                missing[0] = "i don't know a recipe for " + name(target);
            }
            return false;
        }
        onPath.add(target);
        try {
            int tried = 0;
            for (CraftingRecipe recipe : recipes) {
                if (tried++ >= MAX_ALTERNATIVES) {
                    break;
                }
                List<ItemStack> attemptSim = copy(sim);
                List<Step> attemptSteps = new ArrayList<>();
                if (tryRecipe(recipe, target, count, attemptSim, attemptSteps, onPath, depth, missing)) {
                    sim.clear();
                    sim.addAll(attemptSim);
                    steps.addAll(attemptSteps);
                    return true;
                }
            }
            return false;
        } finally {
            onPath.remove(target);
        }
    }

    private boolean tryRecipe(CraftingRecipe recipe, Item target, int count, List<ItemStack> sim,
                              List<Step> steps, Set<Item> onPath, int depth, String[] missing) {
        ItemStack output = recipe.getOutput(registries);
        if (output.isEmpty() || output.getItem() != target) {
            return false;
        }
        int times = (int) Math.ceil(count / (double) Math.max(1, output.getCount()));
        for (int round = 0; round < times; round++) {
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient == null || ingredient.isEmpty()) {
                    continue;
                }
                if (consume(ingredient, sim)) {
                    continue;
                }
                // Nothing in hand satisfies it: try to craft one of the things it accepts.
                boolean made = false;
                int options = 0;
                for (ItemStack option : ingredient.getMatchingStacks()) {
                    if (options++ >= MAX_ALTERNATIVES) {
                        break;
                    }
                    if (!byOutput.containsKey(option.getItem())) {
                        continue;
                    }
                    if (planInto(option.getItem(), 1, sim, steps, onPath, depth + 1, missing)) {
                        made = consume(ingredient, sim);
                        if (made) {
                            break;
                        }
                    }
                }
                if (!made) {
                    if (missing[0] == null) {
                        missing[0] = "i'd need " + describeIngredient(ingredient) + " for " + name(target);
                    }
                    return false;
                }
            }
        }
        // The output goes back into the simulated pack, so a later step can use it.
        ItemStack made = output.copy();
        made.setCount(output.getCount() * times);
        sim.add(made);
        steps.add(new Step(recipe, times, output.copy()));
        return true;
    }

    /** Removes one item matching the ingredient from the simulated pack. */
    private static boolean consume(Ingredient ingredient, List<ItemStack> sim) {
        for (ItemStack stack : sim) {
            if (!stack.isEmpty() && ingredient.test(stack)) {
                stack.decrement(1);
                return true;
            }
        }
        return false;
    }

    /**
     * Runs a plan against the real pack. Each step consumes its ingredients from the
     * pack and puts the output in; container items (buckets) come back.
     *
     * @return how many of the final product were made, 0 if the pack no longer matched the plan
     */
    public int execute(Plan plan, SimpleInventory pack) {
        int made = 0;
        Step finalStep = plan.steps().isEmpty() ? null : plan.steps().get(plan.steps().size() - 1);
        for (Step step : plan.steps()) {
            for (int round = 0; round < step.times(); round++) {
                List<ItemStack> remainders = new ArrayList<>();
                for (Ingredient ingredient : step.recipe().getIngredients()) {
                    if (ingredient == null || ingredient.isEmpty()) {
                        continue;
                    }
                    if (!takeFromPack(ingredient, pack, remainders)) {
                        return ItemStack.EMPTY;
                    }
                }
                ItemStack out = step.recipe().getOutput(registries).copy();
                int produced = out.getCount();
                ItemStack leftover = pack.addStack(out);
                if (!leftover.isEmpty()) {
                    produced -= leftover.getCount(); // pack full; what did not fit is lost to the count
                }
                for (ItemStack remainder : remainders) {
                    pack.addStack(remainder);
                }
                if (step == finalStep) {
                    made += produced;
                }
            }
        }
        return made;
    }

    private static boolean takeFromPack(Ingredient ingredient, SimpleInventory pack, List<ItemStack> remainders) {
        for (int slot = 0; slot < pack.size(); slot++) {
            ItemStack stack = pack.getStack(slot);
            if (stack.isEmpty() || !ingredient.test(stack)) {
                continue;
            }
            Item remainderItem = stack.getItem().getRecipeRemainder();
            pack.removeStack(slot, 1);
            if (remainderItem != null) {
                remainders.add(new ItemStack(remainderItem));
            }
            return true;
        }
        return false;
    }

    private static List<ItemStack> copy(List<ItemStack> stacks) {
        List<ItemStack> out = new ArrayList<>(stacks.size());
        for (ItemStack s : stacks) {
            out.add(s.copy());
        }
        return out;
    }

    public static String name(Item item) {
        return new ItemStack(item).getName().getString().toLowerCase(Locale.ROOT);
    }

    private static String describeIngredient(Ingredient ingredient) {
        ItemStack[] options = ingredient.getMatchingStacks();
        if (options.length == 0) {
            return "something";
        }
        String first = options[0].getName().getString().toLowerCase(Locale.ROOT);
        return options.length > 1 ? first + " or the like" : first;
    }

    public static String id(Item item) {
        return Registries.ITEM.getId(item).toString();
    }
}
