package com.lilahcraft.lumen.brain;

/** One entry of the OpenAI-style {@code messages} array. */
public record ChatMessage(String role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}
