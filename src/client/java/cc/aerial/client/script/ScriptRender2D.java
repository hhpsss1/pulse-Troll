package cc.aerial.client.script;

import cc.aerial.client.render.RenderUtil;
import cc.aerial.client.render.TextRenderUtil;
import cc.aerial.client.screen.AerialClickGui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 2D drawing surface handed to {@code events.onRender2D} callbacks. Colors are ARGB ints
 * (use {@code utils.color(r,g,b,a)}). Draws through the same primitives the ClickGui uses.
 */
public final class ScriptRender2D {
    private final GuiGraphicsExtractor extractor;
    private final float partialTick;

    public ScriptRender2D(GuiGraphicsExtractor extractor, float partialTick) {
        this.extractor = extractor;
        this.partialTick = partialTick;
    }

    public int width() {
        return extractor.guiWidth();
    }

    public int height() {
        return extractor.guiHeight();
    }

    public float getPartialTick() {
        return partialTick;
    }

    public void rect(float x, float y, float w, float h, int color) {
        RenderUtil.flatRect(extractor, x, y, w, h, color);
    }

    public void roundedRect(float x, float y, float w, float h, float radius, int color) {
        RenderUtil.roundedRect(extractor, x, y, w, h, radius, color);
    }

    public void outline(float x, float y, float w, float h, float radius, float thickness, int color) {
        RenderUtil.roundedOutline(extractor, x, y, w, h, radius, thickness, color, null);
    }

    public void gradient(float x, float y, float w, float h, int colorA, int colorB, boolean vertical) {
        RenderUtil.roundedRectGradient(extractor, x, y, w, h, 0.01f, colorA, colorB, vertical, null);
    }

    public void line(float x1, float y1, float x2, float y2, float thickness, int color) {
        // Thin rotated rect between two points.
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001f) {
            return;
        }
        // Axis-aligned fast path (most HUD lines are horizontal/vertical).
        if (Math.abs(dy) < 0.5f) {
            RenderUtil.flatRect(extractor, Math.min(x1, x2), y1 - thickness * 0.5f, len, thickness, color);
        } else if (Math.abs(dx) < 0.5f) {
            RenderUtil.flatRect(extractor, x1 - thickness * 0.5f, Math.min(y1, y2), thickness, len, color);
        } else {
            // Approximate diagonal with a series of dots.
            int steps = (int) len;
            for (int i = 0; i <= steps; i++) {
                float t = i / (float) steps;
                RenderUtil.roundedRect(extractor, x1 + dx * t - thickness * 0.5f,
                        y1 + dy * t - thickness * 0.5f, thickness, thickness, thickness * 0.5f, color);
            }
        }
    }

    public float text(String s, float x, float y, float size, int color) {
        return TextRenderUtil.drawString(extractor, AerialClickGui.mediumFont(), s, x, y, size, color);
    }

    public float textBold(String s, float x, float y, float size, int color) {
        return TextRenderUtil.drawString(extractor, AerialClickGui.boldFont(), s, x, y, size, color);
    }

    public float textShadow(String s, float x, float y, float size, int color) {
        return TextRenderUtil.drawStringWithShadow(extractor, AerialClickGui.mediumFont(), s, x, y, size, color);
    }

    public float textWidth(String s, float size) {
        return AerialClickGui.mediumFont().stringWidth(s, size);
    }

    public float textHeight(float size) {
        return AerialClickGui.mediumFont().height(size);
    }

    public GuiGraphicsExtractor getExtractor() {
        return extractor;
    }
}
