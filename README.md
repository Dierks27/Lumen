# Lumen

An AI companion NPC for a Fabric **1.20.1** Minecraft server. Lumen wanders around
with you, talks in chat, and decides what to do next using a **local Ollama model** -
no cloud, no API keys, nothing leaves the LAN.

Built for Dierks' *Homestead* modded server (~375 mods), but it has no modpack
specific code.

## What works today

**Talking**
- Chat goes through Ollama (`qwen2.5:14b` by default) and comes back as a
  `{reason, command, message}` object
- Everything Lumen says is a **system message**, so `NoChatReports` cannot swallow it
- Malformed or partial JSON degrades to a plain chat line - it never crashes
- Conversation history, including lines Lumen overheard but did not answer

**Knowing where it is**
- Every prompt carries what Lumen can actually see: biome, time, weather, light,
  what it is standing on and looking at, nearby players, hostiles, animals, ground
  items, and the blocks in range
- Blocks that glow or hold a block entity are always called out, so modded ores and
  machines get noticed without the mod having to be known
- The system prompt states that anything absent from that list is not visible, which
  is the lever against invented detail

**Remembering**
- Where things were found is kept in `config/lumen/memory.json` and survives restarts
- Ask for something fetched before and Lumen goes straight back to that container
- Memories that turn out wrong are forgotten and the search starts again

**Moving**
- Opens and walks through wooden doors
- A relaxed path node maker walks through modded blocks that have no collision box
  but report themselves as solid (see [Pathfinding](#pathfinding-and-modded-blocks))
- Stuck detection re-paths and, as a last resort, warps - no more despawn/respawn
- Follows, goes to a spot, wanders when idle

**Doing things**
- A 27 slot pack: hand items over by right-clicking, picks things up off the ground,
  wears or wields anything better than what it has, drops it all on death
- Fetches from chests, barrels and modded containers: *"lumen find me some iron"*
- Defends itself and whoever it is following against hostile mobs

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

## Pathfinding and modded blocks

Baritone cannot drive Lumen, and it is worth being clear why. Standard Baritone is a
**client** mod: it controls `Minecraft.getInstance().player` through client tick and
input hooks, and the 1.20.1 build is a Forge jar. It will not load on a Fabric server,
and there is no player client for it to attach to. That stays true for the Phase 3
fake-player route, because a fake `ServerPlayerEntity` is still server side. Automatone
is the fork that *does* drive server-side mobs - and it is the one that blows the stack
on modded blocks.

So Lumen fixes the actual symptom instead. Vanilla decides whether a block can be walked
through with `AbstractBlock#canPathfindThrough`, which mods routinely leave reporting
their block as solid even when it has no collision box at all - decorative clutter,
cables, pipes, plants. `LumenPathNodeMaker` says: if the pathfinder calls it BLOCKED but
it cannot actually be collided with, treat it as open. One non-recursive shape lookup,
so it cannot recurse into a stack overflow the way Automatone did.

When Lumen still gets stuck, **`/lumen why`** names the blocks around it that vanilla
refuses to route through, and whether the relaxation lets it through anyway. In a 375
mod pack that output is the fastest way from "he is stuck" to the mod at fault - please
paste it into an issue.

## Install

1. Grab `lumen-<version>.jar` from the
   [latest release](https://github.com/Dierks27/Lumen/releases/latest).

   Or build it yourself:
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
ollama run qwen2.5:14b "hello"
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
| `model` | `qwen2.5:14b` | Ollama model tag. |
| `temperature` | `0.8` | |
| `maxTokens` | `300` | Keeps replies chat-sized. |
| `requestTimeoutSeconds` | `90` | Generous on purpose; loaded models still take 5-15s. |
| `personality` | *(see below)* | The system prompt. |
| `chatTrigger` | `always` | `name`, `prefix`, `always` or `never`. |
| `triggerPrefix` | `!lumen` | Used by `prefix` (and always accepted). |
| `maxHistoryMessages` | `24` | Chat lines kept as context. |
| `appearanceEntity` | `minecraft:villager` | Vanilla type clients render. |
| `canOpenDoors` | `true` | Path through and open wooden doors. |
| `stuckRepathTicks` / `stuckTeleportTicks` | `60` / `160` | No-progress thresholds before re-pathing, then warping. |
| `inventorySize` | `27` | |
| `acceptItemsFromPlayers` / `pickUpItems` | `true` | Right-click handover, ground pickup. |
| `dropInventoryOnDeath` | `true` | |
| `allowChestAccess` | `true` | Let Lumen take requested items out of containers. |
| `chestSearchRadius` / `memoryRecallRadius` | `16` / `64` | Cold search vs. walking to a remembered container. |
| `maxFetchStacks` | `3` | Stacks taken per errand. |
| `combat` / `attackDamage` / `defendRadius` | `true` / `3.0` / `12` | Defends itself and whoever it follows. |
| `awarenessBlockRadius` / `awarenessEntityRadius` | `8` / `24` | How much world goes into the prompt. |
| `maxHealth` / `movementSpeed` / `followRange` | `20` / `0.4` / `48` | Attributes. |
| `followStartDistance` / `followStopDistance` | `4.0` / `2.5` | Follow hysteresis. |
| `teleportDistance` | `24` | Past this, Lumen warps to you instead of pathing. |
| `logRawResponses` | `false` | Logs the raw LLM body. The fastest way to debug prompts. |
| `adminPermissionLevel` | `2` | Level for `spawn`, `despawn`, `reload`. |

`chatTrigger` defaults to `always`, so Lumen answers everything. Set it to `name` if
that gets noisy - it will then only answer when a message mentions it. Either way only
one request is in flight at a time; lines that arrive while Lumen is thinking are
remembered rather than dropped.

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
| `/lumen here` | everyone | Warp Lumen to you - the escape hatch when pathing loses |
| `/lumen find <item>` | everyone | Fetch an item from a nearby container |
| `/lumen inventory` | everyone | What Lumen is carrying and wearing |
| `/lumen memory` | everyone | Places Lumen remembers finding things |
| `/lumen why` | everyone | Why Lumen is not moving, and what is blocking it |
| `/lumen spawn` / `despawn` / `reload` / `forget` | level 2 | |

Right-clicking Lumen hands over whatever you are holding, or reports what it is doing
if your hand is empty.

## Roadmap

**Next - productive work**
- Mining, chopping and gathering on request
- Named places (`remember this as home`) and going back to them
- Crafting from what is in the pack

**Phase 3 - polish**
- Player model + custom skin from `config/lumen/skins/`. This needs the fake
  `ServerPlayerEntity` route, which trades away vanilla goal AI and navigation for a
  player-shaped body. The brain, config, memory, chest and chat layers are all
  independent of the entity class, so that swap stays possible.
- Food and hunger
- Conversation highlights carried across restarts, alongside the location memory

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

## Releases

`mod_version` in `gradle.properties` is the single source of truth. When a push
names a version that has no release yet, the `release` workflow builds the mod,
tags the commit and publishes a GitHub release with the jar attached. So cutting
a release is just:

```
mod_version=0.2.0
```

Pushes that do not change the version finish the check in a few seconds and stop.

## Licence

MIT - see [LICENSE](LICENSE).
