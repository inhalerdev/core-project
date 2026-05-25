package net.mineacle.core.punish.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.punish.gui.BanGui;
import net.mineacle.core.punish.model.PunishCase;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public final class PunishService {

    private static final String CASE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Core core;
    private final SecureRandom random = new SecureRandom();
    private final File file;
    private FileConfiguration config;

    public PunishService(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "punish.yml");
        reload();
    }

    public Core core() {
        return core;
    }

    public void reload() {
        if (!core.getDataFolder().exists()) {
            core.getDataFolder().mkdirs();
        }

        if (!file.exists()) {
            core.saveResource("punish.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(file);
    }

    public String title() {
        return TextColor.color(config.getString("gui.title", "&8Ban Player"));
    }

    public String appealUrl() {
        return config.getString("appeal-url", "https://mineacle.net/appeal");
    }

    public List<ReasonOption> reasons() {
        List<ReasonOption> reasons = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("reasons");

        if (section == null) {
            return reasons;
        }

        for (String key : section.getKeys(false)) {
            String path = "reasons." + key;
            reasons.add(new ReasonOption(
                    key,
                    config.getInt(path + ".slot", 10),
                    config.getString(path + ".material", "PAPER"),
                    config.getString(path + ".name", "&c" + key),
                    config.getStringList(path + ".lore"),
                    config.getString(path + ".reason", key)
            ));
        }

        return reasons;
    }

    public List<DurationOption> durations() {
        List<DurationOption> durations = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("durations");

        if (section == null) {
            return durations;
        }

        for (String key : section.getKeys(false)) {
            String path = "durations." + key;
            durations.add(new DurationOption(
                    key,
                    config.getInt(path + ".slot", 21),
                    config.getString(path + ".material", "CLOCK"),
                    config.getString(path + ".name", "&d" + key),
                    config.getStringList(path + ".lore"),
                    config.getString(path + ".duration", "perm"),
                    config.getBoolean(path + ".permanent", false)
            ));
        }

        return durations;
    }

    public ReasonOption reasonAt(int slot) {
        return reasons().stream().filter(reason -> reason.slot() == slot).findFirst().orElse(null);
    }

    public DurationOption durationAt(int slot) {
        return durations().stream().filter(duration -> duration.slot() == slot).findFirst().orElse(null);
    }

    public void open(Player admin, OfflinePlayer target) {
        BanGui.open(admin, target, this);
    }

    public void punish(Player admin, OfflinePlayer target, ReasonOption reason, DurationOption duration) {
        if (reason == null || duration == null) {
            admin.sendMessage(TextColor.color("&cSelect a reason and duration first"));
            return;
        }

        String caseId = generateCaseId();
        String targetName = target.getName() == null ? target.getUniqueId().toString() : target.getName();

        PunishCase punishCase = new PunishCase(
                caseId,
                target.getUniqueId(),
                targetName,
                admin.getUniqueId(),
                admin.getName(),
                reason.reason(),
                duration.duration(),
                System.currentTimeMillis()
        );

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command(punishCase, duration.permanent()));

        admin.closeInventory();
        admin.sendMessage(TextColor.color(config.getString("messages.banned", "&#bbbbbbBanned &#ff88ff%player% &#bbbbbbwith case &#ff88ff%case_id%")
                .replace("%player%", targetName)
                .replace("%case_id%", caseId)));
    }

    private String command(PunishCase punishCase, boolean permanent) {
        String template = permanent
                ? config.getString("commands.permaban", "ban %player% %reason% Appeal ID: %case_id%")
                : config.getString("commands.tempban", "tempban %player% %duration% %reason% Appeal ID: %case_id%");

        return template
                .replace("%player%", punishCase.targetName())
                .replace("%duration%", punishCase.duration())
                .replace("%reason%", punishCase.reason())
                .replace("%case_id%", punishCase.caseId())
                .replace("%appeal_url%", appealUrl());
    }

    private String generateCaseId() {
        String prefix = config.getString("case-prefix", "MCL");
        int length = Math.max(4, config.getInt("case-length", 6));
        StringBuilder builder = new StringBuilder(prefix).append("-");

        for (int i = 0; i < length; i++) {
            builder.append(CASE_CHARS.charAt(random.nextInt(CASE_CHARS.length())));
        }

        return builder.toString();
    }

    public record ReasonOption(String key, int slot, String material, String name, List<String> lore, String reason) {
    }

    public record DurationOption(String key, int slot, String material, String name, List<String> lore, String duration, boolean permanent) {
    }
}
