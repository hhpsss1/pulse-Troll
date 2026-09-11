package cc.aerial.client.mixin;

import cc.aerial.client.event.EventDispatcher;
import cc.aerial.client.event.impl.game.world.WorldEntityRemoveEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "removeEntity", at = @At("HEAD"))
    private void aerial$entityRemoved(int id, Entity.RemovalReason reason, CallbackInfo ci) {
        EventDispatcher.dispatch(new WorldEntityRemoveEvent(id, reason));
    }
}
