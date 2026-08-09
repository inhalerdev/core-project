package net.mineacle.core.duels.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.duels.model.FightResultRecord;
import net.mineacle.core.duels.storage.FightRepository;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class FightTrackerService {

    private static final List<String> DEFAULT_ALLOWED_WORLDS =
            List.of(
                    "overworld",
                    "overworld_nether",
                    "overworld_the_end"
            );

    private final Core core;
    private final FightRepository repository;
    private final Map<FightKey, ActiveFight> activeFights =
            new HashMap<>();

    private Set<String> allowedWorlds = Set.of();
    private long timeoutNanos;

    public FightTrackerService(
            Core core,
            FileConfiguration config,
            FightRepository repository
    ) {
        this.core = core;
        this.repository = repository;
        applySettings(config);
    }

    public void start() {
        if (!repository.enabled()) {
            core.getLogger().info(
                    "Web fight tracking is disabled"
            );
            return;
        }

        initializeRepositoryAsync();
        logSettings();
    }

    public void reload(FileConfiguration config) {
        activeFights.clear();
        repository.reload(config);
        applySettings(config);
        start();
    }

    public void tick() {
        if (!repository.enabled()) {
            activeFights.clear();
            return;
        }

        long now = System.nanoTime();

        activeFights.entrySet().removeIf(
                entry -> entry.getValue().expired(
                        now,
                        timeoutNanos
                )
        );
    }

    public void shutdown() {
        activeFights.clear();
    }

    public void recordDamage(
            Player attacker,
            Player victim
    ) {
        if (!repository.enabled()
                || attacker == null
                || victim == null
                || attacker.getUniqueId().equals(
                victim.getUniqueId()
        )
                || !attacker.isOnline()
                || !victim.isOnline()
                || !attacker.getWorld().equals(
                victim.getWorld()
        )
                || !allowedWorld(attacker.getWorld())) {
            return;
        }

        long nowNanos = System.nanoTime();
        long nowEpoch = System.currentTimeMillis();
        FightKey key = FightKey.of(
                attacker.getUniqueId(),
                victim.getUniqueId()
        );
        String worldKey = attacker.getWorld().getName();
        ActiveFight fight = activeFights.get(key);

        if (fight == null
                || fight.expired(nowNanos, timeoutNanos)
                || !fight.worldKey().equalsIgnoreCase(
                worldKey
        )) {
            fight = new ActiveFight(
                    key,
                    worldKey,
                    nowEpoch,
                    nowNanos
            );
            activeFights.put(key, fight);
        }

        fight.recordHit(
                attacker.getUniqueId(),
                victim.getUniqueId(),
                nowNanos
        );
    }

    public void completeFight(
            Player loser,
            Player killer
    ) {
        if (loser == null) {
            return;
        }

        UUID loserId = loser.getUniqueId();

        if (!repository.enabled()
                || killer == null
                || killer.getUniqueId().equals(loserId)
                || !killer.isOnline()
                || !killer.getWorld().equals(loser.getWorld())
                || !allowedWorld(loser.getWorld())) {
            clearPlayer(loserId);
            return;
        }

        long nowNanos = System.nanoTime();
        long endedAt = System.currentTimeMillis();
        FightKey key = FightKey.of(
                killer.getUniqueId(),
                loserId
        );
        ActiveFight fight = activeFights.get(key);

        boolean valid = fight != null
                && !fight.expired(nowNanos, timeoutNanos)
                && fight.worldKey().equalsIgnoreCase(
                loser.getWorld().getName()
        )
                && fight.mutual()
                && killer.getUniqueId().equals(
                fight.lastAttacker()
        )
                && loserId.equals(fight.lastVictim());

        clearPlayer(loserId);

        if (!valid) {
            return;
        }

        int durationSeconds = (int) Math.min(
                Integer.MAX_VALUE,
                Math.max(
                        0L,
                        (endedAt - fight.startedAtEpoch()) / 1000L
                )
        );
        String worldKey = loser.getWorld().getName();

        FightResultRecord record = new FightResultRecord(
                UUID.randomUUID(),
                killer.getUniqueId(),
                DisplayNames.username(killer),
                DisplayNames.displayName(killer),
                loserId,
                DisplayNames.username(loser),
                DisplayNames.displayName(loser),
                worldKey,
                friendlyWorldName(worldKey),
                combinedHearts(killer),
                0.0D,
                fight.startedAtEpoch(),
                endedAt,
                durationSeconds
        );

        core.getServer().getScheduler().runTaskAsynchronously(
                core,
                () -> repository.insert(record)
        );
    }

    public void clearPlayer(Player player) {
        if (player != null) {
            clearPlayer(player.getUniqueId());
        }
    }

    public void clearPlayer(UUID playerId) {
        if (playerId == null || activeFights.isEmpty()) {
            return;
        }

        activeFights.entrySet().removeIf(
                entry -> entry.getKey().contains(playerId)
        );
    }

    private void applySettings(FileConfiguration config) {
        allowedWorlds = loadAllowedWorlds(config);
        long timeoutSeconds = Math.max(
                1L,
                config.getLong(
                        "web-fights.combat-timeout-seconds",
                        60L
                )
        );
        timeoutNanos = timeoutSeconds * 1_000_000_000L;
    }

    private void initializeRepositoryAsync() {
        core.getServer().getScheduler().runTaskAsynchronously(
                core,
                repository::initialize
        );
    }

    private void logSettings() {
        core.getLogger().info(
                "Web fight tracking enabled for "
                        + String.join(", ", allowedWorlds)
                        + " with a "
                        + (timeoutNanos / 1_000_000_000L)
                        + "s combat timeout"
        );
    }

    private boolean allowedWorld(World world) {
        return world != null
                && allowedWorlds.contains(
                world.getName().toLowerCase(Locale.ROOT)
        );
    }

    private String friendlyWorldName(String worldKey) {
        return switch (worldKey.toLowerCase(Locale.ROOT)) {
            case "overworld" -> "Overworld";
            case "overworld_nether" -> "Nether";
            case "overworld_the_end" -> "End";
            default -> worldKey;
        };
    }

    private double combinedHearts(Player player) {
        double health = Math.max(0.0D, player.getHealth());
        double absorption = Math.max(
                0.0D,
                player.getAbsorptionAmount()
        );
        double hearts = (health + absorption) / 2.0D;

        return Math.round(hearts * 100.0D) / 100.0D;
    }

    private Set<String> loadAllowedWorlds(
            FileConfiguration configuration
    ) {
        List<String> configured =
                configuration.getStringList(
                        "web-fights.allowed-worlds"
                );
        List<String> source = configured.isEmpty()
                ? DEFAULT_ALLOWED_WORLDS
                : configured;
        Set<String> result = new HashSet<>();

        for (String world : source) {
            if (world != null && !world.isBlank()) {
                result.add(
                        world.trim().toLowerCase(Locale.ROOT)
                );
            }
        }

        return Set.copyOf(result);
    }

    private record FightKey(UUID first, UUID second) {

        private static FightKey of(UUID one, UUID two) {
            if (one.compareTo(two) <= 0) {
                return new FightKey(one, two);
            }

            return new FightKey(two, one);
        }

        private boolean contains(UUID playerId) {
            return first.equals(playerId)
                    || second.equals(playerId);
        }
    }

    private static final class ActiveFight {

        private final FightKey key;
        private final String worldKey;
        private final long startedAtEpoch;

        private long lastCombatNanos;
        private boolean firstDamagedSecond;
        private boolean secondDamagedFirst;
        private UUID lastAttacker;
        private UUID lastVictim;

        private ActiveFight(
                FightKey key,
                String worldKey,
                long startedAtEpoch,
                long startedAtNanos
        ) {
            this.key = key;
            this.worldKey = worldKey;
            this.startedAtEpoch = startedAtEpoch;
            this.lastCombatNanos = startedAtNanos;
        }

        private void recordHit(
                UUID attacker,
                UUID victim,
                long atNanos
        ) {
            if (attacker.equals(key.first())
                    && victim.equals(key.second())) {
                firstDamagedSecond = true;
            } else if (attacker.equals(key.second())
                    && victim.equals(key.first())) {
                secondDamagedFirst = true;
            } else {
                return;
            }

            lastAttacker = attacker;
            lastVictim = victim;
            lastCombatNanos = atNanos;
        }

        private boolean mutual() {
            return firstDamagedSecond && secondDamagedFirst;
        }

        private boolean expired(
                long nowNanos,
                long timeout
        ) {
            return nowNanos - lastCombatNanos > timeout;
        }

        private String worldKey() {
            return worldKey;
        }

        private long startedAtEpoch() {
            return startedAtEpoch;
        }

        private UUID lastAttacker() {
            return lastAttacker;
        }

        private UUID lastVictim() {
            return lastVictim;
        }
    }
}
