package net.mineacle.core.chat.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class ChatService {

    public enum MessageResult {
        SUCCESS,
        NO_REPLY_TARGET,
        TARGET_OFFLINE,
        CANNOT_MESSAGE_SELF,
        TARGET_IGNORING,
        EMPTY_MESSAGE
    }

    public enum IgnoreResult {
        NOW_IGNORING,
        NO_LONGER_IGNORING,
        STORAGE_ERROR
    }

    private static final int MAX_MESSAGE_LENGTH = 256;

    private final Core core;
    private final NicknameService nicknameService;
    private final File file;

    private final Map<UUID, UUID> replyTargets = new HashMap<>();
    private final Map<UUID, Set<UUID>> ignored = new HashMap<>();

    public ChatService(
            Core core,
            NicknameService nicknameService
    ) throws IOException {
        this.core = core;
        this.nicknameService = nicknameService;
        this.file = new File(core.getDataFolder(), "chat.yml");

        ensureStorage();
        load();
    }

    public NicknameService nicknames() {
        return nicknameService;
    }

    public boolean enabled() {
        return core.getConfig().getBoolean(
                "chat.enabled",
                true
        );
    }

    public MessageResult sendPrivate(
            Player sender,
            Player receiver,
            String rawMessage
    ) {
        if (sender == null || receiver == null) {
            return MessageResult.TARGET_OFFLINE;
        }

        if (sender.getUniqueId().equals(receiver.getUniqueId())) {
            return MessageResult.CANNOT_MESSAGE_SELF;
        }

        if (isIgnoring(
                receiver.getUniqueId(),
                sender.getUniqueId()
        )) {
            return MessageResult.TARGET_IGNORING;
        }

        String message = sanitizeMessage(rawMessage);

        if (message.isBlank()) {
            return MessageResult.EMPTY_MESSAGE;
        }

        Component senderCopy = privateMessageComponent(
                sender,
                receiver,
                message,
                receiver
        );
        Component receiverCopy = privateMessageComponent(
                sender,
                receiver,
                message,
                sender
        );

        sender.sendMessage(senderCopy);
        receiver.sendMessage(receiverCopy);

        replyTargets.put(
                sender.getUniqueId(),
                receiver.getUniqueId()
        );
        replyTargets.put(
                receiver.getUniqueId(),
                sender.getUniqueId()
        );

        SoundService.chatMessage(receiver, core);
        return MessageResult.SUCCESS;
    }

    public MessageResult reply(
            Player sender,
            String message
    ) {
        if (sender == null) {
            return MessageResult.NO_REPLY_TARGET;
        }

        UUID targetId = replyTargets.get(sender.getUniqueId());

        if (targetId == null) {
            return MessageResult.NO_REPLY_TARGET;
        }

        Player target = Bukkit.getPlayer(targetId);

        if (target == null || !target.isOnline()) {
            replyTargets.remove(sender.getUniqueId());
            return MessageResult.TARGET_OFFLINE;
        }

        return sendPrivate(sender, target, message);
    }

    public synchronized IgnoreResult toggleIgnoreDetailed(
            Player player,
            OfflinePlayer target
    ) {
        UUID playerId = player.getUniqueId();
        UUID targetId = target.getUniqueId();
        Set<UUID> ignoredPlayers = ignored.computeIfAbsent(
                playerId,
                ignoredId -> new HashSet<>()
        );

        if (ignoredPlayers.remove(targetId)) {
            if (ignoredPlayers.isEmpty()) {
                ignored.remove(playerId);
            }

            if (!saveNow()) {
                ignored.computeIfAbsent(
                        playerId,
                        ignoredId -> new HashSet<>()
                ).add(targetId);
                return IgnoreResult.STORAGE_ERROR;
            }

            return IgnoreResult.NO_LONGER_IGNORING;
        }

        ignoredPlayers.add(targetId);

        if (!saveNow()) {
            ignoredPlayers.remove(targetId);

            if (ignoredPlayers.isEmpty()) {
                ignored.remove(playerId);
            }

            return IgnoreResult.STORAGE_ERROR;
        }

        clearReplyPair(playerId, targetId);
        return IgnoreResult.NOW_IGNORING;
    }

    /**
     * Compatibility method retained for integrations that only need the
     * resulting ignored state.
     */
    public boolean toggleIgnore(
            Player player,
            OfflinePlayer target
    ) {
        return toggleIgnoreDetailed(player, target)
                == IgnoreResult.NOW_IGNORING;
    }

    public boolean isIgnoring(
            UUID receiverId,
            UUID senderId
    ) {
        if (receiverId == null || senderId == null) {
            return false;
        }

        return ignored
                .getOrDefault(receiverId, Set.of())
                .contains(senderId);
    }

    public List<String> ignoreList(Player player) {
        List<String> names = new ArrayList<>();

        for (UUID targetId : ignored.getOrDefault(
                player.getUniqueId(),
                Set.of()
        )) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
            names.add(DisplayNames.displayName(target));
        }

        names.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(names);
    }

    public List<Player> chatRecipients(Player sender) {
        List<Player> candidates = switch (worldMode()) {
            case "same-world" -> sameWorldRecipients(sender);
            case "grouped" -> groupedRecipients(sender);
            default -> new ArrayList<>(Bukkit.getOnlinePlayers());
        };

        candidates.removeIf(
                recipient -> !recipient.getUniqueId()
                        .equals(sender.getUniqueId())
                        && isIgnoring(
                        recipient.getUniqueId(),
                        sender.getUniqueId()
                )
        );

        return candidates;
    }

    public String sanitizeMessage(String rawMessage) {
        if (rawMessage == null) {
            return "";
        }

        String cleaned = rawMessage
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace("§", "")
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();

        if (cleaned.length() > MAX_MESSAGE_LENGTH) {
            cleaned = cleaned.substring(0, MAX_MESSAGE_LENGTH);
        }

        return cleaned;
    }

    /**
     * Compatibility formatter used for console and older integrations.
     * User text is appended after color translation so literal ampersands do
     * not become formatting codes.
     */
    public String formatChat(
            Player sender,
            String rawMessage
    ) {
        String prefix = TextColor.color(
                DisplayNames.luckPermsPrefix(sender)
                        + "&#bbbbbb"
                        + DisplayNames.displayName(sender)
                        + "&#bbbbbb: &#bbbbbb"
        );
        return prefix + sanitizeMessage(rawMessage);
    }

    public void cleanupPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }

        replyTargets.remove(playerId);
        replyTargets.entrySet().removeIf(
                entry -> entry.getValue().equals(playerId)
        );
    }

    public void shutdown() {
        save();
        replyTargets.clear();
    }

    public synchronized void save() {
        saveNow();
    }

    private boolean saveNow() {
        try {
            persist();
            return true;
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not save chat.yml",
                    exception
            );
            return false;
        }
    }

    private Component privateMessageComponent(
            Player sender,
            Player receiver,
            String message,
            Player replyTarget
    ) {
        String senderName = DisplayNames.displayName(sender);
        String receiverName = DisplayNames.displayName(receiver);
        String replyName = DisplayNames.displayName(replyTarget);

        Component body = neutral(senderName)
                .append(neutral(" -> "))
                .append(neutral(receiverName))
                .append(neutral(": "))
                .append(neutral(message));

        Component hover = primary("Private Message")
                .append(Component.newline())
                .append(neutral("From: " + senderName))
                .append(Component.newline())
                .append(neutral("To: " + receiverName))
                .append(Component.newline())
                .append(neutral("Click to reply to " + replyName));

        return body
                .hoverEvent(HoverEvent.showText(hover))
                .clickEvent(
                        ClickEvent.suggestCommand(
                                "/msg "
                                        + DisplayNames.commandDisplayName(
                                        replyTarget
                                )
                                        + " "
                        )
                );
    }

    private List<Player> sameWorldRecipients(Player sender) {
        List<Player> recipients = new ArrayList<>();
        World world = sender.getWorld();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(world)) {
                recipients.add(player);
            }
        }

        return recipients;
    }

    private List<Player> groupedRecipients(Player sender) {
        List<Player> recipients = new ArrayList<>();
        String senderGroup = worldGroup(
                sender.getWorld().getName()
        );

        for (Player player : Bukkit.getOnlinePlayers()) {
            String playerGroup = worldGroup(
                    player.getWorld().getName()
            );

            if (senderGroup.equals(playerGroup)) {
                recipients.add(player);
            }
        }

        return recipients;
    }

    private String worldMode() {
        String configured = core.getConfig().getString(
                "chat.worlds.mode",
                "global"
        );

        if (configured == null || configured.isBlank()) {
            return "global";
        }

        return configured.trim().toLowerCase(Locale.ROOT);
    }

    private String worldGroup(String worldName) {
        ConfigurationSection groups = core.getConfig()
                .getConfigurationSection("chat.worlds.groups");

        if (groups == null) {
            return worldName.toLowerCase(Locale.ROOT);
        }

        for (String group : groups.getKeys(false)) {
            List<String> worlds = core.getConfig().getStringList(
                    "chat.worlds.groups." + group
            );

            for (String configuredWorld : worlds) {
                if (configuredWorld != null
                        && configuredWorld.equalsIgnoreCase(worldName)) {
                    return group.toLowerCase(Locale.ROOT);
                }
            }
        }

        return worldName.toLowerCase(Locale.ROOT);
    }

    private synchronized void load() {
        ignored.clear();

        YamlConfiguration configuration =
                YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section =
                configuration.getConfigurationSection("ignored");

        if (section == null) {
            return;
        }

        for (String rawOwnerId : section.getKeys(false)) {
            try {
                UUID ownerId = UUID.fromString(rawOwnerId);
                Set<UUID> ignoredPlayers = new HashSet<>();

                for (String rawTargetId
                        : configuration.getStringList(
                        "ignored." + rawOwnerId
                )) {
                    try {
                        UUID targetId = UUID.fromString(rawTargetId);

                        if (!ownerId.equals(targetId)) {
                            ignoredPlayers.add(targetId);
                        }
                    } catch (IllegalArgumentException ignoredException) {
                    }
                }

                if (!ignoredPlayers.isEmpty()) {
                    ignored.put(ownerId, ignoredPlayers);
                }
            } catch (IllegalArgumentException ignoredException) {
            }
        }
    }

    private synchronized void persist() throws IOException {
        ensureStorage();

        YamlConfiguration configuration = new YamlConfiguration();

        List<UUID> owners = new ArrayList<>(ignored.keySet());
        owners.sort(Comparator.comparing(UUID::toString));

        for (UUID ownerId : owners) {
            List<String> targetIds = ignored
                    .getOrDefault(ownerId, Set.of())
                    .stream()
                    .map(UUID::toString)
                    .sorted()
                    .toList();

            if (!targetIds.isEmpty()) {
                configuration.set(
                        "ignored." + ownerId,
                        targetIds
                );
            }
        }

        atomicSave(configuration);
    }

    private void ensureStorage() throws IOException {
        File folder = core.getDataFolder();

        if (!folder.exists()
                && !folder.mkdirs()
                && !folder.exists()) {
            throw new IOException(
                    "Could not create MineacleCore data folder"
            );
        }

        if (!file.exists()
                && !file.createNewFile()
                && !file.exists()) {
            throw new IOException("Could not create chat.yml");
        }
    }

    private void atomicSave(
            YamlConfiguration configuration
    ) throws IOException {
        File temporary = new File(
                file.getParentFile(),
                file.getName() + ".tmp"
        );

        configuration.save(temporary);

        try {
            Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private void clearReplyPair(
            UUID first,
            UUID second
    ) {
        if (second.equals(replyTargets.get(first))) {
            replyTargets.remove(first);
        }

        if (first.equals(replyTargets.get(second))) {
            replyTargets.remove(second);
        }
    }

    private Component neutral(String text) {
        return Component.text(
                        text == null ? "" : text,
                        net.kyori.adventure.text.format.TextColor.color(
                                0xBBBBBB
                        )
                )
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component primary(String text) {
        return Component.text(
                        text == null ? "" : text,
                        net.kyori.adventure.text.format.TextColor.color(
                                0xFF55FF
                        )
                )
                .decoration(TextDecoration.ITALIC, false);
    }
}
