package net.mineacle.core.bounty.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.bounty.gui.BountyConfirmGui;
import net.mineacle.core.bounty.gui.BountyMainGui;
import net.mineacle.core.bounty.service.BountyService;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BountySearchInputListener
        implements Listener {

    private static final long TIMEOUT_TICKS =
            20L * 45L;
    private static final int MAX_INPUT_LENGTH = 32;

    private final Core core;
    private final BountyService bountyService;
    private final Map<UUID, InputPrompt>
            prompts =
            new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask>
            timeouts =
            new ConcurrentHashMap<>();

    public BountySearchInputListener(
            Core core,
            BountyService bountyService
    ) {
        this.core = core;
        this.bountyService = bountyService;
    }

    public void beginSearch(
            Player player,
            int page
    ) {
        begin(
                player,
                new InputPrompt(
                        InputType.SEARCH,
                        page,
                        null
                )
        );

        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbType a player name to search"
                )
        );
        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbType &#D0AFFFcancel "
                                + "&#bbbbbbto return or "
                                + "&#D0AFFFclear "
                                + "&#bbbbbbto reset"
                )
        );
        sendActionBar(
                player,
                "&#bbbbbbSearch bounties"
        );
    }

    public void beginPlaceTarget(
            Player player,
            int page
    ) {
        begin(
                player,
                new InputPrompt(
                        InputType.PLACE_TARGET,
                        page,
                        null
                )
        );

        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbType the player to place a bounty on"
                )
        );
        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbType &#D0AFFFcancel &#bbbbbbto return"
                )
        );
        sendActionBar(
                player,
                "&#bbbbbbChoose a bounty target"
        );
    }

    public void beginAmount(
            Player player,
            int page,
            UUID targetId
    ) {
        if (targetId == null) {
            error(
                    player,
                    "&cThat player could not be found"
            );
            reopen(
                    player,
                    page
            );
            return;
        }

        OfflinePlayer target =
                Bukkit.getOfflinePlayer(
                        targetId
                );

        if (targetId.equals(
                player.getUniqueId()
        )) {
            error(
                    player,
                    "&cYou cannot place a bounty on yourself"
            );
            reopen(
                    player,
                    page
            );
            return;
        }

        begin(
                player,
                new InputPrompt(
                        InputType.PLACE_AMOUNT,
                        page,
                        targetId
                )
        );

        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbTarget: &#B078FF"
                                + bountyService.displayName(
                                target
                        )
                )
        );
        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbType the bounty amount or &#D0AFFFcancel"
                )
        );
        sendActionBar(
                player,
                "&#bbbbbbEnter bounty amount"
        );
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.LOWEST
    )
    public void onChat(
            AsyncChatEvent event
    ) {
        Player player =
                event.getPlayer();
        InputPrompt prompt =
                prompts.remove(
                        player.getUniqueId()
                );

        if (prompt == null) {
            return;
        }

        event.setCancelled(true);
        cancelTimeout(
                player.getUniqueId()
        );

        String input =
                sanitize(
                        PlainTextComponentSerializer
                                .plainText()
                                .serialize(
                                        event.message()
                                )
                );

        core.getServer()
                .getScheduler()
                .runTask(
                        core,
                        () -> handleInput(
                                player,
                                prompt,
                                input
                        )
                );
    }

    @EventHandler
    public void onQuit(
            PlayerQuitEvent event
    ) {
        UUID playerId =
                event.getPlayer()
                        .getUniqueId();

        prompts.remove(playerId);
        cancelTimeout(playerId);
        BountyMainGui.clearState(
                event.getPlayer()
        );
    }

    public void shutdown() {
        for (BukkitTask task
                : timeouts.values()) {
            task.cancel();
        }

        timeouts.clear();
        prompts.clear();
    }

    private void begin(
            Player player,
            InputPrompt prompt
    ) {
        UUID playerId =
                player.getUniqueId();

        prompts.put(
                playerId,
                prompt
        );
        cancelTimeout(playerId);

        BukkitTask timeout =
                core.getServer()
                        .getScheduler()
                        .runTaskLater(
                                core,
                                () -> expire(
                                        playerId,
                                        prompt
                                ),
                                TIMEOUT_TICKS
                        );

        timeouts.put(
                playerId,
                timeout
        );

        MenuHistory.closeForInput(
                core,
                player
        );
    }

    private void expire(
            UUID playerId,
            InputPrompt expected
    ) {
        if (!prompts.remove(
                playerId,
                expected
        )) {
            return;
        }

        timeouts.remove(playerId);

        Player player =
                Bukkit.getPlayer(
                        playerId
                );

        if (player == null
                || !player.isOnline()) {
            return;
        }

        error(
                player,
                "&cBounty input timed out"
        );
        reopen(
                player,
                expected.page()
        );
    }

    private void handleInput(
            Player player,
            InputPrompt prompt,
            String input
    ) {
        if (!player.isOnline()) {
            return;
        }

        if (input.equalsIgnoreCase(
                "cancel"
        )
                || input.equalsIgnoreCase(
                "cancelled"
        )) {
            SoundService.guiCancel(
                    player,
                    core
            );
            reopen(
                    player,
                    prompt.page()
            );
            return;
        }

        switch (prompt.type()) {
            case SEARCH ->
                    handleSearch(
                            player,
                            prompt,
                            input
                    );
            case PLACE_TARGET ->
                    handleTarget(
                            player,
                            prompt,
                            input
                    );
            case PLACE_AMOUNT ->
                    handleAmount(
                            player,
                            prompt,
                            input
                    );
        }
    }

    private void handleSearch(
            Player player,
            InputPrompt prompt,
            String input
    ) {
        if (input.equalsIgnoreCase(
                "clear"
        )) {
            BountyMainGui.clearSearch(
                    player
            );
            SoundService.guiCancel(
                    player,
                    core
            );
            reopen(
                    player,
                    0
            );
            return;
        }

        if (input.isBlank()) {
            error(
                    player,
                    "&cSearch cannot be empty"
            );
            reopen(
                    player,
                    prompt.page()
            );
            return;
        }

        String displayLabel =
                bountyService
                        .displaySearchLabel(
                                input
                        );

        BountyMainGui.setSearch(
                player,
                input,
                displayLabel
        );

        if (!bountyService.hasMatches(
                BountyMainGui.sortMode(
                        player
                ),
                BountyMainGui.search(
                        player
                )
        )) {
            sendActionBar(
                    player,
                    "&cNo bounty target found"
            );
        }

        SoundService.guiSearch(
                player,
                core
        );
        reopen(
                player,
                0
        );
    }

    private void handleTarget(
            Player player,
            InputPrompt prompt,
            String input
    ) {
        if (input.isBlank()) {
            error(
                    player,
                    "&cPlayer name cannot be empty"
            );
            reopen(
                    player,
                    prompt.page()
            );
            return;
        }

        OfflinePlayer target =
                bountyService.resolveTarget(
                        input
                );

        if (target == null) {
            error(
                    player,
                    "&cThat player could not be found"
            );
            reopen(
                    player,
                    prompt.page()
            );
            return;
        }

        beginAmount(
                player,
                prompt.page(),
                target.getUniqueId()
        );
    }

    private void handleAmount(
            Player player,
            InputPrompt prompt,
            String input
    ) {
        UUID targetId =
                prompt.targetId();

        if (targetId == null) {
            error(
                    player,
                    "&cThat player could not be found"
            );
            reopen(
                    player,
                    prompt.page()
            );
            return;
        }

        long amount =
                bountyService.parseAmount(
                        input
                );

        if (amount <= 0L) {
            error(
                    player,
                    "&cEnter a valid bounty amount"
            );
            reopen(
                    player,
                    prompt.page()
            );
            return;
        }

        long minimum =
                bountyService.minimumCents();

        if (amount < minimum) {
            error(
                    player,
                    "&cMinimum bounty is &a"
                            + bountyService.format(
                            minimum
                    )
            );
            reopen(
                    player,
                    prompt.page()
            );
            return;
        }

        if (bountyService.wouldExceedMaximum(
                targetId,
                amount
        )) {
            error(
                    player,
                    "&cMaximum bounty is &a"
                            + bountyService.format(
                            bountyService
                                    .maximumCents()
                    )
            );
            reopen(
                    player,
                    prompt.page()
            );
            return;
        }

        EconomyService economy =
                EconomyModule.economyService();

        if (economy == null) {
            error(
                    player,
                    "&cEconomy is not available"
            );
            reopen(
                    player,
                    prompt.page()
            );
            return;
        }

        if (!economy.has(
                player.getUniqueId(),
                amount
        )) {
            error(
                    player,
                    "&cYou do not have enough money"
            );
            reopen(
                    player,
                    prompt.page()
            );
            return;
        }

        OfflinePlayer target =
                Bukkit.getOfflinePlayer(
                        targetId
                );

        MenuHistory.openWithoutBackTrigger(
                core,
                player,
                () -> BountyConfirmGui.open(
                        player,
                        target,
                        amount,
                        prompt.page(),
                        bountyService
                )
        );
    }

    private void reopen(
            Player player,
            int page
    ) {
        MenuHistory.openWithoutBackTrigger(
                core,
                player,
                () -> BountyMainGui.open(
                        player,
                        bountyService,
                        page
                )
        );
    }

    private String sanitize(
            String input
    ) {
        if (input == null) {
            return "";
        }

        StringBuilder clean =
                new StringBuilder(
                        Math.min(
                                input.length(),
                                MAX_INPUT_LENGTH
                        )
                );

        String plain =
                TextColor.strip(input);

        for (int index = 0;
             index < plain.length();
             index++) {
            char character =
                    plain.charAt(index);

            if (Character.isISOControl(
                    character
            )) {
                continue;
            }

            clean.append(character);

            if (clean.length()
                    >= MAX_INPUT_LENGTH) {
                break;
            }
        }

        return clean.toString()
                .trim();
    }

    private void cancelTimeout(
            UUID playerId
    ) {
        BukkitTask task =
                timeouts.remove(
                        playerId
                );

        if (task != null) {
            task.cancel();
        }
    }

    private void error(
            Player player,
            String message
    ) {
        player.sendMessage(
                TextColor.color(message)
        );
        sendActionBar(
                player,
                message
        );
        SoundService.guiError(
                player,
                core
        );
    }

    private void sendActionBar(
            Player player,
            String message
    ) {
        player.sendActionBar(
                GuiText.component(message)
        );
    }

    private enum InputType {
        SEARCH,
        PLACE_TARGET,
        PLACE_AMOUNT
    }

    private record InputPrompt(
            InputType type,
            int page,
            UUID targetId
    ) {
    }
}
