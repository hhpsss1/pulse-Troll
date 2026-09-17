package cc.aerial.client.screen.tabs;

import cc.aerial.client.config.ConfigUtility;
import cc.aerial.client.render.RenderUtil;
import cc.aerial.client.screen.AerialClickGui;
import cc.aerial.client.screen.animation.Scroller;
import cc.aerial.client.screen.tabs.scripts.BlockDef;
import cc.aerial.client.screen.tabs.scripts.BlockPalette;
import cc.aerial.client.screen.tabs.scripts.ScriptApiReference;
import cc.aerial.client.screen.tabs.scripts.ScriptCodeGen;
import cc.aerial.client.screen.tabs.scripts.ScriptDocs;
import cc.aerial.client.screen.tabs.scripts.ScriptLanguage;
import cc.aerial.client.screen.tabs.scripts.ScriptModel;
import cc.aerial.client.screen.tabs.scripts.ScriptRunner;
import cc.aerial.client.screen.widget.AerialTextArea;
import cc.aerial.client.script.ScriptSystem;
import net.minecraft.client.Minecraft;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.KeyEvent;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Script constructor tab. Two modes: a visual block constructor for non-coders, and an advanced
 * code editor (JavaScript or Lua) with a quick-access API docs drawer. Everything is rendered
 * natively — no embedded browser.
 */
public final class ScriptsPanel implements TabContent {
    private enum Mode {CONSTRUCTOR, ADVANCED}

    private enum FocusKind {NONE, NAME, PARAM}

    private static final float PAD = 10.0f;
    private static final float TOPBAR_H = 20.0f;
    private static final float GAP = 8.0f;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private record Zone(float x, float y, float w, float h, Runnable action) {
    }

    private record MenuItem(String label, Runnable action) {
    }

    private Mode mode = Mode.CONSTRUCTOR;
    private boolean docsOpen;
    private boolean showCode;
    private String status = "";
    private long statusUntil;

    // Advanced editor
    private final AerialTextArea editor = new AerialTextArea(AerialClickGui.mediumFont(), 8.0f, "// write a script…");
    private ScriptLanguage language = ScriptLanguage.JAVASCRIPT;
    private String currentFile;
    private List<String> files = new ArrayList<>();
    private final Scroller fileScroll = new Scroller();

    // Constructor
    private final ScriptModel model = new ScriptModel();
    private final Scroller canvasScroll = new Scroller();
    private final AerialTextArea inline = new AerialTextArea(AerialClickGui.mediumFont(), 8.0f, "");
    private FocusKind focusKind = FocusKind.NONE;
    private ScriptModel.Block focusBlock;
    private int focusParam;

    // Docs
    private final Scroller docsScroll = new Scroller();

    // Immediate-mode interaction state (rebuilt every render)
    private final List<Zone> zones = new ArrayList<>();
    private double clickX;
    private double clickY;
    private float editorX, editorY, editorW, editorH;
    private float canvasX, canvasY, canvasW, canvasH;
    private float fileX, fileY, fileW, fileH;
    private float docsX, docsY, docsW, docsH;

    // Popup menu
    private List<MenuItem> menu;
    private float menuX, menuY, menuW;

    public ScriptsPanel() {
        inline.setSingleLine(true);
        editor.setValue(starterTemplate());
    }

    @Override
    public void onShow() {
        refreshFiles();
    }

    private void flash(String message) {
        status = message;
        statusUntil = System.currentTimeMillis() + 3000L;
    }

    private void zone(float x, float y, float w, float h, Runnable action) {
        zones.add(new Zone(x, y, w, h, action));
    }

    private void blur() {
        focusKind = FocusKind.NONE;
        focusBlock = null;
        inline.setFocused(false);
        editor.setFocused(false);
    }

    // ---------------------------------------------------------------- render

    @Override
    public void render(GuiGraphicsExtractor extractor, float x, float y, float w, float h,
                       float alpha, int mouseX, int mouseY, ScreenRectangle scissor) {
        zones.clear();
        TabWidgets.card(extractor, x, y, w, h, 10.0f, alpha, scissor);

        float innerX = x + PAD;
        float barY = y + PAD;

        // Segmented mode control
        float segW = 88.0f;
        modeButton(extractor, "Constructor", Mode.CONSTRUCTOR, innerX, barY, segW, alpha, mouseX, mouseY, scissor);
        modeButton(extractor, "Advanced", Mode.ADVANCED, innerX + segW + 4.0f, barY, segW, alpha, mouseX, mouseY, scissor);

        // Docs + AI-copy toggles (right)
        float docsBtnW = 54.0f;
        float docsBtnX = x + w - PAD - docsBtnW;
        boolean docsHover = TabWidgets.hover(mouseX, mouseY, docsBtnX, barY, docsBtnW, TOPBAR_H);
        TabWidgets.button(extractor, docsBtnX, barY, docsBtnW, TOPBAR_H, "Docs", TabIcons.DOC, 8.0f,
                docsOpen, docsHover, alpha, scissor);
        zone(docsBtnX, barY, docsBtnW, TOPBAR_H, () -> docsOpen = !docsOpen);

        float aiW = 48.0f;
        float aiX = docsBtnX - 6.0f - aiW;
        boolean aiHover = TabWidgets.hover(mouseX, mouseY, aiX, barY, aiW, TOPBAR_H);
        TabWidgets.button(extractor, aiX, barY, aiW, TOPBAR_H, "AI", TabIcons.COPY, 8.0f, false, aiHover, alpha, scissor);
        zone(aiX, barY, aiW, TOPBAR_H, this::copyApiForAI);

        float contentY = barY + TOPBAR_H + GAP;
        float contentH = y + h - PAD - contentY;

        if (System.currentTimeMillis() < statusUntil) {
            float sw = TabWidgets.textWidth(status, 7.0f);
            TabWidgets.text(extractor, status, aiX - 8.0f - sw, barY + 6.5f, 7.0f,
                    TabWidgets.withAlpha(TabWidgets.TEXT_DIM, alpha), scissor);
        }

        if (mode == Mode.ADVANCED) {
            renderAdvanced(extractor, innerX, contentY, w - PAD * 2.0f, contentH, alpha, mouseX, mouseY, scissor);
        } else {
            renderConstructor(extractor, innerX, contentY, w - PAD * 2.0f, contentH, alpha, mouseX, mouseY, scissor);
        }

        if (docsOpen) {
            renderDocs(extractor, x, contentY, x + w, y + h - PAD, alpha, mouseX, mouseY, scissor);
        }
        if (menu != null) {
            renderMenu(extractor, alpha, mouseX, mouseY, scissor);
        }
    }

    private void modeButton(GuiGraphicsExtractor extractor, String label, Mode target,
                            float x, float y, float w, float alpha, int mouseX, int mouseY, ScreenRectangle scissor) {
        boolean active = mode == target;
        boolean hover = TabWidgets.hover(mouseX, mouseY, x, y, w, TOPBAR_H);
        TabWidgets.button(extractor, x, y, w, TOPBAR_H, label, (char) 0, 8.0f, active, hover, alpha, scissor);
        zone(x, y, w, TOPBAR_H, () -> {
            mode = target;
            menu = null;
        });
    }

    // ---------------------------------------------------------------- advanced

    private void renderAdvanced(GuiGraphicsExtractor extractor, float x, float y, float w, float h,
                                float alpha, int mouseX, int mouseY, ScreenRectangle scissor) {
        // Toolbar
        float bh = 18.0f;
        float bw = 54.0f;
        float bx = x;
        toolButton(extractor, "New", TabIcons.NEW, bx, y, bw, bh, alpha, mouseX, mouseY, scissor, this::newScript);
        bx += bw + 5.0f;
        toolButton(extractor, "Save", TabIcons.SAVE, bx, y, bw, bh, alpha, mouseX, mouseY, scissor, this::saveEditor);
        bx += bw + 5.0f;
        toolButton(extractor, "Run", TabIcons.PLAY, bx, y, bw, bh, alpha, mouseX, mouseY, scissor, this::runEditor);
        bx += bw + 5.0f;
        toolButton(extractor, "Reload", TabIcons.REFRESH, bx, y, bw, bh, alpha, mouseX, mouseY, scissor,
                () -> ScriptSystem.getInstance().reloadScripts());

        float langW = 66.0f;
        float langX = x + w - langW;
        boolean langHover = TabWidgets.hover(mouseX, mouseY, langX, y, langW, bh);
        TabWidgets.button(extractor, langX, y, langW, bh, language.display, (char) 0, 7.5f, false, langHover, alpha, scissor);
        zone(langX, y, langW, bh, () -> language = language.next());

        float bodyY = y + bh + 6.0f;
        float bodyH = h - bh - 6.0f;

        // File list
        fileX = x;
        fileY = bodyY;
        fileW = 118.0f;
        fileH = bodyH;
        RenderUtil.roundedRect(extractor, fileX, fileY, fileW, fileH, 6.0f,
                TabWidgets.withAlpha(0xFF12131A, alpha), scissor);
        ScreenRectangle fileClip = intersect(scissor, rect(fileX, fileY, fileW, fileH));
        TabWidgets.text(extractor, "Scripts", fileX + 7.0f, fileY + 6.0f, 7.5f,
                TabWidgets.withAlpha(TabWidgets.TEXT_FAINT, alpha), fileClip);
        float rowH = 16.0f;
        float listTop = fileY + 20.0f;
        float scroll = fileScroll.getAnimation().getValue();
        fileScroll.onScroll(Math.max(0.0f, files.size() * rowH - (fileH - 22.0f)));
        for (int i = 0; i < files.size(); i++) {
            String name = files.get(i);
            float ry = listTop + i * rowH + scroll;
            if (ry + rowH < listTop || ry > fileY + fileH) {
                continue;
            }
            boolean current = name.equals(currentFile);
            boolean hover = TabWidgets.hover(mouseX, mouseY, fileX + 3.0f, ry, fileW - 6.0f, rowH - 2.0f);
            if (current || hover) {
                RenderUtil.roundedRect(extractor, fileX + 3.0f, ry, fileW - 6.0f, rowH - 2.0f, 4.0f,
                        current ? TabWidgets.withAlpha(TabWidgets.accent(), 0.25f * alpha)
                                : TabWidgets.withAlpha(0x1EFFFFFF, alpha), fileClip);
            }
            TabWidgets.text(extractor, name, fileX + 8.0f, ry + 4.0f, 7.5f,
                    TabWidgets.withAlpha(current ? TabWidgets.TEXT : TabWidgets.TEXT_DIM, alpha), fileClip);
            final String open = name;
            zone(fileX + 3.0f, ry, fileW - 6.0f, rowH - 2.0f, () -> openFile(open));

            if (hover) {
                float delSize = rowH - 4.0f;
                float delX = fileX + fileW - 5.0f - delSize;
                float delY = ry + 1.0f;
                boolean delHover = TabWidgets.hover(mouseX, mouseY, delX, delY, delSize, delSize);
                RenderUtil.roundedRect(extractor, delX, delY, delSize, delSize, 3.0f,
                        TabWidgets.withAlpha(delHover ? 0x40E0555F : 0x22E0555F, alpha), fileClip);
                TabWidgets.icon(extractor, TabIcons.TRASH, delX + (delSize - 8.0f) * 0.5f,
                        delY + (delSize - 8.0f) * 0.5f, 8.0f,
                        TabWidgets.withAlpha(delHover ? 0xFFFF8A90 : TabWidgets.DANGER, alpha), fileClip);
                final String del = name;
                zone(delX, delY, delSize, delSize, () -> deleteScript(del));
            }
        }

        // Editor
        editorX = x + fileW + 8.0f;
        editorY = bodyY;
        editorW = w - fileW - 8.0f;
        editorH = bodyH;
        TabWidgets.field(extractor, editorX, editorY, editorW, editorH, editor.isFocused(), alpha, scissor);
        editor.draw(extractor, editorX + 6.0f, editorY + 6.0f, editorW - 12.0f, editorH - 12.0f);
        zone(editorX, editorY, editorW, editorH, () -> {
            focusKind = FocusKind.NONE;
            inline.setFocused(false);
            editor.mouseClicked(clickX, clickY, editorX + 6.0f, editorY + 6.0f, editorW - 12.0f, editorH - 12.0f);
        });
    }

    private void toolButton(GuiGraphicsExtractor extractor, String label, char icon, float x, float y,
                            float w, float hgt, float alpha, int mouseX, int mouseY, ScreenRectangle scissor,
                            Runnable action) {
        boolean hover = TabWidgets.hover(mouseX, mouseY, x, y, w, hgt);
        TabWidgets.button(extractor, x, y, w, hgt, label, icon, 7.5f, false, hover, alpha, scissor);
        zone(x, y, w, hgt, action);
    }

    // ---------------------------------------------------------------- constructor

    private void renderConstructor(GuiGraphicsExtractor extractor, float x, float y, float w, float h,
                                   float alpha, int mouseX, int mouseY, ScreenRectangle scissor) {
        // Name row + code toggle + build/save
        float rowH = 18.0f;
        TabWidgets.text(extractor, "Name", x, y + 5.0f, 7.5f, TabWidgets.withAlpha(TabWidgets.TEXT_DIM, alpha), scissor);
        float nameX = x + 32.0f;
        float buildW = 82.0f;
        float saveW = 50.0f;
        float codeW = 48.0f;
        float saveX = x + w - saveW;
        float buildX = saveX - 6.0f - buildW;
        float codeX = buildX - 6.0f - codeW;
        float nameW = codeX - 6.0f - nameX;

        TabWidgets.field(extractor, nameX, y, nameW, rowH, focusKind == FocusKind.NAME, alpha, scissor);
        if (focusKind == FocusKind.NAME) {
            inline.draw(extractor, nameX + 5.0f, y + 5.0f, nameW - 10.0f, 8.0f);
        } else {
            TabWidgets.text(extractor, model.name, nameX + 5.0f, y + 5.0f, 8.0f,
                    TabWidgets.withAlpha(TabWidgets.TEXT, alpha), scissor);
        }
        final float fnx = nameX, fny = y, fnw = nameW;
        zone(nameX, y, nameW, rowH, () -> focusName(fnx, fny, fnw, rowH));

        boolean codeHover = TabWidgets.hover(mouseX, mouseY, codeX, y, codeW, rowH);
        TabWidgets.button(extractor, codeX, y, codeW, rowH, "Code", TabIcons.CODE, 7.5f, showCode, codeHover, alpha, scissor);
        zone(codeX, y, codeW, rowH, () -> showCode = !showCode);

        boolean buildHover = TabWidgets.hover(mouseX, mouseY, buildX, y, buildW, rowH);
        TabWidgets.button(extractor, buildX, y, buildW, rowH, "Build & Run", TabIcons.PLAY, 7.5f, true, buildHover, alpha, scissor);
        zone(buildX, y, buildW, rowH, this::buildAndRun);

        boolean saveHover = TabWidgets.hover(mouseX, mouseY, saveX, y, saveW, rowH);
        TabWidgets.button(extractor, saveX, y, saveW, rowH, "Save", TabIcons.SAVE, 7.5f, false, saveHover, alpha, scissor);
        zone(saveX, y, saveW, rowH, this::saveProject);

        // Canvas
        canvasX = x;
        canvasY = y + rowH + 8.0f;
        canvasW = w;
        canvasH = y + h - canvasY;
        ScreenRectangle clip = intersect(scissor, rect(canvasX, canvasY, canvasW, canvasH));

        if (showCode) {
            renderCodeView(extractor, alpha, clip);
            return;
        }

        float scroll = canvasScroll.getAnimation().getValue();
        float cy = canvasY + scroll;

        cy = sectionHeader(extractor, "Trigger", canvasX, cy, alpha, clip);
        cy = renderTrigger(extractor, canvasX, cy, canvasW, alpha, mouseX, mouseY, clip);
        cy += 6.0f;

        cy = sectionHeader(extractor, "When — all conditions true (" + model.conditions.size() + ")",
                canvasX, cy, alpha, clip);
        cy = renderBlocks(extractor, model.conditions, BlockPalette.CONDITIONS, canvasX, cy, canvasW,
                alpha, mouseX, mouseY, clip);
        cy += 6.0f;

        cy = sectionHeader(extractor, "Do — in order (" + model.actions.size() + ")", canvasX, cy, alpha, clip);
        cy = renderBlocks(extractor, model.actions, BlockPalette.ACTIONS, canvasX, cy, canvasW,
                alpha, mouseX, mouseY, clip);

        float contentHeight = cy - scroll - canvasY;
        canvasScroll.onScroll(Math.max(0.0f, contentHeight - canvasH));
    }

    private void renderCodeView(GuiGraphicsExtractor extractor, float alpha, ScreenRectangle clip) {
        RenderUtil.roundedRect(extractor, canvasX, canvasY, canvasW, canvasH, 6.0f,
                TabWidgets.withAlpha(0xFF101017, alpha), clip);
        String[] lines = ScriptCodeGen.toJs(model).split("\n", -1);
        float scroll = canvasScroll.getAnimation().getValue();
        float cy = canvasY + 5.0f + scroll;
        for (String line : lines) {
            TabWidgets.text(extractor, line.isEmpty() ? " " : line, canvasX + 6.0f, cy, 7.0f,
                    TabWidgets.withAlpha(0xFFB9C0CC, alpha), clip);
            cy += 9.0f;
        }
        float contentHeight = lines.length * 9.0f + 10.0f;
        canvasScroll.onScroll(Math.max(0.0f, contentHeight - canvasH));
    }

    private void iconBtn(GuiGraphicsExtractor extractor, char glyph, float x, float y, float size,
                         int normal, int hoverColor, float alpha, ScreenRectangle clip,
                         int mouseX, int mouseY, Runnable action) {
        boolean hovered = TabWidgets.hover(mouseX, mouseY, x, y, size, size);
        RenderUtil.roundedRect(extractor, x, y, size, size, 4.0f,
                TabWidgets.withAlpha(hovered ? 0x30FFFFFF : 0x16FFFFFF, alpha), clip);
        TabWidgets.icon(extractor, glyph, x + (size - 9.0f) * 0.5f, y + (size - 9.0f) * 0.5f, 9.0f,
                TabWidgets.withAlpha(hovered ? hoverColor : normal, alpha), clip);
        zone(x, y, size, size, action);
    }

    private float sectionHeader(GuiGraphicsExtractor extractor, String title, float x, float y,
                                float alpha, ScreenRectangle clip) {
        TabWidgets.text(extractor, title.toUpperCase(), x, y, 7.0f,
                TabWidgets.withAlpha(TabWidgets.TEXT_FAINT, alpha), clip);
        return y + 12.0f;
    }

    private float renderTrigger(GuiGraphicsExtractor extractor, float x, float y, float w,
                                float alpha, int mouseX, int mouseY, ScreenRectangle clip) {
        float bh = 20.0f;
        boolean hover = TabWidgets.hover(mouseX, mouseY, x, y, w, bh);
        RenderUtil.roundedRect(extractor, x, y, w, bh, 6.0f,
                TabWidgets.withAlpha(hover ? 0x24FFFFFF : 0x16FFFFFF, alpha), clip);
        TabWidgets.icon(extractor, TabIcons.CHEVRON, x + w - 16.0f, y + 4.0f, 11.0f,
                TabWidgets.withAlpha(TabWidgets.TEXT_DIM, alpha), clip);
        TabWidgets.text(extractor, model.trigger.label, x + 8.0f, y + 6.0f, 8.0f,
                TabWidgets.withAlpha(TabWidgets.TEXT, alpha), clip);
        zone(x, y, w, bh, this::cycleTrigger);
        return y + bh + 4.0f;
    }

    private float renderBlocks(GuiGraphicsExtractor extractor, List<ScriptModel.Block> list,
                               List<BlockDef> palette, float x, float y, float w,
                               float alpha, int mouseX, int mouseY, ScreenRectangle clip) {
        float cy = y;
        for (int i = 0; i < list.size(); i++) {
            ScriptModel.Block block = list.get(i);
            boolean hasParams = block.def.params.size() > 0;
            float bh = hasParams ? 34.0f : 20.0f;

            RenderUtil.roundedRect(extractor, x, cy, w, bh, 6.0f,
                    TabWidgets.withAlpha(0xFF171922, alpha), clip);

            // Title (label with params substituted for a readable summary)
            TabWidgets.text(extractor, ScriptCodeGen.label(block.def, block.values), x + 8.0f, cy + 5.0f, 8.0f,
                    TabWidgets.withAlpha(TabWidgets.TEXT, alpha), clip);

            // Toolbar: duplicate / move up / move down / remove
            final List<ScriptModel.Block> owner = list;
            final ScriptModel.Block cur = block;
            final int idx = i;
            float bs = 13.0f;
            float by = cy + 4.0f;
            float bx = x + w - 6.0f - bs;
            iconBtn(extractor, TabIcons.TRASH, bx, by, bs, TabWidgets.DANGER, 0xFFFF8A90, alpha, clip,
                    mouseX, mouseY, () -> owner.remove(cur));
            bx -= bs + 2.0f;
            iconBtn(extractor, TabIcons.DOWN, bx, by, bs, TabWidgets.TEXT_DIM, TabWidgets.TEXT, alpha, clip,
                    mouseX, mouseY, () -> {
                        if (idx < owner.size() - 1) {
                            java.util.Collections.swap(owner, idx, idx + 1);
                        }
                    });
            bx -= bs + 2.0f;
            iconBtn(extractor, TabIcons.UP, bx, by, bs, TabWidgets.TEXT_DIM, TabWidgets.TEXT, alpha, clip,
                    mouseX, mouseY, () -> {
                        if (idx > 0) {
                            java.util.Collections.swap(owner, idx, idx - 1);
                        }
                    });
            bx -= bs + 2.0f;
            iconBtn(extractor, TabIcons.COPY, bx, by, bs, TabWidgets.TEXT_DIM, TabWidgets.TEXT, alpha, clip,
                    mouseX, mouseY, () -> {
                        ScriptModel.Block copy = new ScriptModel.Block(cur.def);
                        System.arraycopy(cur.values, 0, copy.values, 0, cur.values.length);
                        owner.add(Math.min(idx + 1, owner.size()), copy);
                    });

            // Param fields
            if (hasParams) {
                float px = x + 8.0f;
                float py = cy + 18.0f;
                for (int p = 0; p < block.def.params.size(); p++) {
                    BlockDef.Param param = block.def.params.get(p);
                    TabWidgets.text(extractor, param.name(), px, py + 3.0f, 7.0f,
                            TabWidgets.withAlpha(TabWidgets.TEXT_FAINT, alpha), clip);
                    float labelW = TabWidgets.textWidth(param.name(), 7.0f) + 4.0f;
                    float fx = px + labelW;
                    float fw = 54.0f;
                    boolean focused = focusKind == FocusKind.PARAM && focusBlock == block && focusParam == p;
                    TabWidgets.field(extractor, fx, py, fw, 13.0f, focused, alpha, clip);
                    if (focused) {
                        inline.draw(extractor, fx + 4.0f, py + 3.0f, fw - 8.0f, 8.0f);
                    } else {
                        TabWidgets.text(extractor, block.values[p], fx + 4.0f, py + 3.0f, 7.5f,
                                TabWidgets.withAlpha(TabWidgets.TEXT, alpha), clip);
                    }
                    final ScriptModel.Block fb = block;
                    final int fp = p;
                    final float zfx = fx, zfy = py, zfw = fw;
                    zone(fx, py, fw, 13.0f, () -> focusFieldParam(fb, fp, zfx, zfy, zfw, 13.0f));
                    px = fx + fw + 10.0f;
                }
            }
            cy += bh + 5.0f;
        }

        // Add button
        float addW = 96.0f;
        float addH = 17.0f;
        boolean addHover = TabWidgets.hover(mouseX, mouseY, x, cy, addW, addH);
        TabWidgets.button(extractor, x, cy, addW, addH, "Add block", TabIcons.ADD, 7.5f, false, addHover, alpha, clip);
        final float mx = x, my = cy + addH + 2.0f;
        zone(x, cy, addW, addH, () -> openBlockMenu(palette, list, mx, my));
        return cy + addH + 4.0f;
    }

    // ---------------------------------------------------------------- docs drawer

    private void renderDocs(GuiGraphicsExtractor extractor, float left, float top, float right, float bottom,
                            float alpha, int mouseX, int mouseY, ScreenRectangle scissor) {
        float dw = Math.min(240.0f, (right - left) * 0.5f);
        docsX = right - dw;
        docsY = top;
        docsW = dw;
        docsH = bottom - top;
        RenderUtil.roundedRect(extractor, docsX, docsY, docsW, docsH, 8.0f,
                TabWidgets.withAlpha(0xF00E0F15, alpha), scissor);
        RenderUtil.roundedOutline(extractor, docsX, docsY, docsW, docsH, 8.0f, 1.0f,
                TabWidgets.withAlpha(0x30FFFFFF, alpha), scissor);
        ScreenRectangle clip = intersect(scissor, rect(docsX, docsY, docsW, docsH));
        TabWidgets.textBold(extractor, "Scripting API", docsX + 10.0f, docsY + 8.0f, 9.0f,
                TabWidgets.withAlpha(TabWidgets.TEXT, alpha), clip);

        float contentTop = docsY + 24.0f;
        float scroll = docsScroll.getAnimation().getValue();
        float cy = contentTop + scroll;
        float ix = docsX + 10.0f;
        for (ScriptDocs.Section section : ScriptDocs.SECTIONS) {
            TabWidgets.text(extractor, section.title(), ix, cy, 8.5f,
                    TabWidgets.withAlpha(TabWidgets.accent(), alpha), clip);
            cy += 12.0f;
            for (ScriptDocs.Entry entry : section.entries()) {
                TabWidgets.text(extractor, entry.signature(), ix, cy, 7.5f,
                        TabWidgets.withAlpha(TabWidgets.TEXT, alpha), clip);
                cy += 9.5f;
                cy = wrap(extractor, entry.description(), ix + 4.0f, cy, docsW - 24.0f, 7.0f,
                        TabWidgets.withAlpha(TabWidgets.TEXT_FAINT, alpha), clip);
                cy += 4.0f;
            }
            cy += 6.0f;
        }
        float contentHeight = cy - scroll - contentTop;
        docsScroll.onScroll(Math.max(0.0f, contentHeight - (docsH - 24.0f)));
    }

    private float wrap(GuiGraphicsExtractor extractor, String text, float x, float y, float maxW,
                       float size, int color, ScreenRectangle clip) {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        float cy = y;
        for (String word : words) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (TabWidgets.textWidth(test, size) > maxW && line.length() > 0) {
                TabWidgets.text(extractor, line.toString(), x, cy, size, color, clip);
                cy += size + 2.0f;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0) {
            TabWidgets.text(extractor, line.toString(), x, cy, size, color, clip);
            cy += size + 2.0f;
        }
        return cy;
    }

    // ---------------------------------------------------------------- popup menu

    private void renderMenu(GuiGraphicsExtractor extractor, float alpha, int mouseX, int mouseY, ScreenRectangle scissor) {
        float itemH = 16.0f;
        float mh = menu.size() * itemH + 6.0f;
        RenderUtil.roundedRect(extractor, menuX, menuY, menuW, mh, 6.0f,
                TabWidgets.withAlpha(0xFF1B1D26, alpha), scissor);
        RenderUtil.roundedOutline(extractor, menuX, menuY, menuW, mh, 6.0f, 1.0f,
                TabWidgets.withAlpha(0x33FFFFFF, alpha), scissor);
        float iy = menuY + 3.0f;
        for (MenuItem item : menu) {
            boolean hover = TabWidgets.hover(mouseX, mouseY, menuX + 2.0f, iy, menuW - 4.0f, itemH);
            if (hover) {
                RenderUtil.roundedRect(extractor, menuX + 2.0f, iy, menuW - 4.0f, itemH, 4.0f,
                        TabWidgets.withAlpha(TabWidgets.accent(), 0.3f * alpha), scissor);
            }
            TabWidgets.text(extractor, item.label(), menuX + 8.0f, iy + 4.0f, 7.5f,
                    TabWidgets.withAlpha(TabWidgets.TEXT, alpha), scissor);
            final MenuItem chosen = item;
            zone(menuX + 2.0f, iy, menuW - 4.0f, itemH, () -> {
                chosen.action().run();
                menu = null;
            });
            iy += itemH;
        }
    }

    private void openBlockMenu(List<BlockDef> palette, List<ScriptModel.Block> target, float x, float y) {
        List<MenuItem> items = new ArrayList<>();
        for (BlockDef def : palette) {
            items.add(new MenuItem(def.label, () -> {
                ScriptModel.Block block = new ScriptModel.Block(def);
                target.add(block);
            }));
        }
        openMenu(items, x, y);
    }

    private void openMenu(List<MenuItem> items, float x, float y) {
        menu = items;
        menuX = x;
        menuY = y;
        float maxW = 90.0f;
        for (MenuItem item : items) {
            maxW = Math.max(maxW, TabWidgets.textWidth(item.label(), 7.5f) + 18.0f);
        }
        menuW = maxW;
    }

    // ---------------------------------------------------------------- actions

    private void cycleTrigger() {
        int index = BlockPalette.TRIGGERS.indexOf(model.trigger);
        model.trigger = BlockPalette.TRIGGERS.get((index + 1) % BlockPalette.TRIGGERS.size());
    }

    private void focusName(float x, float y, float w, float h) {
        focusKind = FocusKind.NAME;
        focusBlock = null;
        editor.setFocused(false);
        inline.setValue(model.name);
        inline.setFocused(true);
        inline.mouseClicked(clickX, clickY, x + 5.0f, y + 5.0f, w - 10.0f, 8.0f);
    }

    private void focusFieldParam(ScriptModel.Block block, int param, float x, float y, float w, float h) {
        focusKind = FocusKind.PARAM;
        focusBlock = block;
        focusParam = param;
        editor.setFocused(false);
        inline.setValue(block.values[param] == null ? "" : block.values[param]);
        inline.setFocused(true);
        inline.mouseClicked(clickX, clickY, x + 4.0f, y + 3.0f, w - 8.0f, 8.0f);
    }

    private void newScript() {
        currentFile = null;
        editor.setValue(starterTemplate());
        flash("New " + language.display + " script");
    }

    private String starterTemplate() {
        if (language == ScriptLanguage.LUA) {
            return "-- New Aerial Lua script\nutils.log(\"hello from lua\")\n";
        }
        return """
                // New Aerial script — press Run, then Reload to clear.
                events.onRender2D(function(r) {
                    r.roundedRect(4, 4, 96, 16, 4, utils.color(0, 0, 0, 150));
                    r.text("aerial script", 9, 8.5, 8, utils.rainbow(1, 0));
                });

                events.onRender3D(function(r) {
                    game.getPlayers().forEach(function(e) {
                        r.entityBox(e, utils.color(255, 70, 70, 160), false, true);
                    });
                });

                utils.log("script loaded");
                """;
    }

    private void openFile(String name) {
        try {
            File file = new File(scriptsDir(), name);
            editor.setValue(Files.readString(file.toPath()));
            currentFile = name;
            language = ScriptLanguage.forFile(name);
            flash("Opened " + name);
        } catch (Exception e) {
            flash("Could not open " + name);
        }
    }

    private void deleteScript(String name) {
        try {
            // Immediately undo whatever this script registered (modules, HUD, events), then remove it.
            cc.aerial.client.script.ScriptContext.unload(name);
            File file = new File(scriptsDir(), name);
            if (file.delete()) {
                if (name.equals(currentFile)) {
                    currentFile = null;
                }
                refreshFiles();
                flash("Deleted " + name);
            } else {
                flash("Could not delete " + name);
            }
        } catch (Exception e) {
            flash("Delete failed: " + e.getMessage());
        }
    }

    private void saveEditor() {
        String name = currentFile;
        if (name == null) {
            name = "script_" + System.currentTimeMillis() + "." + language.ext;
        }
        try {
            File dir = scriptsDir();
            dir.mkdirs();
            File file = new File(dir, name);
            Files.writeString(file.toPath(), editor.getValue());
            currentFile = name;
            refreshFiles();
            flash("Saved " + name);
        } catch (Exception e) {
            flash("Save failed: " + e.getMessage());
        }
    }

    private void runEditor() {
        String name = currentFile != null ? currentFile : "editor." + language.ext;
        String error = ScriptRunner.run(language, name, editor.getValue());
        flash(error == null ? "Ran " + name : "Error: " + error);
    }

    private void buildAndRun() {
        String js = ScriptCodeGen.toJs(model);
        String fileName = sanitize(model.name) + ".js";
        try {
            File dir = scriptsDir();
            dir.mkdirs();
            Files.writeString(new File(dir, fileName).toPath(), js);
            refreshFiles();
        } catch (Exception e) {
            flash("Write failed: " + e.getMessage());
            return;
        }
        // Key the run by filename so deleting the file also tears down what it registered.
        String error = ScriptRunner.run(ScriptLanguage.JAVASCRIPT, fileName, js);
        flash(error == null ? "Built '" + model.name + "' — now toggle it in Features > Scripts" : "Error: " + error);
    }

    private void saveProject() {
        try {
            File dir = new File(ConfigUtility.configsDirectory(), "scripts");
            dir.mkdirs();
            File file = new File(dir, sanitize(model.name) + ".aerialscript");
            Files.writeString(file.toPath(), GSON.toJson(model.toJson()));
            flash("Saved project " + model.name);
        } catch (Exception e) {
            flash("Save failed: " + e.getMessage());
        }
    }

    /** Copy the full LLM-ready API reference + prompt to the clipboard (and save aerial_api.md). */
    private void copyApiForAI() {
        String reference = ScriptApiReference.build();
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(reference);
        } catch (Exception ignored) {
        }
        try {
            File dir = scriptsDir();
            dir.mkdirs();
            Files.writeString(new File(dir, "aerial_api.md").toPath(), reference);
        } catch (Exception ignored) {
        }
        flash("API + prompt copied — paste into your AI (saved aerial_api.md)");
    }

    private void refreshFiles() {
        files = new ArrayList<>();
        File[] found = scriptsDir().listFiles((dir, n) -> n.endsWith(".js") || n.endsWith(".lua"));
        if (found != null) {
            for (File f : found) {
                files.add(f.getName());
            }
            files.sort(String.CASE_INSENSITIVE_ORDER);
        }
    }

    private File scriptsDir() {
        return ScriptSystem.getInstance().getEngine().getScriptsDirectory();
    }

    private static String sanitize(String name) {
        String cleaned = name.replaceAll("[^a-zA-Z0-9_-]", "");
        return cleaned.isEmpty() ? "script" : cleaned;
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button, float x, float y, float w, float h) {
        clickX = mouseX;
        clickY = mouseY;
        blur();

        // Menu overlay takes priority; clicking outside closes it.
        if (menu != null) {
            for (int i = zones.size() - 1; i >= 0; i--) {
                Zone z = zones.get(i);
                if (TabWidgets.hover(mouseX, mouseY, z.x, z.y, z.w, z.h)) {
                    z.action.run();
                    return true;
                }
            }
            menu = null;
            return true;
        }

        for (int i = zones.size() - 1; i >= 0; i--) {
            Zone z = zones.get(i);
            if (TabWidgets.hover(mouseX, mouseY, z.x, z.y, z.w, z.h)) {
                z.action.run();
                return true;
            }
        }
        return TabWidgets.hover(mouseX, mouseY, x, y, w, h);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double vertical, float x, float y, float w, float h) {
        if (docsOpen && TabWidgets.hover(mouseX, mouseY, docsX, docsY, docsW, docsH)) {
            docsScroll.addScroll(vertical, Float.MAX_VALUE);
            return true;
        }
        if (mode == Mode.ADVANCED) {
            if (TabWidgets.hover(mouseX, mouseY, editorX, editorY, editorW, editorH)) {
                return editor.mouseScrolled(vertical, editorH - 12.0f);
            }
            if (TabWidgets.hover(mouseX, mouseY, fileX, fileY, fileW, fileH)) {
                fileScroll.addScroll(vertical, Float.MAX_VALUE);
                return true;
            }
        } else if (TabWidgets.hover(mouseX, mouseY, canvasX, canvasY, canvasW, canvasH)) {
            canvasScroll.addScroll(vertical, Float.MAX_VALUE);
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char codepoint) {
        if (mode == Mode.ADVANCED && editor.isFocused()) {
            return editor.charTyped(codepoint);
        }
        if (focusKind != FocusKind.NONE && inline.isFocused()) {
            inline.charTyped(codepoint);
            syncInline();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (mode == Mode.ADVANCED && editor.isFocused()) {
            return editor.keyPressed(event);
        }
        if (focusKind != FocusKind.NONE && inline.isFocused()) {
            inline.keyPressed(event);
            syncInline();
            if (!inline.isFocused()) {
                focusKind = FocusKind.NONE;
                focusBlock = null;
            }
            return true;
        }
        return false;
    }

    private void syncInline() {
        if (focusKind == FocusKind.NAME) {
            model.name = inline.getValue();
        } else if (focusKind == FocusKind.PARAM && focusBlock != null) {
            focusBlock.values[focusParam] = inline.getValue();
        }
    }

    @Override
    public void mouseReleased(int button) {
    }

    private static ScreenRectangle rect(float x, float y, float w, float h) {
        return new ScreenRectangle(Math.round(x), Math.round(y), Math.round(w), Math.round(h));
    }

    private static ScreenRectangle intersect(ScreenRectangle a, ScreenRectangle b) {
        if (a == null) {
            return b;
        }
        ScreenRectangle r = a.intersection(b);
        return r == null ? b : r;
    }
}
