package net.mineacle.core.nametag;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.collision.CollisionModule;
import net.mineacle.core.collision.PlayerCollisionService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

public final class NametagModule extends Module {

    private static NametagService service;

    private BukkitTask auditTask;
    private EventSubscription<UserDataRecalculateEvent>
            rankSubscription;

    @Override
    public String name() {
        return "Nametags";
    }

    @Override
    public void enable(Core core) {
        PlayerCollisionService collisionService =
                CollisionModule.service();

        if (collisionService == null) {
            throw new IllegalStateException(
                    "Nametags requires the Collision module"
            );
        }

        service = new NametagService(
                core,
                collisionService
        );

        PluginCommand command =
                core.getCommand("mineaclenametags");

        if (command == null) {
            service.clear();
            service = null;
            throw new IllegalStateException(
                    "Missing command in plugin.yml: "
                            + "mineaclenametags"
            );
        }

        NametagCommand executor =
                new NametagCommand(core, service);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        core.getServer()
                .getPluginManager()
                .registerEvents(
                        new NametagListener(
                                core,
                                service
                        ),
                        core
                );

        subscribeToRankChanges(core);

        auditTask = core.getServer()
                .getScheduler()
                .runTaskTimer(
                        core,
                        service::audit,
                        service.auditTicks(),
                        service.auditTicks()
                );

        service.refreshAll();
    }

    @Override
    public void disable() {
        if (rankSubscription != null) {
            rankSubscription.close();
            rankSubscription = null;
        }

        if (auditTask != null) {
            auditTask.cancel();
            auditTask = null;
        }

        if (service != null) {
            service.clear();
            service = null;
        }
    }

    public static void refreshAll() {
        if (service != null) {
            service.refreshAll();
        }
    }

    public static void refresh(Player player) {
        if (service != null && player != null) {
            service.refresh(player);
        }
    }

    private void subscribeToRankChanges(Core core) {
        LuckPerms luckPerms = LuckPermsProvider.get();

        rankSubscription = luckPerms
                .getEventBus()
                .subscribe(
                        core,
                        UserDataRecalculateEvent.class,
                        event -> {
                            UUID playerId = event
                                    .getUser()
                                    .getUniqueId();

                            core.getServer()
                                    .getScheduler()
                                    .runTask(
                                            core,
                                            () -> {
                                                Player player =
                                                        Bukkit.getPlayer(
                                                                playerId
                                                        );

                                                if (player != null
                                                        && player.isOnline()
                                                        && service != null) {
                                                    service.refresh(
                                                            player
                                                    );
                                                }
                                            }
                                    );
                        }
                );
    }
}
