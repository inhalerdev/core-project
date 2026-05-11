package net.mineacle.core.chat.service;

import me.clip.placeholderapi.PlaceholderAPI;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ChatService {

    private final Core core;
    private final NicknameService nicknameService;
    private final File file;
    private final FileConfiguration config;

    private final Map<UUID, UUID> replyTargets = new HashMap<>();
    private final Map<UUID, Set<UUID>> ignored = new HashMap<>();

    public ChatService(Core core, NicknameService nicknameService) {
        this.core = core;
        this.nicknameService = nicknameService;
        this.file = new File(core.getDataFolder(), "chat.yml");

        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException exception) {
                core.getLogger().severe("Could not create chat.yml");
                exception.printStackTrace();
            }
        }

        this.config = YamlConfiguration.loadConfiguration(file);
        load();
    }

    public NicknameService nicknames() {
        return nicknameService;
    }

    public void sendPrivate(Player sender, Player receiver, String message) {
        if (sender.getUniqueId().equals(receiver.getUniqueId())) {
            sender.sendMessage(core.getMessage("chat.cannot-message-self"));
            return;
        }

        if (isIgnoring(receiver.getUniqueId(), sender.getUniqueId())) {
            sender.sendMessage(core.getMessage("chat.target-ignoring-you"));
            return;
        }

        String formatted = core.getMessage("chat.private-message-format")
                .replace("%sender%", nicknameService.displayName(sender))
                .replace("%receiver%", nicknameService.displayName(receiver))
                .replace("%message%", TextColor.color(message));

        sender.sendMessage(formatted);
        receiver.sendMessage(formatted);

        replyTargets.put(sender.getUniqueId(), receiver.getUniqueId());
        replyTargets.put(receiver.getUniqueId(), sender.getUniqueId());
    }

    public void reply(Player sender, String message) {
        UUID targetId = replyTargets.get(sender.getUniqueId());

        if (targetId == null) {
            sender.sendMessage(core.getMessage("chat.no-reply-target"));
            return;
        }

        Player target = Bukkit.getPlayer(targetId);

        if (target == null || !target.isOnline()) {
            sender.sendMessage(core.getMessage("chat.player-not-found"));
            return;
        }

        sendPrivate(sender, target, message);
    }

    public boolean toggleIgnore(Player player, OfflinePlayer target) {
        Set<UUID> ignoredPlayers = ignored.computeIfAbsent(player.getUniqueId(), ignored -> new HashSet<>());

        if (ignoredPlayers.contains(target.getUniqueId())) {
            ignoredPlayers.remove(target.getUniqueId());

            if (ignoredPlayers.isEmpty()) {
                ignored.remove(player.getUniqueId());
            }

            save();
            return false;
        }

        ignoredPlayers.add(target.getUniqueId());
        save();
        return true;
    }

    public boolean isIgnoring(UUID receiverId, UUID senderId) {
        return ignored.getOrDefault(receiverId, Set.of()).contains(senderId);
    }

    public List<String> ignoreList(Player player) {
        List<String> names = new ArrayList<>();

        for (UUID uuid : ignored.getOrDefault(player.getUniqueId(), Set.of())) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(uuid);
            names.add(nicknameService.displayName(target));
        }

        return names;
    }

    public List<Player> chatRecipients(Player sender) {
        String mode = core.getConfig().getString("chat.worlds.mode", "global").toLowerCase(Locale.ROOT);

        if (mode.equals("global")) {
            return new ArrayList<>(Bukkit.getOnlinePlayers());
        }

        if (mode.equals("same-world")) {
            List<Player> recipients = new ArrayList<>();
            World world = sender.getWorld();

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getWorld().equals(world)) {
                    recipients.add(player);
                }
            }

            return recipients;
        }

        if (mode.equals("grouped")) {
            return groupedRecipients(sender);
        }

        return new ArrayList<>(Bukkit.getOnlinePlayers());
    }

    public String formatChat(Player sender, String message) {
        String format = core.getConfig().getString(
                "chat.format",
                "%mineacle_chat_displayname%&#bbbbbb: &#bbbbbb%message%"
        );

        String output = format.replace("%message%", message);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            output = PlaceholderAPI.setPlaceholders(sender, output);
        } else {
            output = output.replace("%mineacle_chat_displayname%", nicknameService.rawChatDisplayName(sender));
        }

        return TextColor.color(output);
    }

    private List<Player> groupedRecipients(Player sender) {
        List<Player> recipients = new ArrayList<>();
        String senderGroup = worldGroup(sender.getWorld().getName());

        for (Player player : Bukkit.getOnlinePlayers()) {
            String playerGroup = worldGroup(player.getWorld().getName());

            if (senderGroup.equals(playerGroup)) {
                recipients.add(player);
            }
        }

        return recipients;
    }

    private String worldGroup(String worldName) {
        ConfigurationSection groups = core.getConfig().getConfigurationSection("chat.worlds.groups");

        if (groups == null) {
            return worldName;
        }

        for (String group : groups.getKeys(false)) {
            List<String> worlds = core.getConfig().getStringList("chat.worlds.groups." + group);

            for (String world : worlds) {
                if (world.equalsIgnoreCase(worldName)) {
                    return group;
                }
            }
        }

        return worldName;
    }

    private void load() {
        ignored.clear();

        ConfigurationSection section = config.getConfigurationSection("ignored");
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            try {
                UUID owner = UUID.fromString(key);
                Set<UUID> ignoredSet = new HashSet<>();

                for (String raw : config.getStringList("ignored." + key)) {
                    try {
                        ignoredSet.add(UUID.fromString(raw));
                    } catch (IllegalArgumentException ignoredException) {
                    }
                }

                if (!ignoredSet.isEmpty()) {
                    ignored.put(owner, ignoredSet);
                }
            } catch (IllegalArgumentException ignoredException) {
            }
        }
    }

    public void save() {
        config.set("ignored", null);

        for (Map.Entry<UUID, Set<UUID>> entry : ignored.entrySet()) {
            List<String> ids = entry.getValue().stream().map(UUID::toString).toList();
            config.set("ignored." + entry.getKey(), ids);
        }

        try {
            config.save(file);
        } catch (IOException exception) {
            core.getLogger().severe("Could not save chat.yml");
            exception.printStackTrace();
        }
    }
}