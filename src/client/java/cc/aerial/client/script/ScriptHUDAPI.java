package cc.aerial.client.script;

import java.util.List;
import java.util.function.Consumer;

/**
 * The {@code hud} global: create fully custom, draggable HUD elements. The render callback receives
 * a {@link ScriptHudRender} whose coordinates are relative to the element, so it can be repositioned
 * in the HUD editor without changing the script.
 *
 * Example (JS):
 *   var e = hud.registerHUDElement("Coords");
 *   e.setSize(90, 22);
 *   e.setBackground(utils.color(0, 0, 0, 140));
 *   e.setRenderCallback(function(r) {
 *     var p = game.getPlayer();
 *     if (p) r.text("XYZ " + Math.floor(p.getX()) + " " + Math.floor(p.getY()) + " " + Math.floor(p.getZ()),
 *                   6, 7, 8, utils.color(255,255,255,255));
 *   });
 */
public final class ScriptHUDAPI {
    public ScriptHUDElement registerHUDElement(String name) {
        ScriptHUDElement element = new ScriptHUDElement(name);
        ScriptHudManager.getInstance().add(element);
        ScriptContext.record(() -> ScriptHudManager.getInstance().remove(element));
        return element;
    }

    public void unregisterHUDElement(ScriptHUDElement element) {
        ScriptHudManager.getInstance().remove(element);
    }

    public List<ScriptHUDElement> getHUDElements() {
        return ScriptHudManager.getInstance().getElements();
    }

    /** A single, fully customizable HUD element. Rendered by {@link ScriptHudManager}. */
    public static final class ScriptHUDElement {
        private final String name;
        private Consumer<ScriptHudRender> renderCallback;
        private float x = 10.0f;
        private float y = 10.0f;
        private float width = 90.0f;
        private float height = 18.0f;
        private float radius = 4.0f;
        private int background = 0; // 0 = no background
        private boolean visible = true;
        private boolean pinned; // user dragged it in the editor; script setPosition no longer moves it

        public ScriptHUDElement(String name) {
            this.name = name;
        }

        public void setRenderCallback(Consumer<ScriptHudRender> callback) {
            this.renderCallback = callback;
        }

        public void setPosition(float x, float y) {
            if (pinned) {
                return;
            }
            this.x = x;
            this.y = y;
        }

        public void setSize(float width, float height) {
            this.width = width;
            this.height = height;
        }

        public void setBackground(int argb) {
            this.background = argb;
        }

        public void setRadius(float radius) {
            this.radius = radius;
        }

        public void setVisible(boolean visible) {
            this.visible = visible;
        }

        public float getX() {
            return x;
        }

        public float getY() {
            return y;
        }

        public float getWidth() {
            return width;
        }

        public float getHeight() {
            return height;
        }

        public boolean isVisible() {
            return visible;
        }

        public String getName() {
            return name;
        }

        /** Used by the HUD editor: move freely and mark as user-pinned. */
        public void moveTo(float x, float y) {
            this.x = x;
            this.y = y;
            this.pinned = true;
        }

        /** Used by the manager when restoring a saved position. */
        public void applyPinned(float x, float y) {
            this.x = x;
            this.y = y;
            this.pinned = true;
        }

        public void render(ScriptRender2D base) {
            if (!visible) {
                return;
            }
            if (background != 0) {
                base.roundedRect(x, y, width, height, radius, background);
            }
            if (renderCallback != null) {
                renderCallback.accept(new ScriptHudRender(base, x, y, width, height));
            }
        }
    }
}
