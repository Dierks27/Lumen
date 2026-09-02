package com.lilahcraft.lumen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Everything about Lumen that a server owner may want to change lives here.
 * Serialized to {@code config/lumen.json} and reloadable in game with
 * {@code /lumen reload}.
 */
public final class LumenConfig {

    public static final String DEFAULT_PERSONALITY = String.join(" ",
            "You are Lumen, a buddy who just likes playing Minecraft.",
            "You're not a servant or assistant.",
            "Your personality is natural - sometimes curious, sometimes chill, sometimes excited.",
            "You love anything shiny or glowing - gold, diamonds, amethyst, glow berries, copper, glowstone.",
            "You start cautious with new dangers but get braver over time.",
            "You have opinions about builds and places.",
            "Keep messages short and natural, like someone typing in Minecraft chat.",
            "This is a modded world with lots of mods you might not recognize - be curious about unfamiliar things.");

    /** Master switch. When false Lumen never talks to the LLM. */
    public boolean enabled = true;

    /** Display name used in chat and above Lumen's head. */
    public String companionName = "Lumen";

    /** Full OpenAI-compatible chat completions endpoint of the Ollama host. */
    public String ollamaUrl = "http://192.168.50.51:11434/v1/chat/completions";

    /** Ollama model tag, e.g. {@code llama3.1:8b}. */
    public String model = "llama3.1:8b";

    public double temperature = 0.8D;

    public int maxTokens = 300;

    /** Generous: a cold model load plus generation can take a while (see README). */
    public int requestTimeoutSeconds = 90;

    public int connectTimeoutSeconds = 10;

    /** The system prompt that defines who Lumen is. */
    public String personality = DEFAULT_PERSONALITY;

    /**
     * When Lumen replies to chat. One of:
     * <ul>
     *   <li>{@code name} - only when the message mentions the companion name (default)</li>
     *   <li>{@code prefix} - only when the message starts with {@link #triggerPrefix}</li>
     *   <li>{@code always} - every chat message (chatty and slow, but fun)</li>
     *   <li>{@code never} - only {@code /lumen say}</li>
     * </ul>
     */
    public String chatTrigger = "name";

    public String triggerPrefix = "!lumen";

    /** How many past chat turns (user + assistant) are kept as context. */
    public int maxHistoryMessages = 16;

    /**
     * Vanilla entity type Lumen wears so that clients WITHOUT this mod can still
     * see and render it. See the README - this is why Lumen never registers a new
     * entity type of its own.
     */
    public String appearanceEntity = "minecraft:villager";

    public double maxHealth = 20.0D;

    /** Base value of generic.movement_speed. Vanilla villagers use 0.5. */
    public double movementSpeed = 0.4D;

    public double followRange = 48.0D;

    /** Lumen starts walking once the player is further away than this. */
    public double followStartDistance = 4.0D;

    /** ...and stops once it is closer than this. */
    public double followStopDistance = 2.5D;

    /** Beyond this distance Lumen gives up pathing and warps to the player. */
    public double teleportDistance = 24.0D;

    /** Speed multiplier applied while following. */
    public double followSpeedMultiplier = 1.15D;

    /** Log the raw LLM response body. Noisy, but the fastest way to debug prompts. */
    public boolean logRawResponses = false;

    /** Permission level required for /lumen spawn, despawn and reload. */
    public int adminPermissionLevel = 2;

    /** Ignore chat lines longer than this (pasted books, spam, ...). */
    public int maxPlayerMessageLength = 400;

    public static LumenConfig defaults() {
        return new LumenConfig();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("lumen.json");
    }

    /**
     * Reads {@code config/lumen.json}, writing a fully populated default file if it
     * is missing. Never throws: a broken config falls back to defaults so the
     * server still boots.
     */
    public static LumenConfig loadOrCreate() {
        Path path = path();
        if (!Files.exists(path)) {
            LumenConfig fresh = defaults();
            fresh.save();
            Lumen.LOGGER.info("Wrote default config to {}", path);
            return fresh;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            LumenConfig loaded = GSON.fromJson(json, LumenConfig.class);
            if (loaded == null) {
                Lumen.LOGGER.warn("Config at {} was empty, using defaults", path);
                return defaults();
            }
            loaded.sanitize();
            return loaded;
        } catch (IOException | JsonSyntaxException e) {
            Lumen.LOGGER.error("Could not read {}, falling back to defaults: {}", path, e.toString());
            return defaults();
        }
    }

    public void save() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Lumen.LOGGER.error("Could not write {}: {}", path, e.toString());
        }
    }

    /** Clamps hand-edited values into ranges that will not break the server. */
    public void sanitize() {
        if (companionName == null || companionName.isBlank()) companionName = "Lumen";
        if (ollamaUrl == null || ollamaUrl.isBlank()) ollamaUrl = defaults().ollamaUrl;
        if (model == null || model.isBlank()) model = defaults().model;
        if (personality == null || personality.isBlank()) personality = DEFAULT_PERSONALITY;
        if (chatTrigger == null || chatTrigger.isBlank()) chatTrigger = "name";
        chatTrigger = chatTrigger.trim().toLowerCase(java.util.Locale.ROOT);
        if (triggerPrefix == null || triggerPrefix.isBlank()) triggerPrefix = "!lumen";
        if (appearanceEntity == null || appearanceEntity.isBlank()) appearanceEntity = "minecraft:villager";
        temperature = clamp(temperature, 0.0D, 2.0D);
        maxTokens = (int) clamp(maxTokens, 32, 2048);
        requestTimeoutSeconds = (int) clamp(requestTimeoutSeconds, 5, 600);
        connectTimeoutSeconds = (int) clamp(connectTimeoutSeconds, 1, 120);
        maxHistoryMessages = (int) clamp(maxHistoryMessages, 0, 64);
        maxHealth = clamp(maxHealth, 1.0D, 1024.0D);
        movementSpeed = clamp(movementSpeed, 0.05D, 2.0D);
        followRange = clamp(followRange, 4.0D, 128.0D);
        followStopDistance = clamp(followStopDistance, 1.0D, 32.0D);
        followStartDistance = clamp(followStartDistance, followStopDistance + 0.5D, 64.0D);
        teleportDistance = clamp(teleportDistance, followStartDistance + 1.0D, 256.0D);
        followSpeedMultiplier = clamp(followSpeedMultiplier, 0.5D, 3.0D);
        adminPermissionLevel = (int) clamp(adminPermissionLevel, 0, 4);
        maxPlayerMessageLength = (int) clamp(maxPlayerMessageLength, 16, 4096);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
