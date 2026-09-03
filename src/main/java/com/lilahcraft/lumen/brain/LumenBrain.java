package com.lilahcraft.lumen.brain;

import com.lilahcraft.lumen.Lumen;
import com.lilahcraft.lumen.LumenConfig;
import com.lilahcraft.lumen.entity.ChestFinder;
import com.lilahcraft.lumen.entity.LumenEntity;
import com.lilahcraft.lumen.entity.LumenTask;
import com.lilahcraft.lumen.memory.LumenMemory;
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
            // "find iron then mine copper then come back" comes back from a small model
            // as one command. When the player's own sentence has several steps and each
            // one reads as an instruction, those are the more faithful record.
            List<String> spoken = inferCommandsFromRequest(playerText);
            if (spoken.size() > 1 && Phrasing.splitCompound(response.command()).size() == 1) {
                Lumen.LOGGER.info("model collapsed a {}-step request to '{}'; using the steps as spoken",
                        spoken.size(), response.command());
                executeCommand(server, senderName, String.join(" then ", spoken), playerText);
            } else {
                executeCommand(server, senderName, response.command(), playerText);
            }
        } else {
            // The model chatted but sent no command. Rather than saying "on it" and then
            // standing there, work the intent out of what was actually asked.
            String inferred = inferCommandFromRequest(playerText);
            if (inferred != null) {
                Lumen.LOGGER.info("model sent no command field; inferred '{}' from the request", inferred);
                executeCommand(server, senderName, inferred, playerText);
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
        executeCommand(server, senderName, rawCommand, null);
    }

    /**
     * @param playerText what the player actually said, so an amount the model left out
     *                   of its command can be read back off the request
     */
    public void executeCommand(MinecraftServer server, String senderName, String rawCommand,
                               @Nullable String playerText) {
        List<String> parts = Phrasing.splitCompound(rawCommand);
        if (parts.isEmpty()) {
            parts = List.of("");
        }
        List<String> parsedParts = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            String parsed = normaliseCommand(parts.get(i));
            parsedParts.add(parsed);
            // The first step runs now; the rest wait their turn behind it.
            outcomes.add(route(server, senderName, parsed, playerText, i > 0));
        }
        String parsed = String.join(" then ", parsedParts);
        String outcome = String.join(" | ", outcomes);
        this.lastTrace = new CommandTrace(rawCommand, parsed, outcome);
        Lumen.LOGGER.info("command '{}' parsed as '{}' -> {}", rawCommand, parsed, outcome);
    }

    /**
     * The amount for a fetch: what the command says, and when the command names no
     * amount, what the player said. qwen2.5 routinely turns "grab me 12 redstone" into
     * {@code find redstone}; taking 64 of them - or the whole chest - is what made
     * quantities look ignored in v0.5.
     */
    static ChestFinder.Request resolveFetchRequest(String argument, @Nullable String playerText,
                                                   int defaultCount) {
        ChestFinder.Request request = ChestFinder.parseRequest(argument, defaultCount);
        if (request.explicit() || playerText == null) {
            return request;
        }
        ChestFinder.Request fromChat = ChestFinder.quantityIn(playerText);
        return fromChat == null ? request : fromChat.applyAmountTo(request);
    }

    /**
     * @param queued true for every step after the first of a compound request - those
     *               go behind the current errand instead of interrupting it
     */
    private String route(MinecraftServer server, String senderName, String command, @Nullable String playerText,
                         boolean queued) {
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
            case "remember", "save", "mark", "name", "call" -> {
                ServerPlayerEntity speaker = resolvePlayer(server, senderName, senderName);
                if (speaker == null) {
                    return "no such player: " + senderName;
                }
                String name = LumenMemory.cleanPlaceName(Phrasing.placeNameFromRemember(argument));
                if (name.isEmpty()) {
                    Lumen.broadcast(server, "what should i call this spot?");
                    return "no place name given";
                }
                Lumen.memory().rememberPlace(name, speaker.getWorld().getRegistryKey().getValue(),
                        speaker.getBlockPos(), LumenMemory.DEFAULT_PLACE_RADIUS, speaker.getName().getString());
                Lumen.broadcast(server, "got it, this is the " + name + " now");
                return "remembered place '" + name + "' at " + speaker.getBlockPos().toShortString();
            }
            case "goto", "walk" -> {
                ServerPlayerEntity speaker = resolvePlayer(server, senderName, senderName);
                if (speaker == null) {
                    return "no such player: " + senderName;
                }
                if (argument.isEmpty() || argument.equals("me")) {
                    return routeCome(server, lumen, speaker, queued);
                }
                LumenMemory.KnownPlace place = Lumen.memory().findPlace(argument,
                        speaker.getWorld().getRegistryKey().getValue());
                if (place == null) {
                    Lumen.broadcast(server, "i don't know where the " + argument
                            + " is - stand there and tell me 'remember this as the " + argument + "'");
                    return "unknown place: " + argument;
                }
                return describeSubmission(server, lumen,
                        lumen.submit(new LumenTask.GoTo(speaker.getUuid(), place.pos(), place.name)),
                        "heading to the " + place.name, "going to the " + place.name);
            }
            case "continue", "resume", "carry", "keep", "proceed", "onward" -> {
                if (lumen.resume()) {
                    LumenTask task = lumen.currentTask();
                    Lumen.broadcast(server, "back to it" + (task == null ? "" : " - " + task.describe()));
                    return "resumed: " + (task == null ? "?" : task.describe());
                }
                Lumen.broadcast(server, lumen.isOnErrand() ? "i'm still on it" : "nothing was waiting");
                return "nothing to resume";
            }
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
                if (lumen.isOnErrand() && !playerAsked(playerText, "stay|stop|wait|halt|hold|cancel|never mind|nevermind")) {
                    // The model said "stop" in passing; the player did not. Small models do
                    // this mid-conversation, and it used to throw the errand away.
                    return "kept working (the player did not ask to stop): " + lumen.describeActivity();
                }
                int dropped = lumen.cancelAll();
                return dropped > 1 ? "stopped, dropped " + dropped + " tasks" : "stopped";
            }
            case "follow" -> {
                ServerPlayerEntity player = resolvePlayer(server,
                        argument.isEmpty() ? senderName : argument, senderName);
                if (player == null) {
                    return "no such player: " + argument;
                }
                if (lumen.isOnErrand() && !playerAsked(playerText, "follow|come|with me|here")) {
                    return "kept working (the player did not ask to be followed): " + lumen.describeActivity();
                }
                boolean paused = lumen.currentTask() != null;
                lumen.pauseForPlayer();
                lumen.followPlayer(player);
                if (paused) {
                    Lumen.broadcast(server, "ok, i'll pick the rest up when you say carry on");
                }
                return "following " + player.getName().getString() + (paused ? " (errand paused)" : "");
            }
            case "come", "here", "approach" -> {
                ServerPlayerEntity player = resolvePlayer(server, senderName, senderName);
                if (player == null) {
                    return "no such player: " + senderName;
                }
                if (!queued && lumen.isOnErrand() && !playerAsked(playerText, "come|here|over|back|to me")) {
                    return "kept working (the player did not call): " + lumen.describeActivity();
                }
                return routeCome(server, lumen, player, queued);
            }
            case "find", "fetch", "get", "bring", "grab", "collect", "search" -> {
                if (argument.isEmpty()) {
                    return "no item named";
                }
                ServerPlayerEntity requester = resolvePlayer(server, senderName, senderName);
                if (requester == null) {
                    return "no such player: " + senderName;
                }
                PlaceLookup where = lookupPlace(argument, requester);
                ChestFinder.Request request = resolveFetchRequest(where.rest(), playerText,
                        Lumen.config().defaultFetchCount);
                if (where.unknown() != null) {
                    Lumen.broadcast(server, "i don't know where the " + where.unknown() + " is, so i'll look around here");
                }
                String wanted = ChestFinder.describeRequest(request)
                        + (where.place() == null ? "" : " from the " + where.place().name);
                String fromChat = request.explicit() && !ChestFinder.parseRequest(where.rest(), 0).explicit()
                        ? " (amount taken from your message)" : "";
                return describeSubmission(server, lumen,
                        lumen.submit(new LumenTask.Fetch(requester.getUuid(), request,
                                where.place() == null ? null : where.place().pos(),
                                where.place() == null ? null : where.place().name)),
                        "on it - " + wanted, "fetching " + wanted + fromChat);
            }
            case "mine", "dig", "chop", "break", "harvest" -> {
                if (argument.isEmpty()) {
                    return "no block named";
                }
                ServerPlayerEntity requester = resolvePlayer(server, senderName, senderName);
                if (requester == null) {
                    return "no such player: " + senderName;
                }
                PlaceLookup where = lookupPlace(argument, requester);
                if (where.unknown() != null) {
                    Lumen.broadcast(server, "i don't know where the " + where.unknown() + " is, so i'll look around here");
                }
                String what = where.rest() + (where.place() == null ? "" : " near the " + where.place().name);
                return describeSubmission(server, lumen,
                        lumen.submit(new LumenTask.Mine(requester.getUuid(), where.rest(),
                                where.place() == null ? null : where.place().pos(),
                                where.place() == null ? null : where.place().name)),
                        "on my way - mining " + what, "mining " + what);
            }
            case "drop", "give", "hand", "return", "pass" -> {
                ServerPlayerEntity requester = resolvePlayer(server, senderName, senderName);
                if (requester == null) {
                    return "no such player: " + senderName;
                }
                boolean everything = ChestFinder.meansEverything(argument);
                if (!everything && !lumen.isCarrying(argument)) {
                    Lumen.broadcast(server, "i don't have any " + argument);
                    return "not carrying " + argument;
                }
                // Straight into their inventory, never onto the floor - dropping it meant
                // Lumen picked it back up before anyone else could.
                LumenEntity.HandoverResult result = lumen.requestHandover(requester, everything ? null : argument);
                if (result == null) {
                    Lumen.broadcast(server, "coming over with " + (everything ? "your things" : "the " + argument));
                    return "walking over to hand over " + (everything ? "everything" : argument);
                }
                Lumen.broadcast(server, LumenEntity.describeHandover(result, everything ? "" : argument));
                return "handed over " + result.stacks() + " stack(s)";
            }
            default -> {
                return "unrecognised verb '" + verb + "'";
            }
        }
    }

    /**
     * "Come here" / "come back". As a step in a compound request it is a task that
     * waits its turn; on its own it is the player wanting Lumen now, so whatever is
     * running is paused (not lost) and Lumen walks over.
     */
    private String routeCome(MinecraftServer server, LumenEntity lumen, ServerPlayerEntity player, boolean queued) {
        if (queued) {
            return describeSubmission(server, lumen, lumen.submit(new LumenTask.Return(player.getUuid())),
                    "coming", "coming back to " + player.getName().getString());
        }
        boolean paused = lumen.currentTask() != null;
        lumen.pauseForPlayer();
        lumen.goTo(player.getBlockPos());
        if (paused) {
            Lumen.broadcast(server, "ok, coming - say carry on when you want me back on it");
        }
        return "walking to " + player.getName().getString() + (paused ? " (errand paused)" : "");
    }

    /**
     * Whether the player's own words contain one of these phrases. Null text (a slash
     * command, or an unknown source) counts as asked: those are never model noise.
     */
    static boolean playerAsked(@Nullable String playerText, String phrases) {
        if (playerText == null) {
            return true;
        }
        return java.util.regex.Pattern.compile("\\b(?:" + phrases + ")\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(playerText).find();
    }

    /** Turns a submission into chat and a trace line. */
    private static String describeSubmission(MinecraftServer server, LumenEntity lumen,
                                             LumenEntity.Submission result, String sayIfStarted, String trace) {
        switch (result) {
            case STARTED -> {
                return trace;
            }
            case QUEUED -> {
                String later = trace.replaceFirst("^fetching", "fetch")
                        .replaceFirst("^mining", "mine")
                        .replaceFirst("^going", "go")
                        .replaceFirst("^coming back", "come back")
                        .replaceFirst(" \\(amount taken from your message\\)$", "");
                Lumen.broadcast(server, "after this, i'll " + later);
                return "queued (" + lumen.queuedCount() + " waiting): " + trace;
            }
            default -> {
                Lumen.broadcast(server, lumen.lastTaskNote());
                return "could not start: " + lumen.lastTaskNote();
            }
        }
    }

    /** A fetch/mine argument with any named place resolved. {@code unknown} names a place nobody taught. */
    private record PlaceLookup(String rest, @Nullable LumenMemory.KnownPlace place, @Nullable String unknown) {
    }

    private static PlaceLookup lookupPlace(String argument, ServerPlayerEntity requester) {
        Phrasing.PlaceRef ref = Phrasing.splitPlaceReference(argument);
        if (ref.place() == null) {
            return new PlaceLookup(argument, null, null);
        }
        LumenMemory.KnownPlace place = Lumen.memory().findPlace(ref.place(),
                requester.getWorld().getRegistryKey().getValue());
        if (place != null) {
            return new PlaceLookup(ref.rest(), place, null);
        }
        // "torch in a jar" is an item, not a place. Only complain when it looks like one.
        boolean looksLikePlace = ref.place().matches(".*\\b(room|house|base|farm|mine|storage|shed|barn|cave|"
                + "tower|hall|shop|kitchen|cellar|attic|garden|yard|spot|area|place|home)\\b.*");
        return new PlaceLookup(argument, null, looksLikePlace ? ref.place() : null);
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
        // "go to the hops room" is a destination, not the filler "go" in front of a verb.
        text = text.replaceFirst("^(?:go|head|walk|run)\\s+(?:(?:up|down|over|back|on|in|out)\\s+)?to\\b", "goto");
        text = text.replaceFirst("^(?:carry\\s+on|keep\\s+going|go\\s+on|get\\s+back\\s+to\\s+it)\\b", "continue");
        text = text.replaceFirst("^(?:come|go)\\s+back\\b", "come");
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
            "give", "hand", "return", "pass", "stay", "stop", "wait", "halt", "hold",
            "remember", "save", "mark", "continue", "resume", "carry", "keep", "proceed", "walk");

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
                    .replaceFirst("^(can|could|may)\\s+i\\s+(have|get)\\b", "give me")
                    .replaceFirst("^(i\\s+want|i\\s+need)\\s+(the|my|that)\\b(.*)\\bback$", "give me $2$3")
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

    /**
     * Every step of a compound request that reads as an instruction, in order. A step
     * that is only chatter is dropped rather than failing the whole request.
     */
    static List<String> inferCommandsFromRequest(@Nullable String playerText) {
        List<String> out = new ArrayList<>();
        for (String part : Phrasing.splitCompound(playerText)) {
            String inferred = inferCommandFromRequest(part);
            if (inferred != null) {
                out.add(inferred);
            }
        }
        return out;
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
        String recalled = "";
        String places = "";
        String queue = "";
        if (lumen.getWorld() instanceof ServerWorld world) {
            recalled = Lumen.memory().describe(world.getRegistryKey().getValue(), lumen.getBlockPos(), 6);
            places = Lumen.memory().describePlaces(world.getRegistryKey().getValue(), lumen.getBlockPos(), 6);
        }
        if (lumen.queuedCount() > 0) {
            queue = "[what you have lined up]\n- " + String.join("\n- ", lumen.describeQueue()) + "\n";
        }
        messages.add(ChatMessage.user(snapshot + recalled + places + queue + "\n" + senderName + " says: " + text));
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
                + "something you have found before, you already know where to look - say so. "
                + "A [places you know] block lists spots the player named for you, with how far away "
                + "they are; use those names in goto, find and mine. A [what you have lined up] block "
                + "is what you have agreed to do next - do not offer to do it again.\n\n"
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
                + "  give <item>     - hand that item straight to whoever asked (\"give sword\")\n"
                + "  drop            - hand over everything you are carrying\n"
                + "  goto <place>    - walk to a place you were taught the name of\n"
                + "  remember <name> - save the spot the speaker is standing in under that name\n"
                + "  continue        - carry on with whatever was paused\n"
                + "Several things in one request are joined with \"then\": \"find iron then mine copper "
                + "then come\". A place can be named at the end of find or mine: \"find hops from the "
                + "hops room\", \"mine copper near the copper spot\". A second request waits its turn "
                + "behind the first; it does not cancel it.\n"
                + "Name things in plain words - \"iron\", \"oak planks\", \"coal\" - never a mod item "
                + "id. Partial words match, so \"iron\" finds iron ingots and iron ore. A number or "
                + "\"all\" or \"a stack\" before the item sets how much: \"find 10 stone\", "
                + "\"find a stack of oak planks\". A stack is however many fit in one "
                + "inventory slot - 64 for most things, fewer for some. Always copy the amount "
                + "the player asked for into the command: \"grab me 12 redstone\" is "
                + "\"find 12 redstone\", \"a dozen wool\" is \"find 12 wool\".\n\n"
                + "Examples of complete replies:\n"
                + "{\"reason\":\"they want iron\",\"command\":\"find iron\","
                + "\"message\":\"on it, i'll check the chests\"}\n"
                + "{\"reason\":\"they asked for a lot of stone\",\"command\":\"find 64 stone\","
                + "\"message\":\"heading off to grab it\"}\n"
                + "{\"reason\":\"they want their sword back\",\"command\":\"give sword\","
                + "\"message\":\"sure, here\"}\n"
                + "{\"reason\":\"just chatting\",\"command\":\"idle\","
                + "\"message\":\"thanks! i like how the roof came out\"}\n"
                + "{\"reason\":\"they called me over\",\"command\":\"come\","
                + "\"message\":\"coming\"}\n"
                + "{\"reason\":\"they want stone mined\",\"command\":\"mine stone\","
                + "\"message\":\"on my way\"}\n"
                + "{\"reason\":\"three jobs in a row\",\"command\":\"find iron then mine copper then come\","
                + "\"message\":\"iron first, then copper, then back to you\"}\n"
                + "{\"reason\":\"naming this spot\",\"command\":\"remember hops room\","
                + "\"message\":\"the hops room, got it\"}\n\n"
                + "The \"message\" field is the only thing players see: one or two short sentences, "
                + "lowercase and casual, like typing in chat. Never mention JSON, commands or that "
                + "you are an AI.";
    }

}
