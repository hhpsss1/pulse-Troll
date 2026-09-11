package cc.aerial.client.mixin;

import cc.aerial.client.event.EventDispatcher;
import cc.aerial.client.event.impl.game.input.MoveInputEvent;
import cc.aerial.client.rotation.SilentAim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void aerial$dispatchMoveInput(CallbackInfo ci) {
        ClientInputAccessor accessor = (ClientInputAccessor) this;
        Vec2 moveVector = accessor.aerial$getMoveVector();
        Input keyPresses = accessor.aerial$getKeyPresses();

        MoveInputEvent event = new MoveInputEvent(moveVector.y, moveVector.x,
                keyPresses.jump(), keyPresses.shift(), keyPresses.sprint());
        EventDispatcher.dispatch(event);

        float forward = event.getForward();
        float sideways = event.getSideways();

        // With the physics now running on the sent yaw, an untouched input would walk the player along the
        // aim instead of along the camera. Turning the input by the angle between the two puts the world
        // direction back where the player pointed it, which is the whole difference between the two silent
        // corrections. Rounding is what keeps the result one of the nine inputs a keyboard can produce.
        if (SilentAim.INSTANCE.movement() == SilentAim.Movement.REDIRECT) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && (forward != 0.0f || sideways != 0.0f)) {
                float delta = (player.getYRot() - SilentAim.INSTANCE.outgoing().x) * Mth.DEG_TO_RAD;
                float cos = Mth.cos(delta);
                float sin = Mth.sin(delta);
                float turnedX = sideways * cos - forward * sin;
                float turnedZ = forward * cos + sideways * sin;
                sideways = Math.round(turnedX);
                forward = Math.round(turnedZ);
            }
        }

        accessor.aerial$setKeyPresses(new Input(
                forward > 0.0f,
                forward < 0.0f,
                sideways > 0.0f,
                sideways < 0.0f,
                event.isJump(),
                event.isSneak(),

                event.isSprint()
        ));
        accessor.aerial$setMoveVector(new Vec2(sideways, forward).normalized());
    }
}
