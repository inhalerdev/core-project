package net.mineacle.core.bounty;

import net.mineacle.core.Core;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class BountyMainGui implements Listener {

    private final Core core;
    private final BountyModule bountyModule;
    private BountyConfirmGui confirmGui;

    public BountyMainGui(Core core, BountyModule bountyModule) {
        this.core = core;
        this.bountyModule = bountyModule;
    }

    public void setConfirmGui(BountyConfirmGui confirmGui) {
        this.confirmGui = confirmGui;
    }

    public void open(Player viewer) {
        Inventory inventory = Bukkit.createInventory(null, 54, title());

        try {
            List<BountyRecord> active = new ArrayList<>(bountyModule.bountyService().listBounties());
            active.sort(Comparator.comparingLong(BountyRecord::amount).reversed());

            int slot = 0;
            for (BountyRecord record : active) {
                if (slot >= 27) {
                    break;
                }
                inventory.setItem(slot++, activeBountyItem(record));
            }
        } catch (Exception exception) {
            core.getLogger().warning("Failed to load active bounties: " + exception.getMessage());
        }

        List<Player> onlineTargets = new ArrayList<>(Bukkit.getOnlinePlayers().stream()
                .filter(player -> !player.getUniqueId().equals(viewer.getUniqueId()))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .toList());

        int slot = 27;
        for (Player target : onlineTargets) {
            if (slot >= 45) {
                break;
            }
            inventory.setItem(slot++, targetItem(target));
        }

        inventory.setItem(49, simpleItem(
                Material.PAPER,
                resolve("bounty.gui-help-name", "&fBounty Help"),
                List.of(
                        resolve("bounty.gui-help-line-1", "&7Top half: active bounties"),
                        resolve("bounty.gui-help-line-2", "&7Bottom half: online players"),
                        "",
                        resolve("bounty.gui-help-line-3", "&eClick a player head to place or add a bounty")
                )
        ));

        viewer.openInventory(inventory);
        viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        HumanEntity clicker = event.getWhoClicked();
        if (!(clicker instanceof Player player)) {
            return;
        }

        if (!title().equals(event.getView().getTitle())) {
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }

        if (slot == 49) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.0f);
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.0f);
            return;
        }

        if (!(clicked.getItemMeta() instanceof SkullMeta skullMeta)) {
            return;
        }

        OfflinePlayer target = skullMeta.getOwningPlayer();
        if (target == null || target.getUniqueId() == null || target.getPlayer() == null) {
            player.sendMessage(resolve("bounty.player-not-found", "&cThat player is not online."));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.9f, 0.9f);
            return;
        }

        if (confirmGui != null) {
            confirmGui.open(player, target.getUniqueId());
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (title().equals(event.getView().getTitle())) {
            event.setCancelled(true);
        }
    }

    private ItemStack activeBountyItem(BountyRecord record) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(record.targetId());

        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(target);
            meta.setDisplayName(color("&6" + record.targetName()));
            meta.setLore(List.of(
                    color("&7Active bounty: &f$" + record.amount()),
                    "",
                    color("&eClick to add more bounty")
            ));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack targetItem(Player target) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(target);
            meta.setDisplayName(color("&f" + target.getName()));
            meta.setLore(List.of(
                    color("&7Place a bounty on this player."),
                    "",
                    color("&eClick to choose an amount")
            ));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack simpleItem(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(lore.stream().map(this::color).toList());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String title() {
        return color(resolve("bounty.gui-title", "&8Bounty"));
    }

    private String resolve(String key, String fallback) {
        String raw = core.messages().raw(key);
        return raw == null || raw.equals(key) ? fallback : core.messages().get(key);
    }

    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }
}