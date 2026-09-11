package cc.aerial.client.mixin;

import cc.aerial.client.rotation.SilentAim;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Runs the local player's movement on the yaw that was actually sent.
 *
 * <p>{@code Entity.moveRelative} is the single place where a yaw becomes a direction of travel: it hands
 * {@code getYRot()} to {@code getInputVector}. Everything downstream — the delta movement, the position, the
 * packet that reports it — follows from that one call.
 *
 * <p>A silent rotation that stops at the packet leaves the two sides computing different things: the client
 * walks by its camera while the server predicts by the yaw it was told, so every tick the player moves the
 * predicted position drifts from the reported one. That is not a subtle signature — a movement check sees it
 * immediately, and it is the reason a packet-only correction cannot be made quiet by adjusting inputs alone.
 *
 * <p>Redirecting this one call makes the client compute exactly what the server will. It applies only to the
 * local player, and only while something is holding a silent rotation that asked for movement correction.
 */
@Mixin(Entity.class)
public abstract class EntityMovementYawMixin {
    @ModifyExpressionValue(method = "moveRelative",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getYRot()F"))
    private float aerial$movementYaw(float original) {
        if ((Object) this != Minecraft.getInstance().player) {
            return original;
        }
        return SilentAim.INSTANCE.movementYaw(original);
    }
}
