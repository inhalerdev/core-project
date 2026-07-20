package net.mineacle.core.chat.command;

import net.mineacle.core.Core;
import net.mineacle.core.chat.service.NicknameService;
import net.mineacle.core.chat.service.NicknameSettings;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.nametag.NametagModule;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class NickCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;
    private final NicknameService nicknameService;
    private final NicknameSettings nicknameSettings;

    public NickCommand(
            Core core,
            NicknameService nicknameService,
            NicknameSettings nicknameSettings
    ) {
        this.core = core;
        this.nicknameService = nicknameService;
        this.nicknameSettings = nicknameSettings;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    core.getMessage("general.players-only")
            );
            return true;
        }

        if (!nicknameSettings.enabled()) {
            error(
                    player,
                    "&cNicknames are currently disabled"
            );
            return true;
        }

        if (!nicknameSettings.canUse(player)) {
            player.sendMessage(TextColor.color(
                    "&cThis is a Mineacle+ feature"
            ));
            player.sendActionBar(TextColor.color(
                    "&cThis is a Mineacle+ feature"
            ));
            SoundService.mineaclePlus(player, core);
            return true;
        }

        if (args.length != 1) {
            error(
                    player,
                    core.getMessage("chat.nick-usage")
            );
            return true;
        }

        String input = args[0];

        if (isReset(input)) {
            if (!nicknameService.hasNickname(player)) {
                error(
                        player,
                        "&cYou do not have a nickname set"
                );
                return true;
            }

            if (!nicknameService.clearNickname(player)) {
                error(
                        player,
                        "&cCould not reset your nickname"
                );
                return true;
            }

            player.sendMessage(TextColor.color(
                    "&#bbbbbbYour nickname has been reset"
            ));
            SoundService.guiConfirm(player, core);
            NametagModule.refresh(player);
            return true;
        }

        NicknameService.NicknameResult result =
                nicknameService.setNicknameDetailed(
                        player,
                        input
                );

        switch (result) {
            case SUCCESS -> {
                player.sendMessage(TextColor.color(
                        "&#bbbbbbYour nickname is now &#bbbbbb"
                                + nicknameService.displayName(player)
                ));
                SoundService.guiConfirm(player, core);
                NametagModule.refresh(player);
            }
            case UNCHANGED -> player.sendMessage(TextColor.color(
                    "&#bbbbbbYour nickname is already &#bbbbbb"
                            + nicknameService.displayName(player)
            ));
            case TAKEN -> error(
                    player,
                    "&cThat nickname is not available"
            );
            case STORAGE_ERROR -> error(
                    player,
                    "&cCould not save your nickname"
            );
            case INVALID -> error(
                    player,
                    core.getMessage("chat.nick-invalid")
                            .replace(
                                    "%max%",
                                    String.valueOf(
                                            nicknameService.maxLength()
                                    )
                            )
            );
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!(sender instanceof Player player)
                || !nicknameSettings.enabled()
                || !nicknameSettings.canUse(player)
                || args.length != 1
                || !nicknameService.hasNickname(player)) {
            return List.of();
        }

        return PlayerTabComplete.options(
                args[0],
                List.of("reset")
        );
    }

    private boolean isReset(String input) {
        String normalized = input.toLowerCase(Locale.ROOT);

        return normalized.equals("reset")
                || normalized.equals("clear")
                || normalized.equals("off");
    }

    private void error(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        SoundService.guiError(player, core);
    }
}
