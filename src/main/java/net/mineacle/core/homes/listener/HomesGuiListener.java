package net.mineacle.core.homes.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.homes.gui.ConfirmDeleteHomeGui;
import net.mineacle.core.homes.gui.HomesMainGui;
import net.mineacle.core.homes.service.HomeGuiState;
import net.mineacle.core.homes.service.HomeService;
import net.mineacle.core.common.teleport.TeleportService;
import net.mineacle.core.teams.TeamsModule;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamHomeService;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Location;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.UUID;

@SuppressWarnings("unused")
public final class HomesGuiListener
        implements Listener {

    private final Core core;
    private final HomeService homeService;
    private final TeleportService teleportService;
    private final HomeGuiState guiState;

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
    }

    @EventHandler
    public void onInventoryClick(
            InventoryClickEvent event
    ) {
        HumanEntity whoClicked =
                event.getWhoClicked();

        if (!(whoClicked
                instanceof Player player)) {
            return;
        }

        String title = GuiText.plain(event.getView().title());
        String homesTitle =
                plainTitle("homes.gui.title");
        String deleteTitle =
                plainTitle(
                        "homes.gui.delete-title"
                );
        String teamDeleteTitle =
                plainTitle(
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

            for (int index = 0;
                 index
                         < HomesMainGui.BED_SLOTS.length;
                 index++) {
                if (slot
                        == HomesMainGui
                        .BED_SLOTS[index]) {
                    handleHomeBedClick(
                            player,
                            index + 1
                    );
                    return;
                }
            }

            for (int index = 0;
                 index
                         < HomesMainGui.DYE_SLOTS.length;
                 index++) {
                if (slot
                        == HomesMainGui
                        .DYE_SLOTS[index]) {
                    handleHomeDyeClick(
                            player,
                            index + 1
                    );
                    return;
                }
            }

            handleTeamHomeClick(
                    player,
                    slot
            );
            return;
        }

        if (title.equals(deleteTitle)) {
            event.setCancelled(true);
            handlePlayerDeleteConfirm(
                    player,
                    slot
            );
            return;
        }

        if (title.equals(teamDeleteTitle)) {
            event.setCancelled(true);
            handleTeamHomeDeleteConfirm(
                    player,
                    slot
            );
        }
    }

    private void handleHomeBedClick(
            Player player,
            int id
    ) {
        UUID playerId =
                player.getUniqueId();

        if (homeService.exists(
                playerId,
                id
        )) {
            Location target =
                    homeService.get(
                            playerId,
                            id
                    );

            if (target == null) {
                sendPopup(
                        player,
                        core.getMessage(
                                "homes.not-set"
                        ).replace(
                                "%home%",
                                homeService
                                        .getDisplayName(
                                                playerId,
                                                id
                                        )
                        )
                );
                SoundService.guiError(
                        player,
                        core
                );
                return;
            }

            SoundService.guiSelect(
                    player,
                    core
            );
            player.closeInventory();

            teleportService.beginLocation(
                    player,
                    homeService.getDisplayName(
                            playerId,
                            id
                    ),
                    target,
                    TeleportService.TeleportKind.HOME
            );
            return;
        }

        if (!homeService
                .hasFreeHomeCapacity(player)) {
            sendUpgradeMessage(player);
            return;
        }

        if (!homeService
                .canSetPersonalHomeHere(player)) {
            sendBlockedHomeWorld(player);
            return;
        }

        homeService.set(
                playerId,
                id,
                player.getLocation(),
                homeService
                        .getDefaultDisplayName(id)
        );
        sendPopup(
                player,
                core.getMessage(
                        "homes.set"
                ).replace(
                        "%home%",
                        homeService.getDisplayName(
                                playerId,
                                id
                        )
                )
        );
        SoundService.homeSet(
                player,
                core
        );
        HomesMainGui.open(
                core,
                player,
                homeService
        );
    }

    private void handleHomeDyeClick(
            Player player,
            int id
    ) {
        UUID playerId =
                player.getUniqueId();

        if (!homeService.exists(
                playerId,
                id
        )) {
            if (!homeService
                    .hasFreeHomeCapacity(
                            player
                    )) {
                sendUpgradeMessage(player);
                return;
            }

            if (!homeService
                    .canSetPersonalHomeHere(
                            player
                    )) {
                sendBlockedHomeWorld(player);
                return;
            }

            homeService.set(
                    playerId,
                    id,
                    player.getLocation(),
                    homeService
                            .getDefaultDisplayName(id)
            );
            sendPopup(
                    player,
                    core.getMessage(
                            "homes.set"
                    ).replace(
                            "%home%",
                            homeService
                                    .getDisplayName(
                                            playerId,
                                            id
                                    )
                    )
            );
            SoundService.homeSet(
                    player,
                    core
            );
            HomesMainGui.open(
                    core,
                    player,
                    homeService
            );
            return;
        }

        SoundService.guiClick(
                player,
                core
        );
        guiState.startPersonal(player, id);
        ConfirmDeleteHomeGui.openPlayerDelete(
                core,
                player,
                homeService.getDisplayName(playerId, id)
        );
    }

    private void handleTeamHomeClick(
            Player player,
            int slot
    ) {
        int bannerSlot =
                core.getConfig().getInt(
                        "homes.team-home.banner-slot",
                        10
                );
        int dyeSlot =
                core.getConfig().getInt(
                        "homes.team-home.dye-slot",
                        19
                );

        if (slot != bannerSlot
                && slot != dyeSlot) {
            return;
        }

        TeamService teamService =
                TeamsModule.teamService();

        if (teamService == null) {
            player.closeInventory();
            sendPopup(
                    player,
                    "&cTeams are temporarily unavailable"
            );
            SoundService.guiError(
                    player,
                    core
            );
            return;
        }

        TeamHomeService teamHomeService =
                new TeamHomeService(core);
        TeamRecord team =
                teamService.getTeamByPlayer(
                        player.getUniqueId()
                );

        if (team == null) {
            player.closeInventory();
            sendCreateTeamPrompt(player);
            SoundService.guiError(
                    player,
                    core
            );
            return;
        }

        boolean isAdmin =
                teamService.isAdmin(
                        player.getUniqueId()
                );
        boolean isFounder =
                teamService.isFounder(
                        player.getUniqueId()
                );
        boolean hasHome =
                teamHomeService.hasTeamHome(
                        team.teamId()
                );

        if (!hasHome) {
            if (!isAdmin) {
                SoundService.guiError(
                        player,
                        core
                );
                return;
            }

            if (!homeService
                    .canSetTeamHomeHere(
                            player
                    )) {
                sendBlockedTeamHomeWorld(
                        player
                );
                return;
            }

            teamHomeService.setTeamHome(
                    team.teamId(),
                    player.getLocation()
            );
            sendPopup(
                    player,
                    "&#bbbbbbTeam Home set to your current location"
            );
            SoundService.homeSet(
                    player,
                    core
            );
            HomesMainGui.open(
                    core,
                    player,
                    homeService
            );
            return;
        }

        if (slot == bannerSlot) {
            Location home =
                    teamHomeService.getTeamHome(
                            team.teamId()
                    );

            if (home == null) {
                SoundService.guiError(
                        player,
                        core
                );
                return;
            }

            SoundService.guiSelect(
                    player,
                    core
            );
            player.closeInventory();

            teleportService.beginLocation(
                    player,
                    "Team Home",
                    home,
                    TeleportService
                            .TeleportKind
                            .TEAM_HOME
            );
            return;
        }

        if (isFounder) {
            SoundService.guiClick(player, core);
            guiState.startTeam(player, team.teamId());
            ConfirmDeleteHomeGui.openTeamDelete(core, player);
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
            player.closeInventory();
            SoundService.guiError(player, core);
            return;
        }

        String teamId = state.teamId();

        if (slot == ConfirmDeleteHomeGui.CANCEL_SLOT) {
            guiState.clearTeam(player);
            player.closeInventory();
            HomesMainGui.open(core, player, homeService);
            sendPopup(player, "&cTeam home delete cancelled");
            SoundService.guiCancel(player, core);
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
                    "homes.delete-confirm.timeout-seconds",
                    5
            );
            long confirmationExpiresAt =
                    guiState.armTeam(player, teamId, timeout);
            sendPopup(
                    player,
                    "&#bbbbbbClick &#D0AFFFconfirm again &#bbbbbbto continue"
            );
            SoundService.guiConfirm(player, core);
            scheduleTeamDeleteTimeout(
                    player.getUniqueId(),
                    teamId,
                    timeout,
                    confirmationExpiresAt
            );
            return;
        }

        TeamService teamService = TeamsModule.teamService();

        if (teamService == null) {
            guiState.clearTeam(player);
            player.closeInventory();
            sendPopup(player, "&cTeams are temporarily unavailable");
            SoundService.guiError(player, core);
            return;
        }

        TeamHomeService teamHomeService = new TeamHomeService(core);

        if (!teamHomeService.deleteTeamHome(teamId)) {
            guiState.clearTeam(player);
            player.closeInventory();
            sendPopup(player, "&cYour team does not have a home set");
            SoundService.guiError(player, core);
            HomesMainGui.open(core, player, homeService);
            return;
        }

        guiState.clearTeam(player);
        player.closeInventory();
        sendPopup(player, "&cTeam Home deleted");
        SoundService.homeDelete(player, core);
        HomesMainGui.open(core, player, homeService);
    }

    private void scheduleTeamDeleteTimeout(
            UUID playerId,
            String teamId,
            int timeoutSeconds,
            long confirmationExpiresAt
    ) {
        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    Player online = core.getServer().getPlayer(playerId);
                    if (online == null
                            || !guiState.teamConfirmationMatches(
                            online,
                            teamId,
                            confirmationExpiresAt
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
            player.closeInventory();
            SoundService.guiError(player, core);
            return;
        }

        int id = state.homeId();
        String displayName = homeService.getDisplayName(
                player.getUniqueId(),
                id
        );

        if (slot == ConfirmDeleteHomeGui.CANCEL_SLOT) {
            guiState.clearPersonal(player);
            player.closeInventory();
            HomesMainGui.open(core, player, homeService);
            sendPopup(player, core.getMessage("homes.delete-cancelled"));
            SoundService.guiCancel(player, core);
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
            player.closeInventory();
            sendPopup(
                    player,
                    core.getMessage("homes.deleted")
                            .replace("%home%", displayName)
            );
            SoundService.homeDelete(player, core);
            return;
        }

        int timeout = core.getConfig().getInt(
                "homes.delete-confirm.timeout-seconds",
                5
        );
        long confirmationExpiresAt =
                guiState.armPersonal(player, id, timeout);
        sendPopup(
                player,
                "&#bbbbbbClick &#D0AFFFconfirm again &#bbbbbbto continue"
        );
        SoundService.guiConfirm(player, core);
        schedulePersonalDeleteTimeout(
                player.getUniqueId(),
                id,
                timeout,
                confirmationExpiresAt
        );
    }

    private void schedulePersonalDeleteTimeout(
            UUID playerId,
            int homeId,
            int timeoutSeconds,
            long confirmationExpiresAt
    ) {
        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    Player online = core.getServer().getPlayer(playerId);
                    if (online == null
                            || !guiState.personalConfirmationMatches(
                            online,
                            homeId,
                            confirmationExpiresAt
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

    private void sendBlockedHomeWorld(
            Player player
    ) {
        sendPopup(
                player,
                core.getMessage(
                        "homes.blocked-world"
                )
        );
        SoundService.guiError(
                player,
                core
        );
    }

    private void sendBlockedTeamHomeWorld(
            Player player
    ) {
        sendPopup(
                player,
                core.getMessage(
                        "homes.blocked-team-home-world"
                )
        );
        SoundService.guiError(
                player,
                core
        );
    }

    private void sendUpgradeMessage(
            Player player
    ) {
        player.closeInventory();
        player.sendMessage(" ");
        player.sendMessage(
                format(
                        core.getMessage(
                                "homes.upgrade-line-1"
                        )
                )
        );
        player.sendMessage(" ");
        player.sendMessage(
                format(
                        core.getMessage(
                                "homes.upgrade-line-2"
                        )
                )
        );
        player.sendMessage(" ");
        SoundService.guiError(
                player,
                core
        );
    }

    private void sendCreateTeamPrompt(
            Player player
    ) {
        player.sendMessage(
                format(
                        "&cYou are not in a team"
                )
        );
        Component clickable =
                component(
                        "&#bbbbbbType "
                                + "&#8436FE/team create "
                                + "&#bbbbbbto create a team"
                ).clickEvent(
                        ClickEvent.suggestCommand(
                                "/team create "
                        )
                );
        player.sendMessage(clickable);
    }

    private void sendPopup(
            Player player,
            String message
    ) {
        String formatted =
                format(message);
        player.sendActionBar(
                component(formatted)
        );
        player.sendMessage(formatted);
    }

    private String format(String message) {
        return TextColor.color(
                stripTrailingPeriod(
                        message
                )
        );
    }

    private String plainTitle(String path) {
        return TextColor.strip(
                core.getMessage(path)
        );
    }

    private String stripTrailingPeriod(
            String message
    ) {
        if (message == null
                || message.isBlank()) {
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

    private Component component(
            String message
    ) {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(message)
                );
    }
}
