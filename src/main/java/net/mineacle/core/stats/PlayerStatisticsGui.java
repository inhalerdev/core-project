package net.mineacle.core.stats;

import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.stats.service.StatsService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class PlayerStatisticsGui implements Listener {

    private static final int SIZE = 27;
    private static final String TITLE_SUFFIX = " Stats";

    private static final String MONEY = "&#11fc7b";
    private static final String SECONDARY = "&#B078FF";
    private static final String ACCENT = "&#D0AFFF";
    private static final String KILLS = "&#fc1111";
    private static final String DEATHS = "&#fc8611";
    private static final String PLAYTIME = "&#fcd511";
    private static final String NEUTRAL = "&#bbbbbb";

    private static final int SLOT_MONEY = 10;
    private static final int SLOT_PLAYER_KILLS = 11;
    private static final int SLOT_DEATHS = 12;
    private static final int SLOT_PLAYTIME = 13;
    private static final int SLOT_BLOCKS_PLACED = 14;
    private static final int SLOT_BLOCKS_BROKEN = 15;
    private static final int SLOT_MOBS_KILLED = 16;

    private final StatsService statsService;

    public PlayerStatisticsGui() {
        this.statsService = null;
    }

    public PlayerStatisticsGui(
            StatsService statsService
    ) {
        this.statsService = statsService;
    }

    public void open(
            Player viewer,
            UUID targetId
    ) {
        StatsService service = service();

        if (service == null) {
            viewer.sendMessage(
                    TextColor.color(
                            "&cStats are not ready"
                    )
            );
            return;
        }

        OfflinePlayer target =
                Bukkit.getOfflinePlayer(
                        targetId
                );

        StatsHolder holder =
                new StatsHolder(targetId);
        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        SIZE,
                        GuiText.title(
                                DisplayNames
                                        .displayName(
                                                target
                                        )
                                        + TITLE_SUFFIX
                        )
                );
        holder.attach(inventory);

        inventory.setItem(
                SLOT_MONEY,
                mainStatItem(
                        Material.EMERALD,
                        "$",
                        "Balance",
                        service.money(targetId),
                        MONEY
                )
        );

        inventory.setItem(
                SLOT_PLAYER_KILLS,
                mainStatItem(
                        Material.DIAMOND_SWORD,
                        "🗡",
                        "Kills",
                        String.valueOf(
                                service.kills(
                                        targetId
                                )
                        ),
                        KILLS
                )
        );

        inventory.setItem(
                SLOT_DEATHS,
                mainStatItem(
                        Material.SKELETON_SKULL,
                        "☠",
                        "Deaths",
                        String.valueOf(
                                service.deaths(
                                        targetId
                                )
                        ),
                        DEATHS
                )
        );

        inventory.setItem(
                SLOT_PLAYTIME,
                mainStatItem(
                        Material.CLOCK,
                        "⌚",
                        "Playtime",
                        service.playtime(targetId),
                        PLAYTIME
                )
        );

        inventory.setItem(
                SLOT_BLOCKS_PLACED,
                secondaryStatItem(
                        Material.GRASS_BLOCK,
                        "▣",
                        "Blocks Placed",
                        String.valueOf(
                                service.blocksPlaced(
                                        targetId
                                )
                        )
                )
        );

        inventory.setItem(
                SLOT_BLOCKS_BROKEN,
                secondaryStatItem(
                        Material.COBBLESTONE,
                        "⛏",
                        "Blocks Broken",
                        String.valueOf(
                                service.blocksBroken(
                                        targetId
                                )
                        )
                )
        );

        inventory.setItem(
                SLOT_MOBS_KILLED,
                secondaryStatItem(
                        Material.ZOMBIE_HEAD,
                        "⚔",
                        "Mobs Killed",
                        String.valueOf(
                                service.mobsKilled(
                                        targetId
                                )
                        )
                )
        );

        viewer.openInventory(inventory);
    }

    @SuppressWarnings("unused")
    @EventHandler
    public void onClick(
            InventoryClickEvent event
    ) {
        HumanEntity clicker =
                event.getWhoClicked();

        if (!(clicker instanceof Player)
                || isNotStatsView(
                event.getView()
                        .getTopInventory()
        )) {
            return;
        }

        event.setCancelled(true);
        event.setResult(
                Event.Result.DENY
        );
    }

    @SuppressWarnings("unused")
    @EventHandler
    public void onDrag(
            InventoryDragEvent event
    ) {
        if (isNotStatsView(
                event.getView()
                        .getTopInventory()
        )) {
            return;
        }

        event.setCancelled(true);
        event.setResult(
                Event.Result.DENY
        );
    }

    private boolean isNotStatsView(
            Inventory inventory
    ) {
        return inventory == null
                || !(inventory.getHolder()
                instanceof StatsHolder);
    }

    private StatsService service() {
        if (statsService != null) {
            return statsService;
        }

        return StatsModule.statsService();
    }

    private ItemStack mainStatItem(
            Material material,
            String icon,
            String name,
            String value,
            String color
    ) {
        return formattedStatItem(
                material,
                color + icon,
                NEUTRAL + name,
                color + value
        );
    }

    private ItemStack secondaryStatItem(
            Material material,
            String icon,
            String name,
            String value
    ) {
        return formattedStatItem(
                material,
                SECONDARY + icon,
                SECONDARY + name,
                ACCENT + value
        );
    }

    private ItemStack formattedStatItem(
            Material material,
            String icon,
            String name,
            String value
    ) {
        ItemStack item =
                new ItemStack(material);
        ItemMeta meta =
                item.getItemMeta();

        if (meta == null) {
            return item;
        }

        GuiText.apply(
                meta,
                icon
                        + " "
                        + name
                        + " "
                        + value,
                List.of()
        );
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES
        );
        item.setItemMeta(meta);
        return item;
    }

    private static final class StatsHolder
            implements InventoryHolder {

        private final UUID targetId;
        private Inventory inventory;

        private StatsHolder(
                UUID targetId
        ) {
            this.targetId =
                    Objects.requireNonNull(
                            targetId,
                            "targetId"
                    );
        }

        private void attach(
                Inventory inventory
        ) {
            this.inventory =
                    Objects.requireNonNull(
                            inventory,
                            "inventory"
                    );
        }

        @SuppressWarnings("unused")
        private UUID targetId() {
            return targetId;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Objects.requireNonNull(
                    inventory,
                    "Stats inventory is not attached"
            );
        }
    }
}
