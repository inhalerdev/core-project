package net.mineacle.core.collision;

import net.mineacle.core.Core;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class PlayerCollisionService {

    private final Core core;

    private volatile Settings settings;

    public PlayerCollisionService(Core core) {
        this.core = core;
        reload();
    }

    public void reload() {
        boolean enabled = core.getConfig().getBoolean(
                "player-collision.enabled",
                true
        );
        Set<String> disabledWorlds =
                core.getConfig().getStringList(
                                "player-collision.disabled-worlds"
                        )
                        .stream()
                        .filter(value ->
                                value != null
                                        && !value.isBlank())
                        .map(value ->
                                value.trim()
                                        .toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        List<Long> reapplyDelays =
                core.getConfig().getLongList(
                                "player-collision.reapply-delay-ticks"
                        )
                        .stream()
                        .filter(value -> value != null
                                && value >= 0L)
                        .distinct()
                        .sorted()
                        .toList();

        if (reapplyDelays.isEmpty()) {
            reapplyDelays = List.of(1L, 20L);
        }

        settings = new Settings(
                enabled,
                disabledWorlds,
                reapplyDelays
        );
    }

    public void apply(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        apply(
                player,
                player.getWorld()
        );
    }

    public void apply(
            Player player,
            World world
    ) {
        if (player == null || world == null) {
            return;
        }

        Settings current = settings;
        boolean collidable = !current.enabled()
                || !current.disabledWorlds()
                .contains(
                        world.getName()
                                .toLowerCase(Locale.ROOT)
                );

        if (player.isCollidable() != collidable) {
            player.setCollidable(collidable);
        }
    }

    public void scheduleApply(Player player) {
        if (player == null) {
            return;
        }

        apply(player);

        for (long delay
                : settings.reapplyDelays()) {
            core.getServer()
                    .getScheduler()
                    .runTaskLater(
                            core,
                            () -> {
                                if (core.isEnabled()
                                        && player.isOnline()) {
                                    apply(player);
                                }
                            },
                            delay
                    );
        }
    }

    public void applyAll() {
        for (Player player
                : core.getServer()
                .getOnlinePlayers()) {
            scheduleApply(player);
        }
    }

    public void applyAllNow() {
        for (Player player
                : core.getServer()
                .getOnlinePlayers()) {
            apply(player);
        }
    }

    public void restoreAll() {
        for (Player player
                : core.getServer()
                .getOnlinePlayers()) {
            player.setCollidable(true);
        }
    }

    public boolean collisionEnabled(
            World world
    ) {
        if (world == null) {
            return true;
        }

        Settings current = settings;

        return !current.enabled()
                || !current.disabledWorlds()
                .contains(
                        world.getName()
                                .toLowerCase(Locale.ROOT)
                );
    }

    private record Settings(
            boolean enabled,
            Set<String> disabledWorlds,
            List<Long> reapplyDelays
    ) {
    }
}
