package cc.aerial.client.screen;

import cc.aerial.client.AerialClient;
import cc.aerial.client.features.Module;
import cc.aerial.client.features.ModuleCategory;
import cc.aerial.client.features.impl.visual.AnimationsModule;
import cc.aerial.client.property.Property;
import cc.aerial.client.render.AerialBlur;
import cc.aerial.client.render.BlurConsumer;
import cc.aerial.client.render.RenderUtil;
import cc.aerial.client.render.TextRenderUtil;
import cc.aerial.client.render.font.AerialFont;
import cc.aerial.client.screen.animation.Animation;
import cc.aerial.client.screen.animation.Easing;
import cc.aerial.client.screen.animation.Scroller;
import cc.aerial.client.screen.tabs.ClickGuiTab;
import cc.aerial.client.screen.tabs.TabBar;
import cc.aerial.client.screen.tabs.TabHost;
import cc.aerial.client.screen.tabs.TabIcons;
import cc.aerial.client.screen.widget.AerialTextArea;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Third ClickGui layout: a port of the deadlock_p100 menu style — a dark, purple-accent window with
 * a left icon navbar (main tabs), a subtabs row (module categories) and a content area, plus the
 * shared Scripts / HUD / Configs tabs. Reuses Aerial's render/settings primitives.
 */
public final class DeadlockClickGui extends Screen {
    // deadlock palette (ARGB)
    private static final int ACCENT = 0xFF8E84FF;
    private static final int TEXT = 0xFFD7D3FF;
    private static final int TEXT_DISABLED = 0xFF6B6B7D;
    private static final int WINDOW_BG = 0xFF0C0D12;
    private static final int CHILD_BG = 0xFF13141A;
    private static final int FRAME_BG = 0xFF191A22;
    private static final int FRAME_HOVER = 0xFF1F202A;
    private static final int NAV_SELECTED = 0xFF171821;
    private static final int BORDER = 0x24D4CAFF;
    private static final int DEADLOCK_CARD_TINT = 0xC013141A;

    private static final float NAV_WIDTH = 176.0f;
    private static final float PAD = 14.0f;
    private static final float NAV_ITEM_H = 34.0f;
    private static final float ROW_H = 24.0f;
    private static final float INDENT = 12.0f;
    private static final float HEADER_DRAG = 48.0f;
    private static final float RADIUS = 5.0f;

    private record NavItem(ClickGuiTab tab, String label, char icon) {
    }

    private static final NavItem[] NAV = {
            new NavItem(ClickGuiTab.FEATURES, "Modules", TabIcons.FEATURES),
            new NavItem(ClickGuiTab.SCRIPTS, "Scripts", TabIcons.SCRIPTS),
            new NavItem(ClickGuiTab.HUD, "HUD", TabIcons.HUD),
            new NavItem(ClickGuiTab.CONFIGS, "Configs", TabIcons.CONFIGS),
    };

    private static final Map<Module, RowContent> PANELS = new IdentityHashMap<>();
    private static final AnimationsPanel ANIMATIONS_PANEL = new AnimationsPanel(true);

    private static float sPanelX = Float.NaN;
    private static float sPanelY;
    private static ModuleCategory sCategory = ModuleCategory.COMBAT;

    private final Animation openAnim = new Animation(Easing.EASE_OUT_EXPO, 320L);
    private final Map<ClickGuiTab, Animation> navHover = new IdentityHashMap<>();
    private final Map<Module, ModuleUi> moduleUi = new IdentityHashMap<>();
    private final Animation indicatorX = new Animation(Easing.EASE_OUT_EXPO, 260L);
    private final Animation indicatorW = new Animation(Easing.EASE_OUT_EXPO, 260L);
    private final Scroller listScroll = new Scroller();
    private final AerialTextArea search = new AerialTextArea(AerialClickGui.mediumFont(), 8.0f, "search");

    private boolean closing;
    private boolean dragging;
    private float dragOffX;
    private float dragOffY;

    // rebuilt each render for hit-testing
    private final List<float[]> navRects = new ArrayList<>();   // [x,y,w,h,tabOrdinal]
    private final List<float[]> subRects = new ArrayList<>();   // [x,y,w,h,categoryOrdinal]
    private final List<RowHit> rowHits = new ArrayList<>();
    private float[] searchRect = new float[0];
    private float[] contentRect = new float[0];

    private record RowHit(Module module, float rowY, boolean hasProps, RowContent content,
                          float cx, float cy, float cw) {
    }

    private static final class ModuleUi {
        final Animation hover = new Animation(Easing.DECELERATE, 150L);
        final Animation toggle = new Animation(Easing.DECELERATE, 150L);
        final Animation expand = new Animation(Easing.DECELERATE, 160L);
        boolean expanded;
    }

    public DeadlockClickGui() {
        super(Component.empty());
        search.setSingleLine(true);
    }

    public void requestClose() {
        closing = true;
    }

    @Override
    public void tick() {
        if (closing && openAnim.getValue() <= 0.02f) {
            Minecraft.getInstance().setScreenAndShow(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
    }

    private float winW() {
        return Math.min(750.0f, width - 40.0f);
    }

    private float winH() {
        return Math.min(500.0f, height - 40.0f);
    }

    private void ensureCentred() {
        if (!Float.isNaN(sPanelX) || width <= 0 || height <= 0) {
            return;
        }
        sPanelX = (width - winW()) * 0.5f;
        sPanelY = (height - winH()) * 0.5f;
    }

    private ModuleUi ui(Module module) {
        return moduleUi.computeIfAbsent(module, k -> new ModuleUi());
    }

    private RowContent contentFor(Module module) {
        if (module instanceof AnimationsModule) {
            return ANIMATIONS_PANEL;
        }
        return PANELS.computeIfAbsent(module, key -> {
            Property<?>[] properties = key.getProperties();
            return properties.length == 0 ? null : new SimplePropertyPanel(properties, true);
        });
    }

    private List<Module> visibleModules() {
        List<Module> all = AerialClient.getModuleRepository().getModulesInCategory(sCategory);
        String query = search.getValue().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            List<Module> sorted = new ArrayList<>(all);
            sorted.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            return sorted;
        }
        List<Module> filtered = new ArrayList<>();
        for (Module m : all) {
            if (m.getName().toLowerCase(Locale.ROOT).contains(query)) {
                filtered.add(m);
            }
        }
        filtered.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return filtered;
    }

    private static int fade(int argb, float alpha) {
        return AerialClickGui.withAlpha(argb, alpha);
    }

    private static int lerpColor(int a, int b, float t) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static boolean in(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    // ---------------------------------------------------------------- render

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        ensureCentred();
        if (Float.isNaN(sPanelX)) {
            return;
        }
        navRects.clear();
        subRects.clear();
        rowHits.clear();

        openAnim.run(closing ? 0.0f : 1.0f);
        float progress = openAnim.getValue();
        if (progress <= 0.005f) {
            return;
        }

        float w = winW();
        float h = winH();
        float x = sPanelX;
        float y = sPanelY + (1.0f - progress) * 16.0f;

        // Window background + border
        AerialBlur.drawGlass(extractor, BlurConsumer.CLICK_GUI, x, y, w, h, RADIUS, fade(WINDOW_BG, progress),
                progress, null);
        RenderUtil.roundedOutline(extractor, x, y, w, h, RADIUS, 1.0f, fade(BORDER, progress), null);

        drawNavbar(extractor, x, y, h, progress, mouseX, mouseY);

        float cx = x + NAV_WIDTH;
        float cw = w - NAV_WIDTH;
        // separator
        RenderUtil.flatRect(extractor, cx, y + 8.0f, 1.0f, h - 16.0f, fade(BORDER, progress));

        if (TabBar.isFeatures()) {
            drawModulesTab(extractor, cx, y, cw, h, progress, mouseX, mouseY);
        } else {
            float px = cx + PAD;
            float py = y + PAD;
            float pw = cw - PAD * 2.0f;
            float ph = h - PAD * 2.0f;
            contentRect = new float[]{px, py, pw, ph};
            // Retint the shared Scripts/HUD/Configs panels to the deadlock palette.
            cc.aerial.client.screen.tabs.TabWidgets.setThemeOverride(ACCENT, ACCENT, DEADLOCK_CARD_TINT);
            TabHost.renderContent(extractor, px, py, pw, ph, progress, mouseX, mouseY, null);
            cc.aerial.client.screen.tabs.TabWidgets.clearThemeOverride();
        }

        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
    }

    private void drawNavbar(GuiGraphicsExtractor extractor, float x, float y, float h, float alpha,
                            int mouseX, int mouseY) {
        AerialFont bold = AerialClickGui.boldFont();
        AerialFont medium = AerialClickGui.mediumFont();
        AerialFont icons = AerialClickGui.outlinedIconFont();

        // Logo
        float logoW = TextRenderUtil.drawString(extractor, bold, "aerial", x + 16.0f, y + 16.0f, 13.0f,
                fade(TEXT, alpha));
        RenderUtil.roundedRect(extractor, x + 16.0f + logoW + 4.0f, y + 17.0f, 4.0f, 4.0f, 2.0f, fade(ACCENT, alpha));

        float itemY = y + 50.0f;
        for (NavItem item : NAV) {
            boolean selected = TabBar.selected() == item.tab;
            boolean hovered = in(mouseX, mouseY, x + 10.0f, itemY, NAV_WIDTH - 20.0f, NAV_ITEM_H);
            Animation hoverAnim = navHover.computeIfAbsent(item.tab, k -> new Animation(Easing.DECELERATE, 150L));
            hoverAnim.run(hovered ? 1.0f : 0.0f);

            if (selected) {
                RenderUtil.roundedRect(extractor, x + 10.0f, itemY, NAV_WIDTH - 20.0f, NAV_ITEM_H, 4.0f,
                        fade(NAV_SELECTED, alpha));
            }
            int base = lerpColor(TEXT_DISABLED, TEXT, hoverAnim.getValue());
            int color = selected ? ACCENT : base;

            float iconSize = 11.0f;
            TextRenderUtil.drawString(extractor, icons, String.valueOf(item.icon), x + 20.0f,
                    itemY + (NAV_ITEM_H - iconSize) * 0.5f, iconSize, fade(color, alpha));
            TextRenderUtil.drawString(extractor, medium, item.label, x + 40.0f,
                    itemY + (NAV_ITEM_H - 8.0f) * 0.5f, 8.5f, fade(color, alpha));

            navRects.add(new float[]{x + 10.0f, itemY, NAV_WIDTH - 20.0f, NAV_ITEM_H, item.tab.ordinal()});
            itemY += NAV_ITEM_H + 4.0f;
        }

        // Watermark
        String user = Minecraft.getInstance().getUser().getName();
        TextRenderUtil.drawString(extractor, medium, "aerial • " + user, x + 16.0f, y + h - 24.0f, 7.0f,
                fade(TEXT_DISABLED, alpha));
    }

    private void drawModulesTab(GuiGraphicsExtractor extractor, float cx, float y, float cw, float h,
                                float alpha, int mouseX, int mouseY) {
        AerialFont medium = AerialClickGui.mediumFont();

        // Subtabs (categories) with animated underline
        float sx = cx + PAD;
        float subY = y + 16.0f;
        float selX = sx;
        float selW = 0.0f;
        for (ModuleCategory category : ModuleCategory.VALUES) {
            String label = category.getName();
            float tw = medium.stringWidth(label, 8.5f);
            boolean selected = category == sCategory;
            boolean hovered = in(mouseX, mouseY, sx - 2.0f, subY - 2.0f, tw + 4.0f, 14.0f);
            int color = selected ? TEXT : lerpColor(TEXT_DISABLED, TEXT, hovered ? 0.6f : 0.0f);
            TextRenderUtil.drawString(extractor, medium, label, sx, subY, 8.5f, fade(color, alpha));
            subRects.add(new float[]{sx - 2.0f, subY - 4.0f, tw + 4.0f, 18.0f, category.ordinal()});
            if (selected) {
                selX = sx;
                selW = tw;
            }
            sx += tw + 18.0f;
        }
        indicatorX.run(selX);
        indicatorW.run(selW);
        RenderUtil.roundedRect(extractor, indicatorX.getValue(), subY + 13.0f, Math.max(1.0f, indicatorW.getValue()),
                1.5f, 0.75f, fade(ACCENT, alpha));

        // Search field (top-right)
        float searchW = 120.0f;
        float searchH = 18.0f;
        float searchX = cx + cw - PAD - searchW;
        float searchY = y + 10.0f;
        searchRect = new float[]{searchX, searchY, searchW, searchH};
        RenderUtil.roundedRect(extractor, searchX, searchY, searchW, searchH, 4.0f, fade(FRAME_BG, alpha));
        if (search.isFocused()) {
            RenderUtil.roundedOutline(extractor, searchX, searchY, searchW, searchH, 4.0f, 1.0f, fade(ACCENT, alpha), null);
        }
        search.draw(extractor, searchX + 6.0f, searchY + (searchH - 8.0f) * 0.5f, searchW - 12.0f, 8.0f);

        // Module list
        float listX = cx + PAD;
        float listW = cw - PAD * 2.0f;
        float listTop = y + 38.0f;
        float listBottom = y + h - PAD;
        int listTopI = Math.round(listTop);
        ScreenRectangle scissor = new ScreenRectangle(Math.round(listX), listTopI,
                Math.round(listW), Math.round(listBottom - listTop));

        List<Module> modules = visibleModules();
        float total = 0.0f;
        for (Module m : modules) {
            RowContent content = contentFor(m);
            ModuleUi u = ui(m);
            u.expand.run(u.expanded && content != null ? 1.0f : 0.0f);
            float contentH = content != null ? content.measure(listX + INDENT, 0.0f, listW - INDENT, mouseX, mouseY) : 0.0f;
            total += ROW_H + (content != null ? u.expand.getValue() * contentH : 0.0f) + 4.0f;
        }
        float viewH = listBottom - listTop;
        listScroll.onScroll(Math.max(0.0f, total - viewH));
        float scroll = listScroll.getAnimation().getValue();

        float cursor = listTop + scroll;
        for (Module m : modules) {
            RowContent content = contentFor(m);
            boolean hasProps = content != null;
            ModuleUi u = ui(m);
            boolean enabled = m.isEnabled();
            boolean hovered = in(mouseX, mouseY, listX, cursor, listW, ROW_H) && my_in(mouseY, listTop, listBottom);
            u.hover.run(hovered ? 1.0f : 0.0f);
            u.toggle.run(enabled ? 1.0f : 0.0f);

            float contentH = hasProps ? content.measure(listX + INDENT, 0.0f, listW - INDENT, mouseX, mouseY) : 0.0f;
            float rowH = ROW_H + (hasProps ? u.expand.getValue() * contentH : 0.0f);

            if (cursor + rowH >= listTop && cursor <= listBottom) {
                // row surface
                float surface = Math.max(u.hover.getValue() * 0.5f, enabled ? 0.6f : 0.0f);
                if (surface > 0.01f) {
                    RenderUtil.roundedRect(extractor, listX, cursor, listW, ROW_H, 4.0f,
                            fade(lerpColor(WINDOW_BG, FRAME_HOVER, surface), alpha), scissor);
                }
                // checkbox
                float boxSize = 12.0f;
                float boxX = listX + 8.0f;
                float boxY = cursor + (ROW_H - boxSize) * 0.5f;
                RenderUtil.roundedRect(extractor, boxX, boxY, boxSize, boxSize, 3.0f, fade(FRAME_BG, alpha), scissor);
                float t = u.toggle.getValue();
                if (t > 0.01f) {
                    float inset = 3.0f - 1.5f * t;
                    RenderUtil.roundedRect(extractor, boxX + inset, boxY + inset, boxSize - inset * 2.0f,
                            boxSize - inset * 2.0f, 2.0f, fade(ACCENT, alpha * t), scissor);
                }
                RenderUtil.roundedOutline(extractor, boxX, boxY, boxSize, boxSize, 3.0f, 1.0f,
                        fade(enabled ? ACCENT : BORDER, alpha), scissor);
                // name
                int nameColor = lerpColor(TEXT_DISABLED, TEXT, Math.max(u.hover.getValue(), t));
                TextRenderUtil.drawString(extractor, medium, m.getName(), listX + 26.0f,
                        cursor + (ROW_H - 8.0f) * 0.5f, 8.5f, fade(nameColor, alpha), scissor);
                // chevron
                if (hasProps) {
                    float iconY = cursor + (ROW_H - 10.0f) * 0.5f;
                    TextRenderUtil.drawString(extractor, AerialClickGui.regularIconFont(),
                            String.valueOf(AerialClickGui.EXPAND_ICON), listX + listW - 16.0f, iconY, 10.0f,
                            fade(u.expanded ? ACCENT : TEXT_DISABLED, alpha), scissor);
                }
                // settings
                if (hasProps && u.expand.getValue() > 0.01f) {
                    content.draw(extractor, listX + INDENT, cursor + ROW_H, listW - INDENT,
                            u.expand.getValue() * alpha, mouseX, mouseY, scissor, true);
                }
            }

            rowHits.add(new RowHit(m, cursor, hasProps, content, listX + INDENT, cursor + ROW_H, listW - INDENT));
            cursor += rowH + 4.0f;
        }
    }

    private static boolean my_in(double my, float top, float bottom) {
        return my >= top && my <= bottom;
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        ensureCentred();
        if (Float.isNaN(sPanelX)) {
            return super.mouseClicked(event, doubled);
        }
        double mx = event.x();
        double my = event.y();
        int button = event.button();
        float w = winW();
        float h = winH();
        float x = sPanelX;
        float y = sPanelY;

        if (!in(mx, my, x, y, w, h)) {
            return super.mouseClicked(event, doubled);
        }

        // Navbar tabs
        for (float[] r : navRects) {
            if (in(mx, my, r[0], r[1], r[2], r[3])) {
                ClickGuiTab tab = ClickGuiTab.VALUES[(int) r[4]];
                if (TabBar.selected() != tab) {
                    TabBar.select(tab);
                    var c = TabHost.content();
                    if (c != null) {
                        c.onShow();
                    }
                }
                return true;
            }
        }

        if (TabBar.isFeatures()) {
            // subtabs
            for (float[] r : subRects) {
                if (in(mx, my, r[0], r[1], r[2], r[3])) {
                    ModuleCategory cat = ModuleCategory.VALUES[(int) r[4]];
                    if (cat != sCategory) {
                        sCategory = cat;
                        listScroll.getAnimation().setValue(0.0f);
                    }
                    return true;
                }
            }
            // search
            if (searchRect.length == 4
                    && search.mouseClicked(mx, my, searchRect[0] + 6.0f, searchRect[1] + 5.0f,
                    searchRect[2] - 12.0f, 8.0f)) {
                return true;
            }
            search.setFocused(false);
            // module rows
            for (RowHit hit : rowHits) {
                if (in(mx, my, x + NAV_WIDTH + PAD, hit.rowY, w - NAV_WIDTH - PAD * 2.0f, ROW_H)) {
                    if (hit.hasProps && (button == 1 || mx >= x + w - PAD - 20.0f)) {
                        ui(hit.module).expanded = !ui(hit.module).expanded;
                    } else if (button == 0) {
                        hit.module.toggle();
                    }
                    return true;
                }
                if (hit.hasProps && ui(hit.module).expand.getValue() > 0.01f && hit.content != null
                        && hit.content.mouseClicked(hit.cx, hit.cy, hit.cw, mx, my, button)) {
                    return true;
                }
            }
        } else if (contentRect.length == 4
                && TabHost.contentClicked(mx, my, button, contentRect[0], contentRect[1], contentRect[2], contentRect[3])) {
            return true;
        }

        // Drag by header strip
        if (in(mx, my, x, y, w, HEADER_DRAG)) {
            dragging = true;
            dragOffX = (float) mx - sPanelX;
            dragOffY = (float) my - sPanelY;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = false;
        TabHost.contentReleased(event.button());
        for (RowHit hit : rowHits) {
            if (hit.content != null) {
                hit.content.mouseReleased(event.button());
            }
        }
        return super.mouseReleased(event);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (dragging) {
            sPanelX = (float) mouseX - dragOffX;
            sPanelY = (float) mouseY - dragOffY;
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        ensureCentred();
        if (Float.isNaN(sPanelX)) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        float w = winW();
        float h = winH();
        float x = sPanelX;
        float y = sPanelY;
        if (TabBar.isFeatures()) {
            if (in(mouseX, mouseY, x + NAV_WIDTH, y + 34.0f, w - NAV_WIDTH, h - 44.0f)) {
                listScroll.addScroll(verticalAmount, Float.MAX_VALUE);
                return true;
            }
        } else if (contentRect.length == 4
                && TabHost.contentScrolled(mouseX, mouseY, verticalAmount, contentRect[0], contentRect[1],
                contentRect[2], contentRect[3])) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (TabBar.isFeatures() && search.isFocused()) {
            return search.charTyped((char) event.codepoint());
        }
        if (!TabBar.isFeatures() && TabHost.contentCharTyped((char) event.codepoint())) {
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        if (TabBar.isFeatures() && search.isFocused()) {
            if (keyEvent.key() == GLFW.GLFW_KEY_ESCAPE) {
                search.setFocused(false);
                return true;
            }
            return search.keyPressed(keyEvent);
        }
        if (!TabBar.isFeatures() && TabHost.contentKeyPressed(keyEvent)) {
            return true;
        }
        if (keyEvent.key() == GLFW.GLFW_KEY_ESCAPE) {
            requestClose();
            return true;
        }
        return super.keyPressed(keyEvent);
    }
}
