package cc.aerial.client.features.impl.hud;

import cc.aerial.client.AerialClient;
import cc.aerial.client.binding.BindRepository;
import cc.aerial.client.binding.BindingService;
import cc.aerial.client.binding.InputType;
import cc.aerial.client.event.impl.render.Render2DEvent;
import cc.aerial.client.features.Module;
import cc.aerial.client.features.ModuleCategory;
import cc.aerial.client.features.repository.ModuleRepository;
import cc.aerial.client.property.BooleanProperty;
import cc.aerial.client.property.NumberProperty;
import cc.aerial.client.render.AerialBloomFilter;
import cc.aerial.client.render.AerialBlur;
import cc.aerial.client.render.BlurConsumer;
import cc.aerial.client.render.RenderUtil;
import cc.aerial.client.render.TextRenderUtil;
import cc.aerial.client.render.font.AerialFont;
import cc.aerial.client.theme.Theme;
import cc.aerial.client.utility.HudDrag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class KeybindsHud extends Module {
    public static final KeybindsHud INSTANCE = new KeybindsHud();

    private static final Minecraft mc = Minecraft.getInstance();

    private static final int BACKGROUND_COLOR = 0x80090909;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DISABLED_COLOR = 0xFF707070;
    private static final int SEPARATOR_COLOR = 0xFF303030;

    private static final float ROW_HEIGHT = 14.0f;
    private static final float PADDING = 7.0f;
    private static final float RADIUS = 5.2f;
    private static final float MODULE_NAME_SIZE = 5.6f;
    private static final float KEYBIND_SIZE = 5.6f;
    private static final float GAP = 6.0f;
    private static final float SEPARATOR_WIDTH = 1.0f;

    private final NumberProperty xPos = new NumberProperty("X", 10.0, -10000.0, 10000.0, 0.01).hideIf(() -> true);
    private final NumberProperty yPos = new NumberProperty("Y", 10.0, -10000.0, 10000.0, 0.01).hideIf(() -> true);
    private final BooleanProperty onlyActive = new BooleanProperty("Only Active", false);

    private float x = 10f, y = 10f;
    private float currentWidth = 100f, currentHeight = 30f;

    private boolean dragging;
    private float dragOffsetX, dragOffsetY;

    private static AerialFont font;

    private KeybindsHud() {
        super("Keybinds HUD", "Show your keybinds", ModuleCategory.VISUAL);
        addProperties(onlyActive, xPos, yPos);
    }

    private static void ensureFontsLoaded() {
        if (font == null) {
            font = AerialFont.createFromResource("OpalProductSansMedium.ttf");
        }
    }

    @Override
    protected void onDisable() {
        dragging = false;
    }

    public void render(Render2DEvent event) {
        AerialBloomFilter.begin(BlurConsumer.KEYBINDS_HUD);
        try {
            onRender2DBody(event);
        } finally {
            AerialBloomFilter.end();
        }
    }

    private void onRender2DBody(Render2DEvent event) {
        ensureFontsLoaded();

        if (!dragging) {
            x = xPos.getValue().floatValue();
            y = yPos.getValue().floatValue();
        }

        GuiGraphicsExtractor extractor = event.extractor();
        ModuleRepository moduleRepository = AerialClient.getModuleRepository();

        List<Module> modules = new ArrayList<>();
        for (Module module : moduleRepository.getModules()) {
            BindingService.BindKey key = BindRepository.INSTANCE.getBindingService().getKeyFromBindable(module);
            if (key != null) {
                if (!onlyActive.getValue() || module.isEnabled()) {
                    modules.add(module);
                }
            }
        }

        modules.sort(Comparator.comparing(Module::getName));

        if (modules.isEmpty()) {
            currentWidth = 0f;
            currentHeight = 0f;
            return;
        }

        float maxModuleWidth = 0f;
        float maxKeybindWidth = 0f;

        for (Module module : modules) {
            String moduleName = module.getName();
            BindingService.BindKey key = BindRepository.INSTANCE.getBindingService().getKeyFromBindable(module);
            String keybindText = key == null ? "" : BindRepository.INSTANCE.getNameFromInteger(key.code(), key.type());

            float moduleWidth = font.stringWidth(moduleName, MODULE_NAME_SIZE);
            float keybindWidth = font.stringWidth(keybindText, KEYBIND_SIZE);

            maxModuleWidth = Math.max(maxModuleWidth, moduleWidth);
            maxKeybindWidth = Math.max(maxKeybindWidth, keybindWidth);
        }

        currentWidth = PADDING * 2 + maxModuleWidth + GAP + SEPARATOR_WIDTH + GAP + maxKeybindWidth + PADDING;
        currentHeight = PADDING * 2 + modules.size() * ROW_HEIGHT;

        handleDragging();

        Theme theme = cc.aerial.client.features.impl.visual.InterfaceModule.INSTANCE.getTheme();
        int accentCol = theme.getAccentColor(0, 50).getRGB();

        AerialBlur.drawGlass(extractor, BlurConsumer.KEYBINDS_HUD, x, y, currentWidth, currentHeight,
                RADIUS, BACKGROUND_COLOR, 1.0f, null);

        float cursorY = y + PADDING;
        for (Module module : modules) {
            String moduleName = module.getName();
            BindingService.BindKey key = BindRepository.INSTANCE.getBindingService().getKeyFromBindable(module);
            String keybindText = key == null ? "" : BindRepository.INSTANCE.getNameFromInteger(key.code(), key.type());

            boolean enabled = module.isEnabled();
            int textColor = enabled ? WHITE : DISABLED_COLOR;

            float textX = x + PADDING;
            float textY = cursorY + (ROW_HEIGHT - MODULE_NAME_SIZE) / 2f;
            TextRenderUtil.drawString(extractor, font, moduleName, textX, textY, MODULE_NAME_SIZE, textColor);

            float separatorX = x + PADDING + maxModuleWidth + GAP;
            float separatorY = cursorY + 3.0f;
            float separatorHeight = ROW_HEIGHT - 6.0f;
            RenderUtil.flatRect(extractor, separatorX, separatorY, SEPARATOR_WIDTH, separatorHeight, SEPARATOR_COLOR);

            float keybindX = separatorX + SEPARATOR_WIDTH + GAP;
            TextRenderUtil.drawString(extractor, font, keybindText, keybindX, cursorY + (ROW_HEIGHT - KEYBIND_SIZE) / 2f, KEYBIND_SIZE, accentCol);

            cursorY += ROW_HEIGHT;
        }

        if (dragging) {
            drawDragOutline(extractor);
        }
    }

    private void handleDragging() {
        if (!(mc.gui.screen() instanceof ChatScreen)) {
            dragging = false;
            return;
        }
        float mouseX = (float) mc.mouseHandler.getScaledXPos(mc.getWindow());
        float mouseY = (float) mc.mouseHandler.getScaledYPos(mc.getWindow());

        boolean leftPressed = GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (leftPressed) {
            if (!dragging && isHovered(mouseX, mouseY, x, y, currentWidth, currentHeight)) {
                dragging = true;
                dragOffsetX = mouseX - x;
                dragOffsetY = mouseY - y;
            }
            if (dragging) {
                float screenW = mc.getWindow().getGuiScaledWidth();
                float screenH = mc.getWindow().getGuiScaledHeight();
                x = HudDrag.clamp(mouseX - dragOffsetX, currentWidth, screenW);
                y = HudDrag.clamp(mouseY - dragOffsetY, currentHeight, screenH);
                xPos.setValue((double) x);
                yPos.setValue((double) y);
            }
        } else {
            dragging = false;
        }
    }

    private static boolean isHovered(float mouseX, float mouseY, float bx, float by, float bw, float bh) {
        return mouseX >= bx && mouseY >= by && mouseX < bx + bw && mouseY < by + bh;
    }

    private void drawDragOutline(GuiGraphicsExtractor extractor) {
        int color = withAlpha(themeAccent1(), 0.8f);
        float t = 1.0f;
        RenderUtil.flatRect(extractor, x - 0.5f, y - 0.5f, currentWidth + 1f, t, color);
        RenderUtil.flatRect(extractor, x - 0.5f, y + currentHeight - 0.5f, currentWidth + 1f, t, color);
        RenderUtil.flatRect(extractor, x - 0.5f, y - 0.5f, t, currentHeight + 1f, color);
        RenderUtil.flatRect(extractor, x + currentWidth - 0.5f, y - 0.5f, t, currentHeight + 1f, color);
    }

    private static int withAlpha(int argb, float alphaMul) {
        int a = Math.max(0, Math.min(255, (int) (((argb >>> 24) & 0xFF) * alphaMul)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private static int themeAccent1() {
        return cc.aerial.client.features.impl.visual.InterfaceModule.INSTANCE.getTheme().getAccentColor(0, 0).getRGB() | 0xFF000000;
    }
}
