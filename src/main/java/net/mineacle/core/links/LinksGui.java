package net.mineacle.core.links;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.UUID;

public final class LinksGui {

    private LinksGui() {
    }

    public static void open(Player player, LinksService service) {
        Inventory inventory = Bukkit.createInventory(null, service.size(), TextColor.color(service.title()));

        for (LinkItem item : service.items()) {
            if (item.slot() < 0 || item.slot() >= service.size()) {
                continue;
            }

            inventory.setItem(item.slot(), buildItem(item));
        }

        player.openInventory(inventory);
    }

    public static boolean isTitle(String rawTitle, LinksService service) {
        String title = ChatColor.stripColor(rawTitle);
        String expected = ChatColor.stripColor(TextColor.color(service.title()));

        return title != null && expected != null && title.equals(expected);
    }

    private static ItemStack buildItem(LinkItem item) {
        Material material = material(item.material());

        if (material == Material.PLAYER_HEAD && item.texture() != null && !item.texture().isBlank()) {
            return customHead(item);
        }

        return normalItem(material, item.name(), item.lore());
    }

    private static ItemStack customHead(LinkItem link) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();

        if (meta == null) {
            return item;
        }

        PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), null);
        profile.setProperty(new ProfileProperty("textures", link.texture()));
        meta.setPlayerProfile(profile);

        applyMeta(meta, link.name(), link.lore());
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack normalItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        applyMeta(meta, name, lore);
        item.setItemMeta(meta);
        return item;
    }

    private static void applyMeta(ItemMeta meta, String name, List<String> lore) {
        meta.setDisplayName(TextColor.color(name));
        meta.setLore(lore.stream().map(TextColor::color).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
    }

    private static Material material(String raw) {
        if (raw == null || raw.isBlank()) {
            return Material.PLAYER_HEAD;
        }

        try {
            Material material = Material.valueOf(raw.toUpperCase());

            if (!material.isItem()) {
                return Material.PLAYER_HEAD;
            }

            return material;
        } catch (IllegalArgumentException exception) {
            return Material.PLAYER_HEAD;
        }
    }
}
