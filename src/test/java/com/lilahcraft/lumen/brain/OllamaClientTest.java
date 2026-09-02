package com.lilahcraft.lumen.brain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ollama can be pointed at three different endpoints; all of them should work. */
class OllamaClientTest {

    @Test
    @DisplayName("reads the OpenAI compatible /v1/chat/completions shape")
    void readsOpenAiShape() {
        String body = "{\"choices\":[{\"index\":0,\"message\":"
                + "{\"role\":\"assistant\",\"content\":\"hello there\"}}]}";

        assertEquals("hello there", OllamaClient.extractContent(body));
    }

    @Test
    @DisplayName("reads the native /api/chat shape")
    void readsNativeChatShape() {
        assertEquals("hello there",
                OllamaClient.extractContent("{\"message\":{\"role\":\"assistant\",\"content\":\"hello there\"}}"));
    }

    @Test
    @DisplayName("reads the native /api/generate shape")
    void readsNativeGenerateShape() {
        assertEquals("hello there", OllamaClient.extractContent("{\"response\":\"hello there\"}"));
    }

    @Test
    @DisplayName("surfaces an error field as an exception")
    void surfacesErrors() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> OllamaClient.extractContent("{\"error\":\"model 'llama3.1:8b' not found\"}"));

        assertTrue(thrown.getMessage().contains("not found"));
    }

    @Test
    @DisplayName("an unrecognised body is an error, not a silent empty reply")
    void rejectsUnknownShape() {
        assertThrows(IllegalStateException.class, () -> OllamaClient.extractContent("{\"unexpected\":1}"));
        assertThrows(IllegalStateException.class, () -> OllamaClient.extractContent("{\"choices\":[]}"));
    }
}
