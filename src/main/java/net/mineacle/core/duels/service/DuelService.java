package net.mineacle.core.duels.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.duels.model.DuelInvite;
import net.mineacle.core.duels.model.DuelSession;
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
        sessionsByPlayer.clear();
        zoneCountdown = -1;
    }

    public boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    public boolean queued(Player player) {
        return player != null && zoneQueued.contains(player.getUniqueId());
    }

    public boolean tracked(Player player) {
        return player != null && sessionsByPlayer.containsKey(player.getUniqueId());
    }

    public boolean frozen(Player player) {
        DuelSession session = session(player);
        return session != null && session.frozen();
    }

    public DuelSession session(Player player) {
        return player == null ? null : sessionsByPlayer.get(player.getUniqueId());
    }

    public void removeFromQueue(Player player) {
        if (player == null) {
            return;
        }

        boolean removed = zoneQueued.remove(player.getUniqueId());

        if (removed) {
            player.sendMessage(message("messages.queue-left", "&#bbbbbbNo longer queued"));
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
        tickSessions();
        cleanupInvites();
    }

    private void tickQueue() {
        if (zoneQueued.size() < minPlayers()) {
            zoneCountdown = -1;
            broadcastQueueActionbar();
            return;
        }

        if (zoneCountdown < 0) {
            zoneCountdown = countdownSeconds();
        }

        broadcastQueueActionbar();

        if (zoneCountdown <= 0) {
            List<Player> players = queuedPlayers();

            if (players.size() >= minPlayers()) {
                startLooseDuel(players);
            }

            zoneQueued.clear();
            zoneCountdown = -1;
            return;
        }

        zoneCountdown--;
    }

    private void tickSessions() {
        Set<DuelSession> sessions = new HashSet<>(sessionsByPlayer.values());

        for (DuelSession session : sessions) {
            long now = System.currentTimeMillis();

            if (session.expired()) {
                clearSession(session);
                continue;
            }

            if (session.frozen()) {
                int seconds = Math.max(1, (int) Math.ceil((session.fightStartsAt() - now) / 1000.0D));

                for (UUID id : session.players()) {
                    Player player = Bukkit.getPlayer(id);

                    if (player != null && player.isOnline()) {
                        sendActionbar(player, config.getString("messages.fight-countdown-actionbar", "&#bbbbbbFight begins in &d%seconds%")
                                .replace("%seconds%", String.valueOf(seconds)));
                    }
                }

                continue;
            }

            if (!session.released()) {
                session.release();

                for (UUID id : session.players()) {
                    Player player = Bukkit.getPlayer(id);

                    if (player != null && player.isOnline()) {
                        sendActionbar(player, config.getString("messages.released-actionbar", "&aFight"));
                        player.sendMessage(message("messages.started", "&#bbbbbbFight"));
                    }
                }
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

        long expiresAt = System.currentTimeMillis() + (inviteTimeoutSeconds() * 1000L);
        invitesByTarget.put(target.getUniqueId(), new DuelInvite(challenger.getUniqueId(), target.getUniqueId(), expiresAt));

        challenger.sendMessage(message("messages.invite-sent", "&#bbbbbbDuel request sent to &d%target%")
                .replace("%target%", displayName(target)));

        target.sendMessage(message("messages.invite-received", "&#bbbbbb%player% sent you a duel request. Type &d/duel accept")
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

        startLooseDuel(List.of(challenger, target));
    }

    public void deny(Player target) {
        DuelInvite invite = invitesByTarget.remove(target.getUniqueId());

        if (invite == null) {
            target.sendMessage(message("messages.no-invite", "&cYou do not have a pending duel request"));
            return;
        }

        Player challenger = Bukkit.getPlayer(invite.challengerId());

        if (challenger != null) {
            challenger.sendMessage(message("messages.invite-denied", "&#bbbbbb%player% denied your duel request")
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

    public void startLooseDuel(List<Player> rawPlayers) {
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

        long now = System.currentTimeMillis();
        long fightStartsAt = now + (Math.max(0, preFightSeconds()) * 1000L);
        long expiresAt = now + (Math.max(30, trackingSeconds()) * 1000L);

        Set<UUID> ids = new HashSet<>();
        UUID sessionId = UUID.randomUUID();

        for (Player player : players) {
            ids.add(player.getUniqueId());
        }

        DuelSession session = new DuelSession(sessionId, ids, now, fightStartsAt, expiresAt);

        for (int index = 0; index < players.size(); index++) {
            Player player = players.get(index);

            sessionsByPlayer.put(player.getUniqueId(), session);
            zoneQueued.remove(player.getUniqueId());

            Location spawn = location.clone().add(offset(index, players.size()), 0.0D, offset(index + 2, players.size()));
            player.teleport(spawn);
            player.sendMessage(message("messages.matched", "&#bbbbbbMatched. Fight starts soon"));
            sendActionbar(player, config.getString("messages.teleported-actionbar", "&#bbbbbbMatched into Origins"));
        }
    }

    public void handleDeath(Player victim) {
        DuelSession session = session(victim);

        if (session == null) {
            return;
        }

        Player killer = victim.getKiller();

        if (killer == null || !session.contains(killer.getUniqueId())) {
            clearSession(session);
            return;
        }

        if (session.frozen()) {
            clearSession(session);
            return;
        }

        String winner = displayName(killer);
        String loser = displayName(victim);

        for (UUID id : session.players()) {
            Player participant = Bukkit.getPlayer(id);

            if (participant != null && participant.isOnline()) {
                participant.sendMessage(message("messages.winner", "&a%winner% &#bbbbbbdefeated &c%loser%")
                        .replace("%winner%", winner)
                        .replace("%loser%", loser));
                sendActionbar(participant, config.getString("messages.winner-actionbar", "&a%winner% &#bbbbbbwon the duel")
                        .replace("%winner%", winner)
                        .replace("%loser%", loser));
            }
        }

        clearSession(session);
    }

    public void handleQuit(Player player) {
        removeFromQueue(player);

        DuelSession session = session(player);

        if (session == null) {
            return;
        }

        if (session.frozen()) {
            clearSession(session);
            return;
        }

        sessionsByPlayer.remove(player.getUniqueId());
    }

    public boolean shouldCancelFrozenMove(Player player) {
        return frozen(player) && config.getBoolean("fair-start.freeze-movement", true);
    }

    public boolean shouldCancelFrozenDamage(Player player) {
        return frozen(player) && config.getBoolean("fair-start.prevent-damage", true);
    }

    public boolean shouldCancelFrozenInteract(Player player) {
        return frozen(player) && config.getBoolean("fair-start.prevent-interact", true);
    }

    private void clearSession(DuelSession session) {
        for (UUID id : session.players()) {
            sessionsByPlayer.remove(id);
        }
    }

    private void updateZoneQueue() {
        Set<UUID> found = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("mineacleduels.use")) {
                continue;
            }

            if (!validPlayer(player) || tracked(player)) {
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
                    player.sendMessage(message("messages.queue-left", "&#bbbbbbNo longer queued"));
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

            if (player != null && player.isOnline() && validPlayer(player) && !tracked(player)) {
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
            raw = config.getString("messages.queue-waiting-actionbar", "&#bbbbbbQueue: &eWaiting for an opponent");
        } else {
            raw = config.getString("messages.queue-actionbar", "&#bbbbbbQueue: &a%queued%/%max% teleporting in &d%seconds%s")
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
        World world = Bukkit.getWorld(config.getString("destination.world", "origins"));

        if (world == null) {
            return null;
        }

        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        double borderRadius = Math.max(16.0D, border.getSize() / 2.0D - 32.0D);
        double maxRadius = config.getDouble("destination.max-radius", 0.0D);

        if (maxRadius <= 0.0D) {
            maxRadius = borderRadius;
        }

        double radius = Math.min(borderRadius, maxRadius);
        double minRadius = Math.max(0.0D, config.getDouble("destination.min-radius", 250.0D));
        int attempts = Math.max(10, config.getInt("destination.find-location-attempts", 80));

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

    private int countdownSeconds() {
        return Math.max(3, config.getInt("queue.countdown-seconds", 30));
    }

    private int inviteTimeoutSeconds() {
        return Math.max(10, config.getInt("duel-request.timeout-seconds", 60));
    }

    private int preFightSeconds() {
        return Math.max(0, config.getInt("fair-start.countdown-seconds", 5));
    }

    private int trackingSeconds() {
        return Math.max(30, config.getInt("loose-tracking-seconds", 180));
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
        config.addDefault("duel-request.timeout-seconds", 60);
        config.addDefault("loose-tracking-seconds", 180);
        config.addDefault("destination.world", "origins");
        config.addDefault("destination.min-radius", 250);
        config.addDefault("destination.max-radius", 0);
        config.addDefault("destination.find-location-attempts", 80);
        config.addDefault("fair-start.enabled", true);
        config.addDefault("fair-start.countdown-seconds", 5);
        config.addDefault("fair-start.freeze-movement", true);
        config.addDefault("fair-start.prevent-damage", true);
        config.addDefault("fair-start.prevent-interact", true);
        config.options().copyDefaults(true);
    }
}
