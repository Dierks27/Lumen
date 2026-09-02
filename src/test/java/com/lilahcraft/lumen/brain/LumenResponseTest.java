package com.lilahcraft.lumen.brain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model is an 8B local llama: it will wrap the object in fences, chat around it,
 * drop fields and send nulls. None of that may reach the rest of the mod as an
 * exception, so every shape below is pinned down here.
 */
class LumenResponseTest {

    @Test
    @DisplayName("the happy path yields all three fields")
    void parsesCleanObject() {
        LumenResponse response = LumenResponse.parse(
                "{\"reason\":\"they asked me to\",\"command\":\"follow Dierks\",\"message\":\"coming!\"}");

        assertEquals("they asked me to", response.reason());
        assertEquals("follow Dierks", response.command());
        assertEquals("coming!", response.message());
        assertTrue(response.hasMessage());
        assertTrue(response.hasCommand());
    }

    @Test
    @DisplayName("markdown code fences are stripped")
    void parsesFencedObject() {
        LumenResponse response = LumenResponse.parse(
                "```json\n{\"reason\":\"r\",\"command\":\"idle\",\"message\":\"hey\"}\n```");

        assertEquals("idle", response.command());
        assertEquals("hey", response.message());
    }

    @Test
    @DisplayName("prose around the object is ignored")
    void parsesObjectSurroundedByProse() {
        LumenResponse response = LumenResponse.parse(
                "Sure! Here is my response:\n{\"reason\":\"r\",\"command\":\"come\",\"message\":\"omw\"}\nHope that helps.");

        assertEquals("come", response.command());
        assertEquals("omw", response.message());
    }

    @Test
    @DisplayName("null fields become nulls, not NullPointerExceptions")
    void tolerantOfNullFields() {
        LumenResponse response = LumenResponse.parse(
                "{\"reason\":null,\"command\":null,\"message\":null}");

        assertNull(response.reason());
        assertNull(response.command());
        assertNull(response.message());
        assertFalse(response.hasMessage());
        assertFalse(response.hasCommand());
    }

    @Test
    @DisplayName("missing fields become nulls")
    void tolerantOfMissingFields() {
        LumenResponse response = LumenResponse.parse("{\"message\":\"just talking\"}");

        assertNull(response.reason());
        assertNull(response.command());
        assertEquals("just talking", response.message());
    }

    @Test
    @DisplayName("blank fields count as absent")
    void treatsBlankFieldsAsAbsent() {
        LumenResponse response = LumenResponse.parse("{\"command\":\"   \",\"message\":\"\"}");

        assertFalse(response.hasCommand());
        assertFalse(response.hasMessage());
    }

    @Test
    @DisplayName("plain prose degrades to a chat line instead of being dropped")
    void fallsBackToPlainText() {
        LumenResponse response = LumenResponse.parse("ooh is that amethyst?");

        assertEquals("ooh is that amethyst?", response.message());
        assertFalse(response.hasCommand());
    }

    @Test
    @DisplayName("broken JSON degrades to a chat line rather than throwing")
    void fallsBackOnMalformedJson() {
        LumenResponse response = LumenResponse.parse("{\"message\":\"unterminated");

        assertTrue(response.hasMessage());
    }

    @Test
    @DisplayName("braces inside strings do not confuse the extractor")
    void handlesBracesInsideStrings() {
        LumenResponse response = LumenResponse.parse(
                "{\"reason\":\"r\",\"command\":\"idle\",\"message\":\"i like {curly} braces\"}");

        assertEquals("i like {curly} braces", response.message());
    }

    @Test
    @DisplayName("escaped quotes inside strings do not confuse the extractor")
    void handlesEscapedQuotes() {
        LumenResponse response = LumenResponse.parse(
                "{\"reason\":\"r\",\"command\":\"idle\",\"message\":\"he said \\\"hi\\\" to me\"}");

        assertEquals("he said \"hi\" to me", response.message());
    }

    @Test
    @DisplayName("nested objects are extracted whole")
    void handlesNestedObjects() {
        LumenResponse response = LumenResponse.parse(
                "{\"reason\":\"r\",\"extra\":{\"a\":1},\"command\":\"idle\",\"message\":\"ok\"}");

        assertEquals("idle", response.command());
        assertEquals("ok", response.message());
    }

    @Test
    @DisplayName("non-string field values are coerced rather than rejected")
    void coercesNonStringFields() {
        LumenResponse response = LumenResponse.parse(
                "{\"reason\":7,\"command\":\"idle\",\"message\":true}");

        assertEquals("7", response.reason());
        assertEquals("true", response.message());
    }

    @Test
    @DisplayName("empty and null input yield no response at all")
    void returnsNullForEmptyInput() {
        assertNull(LumenResponse.parse(null));
        assertNull(LumenResponse.parse(""));
        assertNull(LumenResponse.parse("   \n  "));
    }
}
