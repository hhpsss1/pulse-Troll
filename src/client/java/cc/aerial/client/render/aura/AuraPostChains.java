package cc.aerial.client.render.aura;

import cc.aerial.client.mixin.PostChainAccessor;
import cc.aerial.client.mixin.ShaderManagerAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.client.renderer.UniformValue;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The post-processing chains of the crystal aura's screen-space effects.
 *
 * <p>Built and cached the way the client's blur chains are: one chain per effect, rebuilt only when the frame
 * size changes, with the old one closed on the next frame rather than mid-frame.
 *
 * <p>The uniform values passed here are placeholders of the right shape and size — a chain bakes its uniforms
 * into a GPU buffer when it is built, so the live values are written into that buffer every frame instead. What
 * matters at build time is only that each block has the right layout.
 *
 * <h2>Why a scene copy</h2>
 * <p>Both refraction effects read the finished world and write into it. A pass cannot sample the target it is
 * writing to, so the world is first copied into a persistent target and the refraction samples that.
 */
public final class AuraPostChains {
    public enum Slot {
        /** Copies the finished world into a target the refraction passes can sample. */
        SCENE_COPY,
        /** The blast dome: a world-anchored shell that refracts the scene behind it. */
        DOME,
        /** The forge: a heat plume over a site the aura is working. */
        FORGE,
        /** The soft halo: the aura's own geometry, blurred and laid back under itself. */
        GLOW
    }

    /** Domes the shader can draw at once; must match the constant in the shader. */
    public static final int MAX_DOMES = 4;

    /** Sites the plume shader can draw at once; must match the constant in the shader. */
    public static final int MAX_SITES = 4;

    public static final String DOME_BLOCK = "AuraDomeData";
    public static final String FORGE_BLOCK = "AuraForgeData";
    public static final String GLOW_BLOCK = "AuraGlowData";

    /** Index of the glow chain's composite pass, which is the one carrying {@link #GLOW_BLOCK}. */
    public static final int GLOW_COMPOSITE_PASS = 5;

    /** The offscreen silhouette the glow chain reads; imported from outside, not owned by the chain. */
    public static final Identifier SILHOUETTE = Identifier.fromNamespaceAndPath("aerial", "aura_silhouette");

    /** How far the blur targets are shrunk before the pyramid runs. */
    private static final int GLOW_DOWNSCALE = 2;

    /**
     * Converts the glow radius, 0..1, into the kernel the Kawase shaders take.
     *
     * <p>Those shaders offset by {@code 0.5 + Radius * 0.2} texels, so this is the inverse of the kernel the
     * source spreads over half a texel at zero and four and a half at one.
     */
    private static final float GLOW_KERNEL_SCALE = 17.5f;

    /** Per-pass share of the kernel, widest where the source is sharpest. */
    private static final float[] GLOW_SCALES = {0.7f, 1.0f, 1.0f, 0.7f};

    private static final Identifier SCENE_TARGET = Identifier.fromNamespaceAndPath("aerial", "aura_scene");
    private static final Identifier GLOW_SCENE = Identifier.fromNamespaceAndPath("aerial", "aura_glow_scene");
    private static final Identifier GLOW_SMALL_A = Identifier.fromNamespaceAndPath("aerial", "aura_glow_a");
    private static final Identifier GLOW_SMALL_B = Identifier.fromNamespaceAndPath("aerial", "aura_glow_b");
    private static final Identifier GLOW_BLUR = Identifier.fromNamespaceAndPath("aerial", "aura_glow_blur");
    private static final Identifier SCREENQUAD = Identifier.withDefaultNamespace("core/screenquad");
    private static final Identifier SCENE_COPY_FSH = Identifier.fromNamespaceAndPath("aerial", "aura/scene_copy");
    private static final Identifier DOME_FSH = Identifier.fromNamespaceAndPath("aerial", "aura/blast_dome");
    private static final Identifier FORGE_FSH = Identifier.fromNamespaceAndPath("aerial", "aura/forge_haze");
    private static final Identifier GLOW_FSH = Identifier.fromNamespaceAndPath("aerial", "aura/aura_glow");
    private static final Identifier KAWASE_DOWN_FSH = Identifier.fromNamespaceAndPath("aerial", "post/aerial_kawase_down");
    private static final Identifier KAWASE_UP_FSH = Identifier.fromNamespaceAndPath("aerial", "post/aerial_kawase_up");

    private static final CrossFrameResourcePool POOL = new CrossFrameResourcePool(3);

    private static final Cached[] CACHE = buildCache();
    private static final List<PostChain> PENDING_CLOSE = new ArrayList<>();

    private AuraPostChains() {
    }

    private static final class Cached {
        @Nullable
        PostChain chain;
        int key = -1;
        int width = -1;
        int height = -1;
    }

    private static Cached[] buildCache() {
        Cached[] cache = new Cached[Slot.values().length];
        for (int i = 0; i < cache.length; i++) {
            cache[i] = new Cached();
        }
        return cache;
    }

    /** The resource pool every aura chain draws its transient targets from. */
    public static CrossFrameResourcePool pool() {
        return POOL;
    }

    /**
     * Ends the aura's render frame: recycles the transient targets and closes the chains a rebuild replaced.
     *
     * <p>Call exactly once per frame, after every aura effect has run. Without the pool's own end-of-frame the
     * targets it hands out are never reclaimed and each frame allocates a fresh set.
     */
    public static void endFrame() {
        POOL.endFrame();
        closePending();
    }

    /** Closes the chains a rebuild replaced. Call once per frame, outside the frame graph. */
    public static void closePending() {
        if (PENDING_CLOSE.isEmpty()) {
            return;
        }
        for (PostChain chain : PENDING_CLOSE) {
            chain.close();
        }
        PENDING_CLOSE.clear();
    }

    /** Drops every chain, for a resource reload. */
    public static void invalidate() {
        for (Cached cached : CACHE) {
            if (cached.chain != null) {
                PENDING_CLOSE.add(cached.chain);
            }
            cached.chain = null;
            cached.key = -1;
            cached.width = -1;
            cached.height = -1;
        }
    }

    /** The chain for one effect at the current frame size, or null when its shader failed to compile. */
    @Nullable
    public static PostChain get(Slot slot, int width, int height) {
        return get(slot, 0, width, height);
    }

    /**
     * The glow chain for one blur kernel at the current frame size.
     *
     * <p>Separate from {@link #get} because this chain bakes its kernel into the uniforms of four passes at
     * build time, so a change of radius is a rebuild and not a write.
     */
    @Nullable
    public static PostChain glow(int kernelKey, int width, int height) {
        return get(Slot.GLOW, kernelKey, width, height);
    }

    @Nullable
    private static PostChain get(Slot slot, int key, int width, int height) {
        Cached cached = CACHE[slot.ordinal()];
        if (cached.chain != null && cached.key == key && cached.width == width && cached.height == height) {
            return cached.chain;
        }
        if (cached.chain != null) {
            PENDING_CLOSE.add(cached.chain);
            cached.chain = null;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ShaderManagerAccessor shaders = (ShaderManagerAccessor) minecraft.getShaderManager();
        PostChainConfig config = switch (slot) {
            case SCENE_COPY -> sceneCopyConfig(width, height);
            case DOME -> refractionConfig(DOME_FSH, DOME_BLOCK, domePlaceholder());
            case FORGE -> refractionConfig(FORGE_FSH, FORGE_BLOCK, forgePlaceholder());
            case GLOW -> glowConfig(key, width, height);
        };
        Set<Identifier> external = slot == Slot.GLOW
                ? Set.of(PostChain.MAIN_TARGET_ID, SILHOUETTE)
                : LevelTargetBundle.MAIN_TARGETS;
        try {
            cached.chain = PostChain.load(config, minecraft.getTextureManager(), external,
                    Identifier.fromNamespaceAndPath("aerial", "runtime_aura_" + slot.ordinal()),
                    shaders.aerial$postChainProjection(),
                    shaders.aerial$postChainProjectionMatrixBuffer());
        } catch (ShaderManager.CompilationException e) {
            cached.chain = null;
        }
        cached.key = key;
        cached.width = width;
        cached.height = height;
        return cached.chain;
    }

    /** The scene snapshot the refraction passes sample, or null before the copy has run. */
    @Nullable
    public static RenderTarget sceneTarget() {
        PostChain chain = CACHE[Slot.SCENE_COPY.ordinal()].chain;
        if (chain == null) {
            return null;
        }
        return ((PostChainAccessor) chain).aerial$persistentTargets().get(SCENE_TARGET);
    }

    private static PostChainConfig sceneCopyConfig(int width, int height) {
        PostChainConfig.InternalTarget scene = new PostChainConfig.InternalTarget(
                Optional.of(width), Optional.of(height), true, 0);
        PostChainConfig.Pass copy = new PostChainConfig.Pass(SCREENQUAD, SCENE_COPY_FSH,
                List.of(new PostChainConfig.TargetInput("In", PostChain.MAIN_TARGET_ID, false, false)),
                SCENE_TARGET, Map.of());
        return new PostChainConfig(Map.of(SCENE_TARGET, scene), List.of(copy));
    }

    /**
     * One full-screen refraction pass: it samples the scene copy and the main target's depth, and writes the
     * main target.
     */
    private static PostChainConfig refractionConfig(Identifier shader, String block, List<UniformValue> layout) {
        PostChainConfig.Pass pass = new PostChainConfig.Pass(SCREENQUAD, shader,
                List.of(new PostChainConfig.TargetInput("Scene", SCENE_TARGET, false, true),
                        new PostChainConfig.TargetInput("Depth", PostChain.MAIN_TARGET_ID, true, false)),
                PostChain.MAIN_TARGET_ID, Map.of(block, layout));
        return new PostChainConfig(Map.of(), List.of(pass));
    }

    /** The dome block's layout: the inverse view-projection, a control vector, and two vectors per dome. */
    private static List<UniformValue> domePlaceholder() {
        List<UniformValue> values = new ArrayList<>();
        values.add(new UniformValue.Matrix4x4Uniform(new Matrix4f()));
        values.add(new UniformValue.Vec4Uniform(new Vector4f()));
        for (int i = 0; i < MAX_DOMES * 2; i++) {
            values.add(new UniformValue.Vec4Uniform(new Vector4f()));
        }
        return List.copyOf(values);
    }

    /**
     * The glow chain: a scene snapshot, a four-pass Kawase pyramid over the silhouette, and the composite.
     *
     * <p>The composite writes the world and cannot also read it, so the first pass copies the world aside.
     * That copy is transient — unlike {@link #SCENE_TARGET}, nothing outside this chain looks at it.
     */
    private static PostChainConfig glowConfig(int kernelKey, int width, int height) {
        int smallWidth = Math.max(1, width / GLOW_DOWNSCALE);
        int smallHeight = Math.max(1, height / GLOW_DOWNSCALE);
        float kernel = kernelKey / 100.0f * GLOW_KERNEL_SCALE;

        Map<Identifier, PostChainConfig.InternalTarget> targets = new HashMap<>(4);
        targets.put(GLOW_SCENE, new PostChainConfig.InternalTarget(
                Optional.of(width), Optional.of(height), false, 0));
        PostChainConfig.InternalTarget small = new PostChainConfig.InternalTarget(
                Optional.of(smallWidth), Optional.of(smallHeight), false, 0);
        targets.put(GLOW_SMALL_A, small);
        targets.put(GLOW_SMALL_B, small);
        targets.put(GLOW_BLUR, small);

        List<PostChainConfig.Pass> passes = List.of(
                new PostChainConfig.Pass(SCREENQUAD, SCENE_COPY_FSH,
                        List.of(new PostChainConfig.TargetInput("In", PostChain.MAIN_TARGET_ID, false, false)),
                        GLOW_SCENE, Map.of()),
                blurPass(KAWASE_DOWN_FSH, SILHOUETTE, GLOW_SMALL_A, kernel * GLOW_SCALES[0]),
                blurPass(KAWASE_DOWN_FSH, GLOW_SMALL_A, GLOW_SMALL_B, kernel * GLOW_SCALES[1] / GLOW_DOWNSCALE),
                blurPass(KAWASE_UP_FSH, GLOW_SMALL_B, GLOW_SMALL_A, kernel * GLOW_SCALES[2] / GLOW_DOWNSCALE),
                blurPass(KAWASE_UP_FSH, GLOW_SMALL_A, GLOW_BLUR, kernel * GLOW_SCALES[3] / GLOW_DOWNSCALE),
                new PostChainConfig.Pass(SCREENQUAD, GLOW_FSH,
                        List.of(new PostChainConfig.TargetInput("Sharp", SILHOUETTE, false, true),
                                new PostChainConfig.TargetInput("Blur", GLOW_BLUR, false, true),
                                new PostChainConfig.TargetInput("Scene", GLOW_SCENE, false, false)),
                        PostChain.MAIN_TARGET_ID,
                        Map.of(GLOW_BLOCK, List.of(new UniformValue.Vec4Uniform(new Vector4f())))));
        return new PostChainConfig(Map.copyOf(targets), passes);
    }

    /** One Kawase step. The direction the blur shaders take is unused by them; only the kernel matters. */
    private static PostChainConfig.Pass blurPass(Identifier shader, Identifier in, Identifier out, float kernel) {
        return new PostChainConfig.Pass(SCREENQUAD, shader,
                List.of(new PostChainConfig.TargetInput("In", in, false, true)),
                out,
                Map.of("AerialBlurConfig", List.of(
                        new UniformValue.Vec2Uniform(new org.joml.Vector2f(1.0f, 1.0f)),
                        new UniformValue.FloatUniform(kernel))));
    }

    /** The forge block's layout: the inverse view-projection, control, shape, the hot tint, then the sites. */
    private static List<UniformValue> forgePlaceholder() {
        List<UniformValue> values = new ArrayList<>();
        values.add(new UniformValue.Matrix4x4Uniform(new Matrix4f()));
        values.add(new UniformValue.Vec4Uniform(new Vector4f()));
        values.add(new UniformValue.Vec4Uniform(new Vector4f()));
        values.add(new UniformValue.Vec4Uniform(new Vector4f()));
        for (int i = 0; i < MAX_SITES; i++) {
            values.add(new UniformValue.Vec4Uniform(new Vector4f()));
        }
        return List.copyOf(values);
    }
}
