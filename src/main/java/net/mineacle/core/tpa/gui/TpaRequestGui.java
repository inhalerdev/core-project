package net.mineacle.core.tpa.gui;

import net.kyori.adventure.text.Component;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.tpa.service.TpaRequest;
import net.mineacle.core.tpa.service.TpaRequestType;
import org.bukkit.Bukkit;
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
import java.util.Locale;

public final class TpaRequestGui {

    public static final Component TITLE =
            GuiText.component("&#8436FETeleport Request");
    public static final int DENY_SLOT = 11;
    public static final int REQUESTER_SLOT = 13;
    public static final int ACCEPT_SLOT = 15;
    public static final int WORLD_SLOT = 22;

    private static final String PRIMARY =
            "&#8436FE";
    private static final String SECONDARY =
            "&#B078FF";
    private static final String ACCENT =
            "&#D0AFFF";
    private static final String BODY =
            "&#bbbbbb";

    private TpaRequestGui() {
    }

    public static void open(
            Player viewer,
            TpaRequest request
    ) {
        if (request == null) {
            viewer.sendMessage(
                    GuiText.component(
                            "&cYou have no pending teleport requests"
                    )
            );
            return;
        }

        OfflinePlayer requester =
                Bukkit.getOfflinePlayer(
                        request.requesterId()
                );
        Player onlineRequester =
                Bukkit.getPlayer(
                        request.requesterId()
                );
        String requesterName =
                DisplayNames.displayName(
                        requester
                );

        Inventory inventory =
                Bukkit.createInventory(
                        null,
                        27,
                        TITLE
                );

        inventory.setItem(
                DENY_SLOT,
                item(
                        Material.RED_STAINED_GLASS_PANE,
                        "&cDeny",
                        List.of(
                                BODY
                                        + "Decline this teleport request",
                                BODY
                                        + "From "
                                        + SECONDARY
                                        + requesterName
                        )
                )
        );

        inventory.setItem(
                REQUESTER_SLOT,
                playerHead(
                        requester,
                        SECONDARY
                                + requesterName,
                        request.type()
                                == TpaRequestType.TO_TARGET
                                ? List.of(
                                BODY
                                        + "Wants to teleport to you",
                                ACCENT
                                        + "Review the request below"
                        )
                                : List.of(
                                BODY
                                        + "Wants you to teleport to them",
                                ACCENT
                                        + "Review the request below"
                        )
                )
        );

        inventory.setItem(
                ACCEPT_SLOT,
                item(
                        Material.LIME_STAINED_GLASS_PANE,
                        "&aAccept",
                        List.of(
                                BODY
                                        + "Accept this teleport request",
                                BODY
                                        + "From "
                                        + SECONDARY
                                        + requesterName
                        )
                )
        );

        inventory.setItem(
                WORLD_SLOT,
                worldItem(
                        onlineRequester,
                        request
                )
        );

        viewer.openInventory(inventory);
    }

    private static ItemStack worldItem(
            Player requester,
            TpaRequest request
    ) {
        if (requester == null) {
            return item(
                    Material.COMPASS,
                    PRIMARY + "Request Location",
                    List.of(
                            "&cRequester is no longer online"
                    )
            );
        }

        World world = requester.getWorld();

        return item(
                worldMaterial(world),
                PRIMARY + "Request Location",
                List.of(
                        BODY
                                + "World: "
                                + SECONDARY
                                + world.getName(),
                        ACCENT
                                + (
                                request.type()
                                        == TpaRequestType.TO_TARGET
                                        ? "They will teleport to you"
                                        : "You will teleport to them"
                        )
                )
        );
    }

    private static Material worldMaterial(
            World world
    ) {
        String name =
                world.getName()
                        .toLowerCase(
                                Locale.ROOT
                        );

        if (name.contains("nether")) {
            return Material.NETHERRACK;
        }

        if (name.contains("end")) {
            return Material.END_STONE;
        }

        if (name.contains("spawn")
                || name.contains("lobby")) {
            return Material.NETHER_STAR;
        }

        return Material.GRASS_BLOCK;
    }

    private static ItemStack playerHead(
            OfflinePlayer owner,
            String name,
            List<String> lore
    ) {
        ItemStack item =
                new ItemStack(
                        Material.PLAYER_HEAD
                );
        ItemMeta rawMeta =
                item.getItemMeta();

        if (!(rawMeta
                instanceof SkullMeta meta)) {
            return item;
        }

        meta.setOwningPlayer(owner);
        applyPresentation(
                meta,
                name,
                lore
        );
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack item(
            Material material,
            String name,
            List<String> lore
    ) {
        ItemStack item =
                new ItemStack(material);
        ItemMeta meta =
                item.getItemMeta();

        if (meta == null) {
            return item;
        }

        applyPresentation(
                meta,
                name,
                lore
        );
        item.setItemMeta(meta);
        return item;
    }

    private static void applyPresentation(
            ItemMeta meta,
            String name,
            List<String> lore
    ) {
        GuiText.apply(meta, name, lore);
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES
        );
    }

}
