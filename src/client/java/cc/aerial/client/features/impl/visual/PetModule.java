package cc.aerial.client.features.impl.visual;

import cc.aerial.client.event.impl.game.JoinWorldEvent;
import cc.aerial.client.event.impl.game.server.ServerDisconnectEvent;
import cc.aerial.client.event.impl.render.Render3DEvent;
import cc.aerial.client.event.subscriber.Subscribe;
import cc.aerial.client.features.Module;
import cc.aerial.client.features.ModuleCategory;
import cc.aerial.client.features.impl.visual.pet.PetRenderer;
import cc.aerial.client.property.ModeProperty;
import cc.aerial.client.property.NumberProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

public final class PetModule extends Module {
    public static final PetModule INSTANCE = new PetModule();

    public enum PetType {
        CAKE_PET("Cake Pet", 126),
        // Add more pet IDs from the cosmetics folder as needed
        ;

        private final String label;
        private final int petId;

        PetType(String label, int petId) {
            this.label = label;
            this.petId = petId;
        }

        @Override
        public String toString() {
            return label;
        }

        public int getPetId() {
            return petId;
        }
    }

    private final ModeProperty<PetType> petType = new ModeProperty<>("Pet", PetType.CAKE_PET);
    private final NumberProperty scale = new NumberProperty("Scale", 0.7, 0.5, 2.0, 0.1);
    private final NumberProperty offsetX = new NumberProperty("Offset X", 0.85, -2.0, 2.0, 0.05);
    private final NumberProperty offsetY = new NumberProperty("Offset Y", 0.25, -2.0, 2.0, 0.05);
    private final NumberProperty offsetZ = new NumberProperty("Offset Z", 0.0, -2.0, 2.0, 0.05);

    private Vec3 petPosition;
    private PetRenderer renderer;

    private PetModule() {
        super("Pet", "Client-side cosmetic pet", ModuleCategory.VISUAL);
        addProperties(petType, scale, offsetX, offsetY, offsetZ);
    }

    @Override
    protected void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            petPosition = mc.player.position();
        }
        
        // Initialize renderer
        renderer = new PetRenderer();
        renderer.loadPet(petType.getValue().getPetId());
    }

    @Override
    protected void onDisable() {
        petPosition = null;
        if (renderer != null) {
            renderer.cleanup();
            renderer = null;
        }
    }

    @Subscribe
    public void onJoinWorld(JoinWorldEvent event) {
        if (isEnabled()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                petPosition = mc.player.position();
            }
            
            // Reload pet on world join
            if (renderer != null) {
                renderer.cleanup();
                renderer.loadPet(petType.getValue().getPetId());
            }
        }
    }

    @Subscribe
    public void onServerDisconnect(ServerDisconnectEvent event) {
        petPosition = null;
        if (renderer != null) {
            renderer.cleanup();
            renderer = null;
        }
    }

    @Subscribe
    public void onRender3D(Render3DEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || renderer == null) {
            return;
        }

        Vec3 playerPos = mc.player.position();
        double offsetXVal = offsetX.getValue();
        double offsetYVal = offsetY.getValue();
        double offsetZVal = offsetZ.getValue();

        // Calculate target position relative to player
        Vec3 targetPos = playerPos.add(
            offsetXVal,
            offsetYVal,
            offsetZVal
        );

        // Initialize pet position if null
        if (petPosition == null) {
            petPosition = targetPos;
        }

        // Smooth movement towards target
        petPosition = petPosition.lerp(targetPos, 0.15);

        // Render pet using the renderer
        float scaleVal = scale.getValue().floatValue();
        renderer.render(event, petPosition, scaleVal);
    }

    @Override
    public String getSuffix() {
        return petType.getValue().toString();
    }

    public Identifier getPetTexture() {
        int petId = petType.getValue().getPetId();
        return Identifier.fromNamespaceAndPath("dev", "cosmetics/pulse/pet/" + petId + "/texture.png");
    }
}
