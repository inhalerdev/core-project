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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BountyConfirmGui implements Listener {

    private static final int[] AMOUNT_SLOTS = {10, 11, 12, 14, 15, 16};

    private final Core core;
    private final BountyModule bountyModule;
    private final Map<UUID, UUID> pendingTargets = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, Long>> pendingAmountsByPlayer = new ConcurrentHashMap<>();

    public BountyConfirmGui(Core core, BountyModule bountyModule) {
        this.core = core;
        this.bountyModule = bountyModule;
    }

    public void open(Player setter, UUID targetId) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
        pendingTargets.put(setter.getUniqueId(), targetId);

        Inventory inventory = Bukkit.createInventory(null, 27, title());

        List<Long> presetAmounts = loadPresetAmounts();
        Map<Integer, Long> slotAmounts = new ConcurrentHashMap<>();

        for (int index = 0; index < presetAmounts.size() && index < AMOUNT_SLOTS.length; index++) {
            long amount = presetAmounts.get(index);
            int slot = AMOUNT_SLOTS[index];
            slotAmounts.put(slot, amount);
            inventory.setItem(slot, amountItem(iconForIndex(index), "&6$" + format(amount), amount));
        }

        pendingAmountsByPlayer.put(setter.getUniqueId(), slotAmounts);

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(target);
            meta.setDisplayName(color("&f" + (target.getName() == null ? "Player" : target.getName())));
            meta.setLore(List.of(
                    color("&7Choose how much bounty to place."),
                    "",
                    color("&eClick one of the gold amounts")
            ));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            head.setItemMeta(meta);
        }
        inventory.setItem(13, head);

        setter.openInventory(inventory);
        setter.playSound(setter.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        HumanEntity clicker = event.getWhoClicked();
        if (!(clicker instanceof Player setter)) {
            return;
        }

        if (!title().equals(event.getView().getTitle())) {
            return;
        }

        event.setCancelled(true);

        UUID targetId = pendingTargets.get(setter.getUniqueId());
        if (targetId == null) {
            setter.closeInventory();
            return;
        }

        Map<Integer, Long> slotAmounts = pendingAmountsByPlayer.get(setter.getUniqueId());
        long amount = slotAmounts != null ? slotAmounts.getOrDefault(event.getRawSlot(), -1L) : -1L;

        if (amount < 0) {
            setter.playSound(setter.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.0f);
            return;
        }

        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            setter.sendMessage(resolve("bounty.player-not-found", "&cThat player is not online."));
            clear(setter.getUniqueId());
            setter.closeInventory();
            setter.playSound(setter.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.9f, 0.9f);
            return;
        }

        try {
            bountyModule.bountyService().placeBounty(setter, target, amount);
            setter.playSound(setter.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.0f);
        } catch (Exception exception) {
            core.getLogger().severe("Bounty confirm GUI failed: " + exception.getMessage());
            setter.sendMessage(resolve("general.error", "&cSomething went wrong. Check console."));
        }

        clear(setter.getUniqueId());
        setter.closeInventory();
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (title().equals(event.getView().getTitle())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (title().equals(event.getView().getTitle())) {
            clear(event.getPlayer().getUniqueId());
        }
    }

    private void clear(UUID playerId) {
        pendingTargets.remove(playerId);
        pendingAmountsByPlayer.remove(playerId);
    }

    private List<Long> loadPresetAmounts() {
        List<Integer> configured = core.getConfig().getIntegerList("bounty.preset-amounts");
        List<Long> amounts = new ArrayList<>();

        for (Integer value : configured) {
            if (value != null && value > 0) {
                amounts.add(value.longValue());
            }
        }

        if (amounts.isEmpty()) {
            amounts.add(500L);
            amounts.add(1000L);
            amounts.add(5000L);
            amounts.add(10000L);
        }

        return amounts;
    }

    private Material iconForIndex(int index) {
        return switch (index) {
            case 0 -> Material.GOLD_INGOT;
            case 1 -> Material.EMERALD;
            case 2 -> Material.DIAMOND;
            case 3 -> Material.NETHERITE_INGOT;
            case 4 -> Material.AMETHYST_SHARD;
            default -> Material.SUNFLOWER;
        };
    }

    private ItemStack amountItem(Material material, String name, long amount) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(List.of(
                    color("&7Place a bounty for &f$" + format(amount)),
                    "",
                    color("&aClick to confirm this amount")
            ));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String format(long amount) {
        return NumberFormat.getNumberInstance(Locale.US).format(amount);
    }

    private String title() {
        return color(resolve("bounty.gui-confirm-title", "&8Confirm Bounty"));
    }

    private String resolve(String key, String fallback) {
        String raw = core.messages().raw(key);
        return raw == null || raw.equals(key) ? fallback : core.messages().get(key);
    }

    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }
}