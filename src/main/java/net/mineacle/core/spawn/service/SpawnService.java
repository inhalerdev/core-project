package net.mineacle.core.spawn.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.spawn.model.SpawnPoint;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class SpawnService {

    private final Core core;
    private final Random random = new Random();

    private File spawnFile;
    private FileConfiguration spawnConfig;

    public SpawnService(Core core) {
        this.core = core;
        load();
    }

    public void load() {
        if (!core.getDataFolder().exists()) {
            core.getDataFolder().mkdirs();
        }

        spawnFile = new File(core.getDataFolder(), "spawn.yml");

        if (!spawnFile.exists()) {
            core.saveResource("spawn.yml", false);
        }

        spawnConfig = YamlConfiguration.loadConfiguration(spawnFile);
    }

    public void save() {
        if (spawnFile == null || spawnConfig == null) {
            return;
        }

        try {
            spawnConfig.save(spawnFile);
        } catch (IOException exception) {
            core.getLogger().severe("Could not save spawn.yml");
            exception.printStackTrace();
        }
    }

    public Core core() {
        return core;
    }

    public boolean enabled() {
        return spawnConfig.getBoolean("enabled", true);
    }

    public String title() {
        return TextColor.color(spawnConfig.getString("title", "Spawn"));
    }

    public int size() {
        int size = spawnConfig.getInt("size", 27);

        if (size < 9) {
            return 27;
        }

        if (size > 54) {
            return 54;
        }

        return ((size + 8) / 9) * 9;
    }

    public int teleportDelaySeconds() {
        return Math.max(0, spawnConfig.getInt("teleport.delay-seconds", 5));
    }

    public int teleportDelaySeconds(Player player) {
        int defaultDelay = Math.max(0, spawnConfig.getInt("teleport.delay-seconds", 5));
        int plusDelay = Math.max(0, spawnConfig.getInt("teleport.plus-delay-seconds", 3));
        String plusPermission = spawnConfig.getString("teleport.plus-permission", "mineacle.plus");

        if (player != null && player.hasPermission(plusPermission)) {
            return plusDelay;
        }

        return defaultDelay;
    }

    public boolean cancelOnMove() {
        return spawnConfig.getBoolean("teleport.cancel-on-move", true);
    }

    public double cancelMoveDistance() {
        return Math.max(0.01D, spawnConfig.getDouble("teleport.cancel-distance", 2.0D));
    }

    public int maxPlayersDisplay() {
        return Math.max(1, spawnConfig.getInt("max-players-display", 100));
    }

    public List<SpawnPoint> spawnPoints() {
        List<SpawnPoint> points = new ArrayList<>();
        ConfigurationSection section = spawnConfig.getConfigurationSection("worlds");

        if (section == null) {
            return points;
        }

        for (String id : section.getKeys(false)) {
            String path = "worlds." + id;

            if (!spawnConfig.contains(path + ".world")) {
                continue;
            }

            boolean enabled = spawnConfig.getBoolean(path + ".enabled", true);
            int slot = spawnConfig.getInt(path + ".slot", 0);
            String displayName = spawnConfig.getString(path + ".display-name", id);
            String worldName = spawnConfig.getString(path + ".world", id);

            points.add(new SpawnPoint(id, displayName, worldName, slot, enabled));
        }

        points.sort(Comparator.comparingInt(SpawnPoint::slot));
        return points;
    }

    public List<SpawnPoint> availableSpawnPoints() {
        List<SpawnPoint> available = new ArrayList<>();

        for (SpawnPoint point : spawnPoints()) {
            if (!point.enabled()) {
                continue;
            }

            if (Bukkit.getWorld(point.worldName()) == null) {
                continue;
            }

            available.add(point);
        }

        return available;
    }

    public SpawnPoint spawnPointById(String id) {
        if (id == null) {
            return null;
        }

        for (SpawnPoint point : spawnPoints()) {
            if (point.id().equalsIgnoreCase(id)) {
                return point;
            }
        }

        return null;
    }

    public SpawnPoint spawnPointBySlot(int slot) {
        for (SpawnPoint point : spawnPoints()) {
            if (point.enabled() && point.slot() == slot) {
                return point;
            }
        }

        return null;
    }

    public boolean isCurrentWorld(Player player, SpawnPoint point) {
        return player.getWorld().getName().equalsIgnoreCase(point.worldName());
    }

    public boolean isSpawnWorld(String worldName) {
        if (worldName == null) {
            return false;
        }

        for (SpawnPoint point : spawnPoints()) {
            if (!point.enabled()) {
                continue;
            }

            if (point.worldName().equalsIgnoreCase(worldName)) {
                return true;
            }
        }

        return false;
    }

    public int onlineInWorld(SpawnPoint point) {
        World world = Bukkit.getWorld(point.worldName());

        if (world == null) {
            return 0;
        }

        return world.getPlayers().size();
    }

    public SpawnPoint selectRandomPoint() {
        List<SpawnPoint> available = availableSpawnPoints();

        if (available.isEmpty()) {
            return null;
        }

        String mode = spawnConfig.getString("random.mode", "LOWEST_POPULATION").toUpperCase(Locale.ROOT);

        if (mode.equals("RANDOM")) {
            return available.get(random.nextInt(available.size()));
        }

        int lowest = available.stream()
                .mapToInt(this::onlineInWorld)
                .min()
                .orElse(0);

        List<SpawnPoint> tied = available.stream()
                .filter(point -> onlineInWorld(point) == lowest)
                .toList();

        if (tied.isEmpty()) {
            return available.get(random.nextInt(available.size()));
        }

        return tied.get(random.nextInt(tied.size()));
    }

    public SpawnPoint selectNamedOrRandom(String target) {
        if (target == null || target.isBlank() || target.equalsIgnoreCase("random")) {
            return selectRandomPoint();
        }

        SpawnPoint point = spawnPointById(target);

        if (point == null || !point.enabled() || Bukkit.getWorld(point.worldName()) == null) {
            return selectRandomPoint();
        }

        return point;
    }

    public SpawnPoint selectFirstJoinTarget() {
        return selectNamedOrRandom(spawnConfig.getString("first-join.target", "random"));
    }

    public SpawnPoint selectVoidTarget() {
        return selectNamedOrRandom(spawnConfig.getString("void.target", "random"));
    }

    public boolean teleport(Player player, SpawnPoint point) {
        Location location = location(point);

        if (location == null) {
            return false;
        }

        player.teleport(location);
        return true;
    }

    public Location location(SpawnPoint point) {
        World world = Bukkit.getWorld(point.worldName());

        if (world == null) {
            return null;
        }

        String path = "worlds." + point.id();

        if (!spawnConfig.contains(path + ".x")
                || !spawnConfig.contains(path + ".y")
                || !spawnConfig.contains(path + ".z")) {
            return world.getSpawnLocation();
        }

        double x = spawnConfig.getDouble(path + ".x");
        double y = spawnConfig.getDouble(path + ".y");
        double z = spawnConfig.getDouble(path + ".z");
        float yaw = (float) spawnConfig.getDouble(path + ".yaw", world.getSpawnLocation().getYaw());
        float pitch = (float) spawnConfig.getDouble(path + ".pitch", world.getSpawnLocation().getPitch());

        return new Location(world, x, y, z, yaw, pitch);
    }

    public boolean randomEnabled() {
        return spawnConfig.getBoolean("random.enabled", true);
    }

    public int randomSlot() {
        return spawnConfig.getInt("random.slot", 15);
    }

    public String randomDisplayName() {
        return spawnConfig.getString("random.display-name", "&aRandom Spawn");
    }

    public List<String> currentSpawnLore() {
        List<String> lore = spawnConfig.getStringList("gui.spawn.current-lore");

        if (!lore.isEmpty()) {
            return lore;
        }

        return List.of("&#bbbbbb%online%/%max%", "", "&aYou are currently here");
    }

    public List<String> availableSpawnLore() {
        List<String> lore = spawnConfig.getStringList("gui.spawn.available-lore");

        if (!lore.isEmpty()) {
            return lore;
        }

        return List.of("&#bbbbbb%online%/%max%", "", "&#ff88ffClick to return to Spawn");
    }

    public List<String> randomLore() {
        List<String> lore = spawnConfig.getStringList("gui.random.lore");

        if (!lore.isEmpty()) {
            return lore;
        }

        return List.of(
                "&#bbbbbbClick to teleport to a random &#ff88ffSpawn",
                "",
                "&#bbbbbbChooses the lowest-populated spawn"
        );
    }

    public List<String> applyLorePlaceholders(List<String> lore, SpawnPoint point, int online) {
        List<String> output = new ArrayList<>();

        for (String line : lore) {
            output.add(line
                    .replace("%id%", point.id())
                    .replace("%spawn%", point.displayName())
                    .replace("%world%", point.worldName())
                    .replace("%online%", String.valueOf(online))
                    .replace("%max%", String.valueOf(maxPlayersDisplay()))
            );
        }

        return output;
    }

    public String message(String path) {
        return TextColor.color(spawnConfig.getString("messages." + path, "&cMissing spawn message: " + path));
    }

    public boolean setSpawnPointEnabled(String id, boolean enabled) {
        SpawnPoint point = spawnPointById(id);

        if (point == null) {
            return false;
        }

        spawnConfig.set("worlds." + point.id() + ".enabled", enabled);
        save();
        load();
        return true;
    }

    public boolean addSpawnPoint(String id, String worldName, int slot, String displayName) {
        if (!isValidId(id)) {
            return false;
        }

        if (slot < 0 || slot >= size()) {
            return false;
        }

        String path = "worlds." + id.toLowerCase(Locale.ROOT);
        spawnConfig.set(path + ".enabled", true);
        spawnConfig.set(path + ".slot", slot);
        spawnConfig.set(path + ".display-name", displayName);
        spawnConfig.set(path + ".world", worldName);
        save();
        load();
        return true;
    }

    public boolean removeSpawnPoint(String id) {
        SpawnPoint point = spawnPointById(id);

        if (point == null) {
            return false;
        }

        spawnConfig.set("worlds." + point.id(), null);
        save();
        load();
        return true;
    }

    public List<String> spawnIds() {
        List<String> ids = new ArrayList<>();

        for (SpawnPoint point : spawnPoints()) {
            ids.add(point.id());
        }

        return ids;
    }

    public boolean voidEnabled() {
        return spawnConfig.getBoolean("void.enabled", true);
    }

    public double voidTriggerY() {
        return spawnConfig.getDouble("void.trigger-y", 0.0D);
    }

    public long voidFallProtectionSeconds() {
        return Math.max(1L, spawnConfig.getLong("void.fall-protection-seconds", 5L));
    }

    public boolean voidWorldAllowed(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }

        List<String> worlds = spawnConfig.getStringList("void.check-worlds");

        if (worlds.isEmpty()) {
            return isSpawnWorld(worldName);
        }

        for (String world : worlds) {
            if (world.equalsIgnoreCase(worldName)) {
                return true;
            }
        }

        return false;
    }

    public boolean firstJoinEnabled() {
        return spawnConfig.getBoolean("first-join.enabled", true);
    }

    public long firstJoinDelayTicks() {
        return Math.max(1L, spawnConfig.getLong("first-join.delay-ticks", 10L));
    }

    public boolean firstJoinSendMessage() {
        return spawnConfig.getBoolean("first-join.send-message", false);
    }

    public boolean loginRerouteEnabled() {
        return spawnConfig.getBoolean("login-reroute.enabled", true);
    }

    public long loginRerouteDelayTicks() {
        return Math.max(1L, spawnConfig.getLong("login-reroute.delay-ticks", 5L));
    }

    public boolean loginRerouteSendMessage() {
        return spawnConfig.getBoolean("login-reroute.send-message", false);
    }

    private boolean isValidId(String id) {
        if (id == null) {
            return false;
        }

        return id.matches("[A-Za-z0-9_-]{2,32}");
    }
}
