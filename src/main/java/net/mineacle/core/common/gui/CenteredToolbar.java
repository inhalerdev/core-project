package net.mineacle.core.common.gui;

/**
 * Shared slot geometry for Mineacle inventory control rows.
 *
 * <p>A Minecraft inventory row contains nine slots. Paginated Mineacle menus
 * reserve the outer slots for Previous and Next, then mirror all utility
 * controls around the row's center. Even control counts leave the exact center
 * empty so the visual weight remains balanced.</p>
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
     * Returns one through seven centered interior slots in left-to-right order.
     * The two outer navigation slots are never returned.
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
     * Returns whether the supplied slots mirror perfectly around the last-row
     * center. Useful for focused validation and future GUI regression tests.
     */
    public static boolean isCentered(
            int inventorySize,
            int... slots
    ) {
        if (slots == null || slots.length == 0) {
            return true;
        }

        int centerTwice = centerSlot(inventorySize) * 2;

        for (int index = 0; index < slots.length; index++) {
            int mirrorIndex = slots.length - 1 - index;

            if (slots[index] + slots[mirrorIndex] != centerTwice) {
                return false;
            }
        }

        return true;
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
