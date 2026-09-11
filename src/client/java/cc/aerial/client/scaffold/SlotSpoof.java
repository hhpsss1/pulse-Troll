package cc.aerial.client.scaffold;

import cc.aerial.client.mixin.MultiPlayerGameModeAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Client-invisible hotbar swap: {@code InventoryMixin} reports {@link #getSlot()} from
 * {@code Inventory.getSelectedSlot()} while a spoof is active, so the server sees the spoofed slot
 * and the player's own hotbar never moves.
 *
 * <p>Only one spoof can be live at a time — the underlying selected slot is a single field. Callers
 * pass an owner token so a second module cannot silently steal or clear someone else's spoof;
 * check {@link #isHeldByOther(Object)} before competing for it.</p>
 */
public final class SlotSpoof {
    private static boolean active;
    private static int slot = -1;
    private static Object owner;

    private SlotSpoof() {
    }

    /**
     * Spoofs the selected slot for {@code owner}. Refuses while another owner holds the spoof.
     *
     * @return true when the spoof is live and owned by {@code owner} after this call
     */
    public static boolean set(int targetSlot, Object owner) {
        if (targetSlot < 0 || targetSlot > 8 || owner == null) {
            return false;
        }
        if (active && SlotSpoof.owner != owner) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null) {
            return false;
        }
        active = true;
        slot = targetSlot;
        SlotSpoof.owner = owner;
        ((MultiPlayerGameModeAccessor) minecraft.gameMode).aerial$ensureHasSentCarriedItem();
        return true;
    }

    /** Clears the spoof, but only if {@code owner} is the one holding it. */
    public static void reset(Object owner) {
        if (!active || SlotSpoof.owner != owner) {
            return;
        }
        active = false;
        slot = -1;
        SlotSpoof.owner = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode != null) {
            ((MultiPlayerGameModeAccessor) minecraft.gameMode).aerial$ensureHasSentCarriedItem();
        }
    }

    /** True when a spoof is live and belongs to someone other than {@code candidate}. */
    public static boolean isHeldByOther(Object candidate) {
        return active && owner != candidate;
    }

    public static boolean isHeldBy(Object candidate) {
        return active && owner == candidate;
    }

    public static boolean isActive() {
        return active;
    }

    public static int getSlot() {
        return slot;
    }

    /** The stack the server currently believes is in hand. */
    public static ItemStack getStack() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return ItemStack.EMPTY;
        }
        int index = active ? slot : player.getInventory().getSelectedSlot();
        if (index < 0 || index > 8) {
            return ItemStack.EMPTY;
        }
        return player.getInventory().getItem(index);
    }
}
