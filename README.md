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
- **Named places.** Stand somewhere and say *"remember this as the hops room"* (or
  `/lumen remember hops room`). From then on *"go to the hops room"*, *"find hops from
  the hops room"* and *"mine copper near the copper spot"* all work, in chat and as
  `/lumen go`, `/lumen find` and `/lumen mine`. Names match loosely - "hops", "the hops
  room" and "hopsroom" are all the same place. `/lumen memory` lists them,
  `/lumen forget <place>` drops one. The model is told which places are nearby and how
  far, so it can talk about them

**Doing several things**
- A second request waits its turn instead of cancelling the first. *"Grab me some iron,
  then go mine some copper, then come back"* is three jobs run in order; so is saying
  them one at a time while it works. `/lumen queue` shows the list
- *"Come here"* and *"follow me"* interrupt: the errand is paused, not lost, and
  *"carry on"* (or `/lumen continue`) picks it back up with whatever was still owed.
  *"Stop"* or `/lumen cancel` throws the lot away
- With combat on, a fight interrupts an errand and the errand resumes when it is over
- Something the model says in passing ("stop", "follow") only counts when you actually
  said it - a chatty model no longer talks Lumen out of a job

**Fetching**
- Takes the amount you asked for and stops: *"grab me 12 redstone"* is 12, not the
  chest. "a dozen", "a couple", "a stack", "half a stack", "3 stacks", "all" and
  "x12" all count, and if the model drops the number from its command the amount is
  read back off what you said
- *"stone"* means stone. Matching is tiered - the exact item first, then whole words
  (*"oak log"* finds dark oak logs, not oak planks), then substrings - and only the
  best tier in a container is touched, so a stack of stone no longer comes back as
  30 stone and 42 cobblestone
- Only containers it can actually walk to. Every candidate gets a real path test, so
  the cabinet on the floor above is off limits unless there are stairs
- Storage networks count: anything that exposes items through the Fabric transfer
  API - Tom's Storage inventory connectors, drawers, most modded storage - is
  searched alongside plain chests
- Honest about shortfalls: *"found 9 white wool but you asked for 12 - that's all I
  could find"*, then it walks back to you with it

**Moving**
- Opens and walks through wooden doors and fence gates, and closes them behind it -
  including doors you opened and walked through ahead of it
- A relaxed path node maker walks through modded blocks that have no collision box
  but report themselves as solid (see [Pathfinding](#pathfinding-and-modded-blocks))
- Stuck detection re-paths and, as a last resort, warps - no more despawn/respawn.
  A path that goes nowhere counts as no path, so "cannot get back inside" is
  answered by a warp in a few seconds rather than eight
- Follows, goes to a spot, wanders when idle

**Doing things**
- A real inventory: right-click Lumen (empty handed, or sneaking) to open its pack as
  an ordinary chest screen and hand over tools, armour, weapons and food. The bottom
  row of that screen is what it is holding and wearing - main hand, off hand, helmet,
  chestplate, leggings, boots - so a sword it equipped can be taken straight back. It
  wears or wields anything better than what it has, picks things up off the ground,
  and drops the lot on death
- *"give me the sword"*, `/lumen give sword`, `/lumen drop`: items go straight into
  your inventory, never onto the floor. (On the floor they were Lumen's again within
  a tick - the loot goblin loop.) If your inventory is full the rest is set down at
  your feet and Lumen leaves it alone
- Fetches from chests, barrels and modded containers: *"lumen find me some iron"*
- Mines on request: *"lumen go mine some iron"* - walks to the ore, breaks it at the
  speed its tool allows, and brings the haul back
- Defends itself and whoever it is following against hostile mobs, and eats from its
  pack when hurt

**Teaching it jobs**
- A skill is a name and a list of steps, taught in one sentence: *"lumen, learn
  restock: take 16 wheat from the storage chest, then put it in this barrel"* (or
  `/lumen teach ...`). Steps are split on "then": **walk to** a place, **right-click**
  any block (a lever, a crank, a crop), **break** any block, **take** items from a
  container, **put** items into a container, **hold** a tool from the pack, **wait** N
  seconds, **say** something, **collect the drops**, **come back**. From then on
  *"restock"* or `/lumen do restock` runs the steps in order. Skills live in
  `config/lumen/skills.json`, survive restarts, and a v0.8.0 file loads as-is
- Ripeness is optional, not required: *"right click the lever"* is a skill. *"Right
  click these when they're ripe"* adds the ripeness filter
- "These", "this chest" and "here" are grounded on the block you are looking at when
  you teach: a crop stores its real block id, a chest stores its exact position, so
  the skill goes back to that chest every time. "The nearest chest" and "a chest with
  tools" also work. `/lumen look` shows what Lumen reads off the block in front of you
- A number caps a run: *"harvest 10 hops"*, `/lumen do 10 hops`, *"take 16 wheat"*
- Ripeness is mod-agnostic: a block that implements vanilla's bone-meal check
  (`Fertilizable`) is ripe when it can grow no further, and an `age`, `stage`,
  `growth` or `maturity` property at its maximum counts as ripe even when the block
  claims it could still grow, because some modded crops say yes to bone meal at every
  age. You can also pin a state yourself: *"sweet berry bush age=3"*
- Right-clicks that hand the harvest to the clicking player (some mods do that instead
  of dropping it) are noticed: whatever appears in your inventory during the click is
  taken back and brought to you with the rest
- Job summaries say why blocks were skipped - *"3 i couldn't reach, 2 not ripe or
  already done"* - and a harvested plant is never clicked twice
- The catch, verified against the 1.20.1 sources: a mob cannot right-click a block by
  itself - `AbstractBlock#onUse` needs a real player and Fabric API 1.20.1 has no
  `FakePlayer`. So a right-click skill acts in the name of the player who asked, who
  must be online, in the same dimension and within `interactRange`. Containers are
  never right-clicked, so a click step cannot open your chests; take and put steps go
  through the same storage access as fetching. `/lumen skills` lists what it knows,
  `/lumen skill <name>` shows its steps, `/lumen forget skill <name>` drops it
- *"Put the cobblestone in this chest"* and `/lumen put everything in the nearest
  chest` work on their own too, without teaching

**Quarrying and digging down**
- *"Go down to level 2 and mine out a 20x20x2 and bring me back all the loot"* is one
  sentence and one job: Lumen digs a staircase down to the level, clears the area top
  down in a serpentine, skips anything next to water or lava, then comes back with the
  haul. Sizes read as `WxLxH`, *"10 by 10"*, *"3 deep"*; levels as *"level 2"*,
  *"y -20"*, *"bedrock"*, *"diamond level"*. A quarry clears every block in the area,
  whatever it is made of
- *"Go down to level 12"* on its own, or `/lumen down 12`, digs a staircase in the
  direction Lumen is facing - one block down per block forward, head then feet - so
  there is always a walkable way back up. Water or lava in the way stops the stairs
  with a message rather than a splash
- Lumen never mines the block under its own feet unless the block beneath that is
  solid, and a plain *"mine stone"* never targets the floor at all. That is the end of
  the one-wide shaft straight down into lava
- `/lumen wand` hands you a selection wand (a stick). Left-click one corner, right-click
  the other - the selection is outlined in particles - then *"mine out the selection"*
  or a bare `/lumen quarry`
- Bounded by `maxQuarryBlocks`, `maxQuarrySize` and `maxQuarryDescent`; containers and
  block entities are never broken, and claims apply the requesting player's permissions

**Crafting**
- *"Craft 8 sticks"*, *"I need a pickaxe"*, `/lumen craft 4 torches`: Lumen plans the
  recipe from what is in its pack, recursively - a stone pickaxe needs sticks, which
  need planks, which need a log - and names what is missing if it cannot (*"i'd need
  any planks for stick"*). Every recipe for an item is considered, the ones that use
  what is in the pack first, so a modded log becomes that mod's planks and then
  sticks. Recipes that need a table send it to the nearest crafting table first (or
  set `craftingNeedsTable: false`)

**Getting around**
- The path search runs `pathSearchEffort` times harder than a vanilla mob's. Vanilla's
  budget of 768 nodes is spent pressed against the floor when the target is one storey
  down, which is exactly *"I can't reach the hops"* said from the roof; with room to
  search, the stairs across the house are found. When something is still unreachable,
  Lumen says which way it needs to go: *"i can't find a way down to the hops - they're
  6 blocks below me. if there are stairs or a door, walk me there and ask again"*
- Lava, fire, magma and cactus are never walked on, at any cost. Water has its vanilla
  path cost again (`waterPenalty`), so ponds are walked around, and a swim that goes
  nowhere for four seconds ends with a hop to the nearest dry block
- *"Stand here"*, `/lumen go here` and `/lumen go 100 64 -20` put Lumen on an exact
  spot before a job. Ladders are still beyond vanilla pathfinding; stairs, slabs and
  doors are fine

**Staying alive**
- Hostile mobs now target Lumen (`hostilesAttackLumen`), and Lumen gets hungry: the
  food bar drains one point every `hungerDecaySeconds`, it eats from its pack when
  hungry or hurt, and below six food it walks slowly and says so. The name tag reads
  `Lumen ♥16` (and `hungry`) so you can see how it is doing from across the room
- One life per `respawnCooldownMinutes` (two hours by default). When it dies the pack
  drops, the death is recorded in memory, and `/lumen spawn` refuses until the time is
  up - `/lumen status` shows how long is left. An admin can `/lumen spawn force`

**Notes to self**
- Every `notesEveryMessages` chat lines the model writes a few one-line notes about
  what mattered - what you built together, what you asked it to remember, jokes that
  landed - into `config/lumen/memory.json`. They come back in the prompt after a
  restart. `/lumen notes` shows them, `/lumen forget note <n>` and
  `/lumen forget notes` drop them

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

Change the look with `appearanceEntity` in the config. Anything the client can render
works, but the hitbox and the attribute set come with the type, so some fit better than
others:

| `appearanceEntity` | Fit | Notes |
| --- | --- | --- |
| `minecraft:villager` (default) | good | Human-sized, walks through 1x2 gaps. Stock look |
| `minecraft:wandering_trader` | good | Same body, blue robe, easy to pick out |
| `minecraft:illusioner` / `minecraft:evoker` / `minecraft:pillager` | good | Human-sized. Renders with the arms crossed or the crossbow of the type |
| `minecraft:zombie_villager` / `minecraft:witch` | fine | Human-sized; a witch renders the hat |
| `minecraft:snow_golem` | fine | Slightly taller; leaves no snow trail (behaviour is Lumen's, not the type's) |
| `minecraft:allay` / `minecraft:vex` | poor | Tiny hitbox: renders floating at knee height and clips through fences |
| `minecraft:iron_golem` | poor | 2.7 blocks tall - cannot follow you indoors |
| `minecraft:slime` / `minecraft:magma_cube` | poor | The hitbox is the large size, so doorways are out |
| `minecraft:armor_stand`, items, projectiles | refused | Not a mob: no movement or follow-range attributes. Lumen logs a warning and falls back to the villager |

At spawn Lumen checks the type has the attributes a walking mob needs and falls back to
the villager otherwise, so a typo in the config never crashes the server.

**A player-shaped Lumen with a Steve skin is not possible this way** - a player model
requires a real (fake) `ServerPlayerEntity` with a tab list entry, which is a different
mechanism with different trade-offs (no vanilla goal AI or navigation). See
[Roadmap](#roadmap).

Because Lumen wears a borrowed type, it is never written to the region file - otherwise
it would come back after a restart as a real villager. Re-run `/lumen spawn` after a
server restart.

## Combat

**Off by default.** v0.3.0 shipped an aggro leak that killed a player: `RevengeGoal`
retaliates against whoever last damaged Lumen with no filter at all, so one stray swing
during a shared fight turned it on the player. That goal is gone, and a player can no
longer become a target through any path - `setTarget` refuses one outright, `tryAttack`
refuses to swing at one, the defend predicate excludes them, and aggro drops the tick
the target dies.

Set `combat: true` to switch it back on. It is opt-in rather than default because a bug
in this area costs someone their inventory.

## Mining

`lumen go mine some iron`, or `/lumen mine iron`. Lumen finds the nearest matching
block it can actually reach, walks to a neighbouring space, and breaks it at roughly
the speed a player holding the same tool would manage - with the vanilla cracking
overlay, so you can watch it work. The drops are banked for whoever asked rather than
scattered on the floor, and the tool takes durability.

Since this is the one feature that changes the world without being watched, it is
fenced in:

- **Containers are never broken.** Any block with a block entity is skipped, so
  chests, furnaces and modded machines are safe.
- **Ore wins ties.** "mine iron" heads for iron ore, not the iron blocks in a wall.
- **No tool, no mining.** A block that needs a pickaxe Lumen does not have ends the
  errand with a message rather than silently failing. Give it a pickaxe through its
  inventory screen.
- **Bounded.** `maxMineBlocks` per errand, `miningRadius` for the search, and
  `allowMining: false` turns the whole thing off.
- **The tool is checked before setting out**, so "I'd need a pickaxe" is said up front
  rather than after standing there playing the whole break animation. Put one in its
  pack first.
- **Drops are banked only after the block actually breaks.** Doing it the other way
  round meant a refused break still handed over items - items from nothing.
- **Inside a claim, it breaks blocks as you.** The block break is done in the name of
  whoever asked, and Lumen reports itself as owned by that player (the same
  `Tameable` hook a wolf uses), so Open Parties and Claims and mods like it apply your
  permissions rather than refusing a stray mob. If it still cannot break something,
  the message says which player it tried as - check that player's claim permissions.

Mining and fetching are separate: `mine` breaks blocks, `find` searches containers.
Neither silently becomes the other.

## Pathfinding and modded blocks

Baritone cannot drive Lumen, and it is worth being clear why. Standard Baritone is a
**client** mod: it controls `Minecraft.getInstance().player` through client tick and
input hooks, and the 1.20.1 build is a Forge jar. It will not load on a Fabric server,
and there is no player client for it to attach to. That stays true for the Phase 3
fake-player route, because a fake `ServerPlayerEntity` is still server side.

Automatone is the Baritone fork that *does* drive server-side mobs, and earlier versions
of this README repeated the folklore that it "blows the stack on modded blocks in
recursive `canWalkThrough` checks". That was checked against the source and it is not
true: `MovementHelper.canWalkThrough` is not recursive (its only outward call is
`canWalkOn`, which never calls back). The one real `StackOverflowError` in Automatone is
an unconditional self-call in `EntityContext.worldData()`, reached only from its `chests`
command, on any block, vanilla or modded. The actual reasons it is not used here are
duller and decisive: the only 1.20.1 build is a **Quilt** jar with no `fabric.mod.json`,
so it does not load on a Fabric server at all; it is **LGPL-3.0**, so a fork could never
be MIT; its `MixinMobEntity` cancels `MobEntity.tickNewAi()` while pathing, which would
switch off every one of Lumen's goals; and it hands modded blocks `BlockPos.ORIGIN` and
sometimes a `null` world when asking about passability, which is a worse modded-block
hazard than the one it was blamed for.

So Lumen fixes the actual symptom instead. Vanilla decides whether a block can be walked
through with `AbstractBlock#canPathfindThrough`, which mods routinely leave reporting
their block as solid even when it has no collision box at all - decorative clutter,
cables, pipes, plants. `LumenPathNodeMaker` says: if the pathfinder calls it BLOCKED but
it cannot actually be collided with, treat it as open. That check asks the real world at
the real position, and a modded block that throws while answering is treated as solid
rather than allowed to kill the tick.

When Lumen still gets stuck, **`/lumen why`** runs a real path test to wherever it is
trying to get to (or to you, when idle) and reports whether the path reaches, where it
ends, and what surrounds that spot. It then names the blocks around Lumen's feet by the
verdict vanilla pathfinding gives each one: walls are "solid", slabs, stairs and carpets
are "half block - steps onto it", doors and gates are things it opens, and blocks with
no collision box are ones it walks through anyway. In a 375 mod pack that output is
the fastest way from "he is stuck" to the mod at fault - please paste it into an issue.

A note on slabs: they are half blocks and Lumen steps onto them like a villager does.
An earlier `/lumen why` listed the slab floor Lumen was standing on as "solid", which
was the diagnostic being unhelpful rather than the pathfinder refusing slabs.

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
| `inventorySize` | `27` | 27 or 45 pack slots; the screen adds an equipment row underneath, so it shows as a 9x4 or 9x6 chest. |
| `acceptItemsFromPlayers` / `pickUpItems` | `true` | Right-click handover, ground pickup. |
| `dropInventoryOnDeath` | `true` | |
| `allowChestAccess` | `true` | Let Lumen take requested items out of containers. |
| `allowMining` | `true` | Let Lumen break blocks on request. |
| `miningRadius` / `miningHeight` | `12` / `8` | How far it looks for something to mine. |
| `maxMineBlocks` | `8` | Blocks broken per errand before it brings the haul back. |
| `eatWhenHurt` / `eatHealthFraction` | `true` / `0.6` | Eat from the pack below this much health. |
| `chestSearchRadius` / `memoryRecallRadius` | `48` / `128` | Cold search vs. walking to a remembered container. |
| `maxFetchStacks` | `3` | Stacks taken per errand. |
| `combat` / `attackDamage` / `defendRadius` | `false` / `3.0` / `12` | Off by default - see [Combat](#combat). |
| `awarenessBlockRadius` / `awarenessEntityRadius` | `8` / `24` | How much world goes into the prompt. |
| `maxHealth` / `movementSpeed` / `followRange` | `20` / `0.4` / `48` | Attributes. |
| `followStartDistance` / `followStopDistance` | `4.0` / `2.5` | Follow hysteresis. |
| `teleportDistance` | `24` | Past this, Lumen warps to you instead of pathing. |
| `allowInteract` / `interactRange` | `true` / `32` | Right-click skills, and how close the player who asked must stay (a mob cannot click a block on its own). |
| `allowTeaching` / `maxSkillBlocks` / `maxSkillSteps` | `true` / `32` / `12` | Whether players can teach skills, blocks handled per click or break step, steps per skill. |
| `pathSearchEffort` | `8` | How many times harder than a vanilla mob Lumen searches for a route. Raise it for a big multi-storey base. |
| `waterPenalty` | `8` | Path cost of a water block, vanilla's value. Lava, fire, magma and cactus are never walked on. |
| `allowQuarry` / `maxQuarryBlocks` | `true` / `512` | Area digging, and the most blocks one quarry job may break. |
| `maxQuarrySize` / `maxQuarryDescent` | `32` / `40` | Longest side of an area, and how far below Lumen it will dig a staircase. |
| `allowCrafting` / `craftingNeedsTable` | `true` / `true` | Crafting from the pack; whether 3x3 recipes need a real crafting table nearby. |
| `hostilesAttackLumen` / `aggroRadius` | `true` / `10` | Hostile mobs within the radius pick Lumen as a target. |
| `hungerEnabled` / `hungerDecaySeconds` | `true` / `240` | One food point lost every so many seconds; below 6 it is slow and hungry. |
| `showHealthInName` | `true` | `Lumen ♥16` above its head. |
| `respawnCooldownMinutes` | `120` | After a death, `/lumen spawn` waits this long. `0` disables it. |
| `conversationNotes` / `notesEveryMessages` | `true` / `30` | Let the model keep notes about the conversation, and how often. |
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
| `/lumen come` / `stay` / `follow [player]` | everyone | Manual control, no LLM involved. `come` and `follow` pause an errand; `stay` cancels everything |
| `/lumen remember <name>` | everyone | Save where you are standing as a named place |
| `/lumen go <place>` / `go here` / `go <x> <y> <z>` | everyone | Walk to a named place, to where you stand, or to coordinates |
| `/lumen queue` / `cancel` / `continue` | everyone | What is lined up; drop it all; resume a paused errand |
| `/lumen here` | everyone | Warp Lumen to you - the escape hatch when pathing loses |
| `/lumen find <item> [from <place>]` | everyone | Fetch an item from a nearby container, or one near a named place |
| `/lumen mine <block> [near <place>]` | everyone | Go break blocks of that kind and bring them back |
| `/lumen inventory` | everyone | What Lumen is carrying and wearing |
| `/lumen memory` | everyone | Places Lumen remembers finding things |
| `/lumen drop` | everyone | Hand back everything it is carrying, straight into your inventory |
| `/lumen give [item]` | everyone | Hand back one thing: `/lumen give sword`. No item means everything |
| `/lumen debug` | everyone | What the model last said, and what became of it |
| `/lumen containers` | everyone | Nearby containers, and which are searchable |
| `/lumen why` | everyone | A path test to its target, and what is blocking it |
| `/lumen look` | everyone | What Lumen sees in the block you are looking at: id, state, whether it reads as ripe |
| `/lumen teach <name>: <steps>` | everyone | Teach a skill from a sentence, steps split on "then", grounded on the block or chest you are looking at |
| `/lumen skills` / `skill <name>` / `do [n] <skill>` | everyone | List skills; show one's steps; run one, optionally capped at n |
| `/lumen put [n] <item> in <container>` | everyone | Put things from the pack into a container: `put 16 wheat in this chest`, `put everything in the nearest chest` |
| `/lumen down <y>` | everyone | Dig a staircase down to that level |
| `/lumen wand` | everyone | Get a selection wand: left-click one corner, right-click the other |
| `/lumen quarry [area]` | everyone | Dig out the wand selection, or a described area: `/lumen quarry 10x10x2 level 40` |
| `/lumen craft [n] <item>` | everyone | Craft from the pack, recursively |
| `/lumen notes` | everyone | The notes the model has kept about your conversations |
| `/lumen forget <place>` / `skill <name>` / `note <n>` / `notes` | everyone | Forget one place, one skill, one note, or every note |
| `/lumen spawn` / `despawn` / `reload` / `forget` | level 2 | Bare `forget` clears every memory. `spawn force` overrides the respawn cooldown |

Right-clicking Lumen with something in hand gives it that item. Right-clicking empty
handed - or while sneaking - opens its pack as a chest screen you can move things in
and out of. The bottom row is what it is holding and wearing.

## Roadmap

**Next**
- A right-click that does not need the player nearby. This needs a fake player, which
  Fabric API 1.20.1 does not ship; a minimal server-side one is the candidate
- Ladders and scaffolding as routes. Vanilla land pathfinding only plans one-block
  steps; climbing needs its own node type
- Conditions in skills: "if the chest is empty, say so and stop"
- Storage that exposes items through neither `Inventory` nor the Fabric transfer API
  (`/lumen containers` shows which)
- Smelting and other machine recipes when crafting

**Phase 3 - polish**
- Player model + custom skin from `config/lumen/skins/`. This needs the fake
  `ServerPlayerEntity` route, which trades away vanilla goal AI and navigation for a
  player-shaped body. The brain, config, memory, chest and chat layers are all
  independent of the entity class, so that swap stays possible.

## Notes from the field

Hard-won details that shaped this build:

1. NoChatReports drops unsigned player chat - companion lines must be system messages.
2. Automatone does not fit: Quilt-only at 1.20.1, LGPL-3.0, and it disables the goal
   selector while pathing. Its `canWalkThrough` is not recursive; that was folklore.
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
