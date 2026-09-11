package cc.aerial.client.features.impl.combat.killaura;

import cc.aerial.client.event.EventDispatcher;
import cc.aerial.client.event.impl.game.PreGameTickEvent;
import cc.aerial.client.event.impl.game.input.MouseHandleInputEvent;
import cc.aerial.client.mouse.MouseHelper;
import cc.aerial.client.event.impl.game.player.movement.PostMovementPacketEvent;
import cc.aerial.client.event.impl.game.player.movement.PreMovementPacketEvent;
import cc.aerial.client.event.subscriber.Subscribe;
import cc.aerial.client.features.Module;
import cc.aerial.client.features.impl.world.BreakerModule;
import cc.aerial.client.features.ModuleCategory;
import cc.aerial.client.features.impl.combat.AutoBlockModule;
import cc.aerial.client.features.impl.combat.HitSelectModule;
import cc.aerial.client.features.impl.combat.KeepSprintModule;
import cc.aerial.client.features.impl.combat.PiercingModule;
import cc.aerial.client.features.impl.combat.killaura.target.CurrentTarget;
import cc.aerial.client.rotation.RaycastUtility;
import cc.aerial.client.rotation.RiseAimPoint;
import cc.aerial.client.rotation.RotationAimTarget;
import cc.aerial.client.rotation.model.impl.LegitNormalRotationModel;
import cc.aerial.client.rotation.RotationHelper;
import cc.aerial.client.rotation.RotationUtility;
import cc.aerial.client.rotation.model.impl.InstantRotationModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;

public final class KillauraModule extends Module {
    public static final KillauraModule INSTANCE = new KillauraModule();

    private static final double WILL_LAND_TOLERANCE = 0.15;

    private final KillauraSettings settings = new KillauraSettings();
    private final KillauraTargeting targeting = new KillauraTargeting(this.settings);

    private boolean delayAttackForSprintReset = false;
    private int tripleCritAttackCount = 0;
    private boolean tripleCritActive = false;
    private boolean hvhHasTurned = false;

    private KillauraModule() {
        super("Kill Aura", "", ModuleCategory.COMBAT);
        addProperties(settings.getProperties());
    }

    @Override
    protected void onDisable() {
        this.targeting.reset();
        this.hitResult = null;
        this.delayAttackForSprintReset = false;
        this.tripleCritAttackCount = 0;
        this.tripleCritActive = false;
        this.hvhHasTurned = false;
    }

    @Override
    public String getSuffix() {
        return settings.getMode().toString();
    }

    public KillauraSettings getSettings() {
        return settings;
    }

    public boolean hasTarget() {
        return isEnabled() && this.targeting.hasTargetsInRange();
    }

    public KillauraTargeting getTargeting() {
        return targeting;
    }

    public boolean canAttack(LocalPlayer player) {
        if (!settings.isOnlyCrits()) {
            return true;
        }

        // Triple Crit: allow 3 attacks in a row during crit window
        if (settings.isTripleCrit()) {
            boolean isCritWindow = !player.onGround() && player.fallDistance > 0.0f;
            if (isCritWindow && tripleCritAttackCount < 3) {
                tripleCritActive = true;
                return true;
            }
            if (!isCritWindow && tripleCritActive) {
                tripleCritActive = false;
                tripleCritAttackCount = 0;
            }
        }

        // Exceptions where crit is not required
        if (player.isInWater() || player.isInLava()) {
            return true;
        }
        if (player.isPassenger() || player.getAbilities().flying || player.isFallFlying()) {
            return true;
        }

        // Smart Crits: allow attack if on ground (moving or standing)
        if (settings.isSmartCrits() && player.onGround()) {
            return true;
        }

        // Crit condition: in air and falling
        boolean isCritWindow = !player.onGround() && player.fallDistance > 0.0f;
        
        // Sprint Reset logic
        if (isCritWindow && player.isSprinting() && settings.isSprintReset()) {
            if (!delayAttackForSprintReset) {
                delayAttackForSprintReset = true;
                return false; // Delay attack to next tick
            }
            delayAttackForSprintReset = false;
            return true;
        }

        // If Only Crits is enabled but no crit window and no smart crits, block attack
        return isCritWindow;
    }

    @Subscribe
    public void onHandleInput(MouseHandleInputEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }

        if (BreakerModule.INSTANCE.isSuppressingKillauraAttack()) {
            return;
        }

        CurrentTarget target = this.targeting.getTarget();

        if (target == null) {
            if (!this.settings.getCpsProperty().isModernDelay()) {
                double closestDistance = this.targeting.getClosestDistance();

                if (closestDistance <= this.settings.getSwingRange()
                        && !this.settings.shouldCancelMissedHit()
                        && !HitSelectModule.INSTANCE.shouldCancelMissedSwing()
                        && this.settings.getSwingCpsProperty().canClick()) {
                    player.swing(InteractionHand.MAIN_HAND, false);
                }
            }
            return;
        }

        boolean allowSwingWhileUsing = AutoBlockModule.INSTANCE.isEnabled() && AutoBlockModule.INSTANCE.isSwingAllowed();
        if (player.isUsingItem() && settings.isDisableWhileBlocking() && !allowSwingWhileUsing) {
            return;
        }

        if (this.settings.isOverrideRaycast()) {
            if (this.settings.isTickLookahead() && (this.hitResult == null || this.hitResult.getEntity() != target.getEntity())) {
                return;
            }

            mc.hitResult = target.getRotations().hitResult();
        }

        if (AutoBlockModule.INSTANCE.isSuppressingAttack()) {
            return;
        }

        if (this.settings.isHitSelect() && !hitSelectReady(player, target.getEntity())) {
            return;
        }

        if (HitSelectModule.INSTANCE.shouldBlockAttack(target.getEntity())) {
            return;
        }

        if (AutoBlockModule.INSTANCE.isSuppressingAttack()) {
            return;
        }

        if (this.settings.getCpsProperty().isReady()
                && KeepSprintModule.INSTANCE.deferAttack(target.getEntity(), this.settings.getAttackDistance())) {
            return;
        }

        if (!this.settings.getCpsProperty().canClick()) {
            return;
        }

        // Check if player is inside target's hitbox - if so, allow attack without raycast
        AABB targetBox = target.getEntity().getBoundingBox();
        Vec3 playerEyePos = player.getEyePosition();
        boolean playerInsideHitbox = targetBox.contains(playerEyePos);
        
        if (playerInsideHitbox) {
            // Player is inside hitbox, allow attack directly
            mc.gameMode.attack(player, target.getEntity());
            player.swing(InteractionHand.MAIN_HAND);
            aerialSendBlockInteraction(player, target);
            target.getKillauraTarget().onAttack(true);
            HitSelectModule.INSTANCE.confirmHit(target.getEntity());

            // Triple Crit counter
            if (settings.isTripleCrit() && tripleCritActive) {
                tripleCritAttackCount++;
            }
            return;
        }

        AABB box = target.getEntity().getBoundingBox().inflate(target.getEntity().getPickRadius() + WILL_LAND_TOLERANCE);
        boolean willLand = RaycastUtility.rayTraceHits(box, player.getYRot(), player.getXRot(), this.settings.getAttackDistance());

        if (willLand && !PiercingModule.INSTANCE.isEnabled()
                && RaycastUtility.isWallCloserThan(player.getYRot(), player.getXRot(), this.settings.getAttackDistance(), box.getCenter())) {
            willLand = false;
        }
        if (!willLand) {
            if (!this.settings.shouldCancelMissedHit() && !HitSelectModule.INSTANCE.shouldCancelMissedSwing()) {
                player.swing(InteractionHand.MAIN_HAND);
            }
            return;
        }

        // Check if attack is allowed (Only Crits logic)
        if (!canAttack(player)) {
            return;
        }

        mc.gameMode.attack(player, target.getEntity());
        player.swing(InteractionHand.MAIN_HAND);
        aerialSendBlockInteraction(player, target);
        target.getKillauraTarget().onAttack(true);
        HitSelectModule.INSTANCE.confirmHit(target.getEntity());

        // Triple Crit counter
        if (settings.isTripleCrit() && tripleCritActive) {
            tripleCritAttackCount++;
        }
    }

    private void aerialSendBlockInteraction(LocalPlayer player, CurrentTarget target) {
        if (!AutoBlockModule.INSTANCE.isEnabled() || !AutoBlockModule.INSTANCE.isBlocking()
                || player.isUsingItem()) {
            return;
        }
        Entity entity = target.getEntity();
        if (Minecraft.getInstance().hitResult instanceof EntityHitResult vanillaHit
                && vanillaHit.getEntity() == entity
                && player.isWithinEntityInteractionRange(entity, 0.0)
                && player.distanceToSqr(entity) <= this.settings.getAttackDistance() * this.settings.getAttackDistance()) {
            return;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        Vec3 relative = target.getRotations().hitResult().getLocation()
                .subtract(entity.getX(), entity.getY(), entity.getZ());
        connection.send(new ServerboundInteractPacket(entity.getId(), InteractionHand.MAIN_HAND,
                relative, player.isShiftKeyDown()));
    }

    private EntityHitResult hitResult;

    @Subscribe(priority = 2)
    public void onPreGameTick(PreGameTickEvent event) {
        if (!shouldRun()) {
            this.targeting.reset();
            return;
        }

        this.targeting.update();

        CurrentTarget target = this.targeting.getRotationTarget();
        if (target == null) {
            return;
        }

        if (settings.isNoRotation()) {
            return;
        }

        if (settings.isHvh()) {
            onHvhRotation(target);
            return;
        }

        if (settings.isLegitNormalRotation()) {
            LocalPlayer player = Minecraft.getInstance().player;
            double range = this.settings.getAttackDistance();
            boolean throughWalls = PiercingModule.INSTANCE.isEnabled();

            Vec2 rotation = RiseAimPoint.computeRotations(target.getEntity(), range, throughWalls, 0.0f);
            RotationAimTarget.submit(target.getEntity(), range, throughWalls);
            RotationHelper.getHandler().rotate(rotation,
                    LegitNormalRotationModel.of(settings.getLegitSpeedMin(), settings.getLegitSpeedMax()),
                    this);
            return;
        }

        if (settings.isRegularRotation()) {
            LocalPlayer player = Minecraft.getInstance().player;
            AABB box = target.getEntity().getBoundingBox().inflate(target.getEntity().getPickRadius());
            Vec2 rotation = RotationUtility.getRotationsToBox(box, player.getYRot(), player.getXRot(),
                    settings.getRegularMaxAngle(), settings.getRegularSmoothness());
            RotationHelper.getHandler().rotate(rotation, InstantRotationModel.INSTANCE, this);
            return;
        }

        RotationHelper.getHandler().rotate(
                target.getRotations().rotation(),
                settings.createRotationModel(),
                this
        );
    }

    @Subscribe
    public void onPreMovementPacket(PreMovementPacketEvent event) {
        if (!this.settings.isTickLookahead() || this.targeting.getRotationTarget() == null || !shouldRun()) {
            return;
        }

        this.targeting.update();

        CurrentTarget target = this.targeting.getRotationTarget();
        if (target == null) {
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        event.setYaw(player.getYRot());
        event.setPitch(player.getXRot());
    }

    @Subscribe
    public void onPostMovementPacket(PostMovementPacketEvent event) {
        if (!this.settings.isTickLookahead()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        CurrentTarget target = this.targeting.getTarget();
        Entity targetEntity = target == null ? null : target.getEntity();
        this.hitResult = RaycastUtility.raycastEntity(this.settings.getAttackDistance(), 1.0F, player.getYRot(), player.getXRot(),
                e -> targetEntity == null || e == targetEntity);
    }

    private boolean hitSelectReady(LocalPlayer player, Entity target) {
        if (!(target instanceof net.minecraft.world.entity.LivingEntity living)) {
            return true;
        }
        if (player.invulnerableTime <= 11) {
            return true;
        }
        return living.hurtTime <= getPingTicks() - 1;
    }

    private long getPingTicks() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null || minecraft.player == null) {
            return 0L;
        }
        net.minecraft.client.multiplayer.PlayerInfo info =
                minecraft.getConnection().getPlayerInfo(minecraft.player.getUUID());
        return info == null ? 0L : info.getLatency() / 50L;
    }

    private boolean shouldRun() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return false;
        }

        if (BreakerModule.INSTANCE.isSuppressingKillauraAttack()) {
            return false;
        }

        if (settings.isRequireAttackKey() && !mc.options.keyAttack.isDown()) {
            return false;
        }

        if (settings.isDisableWhileMining()) {
            boolean destroying = mc.gameMode != null && mc.gameMode.isDestroying();
            boolean miningInput = MouseHelper.getLeftButton().isDown()
                    && mc.hitResult instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK;
            if (destroying || miningInput) {
                return false;
            }
        }

        ItemStack heldItem = player.getMainHandItem();
        if (settings.isRequireWeapon() &&
                !(heldItem.is(ItemTags.SWORDS) || heldItem.is(ItemTags.AXES) || heldItem.is(ItemTags.PICKAXES))) {
            return false;
        }

        return true;
    }

    private void onHvhRotation(CurrentTarget target) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        Entity targetEntity = target.getEntity();
        Vec3 targetHeadPosition = new Vec3(targetEntity.getX(), targetEntity.getY() + targetEntity.getEyeHeight() + 0.5, targetEntity.getZ());

        Vec3 vec = targetHeadPosition.subtract(player.getEyePosition()).normalize();

        double distanceToTarget = player.distanceTo(targetEntity);
        double thresholdDistance = 6.0;

        if (distanceToTarget < thresholdDistance && !hvhHasTurned) {
            float rawYaw = player.getYRot() + 180;
            RotationHelper.getHandler().rotate(new Vec2(rawYaw, player.getXRot()), InstantRotationModel.INSTANCE, this);
            hvhHasTurned = true;
            return;
        }

        if (hvhHasTurned) {
            player.setPos(player.getX() + vec.x, player.getY(), player.getZ() + vec.z);
            return;
        }

        float rawYaw = (float) Math.toDegrees(Math.atan2(-vec.x, vec.z));
        float rawPitch = (float) Mth.clamp(Math.toDegrees(Math.asin(-vec.y)), -89, 89);

        float jitterAmount = 25.0f;
        float jitterYaw = (float) (Math.random() * 2 - 1) * jitterAmount;
        float jitterPitch = (float) (Math.random() * 20 - 1);

        float yawDelta = Mth.wrapDegrees(rawYaw - player.getYRot() + jitterYaw);
        float pitchDelta = rawPitch - player.getXRot() + jitterPitch;

        float yawSpeed = 125;
        float pitchSpeed = 300;

        float clampedYaw = Mth.clamp(yawDelta, -yawSpeed, yawSpeed);
        float clampedPitch = Mth.clamp(pitchDelta, -pitchSpeed, pitchSpeed);

        float interpolatedYaw = player.getYRot() + clampedYaw * 0.5f;
        float interpolatedPitch = player.getXRot() + (Minecraft.getInstance().hitResult != null
                && Minecraft.getInstance().hitResult.getType() == HitResult.Type.ENTITY ? 0 : clampedPitch);

        RotationHelper.getHandler().rotate(new Vec2(interpolatedYaw, interpolatedPitch), InstantRotationModel.INSTANCE, this);
    }
}
