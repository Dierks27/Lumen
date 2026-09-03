package com.lilahcraft.lumen.skill;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lilahcraft.lumen.Lumen;
import com.lilahcraft.lumen.entity.ChestFinder;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The skills Lumen has been taught, kept in {@code config/lumen/skills.json}.
 *
 * <p>Readable and editable by hand: a server owner can fix a target Lumen learned
 * wrongly, or delete a skill they do not want run.
 */
public final class SkillBook {

    private static final class Data {
        List<LumenSkill> skills = new ArrayList<>();
    }

    private static final int MAX_SKILLS = 64;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Data data = new Data();

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("lumen").resolve("skills.json");
    }

    public synchronized void load() {
        Path path = path();
        if (!Files.exists(path)) {
            this.data = new Data();
            return;
        }
        try {
            Data loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
            this.data = loaded == null ? new Data() : loaded;
            if (this.data.skills == null) {
                this.data.skills = new ArrayList<>();
            }
            this.data.skills.removeIf(s -> s == null || s.name == null || s.name.isBlank());
            for (LumenSkill s : this.data.skills) {
                if (s.aliases == null) {
                    s.aliases = new ArrayList<>();
                }
                if (s.target == null) {
                    s.target = "";
                }
                if (s.action == null) {
                    s.action = LumenSkill.INTERACT;
                }
            }
            Lumen.LOGGER.info("Loaded {} learned skill(s)", this.data.skills.size());
        } catch (IOException | RuntimeException e) {
            Lumen.LOGGER.error("Could not read {}, starting with no skills: {}", path, e.toString());
            this.data = new Data();
        }
    }

    public synchronized void save() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this.data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Lumen.LOGGER.error("Could not write {}: {}", path, e.toString());
        }
    }

    public synchronized int size() {
        return data.skills.size();
    }

    public synchronized List<LumenSkill> all() {
        return new ArrayList<>(data.skills);
    }

    /** Adds or replaces (by name). Returns false when the book is full. */
    public synchronized boolean put(LumenSkill skill) {
        data.skills.removeIf(s -> s.name.equalsIgnoreCase(skill.name));
        if (data.skills.size() >= MAX_SKILLS) {
            return false;
        }
        data.skills.add(skill);
        save();
        return true;
    }

    public synchronized boolean remove(String query) {
        LumenSkill found = find(query);
        if (found == null) {
            return false;
        }
        data.skills.remove(found);
        save();
        return true;
    }

    public synchronized void noteUse(LumenSkill skill) {
        skill.uses++;
        save();
    }

    /** Exact name, then alias, then whole words, then substring - "hops" finds "harvest hops". */
    @Nullable
    public synchronized LumenSkill find(@Nullable String query) {
        String q = SkillTeacher.cleanName(query);
        if (q.isEmpty()) {
            return null;
        }
        LumenSkill best = null;
        int bestScore = 0;
        for (LumenSkill skill : data.skills) {
            int score = score(skill, q);
            if (score > bestScore) {
                bestScore = score;
                best = skill;
            }
        }
        return best;
    }

    private static int score(LumenSkill skill, String q) {
        String name = skill.name.toLowerCase(Locale.ROOT);
        if (name.equals(q)) {
            return 5;
        }
        for (String alias : skill.aliases) {
            if (alias != null && alias.toLowerCase(Locale.ROOT).equals(q)) {
                return 4;
            }
        }
        if (ChestFinder.wordsMatch(q, name)) {
            return 3;
        }
        for (String alias : skill.aliases) {
            if (alias != null && ChestFinder.wordsMatch(q, alias.toLowerCase(Locale.ROOT))) {
                return 3;
            }
        }
        if (name.contains(q) || q.contains(name)) {
            return 1;
        }
        return 0;
    }

    /**
     * The block for the prompt: a bare list of names always (so the model knows what
     * exists), and the full line only for skills the player's message seems to be about.
     * Bounded, because the context window is not.
     */
    public synchronized String describeForPrompt(@Nullable String playerText, int maxDetailed) {
        if (data.skills.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("[skills you were taught]\n");
        List<String> names = new ArrayList<>();
        for (LumenSkill skill : data.skills) {
            names.add(skill.name);
        }
        out.append("- names: ").append(String.join(", ", names.subList(0, Math.min(names.size(), 20))))
                .append(names.size() > 20 ? ", ..." : "").append('\n');
        if (playerText != null) {
            String lower = playerText.toLowerCase(Locale.ROOT);
            int shown = 0;
            for (LumenSkill skill : data.skills) {
                if (shown >= maxDetailed) {
                    break;
                }
                boolean mentioned = mentions(lower, skill.name);
                for (String alias : skill.aliases) {
                    mentioned |= alias != null && mentions(lower, alias);
                }
                if (mentioned) {
                    out.append("- ").append(skill.describe()).append('\n');
                    shown++;
                }
            }
        }
        return out.toString();
    }

    private static boolean mentions(String text, String name) {
        for (String word : name.toLowerCase(Locale.ROOT).split(" ")) {
            if (word.length() >= 3 && text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
