package com.lilahcraft.lumen.brain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Several things in one breath, and a place named at the end. */
class PhrasingTest {

    @Test
    @DisplayName("then splits a request into steps, in order")
    void splitsOnThen() {
        assertEquals(List.of("grab me some iron", "go mine some copper", "come back"),
                Phrasing.splitCompound("grab me some iron, then go mine some copper, then come back"));
        assertEquals(List.of("find iron", "mine copper", "come"),
                Phrasing.splitCompound("find iron then mine copper then come"));
        assertEquals(List.of("find iron", "mine copper"),
                Phrasing.splitCompound("find iron and then mine copper"));
        assertEquals(List.of("find iron", "mine copper"),
                Phrasing.splitCompound("find iron; mine copper"));
    }

    @Test
    @DisplayName("and splits only in front of a new instruction")
    void splitsOnAndBeforeVerbs() {
        assertEquals(List.of("go to the hops room", "grab me some hops"),
                Phrasing.splitCompound("go to the hops room and grab me some hops"));
        // "and" inside an item name is not a separator.
        assertEquals(List.of("find black and white wool"),
                Phrasing.splitCompound("find black and white wool"));
        assertEquals(List.of("find sand and gravel"),
                Phrasing.splitCompound("find sand and gravel"));
    }

    @Test
    @DisplayName("a single request is itself")
    void singleRequestIsUnchanged() {
        assertEquals(List.of("find 12 redstone"), Phrasing.splitCompound("find 12 redstone"));
        assertEquals(List.of("come"), Phrasing.splitCompound("come."));
        assertTrue(Phrasing.splitCompound("").isEmpty());
        assertTrue(Phrasing.splitCompound(null).isEmpty());
    }

    @Test
    @DisplayName("a trailing place is peeled off")
    void splitsPlaceReference() {
        Phrasing.PlaceRef ref = Phrasing.splitPlaceReference("12 redstone from the storage room");
        assertEquals("12 redstone", ref.rest());
        assertEquals("storage room", ref.place());
        assertEquals("copper", Phrasing.splitPlaceReference("copper near the copper spot").rest());
        assertEquals("copper spot", Phrasing.splitPlaceReference("copper near the copper spot").place());
        assertEquals("hops", Phrasing.splitPlaceReference("hops in my hops room").rest());
        assertEquals("hops room", Phrasing.splitPlaceReference("hops in my hops room").place());
    }

    @Test
    @DisplayName("no place means no split")
    void noPlaceReference() {
        Phrasing.PlaceRef ref = Phrasing.splitPlaceReference("iron ore");
        assertEquals("iron ore", ref.rest());
        assertNull(ref.place());
        assertEquals("", Phrasing.splitPlaceReference(null).rest());
    }

    @Test
    @DisplayName("the framing words around a remembered name are dropped")
    void placeNameFromRemember() {
        assertEquals("the hops room", Phrasing.placeNameFromRemember("this as the hops room"));
        assertEquals("home", Phrasing.placeNameFromRemember("this spot as home"));
        assertEquals("copper spot", Phrasing.placeNameFromRemember("where i am is called copper spot"));
        assertEquals("hops room", Phrasing.placeNameFromRemember("hops room"));
    }
}
