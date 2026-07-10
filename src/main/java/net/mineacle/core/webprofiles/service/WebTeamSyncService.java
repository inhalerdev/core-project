package net.mineacle.core.webprofiles.service;

import net.mineacle.core.Core;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.stats.StatsModule;
import net.mineacle.core.stats.service.StatsService;
import net.mineacle.core.webprofiles.model.WebTeamRecord;
import net.mineacle.core.webprofiles.storage.WebTeamRepository;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WebTeamSyncService {

    private final Core core;
    private final FileConfiguration config;
    private final WebTeamRepository repository;
    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private BukkitTask syncTask;

    public WebTeamSyncService(Core core, FileConfiguration config, WebTeamRepository repository) {
        this.core = core;
        this.config = config;
        this.repository = repository;
    }

    public void start() {
        if (!config.getBoolean("enabled", true) || !config.getBoolean("web-teams.enabled", true)) {
            core.getLogger().info("Web team sync is disabled");
            return;
        }

        repository.initialize();

        long seconds = config.getLong("web-teams.interval-seconds", config.getLong("sync.interval-seconds", 120L));
        long intervalTicks = Math.max(20L, seconds * 20L);

        syncTask = core.getServer().getScheduler().runTaskTimer(core, this::syncAll, 120L, intervalTicks);
        core.getLogger().info("Web team sync enabled");
    }

    public void stop() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
    }

    public void syncAll() {
        if (!syncing.compareAndSet(false, true)) {
            return;
        }

        try {
            StatsService stats = StatsModule.statsService();
            EconomyService economy = EconomyModule.economyService();

            if (stats == null || economy == null) {
                syncing.set(false);
                return;
            }

            stats.flushAllSessions();

            List<WebTeamRecord> records = collect(stats, economy);
            core.getServer().getScheduler().runTaskAsynchronously(core, () -> {
                try {
                    repository.replaceAll(records);
                } finally {
                    syncing.set(false);
                }
            });
        } catch (Exception exception) {
            syncing.set(false);
            core.getLogger().warning("Could not prepare web team sync: " + exception.getMessage());
        }
    }

    private List<WebTeamRecord> collect(StatsService stats, EconomyService economy) {
        FileConfiguration teamsConfig = core.getTeamsConfig();
        ConfigurationSection teamsSection = teamsConfig.getConfigurationSection("teams");
        List<WebTeamRecord> records = new ArrayList<>();

        if (teamsSection == null) {
            return records;
        }

        int minMembers = Math.max(1, config.getInt("web-teams.min-members", 1));
        int minKills = Math.max(0, config.getInt("web-teams.min-kills", 0));
        long now = System.currentTimeMillis();

        for (String teamId : teamsSection.getKeys(false)) {
            String path = "teams." + teamId;
            String teamName = teamsConfig.getString(path + ".name", teamId);
            String founderUuid = teamsConfig.getString(path + ".founder", "");
            ConfigurationSection membersSection = teamsConfig.getConfigurationSection(path + ".members");

            if (membersSection == null) {
                continue;
            }

            int memberCount = 0;
            int onlineMembers = 0;
            long totalBalance = 0L;
            long totalKills = 0L;
            long totalDeaths = 0L;

            for (String memberRaw : membersSection.getKeys(false)) {
                UUID memberId;

                try {
                    memberId = UUID.fromString(memberRaw);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }

                memberCount++;

                if (isOnline(memberId)) {
                    onlineMembers++;
                }

                totalBalance += economy.getBalanceCents(memberId);
                totalKills += stats.kills(memberId);
                totalDeaths += stats.deaths(memberId);
            }

            if (memberCount < minMembers || totalKills < minKills) {
                continue;
            }

            double kdRatio = kdRatio(totalKills, totalDeaths);

            records.add(new WebTeamRecord(
                    teamId,
                    teamName,
                    founderUuid,
                    memberCount,
                    onlineMembers,
                    totalBalance,
                    economy.format(totalBalance),
                    totalKills,
                    totalDeaths,
                    kdRatio,
                    0,
                    0,
                    now
            ));
        }

        return withRanks(records);
    }

    private List<WebTeamRecord> withRanks(List<WebTeamRecord> records) {
        List<WebTeamRecord> capitalSorted = new ArrayList<>(records);
        capitalSorted.sort(Comparator
                .comparingLong(WebTeamRecord::totalBalanceCents).reversed()
                .thenComparing(WebTeamRecord::teamName, String.CASE_INSENSITIVE_ORDER));

        List<WebTeamRecord> kdSorted = new ArrayList<>(records);
        kdSorted.sort(Comparator
                .comparingDouble(WebTeamRecord::kdRatio).reversed()
                .thenComparing(Comparator.comparingLong(WebTeamRecord::totalKills).reversed())
                .thenComparing(Comparator.comparingLong(WebTeamRecord::totalBalanceCents).reversed())
                .thenComparing(WebTeamRecord::teamName, String.CASE_INSENSITIVE_ORDER));

        List<WebTeamRecord> ranked = new ArrayList<>();

        for (WebTeamRecord record : records) {
            int capitalRank = rankOf(capitalSorted, record.teamId());
            int kdRank = rankOf(kdSorted, record.teamId());

            ranked.add(new WebTeamRecord(
                    record.teamId(),
                    record.teamName(),
                    record.founderUuid(),
                    record.memberCount(),
                    record.onlineMembers(),
                    record.totalBalanceCents(),
                    record.totalBalanceFormatted(),
                    record.totalKills(),
                    record.totalDeaths(),
                    record.kdRatio(),
                    capitalRank,
                    kdRank,
                    record.updatedAt()
            ));
        }

        return ranked;
    }

    private int rankOf(List<WebTeamRecord> sorted, String teamId) {
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).teamId().equals(teamId)) {
                return i + 1;
            }
        }

        return 0;
    }

    private boolean isOnline(UUID uuid) {
        Player player = core.getServer().getPlayer(uuid);
        return player != null && player.isOnline();
    }

    private double kdRatio(long kills, long deaths) {
        if (deaths <= 0L) {
            return kills;
        }

        return Math.round((kills / (double) deaths) * 100.0D) / 100.0D;
    }
}