# Lumen

An AI companion NPC for a Fabric **1.20.1** Minecraft server. Lumen wanders around
with you, talks in chat, and decides what to do next using a **local Ollama model** -
no cloud, no API keys, nothing leaves the LAN.

Built for Dierks' *Homestead* modded server (~375 mods), but it has no modpack
specific code.

## What works today (Phase 1)

- `/lumen spawn` / `/lumen despawn` - Lumen appears next to you and starts following
- Talks to players in chat, routed through Ollama (`llama3.1:8b` by default)
- Vanilla pathfinding, so modded blocks are handled natively
- Commands from the model: `idle`, `follow <player>`, `come`
- Manual overrides: `/lumen follow [player]`, `/lumen come`, `/lumen stay`
- Everything Lumen says is a **system message**, so `NoChatReports` cannot swallow it
- Malformed or partial JSON from the model degrades to a plain chat line - it never crashes
- Config at `config/lumen.json`, hot reloadable with `/lumen reload`

## Server-side only, and what that costs

Lumen is installed on the **server only**. Players join with an unmodified client.

That constraint drives the one design decision worth knowing about: **Lumen does not
register an entity type.** A server-side-only mod that adds an entity type sends
connecting clients a raw entity id their registry does not contain, and the client
cannot render (or in some cases even survive) it.

So Lumen borrows a vanilla entity type for its appearance - `minecraft:villager` by
default - while every bit of behaviour (goals, navigation, chat, the LLM loop) runs in
Lumen's own server-side class. Vanilla clients spawn an ordinary villager model and are
perfectly happy; zombies do not treat Lumen as a villager, and it makes no villager
noises.

Change the look with `appearanceEntity` in the config, e.g. `minecraft:wandering_trader`
or `minecraft:snow_golem`.

**A player-shaped Lumen with a Steve skin is not possible this way** - a player model
requires a real (fake) `ServerPlayerEntity` with a tab list entry, which is a different
mechanism with different trade-offs (no vanilla goal AI or navigation). See
[Roadmap](#roadmap).

Because Lumen wears a borrowed type, it is never written to the region file - otherwise
it would come back after a restart as a real villager. Re-run `/lumen spawn` after a
server restart.

## Install

1. Build the jar (or grab it from the `build` workflow artifacts):
   ```
   ./gradlew build
   ```
   The jar lands in `build/libs/lumen-<version>.jar` (ignore the `-sources` jar).
   `./gradlew test` runs the parser tests - the LLM response handling is covered
   there, since it is the part most likely to meet input nobody predicted.
2. Drop it in the server's `mods/` folder. Requires **Fabric API**.
3. Start the server once to generate `config/lumen.json`, then edit it.
4. `/lumen spawn`.

## Ollama setup

Lumen posts to the OpenAI-compatible endpoint on the machine running Ollama:

```
http://192.168.50.51:11434/v1/chat/completions
```

On that machine:

```powershell
setx OLLAMA_HOST 0.0.0.0        # accept LAN connections, not just localhost
setx OLLAMA_NUM_CTX 8192        # room for world state + conversation history
```

Then restart Ollama and pre-warm the model - **the first load takes 15-45 seconds**:

```
ollama run llama3.1:8b
```

On Windows, allow inbound TCP **11434** through the firewall, or the server will only
ever see connection timeouts.

Sanity check from the Minecraft server box:

```
curl http://192.168.50.51:11434/v1/models
```

## Config (`config/lumen.json`)

| Key | Default | What it does |
| --- | --- | --- |
| `enabled` | `true` | Master switch for the LLM. Lumen still walks around when off. |
| `companionName` | `Lumen` | Name in chat and above its head. |
| `ollamaUrl` | `http://192.168.50.51:11434/v1/chat/completions` | Full endpoint URL. |
| `model` | `llama3.1:8b` | Ollama model tag. |
| `temperature` | `0.8` | |
| `maxTokens` | `300` | Keeps replies chat-sized. |
| `requestTimeoutSeconds` | `90` | Generous on purpose; loaded models still take 5-15s. |
| `personality` | *(see below)* | The system prompt. |
| `chatTrigger` | `name` | `name`, `prefix`, `always` or `never`. |
| `triggerPrefix` | `!lumen` | Used by `prefix` (and always accepted). |
| `maxHistoryMessages` | `16` | Conversation turns kept as context. |
| `appearanceEntity` | `minecraft:villager` | Vanilla type clients render. |
| `maxHealth` / `movementSpeed` / `followRange` | `20` / `0.4` / `48` | Attributes. |
| `followStartDistance` / `followStopDistance` | `4.0` / `2.5` | Follow hysteresis. |
| `teleportDistance` | `24` | Past this, Lumen warps to you instead of pathing. |
| `logRawResponses` | `false` | Logs the raw LLM body. The fastest way to debug prompts. |
| `adminPermissionLevel` | `2` | Level for `spawn`, `despawn`, `reload`. |

`chatTrigger` defaults to `name`, so Lumen only answers when a message mentions it
("lumen, come here"). `always` is fun and very chatty; an 8B model takes several
seconds per line and only one request is in flight at a time.

### Personality

The default system prompt is the one from the design notes:

> You are Lumen, a buddy who just likes playing Minecraft. You're not a servant or
> assistant. Your personality is natural - sometimes curious, sometimes chill,
> sometimes excited. You love anything shiny or glowing - gold, diamonds, amethyst,
> glow berries, copper, glowstone. You start cautious with new dangers but get braver
> over time. You have opinions about builds and places. Keep messages short and
> natural, like someone typing in Minecraft chat. This is a modded world with lots of
> mods you might not recognize - be curious about unfamiliar things.

The mod appends the output contract and a snapshot of the world (position, dimension,
time of day, health, current activity, nearby players) to every request.

## How a turn works

1. A player says something that mentions Lumen (or an operator runs `/lumen say ...`).
2. The message, recent history and a world snapshot go to Ollama on a daemon thread -
   the server thread is never blocked.
3. The model replies with:
   ```json
   {"reason":"...","command":"...","message":"..."}
   ```
4. The response is parsed off the raw text (code fences, stray prose and missing fields
   are all tolerated). Anything that is not JSON at all becomes the chat line verbatim.
5. Back on the server thread: `message` is broadcast, `command` updates Lumen's goals.

Ollama's grammar constraint is not used - it is unreliable here, so the format is
enforced by the system prompt, and the parser is written to expect the model to
misbehave anyway.

## Commands

| Command | Permission | |
| --- | --- | --- |
| `/lumen` or `/lumen status` | everyone | Endpoint, model, current activity |
| `/lumen say <message>` | everyone | Talk to Lumen regardless of `chatTrigger` |
| `/lumen come` / `stay` / `follow [player]` | everyone | Manual control, no LLM involved |
| `/lumen spawn` / `despawn` / `reload` | level 2 | |

Right-clicking Lumen reports what it is currently doing.

## Roadmap

**Phase 2 - smarter movement**
- Baritone integration for real tasks (`mine iron_ore`, `goto x y z`)
- Wider command vocabulary, remembered home coordinates

**Phase 3 - polish**
- Player model + custom skin (needs the fake-`ServerPlayerEntity` route)
- Combat, inventory, food, death drops
- Memory that survives a restart

## Notes from the field

Hard-won details that shaped this build:

1. NoChatReports drops unsigned player chat - companion lines must be system messages.
2. Automatone / forked Baritone `StackOverflowError`s on modded blocks in recursive
   `canWalkThrough` checks. Vanilla pathfinding handles them natively.
3. `useGrammar` on Ollama is not reliable; enforce the JSON shape in the prompt.
4. Null fields in the model's JSON will crash naive history handling - null-check first.
5. `OLLAMA_HOST=0.0.0.0` is required for LAN access.
6. First model load: 15-45 seconds. Pre-warm it.
7. `OLLAMA_NUM_CTX=8192` leaves room for game state plus conversation.
8. Responses take 5-15s under load - async with a 90s timeout.
9. Windows Firewall must allow inbound 11434.

## Licence

MIT - see [LICENSE](LICENSE).
