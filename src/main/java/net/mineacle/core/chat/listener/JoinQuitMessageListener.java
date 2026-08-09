package net.mineacle.core.chat.listener;

import net.mineacle.core.Core;
import net.mineacle.core.chat.service.ChatService;
import net.mineacle.core.chat.service.NicknameService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class JoinQuitMessageListener implements Listener {

    private final Core core;
    private final ChatService chatService;
    private final NicknameService nicknameService;

    public JoinQuitMessageListener(
            Core core,
            ChatService chatService,
            NicknameService nicknameService
    ) {
        this.core = core;
        this.chatService = chatService;
        this.nicknameService = nicknameService;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        event.joinMessage(null);

        Player player = event.getPlayer();

        core.getServer().getScheduler().runTask(
                core,
                () -> {
                    if (player.isOnline()) {
                        nicknameService.updatePlayerDisplay(player);
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        event.quitMessage(null);

        Player player = event.getPlayer();
        chatService.cleanupPlayer(player.getUniqueId());
        nicknameService.cleanupPlayer(player.getUniqueId());
    }
}
