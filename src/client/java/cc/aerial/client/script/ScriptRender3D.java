package cc.aerial.client.script;

import cc.aerial.client.event.impl.render.Render3DEvent;
import cc.aerial.client.render.ESPUtility;
import cc.aerial.client.render.Render3DUtility;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;

/**
 * World-space drawing surface handed to {@code events.onRender3D} callbacks. Coordinates are
 * absolute world coordinates; colors are ARGB ints.
 */
public final class ScriptRender3D {
    private final Render3DEvent event;

    public ScriptRender3D(Render3DEvent event) {
        this.event = event;
    }

    public void box(double x1, double y1, double z1, double x2, double y2, double z2,
                    int color, boolean throughWalls) {
        Render3DUtility.boxOutline(event, new AABB(x1, y1, z1, x2, y2, z2), color, 1.5f, throughWalls);
    }

    public void boxFilled(double x1, double y1, double z1, double x2, double y2, double z2,
                          int color, boolean throughWalls) {
        Render3DUtility.boxFilled(event, new AABB(x1, y1, z1, x2, y2, z2), color, throughWalls);
    }

    public void line(double x1, double y1, double z1, double x2, double y2, double z2,
                     int color, float width, boolean throughWalls) {
        Render3DUtility.line(event, new Vec3(x1, y1, z1), new Vec3(x2, y2, z2), color, width, throughWalls);
    }

    public void circle(double x, double y, double z, double radius, int color, boolean throughWalls) {
        Render3DUtility.circle(event, new Vec3(x, y, z), radius, 48, index -> color, 1.5f, throughWalls);
    }

    public void entityBox(ScriptEntity entity, int color, boolean filled, boolean throughWalls) {
        AABB box = entity.getHandle().getBoundingBox();
        if (filled) {
            Render3DUtility.boxFilled(event, box, color, throughWalls);
        } else {
            Render3DUtility.boxOutline(event, box, color, 1.5f, throughWalls);
        }
    }

    public void tracer(double x, double y, double z, int color, float width) {
        // Line from the bottom-centre of the screen (camera) to a world point.
        Vec3 cam = event.camera().pos;
        Render3DUtility.line(event, cam, new Vec3(x, y, z), color, width, true);
    }

    /** Projects a world point to screen space. Returns {@code [screenX, screenY, onScreen(1/0)]}. */
    public double[] worldToScreen(double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        Vector4f projected = ESPUtility.project(new Vec3(x, y, z), event.camera(), w, h);
        boolean onScreen = projected.z() > 0.0f && projected.z() < 1.0f;
        return new double[]{projected.x(), projected.y(), onScreen ? 1.0 : 0.0};
    }

    public Render3DEvent getHandle() {
        return event;
    }
}
