package cc.aerial.client.utility;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Single-holder arbitration for the vanilla block-breaking state machine.
 *
 * <p>{@code MultiPlayerGameMode} keeps one dig in flight — one {@code destroyBlockPos}, one
 * {@code destroyProgress}, one {@code destroyDelay}. Two modules driving it in the same tick
 * interleave START_DESTROY/ABORT_DESTROY packets and neither dig completes. Every module that
 * touches {@code startDestroyBlock}/{@code continueDestroyBlock} or the accessor fields must claim
 * first and yield when the claim is held by someone else.</p>
 *
 * <p>A claim lapses on its own one tick after the last {@link #claim} call, so a module that stops
 * digging — or crashes out of its loop — never strands the lock.</p>
 */
public final class MiningClaim {
    private static Object owner;
    private static BlockPos pos;
    private static int tick = Integer.MIN_VALUE;

    private MiningClaim() {
    }

    /**
     * Takes or renews the claim. Fails without side effects when someone else holds a live claim.
     *
     * @return true when the caller owns the claim after this call
     */
    public static boolean claim(Object candidate, BlockPos target) {
        if (candidate == null) {
            return false;
        }
        if (isLive() && owner != candidate) {
            return false;
        }
        owner = candidate;
        pos = target;
        tick = currentTick();
        return true;
    }

    /** Drops the claim, but only if the caller is the one holding it. */
    public static void release(Object candidate) {
        if (owner == candidate) {
            owner = null;
            pos = null;
            tick = Integer.MIN_VALUE;
        }
    }

    /** True when a live claim exists and it belongs to someone other than {@code candidate}. */
    public static boolean isHeldByOther(Object candidate) {
        return isLive() && owner != candidate;
    }

    public static boolean isHeldBy(Object candidate) {
        return isLive() && owner == candidate;
    }

    public static BlockPos getPos() {
        return isLive() ? pos : null;
    }

    private static boolean isLive() {
        if (owner == null) {
            return false;
        }
        int now = currentTick();
        if (now == Integer.MIN_VALUE) {
            return false;
        }
        // One tick of slack: PLAN claims, EXECUTE later in the same tick still sees it, and a module
        // that simply stopped calling claim() drops out on the following tick.
        if (now - tick > 1) {
            owner = null;
            pos = null;
            tick = Integer.MIN_VALUE;
            return false;
        }
        return true;
    }

    private static int currentTick() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? Integer.MIN_VALUE : player.tickCount;
    }
}
