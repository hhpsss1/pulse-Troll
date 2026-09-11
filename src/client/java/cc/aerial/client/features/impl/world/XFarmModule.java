package cc.aerial.client.features.impl.world;

import cc.aerial.client.event.impl.render.Render2DEvent;
import cc.aerial.client.event.subscriber.Subscribe;
import cc.aerial.client.features.Module;
import cc.aerial.client.features.ModuleCategory;
import cc.aerial.client.property.BooleanProperty;
import cc.aerial.client.property.GroupProperty;
import cc.aerial.client.property.NumberProperty;
import cc.aerial.client.render.CameraRenderStateHelper;
import cc.aerial.client.render.ESPUtility;
import cc.aerial.client.render.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class XFarmModule extends Module {
    public static final XFarmModule INSTANCE = new XFarmModule();

    private final XFarmSettings settings = new XFarmSettings();
    private final List<BlockPos> highlightedBlocks = new ArrayList<>();

    private XFarmModule() {
        super("XFarm", "Highlights valuable ores nearby", ModuleCategory.WORLD);
        addProperties(settings.getProperties());
    }

    @Subscribe
    public void onRender2D(Render2DEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Level level = mc.level;
        
        if (player == null || level == null) {
            return;
        }

        CameraRenderState camera = CameraRenderStateHelper.get();
        if (camera == null) {
            return;
        }

        highlightedBlocks.clear();
        
        int radius = settings.getRadius();
        BlockPos playerPos = player.blockPosition();
        
        // Scan blocks in radius
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    
                    if (shouldHighlight(state)) {
                        highlightedBlocks.add(pos);
                    }
                }
            }
        }
        
        // Render highlights
        for (BlockPos pos : highlightedBlocks) {
            renderBlockHighlight(pos, event.extractor(), camera, event.width(), event.height());
        }
    }

    private boolean shouldHighlight(BlockState state) {
        if (state.isAir()) {
            return false;
        }

        if (settings.isCoalEnabled() && state.is(Blocks.COAL_ORE)) {
            return true;
        }
        if (settings.isIronEnabled() && state.is(Blocks.IRON_ORE)) {
            return true;
        }
        if (settings.isGoldEnabled() && state.is(Blocks.GOLD_ORE)) {
            return true;
        }
        if (settings.isDiamondEnabled() && state.is(Blocks.DIAMOND_ORE)) {
            return true;
        }
        if (settings.isLapisEnabled() && state.is(Blocks.LAPIS_ORE)) {
            return true;
        }
        if (settings.isRedstoneEnabled() && state.is(Blocks.REDSTONE_ORE)) {
            return true;
        }
        if (settings.isEmeraldEnabled() && state.is(Blocks.EMERALD_ORE)) {
            return true;
        }
        if (settings.isCopperEnabled() && state.is(Blocks.COPPER_ORE)) {
            return true;
        }
        if (settings.isAncientDebrisEnabled() && state.is(Blocks.ANCIENT_DEBRIS)) {
            return true;
        }
        
        return false;
    }

    private void renderBlockHighlight(BlockPos pos, GuiGraphicsExtractor extractor, CameraRenderState camera, int screenWidth, int screenHeight) {
        AABB bb = new AABB(pos);
        ESPUtility.ScreenBox box = ESPUtility.project(bb, camera, screenWidth, screenHeight);
        
        if (box == null) {
            return;
        }

        float red = settings.getRed();
        float green = settings.getGreen();
        float blue = settings.getBlue();
        float alpha = settings.getAlpha();
        
        int color = ((int)(alpha * 255) << 24) | ((int)(red * 255) << 16) | ((int)(green * 255) << 8) | (int)(blue * 255);
        
        drawBoxFrame(extractor, box, 1.5f, color);
    }

    private void drawBoxFrame(GuiGraphicsExtractor extractor, ESPUtility.ScreenBox box, float lineThickness, int color) {
        float x0 = box.x();
        float y0 = box.y();
        float x1 = box.x() + box.width();
        float y1 = box.y() + box.height();
        float t = lineThickness;

        RenderUtil.sharpRect(extractor, x0, y0, x1, y0 + t, color);
        RenderUtil.sharpRect(extractor, x0, y1 - t, x1, y1, color);
        RenderUtil.sharpRect(extractor, x0, y0, x0 + t, y1, color);
        RenderUtil.sharpRect(extractor, x1 - t, y0, x1, y1, color);
    }

    public XFarmSettings getSettings() {
        return settings;
    }
}
