package net.mineacle.core.bounty;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class BountyService {

    private final Core core;
    private final File file;
    private final YamlConfiguration config;

    public BountyService(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "bounties.yml");

        if (!core.getDataFolder().exists()) {
            core.getDataFolder().mkdirs();
        }

        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException exception) {
                core.getLogger().severe("Could not create bounties.yml");
                exception.printStackTrace();
            }
        }

        this.config = YamlConfiguration.loadConfiguration(file);
    }

    public List<BountyRecord> list(BountySortMode sortMode) {
        List<BountyRecord> records = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("bounties");

        if (section == null) {
            return records;
        }

        for (String key : section.getKeys(false)) {
            try {
                UUID targetId = UUID.fromString(key);
                long amount = config.getLong("bounties." + key + ".amount-cents", 0L);

                if (amount <= 0L) {
                    continue;
                }

                String targetName = config.getString("bounties." + key + ".target-name", key);
                long lastUpdated = config.getLong("bounties." + key + ".last-updated", 0L);

                records.add(new BountyRecord(targetId, targetName, amount, lastUpdated));
            } catch (IllegalArgumentException ignored) {
            }
        }

        records.sort(switch (sortMode) {
            case AMOUNT -> Comparator
                    .comparingLong(BountyRecord::amountCents)
                    .reversed()
                    .thenComparing(BountyRecord::targetName, String.CASE_INSENSITIVE_ORDER);
            case RECENT -> Comparator
                    .comparingLong(BountyRecord::lastUpdated)
                    .reversed()
                    .thenComparing(BountyRecord::targetName, String.CASE_INSENSITIVE_ORDER);
            case NAME -> Comparator.comparing(BountyRecord::targetName, String.CASE_INSENSITIVE_ORDER);
        });

        return records;
    }

    public BountyRecord get(UUID targetId) {
        if (targetId == null) {
            return null;
        }

        String path = "bounties." + targetId;

        if (!config.contains(path)) {
            return null;
        }

        long amount = config.getLong(path + ".amount-cents", 0L);

        if (amount <= 0L) {
            return null;
        }

        String targetName = config.getString(path + ".target-name", targetId.toString());
        long lastUpdated = config.getLong(path + ".last-updated", 0L);

        return new BountyRecord(targetId, targetName, amount, lastUpdated);
    }

    public long getAmount(UUID targetId) {
        BountyRecord record = get(targetId);
        return record == null ? 0L : record.amountCents();
    }

    public boolean place(Player setter, OfflinePlayer target, long amountCents) {
        if (setter == null || target == null || amountCents <= 0L) {
            return false;
        }

        if (setter.getUniqueId().equals(target.getUniqueId())) {
            return false;
        }

        EconomyService economy = EconomyModule.economyService();

        if (economy == null || !economy.take(setter.getUniqueId(), amountCents)) {
            return false;
        }

        add(target, amountCents);
        return true;
    }

    public void add(OfflinePlayer target, long amountCents) {
        if (target == null || amountCents <= 0L) {
            return;
        }

        UUID targetId = target.getUniqueId();
        String path = "bounties." + targetId;
        long current = config.getLong(path + ".amount-cents", 0L);

        config.set(path + ".target-name", DisplayNames.displayName(target));
        config.set(path + ".amount-cents", current + amountCents);
        config.set(path + ".last-updated", System.currentTimeMillis());
        save();
    }

    public long remove(UUID targetId) {
        if (targetId == null) {
            return 0L;
        }

        long amount = getAmount(targetId);
        config.set("bounties." + targetId, null);
        save();
        return amount;
    }

    public long claim(Player killer, Player target) {
        if (killer == null || target == null || killer.getUniqueId().equals(target.getUniqueId())) {
            return 0L;
        }

        long amount = remove(target.getUniqueId());

        if (amount <= 0L) {
            return 0L;
        }

        EconomyService economy = EconomyModule.economyService();

        if (economy == null) {
            add(target, amount);
            return 0L;
        }

        long payout = taxedPayout(amount);
        economy.give(killer.getUniqueId(), payout);
        return payout;
    }

    public long taxedPayout(long amountCents) {
        double tax = core.getConfig().getDouble("bounty.tax-percent", -1D);

        if (tax <= 0D) {
            return amountCents;
        }

        double kept = Math.max(0D, 1D - (tax / 100D));
        return Math.max(0L, Math.round(amountCents * kept));
    }

    public long minimumCents() {
        EconomyService economy = EconomyModule.economyService();
        String raw = core.getConfig().getString("bounty.minimum", "1");

        if (economy == null) {
            return 100L;
        }

        long parsed = economy.parseAmountToCents(raw);
        return parsed <= 0L ? 100L : parsed;
    }

    public long maximumCents() {
        EconomyService economy = EconomyModule.economyService();
        String raw = core.getConfig().getString("bounty.maximum", "100m");

        if (raw == null || raw.equalsIgnoreCase("-1") || raw.equalsIgnoreCase("none")) {
            return -1L;
        }

        if (economy == null) {
            return 10_000_000_000L;
        }

        return economy.parseAmountToCents(raw);
    }

    public String format(long cents) {
        EconomyService economy = EconomyModule.economyService();

        if (economy == null) {
            return "$" + (cents / 100D);
        }

        return economy.format(cents);
    }

    public long parseAmount(String raw) {
        EconomyService economy = EconomyModule.economyService();

        if (economy == null) {
            return -1L;
        }

        return economy.parseAmountToCents(raw);
    }

    public OfflinePlayer resolveTarget(String input) {
        Player online = DisplayNames.resolveOnline(input);

        if (online != null) {
            return online;
        }

        OfflinePlayer offline = DisplayNames.resolveOffline(input);

        if (offline == null || (offline.getName() == null && !offline.hasPlayedBefore())) {
            return null;
        }

        return offline;
    }

    public void save() {
        try {
            config.save(file);
        } catch (IOException exception) {
            core.getLogger().severe("Could not save bounties.yml");
            exception.printStackTrace();
        }
    }
}
