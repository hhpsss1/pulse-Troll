package cc.aerial.client.features.impl.movement;

import cc.aerial.client.event.impl.game.input.MoveInputEvent;
import cc.aerial.client.event.impl.game.player.movement.PreMoveEvent;
import cc.aerial.client.event.subscriber.Subscribe;
import cc.aerial.client.features.Module;
import cc.aerial.client.features.ModuleCategory;
import cc.aerial.client.features.impl.combat.killaura.KillauraModule;
import cc.aerial.client.mixin.LivingEntityAccessor;
import cc.aerial.client.property.BooleanProperty;
import cc.aerial.client.property.ModeProperty;
import cc.aerial.client.property.NumberProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public final class TargetStrafeModule extends Module {
    public static final TargetStrafeModule INSTANCE = new TargetStrafeModule();

    private final ModeProperty<Mode> mode = new ModeProperty<>("Mode", Mode.GRIM);
    
    private final ModeProperty<GrimPoint> grimPoint = new ModeProperty<>("Grim Point", GrimPoint.CUBE);
    private final ModeProperty<MatrixPoint> matrixPoint = new ModeProperty<>("Matrix Point", MatrixPoint.CIRCLE);
    
    private final ModeProperty<Direction> direction = new ModeProperty<>("Direction", Direction.CLOCKWISE);
    
    private final BooleanProperty autoJump = new BooleanProperty("Auto Jump", true);
    private final BooleanProperty onlyKeyPressed = new BooleanProperty("Only Key Pressed", false);
    private final BooleanProperty inFrontOfTarget = new BooleanProperty("In Front Of Target", false);
    
    private final NumberProperty grimRadius = new NumberProperty("Grim Radius", 0.87, 0.1, 1.5, 0.01);
    private final NumberProperty matrixRadius = new NumberProperty("Matrix Radius", 2.5, 0.1, 7.0, 0.01);
    private final NumberProperty matrixSpeed = new NumberProperty("Matrix Speed", 0.3, 0.1, 1.0, 0.01);

    private int grimPointIndex = 0;

    private TargetStrafeModule() {
        super("Target Strafe", "Strafe around killaura target", ModuleCategory.MOVEMENT);
        addProperties(mode, grimPoint, matrixPoint, direction, autoJump, onlyKeyPressed, 
                      inFrontOfTarget, grimRadius, matrixRadius, matrixSpeed);
    }

    @Override
    protected void onEnable() {
        grimPointIndex = 0;
    }

    @Subscribe
    public void onMoveInput(MoveInputEvent event) {
        if (mode.getValue() != Mode.GRIM) return;
        
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        LivingEntity target = getTarget();
        if (target == null || !target.isAlive()) return;

        if (onlyKeyPressed.getValue() && !isMovementKeyPressed()) return;

        Vec3 playerPos = mc.player.position();
        Vec3 targetPos = target.position();
        double r = grimRadius.getValue();
        int dir = getDirectionMultiplier();

        Vec3 nextPoint;

        if (inFrontOfTarget.getValue()) {
            float targetYaw = target.getYRot();
            if (grimPoint.getValue() == GrimPoint.CENTER) {
                nextPoint = targetPos.add(
                        -Mth.sin(Mth.DEG_TO_RAD * targetYaw) * r * dir, 0,
                        Mth.cos(Mth.DEG_TO_RAD * targetYaw) * r * dir);
            } else {
                double offset = Math.cos(System.currentTimeMillis() / 500.0) * r * dir;
                nextPoint = targetPos.add(
                        -Mth.sin(Mth.DEG_TO_RAD * targetYaw) * r + Mth.cos(Mth.DEG_TO_RAD * targetYaw) * offset, 0,
                        Mth.cos(Mth.DEG_TO_RAD * targetYaw) * r + Mth.sin(Mth.DEG_TO_RAD * targetYaw) * offset);
            }
        } else if (grimPoint.getValue() == GrimPoint.CUBE) {
            Vec3[] points = getCubePoints(targetPos, playerPos, r);
            if (playerPos.distanceTo(points[grimPointIndex]) < 0.5)
                grimPointIndex = (grimPointIndex + dir + points.length) % points.length;
            nextPoint = points[grimPointIndex];
        } else if (grimPoint.getValue() == GrimPoint.CIRCLE) {
            double baseAngle = (System.currentTimeMillis() % 3600L) / 3600.0 * 4 * Math.PI;
            double angle = dir > 0 ? baseAngle : (2 * Math.PI - baseAngle);
            nextPoint = new Vec3(
                    targetPos.x + Math.cos(angle) * r,
                    playerPos.y,
                    targetPos.z + Math.sin(angle) * r);
        } else {
            nextPoint = new Vec3(targetPos.x, playerPos.y, targetPos.z);
        }

        Vec3 directionVec = nextPoint.subtract(playerPos).normalize();
        float movementAngle = (float) Math.toDegrees(Math.atan2(directionVec.z, directionVec.x)) - 90f;
        float angleDiff = Mth.wrapDegrees(movementAngle - mc.player.getYRot());

        float fwd = 0, strafe = 0;
        if      (angleDiff >= -22.5f  && angleDiff <  22.5f)  { fwd =  1; }
        else if (angleDiff >=  22.5f  && angleDiff <  67.5f)  { fwd =  1; strafe =  1; }
        else if (angleDiff >=  67.5f  && angleDiff < 112.5f)  { strafe =  1; }
        else if (angleDiff >= 112.5f  && angleDiff < 157.5f)  { fwd = -1; strafe =  1; }
        else if (angleDiff >= -67.5f  && angleDiff < -22.5f)  { fwd =  1; strafe = -1; }
        else if (angleDiff >= -112.5f && angleDiff < -67.5f)  { strafe = -1; }
        else if (angleDiff >= -157.5f && angleDiff < -112.5f) { fwd = -1; strafe = -1; }
        else                                                    { fwd = -1; }

        event.setForward(fwd);
        event.setSideways(strafe);

        if (autoJump.getValue() && mc.player.onGround())
            ((LivingEntityAccessor) mc.player).aerial$jumpFromGround();
    }

    @Subscribe
    public void onPreMove(PreMoveEvent event) {
        if (mode.getValue() != Mode.MATRIX) return;
        
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        LivingEntity target = getTarget();
        if (target == null || !target.isAlive()) return;

        if (onlyKeyPressed.getValue() && !isMovementKeyPressed()) return;

        if (autoJump.getValue() && mc.player.onGround())
            ((LivingEntityAccessor) mc.player).aerial$jumpFromGround();

        Vec3 playerPos = mc.player.position();
        Vec3 targetPos = target.position();
        double r = matrixRadius.getValue();
        int dir = getDirectionMultiplier();
        double motionSpeed = matrixSpeed.getValue();

        if (inFrontOfTarget.getValue()) {
            float targetYaw = target.getYRot();
            double x = targetPos.x - Mth.sin(Mth.DEG_TO_RAD * targetYaw) * r * dir;
            double z = targetPos.z + Mth.cos(Mth.DEG_TO_RAD * targetYaw) * r * dir;
            float yaw = (float) Math.toDegrees(Math.atan2(z - playerPos.z, x - playerPos.x)) - 90f;
            mc.player.setDeltaMovement(
                    -Mth.sin(Mth.DEG_TO_RAD * yaw) * motionSpeed,
                    mc.player.getDeltaMovement().y,
                    Mth.cos(Mth.DEG_TO_RAD * yaw) * motionSpeed);
            return;
        }

        if (matrixPoint.getValue() == MatrixPoint.CUBE) {
            Vec3[] points = getCubePoints(targetPos, playerPos, r);
            if (playerPos.distanceTo(points[grimPointIndex]) < 0.5)
                grimPointIndex = (grimPointIndex + dir + points.length) % points.length;
            Vec3 next = points[grimPointIndex];
            Vec3 dirVec = next.subtract(playerPos).normalize();
            float yaw = (float) Math.toDegrees(Math.atan2(dirVec.z, dirVec.x)) - 90f;
            mc.player.setDeltaMovement(
                    -Mth.sin(Mth.DEG_TO_RAD * yaw) * motionSpeed,
                    mc.player.getDeltaMovement().y,
                    Mth.cos(Mth.DEG_TO_RAD * yaw) * motionSpeed);

        } else {
            double angle = Math.atan2(playerPos.z - targetPos.z, playerPos.x - targetPos.x);
            angle += dir * motionSpeed / Math.max(playerPos.distanceTo(targetPos), r);
            double x = targetPos.x + r * Math.cos(angle);
            double z = targetPos.z + r * Math.sin(angle);
            float yaw = (float) Math.toDegrees(Math.atan2(z - playerPos.z, x - playerPos.x)) - 90f;
            mc.player.setDeltaMovement(
                    -Mth.sin(Mth.DEG_TO_RAD * yaw) * motionSpeed,
                    mc.player.getDeltaMovement().y,
                    Mth.cos(Mth.DEG_TO_RAD * yaw) * motionSpeed);
        }
    }

    public LivingEntity getTarget() {
        var target = KillauraModule.INSTANCE.getTargeting().getTarget();
        return target != null ? target.getEntity() : null;
    }

    private int getDirectionMultiplier() {
        if (direction.getValue() == Direction.COUNTERCLOCKWISE) return -1;
        if (direction.getValue() == Direction.RANDOM) return (System.currentTimeMillis() / 3000) % 2 == 0 ? 1 : -1;
        return 1;
    }

    private boolean isMovementKeyPressed() {
        Minecraft mc = Minecraft.getInstance();
        return mc.options.keyUp.isDown() || mc.options.keyDown.isDown() ||
               mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
    }

    private Vec3[] getCubePoints(Vec3 targetPos, Vec3 playerPos, double r) {
        return new Vec3[]{
                new Vec3(targetPos.x - r, playerPos.y, targetPos.z - r),
                new Vec3(targetPos.x - r, playerPos.y, targetPos.z + r),
                new Vec3(targetPos.x + r, playerPos.y, targetPos.z + r),
                new Vec3(targetPos.x + r, playerPos.y, targetPos.z - r)
        };
    }

    public double getCurrentRadius() {
        return mode.getValue() == Mode.GRIM ? grimRadius.getValue() : matrixRadius.getValue();
    }

    public enum Mode {
        GRIM("Grim"),
        MATRIX("Matrix");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum GrimPoint {
        CUBE("Cube"),
        CENTER("Center"),
        CIRCLE("Circle");

        private final String label;

        GrimPoint(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum MatrixPoint {
        CIRCLE("Circle"),
        CUBE("Cube");

        private final String label;

        MatrixPoint(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Direction {
        CLOCKWISE("Clockwise"),
        COUNTERCLOCKWISE("Counterclockwise"),
        RANDOM("Random");

        private final String label;

        Direction(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
