package net.mineacle.core.tpa.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.tpa.service.TpaRequest;
import net.mineacle.core.tpa.service.TpaRequestType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

public final class TpaRequestGui {

    public static final String TITLE = ChatColor.DARK_GRAY + "Teleport Request";
    public static final int DENY_SLOT = 11;
    public static final int REQUESTER_SLOT = 13;
    public static final int ACCEPT_SLOT = 15;
    public static final int WORLD_SLOT = 22;

    private TpaRequestGui() {
    }

    public static void open(Core core, Player viewer, TpaRequest request) {
        if (request == null) {
            viewer.sendMessage(TextColor.color("&cYou have no pending teleport requests"));
            return;
        }

        OfflinePlayer requester = Bukkit.getOfflinePlayer(request.requesterId());
        Player requesterOnline = Bukkit.getPlayer(request.requesterId());

        String requesterName = DisplayNames.prefixedDisplayName(requester);
        String plainRequesterName = DisplayNames.displayName(requester);

        Inventory inventory = Bukkit.createInventory(null, 27, TITLE);

        inventory.setItem(
                DENY_SLOT,
                item(
                        Material.RED_STAINED_GLASS_PANE,
                        "&cDeny",
                        List.of(
                                "&#bbbbbbDecline this teleport request",
                                "&#bbbbbbFrom &#ff88ff" + plainRequesterName
                        )
                )
        );

        inventory.setItem(
                REQUESTER_SLOT,
                playerHead(
                        requester,
                        requesterName,
                        request.type() == TpaRequestType.TO_TARGET
                                ? List.of(
                                        "&#bbbbbbWants to teleport to you",
                                        "&#bbbbbbClick &#ff88ffAccept &#bbbbbbto allow",
                                        "&#bbbbbbClick &cDeny &#bbbbbbto decline"
                                )
                                : List.of(
                                        "&#bbbbbbWants you to teleport to them",
                                        "&#bbbbbbClick &#ff88ffAccept &#bbbbbbto teleport",
                                        "&#bbbbbbClick &cDeny &#bbbbbbto decline"
                                )
                )
        );

        inventory.setItem(
                ACCEPT_SLOT,
                item(
                        Material.LIME_STAINED_GLASS_PANE,
                        "&dAccept",
                        List.of(
                                "&#bbbbbbAccept this teleport request",
                                "&#bbbbbbFrom &#ff88ff" + plainRequesterName
                        )
                )
        );

        inventory.setItem(WORLD_SLOT, worldItem(requesterOnline, request));

        viewer.openInventory(inventory);
    }

    private static ItemStack worldItem(Player requesterOnline, TpaRequest request) {
        if (requesterOnline == null) {
            return item(
                    Material.COMPASS,
                    "&dRequest Location",
                    List.of("&#bbbbbbRequester is no longer online")
            );
        }

        World world = requesterOnline.getWorld();
        Material material = worldMaterial(world);
        String worldName = world == null ? "Unknown" : world.getName();

        return item(
                material,
                "&dRequest Location",
                List.of(
                        "&#bbbbbbWorld: &#ff88ff" + worldName,
                        request.type() == TpaRequestType.TO_TARGET
                                ? "&#bbbbbbThey will teleport to you"
                                : "&#bbbbbbYou will teleport to them"
                )
        );
    }

    private static Material worldMaterial(World world) {
        if (world == null) {
            return Material.COMPASS;
        }

        String name = world.getName().toLowerCase();

        if (name.contains("nether")) {
            return Material.NETHERRACK;
        }

        if (name.contains("end")) {
            return Material.END_STONE;
        }

        if (name.contains("spawn") || name.contains("lobby")) {
            return Material.NETHER_STAR;
        }

        return Material.GRASS_BLOCK;
    }

    private static ItemStack playerHead(OfflinePlayer owner, String name, List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta rawMeta = item.getItemMeta();

        if (!(rawMeta instanceof SkullMeta meta)) {
            return item;
        }

        meta.setOwningPlayer(owner);
        meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(TpaRequestGui::color).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(TpaRequestGui::color).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static String color(String input) {
        return TextColor.color(input);
    }
}
