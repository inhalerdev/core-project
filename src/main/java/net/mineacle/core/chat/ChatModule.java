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
import net.mineacle.core.teams.TeamsModule;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

public final class ChatModule extends Module {

    private static NicknameService nicknameService;
    private static ChatService chatService;

    public static NicknameService nicknameService() {
        return nicknameService;
    }

    public static ChatService chatService() {
        return chatService;
    }

    @Override
    public String name() {
        return "Chat";
    }

    @Override
    public void enable(Core core) {
        nicknameService = new NicknameService(core);
        chatService = new ChatService(core, nicknameService);

        TeamService teamService = TeamsModule.teamService();

        MessageCommand messageCommand = new MessageCommand(core, chatService);
        ReplyCommand replyCommand = new ReplyCommand(core, chatService);
        IgnoreCommand ignoreCommand = new IgnoreCommand(core, chatService);
        IgnoreListCommand ignoreListCommand = new IgnoreListCommand(core, chatService);
        NickCommand nickCommand = new NickCommand(core, nicknameService);
        RealNameCommand realNameCommand = new RealNameCommand(core, nicknameService);

        register(core, "msg", messageCommand);
        register(core, "r", replyCommand);
        register(core, "ignore", ignoreCommand);
        register(core, "ignorelist", ignoreListCommand);
        register(core, "nick", nickCommand);
        register(core, "realname", realNameCommand);

        core.getServer().getPluginManager().registerEvents(
                new ChatFormatListener(core, chatService, teamService),
                core
        );

        core.getServer().getPluginManager().registerEvents(
                new JoinQuitMessageListener(),
                core
        );
    }

    @Override
    public void disable() {
        if (chatService != null) {
            chatService.save();
        }

        if (nicknameService != null) {
            nicknameService.save();
        }

        chatService = null;
        nicknameService = null;
    }

    private void register(Core core, String commandName, CommandExecutor executor) {
        PluginCommand command = core.getCommand(commandName);

        if (command == null) {
            core.getLogger().warning("Missing command in plugin.yml: " + commandName);
            return;
        }

        command.setExecutor(executor);

        if (executor instanceof TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }
}