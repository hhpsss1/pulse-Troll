package cc.aerial.client.features.impl.movement;

import cc.aerial.client.event.impl.game.input.MoveInputEvent;
import cc.aerial.client.event.subscriber.Subscribe;
import cc.aerial.client.features.Module;
import cc.aerial.client.features.ModuleCategory;
import cc.aerial.client.features.impl.combat.killaura.KillauraModule;
import cc.aerial.client.features.impl.combat.killaura.target.CurrentTarget;
import cc.aerial.client.features.impl.visual.FreeLookModule;
import cc.aerial.client.property.ModeProperty;
import cc.aerial.client.rotation.RotationHelper;
import cc.aerial.client.rotation.ServerRotation;
import cc.aerial.client.rotation.handler.RotationMouseHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps movement consistent with the rotation the server sees.
 *
 * <p><b>Normal</b> remaps WASD so the player keeps moving in the direction shown on screen even while a
 * silent rotation points the server-side yaw elsewhere.</p>
 *
 * <p><b>Focus</b> is the Kill Aura correction: while Kill Aura owns the rotation and has a target, WASD is
 * interpreted relative to the target instead of the camera. W walks straight at the target, S backs
 * away, A and D circle it. Without a target it behaves exactly like Normal.</p>
 */
public final class MovementFixModule extends Module {
    public static final MovementFixModule INSTANCE = new MovementFixModule();

    private static final double MIN_FOCUS_DISTANCE = 0.1;

    private final ModeProperty<Mode> mode = new ModeProperty<>("Mode", Mode.NORMAL);

    private MovementFixModule() {
        super("Movement Fix", "", ModuleCategory.MOVEMENT);
        addProperties(mode);
    }

    /** True while movement is resolved against the server-side yaw (both modes). */
    public boolean isFixMovement() {
        return this.isEnabled();
    }

    public boolean isFocusMode() {
        return this.isEnabled() && this.mode.getValue() == Mode.FOCUS;
    }

    public Mode getMode() {
        return this.mode.getValue();
    }

    @Override
    public String getSuffix() {
        return this.mode.getValue().toString();
    }

    @Subscribe
    public void onMoveInput(MoveInputEvent event) {
        if (this.mode.getValue() == Mode.FOCUS) {
            applyFocusFix(event);
        } else {
            applyNormalFix(event);
        }
    }

    public static void applyNormalFix(MoveInputEvent event) {
        if (FreeLookModule.INSTANCE.isFreeLooking()) {
            return;
        }
        float forward = event.getForward();
        float strafe = event.getSideways();
        if (forward == 0.0f && strafe == 0.0f) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        float realYaw = ServerRotation.getYawOr(mc.player.getYRot());
        float cameraYaw = RotationHelper.getScreenYaw(mc.player.getYRot());
        float intendedAngle = (float) Math.toDegrees(direction(cameraYaw, forward, strafe));

        applyClosestInput(event, realYaw, intendedAngle);
    }

    /**
     * Target-relative correction. Falls back to {@link #applyNormalFix} whenever Kill Aura is not
     * actively rotating at something.
     */
    public static void applyFocusFix(MoveInputEvent event) {
        if (FreeLookModule.INSTANCE.isFreeLooking()) {
            return;
        }
        float forward = event.getForward();
        float strafe = event.getSideways();
        if (forward == 0.0f && strafe == 0.0f) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        LivingEntity target = focusTarget();
        if (target == null) {
            applyNormalFix(event);
            return;
        }

        Vec3 playerPos = player.position();
        Vec3 targetPos = target.position();
        double dx = targetPos.x - playerPos.x;
        double dz = targetPos.z - playerPos.z;
        if (dx * dx + dz * dz < MIN_FOCUS_DISTANCE * MIN_FOCUS_DISTANCE) {
            applyNormalFix(event);
            return;
        }

        // Yaw that looks straight at the target (Minecraft convention: yaw = atan2(-dx, dz)).
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float intendedAngle = (float) Math.toDegrees(direction(targetYaw, forward, strafe));

        float realYaw = ServerRotation.getYawOr(player.getYRot());
        applyClosestInput(event, realYaw, intendedAngle);
    }

    /** The entity Kill Aura is rotating at, or null if Kill Aura is not in control of the rotation. */
    private static LivingEntity focusTarget() {
        KillauraModule killaura = KillauraModule.INSTANCE;
        if (!killaura.isEnabled() || killaura.getSettings().isNoRotation()) {
            return null;
        }
        RotationMouseHandler handler = RotationHelper.getHandler();
        if (!handler.isActive() || handler.getOwner() != killaura) {
            return null;
        }
        CurrentTarget current = killaura.getTargeting().getRotationTarget();
        if (current == null) {
            current = killaura.getTargeting().getTarget();
        }
        if (current == null) {
            return null;
        }
        LivingEntity entity = current.getEntity();
        return entity != null && entity.isAlive() ? entity : null;
    }

    /**
     * Picks the WASD combination that, moved relative to {@code realYaw}, lands closest to
     * {@code intendedAngle} (degrees, world space) and writes it into the event.
     */
    private static void applyClosestInput(MoveInputEvent event, float realYaw, float intendedAngle) {
        float closestForward = 0.0f, closestSideways = 0.0f, closestDifference = Float.MAX_VALUE;
        for (float predictedForward = -1.0f; predictedForward <= 1.0f; predictedForward += 1.0f) {
            for (float predictedStrafe = -1.0f; predictedStrafe <= 1.0f; predictedStrafe += 1.0f) {
                if (predictedStrafe == 0.0f && predictedForward == 0.0f) {
                    continue;
                }
                float predictedAngle = (float) Math.toDegrees(direction(realYaw, predictedForward, predictedStrafe));
                float difference = Mth.degreesDifferenceAbs(intendedAngle, predictedAngle);
                if (difference < closestDifference) {
                    closestDifference = difference;
                    closestForward = predictedForward;
                    closestSideways = predictedStrafe;
                }
            }
        }

        event.setForward(closestForward);
        event.setSideways(closestSideways);
    }

    private static double direction(float rotationYaw, double moveForward, double moveStrafing) {
        if (moveForward < 0.0) {
            rotationYaw += 180.0f;
        }
        float forward = 1.0f;
        if (moveForward < 0.0) {
            forward = -0.5f;
        } else if (moveForward > 0.0) {
            forward = 0.5f;
        }
        if (moveStrafing > 0.0) {
            rotationYaw -= 90.0f * forward;
        } else if (moveStrafing < 0.0) {
            rotationYaw += 90.0f * forward;
        }
        return Math.toRadians(rotationYaw);
    }

    public enum Mode {
        NORMAL("Normal"),
        FOCUS("Focus");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
