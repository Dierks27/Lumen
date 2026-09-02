package com.lilahcraft.lumen;

import com.lilahcraft.lumen.brain.LumenBrain;
import com.lilahcraft.lumen.command.LumenCommand;
import com.lilahcraft.lumen.memory.LumenMemory;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Lumen is a server side companion: it adds no registry entries and
 * requires nothing on the client.
 */
public final class Lumen implements ModInitializer {

    public static final String MOD_ID = "lumen";
    public static final Logger LOGGER = LoggerFactory.getLogger("Lumen");

    private static final LumenManager MANAGER = new LumenManager();
    private static final LumenBrain BRAIN = new LumenBrain();
    private static final LumenMemory MEMORY = new LumenMemory();
    private static volatile LumenConfig config = LumenConfig.defaults();

    public static LumenConfig config() {
        return config;
    }

    public static LumenManager manager() {
        return MANAGER;
    }

    public static LumenBrain brain() {
        return BRAIN;
    }

    /** What Lumen has learned and keeps between restarts. */
    public static LumenMemory memory() {
        return MEMORY;
    }

    public static LumenConfig reloadConfig() {
        config = LumenConfig.loadOrCreate();
        return config;
    }

    @Override
    public void onInitialize() {
        config = LumenConfig.loadOrCreate();
        MEMORY.load();
        LOGGER.info("Lumen ready - model {} at {}", config.model, config.ollamaUrl);

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> LumenCommand.register(dispatcher));

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            try {
                BRAIN.onPlayerMessage(sender.getServer(), sender, message.getContent().getString(), false);
            } catch (RuntimeException e) {
                // A companion must never take the server down over a chat line.
                LOGGER.error("Failed to handle chat message", e);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            MANAGER.despawn(server);
            BRAIN.shutdown();
            MEMORY.save();
        });
    }

    /**
     * Sends one of Lumen's lines to everybody as a system message.
     *
     * <p>Deliberately not a player chat packet: NoChatReports (and friends) drop
     * unsigned chat, which would silently swallow everything Lumen says.
     */
    public static void broadcast(MinecraftServer server, String message) {
        if (server == null || message == null || message.isBlank()) {
            return;
        }
        String trimmed = message.strip();
        if (trimmed.length() > 256) {
            trimmed = trimmed.substring(0, 253) + "...";
        }
        Text text = Text.literal("<" + config.companionName + "> ").formatted(Formatting.AQUA)
                .append(Text.literal(trimmed).formatted(Formatting.WHITE));
        server.getPlayerManager().broadcast(text, false);
    }

    /** A private note to one player, e.g. an error from /lumen say. */
    public static void tell(PlayerEntity player, String message) {
        if (player != null && message != null) {
            player.sendMessage(Text.literal(message).formatted(Formatting.GRAY), false);
        }
    }
}
