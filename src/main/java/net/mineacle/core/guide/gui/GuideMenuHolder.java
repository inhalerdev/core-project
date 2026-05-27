package net.mineacle.core.guide.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class GuideMenuHolder implements InventoryHolder {

    private final String menuKey;
    private final Map<Integer, java.util.List<String>> clickCommands;

    public GuideMenuHolder(String menuKey, Map<Integer, java.util.List<String>> clickCommands) {
        this.menuKey = menuKey;
        this.clickCommands = clickCommands;
    }

    public String menuKey() {
        return menuKey;
    }

    public java.util.List<String> commands(int slot) {
        return clickCommands.getOrDefault(slot, java.util.List.of());
    }

    @Override
    public @NotNull Inventory getInventory() {
        throw new UnsupportedOperationException("Guide menu holder does not own a backing inventory");
    }
}
