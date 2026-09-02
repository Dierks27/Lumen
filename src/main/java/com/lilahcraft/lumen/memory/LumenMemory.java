package com.lilahcraft.lumen.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lilahcraft.lumen.Lumen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What Lumen has learned and keeps across restarts.
 *
 * <p>Today that is where things were found: fetch an item once and the container it
 * came out of is remembered, so the next "find me some iron" walks straight there
 * instead of searching the room again. Entries that turn out to be wrong are
 * forgotten on the spot, so the memory corrects itself as chests get emptied.
 *
 * <p>Stored as plain JSON at {@code config/lumen/memory.json} - readable and
 * editable by hand, and safe to delete.
 */
public final class LumenMemory {

    /** One remembered container. A mutable bean rather than a record, so Gson is happy. */
    public static final class KnownContainer {
        public String query = "";
        public String itemId = "";
        public String dimension = "";
        public int x;
        public int y;
        public int z;
        public long lastSeen;
        public int hits;

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    /** The on-disk shape. Keeping a wrapper leaves room to add more kinds of memory. */
    private static final class Data {
        List<KnownContainer> containers = new ArrayList<>();
    }

    /** Beyond this the oldest entries are dropped. */
    private static final int MAX_ENTRIES = 200;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Data data = new Data();

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("lumen").resolve("memory.json");
    }

    public synchronized void load() {
        Path path = path();
        if (!Files.exists(path)) {
            this.data = new Data();
            return;
        }
        try {
            Data loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
            this.data = loaded == null ? new Data() : loaded;
            if (this.data.containers == null) {
                this.data.containers = new ArrayList<>();
            }
            Lumen.LOGGER.info("Loaded {} remembered container(s)", this.data.containers.size());
        } catch (IOException | RuntimeException e) {
            Lumen.LOGGER.error("Could not read {}, starting with an empty memory: {}", path, e.toString());
            this.data = new Data();
        }
    }

    public synchronized void save() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this.data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Lumen.LOGGER.error("Could not write {}: {}", path, e.toString());
        }
    }

    public synchronized int size() {
        return data.containers.size();
    }

    public synchronized void clear() {
        data.containers.clear();
        save();
    }

    /** Records that {@code itemId} was actually taken out of the container at {@code pos}. */
    public synchronized void rememberContainer(String query, Identifier itemId, Identifier dimension, BlockPos pos) {
        String normalised = normalise(query);
        KnownContainer existing = find(dimension, pos, normalised);
        if (existing != null) {
            existing.lastSeen = System.currentTimeMillis();
            existing.hits++;
            existing.itemId = itemId.toString();
        } else {
            KnownContainer entry = new KnownContainer();
            entry.query = normalised;
            entry.itemId = itemId.toString();
            entry.dimension = dimension.toString();
            entry.x = pos.getX();
            entry.y = pos.getY();
            entry.z = pos.getZ();
            entry.lastSeen = System.currentTimeMillis();
            entry.hits = 1;
            data.containers.add(entry);
        }
        evictOldest();
        save();
    }

    /** Drops every memory of this container, e.g. once it turns out to be empty. */
    public synchronized void forgetContainer(Identifier dimension, BlockPos pos) {
        boolean removed = data.containers.removeIf(entry -> entry.dimension.equals(dimension.toString())
                && entry.x == pos.getX() && entry.y == pos.getY() && entry.z == pos.getZ());
        if (removed) {
            save();
        }
    }

    /**
     * Places worth checking for {@code query}, best first: most recently confirmed and
     * most often right, and only ones within reach.
     */
    public synchronized List<BlockPos> recall(String query, Identifier dimension, BlockPos near,
                                              double maxDistance, Set<BlockPos> exclude) {
        String normalised = normalise(query);
        double maxSquared = maxDistance * maxDistance;
        return data.containers.stream()
                .filter(entry -> entry.dimension.equals(dimension.toString()))
                .filter(entry -> looksLike(entry.query, entry.itemId, normalised))
                .filter(entry -> entry.pos().getSquaredDistance(near) <= maxSquared)
                .filter(entry -> !exclude.contains(entry.pos()))
                .sorted(Comparator.comparingInt((KnownContainer entry) -> entry.hits).reversed()
                        .thenComparing(Comparator.comparingLong((KnownContainer entry) -> entry.lastSeen).reversed()))
                .map(KnownContainer::pos)
                .toList();
    }

    /** The "[what you remember]" block for the prompt. Empty string when nothing is known. */
    public synchronized String describe(Identifier dimension, BlockPos near, int limit) {
        List<KnownContainer> relevant = data.containers.stream()
                .filter(entry -> entry.dimension.equals(dimension.toString()))
                .sorted(Comparator.comparingLong((KnownContainer entry) -> entry.lastSeen).reversed())
                .limit(Math.max(0, limit))
                .toList();
        if (relevant.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("[what you remember from before]\n");
        for (KnownContainer entry : relevant) {
            out.append("- ").append(prettyItem(entry.itemId))
                    .append(" was in a container at ").append(entry.x).append(", ")
                    .append(entry.y).append(", ").append(entry.z)
                    .append(" (").append(Math.round(Math.sqrt(entry.pos().getSquaredDistance(near))))
                    .append(" blocks away)\n");
        }
        return out.toString();
    }

    /** Lines for /lumen memory. */
    public synchronized List<String> lines(int limit) {
        return data.containers.stream()
                .sorted(Comparator.comparingLong((KnownContainer entry) -> entry.lastSeen).reversed())
                .limit(Math.max(0, limit))
                .map(entry -> prettyItem(entry.itemId) + " at " + entry.x + ", " + entry.y + ", " + entry.z
                        + " in " + entry.dimension + " (found " + entry.hits + "x)")
                .toList();
    }

    private KnownContainer find(Identifier dimension, BlockPos pos, String normalisedQuery) {
        for (KnownContainer entry : data.containers) {
            if (entry.dimension.equals(dimension.toString()) && entry.x == pos.getX()
                    && entry.y == pos.getY() && entry.z == pos.getZ()
                    && entry.query.equals(normalisedQuery)) {
                return entry;
            }
        }
        return null;
    }

    private void evictOldest() {
        if (data.containers.size() <= MAX_ENTRIES) {
            return;
        }
        data.containers.sort(Comparator.comparingLong((KnownContainer entry) -> entry.lastSeen).reversed());
        data.containers = new ArrayList<>(data.containers.subList(0, MAX_ENTRIES));
    }

    /**
     * A memory is worth trying if it was filed under this request or holds a matching
     * item. Package private and free of Minecraft types so it can be unit tested.
     */
    static boolean looksLike(String entryQuery, String entryItemId, String normalisedQuery) {
        if (normalisedQuery.isEmpty()) {
            return false;
        }
        if (entryQuery.equals(normalisedQuery) || entryQuery.contains(normalisedQuery)) {
            return true;
        }
        // Path only: matching the whole id would make "create" hit every item from
        // createaddition, and "minecraft" hit literally everything.
        return itemPath(entryItemId).contains(normalisedQuery);
    }

    private static String prettyItem(String itemId) {
        return itemPath(itemId).replace('_', ' ');
    }

    /** The item id without its mod namespace, lowercased. */
    private static String itemPath(String itemId) {
        int colon = itemId.indexOf(':');
        return (colon < 0 ? itemId : itemId.substring(colon + 1)).toLowerCase(Locale.ROOT);
    }

    static String normalise(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }
}
