package cc.aerial.client.render.aura;

import cc.aerial.client.event.impl.render.Render3DEvent;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A soft glow layer any aura geometry can be routed through.
 *
 * <p>The caller draws whatever it likes inside {@link #capture}; the geometry lands in an offscreen silhouette
 * instead of the screen, is blurred, and is composited back as the sharp shape sitting on top of its own halo.
 *
 * <h2>How the geometry is diverted</h2>
 *
 * <p>World geometry in 26.2 is not drawn where it is submitted: {@code submitCustomGeometry} files a node into
 * the level's {@link SubmitNodeStorage}, and the whole storage is drawn later, in one batch, into whatever the
 * level renderer had bound at the time. Nothing in that path can be pointed at another target after the fact.
 *
 * <p>So this class keeps a storage of its OWN. {@link #capture} hands the drawing a substitute
 * {@link Render3DEvent} whose collector is that private storage, which means every {@code Render3DUtility} and
 * {@code SoftGeometry} helper works unchanged and the caller passes the same drawing to both passes. The
 * private storage is then drawn by {@link #runFrame} with the game's output-target override in place, which is
 * the same mechanism the vanilla picture-in-picture renderer uses to put an entity into a GUI texture.
 *
 * <h2>Why it runs where it runs</h2>
 *
 * <p>{@link #runFrame} must be called immediately after {@code LevelRenderer.render} returns, and NOT at the
 * end of {@code GameRenderer.renderLevel}: by then the game has already swapped in the held item's projection
 * and CLEARED the main depth buffer. This pass needs the world's projection still bound, and the composite has
 * to land under the hand rather than over it.
 *
 * <h2>Depth</h2>
 *
 * <p>The silhouette carries a depth attachment even though nothing here reads depth afterwards: the caller is
 * free to route geometry through a depth-testing pipeline, and such a pipeline cannot draw into a target with
 * no depth buffer at all. The buffer is cleared every frame rather than copied from the world, so the layer
 * still shows through walls — the depth only lets the routed geometry occlude ITSELF, which is most of what
 * stops a stack of translucent shapes from reading flat.
 *
 * <h2>Failure</h2>
 *
 * <p>Anything the render thread could throw is caught once. After a failure the layer turns itself off for the
 * rest of the session and frees its targets, rather than throwing every frame.
 */
public final class AuraGlow {
    private static final Logger LOGGER = LoggerFactory.getLogger("Aerial");

    private AuraGlow() {
    }

    /**
     * How the glow layer is shaped. Every field is continuous — nothing here switches a look on or off, so a
     * tweened value can be handed straight in without anything popping.
     *
     * @param radius          how far the glow reaches, 0..1; drives the blur kernel
     * @param strength        brightness of the halo; 0 leaves only the sharp geometry
     * @param falloff         exponent on the blurred coverage — higher hugs the geometry more tightly
     * @param alpha           overall opacity of the whole layer, 0..1; at 0 nothing is drawn at all
     * @param interiorDamping how much of the halo survives INSIDE the silhouette, 0..1
     */
    public record GlowSettings(float radius, float strength, float falloff, float alpha, float interiorDamping) {
    }

    /** The default share of the halo kept inside the silhouette, so the interior does not wash out to white. */
    public static final float DEFAULT_INTERIOR_DAMPING = 0.35f;

    /** Idle frames tolerated before the target is handed back — about two seconds at 60 fps. */
    private static final int IDLE_FRAMES_BEFORE_RELEASE = 120;

    private static final Vector4f TRANSPARENT = new Vector4f(0f, 0f, 0f, 0f);

    private static final SubmitNodeStorage STORAGE = new SubmitNodeStorage();

    @Nullable
    private static TextureTarget silhouette;

    @Nullable
    private static GlowSettings pending;

    /** Sticky: set once the effect has thrown, never cleared, so it cannot throw every frame. */
    private static boolean broken;

    private static int idleFrames;

    /**
     * Records {@code geometry} for this frame's glow layer.
     *
     * <p>Call from the world-render event, with the same drawing the world pass used. Safe to call
     * unconditionally: a zero alpha returns before anything is allocated. A zero strength still draws — it
     * leaves the sharp geometry without a halo, which is a legitimate look and not a disabled effect.
     */
    public static void capture(Render3DEvent event, GlowSettings settings, Consumer<Render3DEvent> geometry) {
        if (broken || settings.alpha() <= 0f) {
            return;
        }
        try {
            geometry.accept(new Render3DEvent(new PoseStack(), STORAGE, event.camera(), event.partialTick()));
            pending = settings;
        } catch (Throwable cause) {
            fail(cause);
        }
    }

    /**
     * Draws this frame's recording into the offscreen silhouette, blurs it and composites it onto the world.
     *
     * <p>Call once per frame, immediately after the level render and before the held item.
     */
    public static void runFrame(@Nullable CameraRenderState camera) {
        GlowSettings settings = pending;
        pending = null;
        if (broken || settings == null || camera == null || camera.viewRotationMatrix == null) {
            onIdleFrame();
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
            if (main == null || main.width <= 0 || main.height <= 0) {
                return;
            }
            idleFrames = 0;

            TextureTarget mask = maskTarget(main.width, main.height);
            drawSilhouette(minecraft, mask, camera);
            blurAndComposite(minecraft, main, mask, settings);
        } catch (Throwable cause) {
            fail(cause);
        } finally {
            drain();
        }
    }

    /**
     * Empties the recording when it was not drawn.
     *
     * <p>Drawing it drains it — the dispatcher takes the nodes out of the storage as it prepares them — but
     * every early return above leaves them in, and they would then be drawn a frame late on top of the next
     * frame's recording.
     */
    private static void drain() {
        STORAGE.drainPhases(phase -> {
        });
    }

    /**
     * Frees the silhouette. Call on world change and on disable — it is rebuilt on the next frame that has
     * something to draw.
     */
    public static void release() {
        idleFrames = 0;
        pending = null;
        if (silhouette != null) {
            silhouette.destroyBuffers();
            silhouette = null;
        }
    }

    /**
     * Nothing to draw this frame. The target is kept for a while — the module goes quiet between plans
     * constantly, and reallocating a window-sized target for every gap would cost more than holding it — then
     * handed back once the pause looks permanent.
     */
    private static void onIdleFrame() {
        if (silhouette == null) {
            return;
        }
        if (++idleFrames >= IDLE_FRAMES_BEFORE_RELEASE) {
            release();
        }
    }

    private static void fail(Throwable cause) {
        broken = true;
        LOGGER.error("Crystal aura glow disabled after a render failure", cause);
        release();
    }

    /** The silhouette at the frame's size, allocated on the first frame that actually draws something. */
    private static TextureTarget maskTarget(int width, int height) {
        TextureTarget target = silhouette;
        if (target == null) {
            target = new TextureTarget("aerial_aura_silhouette", width, height, true, GpuFormat.RGBA8_UNORM);
            silhouette = target;
        } else if (target.width != width || target.height != height) {
            target.resize(width, height);
        }
        return target;
    }

    /**
     * Plays this frame's recording into the silhouette.
     *
     * <p>The model-view stack is pushed and multiplied by the camera's rotation exactly as the level renderer
     * does around its own passes: it pops that matrix before returning, and the geometry recorded here is in
     * camera-relative world space, which only lands correctly with the camera's rotation applied.
     */
    private static void drawSilhouette(Minecraft minecraft, TextureTarget mask, CameraRenderState camera) {
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                mask.getColorTexture(), TRANSPARENT, mask.getDepthTexture(), 1.0);

        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.mul(camera.viewRotationMatrix);
        RenderSystem.outputColorTextureOverride = mask.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = mask.getDepthTextureView();
        try {
            minecraft.gameRenderer.featureRenderDispatcher().renderAllFeatures(STORAGE);
        } finally {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
            modelView.popMatrix();
        }
    }

    /**
     * Blurs the silhouette and lays the halo under the sharp shape, over the world.
     *
     * <p>The chain reaches OUTSIDE itself for two targets — the world it writes and the silhouette it reads —
     * so it cannot go through {@code PostChain.process}, which knows how to import exactly one. The frame graph
     * it builds instead is the same one that method builds, with both targets imported by name.
     */
    private static void blurAndComposite(Minecraft minecraft, RenderTarget main, TextureTarget mask,
                                         GlowSettings settings) {
        PostChain chain = AuraPostChains.glow(kernelKey(settings.radius()), main.width, main.height);
        if (chain == null) {
            return;
        }
        boolean written = AuraUniforms.write(chain, AuraPostChains.GLOW_COMPOSITE_PASS,
                AuraPostChains.GLOW_BLOCK, glowBlockSize(),
                builder -> builder.putVec4(
                        Math.max(settings.strength(), 0f),
                        Math.max(settings.falloff(), 0.05f),
                        Math.clamp(settings.alpha(), 0f, 1f),
                        Math.clamp(settings.interiorDamping(), 0f, 1f)));
        if (!written) {
            return;
        }

        FrameGraphBuilder graph = new FrameGraphBuilder();
        Map<Identifier, ResourceHandle<RenderTarget>> targets = new HashMap<>(2);
        targets.put(PostChain.MAIN_TARGET_ID, graph.importExternal("aura_glow_main", main));
        targets.put(AuraPostChains.SILHOUETTE, graph.importExternal("aura_glow_silhouette", mask));
        chain.addToFrame(graph, main.width, main.height, new Bundle(targets));
        graph.execute(AuraPostChains.pool());
    }

    /**
     * The blur radius as a whole number, which is what the chain is cached on.
     *
     * <p>The setting is continuous but the chain bakes the kernel into its uniforms at build time, so a chain
     * per distinct radius is unavoidable. Quantising to the setting's own step keeps that to one rebuild per
     * notch the user actually drags through, instead of one per float that floating-point arithmetic produces.
     */
    private static int kernelKey(float radius) {
        return Math.round(Math.clamp(radius, 0f, 1f) * 100f);
    }

    /** The glow block's std140 size: one control vector. */
    private static int glowBlockSize() {
        Std140SizeCalculator size = new Std140SizeCalculator();
        size.putVec4();
        return size.get();
    }

    /** A target bundle over a fixed map, for the two targets the glow chain imports. */
    private record Bundle(Map<Identifier, ResourceHandle<RenderTarget>> targets) implements PostChain.TargetBundle {
        @Override
        public void replace(Identifier id, ResourceHandle<RenderTarget> handle) {
            targets.put(id, handle);
        }

        @Override
        @Nullable
        public ResourceHandle<RenderTarget> get(Identifier id) {
            return targets.get(id);
        }
    }
}
