package net.mineacle.core.chat.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.chat.service.ChatService;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class ChatFormatListener implements Listener {

    private final Core core;
    private final ChatService chatService;
    private final TeamService teamService;

    public ChatFormatListener(Core core, ChatService chatService, TeamService teamService) {
        this.core = core;
        this.chatService = chatService;
        this.teamService = teamService;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();

        if (teamService != null && teamService.isTeamChatEnabled(sender.getUniqueId())) {
            return;
        }

        event.setCancelled(true);

        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        Bukkit.getScheduler().runTask(core, () -> {
            String formatted = chatService.formatChat(sender, message);

            for (Player recipient : chatService.chatRecipients(sender)) {
                recipient.sendMessage(formatted);
            }

            core.getServer().getConsoleSender().sendMessage(formatted);
        });
    }
}