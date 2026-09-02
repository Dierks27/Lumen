package com.lilahcraft.lumen.brain;

import com.lilahcraft.lumen.Lumen;
import com.lilahcraft.lumen.LumenConfig;
import com.lilahcraft.lumen.entity.LumenEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

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

    private volatile CommandTrace lastTrace;
    private volatile String lastRawContent;

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

    @Nullable
    public CommandTrace lastTrace() {
        return lastTrace;
    }

    /** The raw text the model last returned, for /lumen debug. */
    @Nullable
    public String lastRawContent() {
        return lastRawContent;
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
                        applyResponse(server, senderName, text, content);
                    } finally {
                        busy.set(false);
                    }
                }));
    }

    /** Runs on the server thread. */
    private void applyResponse(MinecraftServer server, String senderName, String playerText, String content) {
        LumenConfig config = Lumen.config();
        this.lastRawContent = content;
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
        } else {
            // The model chatted but sent no command. Rather than saying "on it" and then
            // standing there, work the intent out of what was actually asked.
            String inferred = inferCommandFromRequest(playerText);
            if (inferred != null) {
                Lumen.LOGGER.info("model sent no command field; inferred '{}' from the request", inferred);
                executeCommand(server, senderName, inferred);
                this.lastTrace = new CommandTrace("<none - inferred from your message>",
                        inferred, this.lastTrace == null ? "?" : this.lastTrace.outcome());
            } else {
                this.lastTrace = new CommandTrace("<none>", "", "the model sent no command field");
                Lumen.LOGGER.info("model returned no command field and the request was not an instruction");
            }
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
    /** What the last model command did. Surfaced by /lumen debug. */
    public record CommandTrace(String raw, String parsed, String outcome) {
    }

    /**
     * Turns the model's {@code command} field into an action.
     *
     * <p>Small models are inconsistent about shape: they emit {@code find_iron_ore},
     * {@code "go mine some iron"}, {@code `come`}, trailing full stops, and leading
     * filler. Everything is normalised down to a verb plus an argument before
     * dispatch, and every decision is logged - "it did not execute" is otherwise
     * impossible to tell apart from "it was never asked to".
     */
    public void executeCommand(MinecraftServer server, String senderName, String rawCommand) {
        String parsed = normaliseCommand(rawCommand);
        String outcome = route(server, senderName, parsed);
        this.lastTrace = new CommandTrace(rawCommand, parsed, outcome);
        Lumen.LOGGER.info("command '{}' parsed as '{}' -> {}", rawCommand, parsed, outcome);
    }

    private String route(MinecraftServer server, String senderName, String command) {
        LumenEntity lumen = Lumen.manager().get(server);
        if (lumen == null) {
            return "ignored, not spawned";
        }
        if (command.isEmpty()) {
            return "nothing to do";
        }

        String[] parts = command.split(" ", 2);
        String verb = parts[0];
        String argument = stripFiller(parts.length > 1 ? parts[1] : "");

        switch (verb) {
            case "idle", "none", "nothing", "relax", "chill", "wander", "explore" -> {
                // A conversational "idle" must not throw away an errand. Chatting while
                // Lumen fetches used to cancel the fetch, which is what made it look
                // like it could not hold a thought.
                if (lumen.isOnErrand()) {
                    return "kept working: " + lumen.describeActivity();
                }
                lumen.stopAndIdle();
                return "idling";
            }
            case "stay", "stop", "wait", "halt", "hold" -> {
                lumen.stopAndIdle();
                return "stopped";
            }
            case "follow" -> {
                ServerPlayerEntity player = resolvePlayer(server,
                        argument.isEmpty() ? senderName : argument, senderName);
                if (player == null) {
                    return "no such player: " + argument;
                }
                lumen.followPlayer(player);
                return "following " + player.getName().getString();
            }
            case "come", "goto", "here", "approach" -> {
                ServerPlayerEntity player = resolvePlayer(server, senderName, senderName);
                if (player == null) {
                    return "no such player: " + senderName;
                }
                lumen.goTo(player.getBlockPos());
                return "walking to " + player.getName().getString();
            }
            case "find", "fetch", "get", "bring", "grab", "collect", "search" -> {
                if (argument.isEmpty()) {
                    return "no item named";
                }
                ServerPlayerEntity requester = resolvePlayer(server, senderName, senderName);
                if (requester == null) {
                    return "no such player: " + senderName;
                }
                if (!lumen.startFetch(requester, argument)) {
                    Lumen.broadcast(server, "i can't find any " + argument + " in anything nearby");
                    return "nothing nearby holds " + argument;
                }
                return "fetching " + argument;
            }
            case "mine", "dig", "chop", "break", "harvest" -> {
                if (argument.isEmpty()) {
                    return "no block named";
                }
                ServerPlayerEntity requester = resolvePlayer(server, senderName, senderName);
                if (requester == null) {
                    return "no such player: " + senderName;
                }
                String refusal = lumen.startMining(requester, argument);
                if (refusal != null) {
                    Lumen.broadcast(server, refusal);
                    return "refused: " + refusal;
                }
                return "mining " + argument;
            }
            case "drop", "give", "hand" -> {
                int dropped = lumen.dropEverything();
                Lumen.broadcast(server, dropped == 0 ? "i'm not carrying anything" : "here you go");
                return "dropped " + dropped + " stack(s)";
            }
            default -> {
                return "unrecognised verb '" + verb + "'";
            }
        }
    }

    /**
     * Reduces whatever the model produced to "verb argument".
     *
     * <p>Handles code fences and quotes around the value, a leading slash, underscores
     * instead of spaces, trailing punctuation and leading filler words.
     */
    static String normaliseCommand(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        text = text.replaceAll("^[`\"'\\[(]+", "").replaceAll("[`\"'\\])]+$", "");
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        text = text.replace('_', ' ');
        text = text.replaceAll("[.!,;:]+$", "");
        text = text.replaceAll("\\s+", " ").trim();
        // "go mine iron", "please come", "lumen, follow me"
        for (int i = 0; i < 3; i++) {
            String stripped = text.replaceFirst("^(go|please|now|lumen|hey)\\b[, ]*", "").trim();
            if (stripped.equals(text)) {
                break;
            }
            text = stripped;
        }
        return text;
    }

    /** Verbs route() knows how to act on. */
    private static final java.util.Set<String> ACTION_VERBS = java.util.Set.of(
            "follow", "come", "goto", "here", "approach", "find", "fetch", "get", "bring",
            "grab", "collect", "search", "mine", "dig", "chop", "break", "harvest", "drop",
            "give", "hand", "stay", "stop", "wait", "halt", "hold");

    /**
     * Last resort when the model chats but omits the command field: read the intent
     * straight off what the player asked for.
     *
     * <p>Peels the conversational wrapper - "hey buddy, can you go..." - and keeps the
     * result only if it starts with a verb we can act on, so ordinary conversation
     * does not accidentally send Lumen somewhere.
     *
     * @return a command string, or null if the request was not an instruction
     */
    @Nullable
    static String inferCommandFromRequest(String playerText) {
        if (playerText == null) {
            return null;
        }
        String text = playerText.trim().toLowerCase(Locale.ROOT).replaceAll("[?!.]+$", "");
        for (int i = 0; i < 6; i++) {
            String peeled = text
                    .replaceFirst("^(hey|hi|yo|ok|okay|so|well)\\b[, ]*", "")
                    .replaceFirst("^(buddy|lumen|mate|pal|dude)\\b[, ]*", "")
                    .replaceFirst("^(can|could|would|will)\\s+you\\b", "")
                    .replaceFirst("^(please|just)\\b", "")
                    .replaceFirst("^(i\\s+want\\s+you\\s+to|i\\s+need\\s+you\\s+to)\\b", "")
                    .trim();
            if (peeled.equals(text)) {
                break;
            }
            text = peeled;
        }
        String normalised = normaliseCommand(text);
        if (normalised.isEmpty()) {
            return null;
        }
        String verb = normalised.split(" ", 2)[0];
        return ACTION_VERBS.contains(verb) ? normalised : null;
    }

    /** "me some iron" -> "iron". */
    static String stripFiller(String argument) {
        String text = argument.trim();
        for (int i = 0; i < 5; i++) {
            String stripped = text.replaceFirst("^(me|us|some|any|a|an|the|down|up|for|of|to)\\b", "").trim();
            if (stripped.equals(text)) {
                break;
            }
            text = stripped;
        }
        return text;
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
        String snapshot = WorldSnapshot.describe(lumen, config);
        String recalled = lumen.getWorld() instanceof ServerWorld world
                ? Lumen.memory().describe(world.getRegistryKey().getValue(), lumen.getBlockPos(), 6)
                : "";
        messages.add(ChatMessage.user(snapshot + recalled + "\n" + senderName + " says: " + text));
        return messages;
    }

    private static String buildSystemPrompt(LumenConfig config) {
        return config.personality + "\n\n"
                + "You are in a heavily modded Minecraft world. You control a body in that world.\n\n"
                + "Everything you know about the world is in the [what you can see right now] block "
                + "of the next message: the blocks, mobs, players and items around you, the time, the "
                + "weather, the biome, what you are carrying. Talk about those. If something is not "
                + "listed there, you cannot see it - do not mention it, and never invent places, "
                + "structures, items or things that happened. Saying you are not sure is fine.\n"
                + "If someone asks about something you cannot see from here - the colour of a block, "
                + "what is in a room, what a build looks like - do not guess. Say you will come and "
                + "look, and use the come command.\n"
                + "If a [what you remember from before] block is present, those are places you have "
                + "actually fetched things from and you may talk about them. When someone asks for "
                + "something you have found before, you already know where to look - say so.\n\n"
                + "REPLY FORMAT. Every reply is exactly one JSON object with all three fields:\n"
                + "{\"reason\":\"<short private thought>\","
                + "\"command\":\"<one command>\","
                + "\"message\":\"<what you say out loud>\"}\n\n"
                + "The \"command\" field is REQUIRED on EVERY reply. A reply without it does "
                + "nothing at all - you will say you are on your way and then stand there. If "
                + "nothing needs doing, the command is \"idle\", but the field is always present.\n\n"
                + "Commands:\n"
                + "  idle            - carry on, nothing to do\n"
                + "  follow <player> - walk after that player\n"
                + "  come            - walk to whoever just spoke\n"
                + "  find <item>     - search nearby containers and bring it back\n"
                + "  mine <block>    - break blocks of that kind and bring them back\n"
                + "  drop            - hand over everything you are carrying\n"
                + "Name things in plain words - \"iron\", \"oak planks\", \"coal\" - never a mod item "
                + "id. Partial words match, so \"iron\" finds iron ingots and iron ore. A number or "
                + "\"all\" or \"a stack\" before the item sets how much: \"find 10 stone\", "
                + "\"find a stack of oak planks\". A stack is however many fit in one "
                + "inventory slot - 64 for most things, fewer for some.\n\n"
                + "Examples of complete replies:\n"
                + "{\"reason\":\"they want iron\",\"command\":\"find iron\","
                + "\"message\":\"on it, i'll check the chests\"}\n"
                + "{\"reason\":\"they asked for a lot of stone\",\"command\":\"find 64 stone\","
                + "\"message\":\"heading off to grab it\"}\n"
                + "{\"reason\":\"just chatting\",\"command\":\"idle\","
                + "\"message\":\"thanks! i like how the roof came out\"}\n"
                + "{\"reason\":\"they called me over\",\"command\":\"come\","
                + "\"message\":\"coming\"}\n"
                + "{\"reason\":\"they want stone mined\",\"command\":\"mine stone\","
                + "\"message\":\"on my way\"}\n\n"
                + "The \"message\" field is the only thing players see: one or two short sentences, "
                + "lowercase and casual, like typing in chat. Never mention JSON, commands or that "
                + "you are an AI.";
    }

}
