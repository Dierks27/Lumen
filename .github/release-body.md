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
- `/lumen containers` lists nearby containers and which of them Lumen can search;
  `/lumen drop` hands back everything it is carrying.
- If Lumen gets stuck, `/lumen here` warps it to you and `/lumen why` names the blocks
  around it that vanilla pathfinding refuses to route through - that output is the
  fastest way to find the mod at fault.
- Set `logRawResponses: true` if the model drifts from the expected JSON format —
  that log is where you will see it.

Full setup and configuration reference: [README](https://github.com/Dierks27/Lumen#readme).
