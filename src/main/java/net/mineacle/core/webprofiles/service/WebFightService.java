package net.mineacle.core.webprofiles.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.webprofiles.model.WebFightRecord;
import net.mineacle.core.webprofiles.storage.WebProfileRepository;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class WebFightService {

    private final Core core;
    private final FileConfiguration config;
    private final WebProfileRepository repository;
    private final WebProfileSyncService profileSyncService;

    private final Map<PairKey, CombatSession> sessions =
            new HashMap<>();

    private final boolean enabled;
    private final long timeoutMillis;
    private final Set<String> allowedWorlds;
    private final boolean logInserts;

    private BukkitTask cleanupTask;

    public WebFightService(
            Core core,
            FileConfiguration config,
            WebProfileRepository repository,
            WebProfileSyncService profileSyncService
    ) {
        this.core = core;
        this.config = config;
        this.repository = repository;
        this.profileSyncService = profileSyncService;

        this.enabled = config.getBoolean(
                "web-fights.enabled",
                true
        );
        this.timeoutMillis = Math.max(
                5_000L,
                config.getLong(
                        "web-fights.combat-timeout-seconds",
                        60L
                ) * 1_000L
        );
        this.allowedWorlds = config.getStringList(
                        "web-fights.allowed-worlds"
                )
                .stream()
                .filter(value -> value != null
                        && !value.isBlank())
                .map(value -> value.trim()
                        .toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.logInserts = config.getBoolean(
                "web-fights.log-inserts",
                false
        );
    }

    public void start() {
        if (!enabled) {
            core.getLogger().info(
                    "Web fight history is disabled"
            );
            return;
        }

        cleanupTask = core.getServer()
                .getScheduler()
                .runTaskTimer(
                        core,
                        this::cleanupExpired,
                        100L,
                        100L
                );

        core.getLogger().info(
                "Web fight history enabled for "
                        + (allowedWorlds.isEmpty()
                        ? "all worlds"
                        : String.join(
                                ", ",
                                allowedWorlds
                        ))
        );
    }

    public void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }

        sessions.clear();
    }

    public boolean enabled() {
        return enabled;
    }

    public void recordDamage(
            Player attacker,
            Player victim
    ) {
        if (!enabled
                || attacker == null
                || victim == null
                || attacker.getUniqueId().equals(
                victim.getUniqueId()
        )
                || !attacker.isOnline()
                || !victim.isOnline()
                || attacker.getWorld() != victim.getWorld()
                || !allowedWorld(
                victim.getWorld()
        )) {
            return;
        }

        long now = System.currentTimeMillis();
        PairKey key = PairKey.of(
                attacker.getUniqueId(),
                victim.getUniqueId()
        );
        CombatSession current = sessions.get(key);

        if (current == null
                || now - current.lastDamageAt()
                > timeoutMillis) {
            current = new CombatSession(
                    key,
                    now,
                    now,
                    attacker.getUniqueId()
            );
        } else {
            current = current.withDamage(
                    now,
                    attacker.getUniqueId()
            );
        }

        sessions.put(key, current);
    }

    public void recordDeath(Player loser) {
        if (!enabled
                || loser == null
                || !allowedWorld(loser.getWorld())) {
            removePlayer(loser == null
                    ? null
                    : loser.getUniqueId());
            return;
        }

        long endedAt = System.currentTimeMillis();
        Player winner = resolveWinner(
                loser,
                endedAt
        );

        if (winner == null
                || winner.getUniqueId().equals(
                loser.getUniqueId()
        )
                || winner.getWorld() != loser.getWorld()) {
            removePlayer(loser.getUniqueId());
            return;
        }

        PairKey key = PairKey.of(
                winner.getUniqueId(),
                loser.getUniqueId()
        );
        CombatSession session = sessions.get(key);

        if (session == null
                || endedAt - session.lastDamageAt()
                > timeoutMillis) {
            removePlayer(loser.getUniqueId());
            return;
        }

        long startedAt = Math.min(
                session.startedAt(),
                endedAt
        );
        long durationSeconds = Math.max(
                1L,
                (endedAt - startedAt + 999L)
                        / 1_000L
        );
        World world = loser.getWorld();

        WebFightRecord record = new WebFightRecord(
                UUID.randomUUID(),
                winner.getUniqueId(),
                winner.getName(),
                DisplayNames.displayName(winner),
                loser.getUniqueId(),
                loser.getName(),
                DisplayNames.displayName(loser),
                world.getName(),
                worldDisplayName(world),
                hearts(winner),
                0.0D,
                startedAt,
                endedAt,
                durationSeconds
        );

        removePlayer(loser.getUniqueId());

        core.getServer()
                .getScheduler()
                .runTaskAsynchronously(
                        core,
                        () -> {
                            boolean inserted =
                                    repository.insertFight(
                                            record
                                    );

                            if (inserted && logInserts) {
                                core.getLogger().info(
                                        "Posted web fight "
                                                + record.fightId()
                                                + ": "
                                                + record.winnerUsername()
                                                + " defeated "
                                                + record.loserUsername()
                                );
                            }
                        }
                );

        /*
         * Stats plugins commonly update kill/death counters during the same
         * death event. Sync both profiles on the next tick so the website
         * receives the new fight and the matching counters together.
         */
        core.getServer().getScheduler().runTask(
                core,
                () -> {
                    if (winner.isOnline()) {
                        profileSyncService.syncPlayer(
                                winner,
                                true
                        );
                    }

                    if (loser.isOnline()) {
                        profileSyncService.syncPlayer(
                                loser,
                                true
                        );
                    }
                }
        );
    }

    public void removePlayer(UUID playerId) {
        if (playerId == null
                || sessions.isEmpty()) {
            return;
        }

        sessions.entrySet().removeIf(
                entry -> entry.getKey()
                        .contains(playerId)
        );
    }

    private Player resolveWinner(
            Player loser,
            long now
    ) {
        Player direct = loser.getKiller();

        if (direct != null
                && activePair(
                direct.getUniqueId(),
                loser.getUniqueId(),
                now
        )) {
            return direct;
        }

        return sessions.values()
                .stream()
                .filter(session ->
                        session.key().contains(
                                loser.getUniqueId()
                        ))
                .filter(session ->
                        now - session.lastDamageAt()
                                <= timeoutMillis)
                .max(Comparator.comparingLong(
                        CombatSession::lastDamageAt
                ))
                .map(CombatSession::lastAttacker)
                .map(Bukkit::getPlayer)
                .filter(player -> player != null
                        && player.isOnline())
                .orElse(null);
    }

    private boolean activePair(
            UUID first,
            UUID second,
            long now
    ) {
        CombatSession session = sessions.get(
                PairKey.of(first, second)
        );

        return session != null
                && now - session.lastDamageAt()
                <= timeoutMillis;
    }

    private void cleanupExpired() {
        if (sessions.isEmpty()) {
            return;
        }

        long cutoff = System.currentTimeMillis()
                - timeoutMillis;
        Iterator<Map.Entry<PairKey, CombatSession>>
                iterator =
                sessions.entrySet().iterator();

        while (iterator.hasNext()) {
            if (iterator.next()
                    .getValue()
                    .lastDamageAt() < cutoff) {
                iterator.remove();
            }
        }
    }

    private boolean allowedWorld(World world) {
        if (world == null) {
            return false;
        }

        if (allowedWorlds.isEmpty()
                || allowedWorlds.contains("*")) {
            return true;
        }

        return allowedWorlds.contains(
                world.getName()
                        .toLowerCase(Locale.ROOT)
        );
    }

    private String worldDisplayName(World world) {
        String key = world.getName();
        String configured = config.getString(
                "worlds.mappings."
                        + key
                        + ".name"
        );

        if (configured != null
                && !configured.isBlank()) {
            return configured;
        }

        return switch (
                key.toLowerCase(Locale.ROOT)
        ) {
            case "overworld" -> "Overworld";
            case "overworld_nether" -> "Nether";
            case "overworld_the_end" -> "End";
            case "spawn1" -> "Spawn 1";
            case "spawn2" -> "Spawn 2";
            case "spawn3" -> "Spawn 3";
            default -> key;
        };
    }

    private double hearts(Player player) {
        double health = Math.max(
                0.0D,
                player.getHealth()
        );
        double absorption = Math.max(
                0.0D,
                player.getAbsorptionAmount()
        );

        return Math.round(
                ((health + absorption) / 2.0D)
                        * 100.0D
        ) / 100.0D;
    }

    private record PairKey(
            UUID first,
            UUID second
    ) {

        private static PairKey of(
                UUID left,
                UUID right
        ) {
            if (left.toString().compareTo(
                    right.toString()
            ) <= 0) {
                return new PairKey(left, right);
            }

            return new PairKey(right, left);
        }

        private boolean contains(UUID playerId) {
            return first.equals(playerId)
                    || second.equals(playerId);
        }
    }

    private record CombatSession(
            PairKey key,
            long startedAt,
            long lastDamageAt,
            UUID lastAttacker
    ) {

        private CombatSession withDamage(
                long damageAt,
                UUID attacker
        ) {
            return new CombatSession(
                    key,
                    startedAt,
                    damageAt,
                    attacker
            );
        }
    }
}
