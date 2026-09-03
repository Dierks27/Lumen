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
    public String model = "qwen2.5:14b";

    public double temperature = 0.8D;

    public int maxTokens = 300;

    /**
     * Ask the server to guarantee the reply is valid JSON. Disabled automatically for
     * the session if the Ollama build rejects it.
     */
    public boolean jsonMode = true;

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
    public String chatTrigger = "always";

    public String triggerPrefix = "!lumen";

    /** How many past chat turns (user + assistant) are kept as context. */
    public int maxHistoryMessages = 24;

    /**
     * Remember chat that was not addressed to Lumen, so a reply lands in the middle
     * of a conversation rather than out of nowhere. Also keeps messages that arrive
     * while a request is already in flight instead of dropping them.
     */
    public boolean rememberUntriggeredChat = true;

    // --------------------------------------------------------------- awareness

    /** Horizontal radius of the block scan fed to the model. Cost grows with the cube. */
    public int awarenessBlockRadius = 8;

    /** Vertical half-height of that scan. */
    public int awarenessBlockHeight = 4;

    /** How many distinct block types to name. */
    public int maxListedBlockTypes = 8;

    /** Radius for spotting mobs, players and dropped items. */
    public double awarenessEntityRadius = 24.0D;

    // -------------------------------------------------------------- navigation

    /** Path through and open wooden doors, the way a villager does. */
    public boolean canOpenDoors = true;

    /** Ticks of no progress toward a goal before Lumen recalculates its path. */
    public int stuckRepathTicks = 60;

    /** Ticks of no progress before Lumen gives up and warps to its target. */
    public int stuckTeleportTicks = 160;

    // --------------------------------------------------------------- inventory

    /**
     * Slots in Lumen's pack: 27 or 45. The pack screen adds a row underneath showing
     * what Lumen is holding and wearing, so the screen is a 9x4 or 9x6 chest - the
     * shapes a vanilla client can draw.
     */
    public int inventorySize = 27;

    /** Eat from the pack when hurt. Lumen has no hunger bar; food simply heals. */
    public boolean eatWhenHurt = true;

    /** Eat once health drops below this fraction of maximum. */
    public double eatHealthFraction = 0.6D;

    /** Right-clicking Lumen while holding something hands it over. */
    public boolean acceptItemsFromPlayers = true;

    /** Collect items lying on the ground. */
    public boolean pickUpItems = true;

    public double pickUpRadius = 8.0D;

    /** Drop everything Lumen was carrying when it dies. */
    public boolean dropInventoryOnDeath = true;

    /** Let Lumen take requested items out of nearby containers. */
    public boolean allowChestAccess = true;

    /** How far Lumen will look for a container holding what was asked for. */
    public double chestSearchRadius = 48.0D;

    /**
     * How far away a remembered container is still worth walking to. Larger than the
     * search radius on purpose: Lumen knows exactly where this one is.
     */
    public double memoryRecallRadius = 128.0D;

    /** How many items to fetch when the request does not say. */
    public int defaultFetchCount = 64;

    /** Hard ceiling on one errand, whatever was asked for. */
    public int maxFetchItems = 640;

    // ------------------------------------------------------------------ mining

    /** Let Lumen break blocks on request. Turning this off disables the mine command. */
    public boolean allowMining = true;

    /** How far Lumen will look for something to mine. Cost grows with the cube. */
    public double miningRadius = 12.0D;

    /** Vertical half-height of that search. */
    public int miningHeight = 8;

    /** Blocks broken per errand before Lumen brings the haul back. */
    public int maxMineBlocks = 8;

    // ------------------------------------------------------------------ skills

    /** Let taught skills right-click blocks, done in the name of whoever asked. */
    public boolean allowInteract = true;

    /** The player must be within this many blocks for Lumen to use a block on their behalf. */
    public double interactRange = 32.0D;

    /** Blocks a single run of a skill may work through. */
    public int maxSkillBlocks = 32;

    /** Let players teach skills in chat and with /lumen teach. */
    public boolean allowTeaching = true;

    // ------------------------------------------------------------------ quarry

    /** Let Lumen dig out regions ("mine out a 20x20x2", the wand selection). */
    public boolean allowQuarry = true;

    /** Blocks per quarry job, whatever was asked for. */
    public int maxQuarryBlocks = 512;

    /** Longest side of a region, in blocks. */
    public int maxQuarrySize = 32;

    /** How far below its feet Lumen will dig a staircase to reach a region. */
    public int maxQuarryDescent = 40;

    // ---------------------------------------------------------------- crafting

    public boolean allowCrafting = true;

    /** Recipes bigger than 2x2 need a crafting table within reach, like a player. */
    public boolean craftingNeedsTable = true;

    // ---------------------------------------------------------------- survival

    /** Hostile mobs nearby with nothing else to fight go for Lumen. */
    public boolean hostilesAttackLumen = true;

    public double aggroRadius = 10.0D;

    /** Lumen gets hungry over real time, slows down when starving, and eats from its pack. */
    public boolean hungerEnabled = true;

    /** Seconds of real time per point of food lost; 20 points, so 240 is 80 minutes to empty. */
    public int hungerDecaySeconds = 240;

    /** Show hearts in the name tag - a vanilla client draws no health bar for a mob. */
    public boolean showHealthInName = true;

    /** Real minutes before /lumen spawn works again after a death. 0 disables the cooldown. */
    public int respawnCooldownMinutes = 120;

    // ------------------------------------------------------------------- notes

    /** Summarise the conversation into notes that survive a restart. */
    public boolean conversationNotes = true;

    /** How many player messages between summaries. */
    public int notesEveryMessages = 30;

    // ------------------------------------------------------------------ combat

    /**
     * Fight hostile mobs near Lumen or the player it follows. Off by default: v0.3.0
     * shipped an aggro leak that killed a player, and while that path is now closed
     * three ways over, turning it back on should be a deliberate choice.
     */
    public boolean combat = false;

    /** Base damage per hit. Weapons Lumen is holding add to this. */
    public double attackDamage = 3.0D;

    /** Only defend against hostiles within this range. */
    public double defendRadius = 12.0D;

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
        awarenessBlockRadius = (int) clamp(awarenessBlockRadius, 0, 24);
        awarenessBlockHeight = (int) clamp(awarenessBlockHeight, 0, 16);
        maxListedBlockTypes = (int) clamp(maxListedBlockTypes, 0, 24);
        awarenessEntityRadius = clamp(awarenessEntityRadius, 0.0D, 128.0D);
        stuckRepathTicks = (int) clamp(stuckRepathTicks, 20, 1200);
        stuckTeleportTicks = (int) clamp(stuckTeleportTicks, stuckRepathTicks + 20, 2400);
        // Shown in a vanilla chest screen with an equipment row underneath, so the
        // pack itself is 27 (9x4 screen) or 45 (9x6 screen). Older configs said 54.
        inventorySize = inventorySize > 27 ? 45 : 27;
        eatHealthFraction = clamp(eatHealthFraction, 0.0D, 1.0D);
        pickUpRadius = clamp(pickUpRadius, 0.0D, 32.0D);
        miningRadius = clamp(miningRadius, 0.0D, 32.0D);
        miningHeight = (int) clamp(miningHeight, 0, 32);
        maxMineBlocks = (int) clamp(maxMineBlocks, 1, 64);
        chestSearchRadius = clamp(chestSearchRadius, 0.0D, 256.0D);
        memoryRecallRadius = clamp(memoryRecallRadius, 0.0D, 512.0D);
        defaultFetchCount = (int) clamp(defaultFetchCount, 1, 2304);
        maxFetchItems = (int) clamp(maxFetchItems, 1, 2304);
        attackDamage = clamp(attackDamage, 0.0D, 100.0D);
        defendRadius = clamp(defendRadius, 0.0D, 64.0D);
        adminPermissionLevel = (int) clamp(adminPermissionLevel, 0, 4);
        interactRange = clamp(interactRange, 4.0D, 128.0D);
        maxSkillBlocks = (int) clamp(maxSkillBlocks, 1, 256);
        maxQuarryBlocks = (int) clamp(maxQuarryBlocks, 1, 4096);
        maxQuarrySize = (int) clamp(maxQuarrySize, 1, 64);
        maxQuarryDescent = (int) clamp(maxQuarryDescent, 0, 128);
        aggroRadius = clamp(aggroRadius, 0.0D, 32.0D);
        hungerDecaySeconds = (int) clamp(hungerDecaySeconds, 10, 86400);
        respawnCooldownMinutes = (int) clamp(respawnCooldownMinutes, 0, 10080);
        notesEveryMessages = (int) clamp(notesEveryMessages, 5, 500);
        maxPlayerMessageLength = (int) clamp(maxPlayerMessageLength, 16, 4096);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
