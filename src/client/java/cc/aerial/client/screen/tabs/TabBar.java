package cc.aerial.client.screen.tabs;

import cc.aerial.client.render.RenderUtil;
import cc.aerial.client.screen.AerialClickGui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;

/**
 * A centered horizontal dock of tab buttons, echoleak's {@code ClickGuiDock} re-styled for Aerial.
 * The selected tab is kept statically so it is shared across both ClickGui layouts and survives
 * switching between them.
 */
public final class TabBar {
    private static final float DOCK_HEIGHT = 26.0f;
    private static final float DOCK_PADDING = 4.0f;
    private static final float DOCK_RADIUS = 8.0f;
    private static final float BTN_SPACING = 3.0f;
    private static final float BTN_PADDING = 8.0f;
    private static final float TEXT_SIZE = 8.0f;

    private static final int DOCK_BG = 0xE81A1B22;

    private static ClickGuiTab selected = ClickGuiTab.FEATURES;

    private final float[] btnX = new float[ClickGuiTab.VALUES.length];
    private final float[] btnW = new float[ClickGuiTab.VALUES.length];
    private float btnY;
    private float btnH;
    private float dockX;
    private float dockWidth;

    public static ClickGuiTab selected() {
        return selected;
    }

    public static void select(ClickGuiTab tab) {
        selected = tab;
    }

    public static boolean isFeatures() {
        return selected == ClickGuiTab.FEATURES;
    }

    public float height() {
        return DOCK_HEIGHT;
    }

    public float measureWidth() {
        float total = DOCK_PADDING * 2.0f;
        ClickGuiTab[] tabs = ClickGuiTab.VALUES;
        for (int i = 0; i < tabs.length; i++) {
            total += buttonWidth(tabs[i]);
            if (i < tabs.length - 1) {
                total += BTN_SPACING;
            }
        }
        return total;
    }

    private float buttonWidth(ClickGuiTab tab) {
        float labelW = TabWidgets.textWidth(tab.label, TEXT_SIZE);
        float iconW = AerialClickGui.outlinedIconFont().stringWidth(String.valueOf(tab.icon), TEXT_SIZE + 1.0f);
        return BTN_PADDING * 2.0f + iconW + 3.0f + labelW;
    }

    public void render(GuiGraphicsExtractor extractor, float centerX, float topY, float alpha,
                       int mouseX, int mouseY, ScreenRectangle scissor) {
        dockWidth = measureWidth();
        dockX = centerX - dockWidth * 0.5f;
        btnY = topY + DOCK_PADDING;
        btnH = DOCK_HEIGHT - DOCK_PADDING * 2.0f;

        RenderUtil.roundedRect(extractor, dockX, topY, dockWidth, DOCK_HEIGHT, DOCK_RADIUS,
                TabWidgets.withAlpha(DOCK_BG, alpha), scissor);

        float bx = dockX + DOCK_PADDING;
        ClickGuiTab[] tabs = ClickGuiTab.VALUES;
        for (int i = 0; i < tabs.length; i++) {
            ClickGuiTab tab = tabs[i];
            float w = buttonWidth(tab);
            btnX[i] = bx;
            btnW[i] = w;

            boolean active = tab == selected;
            boolean hovered = TabWidgets.hover(mouseX, mouseY, bx, btnY, w, btnH);
            TabWidgets.button(extractor, bx, btnY, w, btnH, tab.label, tab.icon, TEXT_SIZE,
                    active, hovered, alpha, scissor);

            bx += w + BTN_SPACING;
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        ClickGuiTab[] tabs = ClickGuiTab.VALUES;
        for (int i = 0; i < tabs.length; i++) {
            if (TabWidgets.hover(mouseX, mouseY, btnX[i], btnY, btnW[i], btnH)) {
                selected = tabs[i];
                return true;
            }
        }
        return false;
    }
}
