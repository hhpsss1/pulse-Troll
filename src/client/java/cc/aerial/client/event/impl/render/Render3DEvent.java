package cc.aerial.client.event.impl.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/**
 * Fired once per frame from {@code LevelRenderer.submitFeatures}, after vanilla has submitted
 * entities, block entities and the block outline. The pose stack is at identity in camera-relative
 * space: subtract {@link CameraRenderState#pos} from world coordinates before emitting vertices
 * (see {@link cc.aerial.client.render.Render3DUtility}).
 */
public record Render3DEvent(PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera,
                            float partialTick) {
}
