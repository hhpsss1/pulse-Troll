package cc.aerial.client.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Reaches a post pass's custom uniform buffers.
 *
 * <p>A pass builds one GPU buffer per named uniform block from the values baked into its config, and offers no
 * way to change them afterwards. An effect whose parameters move every frame — a refraction dome following the
 * camera, say — would otherwise have to rebuild and recompile the whole chain each frame. Rewriting the buffer
 * in place is the same work the pass did once at construction.
 */
@Mixin(PostPass.class)
public interface PostPassAccessor {
    @Accessor("customUniforms")
    Map<String, GpuBuffer> aerial$customUniforms();
}
