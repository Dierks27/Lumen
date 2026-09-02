package com.lilahcraft.lumen.brain;

import com.lilahcraft.lumen.Lumen;
import com.lilahcraft.lumen.LumenConfig;
import com.lilahcraft.lumen.entity.LumenEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the conversation: decides whether a chat line is meant for Lumen, builds the
 * prompt, talks to Ollama off-thread and applies the result back on the server thread.
 */
public final class LumenBrain {

    private final Deque<ChatMessage> history = new ArrayDeque<>();

    /** One request at a time - an 8B model on a LAN box is not a load balancer. */
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private OllamaClient client;
    private int clientConnectTimeout = -1;

    /** Free-form notes that survive within a session, e.g. a remembered home. */
    private BlockPos home;

    public synchronized void reset() {
        history.clear();
    }

    public synchronized void shutdown() {
        if (client != null) {
            client.shutdown();
            client = null;
        }
        history.clear();
    }

    public BlockPos getHome() {
        return home;
    }

    public void setHome(BlockPos home) {
        this.home = home;
    }

    public boolean isBusy() {
        return busy.get();
    }

    private synchronized OllamaClient client(LumenConfig config) {
        if (client == null || clientConnectTimeout != config.connectTimeoutSeconds) {
            if (client != null) {
                client.shutdown();
            }
            client = new OllamaClient(config.connectTimeoutSeconds);
            clientConnectTimeout = config.connectTimeoutSeconds;
        }
        return client;
    }

    /**
     * Decides whether a chat line is addressed to Lumen, per {@code chatTrigger}.
     * {@code /lumen say} bypasses this by passing {@code forced}.
     */
    public boolean isAddressedToLumen(LumenConfig config, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return switch (config.chatTrigger) {
            case "always" -> true;
            case "never" -> false;
            case "prefix" -> lower.startsWith(config.triggerPrefix.toLowerCase(Locale.ROOT));
            default -> lower.contains(config.companionName.toLowerCase(Locale.ROOT))
                    || lower.startsWith(config.triggerPrefix.toLowerCase(Locale.ROOT));
        };
    }

    /** Entry point for both player chat and {@code /lumen say}. Never blocks. */
    public void onPlayerMessage(MinecraftServer server, ServerPlayerEntity sender, String rawText, boolean forced) {
        LumenConfig config = Lumen.config();
        if (!config.enabled || server == null || sender == null || rawText == null) {
            return;
        }
        String text = rawText.trim();
        if (text.isEmpty() || text.length() > config.maxPlayerMessageLength) {
            return;
        }
        String senderName = sender.getName().getString();
        String line = senderName + ": " + text;

        if (!forced && !isAddressedToLumen(config, text)) {
            // Not for Lumen, but it was said in earshot. Keeping it means the next
            // reply lands in an ongoing conversation instead of out of nowhere.
            overhear(config, line);
            return;
        }

        LumenEntity lumen = Lumen.manager().get(server);
        if (lumen == null) {
            if (forced) {
                Lumen.tell(sender, config.companionName + " is not spawned. Try /lumen spawn.");
            }
            return;
        }

        if (!busy.compareAndSet(false, true)) {
            // One request at a time, but the line is still remembered rather than lost -
            // silently dropping these is what makes a companion feel like it has no memory.
            Lumen.LOGGER.debug("Not answering '{}' - a request is already in flight", text);
            overhear(config, line);
            return;
        }

        // Recorded now, not on completion, so history stays in the order it was said
        // and survives a failed request.
        remember(config, ChatMessage.user(line));
        List<ChatMessage> messages = buildMessages(config, lumen, senderName, text);

        client(config).complete(config, messages)
                .whenComplete((content, error) -> server.execute(() -> {
                    try {
                        if (error != null) {
                            Lumen.LOGGER.warn("Ollama request failed: {}", error.toString());
                            return;
                        }
                        applyResponse(server, senderName, content);
                    } finally {
                        busy.set(false);
                    }
                }));
    }

    /** Runs on the server thread. */
    private void applyResponse(MinecraftServer server, String senderName, String content) {
        LumenConfig config = Lumen.config();
        LumenResponse response = LumenResponse.parse(content);
        if (response == null) {
            Lumen.LOGGER.warn("Ollama returned an empty response");
            return;
        }
        if (config.logRawResponses) {
            Lumen.LOGGER.info("Lumen reason='{}' command='{}' message='{}'",
                    response.reason(), response.command(), response.message());
        }

        if (response.hasMessage()) {
            remember(config, ChatMessage.assistant(response.message()));
            Lumen.broadcast(server, response.message());
        }
        if (response.hasCommand()) {
            executeCommand(server, senderName, response.command());
        }
    }

    /** Files away a line Lumen heard but is not replying to. */
    private void overhear(LumenConfig config, String line) {
        if (config.rememberUntriggeredChat) {
            remember(config, ChatMessage.user(line));
        }
    }

    private synchronized void remember(LumenConfig config, ChatMessage message) {
        if (config.maxHistoryMessages <= 0) {
            return;
        }
        history.addLast(message);
        while (history.size() > config.maxHistoryMessages) {
            history.removeFirst();
        }
    }

    /**
     * Translates the {@code command} field into entity state. Unknown commands are
     * logged and ignored - the model inventing verbs must never break the game.
     */
    public void executeCommand(MinecraftServer server, String senderName, String rawCommand) {
        LumenEntity lumen = Lumen.manager().get(server);
        if (lumen == null) {
            return;
        }
        String command = rawCommand.trim().toLowerCase(Locale.ROOT);
        if (command.startsWith("/")) {
            command = command.substring(1);
        }

        if (command.isEmpty() || command.equals("none") || command.equals("idle")
                || command.equals("stay") || command.equals("stop") || command.equals("wait")) {
            lumen.stopAndIdle();
            return;
        }

        if (command.startsWith("follow")) {
            String target = command.substring("follow".length()).trim();
            ServerPlayerEntity player = resolvePlayer(server, target.isEmpty() ? senderName : target, senderName);
            if (player != null) {
                lumen.followPlayer(player);
            } else {
                Lumen.LOGGER.debug("Cannot follow unknown player '{}'", target);
            }
            return;
        }

        if (command.equals("come") || command.equals("come here") || command.startsWith("goto ")
                || command.startsWith("come to")) {
            ServerPlayerEntity player = resolvePlayer(server, senderName, senderName);
            if (player != null) {
                lumen.goTo(player.getBlockPos());
            }
            return;
        }

        if (command.startsWith("find") || command.startsWith("fetch") || command.startsWith("get ")) {
            String query = command.replaceFirst("^(find|fetch|get)", "").trim();
            // Models like to pad the object: "find me some iron" -> "iron".
            query = query.replaceFirst("^(me|us)\\b", "").trim();
            query = query.replaceFirst("^(some|a|an|the)\\b", "").trim();
            ServerPlayerEntity requester = resolvePlayer(server, senderName, senderName);
            if (!query.isEmpty() && requester != null && lumen.startFetch(requester, query)) {
                return;
            }
            Lumen.LOGGER.debug("Nothing nearby holds '{}'", query);
            return;
        }

        Lumen.LOGGER.debug("Ignoring unknown command from the model: '{}'", rawCommand);
    }

    private static ServerPlayerEntity resolvePlayer(MinecraftServer server, String name, String fallbackName) {
        if (name != null && !name.isBlank()) {
            String cleaned = name.replaceAll("[^A-Za-z0-9_]", "");
            ServerPlayerEntity exact = server.getPlayerManager().getPlayer(cleaned);
            if (exact != null) {
                return exact;
            }
            String lower = cleaned.toLowerCase(Locale.ROOT);
            for (ServerPlayerEntity candidate : server.getPlayerManager().getPlayerList()) {
                if (candidate.getName().getString().toLowerCase(Locale.ROOT).contains(lower) && !lower.isEmpty()) {
                    return candidate;
                }
            }
        }
        return fallbackName == null ? null : server.getPlayerManager().getPlayer(fallbackName);
    }

    private synchronized List<ChatMessage> buildMessages(LumenConfig config, LumenEntity lumen,
                                                        String senderName, String text) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(buildSystemPrompt(config)));
        messages.addAll(history);
        messages.add(ChatMessage.user(WorldSnapshot.describe(lumen, config) + "\n" + senderName + " says: " + text));
        return messages;
    }

    private static String buildSystemPrompt(LumenConfig config) {
        return config.personality + "\n\n"
                + "You are in a heavily modded Minecraft world. You control a body in that world.\n\n"
                + "Reply with ONE JSON object and nothing else. No markdown, no code fences, no commentary:\n"
                + "{\"reason\":\"<one short private thought about what you are doing>\","
                + "\"command\":\"<one of the commands below>\","
                + "\"message\":\"<what you say out loud in chat>\"}\n\n"
                + "Valid commands:\n"
                + "  idle            - stand around, wander a little, do nothing in particular\n"
                + "  follow <player> - walk after that player and keep up with them\n"
                + "  come            - walk to whoever just spoke to you\n"
                + "  find <item>     - go through nearby chests and barrels for that item and "
                + "bring it back to whoever asked\n"
                + "Use exactly one command. If nothing needs to change, use \"idle\".\n"
                + "The \"message\" field is the only thing players see: keep it to one or two short "
                + "sentences, lowercase and casual, like typing in chat. Never mention JSON, commands "
                + "or that you are an AI.\n\n"
                + "Everything you know about the world is in the [what you can see right now] block of "
                + "the next message: the blocks, mobs, players and items around you, the time, the "
                + "weather, the biome, what you are carrying. Talk about those. If something is not "
                + "listed there, you cannot see it - do not mention it, and never invent places, "
                + "structures, items or things that happened. Saying you are not sure is fine.";
    }

}
