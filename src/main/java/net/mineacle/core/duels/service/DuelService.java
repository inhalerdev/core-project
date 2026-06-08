package net.mineacle.core.duels.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.duels.model.DuelInvite;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
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
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class DuelService {

    private final Core core;
    private final File file;
    private FileConfiguration config;

    private final Map<UUID, DuelInvite> invitesByTarget = new HashMap<>();
    private final Set<UUID> zoneQueued = new HashSet<>();
    private final Map<UUID, PendingTeleport> pendingTeleportsByPlayer = new HashMap<>();
    private final Set<PendingTeleport> pendingTeleports = new HashSet<>();
    private final Random random = new Random();

    private int zoneCountdown = -1;

    public DuelService(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "duels.yml");
        reload();
    }

    public void reload() {
        this.config = YamlConfiguration.loadConfiguration(file);
        ensureDefaults();
        save();
    }

    public void save() {
        try {
            config.save(file);
        } catch (IOException exception) {
            core.getLogger().severe("Could not save duels.yml");
            exception.printStackTrace();
        }
    }

    public void shutdown() {
        zoneQueued.clear();
        invitesByTarget.clear();
        pendingTeleportsByPlayer.clear();
        pendingTeleports.clear();
        zoneCountdown = -1;
    }

    public boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    public boolean queued(Player player) {
        return player != null && zoneQueued.contains(player.getUniqueId());
    }

    public boolean pendingTeleport(Player player) {
        return player != null && pendingTeleportsByPlayer.containsKey(player.getUniqueId());
    }

    public void removeFromQueue(Player player) {
        if (player == null) {
            return;
        }

        boolean removed = zoneQueued.remove(player.getUniqueId());

        if (removed) {
            sendActionbar(player, config.getString("messages.queue-left-actionbar", "&#bbbbbbNo longer queued"));
        }

        if (zoneQueued.size() < minPlayers()) {
            zoneCountdown = -1;
        }
    }

    public void tick() {
        if (!enabled()) {
            return;
        }

        updateZoneQueue();
        tickQueue();
        tickPendingTeleports();
        cleanupInvites();
    }

    private void tickQueue() {
        if (zoneQueued.size() < minPlayers()) {
            zoneCountdown = -1;
            broadcastQueueActionbar();
            return;
        }

        if (zoneCountdown < 0) {
            zoneCountdown = queueCountdownSeconds();
        }

        broadcastQueueActionbar();

        if (zoneCountdown <= 0) {
            List<Player> players = queuedPlayers();

            if (players.size() >= minPlayers()) {
                teleportTogether(players);
            }

            zoneQueued.clear();
            zoneCountdown = -1;
            return;
        }

        zoneCountdown--;
    }

    private void tickPendingTeleports() {
        for (PendingTeleport pending : new HashSet<>(pendingTeleports)) {
            List<Player> players = pending.onlinePlayers();

            if (players.size() < pending.minimumPlayers()) {
                cancelPendingTeleport(pending, config.getString("messages.teleport-cancelled", "&cTeleport cancelled"));
                continue;
            }

            Player moved = pending.movedPlayer();

            if (moved != null) {
                cancelPendingTeleportMoved(pending, moved);
                continue;
            }

            int seconds = pending.secondsRemaining();

            if (seconds <= 0) {
                completePendingTeleport(pending);
                continue;
            }

            String message = config.getString("messages.teleport-countdown-actionbar", "&#bbbbbbDuel teleport in &d%seconds%s")
                    .replace("%seconds%", String.valueOf(seconds));

            for (Player player : players) {
                sendActionbar(player, message);
            }
        }
    }

    private void cleanupInvites() {
        invitesByTarget.entrySet().removeIf(entry -> entry.getValue().expired());
    }

    public void challenge(Player challenger, Player target) {
        if (!enabled()) {
            challenger.sendMessage(message("messages.disabled", "&cDuels are disabled"));
            return;
        }

        if (challenger.equals(target)) {
            challenger.sendMessage(message("messages.self", "&cYou cannot duel yourself"));
            return;
        }

        if (!validPlayer(challenger) || !validPlayer(target)) {
            challenger.sendMessage(message("messages.invalid-player", "&cThat player cannot duel right now"));
            return;
        }

        if (pendingTeleport(challenger) || pendingTeleport(target)) {
            challenger.sendMessage(message("messages.already-teleporting", "&cThat player is already preparing for a duel"));
            return;
        }

        long expiresAt = System.currentTimeMillis() + (inviteTimeoutSeconds() * 1000L);
        invitesByTarget.put(target.getUniqueId(), new DuelInvite(challenger.getUniqueId(), target.getUniqueId(), expiresAt));

        challenger.sendMessage(legacy(config.getString("messages.invite-sent", "&#bbbbbbDuel request sent to &#ff88ff%target%")
                .replace("%target%", DisplayNames.displayName(target))));

        String challengerName = DisplayNames.prefixedDisplayName(challenger);
        String mainLine = config.getString("messages.invite-received-header", "%player% &#bbbbbbsent you a duel request")
                .replace("%player%", challengerName);

        target.sendActionBar(legacy(mainLine));
        target.sendMessage(legacy(mainLine));

        Component accept = legacy(config.getString("messages.accept-button", "&a[Accept]"))
                .clickEvent(ClickEvent.runCommand("/duel accept"))
                .hoverEvent(HoverEvent.showText(legacy(config.getString("messages.accept-hover", "&#bbbbbbAccept duel request"))));

        Component deny = legacy(config.getString("messages.deny-button", "&c[Deny]"))
                .clickEvent(ClickEvent.runCommand("/duel deny"))
                .hoverEvent(HoverEvent.showText(legacy(config.getString("messages.deny-hover", "&#bbbbbbDeny duel request"))));

        Component buttons = legacy(config.getString("messages.respond-prefix", "&#bbbbbbRespond "))
                .append(accept)
                .append(Component.space())
                .append(deny);

        target.sendMessage(buttons);
    }

    public void accept(Player target) {
        DuelInvite invite = invitesByTarget.remove(target.getUniqueId());

        if (invite == null || invite.expired()) {
            target.sendMessage(message("messages.no-invite", "&cYou do not have a pending duel request"));
            return;
        }

        Player challenger = Bukkit.getPlayer(invite.challengerId());

        if (challenger == null || !challenger.isOnline()) {
            target.sendMessage(message("messages.inviter-offline", "&cThat player is no longer online"));
            return;
        }

        startTeleportCountdown(List.of(challenger, target), minPlayers());
    }

    public void deny(Player target) {
        DuelInvite invite = invitesByTarget.remove(target.getUniqueId());

        if (invite == null) {
            target.sendMessage(message("messages.no-invite", "&cYou do not have a pending duel request"));
            return;
        }

        Player challenger = Bukkit.getPlayer(invite.challengerId());

        if (challenger != null) {
            challenger.sendMessage(legacy(config.getString("messages.invite-denied", "&#ff88ff%player% &#bbbbbbdenied your duel request")
                    .replace("%player%", DisplayNames.displayName(target))));
        }

        target.sendMessage(message("messages.denied", "&#bbbbbbDuel request denied"));
    }

    public void cancel(Player challenger) {
        UUID challengerId = challenger.getUniqueId();
        UUID found = null;

        for (Map.Entry<UUID, DuelInvite> entry : invitesByTarget.entrySet()) {
            if (entry.getValue().challengerId().equals(challengerId)) {
                found = entry.getKey();
                break;
            }
        }

        if (found == null) {
            PendingTeleport pending = pendingTeleportsByPlayer.get(challengerId);

            if (pending != null) {
                cancelPendingTeleport(pending, config.getString("messages.teleport-cancelled", "&cTeleport cancelled"));
                return;
            }

            challenger.sendMessage(message("messages.no-outgoing", "&cYou do not have an outgoing duel request"));
            return;
        }

        invitesByTarget.remove(found);
        challenger.sendMessage(message("messages.cancelled", "&#bbbbbbDuel request &ccancelled"));
    }

    private void startTeleportCountdown(List<Player> rawPlayers, int minimumPlayers) {
        List<Player> players = rawPlayers.stream()
                .filter(player -> player != null && player.isOnline())
                .filter(this::validPlayer)
                .filter(player -> !pendingTeleport(player))
                .limit(maxPlayers())
                .toList();

        if (players.size() < minimumPlayers) {
            return;
        }

        PendingTeleport pending = new PendingTeleport(players, minimumPlayers, teleportCountdownSeconds());

        pendingTeleports.add(pending);

        for (Player player : players) {
            zoneQueued.remove(player.getUniqueId());
            pendingTeleportsByPlayer.put(player.getUniqueId(), pending);
            player.sendMessage(message("messages.teleport-started", "&#bbbbbbDuel teleport started. Do not move"));
            sendActionbar(player, config.getString("messages.teleport-countdown-actionbar", "&#bbbbbbDuel teleport in &d%seconds%s")
                    .replace("%seconds%", String.valueOf(teleportCountdownSeconds())));
        }
    }

    private void completePendingTeleport(PendingTeleport pending) {
        List<Player> players = pending.onlinePlayers();
        clearPendingTeleport(pending);

        if (players.size() >= pending.minimumPlayers()) {
            teleportTogether(players);
        }
    }

    private void cancelPendingTeleportMoved(PendingTeleport pending, Player moved) {
        List<Player> players = pending.onlinePlayers();
        clearPendingTeleport(pending);

        String movedName = DisplayNames.prefixedDisplayName(moved);
        String moverMessage = config.getString("messages.teleport-cancelled-moved", "&cTeleport cancelled — you moved");
        String otherMessage = config.getString("messages.teleport-cancelled-other-moved", "&cDuel cancelled — %player% moved")
                .replace("%player%", movedName);

        for (Player player : players) {
            String message = player.getUniqueId().equals(moved.getUniqueId()) ? moverMessage : otherMessage;
            player.sendMessage(TextColor.color(message));
            sendActionbar(player, message);
        }
    }

    private void cancelPendingTeleport(PendingTeleport pending, String message) {
        List<Player> players = pending.onlinePlayers();
        clearPendingTeleport(pending);

        for (Player player : players) {
            player.sendMessage(TextColor.color(message));
            sendActionbar(player, message);
        }
    }

    private void clearPendingTeleport(PendingTeleport pending) {
        pendingTeleports.remove(pending);

        for (UUID id : pending.playerIds()) {
            pendingTeleportsByPlayer.remove(id);
        }
    }

    private void teleportTogether(List<Player> rawPlayers) {
        List<Player> players = rawPlayers.stream()
                .filter(player -> player != null && player.isOnline())
                .filter(this::validPlayer)
                .limit(maxPlayers())
                .toList();

        if (players.size() < minPlayers()) {
            return;
        }

        Location location = findDuelLocation();

        if (location == null) {
            for (Player player : players) {
                player.sendMessage(message("messages.no-arena", "&cCould not find a safe duel location"));
            }
            return;
        }

        location.getChunk().load(true);

        for (int index = 0; index < players.size(); index++) {
            Player player = players.get(index);
            zoneQueued.remove(player.getUniqueId());

            Location spawn = location.clone().add(offset(index, players.size()), 0.0D, offset(index + 2, players.size()));
            player.teleport(spawn);
            sendActionbar(player, config.getString("messages.teleported-actionbar", "&#bbbbbbTeleported to &dOrigins"));
        }
    }

    private void updateZoneQueue() {
        Set<UUID> found = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("mineacleduels.use")) {
                continue;
            }

            if (!validPlayer(player) || pendingTeleport(player)) {
                continue;
            }

            if (insideAnyQueueZone(player)) {
                found.add(player.getUniqueId());
            }
        }

        for (UUID old : new HashSet<>(zoneQueued)) {
            if (!found.contains(old)) {
                Player player = Bukkit.getPlayer(old);

                if (player != null) {
                    sendActionbar(player, config.getString("messages.queue-left-actionbar", "&#bbbbbbNo longer queued"));
                }
            }
        }

        zoneQueued.clear();
        zoneQueued.addAll(found);
    }

    private boolean insideAnyQueueZone(Player player) {
        ConfigurationSection section = config.getConfigurationSection("queue-zones");

        if (section == null) {
            return false;
        }

        for (String key : section.getKeys(false)) {
            String path = "queue-zones." + key;
            World world = Bukkit.getWorld(config.getString(path + ".world", ""));

            if (world == null || !player.getWorld().equals(world)) {
                continue;
            }

            double x = config.getDouble(path + ".x");
            double y = config.getDouble(path + ".y", player.getLocation().getY());
            double z = config.getDouble(path + ".z");
            double radius = config.getDouble(path + ".radius", 9.0D);
            double minY = config.getDouble(path + ".min-y", y - 3.0D);
            double maxY = config.getDouble(path + ".max-y", y + 5.0D);

            Location center = new Location(world, x, player.getLocation().getY(), z);

            if (player.getLocation().getY() < minY || player.getLocation().getY() > maxY) {
                continue;
            }

            if (player.getLocation().distanceSquared(center) <= radius * radius) {
                return true;
            }
        }

        return false;
    }

    private List<Player> queuedPlayers() {
        List<Player> players = new ArrayList<>();

        for (UUID id : zoneQueued) {
            Player player = Bukkit.getPlayer(id);

            if (player != null && player.isOnline() && validPlayer(player) && !pendingTeleport(player)) {
                players.add(player);
            }
        }

        return players;
    }

    private void broadcastQueueActionbar() {
        List<Player> players = queuedPlayers();
        int queued = players.size();
        String raw;

        if (queued < minPlayers()) {
            raw = config.getString("messages.queue-waiting-actionbar", "&#bbbbbbQueue: &#ff88ffWaiting for an opponent");
        } else {
            raw = config.getString("messages.queue-actionbar", "&#bbbbbbQueue: &#ff88ff%queued%/%max%&#bbbbbb teleporting in &d%seconds%s")
                    .replace("%seconds%", String.valueOf(Math.max(0, zoneCountdown)))
                    .replace("%queued%", String.valueOf(queued))
                    .replace("%min%", String.valueOf(minPlayers()))
                    .replace("%max%", String.valueOf(maxPlayers()));
        }

        for (Player player : players) {
            sendActionbar(player, raw);
        }
    }

    private Location findDuelLocation() {
        World world = Bukkit.getWorld(config.getString("destination.world", config.getString("arena.world", "origins")));

        if (world == null) {
            return null;
        }

        WorldBorder border = world.getWorldBorder();
        Location center = new Location(world, config.getDouble("destination.center-x", border.getCenter().getX()), 64.0D, config.getDouble("destination.center-z", border.getCenter().getZ()));
        double borderRadius = Math.max(16.0D, border.getSize() / 2.0D - 32.0D);
        double maxRadius = config.getDouble("destination.max-radius", config.getDouble("arena.max-radius", 48000.0D));

        if (maxRadius <= 0.0D) {
            maxRadius = borderRadius;
        }

        double radius = Math.min(borderRadius, maxRadius);
        double minRadius = Math.max(0.0D, config.getDouble("destination.min-radius", config.getDouble("arena.min-radius", 5000.0D)));
        int attempts = Math.max(10, config.getInt("destination.find-location-attempts", config.getInt("arena.find-location-attempts", 120)));

        for (int attempt = 0; attempt < attempts; attempt++) {
            double distance = minRadius + (random.nextDouble() * Math.max(1.0D, radius - minRadius));
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            Location safe = safeLocation(world, x, z);

            if (safe != null) {
                return safe;
            }
        }

        return null;
    }

    private Location safeLocation(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Location location = new Location(world, x + 0.5D, y + 1.0D, z + 0.5D);
        Block ground = world.getBlockAt(x, y, z);
        Block feet = world.getBlockAt(x, y + 1, z);
        Block head = world.getBlockAt(x, y + 2, z);

        if (!ground.getType().isSolid()) {
            return null;
        }

        if (!feet.isPassable() || !head.isPassable()) {
            return null;
        }

        Material type = ground.getType();

        if (type == Material.LAVA
                || type == Material.WATER
                || type == Material.MAGMA_BLOCK
                || type == Material.CACTUS
                || type == Material.POWDER_SNOW) {
            return null;
        }

        return location;
    }

    public void setQueueZone(String id, Player player, double radius) {
        String key = normalizeZoneId(id);
        String path = "queue-zones." + key;
        Location location = player.getLocation();

        config.set(path + ".world", location.getWorld().getName());
        config.set(path + ".x", round(location.getX()));
        config.set(path + ".y", round(location.getY()));
        config.set(path + ".z", round(location.getZ()));
        config.set(path + ".radius", Math.max(1.0D, radius));
        config.set(path + ".min-y", round(location.getY() - 3.0D));
        config.set(path + ".max-y", round(location.getY() + 5.0D));
        save();
    }

    public boolean removeQueueZone(String id) {
        String key = normalizeZoneId(id);
        String path = "queue-zones." + key;

        if (!config.isConfigurationSection(path)) {
            return false;
        }

        config.set(path, null);
        save();
        return true;
    }

    public List<String> queueZoneIds() {
        ConfigurationSection section = config.getConfigurationSection("queue-zones");

        if (section == null) {
            return List.of();
        }

        List<String> ids = new ArrayList<>(section.getKeys(false));
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        return ids;
    }

    public String queueZoneInfo(String id) {
        String key = normalizeZoneId(id);
        String path = "queue-zones." + key;

        if (!config.isConfigurationSection(path)) {
            return null;
        }

        return key
                + " &8- &#bbbbbb"
                + config.getString(path + ".world", "unknown")
                + " &d"
                + trim(config.getDouble(path + ".x"))
                + " "
                + trim(config.getDouble(path + ".y"))
                + " "
                + trim(config.getDouble(path + ".z"))
                + " &#bbbbbbradius &d"
                + trim(config.getDouble(path + ".radius", 9.0D));
    }

    private boolean validPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        return player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
    }

    private double offset(int index, int size) {
        if (size <= 1) {
            return 0.0D;
        }

        double angle = (Math.PI * 2.0D / size) * index;
        return Math.round(Math.cos(angle) * 4.0D);
    }

    private int minPlayers() {
        return Math.max(2, config.getInt("queue.min-players", 2));
    }

    private int maxPlayers() {
        return Math.max(minPlayers(), config.getInt("queue.max-players", 8));
    }

    private int queueCountdownSeconds() {
        return Math.max(3, config.getInt("queue.countdown-seconds", 30));
    }

    private int teleportCountdownSeconds() {
        return Math.max(3, config.getInt("teleport.countdown-seconds", 30));
    }

    private int inviteTimeoutSeconds() {
        return Math.max(10, config.getInt("duel-request.timeout-seconds", 60));
    }

    private String displayName(Player player) {
        return player == null ? "Unknown" : player.getDisplayName();
    }

    private String message(String path, String fallback) {
        return TextColor.color(config.getString(path, fallback));
    }

    private Component legacy(String input) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(input));
    }

    private void sendActionbar(Player player, String input) {
        player.sendActionBar(legacy(input));
    }

    private String normalizeZoneId(String input) {
        if (input == null || input.isBlank()) {
            return "spawn1";
        }

        return input.toLowerCase(Locale.ROOT)
                .replace(" ", "_")
                .replace("-", "_")
                .replaceAll("[^a-z0-9_]", "");
    }

    private double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private String trim(double value) {
        double rounded = round(value);

        if (Math.abs(rounded - Math.rint(rounded)) < 0.001D) {
            return String.valueOf((long) rounded);
        }

        return String.valueOf(rounded);
    }

    private void ensureDefaults() {
        config.addDefault("enabled", true);
        config.addDefault("queue.min-players", 2);
        config.addDefault("queue.max-players", 8);
        config.addDefault("queue.countdown-seconds", 30);
        config.addDefault("teleport.countdown-seconds", 30);
        config.addDefault("duel-request.timeout-seconds", 60);
        config.addDefault("destination.world", "origins");
        config.addDefault("destination.center-x", 0);
        config.addDefault("destination.center-z", 1);
        config.addDefault("destination.min-radius", 5000);
        config.addDefault("destination.max-radius", 48000);
        config.addDefault("destination.find-location-attempts", 120);
        config.options().copyDefaults(true);
    }

    private final class PendingTeleport {

        private final Set<UUID> playerIds = new HashSet<>();
        private final Map<UUID, Location> startLocations = new HashMap<>();
        private final int minimumPlayers;
        private final long completeAt;

        private PendingTeleport(List<Player> players, int minimumPlayers, int countdownSeconds) {
            this.minimumPlayers = minimumPlayers;
            this.completeAt = System.currentTimeMillis() + (countdownSeconds * 1000L);

            for (Player player : players) {
                playerIds.add(player.getUniqueId());
                startLocations.put(player.getUniqueId(), player.getLocation().clone());
            }
        }

        private Set<UUID> playerIds() {
            return playerIds;
        }

        private int minimumPlayers() {
            return minimumPlayers;
        }

        private int secondsRemaining() {
            return (int) Math.ceil((completeAt - System.currentTimeMillis()) / 1000.0D);
        }

        private List<Player> onlinePlayers() {
            List<Player> players = new ArrayList<>();

            for (UUID id : playerIds) {
                Player player = Bukkit.getPlayer(id);

                if (player != null && player.isOnline() && validPlayer(player)) {
                    players.add(player);
                }
            }

            return players;
        }

        private Player movedPlayer() {
            for (Player player : onlinePlayers()) {
                Location start = startLocations.get(player.getUniqueId());

                if (start == null || start.getWorld() == null || !start.getWorld().equals(player.getWorld())) {
                    return player;
                }

                Location current = player.getLocation();

                if (start.distanceSquared(current) > 4.0D) {
                    return player;
                }
            }

            return null;
        }
    }
}
