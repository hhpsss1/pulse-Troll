package cc.aerial.client.features.impl.movement;

import cc.aerial.client.event.impl.game.PostGameTickEvent;
import cc.aerial.client.event.subscriber.Subscribe;
import cc.aerial.client.features.Module;
import cc.aerial.client.features.ModuleCategory;
import cc.aerial.client.property.BooleanProperty;
import cc.aerial.client.property.MultipleBooleanProperty;
import cc.aerial.client.utility.KeyMappingUtility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

public final class NoPushModule extends Module {
    public static final NoPushModule INSTANCE = new NoPushModule();

    private final BooleanProperty entities = new BooleanProperty("Entities", true);
    private final BooleanProperty blocks = new BooleanProperty("Blocks", false);
    private final BooleanProperty fishingRod = new BooleanProperty("FishingRod", false);
    private final BooleanProperty liquids = new BooleanProperty("Liquids", true);
    private final BooleanProperty sinking = new BooleanProperty("Sinking", false);

    private final MultipleBooleanProperty noPushBy = new MultipleBooleanProperty("PushBy", 
            entities, blocks, fishingRod, liquids, sinking);

    private NoPushModule() {
        super("NoPush", "Disables pushing from other players and some other situations", ModuleCategory.MOVEMENT);
        addProperties(noPushBy);
    }

    public static boolean canPush(NoPushBy by) {
        NoPushModule module = INSTANCE;
        if (!module.isEnabled()) {
            return true;
        }

        return switch (by) {
            case ENTITIES -> !module.entities.getValue();
            case BLOCKS -> !module.blocks.getValue();
            case FISHING_ROD -> !module.fishingRod.getValue();
            case LIQUIDS -> !module.liquids.getValue();
            case SINKING -> !module.sinking.getValue();
        };
    }

    @Subscribe
    public void onPostGameTick(PostGameTickEvent event) {
        if (!sinking.getValue()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (KeyMappingUtility.isPhysicallyDown(mc.options.keyJump) || KeyMappingUtility.isPhysicallyDown(mc.options.keyShift)) {
            return;
        }

        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        if (player.isInWater() || player.isInLava()) {
            if (player.getDeltaMovement().y < 0) {
                player.setDeltaMovement(player.getDeltaMovement().x, 0.0, player.getDeltaMovement().z);
            }
        }
    }

    public enum NoPushBy {
        ENTITIES,
        BLOCKS,
        FISHING_ROD,
        LIQUIDS,
        SINKING
    }
}
