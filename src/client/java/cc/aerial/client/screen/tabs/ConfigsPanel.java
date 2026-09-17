package cc.aerial.client.screen.tabs;

import cc.aerial.client.config.ConfigUtility;
import cc.aerial.client.render.RenderUtil;
import cc.aerial.client.screen.AerialClickGui;
import cc.aerial.client.screen.animation.Scroller;
import cc.aerial.client.screen.widget.AerialTextArea;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.KeyEvent;

import java.util.List;

/**
 * Config manager tab: list saved {@code .aerial} configs, save the current state under a name,
 * load or delete an existing one. Backed by {@link ConfigUtility}.
 */
public final class ConfigsPanel implements TabContent {
    private static final float PAD = 12.0f;
    private static final float FIELD_H = 18.0f;
    private static final float ROW_H = 22.0f;
    private static final float SAVE_W = 58.0f;
    private static final float ACTION_W = 46.0f;
    private static final float GAP = 6.0f;

    private final AerialTextArea nameField = new AerialTextArea(AerialClickGui.mediumFont(), 8.0f, "config name");
    private final Scroller scroller = new Scroller();

    private List<String> configs = List.of();
    private String active;
    private String status = "";
    private long statusUntil;

    public ConfigsPanel() {
        nameField.setSingleLine(true);
    }

    @Override
    public void onShow() {
        refresh();
    }

    private void refresh() {
        configs = ConfigUtility.listConfigs();
    }

    private void flash(String message) {
        status = message;
        statusUntil = System.currentTimeMillis() + 2500L;
    }

    private float listTop(float y) {
        return y + PAD + 22.0f + FIELD_H + 12.0f;
    }

    @Override
    public void render(GuiGraphicsExtractor extractor, float x, float y, float w, float h,
                       float alpha, int mouseX, int mouseY, ScreenRectangle scissor) {
        TabWidgets.card(extractor, x, y, w, h, 10.0f, alpha, scissor);

        float innerX = x + PAD;
        TabWidgets.textBold(extractor, "Configs", innerX, y + PAD, 12.0f,
                TabWidgets.withAlpha(TabWidgets.TEXT, alpha), scissor);

        String info = configs.size() + (configs.size() == 1 ? " saved config" : " saved configs");
        if (System.currentTimeMillis() < statusUntil) {
            info = status;
        }
        float infoW = TabWidgets.textWidth(info, 7.0f);
        TabWidgets.text(extractor, info, x + w - PAD - infoW, y + PAD + 3.0f, 7.0f,
                TabWidgets.withAlpha(TabWidgets.TEXT_DIM, alpha), scissor);

        // Name field + Save button
        float rowY = y + PAD + 20.0f;
        float fieldW = w - PAD * 2.0f - SAVE_W - GAP;
        TabWidgets.field(extractor, innerX, rowY, fieldW, FIELD_H, nameField.isFocused(), alpha, scissor);
        nameField.draw(extractor, innerX + 5.0f, rowY + (FIELD_H - 8.0f) * 0.5f, fieldW - 10.0f, 8.0f);

        float saveX = innerX + fieldW + GAP;
        boolean saveHover = TabWidgets.hover(mouseX, mouseY, saveX, rowY, SAVE_W, FIELD_H);
        TabWidgets.button(extractor, saveX, rowY, SAVE_W, FIELD_H, "Save", TabIcons.SAVE, 8.0f,
                false, saveHover, alpha, scissor);

        // Config list
        float listTop = listTop(y);
        float listH = y + h - PAD - listTop;
        if (listH <= 0) {
            return;
        }
        ScreenRectangle listClip = intersect(scissor, new ScreenRectangle(
                Math.round(x), Math.round(listTop), Math.round(w), Math.round(listH)));

        float contentHeight = configs.size() * ROW_H;
        float maxScroll = Math.max(0.0f, contentHeight - listH);
        scroller.onScroll(maxScroll);
        float scroll = scroller.getAnimation().getValue();

        if (configs.isEmpty()) {
            TabWidgets.text(extractor, "No saved configs yet — type a name and press Save.",
                    innerX, listTop + 4.0f, 7.5f, TabWidgets.withAlpha(TabWidgets.TEXT_FAINT, alpha), listClip);
            return;
        }

        float rowStart = listTop - scroll;
        for (int i = 0; i < configs.size(); i++) {
            String name = configs.get(i);
            float ry = rowStart + i * ROW_H;
            if (ry + ROW_H < listTop || ry > listTop + listH) {
                continue;
            }
            boolean isActive = name.equals(active);
            boolean rowHover = TabWidgets.hover(mouseX, mouseY, innerX, ry, w - PAD * 2.0f, ROW_H - 3.0f);
            int rowBg = isActive
                    ? TabWidgets.withAlpha(TabWidgets.accent(), 0.22f * alpha)
                    : TabWidgets.withAlpha(rowHover ? 0x20FFFFFF : 0x12FFFFFF, alpha);
            RenderUtil.roundedRect(extractor, innerX, ry, w - PAD * 2.0f, ROW_H - 3.0f, 5.0f, rowBg, listClip);

            TabWidgets.text(extractor, name, innerX + 8.0f, ry + (ROW_H - 3.0f - 8.0f) * 0.5f, 8.0f,
                    TabWidgets.withAlpha(isActive ? TabWidgets.TEXT : TabWidgets.TEXT_DIM, alpha), listClip);

            float delX = innerX + (w - PAD * 2.0f) - ACTION_W - 6.0f;
            float loadX = delX - ACTION_W - GAP;
            float bY = ry + (ROW_H - 3.0f - 15.0f) * 0.5f;
            boolean loadHover = TabWidgets.hover(mouseX, mouseY, loadX, bY, ACTION_W, 15.0f);
            boolean delHover = TabWidgets.hover(mouseX, mouseY, delX, bY, ACTION_W, 15.0f);
            TabWidgets.button(extractor, loadX, bY, ACTION_W, 15.0f, "Load", (char) 0, 7.5f,
                    false, loadHover, alpha, listClip);
            RenderUtil.roundedRect(extractor, delX, bY, ACTION_W, 15.0f, 5.0f,
                    TabWidgets.withAlpha(delHover ? 0x33E0555F : 0x1FE0555F, alpha), listClip);
            float delLabelW = TabWidgets.textWidth("Delete", 7.5f);
            TabWidgets.text(extractor, "Delete", delX + (ACTION_W - delLabelW) * 0.5f, bY + (15.0f - 7.5f) * 0.5f,
                    7.5f, TabWidgets.withAlpha(delHover ? 0xFFFF8A90 : TabWidgets.DANGER, alpha), listClip);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button, float x, float y, float w, float h) {
        float innerX = x + PAD;
        float rowY = y + PAD + 20.0f;
        float fieldW = w - PAD * 2.0f - SAVE_W - GAP;

        if (nameField.mouseClicked(mouseX, mouseY, innerX + 5.0f, rowY, fieldW - 10.0f, FIELD_H)) {
            return true;
        }
        nameField.setFocused(false);

        float saveX = innerX + fieldW + GAP;
        if (TabWidgets.hover(mouseX, mouseY, saveX, rowY, SAVE_W, FIELD_H)) {
            saveCurrent();
            return true;
        }

        // list rows
        float listTop = listTop(y);
        float listH = y + h - PAD - listTop;
        if (listH <= 0 || mouseY < listTop || mouseY > listTop + listH) {
            return TabWidgets.hover(mouseX, mouseY, x, y, w, h);
        }
        float scroll = scroller.getAnimation().getValue();
        float rowStart = listTop - scroll;
        for (int i = 0; i < configs.size(); i++) {
            String name = configs.get(i);
            float ry = rowStart + i * ROW_H;
            float delX = innerX + (w - PAD * 2.0f) - ACTION_W - 6.0f;
            float loadX = delX - ACTION_W - GAP;
            float bY = ry + (ROW_H - 3.0f - 15.0f) * 0.5f;
            if (TabWidgets.hover(mouseX, mouseY, loadX, bY, ACTION_W, 15.0f)) {
                if (ConfigUtility.load(name)) {
                    active = name;
                    flash("Loaded '" + name + "'");
                }
                return true;
            }
            if (TabWidgets.hover(mouseX, mouseY, delX, bY, ACTION_W, 15.0f)) {
                if (ConfigUtility.delete(name)) {
                    if (name.equals(active)) {
                        active = null;
                    }
                    refresh();
                    flash("Deleted '" + name + "'");
                }
                return true;
            }
        }
        return TabWidgets.hover(mouseX, mouseY, x, y, w, h);
    }

    private void saveCurrent() {
        String name = nameField.getValue().trim();
        if (name.isEmpty()) {
            name = "config";
        }
        ConfigUtility.save(name);
        active = name;
        refresh();
        flash("Saved '" + name + "'");
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double vertical, float x, float y, float w, float h) {
        float listTop = listTop(y);
        float listH = y + h - PAD - listTop;
        if (mouseY < listTop || mouseY > listTop + listH) {
            return false;
        }
        float maxScroll = Math.max(0.0f, configs.size() * ROW_H - listH);
        scroller.addScroll(vertical, maxScroll);
        return true;
    }

    @Override
    public boolean charTyped(char codepoint) {
        return nameField.charTyped(codepoint);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return nameField.keyPressed(event);
    }

    private static ScreenRectangle intersect(ScreenRectangle a, ScreenRectangle b) {
        if (a == null) {
            return b;
        }
        ScreenRectangle r = a.intersection(b);
        return r == null ? b : r;
    }
}
