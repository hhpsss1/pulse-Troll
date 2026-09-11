package cc.aerial.client.features.impl.visual;

import cc.aerial.client.features.Module;
import cc.aerial.client.features.ModuleCategory;
import cc.aerial.client.property.BooleanProperty;
import cc.aerial.client.property.NumberProperty;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

public final class SmoothCameraModule extends Module {
    public static final SmoothCameraModule INSTANCE = new SmoothCameraModule();

    private final BooleanProperty enableFirstPOV = new BooleanProperty("Enable First Person", false);
    private final BooleanProperty resetOnPerspectiveChange = new BooleanProperty("Reset On Perspective Change", true);
    private final NumberProperty factorH = new NumberProperty("Horizontal Factor", 0.9, 0.0, 1.0, 0.01);
    private final NumberProperty factorV = new NumberProperty("Vertical Factor", 0.93, 0.0, 1.0, 0.01);

    private Vec3 smoothPos = Vec3.ZERO;
    private CameraType lastPerspective;

    private SmoothCameraModule() {
        super("Smooth Camera", "Makes your camera move smoother", ModuleCategory.VISUAL);
        addProperties(enableFirstPOV, resetOnPerspectiveChange, factorH, factorV);
    }

    @Override
    protected void onDisable() {
        smoothPos = Vec3.ZERO;
        lastPerspective = null;
    }

    public Vec3 cameraUpdate(Vec3 pos) {
        if (!isEnabled()) {
            lastPerspective = getPerspective();
            return pos;
        }

        CameraType perspective = getPerspective();
        
        // This provides better responsiveness when switching perspectives
        if (resetOnPerspectiveChange.getValue() && lastPerspective != null && lastPerspective != perspective) {
            smoothPos = pos;
            lastPerspective = perspective;
            return pos;
        }
        
        lastPerspective = perspective;
        
        // Don't smooth for first person since it looks weird
        if (!enableFirstPOV.getValue() && perspective == CameraType.FIRST_PERSON) {
            smoothPos = pos;
            return pos;
        }

        if (isLikelyZero(smoothPos)) {
            smoothPos = pos;
        }

        double factorHValue = factorH.getValue().doubleValue();
        double factorVValue = factorV.getValue().doubleValue();

        smoothPos = new Vec3(
            smoothPos.x * factorHValue + pos.x * (1 - factorHValue),
            smoothPos.y * factorVValue + pos.y * (1 - factorVValue),
            smoothPos.z * factorHValue + pos.z * (1 - factorHValue)
        );

        return smoothPos;
    }

    public boolean shouldApplyChanges() {
        return isEnabled();
    }

    private CameraType getPerspective() {
        return Minecraft.getInstance().options.getCameraType();
    }

    private boolean isLikelyZero(Vec3 vec) {
        return vec.x == 0.0 && vec.y == 0.0 && vec.z == 0.0;
    }
}
