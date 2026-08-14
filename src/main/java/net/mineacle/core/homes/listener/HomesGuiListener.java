package net.mineacle.core.homes.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.teleport.TeleportService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.homes.gui.ConfirmDeleteHomeGui;
import net.mineacle.core.homes.gui.HomesMainGui;
import net.mineacle.core.homes.service.HomeGuiState;
import net.mineacle.core.homes.service.HomeService;
import net.mineacle.core.teams.TeamsModule;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamHomeService;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Location;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

import java.util.UUID;

@SuppressWarnings("unused")
public final class HomesGuiListener implements Listener {

    private final Core core;
    private final HomeService homeService;
    private final TeleportService teleportService;
    private final HomeGuiState guiState;
    private final TeamHomeService teamHomeService;

    public HomesGuiListener(
            Core core,
            HomeService homeService,
            TeleportService teleportService,
            HomeGuiState guiState
    ) {
        this.core = core;
        this.homeService = homeService;
        this.teleportService = teleportService;
        this.guiState = guiState;
        this.teamHomeService = new TeamHomeService(core);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        HumanEntity whoClicked = event.getWhoClicked();

        if (!(whoClicked instanceof Player player)) {
            return;
        }

        String title = GuiText.plain(event.getView().title());
        String homesTitle = plainTitle("homes.gui.title");
        String deleteTitle = plainTitle(
                "homes.gui.delete-title"
        );
        String teamDeleteTitle = plainTitle(
                "homes.gui.team-delete-title"
        );
        int slot = event.getRawSlot();

        if (title.equals(homesTitle)) {
            event.setCancelled(true);

            if (slot < 0
                    || slot >= event.getView()
                    .getTopInventory()
                    .getSize()) {
                return;
            }

            int bedHomeId = HomesMainGui.homeIdForBedSlot(slot);

            if (bedHomeId > 0) {
                handleHomeBedClick(player, bedHomeId);
                return;
            }

            int dyeHomeId = HomesMainGui.homeIdForDyeSlot(slot);

            if (dyeHomeId > 0) {
                handleHomeDyeClick(player, dyeHomeId);
                return;
            }

            handleTeamHomeClick(player, slot);
            return;
        }

        if (title.equals(deleteTitle)) {
            event.setCancelled(true);
            handlePlayerDeleteConfirm(player, slot);
            return;
        }

        if (title.equals(teamDeleteTitle)) {
            event.setCancelled(true);
            handleTeamHomeDeleteConfirm(player, slot);
        }
    }

    /**
     * ESC/foreign close must not leave an armed delete state behind. The
     * shared MenuHistory listener may reopen the previous menu on the next
     * tick; delete state is deliberately independent from that navigation.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        String title = GuiText.plain(event.getView().title());

        if (title.equals(plainTitle("homes.gui.delete-title"))) {
            guiState.clearPersonal(player);
        } else if (title.equals(
                plainTitle("homes.gui.team-delete-title")
        )) {
            guiState.clearTeam(player);
        }
    }

    private void handleHomeBedClick(Player player, int id) {
        if (homeService.slotLocked(player, id)) {
            sendUpgradeMessage(player);
            return;
        }

        UUID playerId = player.getUniqueId();

        if (homeService.exists(playerId, id)) {
            Location target = homeService.get(playerId, id);

            if (target == null
                    || homeService.personalHomeTeleportBlocked(target)) {
                sendPopup(
                        player,
                        "&cThis home is unavailable right now"
                );
                SoundService.guiError(player, core);
                return;
            }

            SoundService.guiSelect(player, core);
            MenuHistory.close(core, player);
            teleportService.beginLocation(
                    player,
                    homeService.getDisplayName(playerId, id),
                    target,
                    TeleportService.TeleportKind.HOME
            );
            return;
        }

        if (homeService.personalHomeSetBlocked(player)) {
            sendBlockedHomeWorld(player);
            return;
        }

        String defaultName = homeService.getDefaultDisplayName(id);

        if (homeService.nameUnavailableForSlot(
                playerId,
                id,
                defaultName
        )) {
            sendPopup(
                    player,
                    core.getMessage("homes.already-exists")
                            .replace("%home%", defaultName)
            );
            SoundService.guiError(player, core);
            return;
        }

        homeService.set(
                playerId,
                id,
                player.getLocation(),
                defaultName
        );
        sendPopup(
                player,
                core.getMessage("homes.set")
                        .replace(
                                "%home%",
                                homeService.getDisplayName(
                                        playerId,
                                        id
                                )
                        )
        );
        SoundService.homeSet(player, core);
        reopenHomes(player);
    }

    private void handleHomeDyeClick(Player player, int id) {
        if (homeService.slotLocked(player, id)) {
            sendUpgradeMessage(player);
            return;
        }

        UUID playerId = player.getUniqueId();

        if (!homeService.exists(playerId, id)) {
            if (homeService.personalHomeSetBlocked(player)) {
                sendBlockedHomeWorld(player);
                return;
            }

            String defaultName = homeService.getDefaultDisplayName(id);

            if (homeService.nameUnavailableForSlot(
                    playerId,
                    id,
                    defaultName
            )) {
                sendPopup(
                        player,
                        core.getMessage("homes.already-exists")
                                .replace("%home%", defaultName)
                );
                SoundService.guiError(player, core);
                return;
            }

            homeService.set(
                    playerId,
                    id,
                    player.getLocation(),
                    defaultName
            );
            sendPopup(
                    player,
                    core.getMessage("homes.set")
                            .replace(
                                    "%home%",
                                    homeService.getDisplayName(
                                            playerId,
                                            id
                                    )
                            )
            );
            SoundService.homeSet(player, core);
            reopenHomes(player);
            return;
        }

        SoundService.guiClick(player, core);
        guiState.startPersonal(player, id);
        MenuHistory.openChild(
                core,
                player,
                () -> HomesMainGui.open(
                        core,
                        player,
                        homeService
                ),
                () -> ConfirmDeleteHomeGui.openPlayerDelete(
                        core,
                        player,
                        homeService.getDisplayName(playerId, id)
                )
        );
    }

    private void handleTeamHomeClick(Player player, int slot) {
        int bannerSlot = core.getConfig().getInt(
                "homes.team-home.banner-slot",
                10
        );
        int dyeSlot = core.getConfig().getInt(
                "homes.team-home.dye-slot",
                19
        );

        if (slot != bannerSlot && slot != dyeSlot) {
            return;
        }

        TeamService teamService = TeamsModule.teamService();

        if (teamService == null) {
            MenuHistory.close(core, player);
            sendPopup(
                    player,
                    "&cTeams are temporarily unavailable"
            );
            SoundService.guiError(player, core);
            return;
        }

        TeamRecord team = teamService.getTeamByPlayer(
                player.getUniqueId()
        );

        if (team == null) {
            MenuHistory.close(core, player);
            sendCreateTeamPrompt(player);
            SoundService.guiError(player, core);
            return;
        }

        boolean isAdmin = teamService.isAdmin(
                player.getUniqueId()
        );
        boolean isFounder = teamService.isFounder(
                player.getUniqueId()
        );
        boolean hasHome = teamHomeService.hasTeamHome(
                team.teamId()
        );

        if (!hasHome) {
            if (!isAdmin) {
                SoundService.guiError(player, core);
                return;
            }

            if (homeService.canSetTeamHomeHere(player)) {
                teamHomeService.setTeamHome(
                        team.teamId(),
                        player.getLocation()
                );
                sendPopup(
                        player,
                        "&#bbbbbbTeam Home set to your current location"
                );
                SoundService.homeSet(player, core);
                reopenHomes(player);
                return;
            }

            sendBlockedTeamHomeWorld(player);
            return;
        }

        if (slot == bannerSlot) {
            Location home = teamHomeService.getTeamHome(
                    team.teamId()
            );

            if (home == null
                    || homeService.teamHomeTeleportBlocked(home)) {
                sendPopup(
                        player,
                        "&cTeam Home is unavailable right now"
                );
                SoundService.guiError(player, core);
                return;
            }

            SoundService.guiSelect(player, core);
            MenuHistory.close(core, player);
            teleportService.beginLocation(
                    player,
                    "Team Home",
                    home,
                    TeleportService.TeleportKind.TEAM_HOME
            );
            return;
        }

        if (isFounder) {
            SoundService.guiClick(player, core);
            guiState.startTeam(player, team.teamId());
            MenuHistory.openChild(
                    core,
                    player,
                    () -> HomesMainGui.open(
                            core,
                            player,
                            homeService
                    ),
                    () -> ConfirmDeleteHomeGui.openTeamDelete(
                            core,
                            player
                    )
            );
            return;
        }

        sendPopup(
                player,
                "&cOnly the founder can delete Team Home"
        );
        SoundService.guiError(player, core);
    }

    private void handleTeamHomeDeleteConfirm(
            Player player,
            int slot
    ) {
        HomeGuiState.TeamDeleteState state = guiState.team(player);

        if (state == null) {
            MenuHistory.close(core, player);
            SoundService.guiError(player, core);
            return;
        }

        String teamId = state.teamId();

        if (slot == ConfirmDeleteHomeGui.CANCEL_SLOT) {
            guiState.clearTeam(player);
            sendPopup(
                    player,
                    "&cTeam home delete cancelled"
            );
            SoundService.guiCancel(player, core);
            backOrClose(player);
            return;
        }

        if (slot == ConfirmDeleteHomeGui.ACTION_SLOT) {
            return;
        }

        if (slot != ConfirmDeleteHomeGui.CONFIRM_SLOT) {
            return;
        }

        if (!guiState.teamReady(player, teamId)) {
            int timeout = core.getConfig().getInt(
                    "homes.team-home.delete-confirm-timeout-seconds",
                    core.getConfig().getInt(
                            "homes.delete-confirm.timeout-seconds",
                            5
                    )
            );
            long expiresAtNanos = guiState.armTeam(
                    player,
                    teamId,
                    timeout
            );
            sendPopup(
                    player,
                    "&#bbbbbbClick confirm again to continue"
            );
            SoundService.guiConfirm(player, core);
            scheduleTeamDeleteTimeout(
                    player.getUniqueId(),
                    teamId,
                    timeout,
                    expiresAtNanos
            );
            return;
        }

        TeamService teamService = TeamsModule.teamService();

        if (teamService == null) {
            failTeamDelete(
                    player,
                    "&cTeams are temporarily unavailable"
            );
            return;
        }

        TeamRecord currentTeam = teamService.getTeamByPlayer(
                player.getUniqueId()
        );

        if (currentTeam == null
                || !currentTeam.teamId().equals(teamId)
                || !teamService.isFounder(
                player.getUniqueId()
        )) {
            failTeamDelete(
                    player,
                    "&cOnly the founder can delete Team Home"
            );
            return;
        }

        if (!teamHomeService.hasTeamHome(teamId)) {
            failTeamDelete(
                    player,
                    "&cYour team does not have a home set"
            );
            return;
        }

        teamHomeService.deleteTeamHome(teamId);
        guiState.clearTeam(player);
        sendPopup(player, "&cTeam Home deleted");
        SoundService.homeDelete(player, core);
        backOrClose(player);
    }

    private void scheduleTeamDeleteTimeout(
            UUID playerId,
            String teamId,
            int timeoutSeconds,
            long expiresAtNanos
    ) {
        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    Player online = core.getServer().getPlayer(playerId);

                    if (online == null
                            || !guiState.teamConfirmationMatches(
                            online,
                            teamId,
                            expiresAtNanos
                    )) {
                        return;
                    }

                    guiState.startTeam(online, teamId);
                    sendPopup(online, "&cAction timed out");
                    SoundService.guiError(online, core);
                },
                Math.max(1, timeoutSeconds) * 20L
        );
    }

    private void handlePlayerDeleteConfirm(
            Player player,
            int slot
    ) {
        HomeGuiState.PersonalDeleteState state = guiState.personal(player);

        if (state == null) {
            MenuHistory.close(core, player);
            SoundService.guiError(player, core);
            return;
        }

        int id = state.homeId();

        if (homeService.slotLocked(player, id)
                || !homeService.exists(
                player.getUniqueId(),
                id
        )) {
            guiState.clearPersonal(player);
            sendPopup(
                    player,
                    core.getMessage("homes.not-set")
                            .replace(
                                    "%home%",
                                    homeService.getDefaultDisplayName(id)
                            )
            );
            SoundService.guiError(player, core);
            backOrClose(player);
            return;
        }

        String displayName = homeService.getDisplayName(
                player.getUniqueId(),
                id
        );

        if (slot == ConfirmDeleteHomeGui.CANCEL_SLOT) {
            guiState.clearPersonal(player);
            sendPopup(
                    player,
                    core.getMessage("homes.delete-cancelled")
            );
            SoundService.guiCancel(player, core);
            backOrClose(player);
            return;
        }

        if (slot == ConfirmDeleteHomeGui.ACTION_SLOT) {
            return;
        }

        if (slot != ConfirmDeleteHomeGui.CONFIRM_SLOT) {
            return;
        }

        if (guiState.personalReady(player, id)) {
            homeService.delete(player.getUniqueId(), id);
            guiState.clearPersonal(player);
            sendPopup(
                    player,
                    core.getMessage("homes.deleted")
                            .replace("%home%", displayName)
            );
            SoundService.homeDelete(player, core);
            backOrClose(player);
            return;
        }

        int timeout = core.getConfig().getInt(
                "homes.delete-confirm.timeout-seconds",
                5
        );
        long expiresAtNanos = guiState.armPersonal(
                player,
                id,
                timeout
        );
        sendPopup(
                player,
                "&#bbbbbbClick confirm again to continue"
        );
        SoundService.guiConfirm(player, core);
        schedulePersonalDeleteTimeout(
                player.getUniqueId(),
                id,
                timeout,
                expiresAtNanos
        );
    }

    private void schedulePersonalDeleteTimeout(
            UUID playerId,
            int homeId,
            int timeoutSeconds,
            long expiresAtNanos
    ) {
        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    Player online = core.getServer().getPlayer(playerId);

                    if (online == null
                            || !guiState.personalConfirmationMatches(
                            online,
                            homeId,
                            expiresAtNanos
                    )) {
                        return;
                    }

                    guiState.startPersonal(online, homeId);
                    sendPopup(online, "&cAction timed out");
                    SoundService.guiError(online, core);
                },
                Math.max(1, timeoutSeconds) * 20L
        );
    }

    private void failTeamDelete(
            Player player,
            String message
    ) {
        guiState.clearTeam(player);
        sendPopup(player, message);
        SoundService.guiError(player, core);
        backOrClose(player);
    }

    private void reopenHomes(Player player) {
        MenuHistory.openWithoutBackTrigger(
                core,
                player,
                () -> HomesMainGui.open(
                        core,
                        player,
                        homeService
                )
        );
    }

    private void backOrClose(Player player) {
        if (!MenuHistory.back(core, player)) {
            MenuHistory.close(core, player);
        }
    }

    private void sendBlockedHomeWorld(Player player) {
        sendPopup(
                player,
                core.getMessage("homes.blocked-world")
        );
        SoundService.guiError(player, core);
    }

    private void sendBlockedTeamHomeWorld(Player player) {
        sendPopup(
                player,
                core.getMessage("homes.blocked-team-home-world")
        );
        SoundService.guiError(player, core);
    }

    private void sendUpgradeMessage(Player player) {
        MenuHistory.close(core, player);
        player.sendMessage(" ");
        player.sendMessage(
                format(core.getMessage("homes.upgrade-line-1"))
        );
        player.sendMessage(" ");
        player.sendMessage(
                format(core.getMessage("homes.upgrade-line-2"))
        );
        player.sendMessage(" ");
        SoundService.mineaclePlus(player, core);
    }

    private void sendCreateTeamPrompt(Player player) {
        player.sendMessage(
                format("&cYou are not in a team")
        );
        Component clickable = component(
                "&#bbbbbbType "
                        + "&#D0AFFF/team create "
                        + "&#bbbbbbto create a team"
        ).clickEvent(
                ClickEvent.suggestCommand("/team create ")
        );
        player.sendMessage(clickable);
    }

    private void sendPopup(Player player, String message) {
        String formatted = format(message);
        player.sendActionBar(component(formatted));
        player.sendMessage(formatted);
    }

    private String format(String message) {
        return TextColor.color(
                stripTrailingPeriod(message)
        );
    }

    private String plainTitle(String path) {
        return TextColor.strip(
                core.getMessage(path)
        );
    }

    private String stripTrailingPeriod(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }

        String output = message;

        while (output.endsWith(".")) {
            output = output.substring(
                    0,
                    output.length() - 1
            );
        }

        return output;
    }

    private Component component(String message) {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(message)
                );
    }
}
