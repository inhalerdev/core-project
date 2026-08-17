package net.mineacle.core.admininspect.service;

import net.kyori.adventure.text.Component;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
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
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Safe offline inventory inspection and deferred writeback.
 *
 * <p>Gameplay playerdata remains authoritative. Mineacle only edits snapshots
 * captured from real Player objects. A durable online marker invalidates a
 * snapshot after an unclean JVM/server stop, preventing stale snapshots from
 * overwriting newer Minecraft playerdata.</p>
 */
public final class OfflineInspectService {

    private static final int PLAYER_INVENTORY_SIZE = 45;
    private static final int STORAGE_SIZE = 36;
    private static final int ARMOR_START = 36;
    private static final int OFFHAND_SLOT = 40;
    private static final int FIRST_BLOCKED_SLOT = 41;
    private static final int MAX_ENDER_CHEST_SIZE = 54;
    private static final int SNAPSHOT_SAFETY_VERSION = 2;

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
    private final File onlineDirectory;
    private final File staleDirectory;
    private final File serverOnlineMarker;
    private final File indexFile;
    private final File settingsFile;
    private final NamespacedKey applyTransactionKey;

    private final Map<UUID, SnapshotMeta> index =
            new LinkedHashMap<>();
    private final Map<UUID, Session> sessions =
            new HashMap<>();
    private final Map<UUID, UUID> editorsByTarget =
            new HashMap<>();
    private final Map<UUID, Session> recoveryByTarget =
            new HashMap<>();
    private final Set<UUID> staleTargets =
            new HashSet<>();
    private final Set<UUID> protectionLoads =
            ConcurrentHashMap.newKeySet();

    private YamlConfiguration settings;
    private YamlConfiguration indexConfig;
    private BukkitTask validationTask;
    private BukkitTask indexSaveTask;
    private final boolean uncleanServerStartup;
    private boolean offlineSafetyAvailable = true;
    private boolean runtimeSafetyFailure;

    public OfflineInspectService(Core core) {
        this.core = core;
        this.dataDirectory = new File(
                core.getDataFolder(),
                "offline-inspect"
        );
        this.onlineDirectory = new File(
                dataDirectory,
                "online"
        );
        this.staleDirectory = new File(
                dataDirectory,
                "stale"
        );
        this.serverOnlineMarker = new File(
                dataDirectory,
                "server-online.lock"
        );
        this.indexFile = new File(
                dataDirectory,
                "index.yml"
        );
        this.settingsFile = new File(
                core.getDataFolder(),
                "admininspect.yml"
        );
        this.applyTransactionKey = new NamespacedKey(
                core,
                "admininspect_apply_transaction"
        );

        initializeStorage();
        uncleanServerStartup = serverOnlineMarker.isFile();
        reload();
    }

    public void reload() {
        settings = YamlConfiguration.loadConfiguration(
                settingsFile
        );
        loadIndex();
        reconcileIndexFromSnapshots();
    }

    public void start() {
        if (uncleanServerStartup) {
            for (UUID playerId : index.keySet()) {
                staleTargets.add(playerId);
                writeIndexStale(playerId, true);
            }
            saveIndexNow();
            core.getLogger().severe(
                    "[AdminInspect] Previous server/plugin session was unclean — existing offline snapshots are fail-closed until refreshed"
            );
        }

        try {
            writeOnlineMarker(serverOnlineMarker);
        } catch (IOException exception) {
            offlineSafetyAvailable = false;
            runtimeSafetyFailure = true;
            core.getLogger().log(
                    Level.SEVERE,
                    "[AdminInspect] Could not establish durable server safety epoch — offline inspection is disabled for this runtime",
                    exception
            );
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            playerJoined(player);

            Snapshot snapshot = loadSnapshot(
                    player.getUniqueId()
            );

            if (snapshot != null && snapshot.pending()) {
                schedulePendingApply(player);
            }
        }

        if (validationTask != null) {
            validationTask.cancel();
        }

        long validationTicks = Math.clamp(
                settings.getLong(
                        "session-validation-ticks",
                        20L
                ),
                5L,
                200L
        );

        validationTask = core.getServer()
                .getScheduler()
                .runTaskTimer(
                        core,
                        this::validateSessions,
                        validationTicks,
                        validationTicks
                );
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

    /**
     * Marks this player's current online session durably. If the marker already
     * existed, the previous server session did not reach a clean capture and
     * the old offline snapshot is treated as stale.
     */
    public void playerJoined(Player player) {
        if (player == null) {
            return;
        }

        UUID playerId = player.getUniqueId();
        File marker = onlineMarker(playerId);
        boolean uncleanPreviousSession = marker.isFile();
        Snapshot snapshot = loadSnapshot(playerId);

        UUID pendingTransaction = snapshot == null
                ? null
                : snapshot.pendingTransactionId();
        UUID appliedTransaction = readApplyTransaction(player);

        boolean pendingAlreadyCommitted = snapshot != null
                && snapshot.pending()
                && pendingTransaction != null
                && pendingTransaction.equals(appliedTransaction);
        boolean legacyUnsafe = snapshot != null
                && snapshot.safetyVersion() < SNAPSHOT_SAFETY_VERSION;
        boolean previouslyStale = staleTargets.contains(playerId);

        if ((previouslyStale
                || uncleanPreviousSession
                || legacyUnsafe)
                && !pendingAlreadyCommitted) {
            markStaleTarget(playerId);
            core.getLogger().warning(
                    "[AdminInspect] Offline snapshot requires a fresh trusted capture for "
                            + player.getName()
                            + " ("
                            + playerId
                            + ") | unclean-server="
                            + uncleanServerStartup
                            + " | unclean-player-session="
                            + uncleanPreviousSession
                            + " | legacy-snapshot="
                            + legacyUnsafe
            );
        } else {
            clearStaleState(playerId);
        }

        try {
            writeOnlineMarker(marker);
        } catch (IOException exception) {
            markStaleTarget(playerId);
            runtimeSafetyFailure = true;
            core.getLogger().log(
                    Level.SEVERE,
                    "[AdminInspect] Could not create online safety marker for "
                            + player.getName()
                            + " ("
                            + playerId
                            + ")",
                    exception
            );
        }
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

        if (isSnapshotStale(meta.playerId())) {
            fail(
                    viewer,
                    "messages.offline-stale",
                    "&cOffline inventory data is stale — that player must reconnect before it can be inspected"
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
                && !viewer.hasPermission(type.selfPermission())) {
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

        if (!viewer.hasPermission(
                AdminInspectService.PROTECTED_BYPASS_PERMISSION
        )) {
            ProtectionState protection = protectionState(meta);

            if (protection == ProtectionState.PROTECTED) {
                fail(
                        viewer,
                        "messages.protected",
                        "&cYou cannot inspect that player"
                );
                return;
            }

            if (protection == ProtectionState.UNKNOWN) {
                fail(
                        viewer,
                        "messages.protection-refresh",
                        "&cPlayer protection is being verified — try again"
                );
                return;
            }
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

        if (snapshot.pending()) {
            fail(
                    viewer,
                    "messages.pending-target-change",
                    "&cThat player already has offline inventory changes waiting to apply"
            );
            return;
        }

        boolean editable = canModify(viewer, type);

        if (!closeViewerSession(viewer)) {
            fail(
                    viewer,
                    "messages.recovery-pending",
                    "&cYour previous offline inspection is still being recovered"
            );
            return;
        }

        if (editable) {
            if (recoveryByTarget.containsKey(meta.playerId())) {
                fail(
                        viewer,
                        "messages.edit-locked",
                        "&cThat offline inventory has unsaved changes being recovered"
                );
                return;
            }

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

        OfflineHolder holder = new OfflineHolder();
        Inventory inventory = createInventory(
                holder,
                meta,
                snapshot,
                type
        );
        holder.inventory = inventory;

        InventoryView view = viewer.openInventory(inventory);

        if (view == null
                || view.getTopInventory() != inventory) {
            fail(
                    viewer,
                    "messages.open-failed",
                    "&cCould not open that inventory"
            );
            return;
        }

        Session session = new Session(
                newSessionId(),
                viewer.getUniqueId(),
                meta,
                type,
                inventory,
                cloneArray(inventory.getContents()),
                snapshot.updatedAt(),
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
        boolean bypassProtected = viewer.hasPermission(
                AdminInspectService.PROTECTED_BYPASS_PERMISSION
        );
        Map<String, String> values = new LinkedHashMap<>();

        for (SnapshotMeta meta : index.values()) {
            if (Bukkit.getPlayer(meta.playerId()) != null
                    || isSnapshotStale(meta.playerId())) {
                continue;
            }

            if (VanishRegistry.isVanished(meta.playerId())
                    && !viewer.hasPermission(
                    AdminInspectService.HIDDEN_PERMISSION
            )) {
                continue;
            }

            /*
             * Completion never fans out thousands of LuckPerms storage loads.
             * Historical protection is enough to hide protected snapshots from
             * normal inspectors; the exact target is revalidated fail-closed
             * against LuckPerms when the command is actually executed.
             */
            if (!bypassProtected && meta.protectedPlayer()) {
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

        if (accessDenied(viewer, session)) {
            scheduleAccessClose(viewer, session);
            return Access.UNAUTHORIZED;
        }

        if (!session.editable()) {
            return Access.READ_ONLY;
        }

        if (!canModify(viewer, session.type())) {
            downgradeToReadOnly(viewer, session);
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
        if (viewer == null) {
            return;
        }

        Session session = sessions.get(viewer.getUniqueId());

        if (session != null) {
            session.dirty = true;
            session.modificationEvents++;
        }
    }

    public boolean targetCursorUnowned(Player viewer) {
        if (viewer == null) {
            return true;
        }

        Session session = sessions.get(viewer.getUniqueId());
        return session == null || !session.cursorOwned;
    }

    public void markTargetCursorOwned(Player viewer) {
        if (viewer == null) {
            return;
        }

        Session session = sessions.get(viewer.getUniqueId());
        if (session != null && session.editable()) {
            session.cursorOwned = true;
        }
    }

    public void reconcileTargetCursorOwnership(Player viewer) {
        if (viewer == null) {
            return;
        }

        Session session = sessions.get(viewer.getUniqueId());
        if (session == null || !session.cursorOwned) {
            return;
        }

        if (!hasItem(viewer.getItemOnCursor())) {
            session.cursorOwned = false;
        }
    }

    /**
     * Returns a target-owned cursor to the detached target inventory without
     * partial mutation. If the full stack does not fit, the inventory is left
     * completely unchanged and false is returned.
     */
    public boolean resolveTargetCursor(
            Player viewer,
            Session session
    ) {
        if (viewer == null || session == null || !session.cursorOwned) {
            return true;
        }

        ItemStack cursor = viewer.getItemOnCursor();

        if (!hasItem(cursor)) {
            session.cursorOwned = false;
            return true;
        }

        ItemStack[] simulated = cloneArray(
                session.inventory().getContents()
        );
        ItemStack remaining = cursor.clone();

        mergeIntoExistingStacks(
                session,
                simulated,
                remaining
        );
        placeIntoEmptySlots(
                session,
                simulated,
                remaining
        );

        if (remaining.getAmount() > 0) {
            return false;
        }

        session.inventory().setContents(simulated);
        viewer.setItemOnCursor(new ItemStack(Material.AIR));
        session.cursorOwned = false;
        session.dirty = true;
        session.modificationEvents++;
        return true;
    }

    public void abortUnresolvedCursor(
            Player viewer,
            Session session,
            String reason
    ) {
        if (session == null) {
            return;
        }

        clearTargetOwnedCursor(viewer, session);
        discardUnsavedSession(session, reason);
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

        if (session.cursorOwned
                && !resolveTargetCursor(viewer, session)) {
            return;
        }

        finishOrRecover(session, "inventory-close");
    }

    /**
     * Finalizes an inspector disconnect. An unresolved target-owned cursor is
     * removed from the inspector and the entire unsaved synthetic session is
     * discarded. The authoritative on-disk target snapshot is therefore left
     * untouched and still contains the original item.
     */
    public void viewerQuit(Player viewer) {
        if (viewer == null) {
            return;
        }

        Session session = sessions.get(viewer.getUniqueId());

        if (session == null) {
            return;
        }

        if (session.cursorOwned
                && !resolveTargetCursor(viewer, session)) {
            clearTargetOwnedCursor(viewer, session);
            discardUnsavedSession(
                    session,
                    "viewer-disconnected-cursor-unresolved"
            );
            return;
        }

        finishOrRecover(session, "viewer-disconnected");
    }

    /**
     * Closes any offline editing session targeting a player who is now joining.
     * Dirty edits are persisted before the delayed pending-apply phase. Unsafe
     * cursor state is discarded instead of crossing persistence domains.
     */
    public void targetJoining(Player target) {
        if (target == null) {
            return;
        }

        UUID targetId = target.getUniqueId();
        Session recovery = recoveryByTarget.get(targetId);

        if (recovery != null) {
            if (persistDirtySession(recovery, true)) {
                recoveryByTarget.remove(targetId, recovery);
                releaseEditor(recovery);
                auditClose(recovery, "recovered-before-target-join");
            } else {
                discardUnsavedForTargetJoin(
                        recovery,
                        "recovery-save-failed"
                );
            }
        }

        for (Session session : List.copyOf(sessions.values())) {
            if (!session.meta().playerId().equals(targetId)) {
                continue;
            }

            Player viewer = Bukkit.getPlayer(session.viewerId());

            if (session.cursorOwned) {
                boolean cursorReturned = viewer != null
                        && resolveTargetCursor(viewer, session);

                if (!cursorReturned) {
                    if (viewer != null) {
                        clearTargetOwnedCursor(viewer, session);
                    }
                    discardUnsavedForTargetJoin(
                            session,
                            "target-joined-cursor-unresolved"
                    );
                    closeSessionView(viewer, session);
                    continue;
                }
            }

            if (session.dirty()
                    && !persistDirtySession(session, true)) {
                discardUnsavedForTargetJoin(
                        session,
                        "active-session-save-failed"
                );
            } else {
                sessions.remove(session.viewerId(), session);
                recoveryByTarget.remove(targetId, session);
                releaseEditor(session);
                auditClose(session, "target-joined");
            }

            closeSessionView(viewer, session);
        }
    }

    /**
     * Applies a committed offline edit exactly once. The transaction UUID is
     * written to player PDC and saved together with the inventory mutation. If
     * the JVM stops after playerdata is saved but before the snapshot is
     * acknowledged, the marker proves the mutation already committed and the
     * next join only finalizes the snapshot.
     */
    public void applyPending(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        Snapshot snapshot = loadSnapshot(player.getUniqueId());

        if (snapshot == null || !snapshot.pending()) {
            scrubOrphanApplyMarker(player);
            return;
        }

        snapshot = ensurePendingTransaction(snapshot);
        if (snapshot == null) {
            return;
        }

        UUID transactionId = snapshot.pendingTransactionId();
        UUID appliedTransaction = readApplyTransaction(player);
        boolean alreadyCommitted = transactionId.equals(appliedTransaction);

        if (staleTargets.contains(player.getUniqueId())
                && !alreadyCommitted) {
            quarantineStalePending(
                    player,
                    snapshot
            );
            return;
        }

        if (!alreadyCommitted) {
            LiveInventoryState original = captureLiveState(player);

            try {
                writeApplyTransaction(player, transactionId);
                applySnapshotToPlayer(player, snapshot);
                player.saveData();
            } catch (RuntimeException exception) {
                restoreAfterFailedApply(
                        player,
                        original,
                        transactionId,
                        exception
                );
                return;
            }
        }

        boolean protectedPlayer =
                player.hasPermission(
                        AdminInspectService.PROTECTED_PERMISSION
                ) || snapshot.protectedPlayer()
                || previouslyProtected(player.getUniqueId());

        Snapshot applied = snapshot
                .withProtected(protectedPlayer)
                .withPending(false, null)
                .withUpdatedAt(System.currentTimeMillis());

        if (snapshotSaveFailed(applied)) {
            core.getLogger().severe(
                    "[AdminInspect] Pending edit reached playerdata but snapshot acknowledgement failed for "
                            + player.getName()
                            + " ("
                            + player.getUniqueId()
                            + ") — transaction marker retained for safe retry"
            );
            return;
        }

        updateIndex(player, applied);
        clearStaleState(player.getUniqueId());
        player.updateInventory();

        clearApplyTransactionBestEffort(player, transactionId);

        core.getLogger().info(
                "[AdminInspect] Applied pending offline inventory changes to "
                        + player.getName()
                        + " ("
                        + player.getUniqueId()
                        + ") | transaction="
                        + transactionId
        );
    }

    /**
     * Captures authoritative live state at a clean player departure or clean
     * module shutdown. The online marker is removed only after Minecraft
     * playerdata and the Mineacle snapshot are both safely persisted.
     */
    public boolean capture(Player player) {
        if (player == null) {
            return false;
        }

        UUID playerId = player.getUniqueId();

        try {
            player.saveData();
        } catch (RuntimeException exception) {
            markStaleTarget(playerId);
            runtimeSafetyFailure = true;
            core.getLogger().log(
                    Level.SEVERE,
                    "[AdminInspect] Could not durably save playerdata before offline snapshot capture for "
                            + player.getName()
                            + " ("
                            + playerId
                            + ") — safety marker retained",
                    exception
            );
            return false;
        }

        Snapshot existing = loadSnapshot(playerId);

        if (existing != null && existing.pending()) {
            core.getLogger().info(
                    "[AdminInspect] Preserved pending offline snapshot for "
                            + player.getName()
                            + " ("
                            + playerId
                            + ") during clean live capture"
            );
            clearOnlineMarker(playerId);
            return true;
        }

        Snapshot snapshot = liveSnapshot(player);

        if (snapshotSaveFailed(snapshot)) {
            markStaleTarget(playerId);
            runtimeSafetyFailure = true;
            core.getLogger().severe(
                    "[AdminInspect] Snapshot capture failed for "
                            + player.getName()
                            + " ("
                            + playerId
                            + ") — safety marker retained"
            );
            return false;
        }

        updateIndex(player, snapshot);
        clearOnlineMarker(playerId);
        return true;
    }

    public void shutdown() {
        if (validationTask != null) {
            validationTask.cancel();
            validationTask = null;
        }

        /*
         * Resolve/flush inspection sessions before capturing live inspectors.
         * This guarantees a target-owned synthetic cursor can never be copied
         * into the inspector's clean shutdown playerdata snapshot.
         */
        Set<Session> toFlush = new LinkedHashSet<>();
        toFlush.addAll(sessions.values());
        toFlush.addAll(recoveryByTarget.values());

        for (Session session : toFlush) {
            Player viewer = Bukkit.getPlayer(session.viewerId());

            if (session.cursorOwned) {
                boolean returned = viewer != null
                        && resolveTargetCursor(viewer, session);

                if (!returned) {
                    if (viewer != null) {
                        clearTargetOwnedCursor(viewer, session);
                    }
                    discardUnsavedSession(
                            session,
                            "module-shutdown-cursor-unresolved"
                    );
                    closeSessionView(viewer, session);
                    continue;
                }
            }

            boolean persisted = !session.dirty()
                    || persistDirtySession(session, true)
                    || persistDirtySession(session, false);

            if (!persisted) {
                core.getLogger().severe(
                        "[AdminInspect] CRITICAL: shutdown could not persist unsaved offline edits for "
                                + session.meta().username()
                                + " ("
                                + session.meta().playerId()
                                + ") | session="
                                + session.sessionId()
                                + " | edits were NOT applied to the player"
                );
            } else {
                auditClose(session, "module-shutdown");
            }

            closeSessionView(viewer, session);
        }

        sessions.clear();
        recoveryByTarget.clear();
        editorsByTarget.clear();

        boolean cleanLiveCaptures = true;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!capture(player)) {
                cleanLiveCaptures = false;
            }
        }

        if (indexSaveTask != null) {
            indexSaveTask.cancel();
            indexSaveTask = null;
        }
        saveIndexNow();
        protectionLoads.clear();

        if (cleanLiveCaptures
                && offlineSafetyAvailable
                && !runtimeSafetyFailure) {
            try {
                Files.deleteIfExists(serverOnlineMarker.toPath());
            } catch (IOException exception) {
                core.getLogger().log(
                        Level.WARNING,
                        "[AdminInspect] Could not clear server safety epoch marker — next startup will fail closed",
                        exception
                );
            }
        } else {
            core.getLogger().severe(
                    "[AdminInspect] Server safety epoch marker retained because shutdown did not reach a fully trusted capture state"
            );
        }
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

    private void schedulePendingApply(Player player) {
        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    if (player.isOnline()) {
                        applyPending(player);
                    }
                },
                applyDelayTicks()
        );
    }

    private boolean canModify(
            Player viewer,
            InspectType type
    ) {
        if (!settings.getBoolean(
                "offline-editing.enabled",
                true
        ) || !viewer.hasPermission(type.modifyPermission())) {
            return false;
        }

        return settings.getBoolean(
                "offline-editing.allow-creative",
                false
        ) || viewer.getGameMode() != GameMode.CREATIVE;
    }

    private boolean accessDenied(
            Player viewer,
            Session session
    ) {
        if (viewer == null
                || session == null
                || !viewer.isOnline()
                || !viewer.hasPermission(session.type().permission())) {
            return true;
        }

        SnapshotMeta meta = session.meta();
        UUID targetId = meta.playerId();
        Player online = Bukkit.getPlayer(targetId);

        if (online != null && online.isOnline()) {
            return true;
        }

        boolean self = viewer.getUniqueId().equals(targetId);

        if (self
                && !viewer.hasPermission(session.type().selfPermission())) {
            return true;
        }

        if (VanishRegistry.isVanished(targetId)
                && !viewer.hasPermission(
                AdminInspectService.HIDDEN_PERMISSION
        )) {
            return true;
        }

        if (isSnapshotStale(targetId)) {
            return true;
        }

        if (viewer.hasPermission(
                AdminInspectService.PROTECTED_BYPASS_PERMISSION
        )) {
            return false;
        }

        /*
         * The target was synchronously verified before this session opened.
         * During validation, re-check any currently loaded LuckPerms user so a
         * protection grant closes the session promptly. Do not close a valid
         * session merely because LuckPerms later unloads that offline user.
         */
        return protectedIfCurrentlyLoaded(meta);
    }

    private void downgradeToReadOnly(
            Player viewer,
            Session session
    ) {
        if (session == null || !session.editable()) {
            return;
        }

        if (session.cursorOwned
                && !resolveTargetCursor(viewer, session)) {
            clearTargetOwnedCursor(viewer, session);
            discardUnsavedSession(
                    session,
                    "edit-access-changed-cursor-unresolved"
            );
            closeSessionView(viewer, session);
            return;
        }

        if (session.dirty()
                && !persistDirtySession(session, true)) {
            retainRecovery(
                    session,
                    "edit-access-changed-save-failed"
            );
            closeSessionView(viewer, session);

            if (viewer != null && viewer.isOnline()) {
                viewer.sendMessage(
                        TextColor.color(
                                message(
                                        "messages.recovery-pending",
                                        "&cInspection closed — unsaved changes are being safely retried"
                                )
                        )
                );
            }
            return;
        }

        releaseEditor(session);
        session.editable = false;

        if (viewer != null && viewer.isOnline()) {
            viewer.sendActionBar(
                    GuiText.component(
                            message(
                                    "messages.downgraded-read-only",
                                    "&#bbbbbbInspection changed to &#D0AFFFRead Only"
                            )
                    )
            );
        }

        if (settings.getBoolean("audit.enabled", true)) {
            core.getLogger().info(
                    "[AdminInspect] session="
                            + session.sessionId()
                            + " changed to READ_ONLY"
                            + " | reason=edit-access-changed"
                            + " | target="
                            + session.meta().username()
                            + " ("
                            + session.meta().playerId()
                            + ")"
            );
        }
    }

    private void scheduleAccessClose(
            Player viewer,
            Session expected
    ) {
        if (viewer == null
                || expected == null
                || expected.closeQueued) {
            return;
        }

        expected.closeQueued = true;
        UUID viewerId = viewer.getUniqueId();

        core.getServer().getScheduler().runTask(
                core,
                () -> {
                    Session current = sessions.get(viewerId);
                    if (current == expected) {
                        closeForAccessChange(viewer, expected);
                    }
                }
        );
    }

    private void validateSessions() {
        if (!sessions.isEmpty()) {
            for (Session session : List.copyOf(sessions.values())) {
                Player viewer = Bukkit.getPlayer(session.viewerId());

                if (viewer == null || !viewer.isOnline()) {
                    discardUnsavedSession(
                            session,
                            "viewer-unavailable-without-quit-finalization"
                    );
                    continue;
                }

                if (viewer.getOpenInventory().getTopInventory()
                        != session.inventory()) {
                    if (session.cursorOwned
                            && !resolveTargetCursor(viewer, session)) {
                        clearTargetOwnedCursor(viewer, session);
                        discardUnsavedSession(
                                session,
                                "inventory-closed-cursor-unresolved"
                        );
                    } else {
                        finishOrRecover(
                                session,
                                "inventory-no-longer-open"
                        );
                    }
                    continue;
                }

                if (accessDenied(viewer, session)) {
                    closeForAccessChange(viewer, session);
                    continue;
                }

                if (session.editable()
                        && !canModify(viewer, session.type())) {
                    downgradeToReadOnly(viewer, session);
                }
            }
        }

        retryRecoveries();
    }

    private void closeForAccessChange(
            Player viewer,
            Session expected
    ) {
        Session current = sessions.get(expected.viewerId());

        if (current != expected) {
            return;
        }

        boolean saved;

        if (expected.cursorOwned
                && !resolveTargetCursor(viewer, expected)) {
            clearTargetOwnedCursor(viewer, expected);
            discardUnsavedSession(
                    expected,
                    "access-changed-cursor-unresolved"
            );
            saved = true;
        } else {
            saved = finishOrRecover(expected, "access-changed");
        }

        closeSessionView(viewer, expected);

        if (viewer != null && viewer.isOnline()) {
            viewer.sendMessage(
                    TextColor.color(
                            saved
                                    ? message(
                                    "messages.access-changed",
                                    "&cInspection closed — access changed"
                            )
                                    : message(
                                    "messages.recovery-pending",
                                    "&cInspection closed — unsaved changes are being safely retried"
                            )
                    )
            );
            SoundService.guiError(viewer, core);
        }
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

    private boolean persistDirtySession(
            Session session,
            boolean reportFailure
    ) {
        if (session == null || !session.dirty()) {
            return true;
        }

        if (session.cursorOwned) {
            if (reportFailure) {
                core.getLogger().severe(
                        "[AdminInspect] Refused to persist session="
                                + session.sessionId()
                                + " while target-owned cursor remained unresolved"
                );
            }
            return false;
        }

        Snapshot current = loadSnapshot(
                session.meta().playerId()
        );

        if (current == null
                || current.pending()
                || current.updatedAt() != session.sourceUpdatedAt()) {
            if (reportFailure) {
                offlineEditSaveFailed(session);
            }
            return false;
        }

        Snapshot updated;

        if (session.type() == InspectType.ENDER_CHEST) {
            updated = current.withEnderChest(
                    cloneArray(session.inventory().getContents())
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
                            session.inventory().getItem(OFFHAND_SLOT)
                    )
            );
        }

        UUID transactionId = UUID.randomUUID();
        updated = updated
                .withPending(true, transactionId)
                .withUpdatedAt(System.currentTimeMillis());

        if (snapshotSaveFailed(updated, reportFailure)) {
            if (reportFailure) {
                offlineEditSaveFailed(session);
            }
            return false;
        }

        updateIndex(session.meta().playerId(), updated);

        core.getLogger().info(
                "[AdminInspect] session="
                        + session.sessionId()
                        + " saved OFFLINE edits for "
                        + session.meta().username()
                        + " ("
                        + session.meta().playerId()
                        + ") | type="
                        + session.type().name()
                        + " | transaction="
                        + transactionId
                        + " | modification-events="
                        + session.modificationEvents()
        );

        session.dirty = false;
        return true;
    }

    /**
     * Player-facing resolution intentionally accepts public display identity
     * only. Raw usernames remain in snapshot metadata for private audit use but
     * cannot bypass nickname privacy through /invsee or /echest.
     */
    private SnapshotMeta resolve(String input) {
        String normalized = normalize(input);
        SnapshotMeta match = null;

        for (SnapshotMeta candidate : index.values()) {
            if (!normalize(candidate.publicName()).equals(normalized)) {
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

    private ProtectionState protectionState(SnapshotMeta meta) {
        if (meta.protectedPlayer()) {
            return ProtectionState.PROTECTED;
        }

        RegisteredServiceProvider<LuckPerms> registration =
                Bukkit.getServicesManager().getRegistration(LuckPerms.class);

        if (registration == null) {
            return ProtectionState.UNKNOWN;
        }

        LuckPerms luckPerms = registration.getProvider();
        User loaded = luckPerms.getUserManager().getUser(meta.playerId());

        if (loaded != null) {
            return hasProtectedPermission(loaded)
                    ? ProtectionState.PROTECTED
                    : ProtectionState.NOT_PROTECTED;
        }

        requestProtectionLoad(luckPerms, meta.playerId());
        return ProtectionState.UNKNOWN;
    }

    private boolean protectedIfCurrentlyLoaded(SnapshotMeta meta) {
        if (meta.protectedPlayer()) {
            return true;
        }

        RegisteredServiceProvider<LuckPerms> registration =
                Bukkit.getServicesManager().getRegistration(LuckPerms.class);

        if (registration == null) {
            return true;
        }

        User loaded = registration.getProvider()
                .getUserManager()
                .getUser(meta.playerId());

        return loaded != null && hasProtectedPermission(loaded);
    }

    private void requestProtectionLoad(
            LuckPerms luckPerms,
            UUID playerId
    ) {
        if (!protectionLoads.add(playerId)) {
            return;
        }

        luckPerms.getUserManager()
                .loadUser(playerId)
                .whenComplete((user, throwable) -> {
                    protectionLoads.remove(playerId);
                    if (throwable != null) {
                        core.getLogger().log(
                                Level.WARNING,
                                "[AdminInspect] Could not refresh offline protection state for "
                                        + playerId,
                                throwable
                        );
                    }
                });
    }

    private boolean hasProtectedPermission(User user) {
        return user.getCachedData()
                .getPermissionData()
                .checkPermission(
                        AdminInspectService.PROTECTED_PERMISSION
                )
                .asBoolean();
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
                if (indexConfig.getBoolean(path + "stale", false)) {
                    staleTargets.add(playerId);
                }
            } catch (IllegalArgumentException ignored) {
                // Malformed index entries are ignored and cannot become targets.
            }
        }
    }

    private void reconcileIndexFromSnapshots() {
        File[] files = dataDirectory.listFiles(
                file -> file.isFile()
                        && file.getName().endsWith(".yml")
                        && !file.equals(indexFile)
        );

        if (files == null || files.length == 0) {
            return;
        }

        boolean changed = false;

        for (File file : files) {
            String name = file.getName();
            String rawId = name.substring(0, name.length() - 4);
            UUID playerId;

            try {
                playerId = UUID.fromString(rawId);
            } catch (IllegalArgumentException ignored) {
                continue;
            }

            Snapshot snapshot = loadSnapshot(playerId);
            if (snapshot == null) {
                continue;
            }

            if (uncleanServerStartup
                    || snapshot.safetyVersion() < SNAPSHOT_SAFETY_VERSION) {
                staleTargets.add(playerId);
                writeIndexStale(playerId, true);
                changed = true;
            }

            SnapshotMeta existing = index.get(playerId);
            if (existing != null
                    && existing.updatedAt() >= snapshot.updatedAt()) {
                continue;
            }

            SnapshotMeta meta = new SnapshotMeta(
                    playerId,
                    snapshot.username(),
                    snapshot.publicName(),
                    snapshot.protectedPlayer(),
                    snapshot.updatedAt()
            );
            index.put(playerId, meta);
            writeIndexMeta(meta);
            changed = true;
        }

        if (changed) {
            saveIndexNow();
        }
    }

    private Snapshot loadSnapshot(UUID playerId) {
        File file = snapshotFile(playerId);

        if (!file.isFile()) {
            return null;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String username = yaml.getString(
                "username",
                playerId.toString()
        );
        String publicName = yaml.getString(
                "public-name",
                username
        );
        UUID pendingTransaction = parseUuid(
                yaml.getString("pending-transaction-id")
        );
        boolean pending = yaml.getBoolean("pending", false);
        int safetyVersion = yaml.getInt("safety-version", 1);
        long updatedAt = yaml.getLong("updated-at", 0L);

        if (safetyVersion >= SNAPSHOT_SAFETY_VERSION) {
            UUID storedPlayerId = parseUuid(yaml.getString("player-id"));

            if (!playerId.equals(storedPlayerId)
                    || updatedAt <= 0L
                    || (pending && pendingTransaction == null)) {
                core.getLogger().severe(
                        "[AdminInspect] Rejected malformed trusted offline snapshot for "
                                + playerId
                );
                return null;
            }
        }

        return new Snapshot(
                playerId,
                username,
                publicName,
                yaml.getBoolean("protected", false),
                pending,
                pendingTransaction,
                safetyVersion,
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
                updatedAt
        );
    }

    private Snapshot ensurePendingTransaction(Snapshot snapshot) {
        if (snapshot == null || !snapshot.pending()) {
            return snapshot;
        }

        if (snapshot.pendingTransactionId() != null) {
            return snapshot;
        }

        Snapshot upgraded = snapshot.withPending(
                true,
                UUID.randomUUID()
        );

        if (snapshotSaveFailed(upgraded)) {
            return null;
        }

        updateIndex(upgraded.playerId(), upgraded);
        return upgraded;
    }

    private boolean snapshotSaveFailed(Snapshot snapshot) {
        return snapshotSaveFailed(snapshot, true);
    }

    private boolean snapshotSaveFailed(
            Snapshot snapshot,
            boolean reportFailure
    ) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("player-id", snapshot.playerId().toString());
        yaml.set("username", snapshot.username());
        yaml.set("public-name", snapshot.publicName());
        yaml.set("protected", snapshot.protectedPlayer());
        yaml.set("pending", snapshot.pending());
        yaml.set(
                "pending-transaction-id",
                snapshot.pendingTransactionId() == null
                        ? null
                        : snapshot.pendingTransactionId().toString()
        );
        yaml.set("safety-version", snapshot.safetyVersion());
        yaml.set("updated-at", snapshot.updatedAt());
        writeItems(yaml, "inventory.storage", snapshot.storage());
        writeItems(yaml, "inventory.armor", snapshot.armor());
        yaml.set("inventory.offhand", snapshot.offhand());
        yaml.set("ender-chest-size", snapshot.enderChest().length);
        writeItems(yaml, "ender-chest", snapshot.enderChest());

        try {
            saveAtomic(yaml, snapshotFile(snapshot.playerId()));
            return false;
        } catch (IOException | RuntimeException exception) {
            if (reportFailure) {
                core.getLogger().log(
                        Level.SEVERE,
                        "[AdminInspect] Could not safely persist offline snapshot for "
                                + snapshot.playerId(),
                        exception
                );
            }
            return true;
        }
    }

    private Snapshot liveSnapshot(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerInventory inventory = player.getInventory();
        boolean protectedPlayer =
                player.hasPermission(
                        AdminInspectService.PROTECTED_PERMISSION
                ) || previouslyProtected(playerId);

        return new Snapshot(
                playerId,
                player.getName(),
                DisplayNames.commandDisplayName(player),
                protectedPlayer,
                false,
                null,
                SNAPSHOT_SAFETY_VERSION,
                cloneArray(inventory.getStorageContents()),
                cloneArray(inventory.getArmorContents()),
                cloneItem(inventory.getItemInOffHand()),
                cloneArray(player.getEnderChest().getContents()),
                System.currentTimeMillis()
        );
    }

    private LiveInventoryState captureLiveState(Player player) {
        PlayerInventory inventory = player.getInventory();

        return new LiveInventoryState(
                cloneArray(inventory.getStorageContents()),
                cloneArray(inventory.getArmorContents()),
                cloneItem(inventory.getItemInOffHand()),
                cloneArray(player.getEnderChest().getContents())
        );
    }

    private void applySnapshotToPlayer(
            Player player,
            Snapshot snapshot
    ) {
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
        enderChest.setContents(
                copyArray(
                        snapshot.enderChest(),
                        enderChest.getSize()
                )
        );
    }

    private void restoreLiveState(
            Player player,
            LiveInventoryState state
    ) {
        PlayerInventory inventory = player.getInventory();
        inventory.setStorageContents(
                copyArray(state.storage(), STORAGE_SIZE)
        );
        inventory.setArmorContents(
                copyArray(state.armor(), 4)
        );
        inventory.setItemInOffHand(
                cloneItem(state.offhand())
        );

        Inventory enderChest = player.getEnderChest();
        enderChest.setContents(
                copyArray(
                        state.enderChest(),
                        enderChest.getSize()
                )
        );
    }

    private void restoreAfterFailedApply(
            Player player,
            LiveInventoryState original,
            UUID transactionId,
            RuntimeException cause
    ) {
        boolean rollbackSaved = false;

        try {
            restoreLiveState(player, original);
            player.getPersistentDataContainer().remove(applyTransactionKey);
            player.updateInventory();
            player.saveData();
            rollbackSaved = true;
        } catch (RuntimeException rollbackException) {
            markStaleTarget(player.getUniqueId());
            core.getLogger().log(
                    Level.SEVERE,
                    "[AdminInspect] CRITICAL: could not persist rollback for "
                            + player.getName()
                            + " ("
                            + player.getUniqueId()
                            + ") after failed offline apply transaction="
                            + transactionId,
                    rollbackException
            );
        }

        core.getLogger().log(
                Level.SEVERE,
                "[AdminInspect] Offline inventory apply aborted for "
                        + player.getName()
                        + " ("
                        + player.getUniqueId()
                        + ") | transaction="
                        + transactionId
                        + " | rollback-saved="
                        + rollbackSaved
                        + " | pending snapshot retained",
                cause
        );
    }

    private void quarantineStalePending(
            Player player,
            Snapshot snapshot
    ) {
        File source = snapshotFile(snapshot.playerId());
        File target = new File(
                staleDirectory,
                snapshot.playerId()
                        + "-"
                        + System.currentTimeMillis()
                        + ".yml"
        );

        try {
            moveReplace(source, target);
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "[AdminInspect] Could not quarantine stale pending snapshot for "
                            + snapshot.playerId()
                            + " — edit was NOT applied",
                    exception
            );
            return;
        }

        Snapshot fresh = liveSnapshot(player);

        if (snapshotSaveFailed(fresh)) {
            core.getLogger().severe(
                    "[AdminInspect] Stale pending snapshot quarantined but fresh replacement could not be written for "
                            + player.getName()
                            + " ("
                            + player.getUniqueId()
                            + ")"
            );
            return;
        }

        updateIndex(player, fresh);
        player.getPersistentDataContainer().remove(applyTransactionKey);

        core.getLogger().severe(
                "[AdminInspect] Quarantined stale pending offline edits for "
                        + player.getName()
                        + " ("
                        + player.getUniqueId()
                        + ") | reason=unclean-online-session"
                        + " | playerdata was NOT overwritten"
        );
    }

    private void offlineEditSaveFailed(Session session) {
        core.getLogger().severe(
                "[AdminInspect] session="
                        + session.sessionId()
                        + " FAILED to persist OFFLINE edits for "
                        + session.meta().username()
                        + " ("
                        + session.meta().playerId()
                        + ") | type="
                        + session.type().name()
                        + " | new changes were not persisted"
        );

        Player viewer = Bukkit.getPlayer(session.viewerId());

        if (viewer != null && viewer.isOnline()) {
            fail(
                    viewer,
                    "messages.offline-save-failed",
                    "&cOffline inventory changes could not be saved safely — no new changes will be applied"
            );
        }
    }

    private void updateIndex(
            Player player,
            Snapshot snapshot
    ) {
        boolean protectedPlayer =
                snapshot.protectedPlayer()
                        || player.hasPermission(
                        AdminInspectService.PROTECTED_PERMISSION
                )
                        || previouslyProtected(player.getUniqueId());

        SnapshotMeta meta = new SnapshotMeta(
                player.getUniqueId(),
                player.getName(),
                DisplayNames.commandDisplayName(player),
                protectedPlayer,
                snapshot.updatedAt()
        );
        index.put(meta.playerId(), meta);
        writeIndexMeta(meta);
        scheduleIndexSave();
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
        scheduleIndexSave();
    }

    private boolean previouslyProtected(UUID playerId) {
        SnapshotMeta meta = index.get(playerId);

        if (meta != null && meta.protectedPlayer()) {
            return true;
        }

        Snapshot snapshot = loadSnapshot(playerId);
        return snapshot != null && snapshot.protectedPlayer();
    }

    private void writeIndexMeta(SnapshotMeta meta) {
        String path = "players." + meta.playerId() + ".";
        indexConfig.set(path + "username", meta.username());
        indexConfig.set(path + "public-name", meta.publicName());
        indexConfig.set(path + "protected", meta.protectedPlayer());
        indexConfig.set(path + "updated-at", meta.updatedAt());
    }

    private void writeIndexStale(UUID playerId, boolean stale) {
        String path = "players." + playerId + ".stale";
        if (stale) {
            indexConfig.set(path, true);
        } else {
            indexConfig.set(path, null);
        }
    }

    private void markStaleTarget(UUID playerId) {
        staleTargets.add(playerId);
        writeIndexStale(playerId, true);
        scheduleIndexSave();
    }

    private void clearStaleState(UUID playerId) {
        staleTargets.remove(playerId);
        writeIndexStale(playerId, false);
        scheduleIndexSave();
    }

    private void scheduleIndexSave() {
        if (indexSaveTask != null) {
            return;
        }

        long delay = Math.clamp(
                settings.getLong(
                        "offline-editing.index-flush-ticks",
                        100L
                ),
                20L,
                1_200L
        );

        indexSaveTask = core.getServer()
                .getScheduler()
                .runTaskLater(
                        core,
                        () -> {
                            indexSaveTask = null;
                            saveIndexNow();
                        },
                        delay
                );
    }

    private void saveIndexNow() {
        try {
            saveAtomic(indexConfig, indexFile);
        } catch (IOException exception) {
            runtimeSafetyFailure = true;
            core.getLogger().log(
                    Level.WARNING,
                    "[AdminInspect] Could not save offline inspect index",
                    exception
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
        forceFile(temporary);

        try {
            moveReplace(temporary, target);
            forceFile(target);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private void forceFile(File file) throws IOException {
        try (FileChannel channel = FileChannel.open(
                file.toPath(),
                StandardOpenOption.WRITE
        )) {
            channel.force(true);
        }
    }

    private void moveReplace(
            File source,
            File target
    ) throws IOException {
        try {
            Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
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

            if (hasItem(item)) {
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
            items[slot] = yaml.getItemStack(path + "." + slot);
        }
        return items;
    }

    private boolean finishOrRecover(
            Session session,
            String reason
    ) {
        if (session == null) {
            return true;
        }

        if (session.cursorOwned) {
            return false;
        }

        if (session.dirty()
                && !persistDirtySession(session, true)) {
            retainRecovery(
                    session,
                    reason + "-save-failed"
            );
            return false;
        }

        sessions.remove(session.viewerId(), session);
        recoveryByTarget.remove(
                session.meta().playerId(),
                session
        );
        releaseEditor(session);
        auditClose(session, reason);
        return true;
    }

    private void retainRecovery(
            Session session,
            String reason
    ) {
        UUID targetId = session.meta().playerId();

        sessions.remove(session.viewerId(), session);
        recoveryByTarget.put(targetId, session);
        editorsByTarget.put(targetId, session.viewerId());
        session.editable = false;
        session.closeQueued = true;

        core.getLogger().warning(
                "[AdminInspect] session="
                        + session.sessionId()
                        + " entered SAVE RECOVERY for "
                        + session.meta().username()
                        + " ("
                        + targetId
                        + ") | reason="
                        + reason
                        + " | editor lock retained"
        );
    }

    private void retryRecoveries() {
        if (recoveryByTarget.isEmpty()) {
            return;
        }

        for (Session session : List.copyOf(recoveryByTarget.values())) {
            UUID targetId = session.meta().playerId();
            Player target = Bukkit.getPlayer(targetId);

            if (target != null && target.isOnline()) {
                discardUnsavedForTargetJoin(
                        session,
                        "target-became-online-during-recovery"
                );
                continue;
            }

            if (!persistDirtySession(session, false)) {
                continue;
            }

            recoveryByTarget.remove(targetId, session);
            releaseEditor(session);
            auditClose(session, "save-recovery-complete");

            Player viewer = Bukkit.getPlayer(session.viewerId());

            if (viewer != null && viewer.isOnline()) {
                viewer.sendMessage(
                        TextColor.color(
                                "&#bbbbbbOffline inventory changes for &#B078FF"
                                        + session.meta().publicName()
                                        + " &#bbbbbbwere saved safely"
                        )
                );
            }
        }
    }

    private void discardUnsavedForTargetJoin(
            Session session,
            String reason
    ) {
        if (session == null) {
            return;
        }

        UUID targetId = session.meta().playerId();
        sessions.remove(session.viewerId(), session);
        recoveryByTarget.remove(targetId, session);
        releaseEditor(session);
        session.dirty = false;
        session.editable = false;
        session.cursorOwned = false;

        core.getLogger().severe(
                "[AdminInspect] UNSAVED offline edits were discarded for "
                        + session.meta().username()
                        + " ("
                        + targetId
                        + ") because the player joined before the edits could be persisted safely"
                        + " | session="
                        + session.sessionId()
                        + " | reason="
                        + reason
                        + " | player state was NOT overwritten"
        );

        Player viewer = Bukkit.getPlayer(session.viewerId());

        if (viewer != null && viewer.isOnline()) {
            viewer.sendMessage(
                    TextColor.color(
                            "&cOffline inventory changes were not saved because the player joined — no unsafe changes were applied"
                    )
            );
            SoundService.guiError(viewer, core);
        }

        auditClose(
                session,
                "target-joined-unsaved-edits-discarded"
        );
    }

    private void discardUnsavedSession(
            Session session,
            String reason
    ) {
        if (session == null) {
            return;
        }

        sessions.remove(session.viewerId(), session);
        recoveryByTarget.remove(
                session.meta().playerId(),
                session
        );
        releaseEditor(session);
        session.dirty = false;
        session.editable = false;
        session.cursorOwned = false;

        core.getLogger().severe(
                "[AdminInspect] Discarded unsaved synthetic offline inspection changes for "
                        + session.meta().username()
                        + " ("
                        + session.meta().playerId()
                        + ") | session="
                        + session.sessionId()
                        + " | reason="
                        + reason
                        + " | authoritative target snapshot was left unchanged"
        );

        auditClose(session, reason);
    }

    private void releaseEditor(Session session) {
        if (session == null) {
            return;
        }

        editorsByTarget.remove(
                session.meta().playerId(),
                session.viewerId()
        );
    }

    private boolean closeViewerSession(Player viewer) {
        Session old = sessions.get(viewer.getUniqueId());

        if (old == null) {
            return true;
        }

        if (old.cursorOwned
                && !resolveTargetCursor(viewer, old)) {
            return false;
        }

        return finishOrRecover(
                old,
                "replaced-by-new-inspection"
        );
    }

    private void clearTargetOwnedCursor(
            Player viewer,
            Session session
    ) {
        if (viewer != null && session != null && session.cursorOwned) {
            viewer.setItemOnCursor(new ItemStack(Material.AIR));
            session.cursorOwned = false;
        }
    }

    private void closeSessionView(
            Player viewer,
            Session session
    ) {
        if (viewer != null
                && viewer.isOnline()
                && viewer.getOpenInventory().getTopInventory()
                == session.inventory()) {
            viewer.closeInventory(InventoryCloseEvent.Reason.CANT_USE);
        }
    }

    private void mergeIntoExistingStacks(
            Session session,
            ItemStack[] contents,
            ItemStack remaining
    ) {
        for (int slot = 0;
             slot < contents.length && remaining.getAmount() > 0;
             slot++) {
            if (blockedTopSlot(session, slot)) {
                continue;
            }

            ItemStack existing = contents[slot];

            if (!hasItem(existing)
                    || !existing.isSimilar(remaining)) {
                continue;
            }

            int free = existing.getMaxStackSize()
                    - existing.getAmount();

            if (free <= 0) {
                continue;
            }

            int moved = Math.min(free, remaining.getAmount());
            ItemStack merged = existing.clone();
            merged.setAmount(existing.getAmount() + moved);
            contents[slot] = merged;
            remaining.setAmount(remaining.getAmount() - moved);
        }
    }

    private void placeIntoEmptySlots(
            Session session,
            ItemStack[] contents,
            ItemStack remaining
    ) {
        for (int slot = 0;
             slot < contents.length && remaining.getAmount() > 0;
             slot++) {
            if (blockedTopSlot(session, slot)
                    || hasItem(contents[slot])) {
                continue;
            }

            int moved = Math.min(
                    remaining.getMaxStackSize(),
                    remaining.getAmount()
            );
            ItemStack placed = remaining.clone();
            placed.setAmount(moved);
            contents[slot] = placed;
            remaining.setAmount(remaining.getAmount() - moved);
        }
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

        String delta = session.modificationEvents() > 0
                ? deltaSummary(session)
                : "none";

        core.getLogger().info(
                "[AdminInspect] session="
                        + session.sessionId()
                        + " closed OFFLINE "
                        + session.type().name()
                        + " of "
                        + session.meta().username()
                        + " ("
                        + session.meta().playerId()
                        + ") | reason="
                        + reason
                        + " | modification-events="
                        + session.modificationEvents()
                        + " | delta="
                        + delta
                        + " | duration-ms="
                        + Math.max(
                        0L,
                        System.currentTimeMillis() - session.openedAt()
                )
        );
    }

    private String deltaSummary(Session session) {
        int maxChanges = Math.clamp(
                settings.getInt("audit.max-delta-slots", 24),
                1,
                100
        );
        ItemStack[] before = session.baseline();
        ItemStack[] after = session.inventory().getContents();
        List<String> changes = new ArrayList<>();
        int total = 0;

        int limit = Math.min(before.length, after.length);
        for (int slot = 0; slot < limit; slot++) {
            if (blockedTopSlot(session, slot)
                    || Objects.equals(before[slot], after[slot])) {
                continue;
            }

            total++;
            if (changes.size() < maxChanges) {
                changes.add(
                        "slot "
                                + slot
                                + ":"
                                + item(before[slot])
                                + "->"
                                + item(after[slot])
                );
            }
        }

        if (total == 0) {
            return "none";
        }

        String summary = String.join(",", changes);
        if (total > changes.size()) {
            summary += ",+" + (total - changes.size()) + " more";
        }
        return summary;
    }

    private String item(ItemStack stack) {
        if (!hasItem(stack)) {
            return "AIR";
        }

        return stack.getAmount()
                + "x"
                + stack.getType().getKey().asString()
                + (stack.hasItemMeta() ? "[meta]" : "");
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
                TextColor.color(message(path, fallback))
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
        return !hasItem(item) ? null : item.clone();
    }

    private boolean hasItem(ItemStack item) {
        return item != null
                && !item.getType().isAir()
                && item.getAmount() > 0;
    }

    private File snapshotFile(UUID playerId) {
        return new File(dataDirectory, playerId + ".yml");
    }

    private File onlineMarker(UUID playerId) {
        return new File(onlineDirectory, playerId + ".lock");
    }

    private void writeOnlineMarker(File marker) throws IOException {
        byte[] bytes = Long.toString(System.currentTimeMillis())
                .getBytes(StandardCharsets.UTF_8);

        try (FileChannel channel = FileChannel.open(
                marker.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                int written = channel.write(buffer);
                if (written == 0) {
                    Thread.onSpinWait();
                }
            }
            channel.force(true);
        }
    }

    private boolean isSnapshotStale(UUID playerId) {
        return !offlineSafetyAvailable
                || staleTargets.contains(playerId)
                || (onlineMarker(playerId).isFile()
                && Bukkit.getPlayer(playerId) == null);
    }

    private void clearOnlineMarker(UUID playerId) {
        try {
            Files.deleteIfExists(onlineMarker(playerId).toPath());
            clearStaleState(playerId);
        } catch (IOException exception) {
            markStaleTarget(playerId);
            runtimeSafetyFailure = true;
            core.getLogger().log(
                    Level.SEVERE,
                    "[AdminInspect] Could not clear online safety marker for "
                            + playerId
                            + " — snapshot remains fail-closed",
                    exception
            );
        }
    }

    private UUID readApplyTransaction(Player player) {
        String raw = player.getPersistentDataContainer().get(
                applyTransactionKey,
                PersistentDataType.STRING
        );
        return parseUuid(raw);
    }

    private void writeApplyTransaction(
            Player player,
            UUID transactionId
    ) {
        player.getPersistentDataContainer().set(
                applyTransactionKey,
                PersistentDataType.STRING,
                transactionId.toString()
        );
    }

    private void clearApplyTransactionBestEffort(
            Player player,
            UUID expected
    ) {
        UUID current = readApplyTransaction(player);
        if (!expected.equals(current)) {
            return;
        }

        player.getPersistentDataContainer().remove(applyTransactionKey);

        try {
            player.saveData();
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "[AdminInspect] Applied transaction marker cleanup will retry on a later join for "
                            + player.getUniqueId(),
                    exception
            );
        }
    }

    private void scrubOrphanApplyMarker(Player player) {
        if (readApplyTransaction(player) == null) {
            return;
        }

        player.getPersistentDataContainer().remove(applyTransactionKey);
        try {
            player.saveData();
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.FINE,
                    "[AdminInspect] Could not persist orphan transaction marker cleanup for "
                            + player.getUniqueId(),
                    exception
            );
        }
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String newSessionId() {
        return UUID.randomUUID()
                .toString()
                .substring(0, 8)
                .toUpperCase(Locale.ROOT);
    }

    private void initializeStorage() {
        ensureDirectory(dataDirectory, "offline inspect data directory");
        ensureDirectory(onlineDirectory, "offline inspect online marker directory");
        ensureDirectory(staleDirectory, "offline inspect stale quarantine directory");

        if (!indexFile.exists()) {
            try {
                if (!indexFile.createNewFile()) {
                    throw new IOException("createNewFile returned false");
                }
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Could not initialize offline inspect index",
                        exception
                );
            }
        }

        if (!indexFile.isFile()) {
            throw new IllegalStateException(
                    "Offline inspect index path is not a file"
            );
        }

        File[] markers = onlineDirectory.listFiles(
                file -> file.isFile()
                        && file.getName().endsWith(".lock")
        );

        if (markers != null) {
            for (File marker : markers) {
                String name = marker.getName();
                String rawId = name.substring(0, name.length() - 5);
                try {
                    staleTargets.add(UUID.fromString(rawId));
                } catch (IllegalArgumentException ignored) {
                    // Ignore malformed marker names safely.
                }
            }
        }
    }

    private void ensureDirectory(
            File directory,
            String description
    ) {
        if (!directory.exists()
                && !directory.mkdirs()
                && !directory.isDirectory()) {
            throw new IllegalStateException(
                    "Could not create " + description
            );
        }
    }

    public enum Access {
        NONE,
        READ_ONLY,
        EDITABLE,
        UNAUTHORIZED
    }

    private enum ProtectionState {
        PROTECTED,
        NOT_PROTECTED,
        UNKNOWN
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
        private final ItemStack[] baseline;
        private final long sourceUpdatedAt;
        private final long openedAt;
        private boolean editable;
        private boolean dirty;
        private boolean closeQueued;
        private boolean cursorOwned;
        private int modificationEvents;

        private Session(
                String sessionId,
                UUID viewerId,
                SnapshotMeta meta,
                InspectType type,
                Inventory inventory,
                ItemStack[] baseline,
                long sourceUpdatedAt,
                boolean editable,
                long openedAt
        ) {
            this.sessionId = sessionId;
            this.viewerId = viewerId;
            this.meta = meta;
            this.type = type;
            this.inventory = inventory;
            this.baseline = baseline;
            this.sourceUpdatedAt = sourceUpdatedAt;
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

        private ItemStack[] baseline() {
            return baseline;
        }

        private long sourceUpdatedAt() {
            return sourceUpdatedAt;
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

    private record LiveInventoryState(
            ItemStack[] storage,
            ItemStack[] armor,
            ItemStack offhand,
            ItemStack[] enderChest
    ) {
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
            UUID pendingTransactionId,
            int safetyVersion,
            ItemStack[] storage,
            ItemStack[] armor,
            ItemStack offhand,
            ItemStack[] enderChest,
            long updatedAt
    ) {
        private Snapshot withProtected(boolean value) {
            return new Snapshot(
                    playerId,
                    username,
                    publicName,
                    value,
                    pending,
                    pendingTransactionId,
                    safetyVersion,
                    storage,
                    armor,
                    offhand,
                    enderChest,
                    updatedAt
            );
        }

        private Snapshot withPending(
                boolean value,
                UUID transactionId
        ) {
            return new Snapshot(
                    playerId,
                    username,
                    publicName,
                    protectedPlayer,
                    value,
                    transactionId,
                    safetyVersion,
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
                    pendingTransactionId,
                    safetyVersion,
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
                    pendingTransactionId,
                    safetyVersion,
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
                    pendingTransactionId,
                    safetyVersion,
                    storageValue,
                    armorValue,
                    offhandValue,
                    enderChest,
                    updatedAt
            );
        }
    }
}
