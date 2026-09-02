package com.lilahcraft.lumen.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How people actually ask for an amount of something. */
class ChestFinderRequestTest {

    private static ChestFinder.Request parse(String text) {
        return ChestFinder.parseRequest(text, 64);
    }

    @Test
    @DisplayName("a bare item takes the default amount")
    void defaultsWhenNoAmountGiven() {
        assertEquals(64, parse("iron").count());
        assertEquals("iron", parse("iron").query());
    }

    @Test
    @DisplayName("a plain number")
    void parsesNumbers() {
        assertEquals(10, parse("10 stone").count());
        assertEquals("stone", parse("10 stone").query());
        assertEquals(3, parse("3 redstone torches").count());
        assertEquals("redstone torches", parse("3 redstone torches").query());
    }

    @Test
    @DisplayName("a stack, and half a stack")
    void parsesStacks() {
        assertEquals(64, parse("a stack of cobblestone").count());
        assertEquals("cobblestone", parse("a stack of cobblestone").query());
        assertEquals(64, parse("stack of oak planks").count());
        assertEquals(32, parse("half a stack of iron").count());
        assertEquals("iron", parse("half a stack of iron").query());
    }

    @Test
    @DisplayName("all of something")
    void parsesAll() {
        assertEquals(Integer.MAX_VALUE, parse("all the sticks").count());
        assertEquals("sticks", parse("all the sticks").query());
        assertEquals("iron", parse("all of the iron").query());
        assertEquals(Integer.MAX_VALUE, parse("all iron").count());
    }

    @Test
    @DisplayName("an empty request yields an empty query, not a wildcard")
    void handlesEmpty() {
        assertEquals("", parse("").query());
        assertEquals("", parse(null).query());
    }
}
