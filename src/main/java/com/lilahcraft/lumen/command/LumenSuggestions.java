package com.lilahcraft.lumen.command;

import com.lilahcraft.lumen.Lumen;
import com.lilahcraft.lumen.entity.LumenEntity;
import com.lilahcraft.lumen.memory.LumenMemory;
import com.lilahcraft.lumen.menu.Catalog;
import com.lilahcraft.lumen.skill.LumenSkill;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Tab-completion for the item words in {@code /lumen} commands, so the player
 * cycles through what is actually there instead of spelling a modded name. The
 * arguments are greedy strings, which lets a suggestion with spaces ("raw tin")
 * stand as-is; no quoting needed.
 *
 * <p>Nearby-container scans are cached for a few seconds because completion fires
 * on every keystroke.
 */
public final class LumenSuggestions {

    private LumenSuggestions() {
    }

    /** What Lumen carries. */
    public static final SuggestionProvider<ServerCommandSource> PACK = (context, builder) ->
            suggest(builder, withLumen(context, lumen -> queries(Catalog.pack(lumen))));

    /** What Lumen carries that a furnace would take, plus "everything". */
    public static final SuggestionProvider<ServerCommandSource> SMELTABLE = (context, builder) -> {
        List<String> options = withLumen(context, lumen -> queries(Catalog.smeltable(lumen)));
        if (options.size() > 1) {
            options.add(0, "everything");
        }
        return suggest(builder, options);
    };

    /** Items seen in containers around Lumen, plus items remembered in places. */
    public static final SuggestionProvider<ServerCommandSource> NEARBY = (context, builder) -> {
        List<String> options = withLumen(context, LumenSuggestions::nearbyCached);
        return suggest(builder, options);
    };

    /** "<item> in <where>": the item from the pack, then the container words. */
    public static final SuggestionProvider<ServerCommandSource> PUT = (context, builder) -> {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        int in = typed.indexOf(" in ");
        if (in < 0) {
            List<String> options = new ArrayList<>();
            options.add("everything in ");
            for (String q : withLumen(context, lumen -> queries(Catalog.pack(lumen)))) {
                options.add(q + " in ");
            }
            return suggest(builder, options);
        }
        String head = builder.getRemaining().substring(0, in + 4);
        List<String> tails = new ArrayList<>(List.of("this chest", "the nearest chest"));
        String item = typed.substring(0, in).trim();
        if (!item.isEmpty() && !item.equals("everything")) {
            tails.add("the chest with " + item);
        }
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player != null) {
            for (LumenMemory.KnownPlace place : Lumen.memory().placesIn(
                    player.getWorld().getRegistryKey().getValue(), player.getBlockPos())) {
                tails.add("the " + place.name);
            }
        }
        List<String> options = new ArrayList<>();
        for (String tail : tails) {
            options.add(head + tail);
        }
        return suggest(builder, options);
    };

    /** Names of taught skills. */
    public static final SuggestionProvider<ServerCommandSource> SKILLS = (context, builder) -> {
        List<String> options = new ArrayList<>();
        for (LumenSkill skill : Lumen.skills().all()) {
            options.add(skill.name);
        }
        return suggest(builder, options);
    };

    /** Names of remembered places in the player's dimension. */
    public static final SuggestionProvider<ServerCommandSource> PLACES = (context, builder) -> {
        List<String> options = new ArrayList<>();
        ServerPlayerEntity player = context.getSource().getPlayer();
        BlockPos near = player == null ? BlockPos.ORIGIN : player.getBlockPos();
        if (player != null) {
            for (LumenMemory.KnownPlace place : Lumen.memory().placesIn(
                    player.getWorld().getRegistryKey().getValue(), near)) {
                options.add(place.name);
            }
        }
        return suggest(builder, options);
    };

    // ------------------------------------------------------------------ helpers

    private interface WithLumen {
        List<String> apply(LumenEntity lumen);
    }

    private static List<String> withLumen(CommandContext<ServerCommandSource> context, WithLumen fn) {
        LumenEntity lumen = Lumen.manager().get(context.getSource().getServer());
        if (lumen == null) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(fn.apply(lumen));
        } catch (RuntimeException e) {
            Lumen.LOGGER.debug("Suggestion scan failed: {}", e.toString());
            return new ArrayList<>();
        }
    }

    private static List<String> queries(List<Catalog.Entry> entries) {
        List<String> out = new ArrayList<>();
        for (Catalog.Entry entry : entries) {
            out.add(entry.query());
        }
        return out;
    }

    private static List<String> cachedNearby = List.of();
    private static long cachedAt;
    private static BlockPos cachedAround = BlockPos.ORIGIN;

    private static synchronized List<String> nearbyCached(LumenEntity lumen) {
        long now = System.currentTimeMillis();
        if (now - cachedAt > 4000L || !cachedAround.isWithinDistance(lumen.getBlockPos(), 8.0D)) {
            cachedNearby = queries(Catalog.nearbyItems(lumen, Catalog.searchRadius()));
            cachedAt = now;
            cachedAround = lumen.getBlockPos();
        }
        return cachedNearby;
    }

    /** Offers every option that starts with, or contains a word starting with, what was typed. */
    private static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder, List<String> options) {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String option : options) {
            String lower = option.toLowerCase(Locale.ROOT);
            if (typed.isEmpty() || lower.startsWith(typed) || lower.contains(" " + typed)) {
                builder.suggest(option);
            }
        }
        return builder.buildFuture();
    }
}
