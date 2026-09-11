package cc.aerial.client.render.aura;

import cc.aerial.client.mixin.PostChainAccessor;
import cc.aerial.client.mixin.PostPassAccessor;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Rewrites a post pass's named uniform block between frames.
 *
 * <p>A post chain bakes its uniform values into GPU buffers when it is built, which is right for a blur radius
 * and wrong for anything that moves: a refraction dome follows the camera, and a heat plume follows the clock.
 * Rebuilding the chain each frame would recompile its shaders, so the buffer is rewritten in place instead —
 * the same std140 layout the pass wrote once at construction, produced by the game's own builder.
 *
 * <p>The write is skipped, rather than truncated, when the encoded block does not match the buffer the pass
 * allocated. That can only happen if the shader's block and the writer here have drifted apart, and a silent
 * partial write would show up as garbled geometry rather than as an error.
 */
public final class AuraUniforms {
    private AuraUniforms() {
    }

    /**
     * Writes one uniform block of one pass of a chain.
     *
     * @param chain      the chain holding the pass
     * @param passIndex  index of the pass within the chain, in config order
     * @param blockName  the uniform block's name, as declared in the shader
     * @param size       the block's std140 size in bytes, from a size calculator
     * @param writer     fills the builder in declaration order
     * @return whether the block was written
     */
    public static boolean write(@Nullable PostChain chain, int passIndex, String blockName, int size,
                                Consumer<Std140Builder> writer) {
        GpuBuffer buffer = bufferOf(chain, passIndex, blockName);
        if (buffer == null || buffer.size() != size) {
            return false;
        }
        if ((buffer.usage() & GpuBuffer.USAGE_COPY_DST) == 0) {
            buffer = makeWritable(chain, passIndex, blockName, buffer, size);
            if (buffer == null) {
                return false;
            }
        }
        ByteBuffer bytes = ByteBuffer.allocateDirect(size).order(java.nio.ByteOrder.nativeOrder());
        Std140Builder builder = Std140Builder.intoBuffer(bytes);
        writer.accept(builder);
        ByteBuffer written = builder.get();
        written.rewind();
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), written);
        return true;
    }

    /**
     * Swaps a pass's uniform buffer for one that can actually be written to.
     *
     * <p>{@code PostPass} allocates its custom blocks with {@code USAGE_UNIFORM} and nothing else, because it
     * only ever fills them once at construction. Writing into such a buffer is refused outright — the device
     * wants {@code USAGE_COPY_DST} as well — so the buffer is replaced, once, by an equivalent that has it.
     * The map is the pass's own live field, so the pass picks the replacement up on its next draw and closes
     * it with the rest; the buffer it replaces is closed here rather than left to leak.
     *
     * @return the writable buffer, or null when the device or the pass would not give one up
     */
    @Nullable
    private static GpuBuffer makeWritable(@Nullable PostChain chain, int passIndex, String blockName,
                                          GpuBuffer original, int size) {
        Map<String, GpuBuffer> uniforms = uniformsOf(chain, passIndex);
        if (uniforms == null) {
            return null;
        }
        GpuBuffer writable;
        try {
            writable = RenderSystem.getDevice().createBuffer(() -> "aerial/aura/" + blockName,
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, size);
            uniforms.put(blockName, writable);
        } catch (UnsupportedOperationException immutable) {
            // The pass keeps its uniforms in an unmodifiable map: nothing to do but leave the block alone.
            return null;
        }
        original.close();
        return writable;
    }

    @Nullable
    private static Map<String, GpuBuffer> uniformsOf(@Nullable PostChain chain, int passIndex) {
        if (chain == null) {
            return null;
        }
        List<PostPass> passes = ((PostChainAccessor) chain).aerial$passes();
        if (passIndex < 0 || passIndex >= passes.size()) {
            return null;
        }
        return ((PostPassAccessor) passes.get(passIndex)).aerial$customUniforms();
    }

    @Nullable
    private static GpuBuffer bufferOf(@Nullable PostChain chain, int passIndex, String blockName) {
        if (chain == null) {
            return null;
        }
        List<PostPass> passes = ((PostChainAccessor) chain).aerial$passes();
        if (passIndex < 0 || passIndex >= passes.size()) {
            return null;
        }
        return ((PostPassAccessor) passes.get(passIndex)).aerial$customUniforms().get(blockName);
    }
}
