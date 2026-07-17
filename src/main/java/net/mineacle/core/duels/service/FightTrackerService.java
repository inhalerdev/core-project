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

    private static final List<String> DEFAULT_ALLOWED_WORLDS = List.of(
            "origins",
            "origins_nether",
            "origins_the_end"
    );

    private final Core core;
    private final FileConfiguration config;
    private final FightRepository repository;
    private final Map<FightKey, ActiveFight> activeFights = new HashMap<>();
    private final Set<String> allowedWorlds;
    private final long timeoutMillis;

    public FightTrackerService(Core core, FileConfiguration config, FightRepository repository) {
        this.core = core;
        this.config = config;
        this.repository = repository;
        this.allowedWorlds = loadAllowedWorlds(config);
        this.timeoutMillis = Math.max(
                1L,
                config.getLong("web-fights.combat-timeout-seconds", 60L)
        ) * 1000L;
    }

    public void start() {
        if (!enabled()) {
            core.getLogger().info("Web fight tracking is disabled");
            return;
        }

        repository.initialize();
        core.getLogger().info(
                "Web fight tracking enabled for "
                        + String.join(", ", allowedWorlds)
                        + " with a "
                        + (timeoutMillis / 1000L)
                        + "s combat timeout"
        );
    }

    public void tick() {
        if (!enabled()) {
            activeFights.clear();
            return;
        }

        long now = System.currentTimeMillis();
        activeFights.entrySet().removeIf(entry -> entry.getValue().expired(now, timeoutMillis));
    }

    public void shutdown() {
        activeFights.clear();
    }

    public void recordDamage(Player attacker, Player victim) {
        if (!enabled()
                || attacker == null
                || victim == null
                || attacker.getUniqueId().equals(victim.getUniqueId())
                || !attacker.isOnline()
                || !victim.isOnline()
                || !attacker.getWorld().equals(victim.getWorld())
                || !allowedWorld(attacker.getWorld())) {
            return;
        }

        long now = System.currentTimeMillis();
        FightKey key = FightKey.of(attacker.getUniqueId(), victim.getUniqueId());
        ActiveFight fight = activeFights.get(key);
        String worldKey = attacker.getWorld().getName();

        if (fight == null
                || fight.expired(now, timeoutMillis)
                || !fight.worldKey().equalsIgnoreCase(worldKey)) {
            fight = new ActiveFight(key, worldKey, now);
            activeFights.put(key, fight);
        }

        fight.recordHit(attacker.getUniqueId(), victim.getUniqueId(), now);
    }

    public void completeFight(Player loser, Player killer) {
        if (loser == null) {
            return;
        }

        UUID loserId = loser.getUniqueId();

        if (!enabled()
                || killer == null
                || killer.getUniqueId().equals(loserId)
                || !killer.isOnline()
                || !killer.getWorld().equals(loser.getWorld())
                || !allowedWorld(loser.getWorld())) {
            clearPlayer(loserId);
            return;
        }

        long now = System.currentTimeMillis();
        FightKey key = FightKey.of(killer.getUniqueId(), loserId);
        ActiveFight fight = activeFights.get(key);

        boolean valid = fight != null
                && !fight.expired(now, timeoutMillis)
                && fight.worldKey().equalsIgnoreCase(loser.getWorld().getName())
                && fight.mutual()
                && killer.getUniqueId().equals(fight.lastAttacker())
                && loserId.equals(fight.lastVictim());

        clearPlayer(loserId);

        if (!valid) {
            return;
        }

        long endedAt = now;
        long startedAt = fight.startedAt();
        int durationSeconds = (int) Math.max(0L, (endedAt - startedAt) / 1000L);
        String worldKey = loser.getWorld().getName();
        String worldName = friendlyWorldName(worldKey);

        FightResultRecord record = new FightResultRecord(
                UUID.randomUUID(),
                killer.getUniqueId(),
                DisplayNames.username(killer),
                DisplayNames.displayName(killer),
                loserId,
                DisplayNames.username(loser),
                DisplayNames.displayName(loser),
                worldKey,
                worldName,
                combinedHearts(killer),
                0.0D,
                startedAt,
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

        activeFights.entrySet().removeIf(entry -> entry.getKey().contains(playerId));
    }

    private boolean enabled() {
        return repository.enabled();
    }

    private boolean allowedWorld(World world) {
        return world != null && allowedWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    private String friendlyWorldName(String worldKey) {
        String configured = config.getString("worlds.mappings." + worldKey + ".name");

        if (configured != null && !configured.isBlank()) {
            return configured;
        }

        return switch (worldKey.toLowerCase(Locale.ROOT)) {
            case "origins" -> "Overworld";
            case "origins_nether" -> "Nether";
            case "origins_the_end" -> "End";
            default -> worldKey;
        };
    }

    private double combinedHearts(Player player) {
        double health = Math.max(0.0D, player.getHealth());
        double absorption = Math.max(0.0D, player.getAbsorptionAmount());
        double hearts = (health + absorption) / 2.0D;
        return Math.round(hearts * 100.0D) / 100.0D;
    }

    private Set<String> loadAllowedWorlds(FileConfiguration configuration) {
        List<String> configured = configuration.getStringList("web-fights.allowed-worlds");
        List<String> source = configured.isEmpty() ? DEFAULT_ALLOWED_WORLDS : configured;
        Set<String> result = new HashSet<>();

        for (String world : source) {
            if (world != null && !world.isBlank()) {
                result.add(world.trim().toLowerCase(Locale.ROOT));
            }
        }

        return Set.copyOf(result);
    }

    private record FightKey(UUID first, UUID second) {

        private static FightKey of(UUID one, UUID two) {
            if (one.toString().compareTo(two.toString()) <= 0) {
                return new FightKey(one, two);
            }

            return new FightKey(two, one);
        }

        private boolean contains(UUID playerId) {
            return first.equals(playerId) || second.equals(playerId);
        }
    }

    private static final class ActiveFight {

        private final FightKey key;
        private final String worldKey;
        private final long startedAt;

        private long lastCombatAt;
        private boolean firstDamagedSecond;
        private boolean secondDamagedFirst;
        private UUID lastAttacker;
        private UUID lastVictim;

        private ActiveFight(FightKey key, String worldKey, long startedAt) {
            this.key = key;
            this.worldKey = worldKey;
            this.startedAt = startedAt;
            this.lastCombatAt = startedAt;
        }

        private void recordHit(UUID attacker, UUID victim, long at) {
            if (attacker.equals(key.first()) && victim.equals(key.second())) {
                firstDamagedSecond = true;
            } else if (attacker.equals(key.second()) && victim.equals(key.first())) {
                secondDamagedFirst = true;
            } else {
                return;
            }

            lastAttacker = attacker;
            lastVictim = victim;
            lastCombatAt = at;
        }

        private boolean mutual() {
            return firstDamagedSecond && secondDamagedFirst;
        }

        private boolean expired(long now, long timeout) {
            return now - lastCombatAt > timeout;
        }

        private String worldKey() {
            return worldKey;
        }

        private long startedAt() {
            return startedAt;
        }

        private UUID lastAttacker() {
            return lastAttacker;
        }

        private UUID lastVictim() {
            return lastVictim;
        }
    }
}
