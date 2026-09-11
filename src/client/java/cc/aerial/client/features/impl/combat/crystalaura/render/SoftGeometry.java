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

import cc.aerial.client.event.impl.render.Render3DEvent;
import cc.aerial.client.render.Render3DUtility;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The soft look of the combat auras' world overlay: volumes instead of decals, feathered ribbons instead of
 * one-pixel wires. All of it is plain CPU geometry through the client's ordinary quad pipeline — no shader, no
 * render target.
 *
 * <p>Three terms replace the flat paint of a stock box:
 *
 * <ol>
 *   <li><b>Fresnel weighting.</b> A box face has a constant normal, so the grazing term can be evaluated per
 *   FACE on the CPU: {@code f = 1 - |dot(normal, toCamera)|} from the face centre, and the face's alpha is
 *   scaled by {@code mix(faceMin, 1, f^fresnelPower)}. Faces seen head-on go faint, faces seen edge-on stay
 *   bright, and because nothing is culled the far side of the box shines through the near side exactly where
 *   the eye expects depth.</li>
 *   <li><b>Vertical gradient.</b> Every vertex is tinted by its own height inside the box, from full alpha at
 *   {@code minY} to {@code topRatio} at {@code maxY}, so no face is a single tone. The top and bottom faces
 *   fall out of the same formula — all four of their vertices share one height — which is why they need no
 *   special case.</li>
 *   <li><b>Feathered outline.</b> The 12 edges become camera-facing ribbons: a solid core with a zero-alpha
 *   shoulder on each side. That zero-alpha outer edge IS the antialiasing — the blend does the smoothing the
 *   rasteriser refuses to do for a line primitive.</li>
 * </ol>
 *
 * <h2>Differences from the LiquidBounce original</h2>
 *
 * <p>LiquidBounce expresses every drawing in a local frame anchored at an {@code origin} and translates the
 * pose stack, because its vertex coordinates would otherwise lose float precision far from the world origin.
 * Aerial's {@code Render3DUtility} subtracts the camera position in double precision before the cast to float,
 * which solves the same problem without the indirection — so every entry point here takes plain WORLD
 * coordinates.
 *
 * <p>LiquidBounce emits triangles; Aerial's filled pipeline is a QUADS pipeline, and every band this file
 * draws (a ribbon segment, a radial slice of an arc) is naturally one quad, so each becomes four vertices
 * rather than six.
 *
 * <p>Aerial's collector runs a submitted geometry callback LATER in the frame, not at the call. Anything a
 * caller may mutate before then has to be copied at submit time — which is why {@link #drawSoftSegments}
 * copies its endpoint buffer even though the buffer exists precisely so the caller can refill it in place.
 *
 * <p>A {@code null} colour in the original is an ARGB of alpha zero here: the original's own
 * {@code isTransparent} guards already treated the two the same everywhere.
 */
public final class SoftGeometry {

    private SoftGeometry() {
    }

    /** Face mask selecting all six faces of a box. */
    public static final int SOFT_ALL_FACES = 0b111111;

    /** Face mask selecting only the four VERTICAL faces, i.e. a column with an open top and bottom. */
    public static final int SOFT_SIDE_FACES = 0b110011;

    /**
     * The overlay is a tactical read of a plan, so it is drawn through terrain throughout — the same choice
     * the module made before the soft path existed.
     */
    private static final boolean THROUGH_WALLS = true;

    /** Stroke width of the hard fallbacks, in pixels. */
    private static final float HARD_LINE_WIDTH = 2f;

    // ------------------------------------------------------------------ boxes

    /** A box drawn as a volume, with all six faces and the style's own vertical gradient. */
    public static void drawSoftBox(Render3DEvent event, AABB box, int fill, int outline, SoftStyle style) {
        drawSoftBox(event, box, fill, outline, style, SOFT_ALL_FACES, style.gradientTop());
    }

    /**
     * A box drawn as a volume instead of a decal.
     *
     * <p>{@code faceMask} keeps only the faces whose bit is set ({@link #SOFT_ALL_FACES},
     * {@link #SOFT_SIDE_FACES}). Note that the {@code enabled = false} fallback goes through the plain filled
     * box, which honours neither the mask nor {@code topRatio}; a caller that needs those in the hard path has
     * to branch itself.
     */
    public static void drawSoftBox(Render3DEvent event, AABB box, int fill, int outline, SoftStyle style,
                                   int faceMask, float topRatio) {
        boolean hasFill = alphaOf(fill) > 0;
        boolean hasEdge = alphaOf(outline) > 0 && style.outlineWidth() > 0f;
        if (!hasFill && !hasEdge) {
            return;
        }
        if (!style.enabled()) {
            if (hasFill) {
                Render3DUtility.boxFilled(event, box, fill, THROUGH_WALLS);
            }
            if (hasEdge) {
                Render3DUtility.boxOutline(event, box, outline, HARD_LINE_WIDTH, THROUGH_WALLS);
            }
            return;
        }

        Vec3 cam = event.camera().pos;
        float x0 = (float) (box.minX - cam.x);
        float y0 = (float) (box.minY - cam.y);
        float z0 = (float) (box.minZ - cam.z);
        float x1 = (float) (box.maxX - cam.x);
        float y1 = (float) (box.maxY - cam.y);
        float z1 = (float) (box.maxZ - cam.z);

        float fade = fadeFactor(length((x0 + x1) * HALF_F, (y0 + y1) * HALF_F, (z0 + z1) * HALF_F), style);
        if (fade <= 0f) {
            return;
        }

        event.collector().submitCustomGeometry(event.poseStack(), Render3DUtility.filled(THROUGH_WALLS),
                (pose, consumer) -> {
                    if (hasFill) {
                        addSoftFaces(pose, consumer, x0, y0, z0, x1, y1, z1, fill, fade, topRatio, style,
                                faceMask);
                    }
                    if (hasEdge) {
                        addSoftEdges(pose, consumer, x0, y0, z0, x1, y1, z1, outline, fade, style);
                    }
                });
    }

    // ------------------------------------------------------------------ rings and arcs

    /** A closed horizontal band, feathered on BOTH radial rims — the ring equivalent of a box outline. */
    public static void drawSoftRing(Render3DEvent event, Vec3 center, float outerRadius, float innerRadius,
                                    int outerColor, int innerColor, SoftStyle style) {
        drawSoftRing(event, center, outerRadius, innerRadius, outerColor, innerColor, style, false, false);
    }

    /**
     * A closed horizontal band centred on {@code center}, feathered on both rims.
     *
     * <p>{@code solidCenter} and {@code additive} are handed straight to {@link #drawSoftArc} — with
     * {@code innerRadius = 0} and both set, this is a filled additive pool rather than a ring.
     */
    public static void drawSoftRing(Render3DEvent event, Vec3 center, float outerRadius, float innerRadius,
                                    int outerColor, int innerColor, SoftStyle style, boolean solidCenter,
                                    boolean additive) {
        drawSoftArc(event, center, outerRadius, innerRadius, 0.0, TWO_PI, outerColor, innerColor, style,
                solidCenter, additive);
    }

    /** A thin free-standing ring of world-space width, for marks whose only job is to be a ring. */
    public static void drawSoftOutlineRing(Render3DEvent event, Vec3 center, float radius, int color,
                                           SoftStyle style) {
        drawSoftOutlineRing(event, center, radius, color, style, false);
    }

    /**
     * A thin free-standing ring of world-space width (the attack shockwave).
     *
     * <p>The band grows with {@code radius} so an expanding wave thins out in perspective rather than staying a
     * constant screen-space stroke, and both rims fade to zero. The hard fallback is a plain line circle —
     * antialiased, but screen-space thin, and therefore exactly the wire look the soft path exists to remove.
     */
    public static void drawSoftOutlineRing(Render3DEvent event, Vec3 center, float radius, int color,
                                           SoftStyle style, boolean additive) {
        if (radius <= 0f || alphaOf(color) == 0) {
            return;
        }
        if (!style.enabled()) {
            Render3DUtility.circle(event, center, radius, hardSteps(TWO_PI), index -> color, HARD_LINE_WIDTH,
                    THROUGH_WALLS);
            return;
        }
        float band = Math.max(radius * RIPPLE_BAND_RATIO, style.outlineWidth() * RIPPLE_MIN_WIDTHS);
        float inner = Math.max(radius - band, 0f);
        drawSoftArc(event, center, radius, inner, 0.0, TWO_PI, color, color, style, false, additive);
    }

    /** One angular slice of a horizontal band, with an inner AND an outer feather zone. */
    public static void drawSoftArc(Render3DEvent event, Vec3 center, float outerRadius, float innerRadius,
                                   double fromRad, double toRad, int outerColor, int innerColor,
                                   SoftStyle style) {
        drawSoftArc(event, center, outerRadius, innerRadius, fromRad, toRad, outerColor, innerColor, style,
                false, false);
    }

    /**
     * One angular slice of a horizontal band, {@code [fromRad, toRad]}, with an inner AND an outer feather
     * zone.
     *
     * <p>The band is emitted as four concentric rails — {@code inner}, {@code inner + zone},
     * {@code outer - zone}, {@code outer} — carrying alphas {@code 0, innerAlpha, outerAlpha, 0}. Both feather
     * zones eat INTO the band rather than extending it, so the radius stays exactly the number the caller
     * means (for the blast ring that number is the kill zone) while neither rim ends on a hard edge. A rail
     * pair whose two alphas are both zero is skipped, which is why the common "solid outer edge fading to
     * nothing inwards" case costs barely more than a plain band.
     *
     * @param solidCenter only meaningful at {@code innerRadius = 0}, where the inner rim is the CENTRE of a
     *     disc and there is nothing outside it to feather into: {@code true} gives the innermost rail the
     *     inner colour's own alpha, turning the ring into a filled pool. Left {@code false} everywhere the
     *     inner rim is a real rim.
     * @param additive routes the mesh through an additive blend instead of the translucent one, so the band
     *     ADDS light to the scene rather than painting over it — the only way a mark stays legible on top of a
     *     fill at alpha 40 of 255. The additive pipeline is depth-tested, so a ground decal has to be lifted
     *     clear of the surface it lies on, and the {@code enabled = false} fallback ignores this and stays
     *     translucent.
     */
    public static void drawSoftArc(Render3DEvent event, Vec3 center, float outerRadius, float innerRadius,
                                   double fromRad, double toRad, int outerColor, int innerColor,
                                   SoftStyle style, boolean solidCenter, boolean additive) {
        double span = toRad - fromRad;
        if (outerRadius <= 0f || span <= 0.0 || (alphaOf(outerColor) == 0 && alphaOf(innerColor) == 0)) {
            return;
        }
        if (!style.enabled()) {
            Render3DUtility.ring(event, center, innerRadius, outerRadius, fromRad, toRad, hardSteps(span),
                    index -> innerColor, index -> outerColor, THROUGH_WALLS);
            return;
        }

        Vec3 cam = event.camera().pos;
        float fade = fadeFactor((float) cam.distanceTo(center), style);
        if (fade <= 0f) {
            return;
        }

        float inner = Math.clamp(innerRadius, 0f, outerRadius);
        float zone = (outerRadius - inner) * HALF_F * Math.clamp(style.feather(), 0f, 1f);
        float coreAlpha = solidCenter && inner <= 0f ? alphaOf(innerColor) / MAX_CHANNEL_F * fade : 0f;

        // The collector runs the callback later in the frame, so the rails are captured now rather than
        // written into a shared scratch the next draw of the same frame would overwrite.
        float[] radii = {inner, inner + zone, outerRadius - zone, outerRadius};
        int[] colors = {
                argb(innerColor & RGB_MASK, coreAlpha),
                argb(innerColor & RGB_MASK, alphaOf(innerColor) / MAX_CHANNEL_F * fade),
                argb(outerColor & RGB_MASK, alphaOf(outerColor) / MAX_CHANNEL_F * fade),
                argb(outerColor & RGB_MASK, 0f),
        };

        int steps = arcSteps(outerRadius, span);
        float cx = (float) (center.x - cam.x);
        float cy = (float) (center.y - cam.y);
        float cz = (float) (center.z - cam.z);
        event.collector().submitCustomGeometry(event.poseStack(), quadType(additive),
                (pose, consumer) -> addArcSlices(pose, consumer, cx, cy, cz, fromRad, span, steps, radii,
                        colors));
    }

    // ------------------------------------------------------------------ segment batches

    /** A batch of independent line segments as feathered ribbons, in ONE mesh. */
    public static void drawSoftSegments(Render3DEvent event, Vec3 origin, float[] points, int count, int color,
                                        SoftStyle style, float widthScale) {
        drawSoftSegments(event, origin, points, count, color, style, widthScale, false);
    }

    /**
     * A batch of independent line segments as feathered ribbons, in ONE mesh.
     *
     * <p>{@code points} holds {@code x0, y0, z0, x1, y1, z1} per segment RELATIVE to {@code origin} and is
     * supplied by the caller, who owns it and refills it in place every frame — that is what keeps the caller
     * allocation-free for a mark rebuilt continuously. Only the first {@code count} segments are read, and the
     * endpoints are copied here because the collector runs the geometry callback after the caller may already
     * have refilled the buffer for the next mark.
     *
     * <p>{@code widthScale} multiplies the style's outline width for marks that want a thinner or fatter line
     * than a box edge, and a segment shorter than the epsilon is skipped — which is what lets a caller fade a
     * tick in and out by growing it from zero length instead of by popping it into existence.
     */
    public static void drawSoftSegments(Render3DEvent event, Vec3 origin, float[] points, int count, int color,
                                        SoftStyle style, float widthScale, boolean additive) {
        if (count <= 0 || alphaOf(color) == 0) {
            return;
        }
        if (!style.enabled()) {
            for (int index = 0; index < count; index++) {
                int base = index * VALUES_PER_SEGMENT;
                Render3DUtility.line(event,
                        origin.add(points[base], points[base + 1], points[base + 2]),
                        origin.add(points[base + 3], points[base + 4], points[base + 5]),
                        color, HARD_LINE_WIDTH, THROUGH_WALLS);
            }
            return;
        }

        Vec3 cam = event.camera().pos;
        float fade = fadeFactor((float) cam.distanceTo(origin), style);
        if (fade <= 0f) {
            return;
        }

        float ox = (float) (origin.x - cam.x);
        float oy = (float) (origin.y - cam.y);
        float oz = (float) (origin.z - cam.z);
        float[] local = new float[count * VALUES_PER_SEGMENT];
        for (int index = 0; index < count; index++) {
            int base = index * VALUES_PER_SEGMENT;
            local[base] = ox + points[base];
            local[base + 1] = oy + points[base + 1];
            local[base + 2] = oz + points[base + 2];
            local[base + 3] = ox + points[base + 3];
            local[base + 4] = oy + points[base + 4];
            local[base + 5] = oz + points[base + 5];
        }

        float[] rails = rails(style.outlineWidth() * widthScale, style);
        int rgb = color & RGB_MASK;
        float alpha = alphaOf(color) / MAX_CHANNEL_F * fade;
        event.collector().submitCustomGeometry(event.poseStack(), quadType(additive), (pose, consumer) -> {
            for (int index = 0; index < count; index++) {
                int base = index * VALUES_PER_SEGMENT;
                addRibbon(pose, consumer, local[base], local[base + 1], local[base + 2],
                        local[base + 3], local[base + 4], local[base + 5], rails, rgb, alpha);
            }
        });
    }

    // ------------------------------------------------------------------ mesh building

    /**
     * Emits the six Fresnel-weighted, vertically graded faces of the box, whose corners are already relative to
     * the camera.
     *
     * <p>Both terms scale an already-scaled alpha, so the colour is split into its RGB and its alpha once and
     * recombined per vertex.
     */
    private static void addSoftFaces(PoseStack.Pose pose, VertexConsumer consumer,
                                     float x0, float y0, float z0, float x1, float y1, float z1,
                                     int color, float fade, float topRatio, SoftStyle style, int faceMask) {
        int rgb = color & RGB_MASK;
        float base = alphaOf(color) / MAX_CHANNEL_F * fade;
        float height = y1 - y0;
        float invHeight = height > EPSILON ? 1f / height : 0f;

        for (int face = 0; face < FACES; face++) {
            if ((faceMask & (1 << face)) == 0) {
                continue;
            }
            float faceAlpha = base * fresnelWeight(x0, y0, z0, x1, y1, z1, face, style);
            if (faceAlpha <= 0f) {
                continue;
            }
            for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
                int index = (face * CORNERS_PER_FACE + corner) * 3;
                float x = lerp(x0, x1, FACE_CORNERS[index]);
                float y = lerp(y0, y1, FACE_CORNERS[index + 1]);
                float z = lerp(z0, z1, FACE_CORNERS[index + 2]);
                float gradient = 1f + (topRatio - 1f) * ((y - y0) * invHeight);
                consumer.addVertex(pose, x, y, z).setColor(argb(rgb, faceAlpha * gradient));
            }
        }
    }

    /**
     * The grazing weight of one face: {@code mix(faceMin, 1, (1 - |dot(n, toCamera)|)^fresnelPower)}, with
     * {@code toCamera} taken from the FACE CENTRE so the term is smooth in the camera position and equal for
     * both faces of a pair.
     *
     * <p>The coordinates are camera-relative, so the camera sits at the origin and the vector towards it is
     * simply the negated face centre.
     */
    private static float fresnelWeight(float x0, float y0, float z0, float x1, float y1, float z1, int face,
                                       SoftStyle style) {
        int base = face * 3;
        float nx = FACE_NORMALS[base];
        float ny = FACE_NORMALS[base + 1];
        float nz = FACE_NORMALS[base + 2];
        float dx = -((x0 + x1) * HALF_F + nx * (x1 - x0) * HALF_F);
        float dy = -((y0 + y1) * HALF_F + ny * (y1 - y0) * HALF_F);
        float dz = -((z0 + z1) * HALF_F + nz * (z1 - z0) * HALF_F);

        float length = length(dx, dy, dz);
        if (length < EPSILON) {
            return 1f;
        }
        float grazing = Math.clamp(1f - Math.abs((nx * dx + ny * dy + nz * dz) / length), 0f, 1f);
        return style.faceMin() + (1f - style.faceMin()) * (float) Math.pow(grazing, style.fresnelPower());
    }

    /** The 12 edges of the box as camera-facing ribbons, inside the caller's single mesh. */
    private static void addSoftEdges(PoseStack.Pose pose, VertexConsumer consumer,
                                     float x0, float y0, float z0, float x1, float y1, float z1,
                                     int color, float fade, SoftStyle style) {
        float alpha = alphaOf(color) / MAX_CHANNEL_F * fade;
        if (alpha <= 0f) {
            return;
        }
        float[] rails = rails(style.outlineWidth(), style);
        int rgb = color & RGB_MASK;
        for (int edge = 0; edge < EDGES; edge++) {
            int from = EDGE_CORNERS[edge * 2] * 3;
            int to = EDGE_CORNERS[edge * 2 + 1] * 3;
            addRibbon(pose, consumer,
                    lerp(x0, x1, CORNER_UNITS[from]),
                    lerp(y0, y1, CORNER_UNITS[from + 1]),
                    lerp(z0, z1, CORNER_UNITS[from + 2]),
                    lerp(x0, x1, CORNER_UNITS[to]),
                    lerp(y0, y1, CORNER_UNITS[to + 1]),
                    lerp(z0, z1, CORNER_UNITS[to + 2]),
                    rails, rgb, alpha);
        }
    }

    /**
     * One antialiased line of controlled world-space width: the segment swept sideways along
     * {@code edgeDirection x toCamera}, so the ribbon always turns its face to the camera and never edges out.
     *
     * <p>The sweep is cut into three quads by the four rails: a solid core at full alpha and one shoulder on
     * each side falling to zero. The side vector is computed per ENDPOINT rather than per edge, which keeps the
     * width honest on an edge that runs away from the camera.
     */
    private static void addRibbon(PoseStack.Pose pose, VertexConsumer consumer,
                                  float fx, float fy, float fz, float tx, float ty, float tz,
                                  float[] rails, int rgb, float alpha) {
        float dx = tx - fx;
        float dy = ty - fy;
        float dz = tz - fz;
        if (dx * dx + dy * dy + dz * dz < EPSILON_SQUARED) {
            return;
        }
        Vector3f direction = SCRATCH_DIRECTION.set(dx, dy, dz);
        sideVector(fx, fy, fz, direction, SCRATCH_SIDE_FROM);
        sideVector(tx, ty, tz, direction, SCRATCH_SIDE_TO);
        Vector3f sideFrom = SCRATCH_SIDE_FROM;
        Vector3f sideTo = SCRATCH_SIDE_TO;

        int transparent = argb(rgb, 0f);
        int opaque = argb(rgb, alpha);
        for (int band = 0; band < RIBBON_BANDS; band++) {
            int nearArgb = band == 0 ? transparent : opaque;
            int farArgb = band == RIBBON_BANDS - 1 ? transparent : opaque;
            float near = rails[band];
            float far = rails[band + 1];
            consumer.addVertex(pose, fx + sideFrom.x * near, fy + sideFrom.y * near, fz + sideFrom.z * near)
                    .setColor(nearArgb);
            consumer.addVertex(pose, fx + sideFrom.x * far, fy + sideFrom.y * far, fz + sideFrom.z * far)
                    .setColor(farArgb);
            consumer.addVertex(pose, tx + sideTo.x * far, ty + sideTo.y * far, tz + sideTo.z * far)
                    .setColor(farArgb);
            consumer.addVertex(pose, tx + sideTo.x * near, ty + sideTo.y * near, tz + sideTo.z * near)
                    .setColor(nearArgb);
        }
    }

    /**
     * Unit vector perpendicular to both the direction and the view ray at the point, i.e. the direction a
     * ribbon has to widen in to face the camera. Falls back to a stable perpendicular when the segment points
     * straight at the camera and the cross product collapses — at that angle the ribbon is a sliver anyway.
     */
    private static void sideVector(float px, float py, float pz, Vector3f direction, Vector3f dest) {
        direction.cross(SCRATCH_TO_CAMERA.set(-px, -py, -pz), dest);
        if (dest.lengthSquared() >= EPSILON_SQUARED) {
            dest.normalize();
            return;
        }
        if (Math.abs(direction.y) <= Math.abs(direction.x) + Math.abs(direction.z)) {
            dest.set(-direction.z, 0f, direction.x);
        } else {
            dest.set(0f, direction.z, -direction.y);
        }
        dest.normalize();
    }

    /** Walks the arc once, reusing each boundary's sine and cosine for the next slice. */
    private static void addArcSlices(PoseStack.Pose pose, VertexConsumer consumer, float cx, float cy, float cz,
                                     double fromRad, double span, int steps, float[] radii, int[] colors) {
        float cosFrom = (float) Math.cos(fromRad);
        float sinFrom = (float) Math.sin(fromRad);
        for (int index = 1; index <= steps; index++) {
            double theta = fromRad + span * index / steps;
            float cosTo = (float) Math.cos(theta);
            float sinTo = (float) Math.sin(theta);
            addArcSlice(pose, consumer, cx, cy, cz, cosFrom, sinFrom, cosTo, sinTo, radii, colors);
            cosFrom = cosTo;
            sinFrom = sinTo;
        }
    }

    /** The three radial bands of one angular slice; a band whose two rails are both transparent is skipped. */
    private static void addArcSlice(PoseStack.Pose pose, VertexConsumer consumer, float cx, float cy, float cz,
                                    float cosFrom, float sinFrom, float cosTo, float sinTo,
                                    float[] radii, int[] colors) {
        for (int band = 0; band < ARC_BANDS; band++) {
            int nearArgb = colors[band];
            int farArgb = colors[band + 1];
            if ((nearArgb >>> ALPHA_SHIFT) == 0 && (farArgb >>> ALPHA_SHIFT) == 0) {
                continue;
            }
            float near = radii[band];
            float far = radii[band + 1];
            consumer.addVertex(pose, cx + cosFrom * near, cy, cz + sinFrom * near).setColor(nearArgb);
            consumer.addVertex(pose, cx + cosFrom * far, cy, cz + sinFrom * far).setColor(farArgb);
            consumer.addVertex(pose, cx + cosTo * far, cy, cz + sinTo * far).setColor(farArgb);
            consumer.addVertex(pose, cx + cosTo * near, cy, cz + sinTo * near).setColor(nearArgb);
        }
    }

    // ------------------------------------------------------------------ shared arithmetic

    /** The four sideways offsets of a ribbon of total width {@code width}. */
    private static float[] rails(float width, SoftStyle style) {
        float half = width * HALF_F;
        float core = half * (1f - Math.clamp(style.feather(), 0f, 1f));
        return new float[]{-half, -core, core, half};
    }

    /**
     * The single place where a drawing's alpha scale is resolved, and therefore where BOTH global alpha terms
     * live: the distance fade and the style's alpha gain.
     *
     * <p>The fade is {@code 1} closer than {@code fadeStart}, {@code 0} beyond {@code fadeEnd} and smoothstep
     * in between, so distant geometry stops shouting without a visible threshold anywhere. One distance per
     * drawing, taken from its anchor rather than per vertex, which keeps the term continuous while the camera
     * moves.
     *
     * <p>The gain multiplies whatever the fade produced, so every helper inherits it for free; the packing
     * clamps, so a gain past full alpha saturates instead of wrapping. A drawing beyond {@code fadeEnd} stays
     * gone no matter how large the gain is.
     */
    private static float fadeFactor(float distance, SoftStyle style) {
        float gain = style.alphaGain();
        if (distance <= style.fadeStart()) {
            return gain;
        }
        if (distance >= style.fadeEnd()) {
            return 0f;
        }
        float span = Math.max(style.fadeEnd() - style.fadeStart(), EPSILON);
        float t = Math.clamp((distance - style.fadeStart()) / span, 0f, 1f);
        return (1f - t * t * (3f - 2f * t)) * gain;
    }

    /**
     * Angular resolution of an arc, scaled with its radius so a small ring does not pay for a big one's
     * vertices and a big one never shows facets. The span is the arc's own angle, so a segment costs a
     * segment's worth.
     */
    private static int arcSteps(float radius, double span) {
        int full = Math.clamp((int) (radius * STEPS_PER_BLOCK), MIN_ARC_STEPS, MAX_ARC_STEPS);
        return Math.max(1, (int) Math.ceil(full * span / TWO_PI));
    }

    /** Fixed two-degree step of the hard fallbacks, matching the resolution the module used before. */
    private static int hardSteps(double span) {
        return Math.max(1, (int) Math.ceil(span / HARD_STEP_RAD));
    }

    /** The quad render type a soft mesh is emitted through: translucent, or additive when asked for light. */
    private static RenderType quadType(boolean additive) {
        return additive ? Types.ADDITIVE : Render3DUtility.filled(THROUGH_WALLS);
    }

    private static float lerp(float min, float max, float t) {
        return min + (max - min) * t;
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static int alphaOf(int argb) {
        return argb >>> ALPHA_SHIFT;
    }

    /** Recombines an RGB triple with a float alpha into the packed ARGB the vertex consumer expects. */
    private static int argb(int rgb, float alpha) {
        return rgb | (Math.clamp((int) (alpha * MAX_CHANNEL_F), 0, MAX_CHANNEL) << ALPHA_SHIFT);
    }

    /**
     * The additive quad pipeline, in a holder so nothing is built before the render system exists.
     *
     * <p>Aerial's own glow render type is a textured sprite shader that fades radially, so it cannot carry
     * arbitrary geometry; this is the plain filled pipeline with an additive blend and the same
     * depth-test-without-write the sprite path uses.
     */
    private static final class Types {
        private static final RenderPipeline ADDITIVE_QUADS =
                RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("aerial", "pipeline/crystal_additive"))
                        .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
                        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                        .withCull(false)
                        .build();

        static final RenderType ADDITIVE = RenderType.create("aerial:crystal_additive",
                RenderSetup.builder(ADDITIVE_QUADS).createRenderSetup());

        private Types() {
        }
    }

    /**
     * The vectors the ribbon builder needs, allocated once. Only ever touched from inside a submitted geometry
     * callback, and the collector runs those one at a time on the render thread, so a shared scratch is safe
     * and keeps a mark rebuilt every frame allocation-free.
     */
    private static final Vector3f SCRATCH_DIRECTION = new Vector3f();
    private static final Vector3f SCRATCH_TO_CAMERA = new Vector3f();
    private static final Vector3f SCRATCH_SIDE_FROM = new Vector3f();
    private static final Vector3f SCRATCH_SIDE_TO = new Vector3f();

    /**
     * Outward unit normals of the six faces, in the order {@code -X, +X, -Y, +Y, -Z, +Z} — the order
     * {@link #FACE_CORNERS}, {@link #SOFT_ALL_FACES} and {@link #SOFT_SIDE_FACES} are indexed and masked by.
     */
    private static final float[] FACE_NORMALS = {
            -1f, 0f, 0f,
            1f, 0f, 0f,
            0f, -1f, 0f,
            0f, 1f, 0f,
            0f, 0f, -1f,
            0f, 0f, 1f,
    };

    /**
     * The four corners of each face on the UNIT cube, as {@code 0}/{@code 1} per axis: a corner's position is
     * {@code min + unit * (max - min)}. Wound as a simple quad; winding is irrelevant because the pipeline
     * these go through has culling disabled, which is also what lets the far side of a box shine through the
     * near side.
     */
    private static final float[] FACE_CORNERS = {
            0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, 0f,
            1f, 0f, 0f, 1f, 0f, 1f, 1f, 1f, 1f, 1f, 1f, 0f,
            0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f,
            0f, 1f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f, 1f,
            0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f,
            0f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, 1f,
    };

    /** The eight corners of the unit cube, indexed as {@code x * 4 + y * 2 + z}. */
    private static final float[] CORNER_UNITS = {
            0f, 0f, 0f,
            0f, 0f, 1f,
            0f, 1f, 0f,
            0f, 1f, 1f,
            1f, 0f, 0f,
            1f, 0f, 1f,
            1f, 1f, 0f,
            1f, 1f, 1f,
    };

    /** The 12 edges as pairs of {@link #CORNER_UNITS} indices: four along X, four along Y, four along Z. */
    private static final int[] EDGE_CORNERS = {
            0, 4, 1, 5, 2, 6, 3, 7,
            0, 2, 1, 3, 4, 6, 5, 7,
            0, 1, 2, 3, 4, 5, 6, 7,
    };

    private static final int FACES = 6;
    private static final int CORNERS_PER_FACE = 4;
    private static final int EDGES = 12;
    private static final int VALUES_PER_SEGMENT = 6;

    /** How many quads a band stack spans: the four rails carry alphas {@code 0, full, full, 0}. */
    private static final int RIBBON_BANDS = 3;
    private static final int ARC_BANDS = 3;

    /** Band width of a free-standing ring as a fraction of its radius, and its floor in outline widths. */
    private static final float RIPPLE_BAND_RATIO = 0.1f;
    private static final float RIPPLE_MIN_WIDTHS = 3f;

    /** Arc tessellation: steps per block of radius over a full turn, clamped to a sane band. */
    private static final float STEPS_PER_BLOCK = 14f;
    private static final int MIN_ARC_STEPS = 24;
    private static final int MAX_ARC_STEPS = 180;

    private static final double HARD_STEP_RAD = 2.0 * Math.PI / 180.0;
    private static final double TWO_PI = 2.0 * Math.PI;

    private static final int ALPHA_SHIFT = 24;
    private static final int RGB_MASK = 0x00FFFFFF;
    private static final int MAX_CHANNEL = 255;
    private static final float MAX_CHANNEL_F = 255f;
    private static final float HALF_F = 0.5f;
    private static final float EPSILON = 1e-6f;
    private static final float EPSILON_SQUARED = 1e-12f;
}
