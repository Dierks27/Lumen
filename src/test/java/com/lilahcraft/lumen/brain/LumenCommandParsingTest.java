package com.lilahcraft.lumen.brain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The shapes a local model actually emits in the {@code command} field.
 *
 * <p>v0.3.0 only matched a handful of exact strings, so most of these fell through to
 * "unrecognised" and nothing happened - which read from chat as the command system
 * being broken. Every case here was seen or is a near neighbour of one that was.
 */
class LumenCommandParsingTest {

    private static String verb(String raw) {
        String parsed = LumenBrain.normaliseCommand(raw);
        return parsed.split(" ", 2)[0];
    }

    private static String argument(String raw) {
        String parsed = LumenBrain.normaliseCommand(raw);
        String[] parts = parsed.split(" ", 2);
        return LumenBrain.stripFiller(parts.length > 1 ? parts[1] : "");
    }

    @Test
    @DisplayName("the plain forms still work")
    void parsesPlainCommands() {
        assertEquals("idle", verb("idle"));
        assertEquals("come", verb("come"));
        assertEquals("follow", verb("follow Dierks"));
        assertEquals("dierks", argument("follow Dierks"));
    }

    @Test
    @DisplayName("underscores instead of spaces")
    void parsesUnderscoreForm() {
        assertEquals("find", verb("find_iron_ore"));
        assertEquals("iron ore", argument("find_iron_ore"));
        assertEquals("mine", verb("mine_iron"));
    }

    @Test
    @DisplayName("quotes, backticks and a leading slash")
    void stripsWrappers() {
        assertEquals("come", verb("`come`"));
        assertEquals("come", verb("\"come\""));
        assertEquals("mine", verb("/mine iron"));
        assertEquals("iron", argument("/mine iron"));
    }

    @Test
    @DisplayName("trailing punctuation")
    void stripsTrailingPunctuation() {
        assertEquals("come", verb("come."));
        assertEquals("idle", verb("idle!"));
        assertEquals("iron", argument("find iron."));
    }

    @Test
    @DisplayName("leading filler the model likes to add")
    void stripsLeadingFiller() {
        assertEquals("mine", verb("go mine some iron"));
        assertEquals("iron", argument("go mine some iron"));
        assertEquals("come", verb("please come"));
        assertEquals("follow", verb("lumen, follow me"));
    }

    @Test
    @DisplayName("'go' is stripped but 'goto' is not mangled by it")
    void doesNotEatGoto() {
        assertEquals("goto", verb("goto"));
        assertEquals("goto", verb("goto Dierks"));
    }

    @Test
    @DisplayName("filler in the argument is dropped")
    void stripsArgumentFiller() {
        assertEquals("iron", argument("find me some iron"));
        assertEquals("iron", argument("bring me the iron"));
        assertEquals("", argument("follow me"));
        assertEquals("", argument("come to me"));
    }

    @Test
    @DisplayName("multi-word items survive")
    void keepsMultiWordItems() {
        assertEquals("dark oak planks", argument("find some dark oak planks"));
        assertEquals("deepslate iron ore", argument("mine deepslate_iron_ore"));
    }

    @Test
    @DisplayName("whitespace and case are normalised")
    void normalisesWhitespaceAndCase() {
        assertEquals("find", verb("  FIND   iron  "));
        assertEquals("iron", argument("  FIND   iron  "));
    }

    // ---- the fallback for when the model chats but sends no command field ----

    @Test
    @DisplayName("a real request from chat is understood when the model omits the command")
    void infersFromRealRequests() {
        // Verbatim from the Homestead v0.4.0 session, where the model replied
        // "On it! Heading to get you some stone" with command field "(none)".
        assertEquals("get me 64 stone",
                LumenBrain.inferCommandFromRequest("hey buddy can you go get me 64 stone"));
        assertEquals("find me sticks",
                LumenBrain.inferCommandFromRequest("okay buddy can you find me sticks"));
        assertEquals("mine this stone",
                LumenBrain.inferCommandFromRequest("can you mine this stone"));
    }

    @Test
    @DisplayName("asking for something back is a give")
    void infersGiveBack() {
        assertEquals("give me the sword", LumenBrain.inferCommandFromRequest("give me the sword"));
        assertEquals("give me the sword back", LumenBrain.inferCommandFromRequest("can i have the sword back"));
        assertEquals("hand me my pickaxe", LumenBrain.inferCommandFromRequest("hand me my pickaxe"));
    }

    @Test
    @DisplayName("the amount the player said survives a command that dropped it")
    void keepsAmountFromChat() {
        // "grab me 12 redstone" came back from the model as "find redstone" and 48 were taken.
        assertEquals(12, LumenBrain.resolveFetchRequest("redstone", "grab me 12 redstone", 64).count());
        assertEquals("redstone", LumenBrain.resolveFetchRequest("redstone", "grab me 12 redstone", 64).query());
        // A command that names its own amount wins over the sentence.
        assertEquals(5, LumenBrain.resolveFetchRequest("5 redstone", "grab me 12 redstone", 64).count());
        // Nothing said either way: the default.
        assertEquals(64, LumenBrain.resolveFetchRequest("redstone", "get me some redstone", 64).count());
        assertEquals(1.0D, LumenBrain.resolveFetchRequest("stone", "bring a stack of stone please", 64).stacks());
    }

    @Test
    @DisplayName("ordinary conversation is not mistaken for an instruction")
    void doesNotInferFromChatter() {
        assertNull(LumenBrain.inferCommandFromRequest("nice build buddy"));
        assertNull(LumenBrain.inferCommandFromRequest("i'll go get some iron myself"));
        assertNull(LumenBrain.inferCommandFromRequest("what do you think of the roof"));
        assertNull(LumenBrain.inferCommandFromRequest(""));
        assertNull(LumenBrain.inferCommandFromRequest(null));
    }

    @Test
    @DisplayName("null and empty are safe")
    void handlesEmpty() {
        assertEquals("", LumenBrain.normaliseCommand(null));
        assertEquals("", LumenBrain.normaliseCommand("   "));
    }
}
