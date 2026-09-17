package cc.aerial.client.script;

/**
 * Drawing surface handed to a HUD element's render callback. All coordinates are relative to the
 * element's own origin (draw at 0,0 and it appears at the element position), so elements can be
 * freely repositioned/dragged without touching the script.
 */
public final class ScriptHudRender {
    private final ScriptRender2D base;
    private final float ox;
    private final float oy;
    private final float w;
    private final float h;

    public ScriptHudRender(ScriptRender2D base, float ox, float oy, float w, float h) {
        this.base = base;
        this.ox = ox;
        this.oy = oy;
        this.w = w;
        this.h = h;
    }

    public void rect(float x, float y, float width, float height, int color) {
        base.rect(ox + x, oy + y, width, height, color);
    }

    public void roundedRect(float x, float y, float width, float height, float radius, int color) {
        base.roundedRect(ox + x, oy + y, width, height, radius, color);
    }

    public void outline(float x, float y, float width, float height, float radius, float thickness, int color) {
        base.outline(ox + x, oy + y, width, height, radius, thickness, color);
    }

    public void gradient(float x, float y, float width, float height, int colorA, int colorB, boolean vertical) {
        base.gradient(ox + x, oy + y, width, height, colorA, colorB, vertical);
    }

    public void line(float x1, float y1, float x2, float y2, float thickness, int color) {
        base.line(ox + x1, oy + y1, ox + x2, oy + y2, thickness, color);
    }

    public float text(String s, float x, float y, float size, int color) {
        return base.text(s, ox + x, oy + y, size, color);
    }

    public float textBold(String s, float x, float y, float size, int color) {
        return base.textBold(s, ox + x, oy + y, size, color);
    }

    public float textShadow(String s, float x, float y, float size, int color) {
        return base.textShadow(s, ox + x, oy + y, size, color);
    }

    public float textWidth(String s, float size) {
        return base.textWidth(s, size);
    }

    public float textHeight(float size) {
        return base.textHeight(size);
    }

    public float getX() {
        return ox;
    }

    public float getY() {
        return oy;
    }

    public float getWidth() {
        return w;
    }

    public float getHeight() {
        return h;
    }

    public int screenWidth() {
        return base.width();
    }

    public int screenHeight() {
        return base.height();
    }
}
