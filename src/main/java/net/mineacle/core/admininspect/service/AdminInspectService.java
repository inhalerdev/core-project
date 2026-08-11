package net.mineacle.core.admininspect.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AdminInspectService {

    public static final String HIDDEN_PERMISSION =
            "mineacleadmin.inspect.hidden";
    public static final String PROTECTED_PERMISSION =
            "mineacleadmin.inspect.protected";
    public static final String PROTECTED_BYPASS_PERMISSION =
            "mineacleadmin.inspect.protected.bypass";

    private final Core core;
    private final File file;
    private final Map<UUID, Session> sessions =
            new LinkedHashMap<>();
    private final Map<UUID, Long> lastReadOnlyNotice =
            new LinkedHashMap<>();

    private FileConfiguration config;
    private BukkitTask validationTask;

    public AdminInspectService(
            Core core
    ) {
        this.core = core;
        this.file = new File(
                core.getDataFolder(),
                "admininspect.yml"
        );

        reload();

        long validationTicks =
                Math.clamp(
                        config.getLong(
                                "session-validation-ticks",
                                20L
                        ),
                        5L,
                        200L
                );

        validationTask =
                core.getServer()
                        .getScheduler()
                        .runTaskTimer(
                                core,
                                this::validateSessions,
                                validationTicks,
                                validationTicks
                        );
    }

    public void reload() {
        ensureDataFile();
        config =
                YamlConfiguration
                        .loadConfiguration(
                                file
                        );
    }

    public OpenResult open(
            Player viewer,
            Player target,
            InspectType type
    ) {
        OpenResult authorization =
                authorize(
                        viewer,
                        target,
                        type
                );

        if (authorization
                != OpenResult.SUCCESS) {
            return authorization;
        }

        Inventory inventory =
                inventory(
                        target,
                        type
                );

        InventoryView view =
                viewer.openInventory(
                        inventory
                );

        if (view == null) {
            return OpenResult.OPEN_FAILED;
        }

        Session session =
                new Session(
                        viewer.getUniqueId(),
                        target.getUniqueId(),
                        type,
                        inventory,
                        System.currentTimeMillis()
                );

        sessions.put(
                viewer.getUniqueId(),
                session
        );

        boolean editable =
                canModify(
                        viewer,
                        type
                );

        viewer.sendActionBar(
                GuiText.component(
                        replacePlayer(
                                message(
                                        editable
                                                ? "messages.open-editable"
                                                : "messages.open-read-only",
                                        editable
                                                ? "&#bbbbbbInspecting &#B078FF%player% &#bbbbbb— &cLive Editing"
                                                : "&#bbbbbbInspecting &#B078FF%player% &#bbbbbb— &#D0AFFFRead Only"
                                ),
                                target
                        )
                )
        );

        SoundService.guiSelect(
                viewer,
                core
        );

        auditOpen(
                viewer,
                target,
                type,
                editable
        );

        return OpenResult.SUCCESS;
    }

    public void fail(
            Player viewer,
            OpenResult result,
            InspectType type
    ) {
        if (viewer == null
                || result == OpenResult.SUCCESS) {
            return;
        }

        String output =
                failureMessage(
                        result,
                        type
                );

        if (!output.isBlank()) {
            viewer.sendMessage(
                    TextColor.color(
                            output
                    )
            );
            SoundService.guiError(
                    viewer,
                    core
            );
        }
    }

    private String failureMessage(
            OpenResult result,
            InspectType type
    ) {
        return switch (result) {
            case SUCCESS -> "";
            case USAGE ->
                    message(
                            type == InspectType.INVENTORY
                                    ? "messages.usage-inventory"
                                    : "messages.usage-ender-chest",
                            type == InspectType.INVENTORY
                                    ? "&cUsage: /invsee <player>"
                                    : "&cUsage: /echest <player>"
                    );
            case NO_PERMISSION ->
                    message(
                            "messages.no-permission",
                            "&cYou do not have permission to inspect player inventories"
                    );
            case TARGET_UNAVAILABLE ->
                    message(
                            "messages.target-unavailable",
                            "&cThat player is not online"
                    );
            case SELF_DENIED ->
                    message(
                            type == InspectType.INVENTORY
                                    ? "messages.self-inventory"
                                    : "messages.self-ender-chest",
                            type == InspectType.INVENTORY
                                    ? "&cYou cannot inspect your own inventory"
                                    : "&cYou cannot inspect your own ender chest"
                    );
            case PROTECTED ->
                    message(
                            "messages.protected",
                            "&cYou cannot inspect that player"
                    );
            case OPEN_FAILED ->
                    message(
                            "messages.open-failed",
                            "&cCould not open that inventory"
                    );
        };
    }

    public List<String> completions(
            Player viewer,
            InspectType type,
            String input
    ) {
        if (viewer == null
                || !viewer.hasPermission(
                type.permission()
        )) {
            return List.of();
        }

        String partial =
                input == null
                        ? ""
                        : input.trim();

        boolean includeSelf =
                viewer.hasPermission(
                        type.selfPermission()
                );
        boolean includeHidden =
                viewer.hasPermission(
                        HIDDEN_PERMISSION
                );
        boolean bypassProtected =
                viewer.hasPermission(
                        PROTECTED_BYPASS_PERMISSION
                );

        Map<String, String> unique =
                new LinkedHashMap<>();

        for (Player target
                : Bukkit.getOnlinePlayers()) {
            if (!target.isOnline()
                    || target.isDead()) {
                continue;
            }

            boolean self =
                    target.getUniqueId()
                            .equals(
                                    viewer.getUniqueId()
                            );

            if (self && !includeSelf) {
                continue;
            }

            if (!self
                    && !viewer.canSee(target)
                    && !includeHidden) {
                continue;
            }

            if (!self
                    && target.hasPermission(
                    PROTECTED_PERMISSION
            )
                    && !bypassProtected) {
                continue;
            }

            if (!partial.isEmpty()
                    && !DisplayNames
                    .startsWithDisplay(
                            target,
                            partial
                    )) {
                continue;
            }

            String name =
                    DisplayNames
                            .commandDisplayName(
                                    target
                            );

            if (name == null
                    || name.isBlank()) {
                continue;
            }

            unique.putIfAbsent(
                    name.toLowerCase(
                            Locale.ROOT
                    ),
                    name
            );
        }

        List<String> result =
                new ArrayList<>(
                        unique.values()
                );
        result.sort(
                String.CASE_INSENSITIVE_ORDER
        );
        return List.copyOf(result);
    }

    public InteractionAccess interactionAccess(
            Player viewer,
            InventoryView view
    ) {
        Session session =
                session(
                        viewer,
                        view
                );

        if (session == null) {
            return InteractionAccess.NONE;
        }

        Player target =
                Bukkit.getPlayer(
                        session.targetId
                );

        if (authorize(
                viewer,
                target,
                session.type
        ) != OpenResult.SUCCESS) {
            return InteractionAccess.UNAUTHORIZED;
        }

        return canModify(
                viewer,
                session.type
        )
                ? InteractionAccess.EDITABLE
                : InteractionAccess.READ_ONLY;
    }

    public void readOnlyFeedback(
            Player viewer
    ) {
        if (viewer == null) {
            return;
        }

        long now =
                System.currentTimeMillis();
        long cooldown =
                Math.clamp(
                        config.getLong(
                                "read-only-feedback-cooldown-millis",
                                750L
                        ),
                        250L,
                        5_000L
                );

        Long previous =
                lastReadOnlyNotice.get(
                        viewer.getUniqueId()
                );

        if (previous != null
                && now - previous < cooldown) {
            return;
        }

        lastReadOnlyNotice.put(
                viewer.getUniqueId(),
                now
        );

        viewer.sendActionBar(
                GuiText.component(
                        message(
                                "messages.read-only",
                                "&cRead-only inspection"
                        )
                )
        );

        SoundService.guiError(
                viewer,
                core
        );
    }

    public void scheduleAccessClose(
            Player viewer
    ) {
        if (viewer == null) {
            return;
        }

        UUID viewerId =
                viewer.getUniqueId();
        Session expected =
                sessions.get(viewerId);

        if (expected == null
                || expected.closeQueued) {
            return;
        }

        expected.closeQueued = true;

        core.getServer()
                .getScheduler()
                .runTask(
                        core,
                        () -> {
                            Session current =
                                    sessions.get(
                                            viewerId
                                    );

                            if (current != expected) {
                                return;
                            }

                            sessions.remove(
                                    viewerId
                            );
                            closeIfViewing(
                                    viewer,
                                    expected,
                                    InventoryCloseEvent
                                            .Reason
                                            .CANT_USE
                            );

                            viewer.sendMessage(
                                    TextColor.color(
                                            message(
                                                    "messages.access-changed",
                                                    "&cInspection closed — access changed"
                                            )
                                    )
                            );

                            SoundService.guiError(
                                    viewer,
                                    core
                            );

                            auditClose(
                                    viewer,
                                    expected,
                                    "access-changed"
                            );
                        }
                );
    }

    public void recordModification(
            Player viewer,
            String detail
    ) {
        if (viewer == null) {
            return;
        }

        Session session =
                sessions.get(
                        viewer.getUniqueId()
                );

        if (session == null) {
            return;
        }

        session.modificationEvents++;

        if (!config.getBoolean(
                "audit.log-modifications",
                true
        )) {
            return;
        }

        Player target =
                Bukkit.getPlayer(
                        session.targetId
                );

        core.getLogger().warning(
                "[AdminInspect] "
                        + identity(viewer)
                        + " modified "
                        + session.type.auditName
                        + " of "
                        + identity(
                        target,
                        session.targetId
                )
                        + " | "
                        + safeDetail(detail)
        );
    }

    public void inventoryClosed(
            Player viewer,
            InventoryView view,
            InventoryCloseEvent.Reason reason
    ) {
        Session session =
                session(
                        viewer,
                        view
                );

        if (session == null) {
            return;
        }

        sessions.remove(
                viewer.getUniqueId()
        );
        lastReadOnlyNotice.remove(
                viewer.getUniqueId()
        );

        auditClose(
                viewer,
                session,
                reason.name()
                        .toLowerCase(
                                Locale.ROOT
                        )
        );
    }

    public void viewerQuit(
            Player viewer
    ) {
        if (viewer == null) {
            return;
        }

        Session session =
                sessions.remove(
                        viewer.getUniqueId()
                );
        lastReadOnlyNotice.remove(
                viewer.getUniqueId()
        );

        if (session != null) {
            auditClose(
                    viewer,
                    session,
                    "viewer-disconnected"
            );
        }
    }

    public void targetUnavailable(
            Player target,
            boolean died
    ) {
        if (target == null) {
            return;
        }

        UUID targetId =
                target.getUniqueId();

        for (Session session
                : new ArrayList<>(
                sessions.values()
        )) {
            if (!session.targetId
                    .equals(targetId)) {
                continue;
            }

            Player viewer =
                    Bukkit.getPlayer(
                            session.viewerId
                    );

            if (viewer == null
                    || !viewer.isOnline()) {
                sessions.remove(
                        session.viewerId
                );
                continue;
            }

            core.getServer()
                    .getScheduler()
                    .runTask(
                            core,
                            () -> closeForTargetState(
                                    viewer,
                                    session,
                                    died
                            )
                    );
        }
    }

    public void shutdown() {
        if (validationTask != null) {
            validationTask.cancel();
            validationTask = null;
        }

        for (Session session
                : new ArrayList<>(
                sessions.values()
        )) {
            Player viewer =
                    Bukkit.getPlayer(
                            session.viewerId
                    );

            if (viewer != null
                    && viewer.isOnline()) {
                closeIfViewing(
                        viewer,
                        session,
                        InventoryCloseEvent
                                .Reason
                                .PLUGIN
                );
                auditClose(
                        viewer,
                        session,
                        "module-disabled"
                );
            }
        }

        sessions.clear();
        lastReadOnlyNotice.clear();
    }

    private OpenResult authorize(
            Player viewer,
            Player target,
            InspectType type
    ) {
        if (viewer == null
                || !viewer.hasPermission(
                type.permission()
        )) {
            return OpenResult.NO_PERMISSION;
        }

        if (target == null
                || !target.isOnline()
                || target.isDead()) {
            return OpenResult
                    .TARGET_UNAVAILABLE;
        }

        boolean self =
                viewer.getUniqueId()
                        .equals(
                                target.getUniqueId()
                        );

        if (self
                && !viewer.hasPermission(
                type.selfPermission()
        )) {
            return OpenResult.SELF_DENIED;
        }

        /*
         * Hidden targets deliberately look offline to unauthorized viewers so
         * this command cannot be used to discover vanished/hidden players.
         */
        if (!self
                && !viewer.canSee(target)
                && !viewer.hasPermission(
                HIDDEN_PERMISSION
        )) {
            return OpenResult
                    .TARGET_UNAVAILABLE;
        }

        if (!self
                && target.hasPermission(
                PROTECTED_PERMISSION
        )
                && !viewer.hasPermission(
                PROTECTED_BYPASS_PERMISSION
        )) {
            return OpenResult.PROTECTED;
        }

        return OpenResult.SUCCESS;
    }

    private boolean canModify(
            Player viewer,
            InspectType type
    ) {
        return viewer != null
                && viewer.hasPermission(
                type.modifyPermission()
        );
    }

    private Inventory inventory(
            Player target,
            InspectType type
    ) {
        return type == InspectType
                .INVENTORY
                ? target.getInventory()
                : target.getEnderChest();
    }

    private Session session(
            Player viewer,
            InventoryView view
    ) {
        if (viewer == null
                || view == null) {
            return null;
        }

        Session session =
                sessions.get(
                        viewer.getUniqueId()
                );

        if (session == null
                || view.getTopInventory()
                != session.inventory) {
            return null;
        }

        return session;
    }

    private void validateSessions() {
        if (sessions.isEmpty()) {
            return;
        }

        for (Session session
                : new ArrayList<>(
                sessions.values()
        )) {
            Player viewer =
                    Bukkit.getPlayer(
                            session.viewerId
                    );

            if (viewer == null
                    || !viewer.isOnline()) {
                sessions.remove(
                        session.viewerId
                );
                continue;
            }

            if (viewer.getOpenInventory()
                    .getTopInventory()
                    != session.inventory) {
                sessions.remove(
                        session.viewerId
                );
                continue;
            }

            Player target =
                    Bukkit.getPlayer(
                            session.targetId
                    );

            if (authorize(
                    viewer,
                    target,
                    session.type
            ) == OpenResult.SUCCESS) {
                continue;
            }

            sessions.remove(
                    session.viewerId
            );

            closeIfViewing(
                    viewer,
                    session,
                    InventoryCloseEvent
                            .Reason
                            .CANT_USE
            );

            viewer.sendMessage(
                    TextColor.color(
                            message(
                                    "messages.access-changed",
                                    "&cInspection closed — access changed"
                            )
                    )
            );

            auditClose(
                    viewer,
                    session,
                    "access-changed"
            );
        }
    }

    private void closeForTargetState(
            Player viewer,
            Session expected,
            boolean died
    ) {
        Session current =
                sessions.get(
                        expected.viewerId
                );

        if (current != expected) {
            return;
        }

        sessions.remove(
                expected.viewerId
        );

        closeIfViewing(
                viewer,
                expected,
                InventoryCloseEvent
                        .Reason
                        .PLUGIN
        );

        viewer.sendMessage(
                TextColor.color(
                        message(
                                died
                                        ? "messages.target-died"
                                        : "messages.target-left",
                                died
                                        ? "&cInspection closed — that player died"
                                        : "&cInspection closed — that player went offline"
                        )
                )
        );

        SoundService.guiError(
                viewer,
                core
        );

        auditClose(
                viewer,
                expected,
                died
                        ? "target-died"
                        : "target-disconnected"
        );
    }

    private void closeIfViewing(
            Player viewer,
            Session session,
            InventoryCloseEvent.Reason reason
    ) {
        if (viewer.getOpenInventory()
                .getTopInventory()
                == session.inventory) {
            viewer.closeInventory(
                    reason
            );
        }
    }

    private void auditOpen(
            Player viewer,
            Player target,
            InspectType type,
            boolean editable
    ) {
        if (!config.getBoolean(
                "audit.enabled",
                true
        )) {
            return;
        }

        core.getLogger().info(
                "[AdminInspect] "
                        + identity(viewer)
                        + " opened "
                        + type.auditName
                        + " of "
                        + identity(target)
                        + " | mode="
                        + (
                        editable
                                ? "EDITABLE"
                                : "READ_ONLY"
                )
        );
    }

    private void auditClose(
            Player viewer,
            Session session,
            String reason
    ) {
        if (!config.getBoolean(
                "audit.enabled",
                true
        )) {
            return;
        }

        long durationMillis =
                Math.max(
                        0L,
                        System.currentTimeMillis()
                                - session.openedAt
                );

        core.getLogger().info(
                "[AdminInspect] "
                        + identity(viewer)
                        + " closed "
                        + session.type.auditName
                        + " of "
                        + identity(
                        Bukkit.getPlayer(
                                session.targetId
                        ),
                        session.targetId
                )
                        + " | reason="
                        + reason
                        + " | modification-events="
                        + session.modificationEvents
                        + " | duration-ms="
                        + durationMillis
        );
    }

    private String identity(
            Player player
    ) {
        return identity(
                player,
                player == null
                        ? null
                        : player.getUniqueId()
        );
    }

    private String identity(
            Player player,
            UUID fallbackId
    ) {
        String name =
                player == null
                        ? "offline"
                        : player.getName();

        UUID id =
                player == null
                        ? fallbackId
                        : player.getUniqueId();

        return name
                + " ("
                + (
                id == null
                        ? "unknown"
                        : id
        )
                + ")";
    }

    private String safeDetail(
            String detail
    ) {
        if (detail == null
                || detail.isBlank()) {
            return "inventory-change";
        }

        String cleaned =
                detail.replace(
                        '\n',
                        ' '
                )
                        .replace(
                                '\r',
                                ' '
                        )
                        .trim();

        return cleaned.length() <= 240
                ? cleaned
                : cleaned.substring(
                        0,
                        240
                );
    }

    private String message(
            String path,
            String fallback
    ) {
        return config.getString(
                path,
                fallback
        );
    }

    private String replacePlayer(
            String input,
            Player player
    ) {
        return input.replace(
                "%player%",
                DisplayNames.displayName(
                        player
                )
        );
    }

    private void ensureDataFile() {
        if (!core.getDataFolder()
                .exists()
                && !core.getDataFolder()
                .mkdirs()
                && !core.getDataFolder()
                .exists()) {
            throw new IllegalStateException(
                    "Could not create MineacleCore data folder"
            );
        }

        if (!file.exists()) {
            core.saveResource(
                    "admininspect.yml",
                    false
            );
        }

        if (!file.isFile()) {
            throw new IllegalStateException(
                    "Could not initialize admininspect.yml"
            );
        }
    }

    public enum OpenResult {
        SUCCESS,
        USAGE,
        NO_PERMISSION,
        TARGET_UNAVAILABLE,
        SELF_DENIED,
        PROTECTED,
        OPEN_FAILED
    }

    public enum InteractionAccess {
        NONE,
        READ_ONLY,
        EDITABLE,
        UNAUTHORIZED
    }

    public enum InspectType {
        INVENTORY(
                "mineacleadmin.invsee",
                "mineacleadmin.invsee.self",
                "mineacleadmin.invsee.modify",
                "inventory"
        ),
        ENDER_CHEST(
                "mineacleadmin.echest",
                "mineacleadmin.echest.self",
                "mineacleadmin.echest.modify",
                "ender chest"
        );

        private final String permission;
        private final String selfPermission;
        private final String modifyPermission;
        private final String auditName;

        InspectType(
                String permission,
                String selfPermission,
                String modifyPermission,
                String auditName
        ) {
            this.permission = permission;
            this.selfPermission =
                    selfPermission;
            this.modifyPermission =
                    modifyPermission;
            this.auditName = auditName;
        }

        public String permission() {
            return permission;
        }

        public String selfPermission() {
            return selfPermission;
        }

        public String modifyPermission() {
            return modifyPermission;
        }
    }

    private static final class Session {

        private final UUID viewerId;
        private final UUID targetId;
        private final InspectType type;
        private final Inventory inventory;
        private final long openedAt;

        private int modificationEvents;
        private boolean closeQueued;

        private Session(
                UUID viewerId,
                UUID targetId,
                InspectType type,
                Inventory inventory,
                long openedAt
        ) {
            this.viewerId = viewerId;
            this.targetId = targetId;
            this.type = type;
            this.inventory = inventory;
            this.openedAt = openedAt;
        }
    }
}
