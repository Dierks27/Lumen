package com.lilahcraft.lumen.brain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    @DisplayName("null and empty are safe")
    void handlesEmpty() {
        assertEquals("", LumenBrain.normaliseCommand(null));
        assertEquals("", LumenBrain.normaliseCommand("   "));
    }
}
