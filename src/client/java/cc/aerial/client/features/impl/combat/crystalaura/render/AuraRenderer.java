/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.aerial.client.features.impl.combat.crystalaura.render;

import cc.aerial.client.event.impl.render.Render2DEvent;
import cc.aerial.client.event.impl.render.Render3DEvent;
import cc.aerial.client.features.impl.combat.crystalaura.config.RenderSettings;
import cc.aerial.client.render.CameraRenderStateHelper;
import cc.aerial.client.render.ESPUtility;
import cc.aerial.client.render.aura.AuraGlow;
import cc.aerial.client.render.aura.AuraScreenEffects;
import cc.aerial.client.render.font.AerialFont;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * World overlay of the aura's current plan: the block the next crystal will be placed on, the block an obsidian
 * base will be placed at, the hitbox of the crystal that will be attacked, the target's kill-zone ring
 * ({@link AuraTargetRenderer}), the expanding shockwave of every attack we actually sent, and the damage
 * figures over the placement.
 *
 * <p>Pure rendering; mirrors no vanilla logic. The damage text is projected and drawn on the HUD layer, so the
 * module has to route both a {@code Render3DEvent} to {@link #render} and a {@code Render2DEvent} to
 * {@link #renderOverlay}.
 *
 * <h2>Animation</h2>
 *
 * <p>Nothing in here pops. {@link #render} first pushes this frame's snapshot into the tweens and then draws
 * whatever they currently show, so every element survives the snapshot going {@code null}: the boxes glide from
 * the old block to the new one instead of teleporting, the damage figures roll towards their new value, and
 * everything fades rather than blinking. The animation-speed setting is copied into the shared tween divisor
 * once per frame and scales all of it.
 *
 * <h2>Layers this tier does not draw</h2>
 *
 * <p>This is the CPU-geometry tier. The additive halo, the refraction dome and the forge are a later stage;
 * every place they hook in is a named no-op below ({@link #renderGlowHalo}, {@link #renderBlastDome},
 * {@link #drawForgeGeometry}, {@link #renderForgePlume}, {@link #recordShaderDetonation},
 * {@link #releaseShaderTier}), so wiring them up is a matter of filling those bodies in.
 */
public final class AuraRenderer {
    /** How far a detonation's refraction shell grows, in blocks. */
    private static final float DOME_MAX_RADIUS = 6f;

    /** How long it takes to get there, in milliseconds; the shell ends the moment it does. */
    private static final long DOME_DURATION_MS = 700L;

    /** Fraction of the shell's life spent ramping the fade up, and back down. */
    private static final float DOME_FADE_IN = 0.12f;
    private static final float DOME_FADE_OUT = 0.45f;

    /** Width of the light radial smear the shell adds, in screen fractions. */
    private static final float DOME_RADIAL_BLUR = 0.003f;

    /** How much taller the plume is than the site is wide, and its peak displacement. */
    private static final float HAZE_RISE_SCALE = 1.6f;
    private static final float HAZE_AMPLITUDE = 0.014f;

    /** How much of the hot colour the plume adds at full heat. */
    private static final float HAZE_TINT = 0.1f;


    private AuraRenderer() {
    }

    /** Lifetime and end radius of one shockwave ripple, and how many may be alive at once. */
    private static final float SHOCKWAVE_MS = 260f;
    private static final float SHOCKWAVE_RADIUS = 4f;
    private static final int SHOCKWAVE_RING = 24;

    /** The inner ring: shorter, smaller and dimmer than the outer one. */
    private static final float SHOCKWAVE_INNER_MS = 170f;
    private static final float SHOCKWAVE_INNER_SCALE = 0.55f;
    private static final float SHOCKWAVE_INNER_ALPHA = 0.7f;

    /** The orbit crown around a ripple; the spin is how far it turns over one ripple's whole life. */
    private static final int ORBIT_POINTS = 18;
    private static final double ORBIT_STEP_DEG = 20.0;
    private static final float ORBIT_SPIN_DEG = 40f;
    private static final float ORBIT_SCALE = 1.15f;
    private static final float ORBIT_SHRINK = 0.45f;
    private static final float ORBIT_ALPHA = 0.55f;
    private static final float ORBIT_BASE_HEIGHT = 0.1f;
    private static final float ORBIT_HEIGHT = 0.45f;
    private static final double DEG_TO_RAD = Math.PI / 180.0;

    /** Floats per orbit tick (two endpoints), and how wide its ribbon is drawn. */
    private static final int ORBIT_STRIDE = 6;
    private static final float ORBIT_WIDTH_SCALE = 0.75f;

    /** Flash of the attacked crystal's outline: how long, and how far towards white it pulls. */
    private static final float ATTACK_FLASH_MS = 180f;
    private static final float ATTACK_FLASH_MIX = 0.8f;

    /** Damage text: anchored one and a bit blocks over the centre of the placement block. */
    private static final double TEXT_HEIGHT = 1.6;
    private static final float TEXT_SIZE = 11f;
    private static final String SELF_SEPARATOR = " / ";

    /** Tween lengths of the plan overlay, before the shared animation-speed divisor. */
    private static final long GLIDE_MS = 200L;
    private static final long BLOOM_MS = 260L;
    private static final long FADE_MS = 180L;
    private static final long TEXT_ROLL_MS = 180L;
    private static final long TEXT_POP_MS = 200L;
    private static final long TEXT_COLOR_MS = 220L;

    /** Peak of the text pop, the floor its spring undershoot is clamped at, and the arrival bloom's gain. */
    private static final float TEXT_POP_PEAK = 1.18f;
    private static final float TEXT_MIN_POP = 0.5f;
    private static final float BLOOM_GAIN = 1.4f;

    private static final double HALF_BLOCK = 0.5;
    private static final int MAX_CHANNEL = 255;

    /**
     * Live shockwaves as a ring buffer of at most {@link #SHOCKWAVE_RING} entries: centre and wall-clock start.
     * A slot is cleared once its ripple finished; the oldest entry is overwritten when the buffer wraps.
     */
    private static final Vec3[] SHOCKWAVE_CENTERS = new Vec3[SHOCKWAVE_RING];
    private static final long[] SHOCKWAVE_STARTS = new long[SHOCKWAVE_RING];
    private static int shockwaveCursor;

    /** Wall clock of the most recent attack, shared with the shockwaves; drives the attack-box flash. */
    private static long lastAttackAt;

    /** The planned placement block and the obsidian base block, each gliding to wherever the plan moved it. */
    private static final GlidingBlock PLACE_BOX = new GlidingBlock();
    private static final GlidingBlock BASE_BOX = new GlidingBlock();

    /** The two damage figures as rolling counters, so the numbers ease to their new value instead of jumping. */
    private static final AuraAnim DAMAGE = new AuraAnim(TEXT_ROLL_MS, 0f, AuraEase.OUT_CUBIC);
    private static final AuraAnim SELF_DAMAGE = new AuraAnim(TEXT_ROLL_MS, 0f, AuraEase.OUT_CUBIC);

    /** Scale "pop" of the damage text: snapped to the peak and springing back to 1 on every new position. */
    private static final AuraAnim TEXT_POP = new AuraAnim(TEXT_POP_MS, 1f, AuraEase.SPRING);

    /** Cold to hot (or lethal) text colour, cross-faded like the ring's. */
    private static final AuraAnimColor TEXT_COLOR =
            new AuraAnimColor(TEXT_COLOR_MS, CrystalColors.WHITE, AuraEase.OUT_CUBIC);

    /**
     * Endpoints of the orbit's vertical ticks, refilled in place every frame — the crown must not allocate, it
     * is rebuilt for every live ripple of every frame.
     */
    private static final float[] ORBIT_SEGMENTS = new float[ORBIT_POINTS * ORBIT_STRIDE];

    /** One detonation: where it happened and when. */
    public record Shockwave(Vec3 center, long startMs) {
        /** Progress from 0 to 1 over the ripple's life; at or above 1 it is finished. */
        public float progress(long nowMs) {
            return (nowMs - startMs) / SHOCKWAVE_MS;
        }
    }

    /**
     * Reports a detonation whose attack really went out.
     *
     * <p>Deliberately NOT gated on the shockwave setting: several effects hang off this one event, and each
     * owns its own toggle downstream. Gating the report itself would silently switch off the others with the
     * ripple.
     */
    public static void recordShockwave(Vec3 center) {
        long now = System.currentTimeMillis();
        SHOCKWAVE_CENTERS[shockwaveCursor] = center;
        SHOCKWAVE_STARTS[shockwaveCursor] = now;
        shockwaveCursor = (shockwaveCursor + 1) % SHOCKWAVE_RING;
        lastAttackAt = now;
        recordShaderDetonation(center);
    }

    /** The live ripples, oldest first, with the finished ones dropped. */
    public static List<Shockwave> shockwaves() {
        long now = System.currentTimeMillis();
        List<Shockwave> live = new ArrayList<>();
        for (int step = 0; step < SHOCKWAVE_RING; step++) {
            int index = (shockwaveCursor + step) % SHOCKWAVE_RING;
            Vec3 center = SHOCKWAVE_CENTERS[index];
            if (center == null) {
                continue;
            }
            if (now - SHOCKWAVE_STARTS[index] >= SHOCKWAVE_MS) {
                SHOCKWAVE_CENTERS[index] = null;
                continue;
            }
            live.add(new Shockwave(center, SHOCKWAVE_STARTS[index]));
        }
        return live;
    }

    /** Drops every live shockwave and every animation; called from the module's own reset. */
    public static void reset() {
        Arrays.fill(SHOCKWAVE_CENTERS, null);
        shockwaveCursor = 0;
        lastAttackAt = 0L;
        PLACE_BOX.reset();
        BASE_BOX.reset();
        DAMAGE.snap(0f);
        SELF_DAMAGE.snap(0f);
        TEXT_POP.snap(1f);
        TEXT_COLOR.snap(CrystalColors.WHITE);
        AuraTargetRenderer.reset();
        releaseShaderTier();
    }

    /**
     * Draws the planned boxes, the target ring and the live shockwaves, and pushes this frame's snapshot into
     * every tween on the way.
     *
     * <p>The geometry is built once per pass and would be emitted TWICE when the glow is on: once straight onto
     * the world target, and once into an offscreen target that blurs it into a halo underneath. Passing the
     * same drawing to both is what keeps the halo registered with the shape. The two passes differ in exactly
     * ONE number: a halo derived from the colours it is handed gets a nearly black source when a fill at alpha
     * 40 of 255 has already been scaled down again by the Fresnel term, so the glow pass uses a style whose
     * alpha gain is the module's glow gain while the world pass keeps 1 and draws at exactly the alpha chosen.
     * That is why the geometry takes the style as a parameter rather than closing over it.
     */
    public static void render(Render3DEvent event, @Nullable AuraVisuals visuals, RenderSettings settings) {
        AuraAnim.setSpeed(settings.animationSpeed.getValue().floatValue());
        long now = System.currentTimeMillis();
        update(visuals, settings, now);

        boolean shockwaves = settings.shockwave.getValue() && hasLiveShockwave(now);
        boolean hasGeometry = shockwaves || hasPlanResidual() || AuraTargetRenderer.hasResidual();

        if (hasGeometry) {
            drawGeometry(event, visuals, settings, SoftStyle.of(settings, 1f), now, shockwaves);
            if (settings.glow.getValue()) {
                float gain = settings.glowGain.getValue().floatValue();
                renderGlowHalo(event, visuals, settings, SoftStyle.of(settings, gain), now, shockwaves);
            }
        }

        if (settings.dome.getValue()) {
            renderBlastDome(event, settings);
        }
        renderForgePlume(event, settings, now);
    }

    /** Everything the world pass draws, at one style — the unit a glow pass would re-emit unchanged. */
    private static void drawGeometry(Render3DEvent event, @Nullable AuraVisuals visuals,
                                     RenderSettings settings, SoftStyle style, long nowMs,
                                     boolean shockwaves) {
        drawPlan(event, visuals, settings, style, nowMs);
        AuraTargetRenderer.draw(event, settings, style, nowMs);
        drawForgeGeometry(event, settings, style, nowMs);
        if (shockwaves) {
            drawShockwaves(event, settings, style, nowMs);
        }
    }

    /**
     * Pushes this frame's snapshot into every tween; draws nothing. Called unconditionally, so an element that
     * just lost its plan starts fading out instead of being dropped on the spot.
     */
    private static void update(@Nullable AuraVisuals visuals, RenderSettings settings, long nowMs) {
        if (PLACE_BOX.retarget(visuals == null ? null : visuals.placePos())) {
            // The chosen block moved: bump the text so the new figure announces itself.
            TEXT_POP.snap(TEXT_POP_PEAK);
            TEXT_POP.setTarget(1f);
        }
        BASE_BOX.retarget(visuals == null ? null : visuals.basePos());
        reapShockwaves(nowMs);

        if (visuals != null) {
            DAMAGE.setTarget(visuals.bestDamage());
            SELF_DAMAGE.setTarget(visuals.selfDamage());
            TEXT_COLOR.setTarget(targetColor(visuals));
        }
        AuraTargetRenderer.update(visuals, settings, nowMs);
    }

    /**
     * Frees the slots of waves that have run out.
     *
     * <p>This belongs to the once-per-frame update and not to the drawing, because the drawing is emitted
     * twice when the glow is on and anything it reaps would be missing from the second pass.
     */
    private static void reapShockwaves(long nowMs) {
        for (int index = 0; index < SHOCKWAVE_RING; index++) {
            if (SHOCKWAVE_CENTERS[index] == null) {
                continue;
            }
            long age = nowMs - SHOCKWAVE_STARTS[index];
            if (age < 0L || age >= SHOCKWAVE_MS) {
                SHOCKWAVE_CENTERS[index] = null;
            }
        }
    }

    /** True while a box is still visible after the plan is gone. */
    private static boolean hasPlanResidual() {
        return PLACE_BOX.visible() || BASE_BOX.visible();
    }

    /**
     * Damage figures over the planned placement: the effective damage to the target, coloured by profit, then
     * the self damage in a dimmer colour. Both numbers are the ROLLING values, the whole block is scaled by the
     * pop and faded with the placement marker, and the anchor follows the marker's glide. Skipped when the
     * anchor is behind the camera, where the projection has nothing to return.
     *
     * <p>Takes no plan: everything it draws lives in the tweens {@link #render} already pushed, so the text
     * keeps fading out correctly for a frame that has no plan at all.
     */
    public static void renderOverlay(Render2DEvent event, RenderSettings settings) {
        if (!settings.damageText.getValue()) {
            return;
        }
        float alpha = PLACE_BOX.alpha();
        if (alpha <= 0f) {
            return;
        }
        CameraRenderState camera = CameraRenderStateHelper.get();
        if (camera == null) {
            return;
        }
        Vec3 anchor = PLACE_BOX.center().add(0.0, TEXT_HEIGHT, 0.0);
        Vector4f screen = ESPUtility.project(anchor, camera, event.width(), event.height());
        if (screen == null) {
            return;
        }
        drawDamageText(event.extractor(), screen.x, screen.y, Math.clamp(alpha, 0f, 1f));
    }

    private static void drawDamageText(GuiGraphicsExtractor ctx, float screenX, float screenY, float alpha) {
        AerialFont font = AuraText.font();
        if (font == null) {
            return;
        }
        float size = TEXT_SIZE * Math.max(TEXT_POP.value(), TEXT_MIN_POP);
        String targetText = AuraText.format(DAMAGE.value());
        String selfText = SELF_SEPARATOR + AuraText.format(SELF_DAMAGE.value());
        float width = AuraText.width(font, targetText, size) + AuraText.width(font, selfText, size);
        float left = screenX - width / 2f;
        float top = screenY - AuraText.lineHeight(font, size) / 2f;

        float pen = AuraText.draw(font, ctx, targetText, left, top, size, fade(TEXT_COLOR.value(), alpha));
        AuraText.draw(font, ctx, selfText, pen, top, size, fade(CrystalColors.SELF_TEXT, alpha));
    }

    /** Placement, base and attack boxes, behind the box toggle. */
    private static void drawPlan(Render3DEvent event, @Nullable AuraVisuals visuals, RenderSettings settings,
                                 SoftStyle style, long nowMs) {
        if (!settings.box.getValue()) {
            return;
        }
        int fill = CrystalColors.FILL;
        drawGlidingBlock(event, PLACE_BOX, fill, CrystalColors.OUTLINE, style);
        drawGlidingBlock(event, BASE_BOX, withAlpha(fill, alphaOf(fill) / 2), CrystalColors.OUTLINE, style);

        AABB attackBox = visuals == null ? null : visuals.attackBox();
        if (attackBox != null) {
            SoftGeometry.drawSoftBox(event, attackBox, 0, attackOutline(nowMs), style);
        }
    }

    /**
     * One gliding full block: the drawn corner is the tween between the block we came from and the block we are
     * going to, the outline follows the fade, and the face alpha blooms once on arrival.
     *
     * <p>Through the soft box, so the six faces are Fresnel-weighted and vertically graded and the 12 edges are
     * feathered ribbons instead of one-pixel wires.
     */
    private static void drawGlidingBlock(Render3DEvent event, GlidingBlock block, int fill, int outline,
                                         SoftStyle style) {
        float alpha = block.alpha();
        if (alpha <= 0f) {
            return;
        }
        // The arrival bloom has to be able to push the face past its configured alpha, so the face channel is
        // computed directly rather than through a helper that can only dim.
        int face = withAlpha(fill, (int) (alphaOf(fill) * alpha * block.fillBoost()));
        SoftGeometry.drawSoftBox(event, block.box(), face, fade(outline, Math.clamp(alpha, 0f, 1f)), style);
    }

    /**
     * Outline colour of the crystal we are about to hit, flashed towards white for a moment from the instant
     * the attack packet went out (the same stamp the shockwave uses).
     */
    private static int attackOutline(long nowMs) {
        int base = CrystalColors.ATTACK;
        if (lastAttackAt == 0L) {
            return base;
        }
        float rest = 1f - (nowMs - lastAttackAt) / ATTACK_FLASH_MS;
        if (rest <= 0f || rest > 1f) {
            return base;
        }
        float flash = AuraEase.OUT_CUBIC.ease(rest);
        int lit = Math.clamp((int) (alphaOf(base) + (MAX_CHANNEL - alphaOf(base)) * flash), 0, MAX_CHANNEL);
        return withAlpha(AuraAnimColor.lerp(base, CrystalColors.WHITE, flash * ATTACK_FLASH_MIX), lit);
    }

    /** True while any slot still has a ripple inside its window. */
    private static boolean hasLiveShockwave(long now) {
        for (int index = 0; index < SHOCKWAVE_RING; index++) {
            if (SHOCKWAVE_CENTERS[index] != null && now - SHOCKWAVE_STARTS[index] < SHOCKWAVE_MS) {
                return true;
            }
        }
        return false;
    }

    /**
     * One ripple per live shockwave, plus the optional orbit. Finished slots are released here, so a paused
     * game never keeps a stale ripple.
     */
    private static void drawShockwaves(Render3DEvent event, RenderSettings settings, SoftStyle style,
                                       long now) {
        int color = CrystalColors.SHOCKWAVE;
        for (int index = 0; index < SHOCKWAVE_RING; index++) {
            Vec3 center = SHOCKWAVE_CENTERS[index];
            if (center == null) {
                continue;
            }
            float age = now - SHOCKWAVE_STARTS[index];
            if (age < 0f || age >= SHOCKWAVE_MS) {
                continue;
            }
            drawRipple(event, center, color, age, style);
            if (settings.orbit.getValue()) {
                drawOrbit(event, center, color, age, style);
            }
        }
    }

    /**
     * Two concentric rings so the wave reads as having depth: the outer one eases out over the full lifetime
     * with an {@code (1 - t)^2} alpha, the inner one snaps out on a back-out curve over a much shorter window
     * and is gone before the outer is half-way.
     *
     * <p>Both are world-space bands that thin with distance and fade to nothing on both rims, rather than the
     * constant two-pixel stroke that made a four-block wave read as a wire circle.
     */
    private static void drawRipple(Render3DEvent event, Vec3 center, int color, float age, SoftStyle style) {
        float progress = age / SHOCKWAVE_MS;
        float rest = 1f - progress;
        SoftGeometry.drawSoftOutlineRing(event, center,
                SHOCKWAVE_RADIUS * AuraEase.OUT_CUBIC.ease(progress), fade(color, rest * rest), style);

        float innerProgress = age / SHOCKWAVE_INNER_MS;
        if (innerProgress >= 1f) {
            return;
        }
        SoftGeometry.drawSoftOutlineRing(event, center,
                SHOCKWAVE_RADIUS * SHOCKWAVE_INNER_SCALE * AuraEase.OUT_BACK.ease(innerProgress),
                fade(color, (1f - innerProgress) * SHOCKWAVE_INNER_ALPHA), style);
    }

    /**
     * The orbital flourish: eighteen points every twenty degrees at
     * {@code (cos, sin)(angle + t * spin) * radius * scale}, each raised by
     * {@code base + amplitude * |sin(angle)|}, so the ring looks like a crown that turns and shrinks as the
     * wave decays. The ticks go into the reusable buffer and out as ONE feathered-ribbon mesh — no allocation
     * inside the loop, and no one-pixel wires left in the module.
     */
    private static void drawOrbit(Render3DEvent event, Vec3 center, int color, float age, SoftStyle style) {
        float progress = age / SHOCKWAVE_MS;
        float rest = 1f - progress;
        int faded = fade(color, rest * rest * ORBIT_ALPHA);
        if (alphaOf(faded) == 0) {
            return;
        }

        float radius = SHOCKWAVE_RADIUS * (ORBIT_SCALE - ORBIT_SHRINK * progress);
        float spin = progress * ORBIT_SPIN_DEG;
        for (int step = 0; step < ORBIT_POINTS; step++) {
            double degrees = step * ORBIT_STEP_DEG;
            double theta = (degrees + spin) * DEG_TO_RAD;
            float x = (float) (Math.cos(theta) * radius);
            float z = (float) (Math.sin(theta) * radius);
            float height = ORBIT_BASE_HEIGHT
                    + ORBIT_HEIGHT * (float) Math.abs(Math.sin(degrees * DEG_TO_RAD));
            int base = step * ORBIT_STRIDE;
            ORBIT_SEGMENTS[base] = x;
            ORBIT_SEGMENTS[base + 1] = 0f;
            ORBIT_SEGMENTS[base + 2] = z;
            ORBIT_SEGMENTS[base + 3] = x;
            ORBIT_SEGMENTS[base + 4] = height;
            ORBIT_SEGMENTS[base + 5] = z;
        }
        SoftGeometry.drawSoftSegments(event, center, ORBIT_SEGMENTS, ORBIT_POINTS, faded, style,
                ORBIT_WIDTH_SCALE);
    }

    /**
     * Cold to hot by the target renderer's heat ratio, at full alpha so the glyphs stay readable. A placement
     * that kills outright uses the attack colour instead: the palette has no separate lethal colour, and that
     * one is already the module's "this crystal ends it" red.
     */
    private static int targetColor(AuraVisuals visuals) {
        if (visuals.lethal()) {
            return withAlpha(CrystalColors.ATTACK, MAX_CHANNEL);
        }
        float ratio = AuraTargetRenderer.heatRatio(visuals);
        return withAlpha(AuraAnimColor.lerp(CrystalColors.RING_COLD, CrystalColors.RING_HOT, ratio),
                MAX_CHANNEL);
    }

    // ------------------------------------------------------------------ shader-tier hooks

    /**
     * Re-emits the frame's geometry into an offscreen target, blurs it and composites a soft halo underneath.
     *
     * <p>The style handed over already carries the module's glow gain, which is the one number that differs
     * from the world pass; everything else about the two passes is the same call with the same arguments, and
     * that is what keeps the halo registered with the shape it belongs to.
     */
    private static void renderGlowHalo(Render3DEvent event, @Nullable AuraVisuals visuals,
                                       RenderSettings settings, SoftStyle glowStyle, long nowMs,
                                       boolean shockwaves) {
        AuraGlow.capture(event,
                new AuraGlow.GlowSettings(
                        settings.glowRadius.getValue().floatValue(),
                        settings.glowStrength.getValue().floatValue(),
                        settings.glowFalloff.getValue().floatValue(),
                        settings.glowAlpha.getValue().floatValue(),
                        AuraGlow.DEFAULT_INTERIOR_DAMPING),
                glowEvent -> drawGeometry(glowEvent, visuals, settings, glowStyle, nowMs, shockwaves));
    }

    /**
     * The world-anchored refraction dome of a detonation, which displaces the finished scene rather than
     * drawing into it.
     *
     * <p>No-op in the CPU-geometry tier; the shader tier fills this in.
     */
    private static void renderBlastDome(Render3DEvent event, RenderSettings settings) {
        Vec3 camera = event.camera().pos;
        long now = System.currentTimeMillis();
        AuraScreenEffects.setDomeStrength(settings.domeStrength.getValue().floatValue());
        for (Shockwave wave : shockwaves()) {
            float progress = Math.clamp((now - wave.startMs()) / (float) DOME_DURATION_MS, 0f, 1f);
            if (progress >= 1f) {
                continue;
            }
            // Ease out: quick off the mark, settling as it reaches full size, the way a blast front
            // actually decelerates.
            float remaining = 1f - progress;
            float radius = DOME_MAX_RADIUS * (1f - remaining * remaining);
            AuraScreenEffects.submitDome(new AuraScreenEffects.Dome(
                    wave.center().subtract(camera),
                    radius,
                    domeFade(progress),
                    settings.domeAmplitude.getValue().floatValue(),
                    settings.domeRipples.getValue().floatValue(),
                    DOME_RADIAL_BLUR));
        }
    }

    /** Ramps up over the opening fraction of the shell's life and back down over the closing one. */
    private static float domeFade(float progress) {
        float rise = smoothStep(0f, DOME_FADE_IN, progress);
        float fall = 1f - smoothStep(1f - DOME_FADE_OUT, 1f, progress);
        return Math.clamp(rise * fall, 0f, 1f);
    }

    /** The classic smooth step, clamped at both ends. */
    private static float smoothStep(float from, float to, float value) {
        if (to <= from) {
            return value < from ? 0f : 1f;
        }
        float t = Math.clamp((value - from) / (to - from), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /**
     * The forge's world-space half: an additive pool on the ground at the site the aura is spamming crystals
     * on, its feathered rim, the turning spokes and the crown of rising embers.
     *
     * <p>No-op in the CPU-geometry tier. It runs inside the geometry pass because it is ordinary soft geometry
     * and belongs in the halo with everything else; the later stage fills it in.
     */
    private static void drawForgeGeometry(Render3DEvent event, RenderSettings settings, SoftStyle style,
                                          long nowMs) {
        AuraForgeGeometry.draw(event, settings, style, nowMs);
    }

    /**
     * The forge's screen-space half: a world-anchored heat plume over the site that displaces the scene the way
     * hot air does.
     *
     * <p>No-op in the CPU-geometry tier; the shader tier fills this in. It runs last and independently of any
     * geometry, because it displaces the finished scene and has nothing to draw into the world pass.
     */
    private static void renderForgePlume(Render3DEvent event, RenderSettings settings, long nowMs) {
        if (!settings.forge.getValue() || !settings.forgeHaze.getValue()) {
            return;
        }
        float radius = settings.forgeRadius.getValue().floatValue();
        AuraScreenEffects.setForgeShape(
                settings.forgeStrength.getValue().floatValue(),
                radius,
                radius * HAZE_RISE_SCALE,
                HAZE_AMPLITUDE,
                CrystalColors.FORGE_HOT,
                HAZE_TINT,
                nowMs / 1000f);
        Vec3 camera = event.camera().pos;
        for (AuraForgeLedger.Site site : AuraForgeLedger.sites(nowMs)) {
            if (site.heat() < AuraForgeLedger.DEAD_HEAT) {
                continue;
            }
            AuraScreenEffects.submitSite(
                    new AuraScreenEffects.Site(site.center().subtract(camera), site.heat()));
        }
    }

    /**
     * The same detonation that starts a ripple also drives the refraction dome and heats the forge at that
     * block — one hit is a flicker, a sustained cycle pins the site at full heat.
     *
     * <p>No-op in the CPU-geometry tier; the shader and forge tiers fill this in.
     */
    private static void recordShaderDetonation(Vec3 center) {
        // The shell reads the ripple list directly, so only the forge needs telling.
        AuraForgeLedger.hit(center, System.currentTimeMillis());
    }

    /**
     * Releases whatever the shader tier holds — render targets, blur pyramids, the forge's heat ledger.
     *
     * <p>No-op in the CPU-geometry tier; the shader tier fills this in.
     */
    private static void releaseShaderTier() {
        AuraForgeLedger.clear();
        AuraScreenEffects.reset();
        AuraGlow.release();
    }

    // ------------------------------------------------------------------ colour arithmetic

    private static int alphaOf(int argb) {
        return argb >>> 24;
    }

    private static int withAlpha(int argb, int alpha) {
        return (Math.clamp(alpha, 0, MAX_CHANNEL) << 24) | (argb & 0x00FFFFFF);
    }

    /** Dims only, like the colour helper it replaces: a factor at or above 1 leaves the colour alone. */
    private static int fade(int argb, float factor) {
        return factor >= 1f ? argb : withAlpha(argb, (int) (alphaOf(argb) * factor));
    }

    /**
     * A full block whose DRAWN position is a tween: retargeting it records where it is right now and springs
     * from there to the new block, so a plan that hops one block over glides instead of teleporting. The alpha
     * fades the whole thing in and out with the plan, and the fill boost blooms the face alpha once, at the end
     * of the glide.
     */
    private static final class GlidingBlock {

        private final AuraAnim glide = new AuraAnim(GLIDE_MS, 1f, AuraEase.SPRING);
        private final AuraAnim bloom = new AuraAnim(BLOOM_MS, 0f, AuraEase.OUT_CUBIC);

        /** Opacity of the whole block: 1 while the plan names it, 0 afterwards. */
        private final AuraAnim opacity = new AuraAnim(FADE_MS, 0f, AuraEase.OUT_CUBIC);

        private Vec3 from = Vec3.ZERO;
        private Vec3 to = Vec3.ZERO;
        @Nullable
        private BlockPos key;
        private boolean bloomed = true;

        float alpha() {
            return opacity.value();
        }

        /** True while anything of this block is still on screen. */
        boolean visible() {
            return opacity.value() > 0f;
        }

        /** Extra face-alpha factor of the arrival bloom; 1 once it decayed. */
        float fillBoost() {
            return 1f + bloom.value() * BLOOM_GAIN;
        }

        /**
         * Pushes a position (which may be {@code null}) and returns true when the named block actually CHANGED
         * this frame. The arrival bloom is fired on the first frame the glide is settled, not when it starts.
         */
        boolean retarget(@Nullable BlockPos pos) {
            if (!bloomed && glide.settled()) {
                bloomed = true;
                bloom.snap(1f);
                bloom.setTarget(0f);
            }
            opacity.setTarget(pos == null ? 0f : 1f);
            if (pos == null || pos.equals(key)) {
                return false;
            }

            from = key == null ? cornerOf(pos) : corner();
            to = cornerOf(pos);
            key = pos.immutable();
            bloomed = false;
            glide.snap(0f);
            glide.setTarget(1f);
            return true;
        }

        /** The lower-north-west corner currently drawn at. */
        Vec3 corner() {
            double t = glide.value();
            return new Vec3(
                    from.x + (to.x - from.x) * t,
                    from.y + (to.y - from.y) * t,
                    from.z + (to.z - from.z) * t);
        }

        /** The full block at the corner currently drawn at, in world coordinates. */
        AABB box() {
            Vec3 corner = corner();
            return new AABB(corner.x, corner.y, corner.z, corner.x + 1.0, corner.y + 1.0, corner.z + 1.0);
        }

        /** The centre of the currently drawn block, i.e. the anchor of the damage text. */
        Vec3 center() {
            return corner().add(HALF_BLOCK, 0.0, HALF_BLOCK);
        }

        void reset() {
            glide.snap(1f);
            bloom.snap(0f);
            opacity.snap(0f);
            from = Vec3.ZERO;
            to = Vec3.ZERO;
            key = null;
            bloomed = true;
        }

        private static Vec3 cornerOf(BlockPos pos) {
            return new Vec3(pos.getX(), pos.getY(), pos.getZ());
        }
    }
}
