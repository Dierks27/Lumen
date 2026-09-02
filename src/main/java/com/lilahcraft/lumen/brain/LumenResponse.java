package com.lilahcraft.lumen.brain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lilahcraft.lumen.Lumen;

/**
 * The three field object we ask the model for:
 * {@code {"reason":"...","command":"...","message":"..."}}.
 *
 * <p>Small models wander: they wrap the object in prose, in ``` fences, or drop
 * fields entirely. Parsing is therefore best effort and every field is optional -
 * a response we cannot parse at all becomes a plain chat line instead of an error.
 */
public record LumenResponse(String reason, String command, String message) {

    public boolean hasMessage() {
        return message != null && !message.isBlank();
    }

    public boolean hasCommand() {
        return command != null && !command.isBlank();
    }

    /**
     * Pulls a Lumen response out of whatever the model produced.
     *
     * @return a response, or {@code null} if the content was empty
     */
    public static LumenResponse parse(String rawContent) {
        if (rawContent == null) {
            return null;
        }
        String content = stripFences(rawContent).trim();
        if (content.isEmpty()) {
            return null;
        }

        String json = extractJsonObject(content);
        if (json != null) {
            try {
                JsonElement element = JsonParser.parseString(json);
                if (element.isJsonObject()) {
                    JsonObject object = element.getAsJsonObject();
                    return new LumenResponse(
                            readString(object, "reason"),
                            readString(object, "command"),
                            readString(object, "message"));
                }
            } catch (RuntimeException e) {
                Lumen.LOGGER.debug("Could not parse LLM JSON, treating it as plain text: {}", e.toString());
            }
        }

        // No usable JSON. Rather than dropping the turn, let Lumen say what it said.
        return new LumenResponse(null, null, content);
    }

    /** Reads a field as a string, tolerating nulls, numbers and booleans. */
    private static String readString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            String value = element.getAsString();
            return value.isBlank() ? null : value;
        }
        return element.toString();
    }

    private static String stripFences(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline < 0) {
            return trimmed;
        }
        String body = trimmed.substring(firstNewline + 1);
        int closing = body.lastIndexOf("```");
        return closing >= 0 ? body.substring(0, closing) : body;
    }

    /** Returns the outermost balanced {@code { ... }} block, or null if there is none. */
    private static String extractJsonObject(String content) {
        int start = content.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
