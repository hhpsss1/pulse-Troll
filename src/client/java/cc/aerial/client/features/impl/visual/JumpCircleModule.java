package cc.aerial.client.features.impl.visual;

import cc.aerial.client.event.impl.game.player.movement.JumpEvent;
import cc.aerial.client.event.impl.render.Render3DEvent;
import cc.aerial.client.event.subscriber.Subscribe;
import cc.aerial.client.features.Module;
import cc.aerial.client.features.ModuleCategory;
import cc.aerial.client.render.AerialImage;
import cc.aerial.client.render.Render3DUtility;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

public final class JumpCircleModule extends Module {
    public static final JumpCircleModule INSTANCE = new JumpCircleModule();

    private static final Identifier JUMP_CIRCLE_TEXTURE = Identifier.fromNamespaceAndPath("dev", "textures/effects/jump_circle_wisp.png");

    private long jumpTime = 0;
    private Vec3 jumpPosition;
    private boolean shouldRender = false;
    private AerialImage jumpCircleImage;

    private JumpCircleModule() {
        super("Jump Circle", "Displays a circle effect when you jump.", ModuleCategory.VISUAL);
        setEnabled(true);
    }

    @Override
    protected void onEnable() {
        loadJumpCircleImage();
    }

    @Override
    protected void onDisable() {
        shouldRender = false;
        jumpPosition = null;
        if (jumpCircleImage != null) {
            jumpCircleImage.close();
            jumpCircleImage = null;
        }
    }

    private void loadJumpCircleImage() {
        try {
            String path = "/assets/dev/textures/effects/jump_circle_wisp.png";
            InputStream in = JumpCircleModule.class.getResourceAsStream(path);
            if (in == null) {
                System.err.println("Failed to find jump circle image at: " + path);
                return;
            }
            BufferedImage image = ImageIO.read(in);
            jumpCircleImage = AerialImage.fromImage(image);
            in.close();
        } catch (IOException e) {
            System.err.println("Failed to load jump circle image: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Subscribe
    public void onJump(JumpEvent event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        jumpTime = System.currentTimeMillis();
        jumpPosition = player.position();
        shouldRender = true;
    }

    @Subscribe
    public void onRender3D(Render3DEvent event) {
        if (!shouldRender || jumpPosition == null || jumpCircleImage == null) {
            return;
        }

        long elapsed = System.currentTimeMillis() - jumpTime;
        if (elapsed > 500) {
            shouldRender = false;
            return;
        }

        float alpha = 1.0f - (elapsed / 500.0f);
        if (alpha <= 0) {
            shouldRender = false;
            return;
        }

        float size = 2.0f + (elapsed / 500.0f) * 1.0f;
        int color = ((int) (alpha * 255) << 24) | 0xFFFFFF;
        
        // Use glow sprite for the effect
        Render3DUtility.glowSprite(event, jumpPosition, size, color, true);
    }
}
