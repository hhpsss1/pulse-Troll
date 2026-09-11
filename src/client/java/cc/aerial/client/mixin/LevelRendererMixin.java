package cc.aerial.client.mixin;

import cc.aerial.client.event.EventDispatcher;
import cc.aerial.client.event.impl.render.Render3DEvent;
import cc.aerial.client.features.impl.visual.VanillaFixModule;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "addWeatherPass", at = @At("HEAD"), cancellable = true)
    private void aerial$removeWeather(FrameGraphBuilder frameGraphBuilder, GpuBufferSlice fogBuffer, CallbackInfo ci) {
        if (VanillaFixModule.INSTANCE.isWeatherRemoved()) {
            ci.cancel();
        }
    }

    @Inject(method = "submitFeatures", at = @At("TAIL"))
    private void aerial$submitFeatures(LevelRenderState levelRenderState, SubmitNodeCollector collector,
                                       boolean renderBlockOutline, CallbackInfo ci) {
        CameraRenderState camera = levelRenderState.cameraRenderState;
        Minecraft mc = Minecraft.getInstance();
        if (camera == null || camera.pos == null || mc.level == null || mc.player == null) {
            return;
        }
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        EventDispatcher.dispatch(new Render3DEvent(new PoseStack(), collector, camera, partialTick));
    }
}
