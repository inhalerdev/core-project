package net.mineacle.core.webprofiles.command;

import net.mineacle.core.webprofiles.auth.WebVerificationRepository;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VerifyCommand
        implements CommandExecutor, TabCompleter {

    private final Core.Core core;
    private final WebVerificationRepository repository;
    private final Set<UUID> pending =
            ConcurrentHashMap.newKeySet();

    public VerifyCommand(
            Core.Core core,
            WebVerificationRepository repository
    ) {
        this.core = core;
        this.repository = repository;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    core.getMessageText(
                            "&cOnly players can use this command"
                    )
            );
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(
                    core.getMessageText(
                            "&#bbbbbbUse &#ff88ff/verify <code>"
                    )
            );
            return true;
        }

        UUID uuid = player.getUniqueId();

        if (!pending.add(uuid)) {
            player.sendMessage(
                    core.getMessageText(
                            "&#bbbbbbYour verification is already being checked"
                    )
            );
            return true;
        }

        String code = args[0];
        String username = player.getName();

        player.sendMessage(
                core.getMessageText(
                        "&#bbbbbbChecking your website verification code"
                )
        );

        core.getServer().getScheduler().runTaskAsynchronously(
                core,
                () -> {
                    WebVerificationRepository.VerificationResult result =
                            repository.verify(
                                    uuid,
                                    username,
                                    code
                            );

                    core.getServer().getScheduler().runTask(
                            core,
                            () -> {
                                pending.remove(uuid);
                                Player online = core.getServer()
                                        .getPlayer(uuid);

                                if (online == null) {
                                    return;
                                }

                                online.sendMessage(
                                        core.getMessageText(
                                                message(result)
                                        )
                                );
                            }
                    );
                }
        );

        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        return List.of();
    }

    private String message(
            WebVerificationRepository.VerificationResult result
    ) {
        return switch (result) {
            case VERIFIED ->
                    "&#62c249Website account verified &#bbbbbbReturn to Mineacle.net to create your password";
            case WRONG_PLAYER ->
                    "&cThis verification code belongs to a different Minecraft account";
            case EXPIRED ->
                    "&cThis verification code expired &#bbbbbbGenerate a new one on Mineacle.net";
            case ALREADY_USED ->
                    "&cThis verification code has already been used";
            case DISABLED ->
                    "&cWebsite verification is currently disabled";
            case ERROR ->
                    "&cWebsite verification is temporarily unavailable";
            case INVALID_CODE ->
                    "&cInvalid verification code &#bbbbbbCopy the command directly from Mineacle.net";
        };
    }
}
