package cc.aerial.client.screen.tabs;

import cc.aerial.client.render.AerialBlur;
import cc.aerial.client.render.BlurConsumer;
import cc.aerial.client.render.RenderUtil;
import cc.aerial.client.render.TextRenderUtil;
import cc.aerial.client.render.font.AerialFont;
import cc.aerial.client.screen.AerialClickGui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;

/**
 * Small shared drawing helpers so the tab bar and every tab panel share one visual language,
 * matching the existing Aerial ClickGui (theme accent, dark glass cards, Opal fonts).
 */
public final class TabWidgets {
    private TabWidgets() {
    }

    public static final int TEXT = 0xFFF2F3F5;
    public static final int TEXT_DIM = 0xFF9CA0AA;
    public static final int TEXT_FAINT = 0xFF6E727C;
    public static final int CARD_TINT = 0xA8121319;
    public static final int FIELD_BG = 0xFF16171E;
    public static final int FIELD_OUTLINE = 0x33FFFFFF;
    public static final int BTN_BG = 0x2BFFFFFF;
    public static final int BTN_HOVER = 0x40FFFFFF;
    public static final int DANGER = 0xFFE0555F;

    // Optional palette override so a host layout (e.g. the Deadlock GUI) can retint the shared tab
    // panels to match itself. 0 means "use the Aerial theme".
    private static int accentOverride;
    private static int accent2Override;
    private static int cardTintOverride;

    public static void setThemeOverride(int accent, int accent2, int cardTint) {
        accentOverride = accent;
        accent2Override = accent2;
        cardTintOverride = cardTint;
    }

    public static void clearThemeOverride() {
        accentOverride = 0;
        accent2Override = 0;
        cardTintOverride = 0;
    }

    public static int accent() {
        return accentOverride != 0 ? accentOverride : AerialClickGui.themeColor();
    }

    public static int accent2() {
        return accent2Override != 0 ? accent2Override : AerialClickGui.themeColorSecondary();
    }

    public static int cardTint() {
        return cardTintOverride != 0 ? cardTintOverride : CARD_TINT;
    }

    public static boolean hover(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    public static int withAlpha(int argb, float alpha) {
        return AerialClickGui.withAlpha(argb, alpha);
    }

    /** Dark glass card background matching the host layout's cards. */
    public static void card(GuiGraphicsExtractor extractor, float x, float y, float w, float h,
                            float radius, float alpha, ScreenRectangle scissor) {
        AerialBlur.drawGlass(extractor, BlurConsumer.CLICK_GUI, x, y, w, h, radius,
                withAlpha(cardTint(), alpha), alpha, scissor);
    }

    /** A rounded pill button with a centered label (and optional leading icon glyph). */
    public static void button(GuiGraphicsExtractor extractor, float x, float y, float w, float h,
                              String label, char icon, float textSize,
                              boolean active, boolean hovered, float alpha, ScreenRectangle scissor) {
        float radius = Math.min(h * 0.5f, 6.0f);
        if (active) {
            RenderUtil.roundedRectGradient(extractor, x, y, w, h, radius,
                    withAlpha(accent(), alpha),
                    withAlpha(accent2(), alpha), false, scissor);
        } else {
            RenderUtil.roundedRect(extractor, x, y, w, h, radius,
                    withAlpha(hovered ? BTN_HOVER : BTN_BG, alpha), scissor);
        }

        int color = withAlpha(active ? 0xFFFFFFFF : (hovered ? TEXT : TEXT_DIM), alpha);
        AerialFont font = AerialClickGui.mediumFont();
        float labelW = font.stringWidth(label, textSize);
        float iconW = 0.0f;
        float iconGap = 3.0f;
        if (icon != 0) {
            iconW = AerialClickGui.outlinedIconFont().stringWidth(String.valueOf(icon), textSize + 1.0f);
        }
        float totalW = labelW + (icon != 0 ? iconW + iconGap : 0.0f);
        float startX = x + (w - totalW) * 0.5f;
        float ty = y + (h - textSize) * 0.5f;
        if (icon != 0) {
            TextRenderUtil.drawString(extractor, AerialClickGui.outlinedIconFont(), String.valueOf(icon),
                    startX, y + (h - (textSize + 1.0f)) * 0.5f, textSize + 1.0f, color, scissor);
            startX += iconW + iconGap;
        }
        TextRenderUtil.drawString(extractor, font, label, startX, ty, textSize, color, scissor);
    }

    /** Rounded input-field background with a subtle outline; brighter outline when focused. */
    public static void field(GuiGraphicsExtractor extractor, float x, float y, float w, float h,
                             boolean focused, float alpha, ScreenRectangle scissor) {
        RenderUtil.roundedRect(extractor, x, y, w, h, 4.0f, withAlpha(FIELD_BG, alpha), scissor);
        int outline = focused ? withAlpha(accent(), alpha) : withAlpha(FIELD_OUTLINE, alpha);
        RenderUtil.roundedOutline(extractor, x, y, w, h, 4.0f, 1.0f, outline, scissor);
    }

    public static float textWidth(String text, float size) {
        return AerialClickGui.mediumFont().stringWidth(text, size);
    }

    public static void text(GuiGraphicsExtractor extractor, String text, float x, float y, float size,
                            int color, ScreenRectangle scissor) {
        TextRenderUtil.drawString(extractor, AerialClickGui.mediumFont(), text, x, y, size, color, scissor);
    }

    public static void textBold(GuiGraphicsExtractor extractor, String text, float x, float y, float size,
                                int color, ScreenRectangle scissor) {
        TextRenderUtil.drawString(extractor, AerialClickGui.boldFont(), text, x, y, size, color, scissor);
    }

    public static void icon(GuiGraphicsExtractor extractor, char glyph, float x, float y, float size,
                            int color, ScreenRectangle scissor) {
        TextRenderUtil.drawString(extractor, AerialClickGui.outlinedIconFont(), String.valueOf(glyph),
                x, y, size, color, scissor);
    }
}
