package net.mineacle.core.common.gui;

/**
 * Shared slot geometry for Mineacle inventory control rows.
 *
 * <p>A Minecraft inventory row contains nine slots. Paginated Mineacle menus
 * reserve the outer slots for Previous and Next, then place utility controls
 * inside the seven interior slots.</p>
 */
public final class CenteredToolbar {

    private CenteredToolbar() {
    }

    public static int previousSlot(int inventorySize) {
        return lastRowStart(inventorySize);
    }

    public static int nextSlot(int inventorySize) {
        return lastRowStart(inventorySize) + 8;
    }

    public static int centerSlot(int inventorySize) {
        return lastRowStart(inventorySize) + 4;
    }

    /**
     * Returns one through seven visually centered interior slots in
     * left-to-right order. Even control counts leave the exact center empty.
     */
    public static int[] interiorSlots(
            int inventorySize,
            int controlCount
    ) {
        int center = centerSlot(inventorySize);

        return switch (controlCount) {
            case 1 -> new int[]{center};
            case 2 -> new int[]{center - 1, center + 1};
            case 3 -> new int[]{center - 1, center, center + 1};
            case 4 -> new int[]{
                    center - 2,
                    center - 1,
                    center + 1,
                    center + 2
            };
            case 5 -> new int[]{
                    center - 2,
                    center - 1,
                    center,
                    center + 1,
                    center + 2
            };
            case 6 -> new int[]{
                    center - 3,
                    center - 2,
                    center - 1,
                    center + 1,
                    center + 2,
                    center + 3
            };
            case 7 -> new int[]{
                    center - 3,
                    center - 2,
                    center - 1,
                    center,
                    center + 1,
                    center + 2,
                    center + 3
            };
            default -> throw new IllegalArgumentException(
                    "controlCount must be between 1 and 7"
            );
        };
    }

    /**
     * Returns consecutive interior slots while forcing one designated control
     * to occupy the exact center slot. This is the Mineacle standard for rows
     * containing Refresh/Reload: Refresh owns the center and the surrounding
     * controls retain their natural left-to-right order.
     *
     * @param inventorySize inventory size, 9 through 54 in rows of nine
     * @param controlCount number of controls, 1 through 7
     * @param centeredControlIndex zero-based index of the control that must own
     *                             the exact center slot
     * @return consecutive interior slots in left-to-right order
     */
    public static int[] interiorSlotsCenteredOn(
            int inventorySize,
            int controlCount,
            int centeredControlIndex
    ) {
        if (controlCount < 1 || controlCount > 7) {
            throw new IllegalArgumentException(
                    "controlCount must be between 1 and 7"
            );
        }

        if (centeredControlIndex < 0
                || centeredControlIndex >= controlCount) {
            throw new IllegalArgumentException(
                    "centeredControlIndex must reference an existing control"
            );
        }

        int rowStart = lastRowStart(inventorySize);
        int firstInterior = rowStart + 1;
        int lastInterior = rowStart + 7;
        int firstSlot =
                centerSlot(inventorySize)
                        - centeredControlIndex;
        int finalSlot =
                firstSlot + controlCount - 1;

        if (firstSlot < firstInterior
                || finalSlot > lastInterior) {
            throw new IllegalArgumentException(
                    "controls cannot fit while keeping the selected control centered"
            );
        }

        int[] slots = new int[controlCount];

        for (int index = 0;
             index < controlCount;
             index++) {
            slots[index] = firstSlot + index;
        }

        return slots;
    }

    private static int lastRowStart(int inventorySize) {
        if (inventorySize < 9
                || inventorySize > 54
                || inventorySize % 9 != 0) {
            throw new IllegalArgumentException(
                    "inventorySize must be a multiple of 9 between 9 and 54"
            );
        }

        return inventorySize - 9;
    }
}
