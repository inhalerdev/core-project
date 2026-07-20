package net.mineacle.core.orders.gui;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;
import java.util.UUID;

public final class OrdersGuiHolder
        implements InventoryHolder {

    public enum View {
        MAIN,
        YOUR_ORDERS,
        CREATE,
        CONFIRM
    }

    public enum Confirmation {
        DELIVER,
        CANCEL_ORDER
    }

    private final View view;
    private final int page;
    private final List<UUID> orderIds;
    private final List<Material> materials;
    private final Confirmation confirmation;
    private final UUID confirmationOrderId;

    private Inventory inventory;
    private long armedUntilMillis;

    private OrdersGuiHolder(
            View view,
            int page,
            List<UUID> orderIds,
            List<Material> materials,
            Confirmation confirmation,
            UUID confirmationOrderId
    ) {
        this.view = view;
        this.page = page;
        this.orderIds = orderIds == null
                ? List.of()
                : List.copyOf(orderIds);
        this.materials = materials == null
                ? List.of()
                : List.copyOf(materials);
        this.confirmation = confirmation;
        this.confirmationOrderId = confirmationOrderId;
    }

    public static OrdersGuiHolder main(
            int page,
            List<UUID> orderIds
    ) {
        return new OrdersGuiHolder(
                View.MAIN,
                page,
                orderIds,
                List.of(),
                null,
                null
        );
    }

    public static OrdersGuiHolder yourOrders(
            int page,
            List<UUID> orderIds
    ) {
        return new OrdersGuiHolder(
                View.YOUR_ORDERS,
                page,
                orderIds,
                List.of(),
                null,
                null
        );
    }

    public static OrdersGuiHolder create(
            int page,
            List<Material> materials
    ) {
        return new OrdersGuiHolder(
                View.CREATE,
                page,
                List.of(),
                materials,
                null,
                null
        );
    }

    public static OrdersGuiHolder confirm(
            Confirmation confirmation,
            UUID orderId
    ) {
        return new OrdersGuiHolder(
                View.CONFIRM,
                1,
                List.of(),
                List.of(),
                confirmation,
                orderId
        );
    }

    public View view() {
        return view;
    }

    public int page() {
        return page;
    }

    public UUID orderIdAt(int slot) {
        if (slot < 0 || slot >= orderIds.size()) {
            return null;
        }

        return orderIds.get(slot);
    }

    public Material materialAt(int slot) {
        if (slot < 0 || slot >= materials.size()) {
            return null;
        }

        return materials.get(slot);
    }

    public Confirmation confirmation() {
        return confirmation;
    }

    public UUID confirmationOrderId() {
        return confirmationOrderId;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public void arm(long armedUntilMillis) {
        this.armedUntilMillis = armedUntilMillis;
    }

    public void disarm() {
        armedUntilMillis = 0L;
    }

    public boolean armed(long nowMillis) {
        return armedUntilMillis >= nowMillis;
    }

    public long armedUntilMillis() {
        return armedUntilMillis;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
