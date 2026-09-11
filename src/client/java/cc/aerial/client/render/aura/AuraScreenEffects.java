package cc.aerial.client.render.aura;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * The screen-space half of the aura's overlay: the blast domes and the forge plume.
 *
 * <p>Both refract the finished world rather than drawing into it, so neither can run from the world-render
 * event — that fires while the frame graph is still being built. Instead the world pass RECORDS what it wants
 * this frame, and {@link #runFrame()} plays the recording back once the level is finished.
 *
 * <p>Order per frame: copy the finished world into a target the refraction can sample, then the domes, then the
 * plume. Nothing happens at all when neither effect has anything to draw, so the cost is exactly zero on an
 * ordinary frame.
 *
 * <p>Positions are camera-relative. Both shaders unproject through the inverse of projection times view
 * rotation, which lands in that same space, and it keeps the arithmetic away from the precision cliff of
 * absolute world coordinates far from the origin.
 */
public final class AuraScreenEffects {
    private AuraScreenEffects() {
    }

    /** One dome: where it is, how big, and how it looks right now. */
    public record Dome(Vec3 centerRelative, float radius, float fade, float amplitude, float ripples, float blur) {
    }

    /** One forge site: where it is and how hot. */
    public record Site(Vec3 centerRelative, float heat) {
    }

    private static final List<Dome> DOMES = new ArrayList<>();
    private static final List<Site> SITES = new ArrayList<>();

    private static float domeStrength;
    private static float forgeStrength;
    private static float forgeRadius;
    private static float forgeRise;
    private static float forgePeak;
    private static float hotRed;
    private static float hotGreen;
    private static float hotBlue;
    private static float hotAmount;
    private static float timeSeconds;

    /** Records one dome for this frame; extra domes past the shader's capacity are dropped. */
    public static void submitDome(Dome dome) {
        if (DOMES.size() < AuraPostChains.MAX_DOMES) {
            DOMES.add(dome);
        }
    }

    /** Records one forge site for this frame; extra sites past the shader's capacity are dropped. */
    public static void submitSite(Site site) {
        if (SITES.size() < AuraPostChains.MAX_SITES) {
            SITES.add(site);
        }
    }

    /** The overall displacement multiplier of the domes. */
    public static void setDomeStrength(float strength) {
        domeStrength = Math.max(strength, 0f);
    }

    /** The plume's shape and tint for this frame. */
    public static void setForgeShape(float strength, float radius, float rise, float peak,
                                     int hotArgb, float hotAmount, float timeSeconds) {
        AuraScreenEffects.forgeStrength = Math.max(strength, 0f);
        AuraScreenEffects.forgeRadius = radius;
        AuraScreenEffects.forgeRise = rise;
        AuraScreenEffects.forgePeak = peak;
        AuraScreenEffects.hotRed = ((hotArgb >> 16) & 0xFF) / 255f;
        AuraScreenEffects.hotGreen = ((hotArgb >> 8) & 0xFF) / 255f;
        AuraScreenEffects.hotBlue = (hotArgb & 0xFF) / 255f;
        AuraScreenEffects.hotAmount = hotAmount;
        AuraScreenEffects.timeSeconds = timeSeconds;
    }

    /** Drops everything recorded, without drawing. */
    public static void reset() {
        DOMES.clear();
        SITES.clear();
    }

    /**
     * Runs this frame's recorded effects and clears the recording. Call once, after the level is finished and
     * before the HUD.
     */
    public static void runFrame() {
        try {
            if (DOMES.isEmpty() && SITES.isEmpty()) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
            if (main == null || main.width <= 0 || main.height <= 0) {
                return;
            }
            Matrix4f invProjView = inverseProjectionView();
            if (invProjView == null) {
                return;
            }

            PostChain copy = AuraPostChains.get(AuraPostChains.Slot.SCENE_COPY, main.width, main.height);
            if (copy == null) {
                return;
            }
            copy.process(main, AuraPostChains.pool());

            if (!DOMES.isEmpty()) {
                runDomes(main, invProjView);
            }
            if (!SITES.isEmpty()) {
                runForge(main, invProjView);
            }
        } finally {
            DOMES.clear();
            SITES.clear();
        }
    }

    private static void runDomes(RenderTarget main, Matrix4f invProjView) {
        PostChain chain = AuraPostChains.get(AuraPostChains.Slot.DOME, main.width, main.height);
        if (chain == null) {
            return;
        }
        int size = domeBlockSize();
        boolean written = AuraUniforms.write(chain, 0, AuraPostChains.DOME_BLOCK, size, builder -> {
            builder.putMat4f(invProjView);
            builder.putVec4(DOMES.size(), zZeroToOne(), domeStrength, 0f);
            for (int i = 0; i < AuraPostChains.MAX_DOMES; i++) {
                Dome dome = i < DOMES.size() ? DOMES.get(i) : null;
                if (dome == null) {
                    builder.putVec4(0f, 0f, 0f, 0f);
                } else {
                    builder.putVec4((float) dome.centerRelative().x, (float) dome.centerRelative().y,
                            (float) dome.centerRelative().z, dome.radius());
                }
            }
            for (int i = 0; i < AuraPostChains.MAX_DOMES; i++) {
                Dome dome = i < DOMES.size() ? DOMES.get(i) : null;
                if (dome == null) {
                    builder.putVec4(0f, 0f, 0f, 0f);
                } else {
                    builder.putVec4(dome.fade(), dome.amplitude(), dome.ripples(), dome.blur());
                }
            }
        });
        if (written) {
            chain.process(main, AuraPostChains.pool());
        }
    }

    private static void runForge(RenderTarget main, Matrix4f invProjView) {
        PostChain chain = AuraPostChains.get(AuraPostChains.Slot.FORGE, main.width, main.height);
        if (chain == null) {
            return;
        }
        float aspect = (float) main.width / Math.max(main.height, 1);
        int size = forgeBlockSize();
        boolean written = AuraUniforms.write(chain, 0, AuraPostChains.FORGE_BLOCK, size, builder -> {
            builder.putMat4f(invProjView);
            builder.putVec4(SITES.size(), zZeroToOne(), forgeStrength, timeSeconds);
            builder.putVec4(aspect, forgeRadius, forgeRise, forgePeak);
            builder.putVec4(hotRed, hotGreen, hotBlue, hotAmount);
            for (int i = 0; i < AuraPostChains.MAX_SITES; i++) {
                Site site = i < SITES.size() ? SITES.get(i) : null;
                if (site == null) {
                    builder.putVec4(0f, 0f, 0f, 0f);
                } else {
                    builder.putVec4((float) site.centerRelative().x, (float) site.centerRelative().y,
                            (float) site.centerRelative().z, site.heat());
                }
            }
        });
        if (written) {
            chain.process(main, AuraPostChains.pool());
        }
    }

    /**
     * The inverse of projection times view rotation, which unprojects a screen point straight into
     * camera-relative space. Null before the first level render, when the camera state has no matrices yet.
     */
    private static Matrix4f inverseProjectionView() {
        var camera = cc.aerial.client.render.CameraRenderStateHelper.get();
        if (camera == null || camera.projectionMatrix == null || camera.viewRotationMatrix == null) {
            return null;
        }
        return new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix).invert();
    }

    /**
     * Whether the device clips depth to zero-to-one rather than minus-one-to-one, as the shaders' control
     * vector expects it.
     *
     * <p>In 26.1 this was a field on the device itself; 26.2 moved it onto the device info record.
     */
    private static float zZeroToOne() {
        return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1f : 0f;
    }

    /** The dome block's std140 size: a matrix, a control vector, and two vectors per dome. */
    private static int domeBlockSize() {
        Std140SizeCalculator size = new Std140SizeCalculator();
        size.putMat4f();
        size.putVec4();
        for (int i = 0; i < AuraPostChains.MAX_DOMES * 2; i++) {
            size.putVec4();
        }
        return size.get();
    }

    /** The forge block's std140 size: a matrix, control, shape, the hot tint, then one vector per site. */
    private static int forgeBlockSize() {
        Std140SizeCalculator size = new Std140SizeCalculator();
        size.putMat4f();
        size.putVec4();
        size.putVec4();
        size.putVec4();
        for (int i = 0; i < AuraPostChains.MAX_SITES; i++) {
            size.putVec4();
        }
        return size.get();
    }
}
