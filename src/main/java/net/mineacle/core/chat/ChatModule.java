package net.mineacle.core.chat;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.chat.command.IgnoreCommand;
import net.mineacle.core.chat.command.IgnoreListCommand;
import net.mineacle.core.chat.command.MessageCommand;
import net.mineacle.core.chat.command.NickCommand;
import net.mineacle.core.chat.command.RealNameCommand;
import net.mineacle.core.chat.command.ReplyCommand;
import net.mineacle.core.chat.listener.ChatFormatListener;
import net.mineacle.core.chat.listener.JoinQuitMessageListener;
import net.mineacle.core.chat.service.ChatService;
import net.mineacle.core.chat.service.NicknameService;
import net.mineacle.core.chat.service.NicknameSettings;
import net.mineacle.core.teams.TeamsModule;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class ChatModule extends Module {

    private static NicknameService nicknameService;
    private static ChatService chatService;
    private static NicknameSettings nicknameSettings;

    public static NicknameService nicknameService() {
        return nicknameService;
    }

    public static ChatService chatService() {
        return chatService;
    }

    public static NicknameSettings nicknameSettings() {
        return nicknameSettings;
    }

    @Override
    public String name() {
        return "Chat";
    }

    @Override
    public void enable(Core core) throws Exception {
        nicknameSettings = new NicknameSettings(core);
        nicknameService = new NicknameService(core);
        chatService = new ChatService(core, nicknameService);

        TeamService teamService = TeamsModule.teamService();

        register(
                core,
                "msg",
                new MessageCommand(core, chatService)
        );
        register(
                core,
                "r",
                new ReplyCommand(core, chatService)
        );
        register(
                core,
                "ignore",
                new IgnoreCommand(core, chatService)
        );
        register(
                core,
                "ignorelist",
                new IgnoreListCommand(core, chatService)
        );
        registerNick(
                core,
                new NickCommand(
                        core,
                        nicknameService,
                        nicknameSettings
                )
        );
        register(
                core,
                "realname",
                new RealNameCommand(core, nicknameService)
        );

        core.getServer().getPluginManager().registerEvents(
                new ChatFormatListener(
                        core,
                        chatService,
                        teamService
                ),
                core
        );
        core.getServer().getPluginManager().registerEvents(
                new JoinQuitMessageListener(
                        core,
                        chatService,
                        nicknameService
                ),
                core
        );

        for (Player player : core.getServer().getOnlinePlayers()) {
            nicknameService.updatePlayerDisplay(player);
        }
    }

    @Override
    public void disable() {
        if (chatService != null) {
            chatService.shutdown();
        }

        if (nicknameService != null) {
            nicknameService.shutdown();
        }

        chatService = null;
        nicknameService = null;
        nicknameSettings = null;
    }

    private void registerNick(
            Core core,
            NickCommand executor
    ) {
        PluginCommand command = requiredCommand(core, "nick");

        /*
         * Runtime permission handling is intentional. Plus players receive
         * mineacle.plus, while staff may receive mineaclechat.nick directly.
         * A plugin.yml-only permission would incorrectly block one group.
         */
        command.setPermission(null);
        command.setPermissionMessage(null);
        command.setUsage("/nick <nickname|reset>");
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void register(
            Core core,
            String commandName,
            CommandExecutor executor
    ) {
        PluginCommand command = requiredCommand(core, commandName);
        command.setExecutor(executor);

        if (executor instanceof TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }

    private PluginCommand requiredCommand(
            Core core,
            String commandName
    ) {
        PluginCommand command = core.getCommand(commandName);

        if (command == null) {
            throw new IllegalStateException(
                    "Missing command in plugin.yml: " + commandName
            );
        }

        return command;
    }
}
