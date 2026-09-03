package com.lilahcraft.lumen.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a remembered container is worth walking back to comes down to this match.
 * Too loose and Lumen walks to the wrong chest; too tight and the memory never fires
 * because people do not phrase a request the same way twice.
 */
class LumenMemoryTest {

    private static boolean matches(String storedQuery, String storedItemId, String asked) {
        return LumenMemory.looksLike(storedQuery, storedItemId, LumenMemory.normalise(asked));
    }

    @Test
    @DisplayName("the same request finds the same container again")
    void matchesTheOriginalRequest() {
        assertTrue(matches("iron", "minecraft:iron_ingot", "iron"));
    }

    @Test
    @DisplayName("a request matches on the item that satisfied it last time")
    void matchesOnTheStoredItem() {
        // Asked for "iron", got iron ingots. Asking for "iron_ingot" should still hit.
        assertTrue(matches("iron", "minecraft:iron_ingot", "iron_ingot"));
        assertTrue(matches("iron", "minecraft:iron_ingot", "ingot"));
    }

    @Test
    @DisplayName("phrasing differences do not lose the memory")
    void toleratesPhrasing() {
        assertTrue(matches("iron_ingot", "minecraft:iron_ingot", "Iron Ingot"));
        assertTrue(matches("iron_ingot", "minecraft:iron_ingot", "  IRON  "));
    }

    @Test
    @DisplayName("modded items are matched on their path, not their namespace")
    void matchesModdedItems() {
        assertTrue(matches("ruby", "createaddition:ruby_ingot", "ruby"));
        // The namespace must not be what makes it match, or every modded item would.
        assertFalse(matches("ruby", "createaddition:ruby_ingot", "create"));
    }

    @Test
    @DisplayName("unrelated requests do not reuse a container")
    void doesNotMatchUnrelated() {
        assertFalse(matches("iron", "minecraft:iron_ingot", "diamond"));
        assertFalse(matches("oak_log", "minecraft:oak_log", "stone"));
    }

    @Test
    @DisplayName("an empty request never matches anything")
    void emptyQueryMatchesNothing() {
        assertFalse(matches("iron", "minecraft:iron_ingot", ""));
        assertFalse(matches("iron", "minecraft:iron_ingot", "   "));
    }

    @Test
    @DisplayName("requests are normalised the way they are stored")
    void normalisesConsistently() {
        assertEquals("iron_ingot", LumenMemory.normalise("  Iron Ingot "));
        assertEquals("", LumenMemory.normalise(null));
    }

    // ---- named places ----

    @Test
    @DisplayName("place names are cleaned to the name and nothing else")
    void cleansPlaceNames() {
        assertEquals("hops room", LumenMemory.cleanPlaceName("the Hops Room!"));
        assertEquals("hops room", LumenMemory.cleanPlaceName("this as the hops room"));
        assertEquals("hops room", LumenMemory.cleanPlaceName("hops_room"));
        assertEquals("home", LumenMemory.cleanPlaceName("home"));
        assertEquals("copper spot", LumenMemory.cleanPlaceName("the copper spot"));
        assertEquals("", LumenMemory.cleanPlaceName(null));
        assertEquals("", LumenMemory.cleanPlaceName("this"));
    }

    @Test
    @DisplayName("place matching is tiered: exact, every word, then substring")
    void scoresPlaces() {
        assertEquals(3, LumenMemory.placeScore("hops room", "hops room"));
        assertEquals(2, LumenMemory.placeScore("hops room", "hops"));
        assertEquals(2, LumenMemory.placeScore("upper hops room", "hops room"));
        assertEquals(1, LumenMemory.placeScore("hops room", "hopsroom"));
        assertEquals(1, LumenMemory.placeScore("hops room", "ops"));
        assertEquals(0, LumenMemory.placeScore("hops room", "copper"));
        // "room" alone matches every room; a whole-word hit is still only tier 2.
        assertEquals(2, LumenMemory.placeScore("storage room", "room"));
    }

    @Test
    @DisplayName("bearings use minecraft's axes: +z is south, +x is east")
    void bearings() {
        net.minecraft.util.math.BlockPos origin = new net.minecraft.util.math.BlockPos(0, 64, 0);
        assertEquals("north", LumenMemory.bearing(origin, new net.minecraft.util.math.BlockPos(0, 64, -10)));
        assertEquals("south", LumenMemory.bearing(origin, new net.minecraft.util.math.BlockPos(0, 64, 10)));
        assertEquals("east", LumenMemory.bearing(origin, new net.minecraft.util.math.BlockPos(10, 64, 0)));
        assertEquals("west", LumenMemory.bearing(origin, new net.minecraft.util.math.BlockPos(-10, 64, 0)));
        assertEquals("north-east", LumenMemory.bearing(origin, new net.minecraft.util.math.BlockPos(10, 64, -10)));
        assertEquals("here", LumenMemory.bearing(origin, origin));
    }
}
