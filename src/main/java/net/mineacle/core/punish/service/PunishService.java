package net.mineacle.core.punish.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.punish.gui.PunishGui;
import net.mineacle.core.punish.model.PunishAction;
import net.mineacle.core.punish.model.PunishCase;
import net.mineacle.core.punish.model.PunishDuration;
import net.mineacle.core.punish.model.PunishReason;
import net.mineacle.core.punish.model.PunishSession;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class PunishService {
    private static final String CASE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Core core;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, PunishSession> sessions = new HashMap<>();
    private final File configFile;
    private final File casesFile;
    private FileConfiguration config;
    private FileConfiguration casesConfig;

    public PunishService(Core core) {
        this.core = core;
        this.configFile = new File(core.getDataFolder(), "punish.yml");
        this.casesFile = new File(core.getDataFolder(), "punish-cases.yml");
        reload();
    }

    public Core core() {
        return core;
    }

    public void reload() {
        if (!core.getDataFolder().exists()) {
            core.getDataFolder().mkdirs();
        }
        if (!configFile.exists()) {
            core.saveResource("punish.yml", false);
        }
        if (!casesFile.exists()) {
            try {
                casesFile.createNewFile();
            } catch (IOException exception) {
                core.getLogger().severe("Could not create punish-cases.yml");
                exception.printStackTrace();
            }
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        casesConfig = YamlConfiguration.loadConfiguration(casesFile);
    }

    public void open(Player admin, OfflinePlayer target) {
        if (target == null || target.getUniqueId() == null) {
            admin.sendMessage(TextColor.color("&cPlayer not found"));
            return;
        }
        sessions.put(admin.getUniqueId(), new PunishSession(target.getUniqueId(), safeName(target)));
        PunishGui.open(admin, this);
    }

    public PunishSession session(Player admin) {
        return sessions.get(admin.getUniqueId());
    }

    public void clear(Player admin) {
        sessions.remove(admin.getUniqueId());
    }

    public String title() {
        return TextColor.strip(config.getString("gui.title", "Punish Player"));
    }

    public String confirmTitle() {
        return TextColor.strip(config.getString("gui.confirm-title", "Confirm Punishment"));
    }

    public String appealUrl() {
        return config.getString("appeal-url", "https://bans.mineacle.net");
    }

    public int confirmTimeoutSeconds() {
        return Math.max(2, config.getInt("confirm-timeout-seconds", 5));
    }

    public List<PunishReason> reasons() {
        List<PunishReason> reasons = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("reasons");
        if (section == null) {
            return reasons;
        }
        for (String key : section.getKeys(false)) {
            String path = "reasons." + key;
            reasons.add(new PunishReason(
                key,
                config.getInt(path + ".slot", 28),
                config.getString(path + ".material", "PAPER"),
                config.getString(path + ".name", "&#ff88ff" + key),
                config.getStringList(path + ".lore"),
                config.getString(path + ".reason", key)
            ));
        }
        return reasons;
    }

    public List<PunishDuration> durations() {
        List<PunishDuration> durations = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("durations");
        if (section == null) {
            return durations;
        }
        for (String key : section.getKeys(false)) {
            String path = "durations." + key;
            durations.add(new PunishDuration(
                key,
                config.getInt(path + ".slot", 37),
                config.getString(path + ".material", "CLOCK"),
                config.getString(path + ".name", "&#ff88ff" + key),
                config.getStringList(path + ".lore"),
                config.getString(path + ".duration", key)
            ));
        }
        return durations;
    }

    public PunishReason reasonAt(int slot) {
        for (PunishReason reason : reasons()) {
            if (reason.slot() == slot) {
                return reason;
            }
        }
        return null;
    }

    public PunishDuration durationAt(int slot) {
        for (PunishDuration duration : durations()) {
            if (duration.slot() == slot) {
                return duration;
            }
        }
        return null;
    }

    public String validationError(PunishSession session) {
        if (session == null) {
            return "&cNo punishment session found";
        }
        if (session.action() == null) {
            return "&cSelect a punishment action";
        }
        if (session.reason() == null) {
            return "&cSelect a punishment reason";
        }
        if (session.action().durationRequired() && session.duration() == null) {
            return "&cSelect a punishment duration";
        }
        return null;
    }

    public PunishCase execute(Player admin, PunishSession session) {
        String error = validationError(session);
        if (error != null) {
            admin.sendMessage(TextColor.color(error));
            return null;
        }

        String caseId = generateCaseId();
        String duration = session.action().durationRequired() && session.duration() != null ? session.duration().duration() : "permanent";
        PunishCase punishCase = new PunishCase(
            caseId,
            session.targetUuid(),
            session.targetName(),
            admin.getUniqueId(),
            admin.getName(),
            session.action(),
            session.reason().reason(),
            duration,
            System.currentTimeMillis()
        );

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command(punishCase));
        saveCase(punishCase);
        return punishCase;
    }

    public String message(String path, String fallback) {
        return TextColor.color(config.getString("messages." + path, fallback));
    }

    private String command(PunishCase punishCase) {
        String template = config.getString("commands." + punishCase.action().key(), defaultCommand(punishCase.action()));
        return template
            .replace("%player%", punishCase.targetName())
            .replace("%admin%", punishCase.adminName())
            .replace("%action%", punishCase.action().displayName())
            .replace("%duration%", punishCase.duration())
            .replace("%reason%", punishCase.reason())
            .replace("%case_id%", punishCase.caseId())
            .replace("%appeal_url%", appealUrl());
    }

    private String defaultCommand(PunishAction action) {
        return switch (action) {
            case WARN -> "warn %player% %reason% | Appeal ID: %case_id%";
            case KICK -> "kick %player% %reason% | Appeal ID: %case_id%";
            case MUTE -> "mute %player% %reason% | Appeal ID: %case_id%";
            case TEMP_MUTE -> "tempmute %player% %duration% %reason% | Appeal ID: %case_id%";
            case TEMP_BAN -> "tempban %player% %duration% %reason% | Appeal ID: %case_id%";
            case PERMANENT_BAN -> "ban %player% %reason% | Appeal ID: %case_id%";
        };
    }

    private void saveCase(PunishCase punishCase) {
        String path = "cases." + punishCase.caseId();
        casesConfig.set(path + ".target-uuid", punishCase.targetUuid().toString());
        casesConfig.set(path + ".target-name", punishCase.targetName());
        casesConfig.set(path + ".admin-uuid", punishCase.adminUuid().toString());
        casesConfig.set(path + ".admin-name", punishCase.adminName());
        casesConfig.set(path + ".action", punishCase.action().key());
        casesConfig.set(path + ".reason", punishCase.reason());
        casesConfig.set(path + ".duration", punishCase.duration());
        casesConfig.set(path + ".appeal-url", appealUrl());
        casesConfig.set(path + ".created-at", punishCase.createdAtMillis());
        try {
            casesConfig.save(casesFile);
        } catch (IOException exception) {
            core.getLogger().warning("Could not save punishment case " + punishCase.caseId());
        }
    }

    private String generateCaseId() {
        String prefix = config.getString("case-prefix", "MCL");
        int length = Math.max(4, config.getInt("case-length", 6));
        StringBuilder builder = new StringBuilder(prefix).append("-");
        for (int i = 0; i < length; i++) {
            builder.append(CASE_CHARS.charAt(random.nextInt(CASE_CHARS.length())));
        }
        return builder.toString().toUpperCase(Locale.ROOT);
    }

    private String safeName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }
}
