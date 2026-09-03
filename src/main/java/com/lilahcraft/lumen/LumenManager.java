package com.lilahcraft.lumen;

import com.lilahcraft.lumen.entity.LumenEntity;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Keeps track of the one live Lumen, so commands and the brain can find it. */
public final class LumenManager {

    @Nullable
    private UUID lumenUuid;

    @Nullable
    private RegistryKey<World> lumenWorld;

    /** @return the live Lumen, or null if it was never spawned or has gone away */
    @Nullable
    public LumenEntity get(@Nullable MinecraftServer server) {
        if (server == null || lumenUuid == null) {
            return null;
        }
        if (lumenWorld != null) {
            ServerWorld world = server.getWorld(lumenWorld);
            LumenEntity found = lookup(world);
            if (found != null) {
                return found;
            }
        }
        // The world key can go stale if Lumen changed dimension; search the rest.
        for (ServerWorld world : server.getWorlds()) {
            LumenEntity found = lookup(world);
            if (found != null) {
                lumenWorld = world.getRegistryKey();
                return found;
            }
        }
        return null;
    }

    @Nullable
    private LumenEntity lookup(@Nullable ServerWorld world) {
        if (world == null || lumenUuid == null) {
            return null;
        }
        Entity entity = world.getEntity(lumenUuid);
        return entity instanceof LumenEntity lumen && lumen.isAlive() ? lumen : null;
    }

    public boolean isSpawned(@Nullable MinecraftServer server) {
        return get(server) != null;
    }

    /**
     * Spawns Lumen at the given position, replacing any existing one.
     *
     * @return the new entity, or null if the world rejected the spawn
     */
    /**
     * Why Lumen cannot be spawned right now, or null when it can. A death starts a
     * real-time cooldown so losing Lumen costs something.
     */
    @Nullable
    public String spawnBlockedReason(LumenConfig config) {
        long remaining = cooldownRemainingMillis(config);
        if (remaining <= 0L) {
            return null;
        }
        long minutes = (remaining + 59_999L) / 60_000L;
        return config.companionName + " is still recovering - back in " + minutes + " minute" + (minutes == 1 ? "" : "s")
                + ". An admin can /lumen spawn force.";
    }

    /** Millis left on the respawn cooldown, 0 when none. */
    public long cooldownRemainingMillis(LumenConfig config) {
        if (config.respawnCooldownMinutes <= 0) {
            return 0L;
        }
        long died = Lumen.memory().lastDeath();
        if (died <= 0L) {
            return 0L;
        }
        long until = died + config.respawnCooldownMinutes * 60_000L;
        return Math.max(0L, until - System.currentTimeMillis());
    }

    @Nullable
    public LumenEntity spawn(ServerWorld world, Vec3d pos, float yaw, LumenConfig config) {
        despawn(world.getServer());
        // A successful spawn ends any cooldown, so a forced one does not linger.
        if (Lumen.memory().lastDeath() > 0L) {
            Lumen.memory().clearDeath();
        }

        LumenEntity lumen = LumenEntity.create(world, config);
        lumen.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, 0.0F);
        if (!world.spawnEntity(lumen)) {
            Lumen.LOGGER.error("World refused to spawn Lumen at {}", pos);
            return null;
        }
        this.lumenUuid = lumen.getUuid();
        this.lumenWorld = world.getRegistryKey();
        Lumen.LOGGER.info("Spawned {} at {} {} {} in {}", config.companionName,
                Math.round(pos.x), Math.round(pos.y), Math.round(pos.z),
                world.getRegistryKey().getValue());
        return lumen;
    }

    /** @return true if a Lumen was actually removed */
    public boolean despawn(@Nullable MinecraftServer server) {
        LumenEntity lumen = get(server);
        this.lumenUuid = null;
        this.lumenWorld = null;
        if (lumen == null) {
            return false;
        }
        // Despawning used to delete the pack. Anything a player handed over comes back.
        int dropped = lumen.dropEverything();
        if (dropped > 0) {
            Lumen.LOGGER.info("Dropped {} stack(s) that {} was carrying", dropped, Lumen.config().companionName);
        }
        lumen.discard();
        return true;
    }

    /** Called by the entity itself so a chunk unload or death clears our reference. */
    public void onEntityRemoved(LumenEntity entity) {
        if (lumenUuid != null && lumenUuid.equals(entity.getUuid())) {
            lumenUuid = null;
            lumenWorld = null;
        }
    }
}
