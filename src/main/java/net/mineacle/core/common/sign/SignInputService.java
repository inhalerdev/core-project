package net.mineacle.core.common.sign;

import net.kyori.adventure.text.Component;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class SignInputService implements Listener {

    private final Core core;
    private final Map<UUID, PendingSignInput> pendingInputs = new HashMap<>();

    public SignInputService(Core core) {
        this.core = core;
    }

    public void open(Player player, String[] lines, Consumer<String[]> callback) {
        clear(player, false);

        String[] safeLines = Arrays.copyOf(lines == null ? new String[0] : lines, 4);

        Location signLocation = player.getLocation().clone().subtract(0.0D, 4.0D, 0.0D);
        Block block = signLocation.getBlock();
        BlockState originalState = block.getState();

        block.setType(Material.OAK_SIGN, false);

        if (!(block.getState() instanceof Sign sign)) {
            originalState.update(true, false);
            callback.accept(new String[4]);
            return;
        }

        for (int index = 0; index < 4; index++) {
            sign.getSide(Side.FRONT).line(index, Component.text(TextColor.color(safeLines[index] == null ? "" : safeLines[index])));
        }

        sign.update(true, false);

        pendingInputs.put(
                player.getUniqueId(),
                new PendingSignInput(signLocation, originalState, callback)
        );

        player.openSign(sign, Side.FRONT);
    }

    public void clear(Player player) {
        clear(player, true);
    }

    public void clear(Player player, boolean callCallback) {
        PendingSignInput pending = pendingInputs.remove(player.getUniqueId());

        if (pending == null) {
            return;
        }

        pending.originalState().update(true, false);

        if (callCallback) {
            pending.callback().accept(new String[4]);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        PendingSignInput pending = pendingInputs.remove(player.getUniqueId());

        if (pending == null) {
            return;
        }

        if (!sameBlock(event.getBlock().getLocation(), pending.signLocation())) {
            pendingInputs.put(player.getUniqueId(), pending);
            return;
        }

        event.setCancelled(true);

        String[] lines = new String[4];

        for (int index = 0; index < 4; index++) {
            lines[index] = event.getLine(index) == null ? "" : event.getLine(index);
        }

        core.getServer().getScheduler().runTask(core, () -> {
            pending.originalState().update(true, false);
            pending.callback().accept(lines);
        });
    }

    private boolean sameBlock(Location first, Location second) {
        if (first == null || second == null) {
            return false;
        }

        if (first.getWorld() == null || second.getWorld() == null) {
            return false;
        }

        return first.getWorld().equals(second.getWorld())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private record PendingSignInput(
            Location signLocation,
            BlockState originalState,
            Consumer<String[]> callback
    ) {
    }
}