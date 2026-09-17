package cc.aerial.client.screen.tabs;

import cc.aerial.client.render.RenderUtil;
import cc.aerial.client.screen.AerialClickGui;
import cc.aerial.client.script.ScriptHUDAPI;
import cc.aerial.client.script.ScriptHudManager;
import cc.aerial.client.script.ScriptRender2D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;

import java.util.List;

/**
 * HUD editor tab: overlays draggable handles on every script HUD element so the user can position
 * them anywhere on screen. Positions persist via {@link ScriptHudManager}. The elements themselves
 * are drawn continuously by the manager; this panel only adds the editor chrome and dragging.
 */
public final class HudEditorPanel implements TabContent {
    private static final float PAD = 12.0f;

    private ScriptHUDAPI.ScriptHUDElement grabbed;
    private float grabOffX;
    private float grabOffY;

    @Override
    public void render(GuiGraphicsExtractor extractor, float x, float y, float w, float h,
                       float alpha, int mouseX, int mouseY, ScreenRectangle scissor) {
        List<ScriptHUDAPI.ScriptHUDElement> elements = ScriptHudManager.getInstance().getElements();

        if (grabbed != null) {
            grabbed.moveTo(mouseX - grabOffX, mouseY - grabOffY);
        }

        // 1) Info card / chrome first, so element previews and handles draw ON TOP of it and stay
        //    visible even when an element sits over the panel area.
        TabWidgets.card(extractor, x, y, w, h, 10.0f, alpha, scissor);
        float innerX = x + PAD;
        TabWidgets.textBold(extractor, "HUD Editor", innerX, y + PAD, 12.0f,
                TabWidgets.withAlpha(TabWidgets.TEXT, alpha), scissor);
        TabWidgets.text(extractor, "Drag elements anywhere — positions save automatically.",
                innerX, y + PAD + 16.0f, 7.5f, TabWidgets.withAlpha(TabWidgets.TEXT_DIM, alpha), scissor);

        float ly = y + PAD + 34.0f;
        if (elements.isEmpty()) {
            TabWidgets.text(extractor, "No script HUD elements yet.", innerX, ly, 8.0f,
                    TabWidgets.withAlpha(TabWidgets.TEXT_FAINT, alpha), scissor);
            TabWidgets.text(extractor, "Create one with hud.registerHUDElement(name) in a script.",
                    innerX, ly + 12.0f, 7.5f, TabWidgets.withAlpha(TabWidgets.TEXT_FAINT, alpha), scissor);
            return;
        }
        for (ScriptHUDAPI.ScriptHUDElement e : elements) {
            String line = e.getName() + "   ·   " + Math.round(e.getX()) + ", " + Math.round(e.getY());
            TabWidgets.text(extractor, line, innerX, ly, 8.0f,
                    TabWidgets.withAlpha(TabWidgets.TEXT, alpha), scissor);
            ly += 13.0f;
        }

        // 2) Live preview of the real element content (the in-game HUD event does not fire while a
        //    screen is open), drawn on top of the card.
        ScriptRender2D preview = new ScriptRender2D(extractor, 1.0f);
        for (ScriptHUDAPI.ScriptHUDElement e : elements) {
            try {
                e.render(preview);
            } catch (Throwable ignored) {
            }
        }

        // 3) Full-screen draggable handles over each element.
        for (ScriptHUDAPI.ScriptHUDElement e : elements) {
            float ex = e.getX();
            float ey = e.getY();
            float ew = Math.max(e.getWidth(), 8.0f);
            float eh = Math.max(e.getHeight(), 8.0f);
            boolean hovered = TabWidgets.hover(mouseX, mouseY, ex, ey, ew, eh) || e == grabbed;
            int fill = TabWidgets.withAlpha(e == grabbed ? TabWidgets.accent() : 0xFFFFFFFF,
                    (hovered ? 0.16f : 0.07f) * alpha);
            RenderUtil.roundedRect(extractor, ex, ey, ew, eh, 3.0f, fill, null);
            RenderUtil.roundedOutline(extractor, ex, ey, ew, eh, 3.0f, 1.0f,
                    TabWidgets.withAlpha(hovered ? TabWidgets.accent() : 0x88FFFFFF, alpha), null);
            TabWidgets.text(extractor, e.getName(), ex + 1.0f, ey - 8.0f, 6.5f,
                    TabWidgets.withAlpha(TabWidgets.TEXT_DIM, alpha), null);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button, float x, float y, float w, float h) {
        if (button == 0) {
            List<ScriptHUDAPI.ScriptHUDElement> elements = ScriptHudManager.getInstance().getElements();
            // Topmost first so overlapping handles grab the one drawn last.
            for (int i = elements.size() - 1; i >= 0; i--) {
                ScriptHUDAPI.ScriptHUDElement e = elements.get(i);
                float ew = Math.max(e.getWidth(), 8.0f);
                float eh = Math.max(e.getHeight(), 8.0f);
                if (TabWidgets.hover(mouseX, mouseY, e.getX(), e.getY(), ew, eh)) {
                    grabbed = e;
                    grabOffX = (float) mouseX - e.getX();
                    grabOffY = (float) mouseY - e.getY();
                    return true;
                }
            }
        }
        return TabWidgets.hover(mouseX, mouseY, x, y, w, h);
    }

    @Override
    public void mouseReleased(int button) {
        if (grabbed != null) {
            ScriptHudManager.getInstance().pin(grabbed);
            grabbed = null;
        }
    }
}
