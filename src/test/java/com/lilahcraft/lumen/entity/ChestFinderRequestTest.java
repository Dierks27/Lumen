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
    @DisplayName("a stack is left to be sized against the real item, not assumed to be 64")
    void parsesStacks() {
        // 64 for cobblestone, but 16 for ender pearls and 1 for a pickaxe - so the
        // amount stays expressed in stacks until the item is actually in hand.
        assertEquals(1.0D, parse("a stack of cobblestone").stacks());
        assertEquals("cobblestone", parse("a stack of cobblestone").query());
        assertEquals(1.0D, parse("stack of oak planks").stacks());
        assertEquals(0.5D, parse("half a stack of iron").stacks());
        assertEquals("iron", parse("half a stack of iron").query());
        assertEquals(3.0D, parse("3 stacks of stone").stacks());
        assertEquals("stone", parse("3 stacks of stone").query());
    }

    @Test
    @DisplayName("a plain number is a number of items, not stacks")
    void plainNumbersAreNotStacks() {
        assertEquals(0.0D, parse("10 stone").stacks());
        assertEquals(10, parse("10 stone").count());
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
