package com.lilahcraft.lumen.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How people actually ask for an amount of something. */
class ChestFinderRequestTest {

    private static ChestFinder.Request parse(String text) {
        return ChestFinder.parseRequest(text, 64);
    }

    @Test
    @DisplayName("a bare item takes the default amount, and says so")
    void defaultsWhenNoAmountGiven() {
        assertEquals(64, parse("iron").count());
        assertEquals("iron", parse("iron").query());
        assertFalse(parse("iron").explicit());
    }

    @Test
    @DisplayName("a plain number")
    void parsesNumbers() {
        assertEquals(10, parse("10 stone").count());
        assertEquals("stone", parse("10 stone").query());
        assertTrue(parse("10 stone").explicit());
        assertEquals(3, parse("3 redstone torches").count());
        assertEquals("redstone torches", parse("3 redstone torches").query());
    }

    @Test
    @DisplayName("12x and x12 forms, and a trailing number")
    void parsesMultiplierForms() {
        assertEquals(12, parse("12x redstone").count());
        assertEquals("redstone", parse("12x redstone").query());
        assertEquals(12, parse("redstone x12").count());
        assertEquals("redstone", parse("redstone x12").query());
        assertEquals(12, parse("redstone 12").count());
        assertEquals("redstone", parse("redstone 12").query());
        assertEquals(5, parse("oak planks (5)").count());
        assertEquals("oak planks", parse("oak planks (5)").query());
    }

    @Test
    @DisplayName("a dozen, a couple, a few")
    void parsesWords() {
        assertEquals(12, parse("a dozen wool").count());
        assertEquals("wool", parse("a dozen wool").query());
        assertEquals(12, parse("dozen white wool").count());
        assertEquals(2, parse("a couple of torches").count());
        assertEquals("torches", parse("a couple of torches").query());
        assertEquals(3, parse("a few sticks").count());
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
        assertTrue(parse("all iron").isEverything());
    }

    @Test
    @DisplayName("an empty request yields an empty query, not a wildcard")
    void handlesEmpty() {
        assertEquals("", parse("").query());
        assertEquals("", parse(null).query());
    }

    // ---- reading the amount back off what the player said ----

    @Test
    @DisplayName("an amount is found anywhere in a sentence")
    void findsQuantityInChat() {
        // Verbatim from the Homestead v0.5.1 session: the model turned each of these
        // into a command with no number in it.
        assertEquals(12, ChestFinder.quantityIn("grab me 12 redstone").count());
        assertEquals(10, ChestFinder.quantityIn("do we have 10 snow").count());
        assertEquals(10, ChestFinder.quantityIn("hey can you get me 10 dark oak logs?").count());
        assertEquals(1.0D, ChestFinder.quantityIn("bring me a stack of stone").stacks());
        assertEquals(12, ChestFinder.quantityIn("fetch a dozen wool").count());
        assertEquals(2.0D, ChestFinder.quantityIn("i need 2 stacks of cobble").stacks());
        assertEquals(Integer.MAX_VALUE, ChestFinder.quantityIn("bring all the sticks").count());
    }

    @Test
    @DisplayName("coordinates and item names are not mistaken for amounts")
    void ignoresNonAmounts() {
        assertNull(ChestFinder.quantityIn("go get some iron"));
        assertNull(ChestFinder.quantityIn("meet me at -1569, 97, -27"));
        assertNull(ChestFinder.quantityIn(""));
        assertNull(ChestFinder.quantityIn(null));
    }

    @Test
    @DisplayName("the chat amount fills in only when the command had none")
    void chatAmountAppliesToCommandItem() {
        ChestFinder.Request fromCommand = parse("redstone");
        ChestFinder.Request fromChat = ChestFinder.quantityIn("grab me 12 redstone");
        assertNotNull(fromChat);
        ChestFinder.Request merged = fromChat.applyAmountTo(fromCommand);
        assertEquals(12, merged.count());
        assertEquals("redstone", merged.query());
        assertTrue(merged.explicit());
    }

    // ---- words, plurals and "everything" ----

    @Test
    @DisplayName("whole-word matching keeps stone away from cobblestone")
    void matchesWholeWords() {
        assertTrue(ChestFinder.wordsMatch("stone", "stone_bricks"));
        assertTrue(ChestFinder.wordsMatch("stone", "smooth_stone"));
        assertFalse(ChestFinder.wordsMatch("stone", "cobblestone"));
        assertFalse(ChestFinder.wordsMatch("stone", "redstone"));
        assertTrue(ChestFinder.wordsMatch("iron", "iron_ingot"));
        assertTrue(ChestFinder.wordsMatch("dark_oak_logs", "dark_oak_log"));
        assertTrue(ChestFinder.wordsMatch("torches", "Redstone Torch"));
        assertTrue(ChestFinder.wordsMatch("wool", "white_wool"));
        assertFalse(ChestFinder.wordsMatch("oak planks", "dark_oak_log"));
    }

    @Test
    @DisplayName("plurals")
    void singulars() {
        assertEquals("log", ChestFinder.singular("logs"));
        assertEquals("torch", ChestFinder.singular("torches"));
        assertEquals("berry", ChestFinder.singular("berries"));
        assertEquals("glass", ChestFinder.singular("glass"));
        assertEquals("box", ChestFinder.singular("boxes"));
    }

    @Test
    @DisplayName("everything-words are not item names")
    void everythingWords() {
        assertTrue(ChestFinder.meansEverything(null));
        assertTrue(ChestFinder.meansEverything(""));
        assertTrue(ChestFinder.meansEverything("everything"));
        assertTrue(ChestFinder.meansEverything("my stuff"));
        assertTrue(ChestFinder.meansEverything("it all back"));
        assertFalse(ChestFinder.meansEverything("sword"));
        assertFalse(ChestFinder.meansEverything("the sword back"));
    }

    @Test
    @DisplayName("requests read back in plain words")
    void describesRequests() {
        assertEquals("12 redstone", ChestFinder.describeRequest(parse("12 redstone")));
        assertEquals("a stack of stone", ChestFinder.describeRequest(parse("a stack of stone")));
        assertEquals("half a stack of iron", ChestFinder.describeRequest(parse("half a stack of iron")));
        assertEquals("3 stacks of stone", ChestFinder.describeRequest(parse("3 stacks of stone")));
        assertEquals("all the sticks", ChestFinder.describeRequest(parse("all the sticks")));
    }
}
