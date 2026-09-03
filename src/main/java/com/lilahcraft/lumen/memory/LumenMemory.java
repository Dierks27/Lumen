package com.lilahcraft.lumen.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lilahcraft.lumen.Lumen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

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

    /**
     * A place the player named: "remember this as the hops room". A point plus a radius,
     * which is enough to say "you are in the hops room" and to search around it.
     */
    public static final class KnownPlace {
        public String name = "";
        public String dimension = "";
        public int x;
        public int y;
        public int z;
        public int radius = DEFAULT_PLACE_RADIUS;
        public String taughtBy = "";
        public long created;
        public int visits;

        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        /** True when {@code pos} is inside this place's sphere. */
        public boolean contains(BlockPos pos) {
            return pos().getSquaredDistance(pos) <= (double) radius * radius;
        }
    }

    /** How big a place is when nobody says: a small room. */
    public static final int DEFAULT_PLACE_RADIUS = 6;

    /** The on-disk shape. Keeping a wrapper leaves room to add more kinds of memory. */
    private static final class Data {
        List<KnownContainer> containers = new ArrayList<>();
        /** Absent in files written before v0.7.0; null-guarded on load. */
        List<KnownPlace> places = new ArrayList<>();
    }

    /** Beyond this the oldest entries are dropped. */
    private static final int MAX_ENTRIES = 200;

    private static final int MAX_PLACES = 100;

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
            if (this.data.places == null) {
                this.data.places = new ArrayList<>();
            }
            Lumen.LOGGER.info("Loaded {} remembered container(s) and {} place(s)",
                    this.data.containers.size(), this.data.places.size());
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

    public synchronized int placeCount() {
        return data.places.size();
    }

    /** Forgets everything: containers and places alike. */
    public synchronized void clear() {
        data.containers.clear();
        data.places.clear();
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

    // ------------------------------------------------------------------ places

    /**
     * Saves a named place, replacing any place of the same name in the same dimension.
     * Names are kept as plain lowercase words so "the Hops Room" and "hops room" are one
     * place.
     */
    public synchronized KnownPlace rememberPlace(String name, Identifier dimension, BlockPos pos, int radius,
                                                 String taughtBy) {
        String clean = cleanPlaceName(name);
        data.places.removeIf(place -> place.dimension.equals(dimension.toString()) && place.name.equals(clean));
        KnownPlace place = new KnownPlace();
        place.name = clean;
        place.dimension = dimension.toString();
        place.x = pos.getX();
        place.y = pos.getY();
        place.z = pos.getZ();
        place.radius = Math.max(1, Math.min(64, radius));
        place.taughtBy = taughtBy == null ? "" : taughtBy;
        place.created = System.currentTimeMillis();
        data.places.add(place);
        if (data.places.size() > MAX_PLACES) {
            data.places.remove(0);
        }
        save();
        return place;
    }

    /** @return true if a place by that name (fuzzily) existed and is now gone */
    public synchronized boolean forgetPlace(String query) {
        KnownPlace place = findPlace(query, null);
        if (place == null) {
            return false;
        }
        data.places.remove(place);
        save();
        return true;
    }

    /**
     * The place that best answers to {@code query}, in {@code dimension} if given.
     * Matching is tiered the same way items are: exact name, then every word of the
     * query as a whole word of the name, then substring. "hops", "the hops room" and
     * "hopsroom" all find "hops room".
     */
    @Nullable
    public synchronized KnownPlace findPlace(String query, @Nullable Identifier dimension) {
        String clean = cleanPlaceName(query);
        if (clean.isEmpty()) {
            return null;
        }
        KnownPlace best = null;
        int bestScore = 0;
        for (KnownPlace place : data.places) {
            if (dimension != null && !place.dimension.equals(dimension.toString())) {
                continue;
            }
            int score = placeScore(place.name, clean);
            if (score > bestScore) {
                bestScore = score;
                best = place;
            }
        }
        return best;
    }

    /** All places in a dimension, nearest first. */
    public synchronized List<KnownPlace> placesIn(Identifier dimension, BlockPos near) {
        return data.places.stream()
                .filter(place -> place.dimension.equals(dimension.toString()))
                .sorted(Comparator.comparingDouble((KnownPlace place) -> place.pos().getSquaredDistance(near)))
                .toList();
    }

    /** The place {@code pos} is inside, if any - the smallest one when they overlap. */
    @Nullable
    public synchronized KnownPlace placeAt(Identifier dimension, BlockPos pos) {
        KnownPlace inside = null;
        for (KnownPlace place : data.places) {
            if (place.dimension.equals(dimension.toString()) && place.contains(pos)
                    && (inside == null || place.radius < inside.radius)) {
                inside = place;
            }
        }
        return inside;
    }

    public synchronized void notePlaceVisit(KnownPlace place) {
        place.visits++;
        save();
    }

    /**
     * The "[places you know]" block for the prompt, nearest first, with a compass
     * bearing so the model can talk about them sensibly. Empty when nothing is known.
     */
    public synchronized String describePlaces(Identifier dimension, BlockPos near, int limit) {
        List<KnownPlace> nearby = placesIn(dimension, near);
        if (nearby.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("[places you know]\n");
        int shown = 0;
        for (KnownPlace place : nearby) {
            if (shown++ >= Math.max(0, limit)) {
                break;
            }
            int distance = (int) Math.round(Math.sqrt(place.pos().getSquaredDistance(near)));
            out.append("- ").append(place.name).append(": ");
            if (place.contains(near)) {
                out.append("you are in it now");
            } else {
                out.append(distance).append(" blocks ").append(bearing(near, place.pos()));
                if (place.y - near.getY() >= 3) {
                    out.append(", up");
                } else if (near.getY() - place.y >= 3) {
                    out.append(", down");
                }
            }
            out.append("\n");
        }
        return out.toString();
    }

    /** Lines for /lumen memory. */
    public synchronized List<String> placeLines(int limit) {
        return data.places.stream()
                .sorted(Comparator.comparingLong((KnownPlace place) -> place.created).reversed())
                .limit(Math.max(0, limit))
                .map(place -> place.name + " at " + place.x + ", " + place.y + ", " + place.z
                        + " in " + place.dimension + " (r" + place.radius
                        + (place.taughtBy.isEmpty() ? "" : ", from " + place.taughtBy) + ")")
                .toList();
    }

    /** "north", "south-east", ... from {@code from} to {@code to}, ignoring height. */
    static String bearing(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            return "here";
        }
        // Minecraft: +z is south, +x is east.
        double angle = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = north, 90 = east
        if (angle < 0) {
            angle += 360.0D;
        }
        String[] points = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return points[(int) Math.round(angle / 45.0D) % 8];
    }

    /**
     * How well a stored place name answers to a query: 3 exact, 2 every query word is a
     * word of the name, 1 substring either way, 0 nothing. Pure, for the tests.
     */
    static int placeScore(String name, String query) {
        if (name.equals(query)) {
            return 3;
        }
        String[] queryWords = query.split(" ");
        String[] nameWords = name.split(" ");
        boolean all = queryWords.length > 0;
        for (String wanted : queryWords) {
            boolean found = false;
            for (String present : nameWords) {
                if (present.equals(wanted)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                all = false;
                break;
            }
        }
        if (all) {
            return 2;
        }
        String squashedName = name.replace(" ", "");
        String squashedQuery = query.replace(" ", "");
        if (squashedName.contains(squashedQuery) || squashedQuery.contains(squashedName)) {
            return 1;
        }
        return 0;
    }

    /**
     * "the Hops Room!" -> "hops room". Drops articles and the words people wrap a name in
     * ("this as", "call it"), so the stored name is the name and nothing else.
     */
    public static String cleanPlaceName(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 _-]+", " ")
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        // Only framing words come off. "spot", "place" and "room" stay: "the copper
        // spot" is the name the player chose, and "spot" is half of it.
        for (int i = 0; i < 4; i++) {
            String stripped = text
                    .replaceFirst("^(this|here|it|that|the|a|an|my|our|as|is|called|named)\\b\\s*", "")
                    .replaceFirst("\\s+the$", "")
                    .trim();
            if (stripped.equals(text)) {
                break;
            }
            text = stripped;
        }
        return text;
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
