package net.mineacle.core.common.sound;

import net.kyori.adventure.text.Component;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.GuiText;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class GuiSoundListener
        implements Listener {

    private static final Set<String> DEFAULT_KNOWN_TITLES =
            Set.of(
                    "home",
                    "team",
                    "member",
                    "invite",
                    "teleport",
                    "tpa",
                    "spawn",
                    "random teleport",
                    "balance top",
                    "baltop",
                    "guide",
                    "rules",
                    "links",
                    "order",
                    "auction",
                    "bounty",
                    "worth",
                    "sell",
                    "enchant",
                    "inspect"
            );

    private static final Set<String> DEFAULT_SILENT_TITLES =
            Set.of(
                    "statistics",
                    "player statistics",
                    "stats",
                    "shulker box"
            );

    private final Core core;

    public GuiSoundListener(Core core) {
        this.core = core;
    }

    /**
     * Enforces the shared player-facing Refresh/Reload visual standard before
     * the inventory is shown. Keeping this at the common GUI boundary means a
     * future Core menu cannot silently regress to paper or another icon.
     */
    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true
    )
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        InventoryView view = event.getView();

        if (!mineacleOwnedMenu(view)) {
            return;
        }

        Inventory top = view.getTopInventory();

        for (int slot = 0; slot < top.getSize(); slot++) {
            ItemStack item = top.getItem(slot);

            if (item == null
                    || item.getType().isAir()
                    || item.getType() == Material.EMERALD
                    || !refreshOrReload(item)) {
                continue;
            }

            top.setItem(
                    slot,
                    item.withType(Material.EMERALD)
            );
        }
    }

    /**
     * This listener supplies a semantic fallback sound for Mineacle GUI
     * interactions that did not already request a more specific sound.
     * <p>
     * SoundService coalesces every request from the same click and keeps only
     * the highest-priority result, so existing system-specific sounds always
     * win over this fallback.
     */
    @SuppressWarnings("unused")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        InventoryView view = event.getView();
        Inventory top = view.getTopInventory();
        int rawSlot = event.getRawSlot();

        if (rawSlot < 0 || rawSlot >= top.getSize()) {
            return;
        }

        ItemStack item = event.getCurrentItem();

        if (item == null
                || item.getType().isAir()
                || filler(item)
                || !mineacleMenu(view)) {
            return;
        }

        String text = itemText(item);

        if (text.isBlank()
                || !actionCue(text, item.getType())
                && !blocked(text)) {
            return;
        }

        route(
                player,
                item,
                text,
                event.getClick()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        SoundService.clearPlayer(event.getPlayer());
    }

    private void route(
            Player player,
            ItemStack item,
            String text,
            ClickType click
    ) {
        if (blocked(text)) {
            if (text.contains("mineacle+")
                    || text.contains("upgrade")) {
                SoundService.mineaclePlus(player, core);
            } else {
                SoundService.guiError(player, core);
            }
            return;
        }

        if (click.isRightClick()
                && text.contains("search")
                && (text.contains("clear")
                || text.contains("right-click"))) {
            SoundService.guiCancel(player, core);
            return;
        }

        if (toggleEnable(text)
                || text.contains("promote")) {
            SoundService.featureEnable(player, core);
            return;
        }

        if (toggleDisable(text)
                || text.contains("demote")) {
            SoundService.featureDisable(player, core);
            return;
        }

        if (destructive(text, item.getType())) {
            SoundService.guiDelete(player, core);
            return;
        }

        if (cancel(text, item.getType())) {
            SoundService.guiCancel(player, core);
            return;
        }

        if (teleportSelection(text)) {
            SoundService.guiSelect(player, core);
            return;
        }

        if (confirm(text, item.getType())) {
            SoundService.guiConfirm(player, core);
            return;
        }

        if (back(text)) {
            SoundService.guiBack(player, core);
            return;
        }

        if (page(text, item.getType())) {
            SoundService.guiPage(player, core);
            return;
        }

        if (text.contains("refresh")
                || text.contains("reload")) {
            SoundService.guiRefresh(player, core);
            return;
        }

        if (text.contains("search")) {
            SoundService.guiSearch(player, core);
            return;
        }

        if (text.contains("sort")) {
            SoundService.guiSort(player, core);
            return;
        }

        if (text.contains("filter")
                || text.contains("category")) {
            SoundService.guiFilter(player, core);
            return;
        }

        if (select(text, item.getType())) {
            SoundService.guiSelect(player, core);
            return;
        }

        SoundService.guiClick(player, core);
    }

    private boolean mineacleMenu(InventoryView view) {
        Inventory top = view.getTopInventory();
        InventoryHolder holder = top.getHolder(false);
        String title = normalize(
                GuiText.plain(view.title())
        );

        return !silentTitle(title)
                && !silentHolder(holder)
                && mineacleOwnedMenu(view);
    }

    private boolean mineacleOwnedMenu(InventoryView view) {
        Inventory top = view.getTopInventory();
        InventoryHolder holder = top.getHolder(false);
        String title = normalize(
                GuiText.plain(view.title())
        );

        if (holder != null
                && holder.getClass()
                .getPackageName()
                .startsWith("net.mineacle.core")) {
            return true;
        }

        if (realStorage(top)) {
            return false;
        }

        /*
         * A non-Mineacle custom InventoryHolder belongs to another plugin.
         * Never style or route those inventories through MineacleCore.
         */
        if (holder != null) {
            return false;
        }

        int actionItems = actionItemCount(top);

        if (knownTitle(title)) {
            return actionItems >= 1;
        }

        /*
         * Legacy Mineacle menus sometimes use a null InventoryHolder.
         * Require multiple action-style items before treating an unknown title
         * as a Mineacle workflow, preventing normal named chests from being
         * styled or sounded.
         */
        return actionItems >= 2;
    }

    private int actionItemCount(Inventory inventory) {
        int actionItems = 0;

        for (ItemStack item : inventory.getContents()) {
            if (item == null
                    || item.getType().isAir()
                    || filler(item)) {
                continue;
            }

            String text = itemText(item);

            if (actionCue(text, item.getType())
                    || blocked(text)) {
                actionItems++;
            }
        }

        return actionItems;
    }

    private boolean knownTitle(String title) {
        List<String> configured =
                core.getConfig().getStringList(
                        "sounds.gui-router.known-title-contains"
                );
        Iterable<String> values = configured.isEmpty()
                ? DEFAULT_KNOWN_TITLES
                : configured;

        for (String value : values) {
            String normalized = normalize(value);

            if (!normalized.isBlank()
                    && title.contains(normalized)) {
                return true;
            }
        }

        return false;
    }

    private boolean silentTitle(String title) {
        List<String> configured =
                core.getConfig().getStringList(
                        "sounds.gui-router.silent-title-contains"
                );
        Iterable<String> values = configured.isEmpty()
                ? DEFAULT_SILENT_TITLES
                : configured;

        for (String value : values) {
            String normalized = normalize(value);

            if (!normalized.isBlank()
                    && title.contains(normalized)) {
                return true;
            }
        }

        return false;
    }

    private boolean silentHolder(InventoryHolder holder) {
        if (holder == null) {
            return false;
        }

        String simpleName = holder.getClass()
                .getSimpleName()
                .toLowerCase(Locale.ROOT);

        return simpleName.contains("statistics")
                || simpleName.contains("stats")
                || simpleName.contains("shulkerpreview");
    }

    private boolean realStorage(Inventory inventory) {
        if (inventory == null) {
            return false;
        }

        InventoryType type = inventory.getType();

        if (type == InventoryType.ENDER_CHEST) {
            return true;
        }

        InventoryHolder holder = inventory.getHolder(false);

        return holder instanceof BlockInventoryHolder
                || holder instanceof BlockState
                || holder instanceof DoubleChest
                || holder instanceof StorageMinecart;
    }

    private boolean filler(ItemStack item) {
        if (!item.getType()
                .name()
                .endsWith("_STAINED_GLASS_PANE")) {
            return false;
        }

        String text = itemText(item);

        return text.isBlank()
                || text.equals(" ")
                || text.equals("-")
                || !actionCue(text, item.getType());
    }

    private boolean actionCue(
            String text,
            Material material
    ) {
        if (text.isBlank()) {
            return false;
        }

        return text.contains("click")
                || text.contains("confirm")
                || text.contains("cancel")
                || text.contains("accept")
                || text.contains("deny")
                || text.contains("back")
                || text.contains("return")
                || text.contains("next")
                || text.contains("previous")
                || text.contains("search")
                || text.contains("sort")
                || text.contains("filter")
                || text.contains("refresh")
                || text.contains("reload")
                || text.contains("toggle")
                || text.contains("enable")
                || text.contains("disable")
                || text.contains("select")
                || text.contains("manage")
                || text.contains("invite")
                || text.contains("teleport")
                || text.contains("delete")
                || text.contains("remove")
                || text.contains("claim")
                || text.contains("collect")
                || text.contains("buy")
                || text.contains("sell")
                || text.contains("create")
                || text.contains("rename")
                || text.contains("promote")
                || text.contains("demote")
                || text.contains("kick")
                || text.contains("leave")
                || text.contains("team chat")
                || text.contains("team pvp")
                || text.contains("your items")
                || text.contains("your orders")
                || text.contains("profile")
                || text.contains("settings")
                || text.contains("close")
                || text.contains("set ")
                || material == Material.PLAYER_HEAD;
    }

    private boolean blocked(String text) {
        return text.contains("locked")
                || text.contains("no permission")
                || text.contains("permission required")
                || text.contains("mineacle+ required")
                || text.contains("upgrade required")
                || text.contains("unavailable")
                || text.contains("disabled")
                || text.contains("only the founder")
                || text.contains("only the owner")
                || text.contains("only an admin")
                || text.contains("cannot use");
    }

    private boolean toggleEnable(String text) {
        return text.contains("click to enable")
                || text.contains("turn on")
                || text.contains("enable this")
                || text.contains("currently disabled");
    }

    private boolean toggleDisable(String text) {
        return text.contains("click to disable")
                || text.contains("turn off")
                || text.contains("disable this")
                || text.contains("currently enabled");
    }

    private boolean destructive(
            String text,
            Material material
    ) {
        boolean destructiveMaterial =
                material == Material.RED_DYE
                        || material == Material.BARRIER
                        || material == Material.TNT
                        || material == Material.LAVA_BUCKET;

        return text.contains("click to delete")
                || text.contains("confirm delete")
                || text.contains("delete team")
                || text.contains("delete home")
                || text.contains("disband")
                || text.contains("kick member")
                || text.contains("remove member")
                || text.contains("remove listing")
                || text.contains("cancel listing")
                || destructiveMaterial
                && (text.contains("delete")
                || text.contains("remove")
                || text.contains("kick")
                || text.contains("disband"));
    }

    private boolean cancel(
            String text,
            Material material
    ) {
        return text.contains("cancel")
                || text.contains("deny")
                || text.contains("decline")
                || text.contains("leave team")
                || text.contains("clear search")
                || text.contains("reset search")
                || material == Material.BARRIER
                || material == Material.RED_DYE;
    }

    private boolean confirm(
            String text,
            Material material
    ) {
        return text.contains("click to confirm")
                || text.contains("confirm purchase")
                || text.contains("confirm sale")
                || text.contains("confirm order")
                || text.contains("place order")
                || text.contains("accept request")
                || text.contains("accept invite")
                || text.contains("create team")
                || text.contains("save changes")
                || text.contains("claim reward")
                || material == Material.LIME_DYE
                || material == Material.EMERALD_BLOCK;
    }

    private boolean teleportSelection(String text) {
        boolean teleportAction = text.contains("teleport")
                || text.contains("tpa request");
        boolean selectionAction = text.contains("click")
                || text.contains("accept")
                || text.contains("confirm")
                || text.contains("select")
                || text.contains("choose")
                || text.contains("return");

        return teleportAction && selectionAction;
    }

    private boolean back(String text) {
        return text.contains("back")
                || text.contains("return to")
                || text.contains("previous menu");
    }

    private boolean page(
            String text,
            Material material
    ) {
        return text.contains("next page")
                || text.contains("previous page")
                || text.contains("page ")
                || material == Material.ARROW
                && (text.contains("next")
                || text.contains("previous"));
    }

    private boolean select(
            String text,
            Material material
    ) {
        return material == Material.PLAYER_HEAD
                || text.contains("select")
                || text.contains("open")
                || text.contains("view")
                || text.contains("manage")
                || text.contains("invite")
                || text.contains("teleport")
                || text.contains("rename")
                || text.contains("choose")
                || text.contains("click to set")
                || text.contains("click to save")
                || text.contains("click to buy")
                || text.contains("click to sell")
                || text.contains("click to claim")
                || text.contains("click to collect");
    }

    private boolean refreshOrReload(ItemStack item) {
        ItemMeta meta = item.getItemMeta();

        if (meta == null || !meta.hasCustomName()) {
            return false;
        }

        Component customName = meta.customName();

        if (customName == null) {
            return false;
        }

        String name = normalize(
                GuiText.plain(customName)
        );
        return name.contains("refresh")
                || name.contains("reload");
    }

    private String itemText(ItemStack item) {
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return normalize(item.getType().name());
        }

        List<String> parts = new ArrayList<>();
        Component customName = meta.customName();

        if (customName != null) {
            parts.add(GuiText.plain(customName));
        }

        List<Component> lore = meta.lore();

        if (lore != null) {
            for (Component line : lore) {
                parts.add(GuiText.plain(line));
            }
        }

        if (parts.isEmpty()) {
            parts.add(item.getType().name());
        }

        return normalize(String.join(" ", parts));
    }

    private String normalize(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        return input.toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
