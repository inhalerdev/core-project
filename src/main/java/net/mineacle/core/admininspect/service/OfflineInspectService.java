package net.mineacle.core.admininspect.service;

import net.kyori.adventure.text.Component;
import net.mineacle.core.Core;
import net.mineacle.core.admininspect.service.AdminInspectService.InspectType;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.VanishRegistry;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class OfflineInspectService {

    private static final int PLAYER_INVENTORY_SIZE = 45;
    private static final int STORAGE_SIZE = 36;
    private static final int ARMOR_START = 36;
    private static final int OFFHAND_SLOT = 40;
    private static final int FIRST_BLOCKED_SLOT = 41;
    private static final int MAX_ENDER_CHEST_SIZE = 54;

    private static final Set<InventoryAction> BLOCKED_ACTIONS =
            Set.copyOf(
                    EnumSet.of(
                            InventoryAction.CLONE_STACK,
                            InventoryAction.COLLECT_TO_CURSOR,
                            InventoryAction.DROP_ALL_CURSOR,
                            InventoryAction.DROP_ALL_SLOT,
                            InventoryAction.DROP_ONE_CURSOR,
                            InventoryAction.DROP_ONE_SLOT,
                            InventoryAction.HOTBAR_SWAP,
                            InventoryAction.MOVE_TO_OTHER_INVENTORY,
                            InventoryAction.UNKNOWN
                    )
            );

    private final Core core;
    private final File dataDirectory;
    private final File indexFile;
    private final File settingsFile;
    private final Map<UUID, SnapshotMeta> index =
            new LinkedHashMap<>();
    private final Map<UUID, Session> sessions =
            new HashMap<>();
    private final Map<UUID, UUID> editorsByTarget =
            new HashMap<>();

    private YamlConfiguration settings;
    private YamlConfiguration indexConfig;

    public OfflineInspectService(Core core) {
        this.core = core;
        this.dataDirectory = new File(
                core.getDataFolder(),
                "offline-inspect"
        );
        this.indexFile = new File(
                dataDirectory,
                "index.yml"
        );
        this.settingsFile = new File(
                core.getDataFolder(),
                "admininspect.yml"
        );

        initializeStorage();
        reload();
    }

    public void reload() {
        settings = YamlConfiguration.loadConfiguration(
                settingsFile
        );
        loadIndex();
    }

    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            capture(player);
        }
    }

    public long applyDelayTicks() {
        return Math.clamp(
                settings.getLong(
                        "offline-editing.apply-delay-ticks",
                        5L
                ),
                1L,
                40L
        );
    }

    public void open(
            Player viewer,
            String input,
            InspectType type
    ) {
        if (viewer == null
                || !viewer.hasPermission(type.permission())) {
            fail(
                    viewer,
                    "messages.no-permission",
                    "&cYou do not have permission to inspect player inventories"
            );
            return;
        }

        SnapshotMeta meta = resolve(input);

        if (meta == null) {
            fail(
                    viewer,
                    "messages.offline-data-unavailable",
                    "&cOffline inventory data is not available for that player yet"
            );
            return;
        }

        Player online = Bukkit.getPlayer(meta.playerId());

        if (online != null && online.isOnline()) {
            fail(
                    viewer,
                    "messages.target-unavailable",
                    "&cThat player is unavailable"
            );
            return;
        }

        if (VanishRegistry.isVanished(meta.playerId())
                && !viewer.hasPermission(
                AdminInspectService.HIDDEN_PERMISSION
        )) {
            fail(
                    viewer,
                    "messages.target-unavailable",
                    "&cThat player is unavailable"
            );
            return;
        }

        if (viewer.getUniqueId().equals(meta.playerId())
                && !viewer.hasPermission(
                type.selfPermission()
        )) {
            fail(
                    viewer,
                    type == InspectType.INVENTORY
                            ? "messages.self-inventory"
                            : "messages.self-ender-chest",
                    type == InspectType.INVENTORY
                            ? "&cYou cannot inspect your own inventory"
                            : "&cYou cannot inspect your own ender chest"
            );
            return;
        }

        if (meta.protectedPlayer()
                && !viewer.hasPermission(
                AdminInspectService
                        .PROTECTED_BYPASS_PERMISSION
        )) {
            fail(
                    viewer,
                    "messages.protected",
                    "&cYou cannot inspect that player"
            );
            return;
        }

        Snapshot snapshot = loadSnapshot(meta.playerId());

        if (snapshot == null) {
            fail(
                    viewer,
                    "messages.offline-data-unavailable",
                    "&cOffline inventory data is not available for that player yet"
            );
            return;
        }

        boolean editable = canModify(viewer, type);

        if (editable) {
            UUID existing = editorsByTarget.get(meta.playerId());

            if (existing != null
                    && !existing.equals(viewer.getUniqueId())) {
                fail(
                        viewer,
                        "messages.edit-locked",
                        "&cThat offline inventory is already being edited"
                );
                return;
            }
        }

        closeViewerSession(viewer);

        OfflineHolder holder = new OfflineHolder();
        Inventory inventory = createInventory(
                holder,
                meta,
                snapshot,
                type
        );
        holder.inventory = inventory;

        Session session = new Session(
                UUID.randomUUID().toString()
                        .substring(0, 8)
                        .toUpperCase(Locale.ROOT),
                viewer.getUniqueId(),
                meta,
                type,
                inventory,
                editable,
                System.currentTimeMillis()
        );
        sessions.put(viewer.getUniqueId(), session);

        if (editable) {
            editorsByTarget.put(
                    meta.playerId(),
                    viewer.getUniqueId()
            );
        }

        viewer.openInventory(inventory);
        viewer.sendMessage(
                TextColor.color(
                        message(
                                editable
                                        ? "messages.open-offline-editable"
                                        : "messages.open-offline-read-only",
                                editable
                                        ? "&#bbbbbbInspecting &#B078FF%player% &#bbbbbb— &cOffline Editing"
                                        : "&#bbbbbbInspecting &#B078FF%player% &#bbbbbb— &#D0AFFFOffline Read Only"
                        ).replace(
                                "%player%",
                                meta.publicName()
                        )
                )
        );
        SoundService.guiSelect(viewer, core);
        auditOpen(viewer, session);
    }

    public List<String> completions(
            Player viewer,
            InspectType type,
            String input
    ) {
        if (viewer == null
                || !viewer.hasPermission(type.permission())) {
            return List.of();
        }

        String partial = normalize(input);
        Map<String, String> values = new LinkedHashMap<>();

        for (SnapshotMeta meta : index.values()) {
            if (Bukkit.getPlayer(meta.playerId()) != null) {
                continue;
            }

            if (VanishRegistry.isVanished(meta.playerId())
                    && !viewer.hasPermission(
                    AdminInspectService.HIDDEN_PERMISSION
            )) {
                continue;
            }

            String publicName = meta.publicName();
            String normalized = normalize(publicName);

            if (!partial.isEmpty()
                    && !normalized.startsWith(partial)) {
                continue;
            }

            values.putIfAbsent(normalized, publicName);
        }

        List<String> result = new ArrayList<>(values.values());
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(result);
    }

    public Access access(
            Player viewer,
            Inventory inventory
    ) {
        Session session = session(viewer, inventory);

        if (session == null) {
            return Access.NONE;
        }

        if (!viewer.hasPermission(session.type().permission())) {
            return Access.UNAUTHORIZED;
        }

        if (!session.editable()) {
            return Access.READ_ONLY;
        }

        if (!viewer.hasPermission(
                session.type().modifyPermission()
        )) {
            session.editable = false;
            releaseEditor(session);
            return Access.READ_ONLY;
        }

        return Access.EDITABLE;
    }

    public boolean blockedAction(InventoryAction action) {
        return action != null && BLOCKED_ACTIONS.contains(action);
    }

    public boolean blockedTopSlot(
            Session session,
            int rawSlot
    ) {
        if (session == null || rawSlot < 0) {
            return true;
        }

        if (session.type() == InspectType.ENDER_CHEST) {
            return rawSlot >= session.inventory().getSize();
        }

        return rawSlot >= FIRST_BLOCKED_SLOT;
    }

    public void recordModification(Player viewer) {
        Session session = sessions.get(viewer.getUniqueId());

        if (session != null) {
            session.dirty = true;
            session.modificationEvents++;
        }
    }

    public void readOnlyFeedback(Player viewer) {
        if (viewer == null) {
            return;
        }

        viewer.sendActionBar(
                GuiText.component(
                        message(
                                "messages.read-only",
                                "&cRead-only inspection"
                        )
                )
        );
        SoundService.guiError(viewer, core);
    }

    public void blockedFeedback(Player viewer) {
        if (viewer == null) {
            return;
        }

        viewer.sendActionBar(
                GuiText.component(
                        message(
                                "messages.blocked-edit-action",
                                "&cThat action is blocked during inspection"
                        )
                )
        );
        SoundService.guiError(viewer, core);
    }

    public void close(
            Player viewer,
            Inventory inventory
    ) {
        Session session = session(viewer, inventory);

        if (session == null) {
            return;
        }

        sessions.remove(viewer.getUniqueId());
        releaseEditor(session);

        if (session.editable() && session.dirty()) {
            saveEditedSession(session);
        }

        auditClose(session, "inventory-close");
    }

    public void viewerQuit(Player viewer) {
        if (viewer == null) {
            return;
        }

        Session session = sessions.remove(viewer.getUniqueId());

        if (session == null) {
            return;
        }

        releaseEditor(session);

        if (session.editable() && session.dirty()) {
            saveEditedSession(session);
        }

        auditClose(session, "viewer-disconnected");
    }

    public void targetJoining(Player target) {
        if (target == null) {
            return;
        }

        UUID targetId = target.getUniqueId();

        for (Session session : List.copyOf(sessions.values())) {
            if (!session.meta().playerId().equals(targetId)) {
                continue;
            }

            Player viewer = Bukkit.getPlayer(session.viewerId());

            if (viewer != null && viewer.isOnline()) {
                viewer.closeInventory();
            } else {
                sessions.remove(session.viewerId());
                releaseEditor(session);

                if (session.editable() && session.dirty()) {
                    saveEditedSession(session);
                }
            }
        }
    }

    public void applyPending(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        Snapshot snapshot = loadSnapshot(player.getUniqueId());

        if (snapshot == null || !snapshot.pending()) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        inventory.setStorageContents(
                copyArray(snapshot.storage(), STORAGE_SIZE)
        );
        inventory.setArmorContents(
                copyArray(snapshot.armor(), 4)
        );
        inventory.setItemInOffHand(
                cloneItem(snapshot.offhand())
        );

        Inventory enderChest = player.getEnderChest();
        ItemStack[] ender = copyArray(
                snapshot.enderChest(),
                enderChest.getSize()
        );
        enderChest.setContents(ender);

        Snapshot applied = snapshot.withPending(false);
        saveSnapshot(applied);
        updateIndex(player, applied.updatedAt());
        player.updateInventory();
        player.saveData();

        core.getLogger().info(
                "[AdminInspect] Applied pending offline inventory changes to "
                        + player.getName()
                        + " ("
                        + player.getUniqueId()
                        + ")"
        );
    }

    public void capture(Player player) {
        if (player == null) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        Snapshot snapshot = new Snapshot(
                player.getUniqueId(),
                player.getName(),
                DisplayNames.commandDisplayName(player),
                player.hasPermission(
                        AdminInspectService.PROTECTED_PERMISSION
                ),
                false,
                cloneArray(inventory.getStorageContents()),
                cloneArray(inventory.getArmorContents()),
                cloneItem(inventory.getItemInOffHand()),
                cloneArray(player.getEnderChest().getContents()),
                System.currentTimeMillis()
        );

        saveSnapshot(snapshot);
        updateIndex(player, snapshot.updatedAt());
    }

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            capture(player);
        }

        for (Session session : List.copyOf(sessions.values())) {
            Player viewer = Bukkit.getPlayer(session.viewerId());

            if (viewer != null && viewer.isOnline()) {
                viewer.closeInventory();
            } else if (session.editable() && session.dirty()) {
                saveEditedSession(session);
            }
        }

        sessions.clear();
        editorsByTarget.clear();
        saveIndex();
    }

    public Session session(
            Player viewer,
            Inventory inventory
    ) {
        if (viewer == null || inventory == null) {
            return null;
        }

        Session session = sessions.get(viewer.getUniqueId());

        return session != null
                && session.inventory() == inventory
                ? session
                : null;
    }

    private boolean canModify(
            Player viewer,
            InspectType type
    ) {
        if (!settings.getBoolean(
                "offline-editing.enabled",
                true
        ) || !viewer.hasPermission(
                type.modifyPermission()
        )) {
            return false;
        }

        return settings.getBoolean(
                "offline-editing.allow-creative",
                false
        ) || viewer.getGameMode() != GameMode.CREATIVE;
    }

    private Inventory createInventory(
            OfflineHolder holder,
            SnapshotMeta meta,
            Snapshot snapshot,
            InspectType type
    ) {
        if (type == InspectType.ENDER_CHEST) {
            int configured = snapshot.enderChest().length;
            int size = Math.clamp(
                    ((Math.max(1, configured) + 8) / 9) * 9,
                    9,
                    MAX_ENDER_CHEST_SIZE
            );
            Inventory inventory = Bukkit.createInventory(
                    holder,
                    size,
                    Component.text(
                            "Ender Chest — "
                                    + meta.publicName()
                                    + " (Offline)"
                    )
            );
            inventory.setContents(
                    copyArray(snapshot.enderChest(), size)
            );
            return inventory;
        }

        Inventory inventory = Bukkit.createInventory(
                holder,
                PLAYER_INVENTORY_SIZE,
                Component.text(
                        "Inventory — "
                                + meta.publicName()
                                + " (Offline)"
                )
        );
        ItemStack[] storage = copyArray(
                snapshot.storage(),
                STORAGE_SIZE
        );

        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            inventory.setItem(slot, storage[slot]);
        }

        ItemStack[] armor = copyArray(snapshot.armor(), 4);
        for (int offset = 0; offset < 4; offset++) {
            inventory.setItem(
                    ARMOR_START + offset,
                    armor[offset]
            );
        }
        inventory.setItem(
                OFFHAND_SLOT,
                cloneItem(snapshot.offhand())
        );

        for (int slot = FIRST_BLOCKED_SLOT;
             slot < PLAYER_INVENTORY_SIZE;
             slot++) {
            inventory.setItem(slot, spacer());
        }
        return inventory;
    }

    private void saveEditedSession(Session session) {
        Snapshot current = loadSnapshot(
                session.meta().playerId()
        );

        if (current == null) {
            return;
        }

        Snapshot updated;

        if (session.type() == InspectType.ENDER_CHEST) {
            updated = current.withEnderChest(
                    cloneArray(
                            session.inventory().getContents()
                    )
            );
        } else {
            ItemStack[] storage = new ItemStack[STORAGE_SIZE];
            for (int slot = 0; slot < STORAGE_SIZE; slot++) {
                storage[slot] = cloneItem(
                        session.inventory().getItem(slot)
                );
            }

            ItemStack[] armor = new ItemStack[4];
            for (int offset = 0; offset < 4; offset++) {
                armor[offset] = cloneItem(
                        session.inventory().getItem(
                                ARMOR_START + offset
                        )
                );
            }

            updated = current.withInventory(
                    storage,
                    armor,
                    cloneItem(
                            session.inventory().getItem(
                                    OFFHAND_SLOT
                            )
                    )
            );
        }

        updated = updated.withPending(true)
                .withUpdatedAt(System.currentTimeMillis());
        saveSnapshot(updated);
        updateIndex(
                session.meta().playerId(),
                updated
        );

        core.getLogger().info(
                "[AdminInspect] session="
                        + session.sessionId()
                        + " saved OFFLINE edits for "
                        + session.meta().username()
                        + " ("
                        + session.meta().playerId()
                        + ") | type="
                        + session.type().name()
                        + " | modification-events="
                        + session.modificationEvents()
        );
    }

    private SnapshotMeta resolve(String input) {
        String normalized = normalize(input);
        SnapshotMeta match = null;

        for (SnapshotMeta candidate : index.values()) {
            if (!normalize(candidate.publicName()).equals(normalized)
                    && !normalize(candidate.username()).equals(normalized)) {
                continue;
            }

            if (match != null
                    && !match.playerId().equals(candidate.playerId())) {
                return null;
            }

            match = candidate;
        }

        return match;
    }

    private void loadIndex() {
        index.clear();
        indexConfig = YamlConfiguration.loadConfiguration(indexFile);
        ConfigurationSection players =
                indexConfig.getConfigurationSection("players");

        if (players == null) {
            return;
        }

        for (String rawId : players.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(rawId);
                String path = "players." + rawId + ".";
                String username = indexConfig.getString(
                        path + "username",
                        rawId
                );
                String publicName = indexConfig.getString(
                        path + "public-name",
                        username
                );
                boolean protectedPlayer = indexConfig.getBoolean(
                        path + "protected",
                        false
                );
                long updatedAt = indexConfig.getLong(
                        path + "updated-at",
                        0L
                );
                index.put(
                        playerId,
                        new SnapshotMeta(
                                playerId,
                                username,
                                publicName,
                                protectedPlayer,
                                updatedAt
                        )
                );
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed index keys safely.
            }
        }
    }

    private Snapshot loadSnapshot(UUID playerId) {
        File file = snapshotFile(playerId);

        if (!file.isFile()) {
            return null;
        }

        YamlConfiguration yaml =
                YamlConfiguration.loadConfiguration(file);
        String username = yaml.getString(
                "username",
                playerId.toString()
        );
        String publicName = yaml.getString(
                "public-name",
                username
        );

        return new Snapshot(
                playerId,
                username,
                publicName,
                yaml.getBoolean("protected", false),
                yaml.getBoolean("pending", false),
                readItems(yaml, "inventory.storage", STORAGE_SIZE),
                readItems(yaml, "inventory.armor", 4),
                yaml.getItemStack("inventory.offhand"),
                readItems(
                        yaml,
                        "ender-chest",
                        Math.clamp(
                                yaml.getInt("ender-chest-size", 27),
                                9,
                                MAX_ENDER_CHEST_SIZE
                        )
                ),
                yaml.getLong("updated-at", 0L)
        );
    }

    private void saveSnapshot(Snapshot snapshot) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("username", snapshot.username());
        yaml.set("public-name", snapshot.publicName());
        yaml.set("protected", snapshot.protectedPlayer());
        yaml.set("pending", snapshot.pending());
        yaml.set("updated-at", snapshot.updatedAt());
        writeItems(yaml, "inventory.storage", snapshot.storage());
        writeItems(yaml, "inventory.armor", snapshot.armor());
        yaml.set("inventory.offhand", snapshot.offhand());
        yaml.set("ender-chest-size", snapshot.enderChest().length);
        writeItems(yaml, "ender-chest", snapshot.enderChest());

        try {
            saveAtomic(
                    yaml,
                    snapshotFile(snapshot.playerId())
            );
        } catch (IOException exception) {
            core.getLogger().warning(
                    "[AdminInspect] Could not save offline snapshot for "
                            + snapshot.playerId()
                            + ": "
                            + exception.getMessage()
            );
        }
    }

    private void updateIndex(Player player, long updatedAt) {
        SnapshotMeta meta = new SnapshotMeta(
                player.getUniqueId(),
                player.getName(),
                DisplayNames.commandDisplayName(player),
                player.hasPermission(
                        AdminInspectService.PROTECTED_PERMISSION
                ),
                updatedAt
        );
        index.put(meta.playerId(), meta);
        writeIndexMeta(meta);
        saveIndex();
    }

    private void updateIndex(UUID playerId, Snapshot snapshot) {
        SnapshotMeta meta = new SnapshotMeta(
                playerId,
                snapshot.username(),
                snapshot.publicName(),
                snapshot.protectedPlayer(),
                snapshot.updatedAt()
        );
        index.put(playerId, meta);
        writeIndexMeta(meta);
        saveIndex();
    }

    private void writeIndexMeta(SnapshotMeta meta) {
        String path = "players." + meta.playerId() + ".";
        indexConfig.set(path + "username", meta.username());
        indexConfig.set(path + "public-name", meta.publicName());
        indexConfig.set(path + "protected", meta.protectedPlayer());
        indexConfig.set(path + "updated-at", meta.updatedAt());
    }

    private void saveIndex() {
        try {
            saveAtomic(indexConfig, indexFile);
        } catch (IOException exception) {
            core.getLogger().warning(
                    "[AdminInspect] Could not save offline inspect index: "
                            + exception.getMessage()
            );
        }
    }

    private void saveAtomic(
            YamlConfiguration yaml,
            File target
    ) throws IOException {
        File parent = target.getParentFile();

        if (parent == null) {
            throw new IOException(
                    "Missing parent directory for "
                            + target.getName()
            );
        }

        File temporary = new File(
                parent,
                target.getName() + ".tmp"
        );
        yaml.save(temporary);

        try {
            Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private void writeItems(
            YamlConfiguration yaml,
            String path,
            ItemStack[] items
    ) {
        yaml.set(path, null);

        for (int slot = 0; slot < items.length; slot++) {
            ItemStack item = items[slot];

            if (item != null && !item.getType().isAir()) {
                yaml.set(path + "." + slot, item);
            }
        }
    }

    private ItemStack[] readItems(
            YamlConfiguration yaml,
            String path,
            int size
    ) {
        ItemStack[] items = new ItemStack[size];

        for (int slot = 0; slot < size; slot++) {
            items[slot] = yaml.getItemStack(
                    path + "." + slot
            );
        }
        return items;
    }

    private void releaseEditor(Session session) {
        if (session == null || !session.editable()) {
            return;
        }

        editorsByTarget.remove(
                session.meta().playerId(),
                session.viewerId()
        );
    }

    private void closeViewerSession(
            Player viewer
    ) {
        Session old = sessions.remove(viewer.getUniqueId());

        if (old == null) {
            return;
        }

        releaseEditor(old);

        if (old.editable() && old.dirty()) {
            saveEditedSession(old);
        }

        auditClose(old, "replaced-by-new-inspection");
    }

    private void auditOpen(Player viewer, Session session) {
        if (!settings.getBoolean("audit.enabled", true)) {
            return;
        }

        core.getLogger().info(
                "[AdminInspect] session="
                        + session.sessionId()
                        + " "
                        + viewer.getName()
                        + " ("
                        + viewer.getUniqueId()
                        + ") opened OFFLINE "
                        + session.type().name()
                        + " of "
                        + session.meta().username()
                        + " ("
                        + session.meta().playerId()
                        + ") | mode="
                        + (session.editable()
                        ? "EDITABLE"
                        : "READ_ONLY")
        );
    }

    private void auditClose(
            Session session,
            String reason
    ) {
        if (!settings.getBoolean("audit.enabled", true)) {
            return;
        }

        core.getLogger().info(
                "[AdminInspect] session="
                        + session.sessionId()
                        + " closed OFFLINE "
                        + session.type().name()
                        + " of "
                        + session.meta().username()
                        + " | reason="
                        + reason
                        + " | modification-events="
                        + session.modificationEvents()
                        + " | duration-ms="
                        + Math.max(
                        0L,
                        System.currentTimeMillis()
                                - session.openedAt()
                )
        );
    }

    private void fail(
            Player viewer,
            String path,
            String fallback
    ) {
        if (viewer == null) {
            return;
        }

        viewer.sendMessage(
                TextColor.color(
                        message(path, fallback)
                )
        );
        SoundService.guiError(viewer, core);
    }

    private String message(String path, String fallback) {
        return settings.getString(path, fallback);
    }

    private String normalize(String input) {
        if (input == null) {
            return "";
        }

        return TextColor.strip(input)
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private ItemStack spacer() {
        ItemStack item = new ItemStack(
                Material.GRAY_STAINED_GLASS_PANE
        );
        var meta = item.getItemMeta();
        if (meta != null) {
            GuiText.apply(
                    meta,
                    "&#bbbbbbOffline Inventory",
                    List.of(
                            "&#D0AFFFSlots 36-39 are armor",
                            "&#D0AFFFSlot 40 is offhand"
                    )
            );
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack[] cloneArray(ItemStack[] source) {
        if (source == null) {
            return new ItemStack[0];
        }

        ItemStack[] copy = new ItemStack[source.length];
        for (int index = 0; index < source.length; index++) {
            copy[index] = cloneItem(source[index]);
        }
        return copy;
    }

    private ItemStack[] copyArray(
            ItemStack[] source,
            int size
    ) {
        ItemStack[] copy = new ItemStack[size];

        if (source == null) {
            return copy;
        }

        for (int index = 0;
             index < Math.min(source.length, size);
             index++) {
            copy[index] = cloneItem(source[index]);
        }
        return copy;
    }

    private ItemStack cloneItem(ItemStack item) {
        return item == null || item.getType().isAir()
                ? null
                : item.clone();
    }

    private File snapshotFile(UUID playerId) {
        return new File(
                dataDirectory,
                playerId + ".yml"
        );
    }

    private void initializeStorage() {
        if (!dataDirectory.exists()
                && !dataDirectory.mkdirs()
                && !dataDirectory.isDirectory()) {
            throw new IllegalStateException(
                    "Could not create offline inspect data directory"
            );
        }

        if (!indexFile.exists()) {
            try {
                if (!indexFile.createNewFile()) {
                    throw new IOException(
                            "createNewFile returned false"
                    );
                }
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Could not initialize offline inspect index",
                        exception
                );
            }
        }
    }

    public enum Access {
        NONE,
        READ_ONLY,
        EDITABLE,
        UNAUTHORIZED
    }

    public static final class OfflineHolder
            implements InventoryHolder {

        private Inventory inventory;

        private OfflineHolder() {
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    public static final class Session {

        private final String sessionId;
        private final UUID viewerId;
        private final SnapshotMeta meta;
        private final InspectType type;
        private final Inventory inventory;
        private final long openedAt;
        private boolean editable;
        private boolean dirty;
        private int modificationEvents;

        private Session(
                String sessionId,
                UUID viewerId,
                SnapshotMeta meta,
                InspectType type,
                Inventory inventory,
                boolean editable,
                long openedAt
        ) {
            this.sessionId = sessionId;
            this.viewerId = viewerId;
            this.meta = meta;
            this.type = type;
            this.inventory = inventory;
            this.editable = editable;
            this.openedAt = openedAt;
        }

        public String sessionId() {
            return sessionId;
        }

        public UUID viewerId() {
            return viewerId;
        }

        private SnapshotMeta meta() {
            return meta;
        }

        public InspectType type() {
            return type;
        }

        public Inventory inventory() {
            return inventory;
        }

        public long openedAt() {
            return openedAt;
        }

        public boolean editable() {
            return editable;
        }

        public boolean dirty() {
            return dirty;
        }

        public int modificationEvents() {
            return modificationEvents;
        }
    }

    private record SnapshotMeta(
            UUID playerId,
            String username,
            String publicName,
            boolean protectedPlayer,
            long updatedAt
    ) {
    }

    private record Snapshot(
            UUID playerId,
            String username,
            String publicName,
            boolean protectedPlayer,
            boolean pending,
            ItemStack[] storage,
            ItemStack[] armor,
            ItemStack offhand,
            ItemStack[] enderChest,
            long updatedAt
    ) {
        private Snapshot withPending(boolean value) {
            return new Snapshot(
                    playerId,
                    username,
                    publicName,
                    protectedPlayer,
                    value,
                    storage,
                    armor,
                    offhand,
                    enderChest,
                    updatedAt
            );
        }

        private Snapshot withUpdatedAt(long value) {
            return new Snapshot(
                    playerId,
                    username,
                    publicName,
                    protectedPlayer,
                    pending,
                    storage,
                    armor,
                    offhand,
                    enderChest,
                    value
            );
        }

        private Snapshot withEnderChest(ItemStack[] value) {
            return new Snapshot(
                    playerId,
                    username,
                    publicName,
                    protectedPlayer,
                    pending,
                    storage,
                    armor,
                    offhand,
                    value,
                    updatedAt
            );
        }

        private Snapshot withInventory(
                ItemStack[] storageValue,
                ItemStack[] armorValue,
                ItemStack offhandValue
        ) {
            return new Snapshot(
                    playerId,
                    username,
                    publicName,
                    protectedPlayer,
                    pending,
                    storageValue,
                    armorValue,
                    offhandValue,
                    enderChest,
                    updatedAt
            );
        }
    }
}
