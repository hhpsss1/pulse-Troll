package cc.aerial.client.event.impl.game.world;

import net.minecraft.world.entity.Entity;

/**
 * Fired from {@code ClientLevel.removeEntity} HEAD, before the entity leaves the level.
 *
 * <p>Carries the network id rather than the entity itself: every consumer so far only needs the id,
 * and taking it from the method's own parameter keeps the injection at HEAD instead of hunting for a
 * local inside {@code Entity.onClientRemoval}. Resolve the entity with {@code level.getEntity(id)}
 * while handling the event if you need more than the id.</p>
 */
public record WorldEntityRemoveEvent(int entityId, Entity.RemovalReason reason) {
}
