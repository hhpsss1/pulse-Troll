package cc.aerial.client.screen.tabs;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.KeyEvent;

/**
 * Shared, static entry point that both ClickGui layouts use to render the tab bar and the active
 * tab's content and route input to it. Keeping the bar and panels here means the selected tab and
 * all panel state (script drafts, config selection) are shared across the Rail and Classic layouts.
 */
public final class TabHost {
    private static final TabBar BAR = new TabBar();
    private static final ConfigsPanel CONFIGS = new ConfigsPanel();
    private static final ScriptsPanel SCRIPTS = new ScriptsPanel();
    private static final HudEditorPanel HUD = new HudEditorPanel();

    private TabHost() {
    }

    public static boolean isFeatures() {
        return TabBar.isFeatures();
    }

    public static float barHeight() {
        return BAR.height();
    }

    public static float barWidth() {
        return BAR.measureWidth();
    }

    public static TabContent content() {
        return switch (TabBar.selected()) {
            case CONFIGS -> CONFIGS;
            case SCRIPTS -> SCRIPTS;
            case HUD -> HUD;
            default -> null;
        };
    }

    public static void renderBar(GuiGraphicsExtractor extractor, float centerX, float topY, float alpha,
                                 int mouseX, int mouseY, ScreenRectangle scissor) {
        BAR.render(extractor, centerX, topY, alpha, mouseX, mouseY, scissor);
    }

    public static void renderContent(GuiGraphicsExtractor extractor, float x, float y, float w, float h,
                                     float alpha, int mouseX, int mouseY, ScreenRectangle scissor) {
        TabContent c = content();
        if (c != null) {
            c.render(extractor, x, y, w, h, alpha, mouseX, mouseY, scissor);
        }
    }

    public static boolean barClicked(double mouseX, double mouseY, int button) {
        ClickGuiTab before = TabBar.selected();
        boolean handled = BAR.mouseClicked(mouseX, mouseY, button);
        if (handled && TabBar.selected() != before) {
            TabContent c = content();
            if (c != null) {
                c.onShow();
            }
        }
        return handled;
    }

    public static boolean contentClicked(double mouseX, double mouseY, int button,
                                         float x, float y, float w, float h) {
        TabContent c = content();
        return c != null && c.mouseClicked(mouseX, mouseY, button, x, y, w, h);
    }

    public static void contentReleased(int button) {
        TabContent c = content();
        if (c != null) {
            c.mouseReleased(button);
        }
    }

    public static boolean contentScrolled(double mouseX, double mouseY, double vertical,
                                          float x, float y, float w, float h) {
        TabContent c = content();
        return c != null && c.mouseScrolled(mouseX, mouseY, vertical, x, y, w, h);
    }

    public static boolean contentCharTyped(char codepoint) {
        TabContent c = content();
        return c != null && c.charTyped(codepoint);
    }

    public static boolean contentKeyPressed(KeyEvent event) {
        TabContent c = content();
        return c != null && c.keyPressed(event);
    }
}
