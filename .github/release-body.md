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
ollama run llama3.1:8b
```

## Notes

- Lumen borrows a vanilla entity type for its appearance (`minecraft:villager` by
  default, configurable) so that unmodified clients can render it. There is no
  Steve skin yet — see the README for why.
- Lumen is not saved to the world. Re-run `/lumen spawn` after a server restart.
- `chatTrigger` defaults to `name`, so Lumen only answers when a message mentions
  it. Set it to `always` if you want it to reply to everything.
- Set `logRawResponses: true` if the model drifts from the expected JSON format —
  that log is where you will see it.

Full setup and configuration reference: [README](https://github.com/Dierks27/Lumen#readme).
