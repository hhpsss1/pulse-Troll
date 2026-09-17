package cc.aerial.client.screen.tabs;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.KeyEvent;

/**
 * A self-contained panel rendered inside a bounded content rectangle so it can be hosted by
 * either ClickGui layout (Rail or Classic). All coordinates are absolute screen pixels; the host
 * passes the same {@code (x, y, w, h)} rectangle to both {@link #render} and the input handlers.
 */
public interface TabContent {
    void render(GuiGraphicsExtractor extractor, float x, float y, float w, float h,
                float alpha, int mouseX, int mouseY, ScreenRectangle scissor);

    /** Called when the panel becomes visible so it can refresh cached state. */
    default void onShow() {
    }

    default boolean mouseClicked(double mouseX, double mouseY, int button,
                                 float x, float y, float w, float h) {
        return false;
    }

    default void mouseReleased(int button) {
    }

    default boolean mouseScrolled(double mouseX, double mouseY, double vertical,
                                  float x, float y, float w, float h) {
        return false;
    }

    default boolean charTyped(char codepoint) {
        return false;
    }

    default boolean keyPressed(KeyEvent event) {
        return false;
    }
}
