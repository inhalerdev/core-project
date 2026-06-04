package net.mineacle.core.stats.service;

import net.mineacle.core.Core;
import net.mineacle.core.economy.service.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class StatsService {

    private final Core core;
    private final StatsStorageService storage;
    private final EconomyService economyService;
    private final Map<UUID, Long> playtimeSessionStarted = new HashMap<>();

    public StatsService(Core core, StatsStorageService storage, EconomyService economyService) {
        this.core = core;
        this.storage = storage;
        this.economyService = economyService;
    }

    public void startSession(Player player) {
        storage.ensureProfile(player);
        seedExistingPlaytime(player);

        if (!isPlaytimeWorld(player.getWorld())) {
            playtimeSessionStarted.remove(player.getUniqueId());
            return;
        }

        playtimeSessionStarted.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public void stopSession(Player player) {
        flushSession(player);
        playtimeSessionStarted.remove(player.getUniqueId());
    }

    public void switchWorld(Player player) {
        storage.ensureProfile(player);
        seedExistingPlaytime(player);
        flushSession(player);

        if (isPlaytimeWorld(player.getWorld())) {
            playtimeSessionStarted.put(player.getUniqueId(), System.currentTimeMillis());
        } else {
            playtimeSessionStarted.remove(player.getUniqueId());
        }
    }

    public void flushAllSessions() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            flushSession(player);
        }
    }

    public void flushSession(Player player) {
        Long startedAt = playtimeSessionStarted.get(player.getUniqueId());
        if (startedAt == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long seconds = Math.max(0L, (now - startedAt) / 1000L);

        if (seconds <= 0L) {
            return;
        }

        storage.addPlaytimeSeconds(player.getUniqueId(), seconds);
        playtimeSessionStarted.put(player.getUniqueId(), now);
    }

    public void recordDeath(Player victim) {
        if (!isCombatStatsWorld(victim.getWorld())) {
            return;
        }

        storage.ensureProfile(victim);
        storage.addDeath(victim.getUniqueId());

        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }

        if (!isCombatStatsWorld(killer.getWorld())) {
            return;
        }

        storage.ensureProfile(killer);
        storage.addKill(killer.getUniqueId());
    }

    public String money(UUID uuid) {
        return economyService == null ? "$0" : economyService.format(economyService.getBalanceCents(uuid));
    }

    public long kills(UUID uuid) {
        return storage.kills(uuid);
    }

    public long deaths(UUID uuid) {
        return storage.deaths(uuid);
    }

    public long playtimeSeconds(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            storage.ensureProfile(online);
            seedExistingPlaytime(online);
            flushSession(online);
        }

        return storage.playtimeSeconds(uuid);
    }

    public String playtime(UUID uuid) {
        return formatPlaytime(playtimeSeconds(uuid));
    }

    public List<StatsStorageService.StatProfile> topKills(int limit) {
        return storage.profiles()
                .values()
                .stream()
                .filter(profile -> profile.kills() > 0L)
                .sorted(Comparator
                        .comparingLong(StatsStorageService.StatProfile::kills)
                        .reversed()
                        .thenComparing(profile -> profile.name() == null ? "" : profile.name(), String.CASE_INSENSITIVE_ORDER))
                .limit(limit)
                .toList();
    }

    public List<StatsStorageService.StatProfile> topDeaths(int limit) {
        return storage.profiles()
                .values()
                .stream()
                .filter(profile -> profile.deaths() > 0L)
                .sorted(Comparator
                        .comparingLong(StatsStorageService.StatProfile::deaths)
                        .reversed()
                        .thenComparing(profile -> profile.name() == null ? "" : profile.name(), String.CASE_INSENSITIVE_ORDER))
                .limit(limit)
                .toList();
    }

    public List<StatsStorageService.StatProfile> topPlaytime(int limit) {
        flushAllSessions();

        for (Player player : Bukkit.getOnlinePlayers()) {
            storage.ensureProfile(player);
            seedExistingPlaytime(player);
        }

        return storage.profiles()
                .values()
                .stream()
                .filter(profile -> profile.playtimeSeconds() > 0L)
                .sorted(Comparator
                        .comparingLong(StatsStorageService.StatProfile::playtimeSeconds)
                        .reversed()
                        .thenComparing(profile -> profile.name() == null ? "" : profile.name(), String.CASE_INSENSITIVE_ORDER))
                .limit(limit)
                .toList();
    }

    public int rankKills(UUID uuid) {
        return rank(uuid, topKills(Integer.MAX_VALUE));
    }

    public int rankDeaths(UUID uuid) {
        return rank(uuid, topDeaths(Integer.MAX_VALUE));
    }

    public int rankPlaytime(UUID uuid) {
        return rank(uuid, topPlaytime(Integer.MAX_VALUE));
    }

    private int rank(UUID uuid, List<StatsStorageService.StatProfile> profiles) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).uuid().equals(uuid)) {
                return i + 1;
            }
        }

        return 0;
    }

    public int mobsKilled(UUID uuid) {
        return statistic(uuid, Statistic.MOB_KILLS);
    }

    public int blocksPlaced(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return 0;
        }

        int total = 0;
        for (Material material : Material.values()) {
            if (!material.isBlock() || !material.isItem()) {
                continue;
            }

            try {
                total += player.getStatistic(Statistic.USE_ITEM, material);
            } catch (Exception ignored) {
            }
        }

        return total;
    }

    public int blocksBroken(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return 0;
        }

        int total = 0;
        for (Material material : Material.values()) {
            if (!material.isBlock()) {
                continue;
            }

            try {
                total += player.getStatistic(Statistic.MINE_BLOCK, material);
            } catch (Exception ignored) {
            }
        }

        return total;
    }

    public String formatPlaytime(long totalSeconds) {
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;

        if (days > 0L) {
            return days + "d " + hours + "h";
        }

        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }

        return minutes + "m";
    }

    public void save() {
        flushAllSessions();
        storage.save();
    }

    public boolean isPlaytimeWorld(World world) {
        if (world == null) {
            return false;
        }

        return enabledWorlds("stats.playtime.enabled-worlds")
                .stream()
                .anyMatch(value -> value.equals(world.getName().toLowerCase(Locale.ROOT)));
    }

    public boolean isCombatStatsWorld(World world) {
        if (world == null) {
            return false;
        }

        return enabledWorlds("stats.combat.enabled-worlds")
                .stream()
                .anyMatch(value -> value.equals(world.getName().toLowerCase(Locale.ROOT)));
    }

    public int statistic(UUID uuid, Statistic statistic) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return 0;
        }

        try {
            return player.getStatistic(statistic);
        } catch (Exception ignored) {
            return 0;
        }
    }

    public OfflinePlayer offline(UUID uuid) {
        return Bukkit.getOfflinePlayer(uuid);
    }

    private void seedExistingPlaytime(Player player) {
        if (!core.getConfig().getBoolean("stats.playtime.import-vanilla-on-first-seen", true)) {
            return;
        }

        long currentNative = storage.playtimeSeconds(player.getUniqueId());
        if (currentNative > 0L) {
            return;
        }

        long vanillaSeconds;
        try {
            vanillaSeconds = Math.max(0L, player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L);
        } catch (Exception ignored) {
            vanillaSeconds = 0L;
        }

        if (vanillaSeconds > 0L) {
            storage.setPlaytimeSeconds(player.getUniqueId(), vanillaSeconds);
        }
    }

    private List<String> enabledWorlds(String path) {
        List<String> configured = core.getConfig().getStringList(path);

        if (configured == null || configured.isEmpty()) {
            return List.of("origins", "origins_nether", "origins_the_end");
        }

        return configured.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .toList();
    }
}
