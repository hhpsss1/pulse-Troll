package cc.aerial.client.render;

import cc.aerial.client.event.impl.render.Render3DEvent;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * World-space drawing for Minecraft 26.2.
 *
 * <p>Everything goes through {@code SubmitNodeCollector.submitCustomGeometry}: geometry is handed to the
 * feature renderer as a callback and drawn with the level view/projection matrices, so it coexists
 * with Sodium and friends without touching GL state. Coordinates are world coordinates; the camera
 * offset is subtracted here in double precision before the cast to float.</p>
 *
 * <p>Every primitive has a {@code throughWalls} flag. {@code false} depth-tests against the world,
 * {@code true} uses a pipeline with {@code CompareOp.ALWAYS_PASS} so it draws over terrain.</p>
 */
public final class Render3DUtility {
    private Render3DUtility() {
    }

    private static final DepthStencilState NO_DEPTH = new DepthStencilState(CompareOp.ALWAYS_PASS, false);
    private static final DepthStencilState DEPTH_TEST_NO_WRITE = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false);

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("aerial", path);
    }

    /** Pipelines and render types live in a holder so nothing is built before the render system exists. */
    private static final class Types {
        private static final RenderPipeline LINES_NO_DEPTH = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                .withLocation(id("pipeline/lines_no_depth"))
                .withDepthStencilState(NO_DEPTH)
                .build();

        private static final RenderPipeline FILLED_NO_DEPTH = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation(id("pipeline/filled_no_depth"))
                .withDepthStencilState(NO_DEPTH)
                .withCull(false)
                .build();

        private static final Identifier GLOW_SHADER = id("core/glow_sprite");

        /** Round, procedurally shaded glow quads: the fragment shader fades radially from the centre. */
        private static final RenderPipeline GLOW = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                .withLocation(id("pipeline/glow"))
                .withVertexShader(GLOW_SHADER)
                .withFragmentShader(GLOW_SHADER)
                .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withDepthStencilState(DEPTH_TEST_NO_WRITE)
                .withCull(false)
                .build();

        private static final RenderPipeline GLOW_NO_DEPTH = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                .withLocation(id("pipeline/glow_no_depth"))
                .withVertexShader(GLOW_SHADER)
                .withFragmentShader(GLOW_SHADER)
                .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withDepthStencilState(NO_DEPTH)
                .withCull(false)
                .build();

        static final RenderType LINES = RenderTypes.lines();
        static final RenderType LINES_THROUGH = RenderType.create("aerial:lines_no_depth",
                RenderSetup.builder(LINES_NO_DEPTH)
                        .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                        .createRenderSetup());

        static final RenderType FILLED = RenderTypes.debugQuads();
        static final RenderType FILLED_THROUGH = RenderType.create("aerial:filled_no_depth",
                RenderSetup.builder(FILLED_NO_DEPTH).sortOnUpload().createRenderSetup());

        static final RenderType GLOW_TYPE = RenderType.create("aerial:glow",
                RenderSetup.builder(GLOW).createRenderSetup());
        static final RenderType GLOW_THROUGH = RenderType.create("aerial:glow_no_depth",
                RenderSetup.builder(GLOW_NO_DEPTH).createRenderSetup());
    }

    public static RenderType lines(boolean throughWalls) {
        return throughWalls ? Types.LINES_THROUGH : Types.LINES;
    }

    public static RenderType filled(boolean throughWalls) {
        return throughWalls ? Types.FILLED_THROUGH : Types.FILLED;
    }

    /** Additive (glow) quads. Overlapping quads brighten instead of covering each other. */
    public static RenderType glow(boolean throughWalls) {
        return throughWalls ? Types.GLOW_THROUGH : Types.GLOW_TYPE;
    }

    // ---------------------------------------------------------------- helpers

    public static Vec3 interpolatedPosition(Entity entity, float partialTick) {
        return entity.getPosition(partialTick);
    }

    public static AABB interpolatedBox(Entity entity, float partialTick) {
        Vec3 pos = entity.getPosition(partialTick);
        AABB current = entity.getBoundingBox();
        double halfWidth = (current.maxX - current.minX) / 2.0;
        double height = current.maxY - current.minY;
        return new AABB(pos.x - halfWidth, pos.y, pos.z - halfWidth,
                pos.x + halfWidth, pos.y + height, pos.z + halfWidth);
    }

    public static int withAlpha(int argb, float alpha) {
        int a = Math.round(Math.clamp(alpha, 0.0f, 1.0f) * 255.0f);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    public static int multiplyAlpha(int argb, float factor) {
        int a = Math.round(((argb >>> 24) & 0xFF) * Math.clamp(factor, 0.0f, 1.0f));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private static float rx(CameraRenderState camera, double x) {
        return (float) (x - camera.pos.x);
    }

    private static float ry(CameraRenderState camera, double y) {
        return (float) (y - camera.pos.y);
    }

    private static float rz(CameraRenderState camera, double z) {
        return (float) (z - camera.pos.z);
    }

    // ---------------------------------------------------------------- lines

    private static void emitLine(PoseStack.Pose pose, VertexConsumer consumer,
                                 float x1, float y1, float z1, float x2, float y2, float z2,
                                 int color1, int color2, float width) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-5f) {
            return;
        }
        float nx = dx / length, ny = dy / length, nz = dz / length;
        consumer.addVertex(pose, x1, y1, z1).setColor(color1).setNormal(pose, nx, ny, nz).setLineWidth(width);
        consumer.addVertex(pose, x2, y2, z2).setColor(color2).setNormal(pose, nx, ny, nz).setLineWidth(width);
    }

    public static void line(Render3DEvent event, Vec3 from, Vec3 to, int argb, float width, boolean throughWalls) {
        line(event, from, to, argb, argb, width, throughWalls);
    }

    public static void line(Render3DEvent event, Vec3 from, Vec3 to, int fromColor, int toColor, float width,
                            boolean throughWalls) {
        CameraRenderState camera = event.camera();
        float x1 = rx(camera, from.x), y1 = ry(camera, from.y), z1 = rz(camera, from.z);
        float x2 = rx(camera, to.x), y2 = ry(camera, to.y), z2 = rz(camera, to.z);
        event.collector().submitCustomGeometry(event.poseStack(), lines(throughWalls),
                (pose, consumer) -> emitLine(pose, consumer, x1, y1, z1, x2, y2, z2, fromColor, toColor, width));
    }

    /**
     * Connected segments through {@code points}. {@code colorAt} receives the point index and returns the
     * ARGB colour for that point, which lets a trail fade along its length.
     */
    public static void polyline(Render3DEvent event, List<Vec3> points, IntUnaryOperator colorAt, float width,
                                boolean throughWalls) {
        int count = points.size();
        if (count < 2) {
            return;
        }
        CameraRenderState camera = event.camera();
        float[] xs = new float[count], ys = new float[count], zs = new float[count];
        int[] colors = new int[count];
        for (int i = 0; i < count; i++) {
            Vec3 p = points.get(i);
            xs[i] = rx(camera, p.x);
            ys[i] = ry(camera, p.y);
            zs[i] = rz(camera, p.z);
            colors[i] = colorAt.applyAsInt(i);
        }
        event.collector().submitCustomGeometry(event.poseStack(), lines(throughWalls), (pose, consumer) -> {
            for (int i = 1; i < count; i++) {
                emitLine(pose, consumer, xs[i - 1], ys[i - 1], zs[i - 1], xs[i], ys[i], zs[i],
                        colors[i - 1], colors[i], width);
            }
        });
    }

    /** Horizontal circle of {@code segments} segments; {@code colorAt} gets the vertex index (0..segments). */
    public static void circle(Render3DEvent event, Vec3 center, double radius, int segments, IntUnaryOperator colorAt,
                              float width, boolean throughWalls) {
        CameraRenderState camera = event.camera();
        int n = Math.max(3, segments);
        float[] xs = new float[n + 1], zs = new float[n + 1];
        int[] colors = new int[n + 1];
        float y = ry(camera, center.y);
        for (int i = 0; i <= n; i++) {
            double angle = (Math.PI * 2.0 * i) / n;
            xs[i] = rx(camera, center.x + Math.cos(angle) * radius);
            zs[i] = rz(camera, center.z + Math.sin(angle) * radius);
            colors[i] = colorAt.applyAsInt(i);
        }
        event.collector().submitCustomGeometry(event.poseStack(), lines(throughWalls), (pose, consumer) -> {
            for (int i = 1; i <= n; i++) {
                emitLine(pose, consumer, xs[i - 1], y, zs[i - 1], xs[i], y, zs[i], colors[i - 1], colors[i], width);
            }
        });
    }

    public static void boxOutline(Render3DEvent event, AABB box, int argb, float width, boolean throughWalls) {
        CameraRenderState camera = event.camera();
        float x0 = rx(camera, box.minX), y0 = ry(camera, box.minY), z0 = rz(camera, box.minZ);
        float x1 = rx(camera, box.maxX), y1 = ry(camera, box.maxY), z1 = rz(camera, box.maxZ);
        event.collector().submitCustomGeometry(event.poseStack(), lines(throughWalls), (pose, consumer) -> {
            // bottom
            emitLine(pose, consumer, x0, y0, z0, x1, y0, z0, argb, argb, width);
            emitLine(pose, consumer, x1, y0, z0, x1, y0, z1, argb, argb, width);
            emitLine(pose, consumer, x1, y0, z1, x0, y0, z1, argb, argb, width);
            emitLine(pose, consumer, x0, y0, z1, x0, y0, z0, argb, argb, width);
            // top
            emitLine(pose, consumer, x0, y1, z0, x1, y1, z0, argb, argb, width);
            emitLine(pose, consumer, x1, y1, z0, x1, y1, z1, argb, argb, width);
            emitLine(pose, consumer, x1, y1, z1, x0, y1, z1, argb, argb, width);
            emitLine(pose, consumer, x0, y1, z1, x0, y1, z0, argb, argb, width);
            // verticals
            emitLine(pose, consumer, x0, y0, z0, x0, y1, z0, argb, argb, width);
            emitLine(pose, consumer, x1, y0, z0, x1, y1, z0, argb, argb, width);
            emitLine(pose, consumer, x1, y0, z1, x1, y1, z1, argb, argb, width);
            emitLine(pose, consumer, x0, y0, z1, x0, y1, z1, argb, argb, width);
        });
    }

    // ---------------------------------------------------------------- filled

    private static void emitQuad(PoseStack.Pose pose, VertexConsumer consumer, int argb,
                                 float ax, float ay, float az, float bx, float by, float bz,
                                 float cx, float cy, float cz, float dx, float dy, float dz) {
        consumer.addVertex(pose, ax, ay, az).setColor(argb);
        consumer.addVertex(pose, bx, by, bz).setColor(argb);
        consumer.addVertex(pose, cx, cy, cz).setColor(argb);
        consumer.addVertex(pose, dx, dy, dz).setColor(argb);
    }

    public static void boxFilled(Render3DEvent event, AABB box, int argb, boolean throughWalls) {
        CameraRenderState camera = event.camera();
        float x0 = rx(camera, box.minX), y0 = ry(camera, box.minY), z0 = rz(camera, box.minZ);
        float x1 = rx(camera, box.maxX), y1 = ry(camera, box.maxY), z1 = rz(camera, box.maxZ);
        event.collector().submitCustomGeometry(event.poseStack(), filled(throughWalls), (pose, consumer) -> {
            emitQuad(pose, consumer, argb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1); // bottom
            emitQuad(pose, consumer, argb, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0); // top
            emitQuad(pose, consumer, argb, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0); // north
            emitQuad(pose, consumer, argb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1); // south
            emitQuad(pose, consumer, argb, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0); // west
            emitQuad(pose, consumer, argb, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1); // east
        });
    }

    // ---------------------------------------------------------------- billboards

    private static Vector3f[] cameraAxes(CameraRenderState camera) {
        Quaternionf orientation = camera.orientation;
        Vector3f right = new Vector3f(1.0f, 0.0f, 0.0f);
        Vector3f up = new Vector3f(0.0f, 1.0f, 0.0f);
        if (orientation != null) {
            right.rotate(orientation);
            up.rotate(orientation);
        }
        return new Vector3f[]{right, up};
    }

    /**
     * Round glow of diameter {@code size} at {@code pos}: a camera-facing quad whose fragment shader
     * fades radially, blended additively. Overlapping glows brighten each other.
     */
    public static void glowSprite(Render3DEvent event, Vec3 pos, float size, int argb, boolean throughWalls) {
        CameraRenderState camera = event.camera();
        Vector3f[] axes = cameraAxes(camera);
        Vector3f right = axes[0], up = axes[1];
        float cx = rx(camera, pos.x), cy = ry(camera, pos.y), cz = rz(camera, pos.z);
        event.collector().submitCustomGeometry(event.poseStack(), glow(throughWalls),
                (pose, consumer) -> emitGlowQuad(pose, consumer, cx, cy, cz, right, up, size, argb));
    }

    /**
     * Comet tail: a chain of round glows along {@code points} (oldest first). The head is the last
     * point at full {@code size}; each earlier point shrinks and dims towards the tail, and adjacent
     * glows overlap so the additive blend reads as one continuous streak. {@code colorAt} returns the
     * ARGB colour for a point index and may return a fully transparent colour to skip that point.
     */
    public static void glowTrail(Render3DEvent event, List<Vec3> points, float size, IntUnaryOperator colorAt,
                                 boolean throughWalls) {
        int count = points.size();
        if (count == 0) {
            return;
        }
        CameraRenderState camera = event.camera();
        Vector3f[] axes = cameraAxes(camera);
        Vector3f right = axes[0], up = axes[1];

        float[] xs = new float[count], ys = new float[count], zs = new float[count];
        int[] colors = new int[count];
        for (int i = 0; i < count; i++) {
            Vec3 p = points.get(i);
            xs[i] = rx(camera, p.x);
            ys[i] = ry(camera, p.y);
            zs[i] = rz(camera, p.z);
            colors[i] = colorAt.applyAsInt(i);
        }

        event.collector().submitCustomGeometry(event.poseStack(), glow(throughWalls), (pose, consumer) -> {
            for (int i = 0; i < count; i++) {
                if ((colors[i] >>> 24) == 0) {
                    continue;
                }
                float t = count == 1 ? 1.0f : (float) i / (count - 1);   // 0 = tail end, 1 = head
                float scale = 0.25f + 0.75f * t * t;
                emitGlowQuad(pose, consumer, xs[i], ys[i], zs[i], right, up, size * scale, colors[i]);
            }
        });
    }

    private static void emitGlowQuad(PoseStack.Pose pose, VertexConsumer consumer, float cx, float cy, float cz,
                                     Vector3f right, Vector3f up, float size, int argb) {
        float h = size * 0.5f;
        float rxh = right.x * h, ryh = right.y * h, rzh = right.z * h;
        float uxh = up.x * h, uyh = up.y * h, uzh = up.z * h;
        consumer.addVertex(pose, cx - rxh - uxh, cy - ryh - uyh, cz - rzh - uzh).setUv(0.0f, 0.0f).setColor(argb);
        consumer.addVertex(pose, cx + rxh - uxh, cy + ryh - uyh, cz + rzh - uzh).setUv(1.0f, 0.0f).setColor(argb);
        consumer.addVertex(pose, cx + rxh + uxh, cy + ryh + uyh, cz + rzh + uzh).setUv(1.0f, 1.0f).setColor(argb);
        consumer.addVertex(pose, cx - rxh + uxh, cy - ryh + uyh, cz - rzh + uzh).setUv(0.0f, 1.0f).setColor(argb);
    }

    private static void emitBillboard(PoseStack.Pose pose, VertexConsumer consumer, float cx, float cy, float cz,
                                      Vector3f right, Vector3f up, float size, int argb) {
        float h = size * 0.5f;
        float rxh = right.x * h, ryh = right.y * h, rzh = right.z * h;
        float uxh = up.x * h, uyh = up.y * h, uzh = up.z * h;
        consumer.addVertex(pose, cx - rxh - uxh, cy - ryh - uyh, cz - rzh - uzh).setColor(argb);
        consumer.addVertex(pose, cx + rxh - uxh, cy + ryh - uyh, cz + rzh - uzh).setColor(argb);
        consumer.addVertex(pose, cx + rxh + uxh, cy + ryh + uyh, cz + rzh + uzh).setColor(argb);
        consumer.addVertex(pose, cx - rxh + uxh, cy - ryh + uyh, cz - rzh + uzh).setColor(argb);
    }

    // ---------------------------------------------------------------- occlusion

    /**
     * True when {@code point} is hidden behind (or inside) {@code box} as seen from the camera.
     * Lets through-wall drawings still disappear behind the entity they decorate.
     */
    public static boolean occludedBy(AABB box, CameraRenderState camera, Vec3 point) {
        if (box.contains(point)) {
            return true;
        }
        Vec3 from = camera.pos;
        if (box.contains(from)) {
            return false;
        }
        return box.clip(from, point).isPresent();
    }

    /** Camera-facing square with normal alpha blending (no glow), useful for flat markers. */
    public static void billboard(Render3DEvent event, Vec3 pos, float size, int argb, boolean throughWalls) {
        CameraRenderState camera = event.camera();
        Vector3f[] axes = cameraAxes(camera);
        Vector3f right = axes[0], up = axes[1];
        float cx = rx(camera, pos.x), cy = ry(camera, pos.y), cz = rz(camera, pos.z);
        event.collector().submitCustomGeometry(event.poseStack(), filled(throughWalls),
                (pose, consumer) -> emitBillboard(pose, consumer, cx, cy, cz, right, up, size, argb));
    }

    // ---------------------------------------------------------------- arcs and rings

    /**
     * Outlined arc in the horizontal plane through {@code center}, swept from {@code startAngle} to
     * {@code endAngle} (radians, counter-clockwise seen from above, 0 along +X).
     *
     * <p>{@code colorAt} receives the vertex index 0..{@code segments} so a sweep can fade along its
     * length. A full circle is {@code startAngle = 0}, {@code endAngle = 2*PI} — {@link #circle} is
     * the shorthand for that.</p>
     */
    public static void arc(Render3DEvent event, Vec3 center, double radius,
                           double startAngle, double endAngle, int segments,
                           IntUnaryOperator colorAt, float width, boolean throughWalls) {
        int n = Math.max(1, segments);
        CameraRenderState camera = event.camera();
        float[] xs = new float[n + 1], zs = new float[n + 1];
        int[] colors = new int[n + 1];
        float y = ry(camera, center.y);
        double step = (endAngle - startAngle) / n;
        for (int i = 0; i <= n; i++) {
            double angle = startAngle + step * i;
            xs[i] = rx(camera, center.x + Math.cos(angle) * radius);
            zs[i] = rz(camera, center.z + Math.sin(angle) * radius);
            colors[i] = colorAt.applyAsInt(i);
        }
        event.collector().submitCustomGeometry(event.poseStack(), lines(throughWalls), (pose, consumer) -> {
            for (int i = 1; i <= n; i++) {
                emitLine(pose, consumer, xs[i - 1], y, zs[i - 1], xs[i], y, zs[i], colors[i - 1], colors[i], width);
            }
        });
    }

    /**
     * Filled annulus sector in the horizontal plane through {@code center} — the workhorse for soft
     * rings, where the alpha gradient between the two radii is what makes the edge look feathered.
     *
     * <p>Both colour functions receive the vertex index 0..{@code segments}; the inner and outer rim
     * are coloured independently, so a ring can fade outwards, around, or both.</p>
     */
    public static void ring(Render3DEvent event, Vec3 center, double innerRadius, double outerRadius,
                            double startAngle, double endAngle, int segments,
                            IntUnaryOperator innerColorAt, IntUnaryOperator outerColorAt,
                            boolean throughWalls) {
        int n = Math.max(1, segments);
        CameraRenderState camera = event.camera();
        float[] ix = new float[n + 1], iz = new float[n + 1];
        float[] ox = new float[n + 1], oz = new float[n + 1];
        int[] ic = new int[n + 1], oc = new int[n + 1];
        float y = ry(camera, center.y);
        double step = (endAngle - startAngle) / n;
        for (int i = 0; i <= n; i++) {
            double angle = startAngle + step * i;
            double cos = Math.cos(angle), sin = Math.sin(angle);
            ix[i] = rx(camera, center.x + cos * innerRadius);
            iz[i] = rz(camera, center.z + sin * innerRadius);
            ox[i] = rx(camera, center.x + cos * outerRadius);
            oz[i] = rz(camera, center.z + sin * outerRadius);
            ic[i] = innerColorAt.applyAsInt(i);
            oc[i] = outerColorAt.applyAsInt(i);
        }
        event.collector().submitCustomGeometry(event.poseStack(), filled(throughWalls), (pose, consumer) -> {
            for (int i = 1; i <= n; i++) {
                consumer.addVertex(pose, ix[i - 1], y, iz[i - 1]).setColor(ic[i - 1]);
                consumer.addVertex(pose, ox[i - 1], y, oz[i - 1]).setColor(oc[i - 1]);
                consumer.addVertex(pose, ox[i], y, oz[i]).setColor(oc[i]);
                consumer.addVertex(pose, ix[i], y, iz[i]).setColor(ic[i]);
            }
        });
    }

    /**
     * Filled fan from {@code center} out to {@code rim}, for shapes an annulus cannot express —
     * wedges, domes seen from below, irregular blast outlines.
     *
     * <p>The pipeline draws quads, so each triangle is emitted with its last vertex doubled.
     * {@code rimColorAt} receives the rim index.</p>
     */
    public static void triangleFan(Render3DEvent event, Vec3 center, List<Vec3> rim,
                                   int centerColor, IntUnaryOperator rimColorAt, boolean throughWalls) {
        int count = rim.size();
        if (count < 2) {
            return;
        }
        CameraRenderState camera = event.camera();
        float cx = rx(camera, center.x), cy = ry(camera, center.y), cz = rz(camera, center.z);
        float[] xs = new float[count], ys = new float[count], zs = new float[count];
        int[] colors = new int[count];
        for (int i = 0; i < count; i++) {
            Vec3 p = rim.get(i);
            xs[i] = rx(camera, p.x);
            ys[i] = ry(camera, p.y);
            zs[i] = rz(camera, p.z);
            colors[i] = rimColorAt.applyAsInt(i);
        }
        event.collector().submitCustomGeometry(event.poseStack(), filled(throughWalls), (pose, consumer) -> {
            for (int i = 1; i < count; i++) {
                consumer.addVertex(pose, cx, cy, cz).setColor(centerColor);
                consumer.addVertex(pose, xs[i - 1], ys[i - 1], zs[i - 1]).setColor(colors[i - 1]);
                consumer.addVertex(pose, xs[i], ys[i], zs[i]).setColor(colors[i]);
                consumer.addVertex(pose, xs[i], ys[i], zs[i]).setColor(colors[i]);
            }
        });
    }
}
