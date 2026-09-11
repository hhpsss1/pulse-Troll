package cc.aerial.client.features.impl.hud;

import cc.aerial.client.event.impl.render.Render2DEvent;
import cc.aerial.client.features.impl.visual.InterfaceModule;
import cc.aerial.client.render.AerialBloomFilter;
import cc.aerial.client.render.AerialBlur;
import cc.aerial.client.render.AerialImage;
import cc.aerial.client.render.BlurConsumer;
import cc.aerial.client.render.RenderUtil;
import cc.aerial.client.render.TextRenderUtil;
import cc.aerial.client.render.font.AerialFont;
import cc.aerial.client.theme.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class CenteredWatermark {
    public static final CenteredWatermark INSTANCE = new CenteredWatermark();

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private static final int BACKGROUND_COLOR = 0x80090909;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int MUTED_COLOR = 0xFF808080;

    private static final float TOP_MARGIN = 6.0f;

    private static final float TEXT_FONT_SIZE = 5.6f;
    private static final float ICON_FONT_SIZE = 7.0f;
    private static final float LOGO_SIZE = 11.5f;
    private static final float PILL_HEIGHT = 16.0f;
    private static final float PILL_RADIUS = 5.2f;
    private static final float PAD_X = 7.0f;
    private static final float ITEM_GAP = 6.5f;
    private static final float ICON_TEXT_GAP = 3.5f;
    private static final float TEXT_OFFSET_Y = 5.0f;
    private static final float ICON_OFFSET_Y = 4.5f;
    private static final float SEP_OFFSET_Y = 4.0f;
    private static final float SEP_HEIGHT = 8.0f;
    private static final float SEP_WIDTH = 1.0f;

    private static final String CLIENT_NAME = "Aerial";

    private static AerialFont font;
    private static AerialFont regularFont;
    private static AerialFont iconFont;
    private static AerialImage logo;

    private static void ensureAssetsLoaded() {
        if (font == null) {
            font = AerialFont.createFromResource("OpalProductSansMedium.ttf");
            regularFont = AerialFont.createFromResource("OpalProductSansMedium.ttf");
            iconFont = AerialFont.createIconFromResource("OpalMaterialIconsRegular.ttf",
                    (char) 0xE7FD, (char) 0xE84E, (char) 0xE192);
            logo = AerialImage.fromResource("aerial_logo.png");
        }
    }

    public void render(Render2DEvent event) {
        AerialBloomFilter.begin(BlurConsumer.CENTERED_WATERMARK);
        try {
            onRender2DBody(event);
        } finally {
            AerialBloomFilter.end();
        }
    }

    private void onRender2DBody(Render2DEvent event) {
        ensureAssetsLoaded();

        Minecraft mc = Minecraft.getInstance();
        GuiGraphicsExtractor extractor = event.extractor();

        String userName = (mc.player != null && mc.player.getName() != null)
                ? mc.player.getName().getString()
                : "Player";

        int fps = mc.getFps();
        String fpsNum = String.valueOf(fps);
        String fpsSuffix = "fps";

        String timeStr = LocalTime.now().format(TIME_FORMAT);

        Theme theme = InterfaceModule.INSTANCE.getTheme();
        int accentCol = theme.getAccentColor(0, 50).getRGB();
        int accentColRight = theme.getAccentColor(0, 0).getRGB();
        int textCol = WHITE;

        float clientW = font.stringWidth(CLIENT_NAME, TEXT_FONT_SIZE);

        float userIconW = iconFont.stringWidth(String.valueOf((char) 0xE7FD), ICON_FONT_SIZE);
        float userW = font.stringWidth(userName, TEXT_FONT_SIZE);

        float fpsIconW = iconFont.stringWidth(String.valueOf((char) 0xE84E), ICON_FONT_SIZE);
        float fpsNumW = font.stringWidth(fpsNum, TEXT_FONT_SIZE);
        float fpsSuffixW = regularFont.stringWidth(fpsSuffix, TEXT_FONT_SIZE);
        float fpsTotalW = fpsNumW + fpsSuffixW;

        float timeIconW = iconFont.stringWidth(String.valueOf((char) 0xE192), ICON_FONT_SIZE);
        float timeW = font.stringWidth(timeStr, TEXT_FONT_SIZE);

        float sec1W = LOGO_SIZE + ICON_TEXT_GAP + clientW;
        float sec2W = userIconW + ICON_TEXT_GAP + userW;
        float sec3W = fpsIconW + ICON_TEXT_GAP + fpsTotalW;
        float sec4W = timeIconW + ICON_TEXT_GAP + timeW;

        float totalW = PAD_X * 2
                + sec1W + ITEM_GAP + SEP_WIDTH + ITEM_GAP
                + sec2W + ITEM_GAP + SEP_WIDTH + ITEM_GAP
                + sec3W + ITEM_GAP + SEP_WIDTH + ITEM_GAP
                + sec4W;

        float screenWidth = mc.getWindow().getGuiScaledWidth();
        float x = (screenWidth - totalW) / 2.0f;
        float y = TOP_MARGIN;

        AerialBlur.drawGlass(extractor, BlurConsumer.CENTERED_WATERMARK, x, y, totalW, PILL_HEIGHT,
                PILL_RADIUS, BACKGROUND_COLOR, 1.0f, null);

        float curX = x + PAD_X;

        float logoY = y + (PILL_HEIGHT - LOGO_SIZE) / 2.0f;
        RenderUtil.image(extractor, logo, curX, logoY, LOGO_SIZE, LOGO_SIZE, accentCol, accentColRight);
        curX += LOGO_SIZE + ICON_TEXT_GAP;
        TextRenderUtil.drawString(extractor, font, CLIENT_NAME, curX, y + TEXT_OFFSET_Y, TEXT_FONT_SIZE, textCol);
        curX += clientW + ITEM_GAP;

        RenderUtil.flatRect(extractor, curX, y + SEP_OFFSET_Y, SEP_WIDTH, SEP_HEIGHT, MUTED_COLOR);
        curX += SEP_WIDTH + ITEM_GAP;

        TextRenderUtil.drawString(extractor, iconFont, String.valueOf((char) 0xE7FD), curX, y + ICON_OFFSET_Y, ICON_FONT_SIZE, accentCol);
        curX += userIconW + ICON_TEXT_GAP;
        TextRenderUtil.drawString(extractor, font, userName, curX, y + TEXT_OFFSET_Y, TEXT_FONT_SIZE, textCol);
        curX += userW + ITEM_GAP;

        RenderUtil.flatRect(extractor, curX, y + SEP_OFFSET_Y, SEP_WIDTH, SEP_HEIGHT, MUTED_COLOR);
        curX += SEP_WIDTH + ITEM_GAP;

        TextRenderUtil.drawString(extractor, iconFont, String.valueOf((char) 0xE84E), curX, y + ICON_OFFSET_Y, ICON_FONT_SIZE, accentCol);
        curX += fpsIconW + ICON_TEXT_GAP;
        TextRenderUtil.drawString(extractor, font, fpsNum, curX, y + TEXT_OFFSET_Y, TEXT_FONT_SIZE, textCol);
        curX += fpsNumW;
        TextRenderUtil.drawString(extractor, regularFont, fpsSuffix, curX, y + TEXT_OFFSET_Y, TEXT_FONT_SIZE, accentCol);
        curX += fpsSuffixW + ITEM_GAP;

        RenderUtil.flatRect(extractor, curX, y + SEP_OFFSET_Y, SEP_WIDTH, SEP_HEIGHT, MUTED_COLOR);
        curX += SEP_WIDTH + ITEM_GAP;

        TextRenderUtil.drawString(extractor, iconFont, String.valueOf((char) 0xE192), curX, y + ICON_OFFSET_Y, ICON_FONT_SIZE, accentCol);
        curX += timeIconW + ICON_TEXT_GAP;
        TextRenderUtil.drawString(extractor, font, timeStr, curX, y + TEXT_OFFSET_Y, TEXT_FONT_SIZE, textCol);
    }
}
