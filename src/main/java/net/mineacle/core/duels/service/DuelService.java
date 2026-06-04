package net.mineacle.core.duels.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.duels.model.DuelInvite;
import net.mineacle.core.duels.model.DuelSession;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
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
    private final Map<UUID, DuelSession> sessionsByPlayer = new HashMap<>();
    private final Set<UUID> zoneQueued = new HashSet<>();
    private final Random random = new Random();

    private int zoneCountdown = -1;

    public DuelService(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "duels.yml");
        reload();
    }

    public void reload() {
        this.config = YamlConfiguration.loadConfiguration(file);
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
        sessionsByPlayer.clear();
        zoneCountdown = -1;
    }

    public FileConfiguration config() {
        return config;
    }

    public boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    public boolean inDuel(Player player) {
        return player != null && sessionsByPlayer.containsKey(player.getUniqueId());
    }

    public DuelSession session(Player player) {
        return player == null ? null : sessionsByPlayer.get(player.getUniqueId());
    }

    public boolean queued(Player player) {
        return player != null && zoneQueued.contains(player.getUniqueId());
    }

    public void removeFromQueue(Player player) {
        if (player == null) {
            return;
        }

        zoneQueued.remove(player.getUniqueId());

        if (zoneQueued.size() < minPlayers()) {
            zoneCountdown = -1;
        }
    }

    public void tick() {
        if (!enabled()) {
            return;
        }

        updateZoneQueue();

        if (zoneQueued.size() < minPlayers()) {
            if (zoneCountdown > 0) {
                broadcastQueue("messages.queue-cancelled", "&#bbbbbbDuel countdown cancelled");
            }

            zoneCountdown = -1;
            return;
        }

        if (zoneCountdown < 0) {
            zoneCountdown = countdownSeconds();
            broadcastQueue("messages.queue-started", "&#bbbbbbDuel starting in &d%seconds%s");
        }

        broadcastQueueActionbar();

        if (zoneCountdown <= 0) {
            List<Player> players = queuedPlayers();

            if (players.size() >= minPlayers()) {
                startDuel(players);
            }

            zoneQueued.clear();
            zoneCountdown = -1;
            return;
        }

        zoneCountdown--;
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

        if (inDuel(challenger) || inDuel(target)) {
            challenger.sendMessage(message("messages.already-in-duel", "&cThat player is already in a duel"));
            return;
        }

        if (!validPlayer(challenger) || !validPlayer(target)) {
            challenger.sendMessage(message("messages.invalid-player", "&cThat player cannot duel right now"));
            return;
        }

        long expiresAt = System.currentTimeMillis() + (inviteTimeoutSeconds() * 1000L);
        invitesByTarget.put(target.getUniqueId(), new DuelInvite(challenger.getUniqueId(), target.getUniqueId(), expiresAt));

        challenger.sendMessage(message("messages.invite-sent", "&#bbbbbbDuel request sent to &d%target%")
                .replace("%target%", displayName(target)));

        target.sendMessage(message("messages.invite-received", "&d%player% &#bbbbbbsent you a duel request. Type &d/duel accept")
                .replace("%player%", displayName(challenger)));
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

        if (inDuel(challenger) || inDuel(target)) {
            target.sendMessage(message("messages.already-in-duel", "&cThat player is already in a duel"));
            return;
        }

        startDuel(List.of(challenger, target));
    }

    public void deny(Player target) {
        DuelInvite invite = invitesByTarget.remove(target.getUniqueId());

        if (invite == null) {
            target.sendMessage(message("messages.no-invite", "&cYou do not have a pending duel request"));
            return;
        }

        Player challenger = Bukkit.getPlayer(invite.challengerId());

        if (challenger != null) {
            challenger.sendMessage(message("messages.invite-denied", "&d%player% &#bbbbbbdenied your duel request")
                    .replace("%player%", displayName(target)));
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
            challenger.sendMessage(message("messages.no-outgoing", "&cYou do not have an outgoing duel request"));
            return;
        }

        invitesByTarget.remove(found);
        challenger.sendMessage(message("messages.cancelled", "&#bbbbbbDuel request cancelled"));
    }

    public void startDuel(List<Player> rawPlayers) {
        List<Player> players = rawPlayers.stream()
                .filter(player -> player != null && player.isOnline())
                .filter(this::validPlayer)
                .filter(player -> !inDuel(player))
                .limit(maxPlayers())
                .toList();

        if (players.size() < minPlayers()) {
            return;
        }

        Location arena = findArenaLocation();

        if (arena == null) {
            for (Player player : players) {
                player.sendMessage(message("messages.no-arena", "&cCould not find a safe duel location"));
            }
            return;
        }

        UUID sessionId = UUID.randomUUID();
        Set<UUID> ids = new HashSet<>();

        for (Player player : players) {
            ids.add(player.getUniqueId());
        }

        DuelSession session = new DuelSession(sessionId, ids, arena);

        for (Player player : players) {
            sessionsByPlayer.put(player.getUniqueId(), session);
            zoneQueued.remove(player.getUniqueId());
            player.teleport(arena.clone().add(random.nextInt(7) - 3, 0, random.nextInt(7) - 3));
            player.sendMessage(message("messages.started", "&#bbbbbbDuel started. Fight to the death"));
            sendActionbar(player, config.getString("messages.started-actionbar", "&#bbbbbbDuel started"));
        }
    }

    public void eliminate(Player player) {
        DuelSession session = session(player);

        if (session == null || !session.alive(player.getUniqueId())) {
            return;
        }

        session.eliminate(player.getUniqueId());

        for (UUID id : session.players()) {
            Player participant = Bukkit.getPlayer(id);

            if (participant != null && participant.isOnline()) {
                participant.sendMessage(message("messages.eliminated", "&d%player% &#bbbbbbwas eliminated")
                        .replace("%player%", displayName(player)));
            }
        }

        if (session.finished()) {
            finish(session);
        }
    }

    public void forfeit(Player player) {
        DuelSession session = session(player);

        if (session == null) {
            return;
        }

        session.eliminate(player.getUniqueId());

        player.sendMessage(message("messages.forfeit", "&#bbbbbbYou left the duel"));

        if (session.finished()) {
            finish(session);
        }
    }

    public void finish(DuelSession session) {
        UUID winnerId = session.alive().stream().findFirst().orElse(null);
        Player winner = winnerId == null ? null : Bukkit.getPlayer(winnerId);
        String winnerName = winner == null ? "No one" : displayName(winner);

        for (UUID id : session.players()) {
            Player player = Bukkit.getPlayer(id);
            sessionsByPlayer.remove(id);

            if (player != null && player.isOnline()) {
                player.sendMessage(message("messages.finished", "&#bbbbbbDuel finished. Winner: &d%winner%")
                        .replace("%winner%", winnerName));
                sendActionbar(player, config.getString("messages.finished-actionbar", "&#bbbbbbWinner: &d%winner%")
                        .replace("%winner%", winnerName));
            }
        }
    }

    public boolean blockedCommand(Player player, String command) {
        if (!inDuel(player)) {
            return false;
        }

        String lower = command.toLowerCase(Locale.ROOT);

        for (String blocked : config.getStringList("blocked-commands")) {
            String normalized = blocked.toLowerCase(Locale.ROOT);

            if (lower.equals(normalized) || lower.startsWith(normalized + " ")) {
                return true;
            }
        }

        return false;
    }

    private void updateZoneQueue() {
        Set<UUID> found = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("mineacleduels.use")) {
                continue;
            }

            if (!validPlayer(player) || inDuel(player)) {
                continue;
            }

            if (insideAnyQueueZone(player)) {
                found.add(player.getUniqueId());

                if (!zoneQueued.contains(player.getUniqueId())) {
                    player.sendMessage(message("messages.queue-joined", "&#bbbbbbYou joined the duel queue"));
                }
            }
        }

        for (UUID old : new HashSet<>(zoneQueued)) {
            if (!found.contains(old)) {
                Player player = Bukkit.getPlayer(old);

                if (player != null) {
                    player.sendMessage(message("messages.queue-left", "&#bbbbbbYou left the duel queue"));
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
            String worldName = config.getString(path + ".world", "");
            World world = Bukkit.getWorld(worldName);

            if (world == null || !player.getWorld().equals(world)) {
                continue;
            }

            double x = config.getDouble(path + ".x");
            double y = config.getDouble(path + ".y", player.getLocation().getY());
            double z = config.getDouble(path + ".z");
            double radius = config.getDouble(path + ".radius", 5.0D);
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

            if (player != null && player.isOnline() && validPlayer(player) && !inDuel(player)) {
                players.add(player);
            }
        }

        return players;
    }

    private void broadcastQueue(String path, String fallback) {
        String message = message(path, fallback).replace("%seconds%", String.valueOf(zoneCountdown));

        for (Player player : queuedPlayers()) {
            player.sendMessage(message);
        }
    }

    private void broadcastQueueActionbar() {
        String raw = config.getString("messages.queue-actionbar", "&#bbbbbbDuel starting in &d%seconds%s")
                .replace("%seconds%", String.valueOf(Math.max(0, zoneCountdown)));

        for (Player player : queuedPlayers()) {
            sendActionbar(player, raw);
        }
    }

    private boolean validPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        return player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
    }

    private Location findArenaLocation() {
        World world = Bukkit.getWorld(config.getString("arena.world", "origins"));

        if (world == null) {
            return null;
        }

        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        double borderRadius = Math.max(16.0D, border.getSize() / 2.0D - 32.0D);
        double maxRadius = Math.max(32.0D, config.getDouble("arena.max-radius", borderRadius));
        double radius = Math.min(borderRadius, maxRadius);
        double minRadius = Math.max(0.0D, config.getDouble("arena.min-radius", 250.0D));
        int attempts = Math.max(10, config.getInt("arena.find-location-attempts", 80));

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
        int y = world.getHighestBlockYAt(x, z, FluidCollisionMode.NEVER);
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

    private int minPlayers() {
        return Math.max(2, config.getInt("queue.min-players", 2));
    }

    private int maxPlayers() {
        return Math.max(minPlayers(), config.getInt("queue.max-players", 8));
    }

    private int countdownSeconds() {
        return Math.max(3, config.getInt("queue.countdown-seconds", 30));
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

    private void sendActionbar(Player player, String input) {
        Component component = LegacyComponentSerializer.legacySection().deserialize(TextColor.color(input));
        player.sendActionBar(component);
    }
}
