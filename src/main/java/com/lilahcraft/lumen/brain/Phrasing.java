package com.lilahcraft.lumen.brain;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shapes of a request that are about structure rather than content: several
 * things in one breath, and a place named at the end. Pure Java, no Minecraft types,
 * so every rule here is covered by a unit test that runs without a game.
 */
public final class Phrasing {

    private Phrasing() {
    }

    /** Verbs that start a new instruction, so "and" before one of them is a separator. */
    private static final String VERBS = "go|goto|come|mine|dig|chop|break|harvest|find|fetch|get|bring|grab|"
            + "collect|search|follow|stay|stop|wait|give|hand|drop|return|head|walk|remember|continue|"
            + "put|store|stash|deposit|stand|craft|make|quarry|take|descend";

    private static final Pattern THEN = Pattern.compile(
            "\\s*(?:[,;]\\s*)?(?:and\\s+)?then\\s+|\\s*;\\s*|\\s*,?\\s+and\\s+(?:also\\s+)?(?=(?:" + VERBS + ")\\b)",
            Pattern.CASE_INSENSITIVE);

    /**
     * "grab me some iron, then go mine some copper, then come back" -> three requests.
     * Also splits on ";" and on "and" when what follows is plainly a new instruction.
     * Returns the text itself, alone, when there is nothing to split.
     */
    public static List<String> splitCompound(@Nullable String text) {
        List<String> parts = new ArrayList<>();
        if (text == null) {
            return parts;
        }
        for (String part : THEN.split(text.trim())) {
            String clean = part.trim().replaceAll("^[,;]+|[,;.!]+$", "").trim();
            if (!clean.isEmpty()) {
                parts.add(clean);
            }
        }
        return parts;
    }

    /** An argument with a trailing place reference peeled off. {@code place} is null when there was none. */
    public record PlaceRef(String rest, @Nullable String place) {
    }

    private static final Pattern PLACE = Pattern.compile(
            "^(.*?\\S)\\s+(?:from|in|at|near|by|inside|around|beside|over\\s+(?:at|in|by))\\s+"
                    + "(?:the\\s+|my\\s+|our\\s+|your\\s+)?(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * "12 redstone from the storage room" -> ("12 redstone", "storage room").
     *
     * <p>Only a guess: "torch in a jar" splits too. The caller looks the place up and,
     * when nothing by that name is known, uses the whole argument as the item again.
     */
    public static PlaceRef splitPlaceReference(@Nullable String argument) {
        if (argument == null) {
            return new PlaceRef("", null);
        }
        String text = argument.trim();
        Matcher m = PLACE.matcher(text);
        if (!m.matches()) {
            return new PlaceRef(text, null);
        }
        String rest = m.group(1).trim();
        String place = m.group(2).trim();
        if (rest.isEmpty() || place.isEmpty()) {
            return new PlaceRef(text, null);
        }
        return new PlaceRef(rest, place);
    }

    /**
     * "remember this as the hops room" -> "the hops room"; "call this spot home" ->
     * "home". The memory layer strips articles; this only removes the framing words a
     * person puts between the verb and the name.
     */
    public static String placeNameFromRemember(@Nullable String argument) {
        if (argument == null) {
            return "";
        }
        String text = argument.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < 4; i++) {
            String stripped = text
                    .replaceFirst("^(this\\s+spot|this\\s+place|this\\s+area|where\\s+i\\s+am|where\\s+we\\s+are|this|here|it|that)\\b\\s*", "")
                    .replaceFirst("^(as|is|called|named|the\\s+name)\\b\\s*", "")
                    .trim();
            if (stripped.equals(text)) {
                break;
            }
            text = stripped;
        }
        return text;
    }
}
