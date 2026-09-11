package cc.aerial.client.rotation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

public final class ServerRotation {
    private static float yaw;
    private static int priority = Integer.MIN_VALUE;
    private static int submittedTick = -1;

    private ServerRotation() {
    }

    public static void submit(float yaw, int priority) {
        int tick = currentTick();
        if (tick != submittedTick) {
            submittedTick = tick;
            ServerRotation.priority = Integer.MIN_VALUE;
        }
        if (priority < ServerRotation.priority) {
            return;
        }
        ServerRotation.priority = priority;
        ServerRotation.yaw = yaw;
    }

    /**
     * True while a submitted yaw is still the one movement should be resolved against.
     *
     * <p>A tick of grace, not strict equality. A submitter running from {@code Minecraft.tick} — the pre-tick
     * handlers or the keybind handling — records the counter BEFORE {@code level.tickEntities()} runs, and
     * {@code LocalPlayer.tick} increments it through {@code baseTick} before the movement input is resolved
     * and the movement packet is built. Comparing for equality therefore discards the submission of every
     * such caller: the strafe is then computed from the camera while the packet reports a different yaw, and
     * the two disagree on the wire, which is exactly what a movement check looks for.
     */
    public static boolean isActive() {
        return submittedTick >= 0 && currentTick() - submittedTick <= 1;
    }

    public static float getYaw() {
        return yaw;
    }

    public static float getYawOr(float fallback) {
        return isActive() ? yaw : fallback;
    }

    private static int currentTick() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? -1 : player.tickCount;
    }
}
