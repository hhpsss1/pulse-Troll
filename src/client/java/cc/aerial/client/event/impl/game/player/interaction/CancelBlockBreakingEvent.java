package cc.aerial.client.event.impl.game.player.interaction;

import cc.aerial.client.event.EventCancellable;

/**
 * Fired from {@code MultiPlayerGameMode.stopDestroyBlock} HEAD.
 *
 * <p>Vanilla stops the current dig whenever the attack key is released or the looked-at block
 * changes. A module that drives a multi-tick dig of its own cancels this event to keep its progress.</p>
 */
public final class CancelBlockBreakingEvent extends EventCancellable {
}
