package cc.aerial.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

@Mixin(PostChain.class)
public interface PostChainAccessor {
    @Accessor("persistentTargets")
    Map<Identifier, RenderTarget> aerial$persistentTargets();

    /** The chain's passes, in order, so a caller can rewrite a pass's uniform block between frames. */
    @Accessor("passes")
    List<PostPass> aerial$passes();
}
