A server-side AI companion for **Fabric 1.20.1**. Install it on the server only —
players connect with an unmodified client.

## Install

1. Download `lumen-<version>.jar` from the assets below.
2. Drop it into the server's `mods/` folder. **Fabric API** is required.
3. Start the server once to generate `config/lumen.json`, then set `ollamaUrl`
   and `model` to match your Ollama host.
4. `/lumen spawn`.

## Before you spawn it

On the machine running Ollama:

```
OLLAMA_HOST=0.0.0.0      # accept LAN connections
OLLAMA_NUM_CTX=8192      # room for world state + conversation
```

Restart Ollama, allow inbound TCP **11434** through the firewall, and pre-warm the
model — the first load takes 15-45 seconds:

```
ollama run qwen2.5:14b "hello"
```

## Notes

- Lumen borrows a vanilla entity type for its appearance (`minecraft:villager` by
  default, configurable) so that unmodified clients can render it. There is no
  Steve skin yet — see the README for why.
- Lumen is not saved to the world. Re-run `/lumen spawn` after a server restart.
- `chatTrigger` defaults to `always`, so Lumen answers everything. Set it to `name`
  if that gets noisy and it will only reply when a message mentions it.
- Lumen remembers where it found things in `config/lumen/memory.json`, so it survives
  restarts. `/lumen memory` shows what it knows, `/lumen forget` clears it.
- Right-click Lumen empty handed (or while sneaking) to open its pack and give it
  tools, armour, weapons and food. Give it a pickaxe before asking it to mine.
- `/lumen mine <block>` and `/lumen find <item>` send it off to work. Mining never
  breaks containers and is bounded by `maxMineBlocks`; set `allowMining: false` to
  disable it entirely.
- **Combat is off by default.** v0.3.0 had an aggro leak that killed a player; the
  path is closed four ways over in v0.3.1, but switching it back on should be your
  choice. Set `combat: true` when you are ready.
- `/lumen debug` shows what the model last replied, how the command was understood
  and what became of it - the first thing to check if an instruction seems ignored.
- `/lumen containers` lists nearby containers and which of them Lumen can search.
  Storage networks that expose items through the Fabric transfer API - Tom's Storage
  inventory connectors included - are searchable.
- `/lumen drop` and `/lumen give <item>` hand things back straight into your
  inventory, never onto the floor. "give me the sword" in chat does the same. The
  pack screen's bottom row shows what Lumen is holding and wearing.
- Fetching takes the amount you asked for and stops, matches the exact item before
  look-alikes (stone is not cobblestone), only uses containers it can walk to, and
  says plainly when it found fewer than you asked for.
- If Lumen gets stuck, `/lumen here` warps it to you and `/lumen why` runs a path test
  to its target and names what is around it - that output is the fastest way to find
  the mod at fault.
- Inside a claim, blocks are broken in the name of whoever asked, so Open Parties and
  Claims applies that player's permissions.
- Named places: stand somewhere and say "remember this as the hops room", then "go to
  the hops room" or "find hops from the hops room". `/lumen memory` lists them.
- A second request waits its turn instead of cancelling the first: "grab iron, then
  mine copper, then come back" runs in order. "Come here" pauses an errand and "carry
  on" resumes it; `/lumen queue` shows what is lined up.
- Teach it a job in one sentence, any steps you like: "learn restock: take 16 wheat
  from the storage chest, then put it in this barrel", "learn harvest hops: right
  click the ripe hops vines, then collect the drops", "learn start the mill: flip the
  lever". Look at the crop or chest while you say it. Then "restock" or
  `/lumen do restock`; "harvest 10 hops" caps a run. Right-click steps act as the
  player who asked, who has to stay within `interactRange`. `/lumen skills`,
  `/lumen skill <name>`, `/lumen look`, `config/lumen/skills.json`.
- "Put the cobblestone in this chest", `/lumen put everything in the nearest chest`.
- Pathfinding searches `pathSearchEffort` (8) times harder than a vanilla mob, so the
  stairs to the floor below are found. Lava, fire, magma and cactus are never walked
  on; water costs what it should again; a swim that goes nowhere ends on dry land.
- "Go down to level 12" / `/lumen down 12` digs a staircase, never a shaft, and Lumen
  no longer mines the floor from under its own feet.
- Crafting tries every recipe, the ones using what is in the pack first: a modded log
  becomes planks and then sticks.
- Ripeness: an age at its maximum counts even when a modded crop claims it can still
  grow. Job summaries say why blocks were skipped.
- Area mining: "go down to level 2 and mine out a 20x20x2 and bring back the loot", or
  `/lumen wand`, mark two corners, then "mine out the selection". Clears every block
  in the area. Bounded by `maxQuarryBlocks`; never breaks containers.
- Crafting from the pack, recursively: "craft 8 sticks", "I need a pickaxe",
  `/lumen craft 4 torches`. It names what is missing when it cannot.
- Survival: hostiles target Lumen, it gets hungry and eats from its pack, the name tag
  shows hearts, and after a death `/lumen spawn` waits `respawnCooldownMinutes` (120).
  `/lumen status` shows the time left; `/lumen spawn force` overrides it.
- Conversation notes: every 30 lines the model writes down what mattered and reads it
  back after a restart. `/lumen notes` shows them.
- `appearanceEntity` now falls back to the villager for types that cannot walk (armor
  stands, items). The README has a table of which vanilla types fit.
- Set `logRawResponses: true` if the model drifts from the expected JSON format —
  that log is where you will see it.

Full setup and configuration reference: [README](https://github.com/Dierks27/Lumen#readme).
