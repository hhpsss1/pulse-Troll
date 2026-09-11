package cc.aerial.client.mixin;

import cc.aerial.client.features.impl.visual.NoHurtCameraModule;
import cc.aerial.client.render.CameraRenderStateHelper;
import cc.aerial.client.render.aura.AuraGlow;
import cc.aerial.client.render.aura.AuraPostChains;
import cc.aerial.client.render.aura.AuraScreenEffects;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private GameRenderState gameRenderState;

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void aerial$noHurtCam(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
        if (NoHurtCameraModule.INSTANCE.isEnabled()) {
            ci.cancel();
        }
    }

    /**
     * Runs the crystal aura's screen-space tier, in the one window where it works.
     *
     * <p>Its effects act on the FINISHED world, so none of them can run from the world-render event — that
     * fires while the frame graph is still being built. But the end of this method is already too late: by
     * then the game has swapped the held item's projection in and CLEARED the main depth buffer, which the
     * blast dome samples to decide what is in front of its shell, and the glow needs the world's own
     * projection to redraw its geometry. So the hook sits between the two: the level is resolved, and nothing
     * has been laid over it yet.
     */
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
            shift = At.Shift.AFTER))
    private void aerial$auraWorldEffects(DeltaTracker deltaTracker, CallbackInfo ci) {
        CameraRenderState camera = gameRenderState.levelRenderState.cameraRenderState;
        CameraRenderStateHelper.set(camera);
        AuraGlow.runFrame(camera);
        AuraScreenEffects.runFrame();
        AuraPostChains.endFrame();
    }
}
