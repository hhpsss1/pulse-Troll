package cc.aerial.client.script;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

public final class ScriptPlayer {
    private final LocalPlayer player;
    
    public ScriptPlayer(LocalPlayer player) {
        this.player = player;
    }
    
    public String getName() {
        return player.getName().getString();
    }
    
    public double getX() {
        return player.getX();
    }
    
    public double getY() {
        return player.getY();
    }
    
    public double getZ() {
        return player.getZ();
    }
    
    public float getYaw() {
        return player.getYRot();
    }
    
    public float getPitch() {
        return player.getXRot();
    }
    
    public float getHealth() {
        return player.getHealth();
    }
    
    public float getMaxHealth() {
        return player.getMaxHealth();
    }
    
    public int getFoodLevel() {
        return player.getFoodData().getFoodLevel();
    }
    
    public boolean isOnGround() {
        return player.onGround();
    }
    
    public void setPosition(double x, double y, double z) {
        player.setPos(x, y, z);
    }
    
    public void setRotation(float yaw, float pitch) {
        player.setYRot(yaw);
        player.setXRot(pitch);
    }
    
    public void chat(String message) {
        player.connection.sendChat(message);
    }

    // --- velocity / motion ---
    public double getMotionX() {
        return player.getDeltaMovement().x;
    }

    public double getMotionY() {
        return player.getDeltaMovement().y;
    }

    public double getMotionZ() {
        return player.getDeltaMovement().z;
    }

    public void setVelocity(double x, double y, double z) {
        player.setDeltaMovement(x, y, z);
    }

    public void addVelocity(double x, double y, double z) {
        Vec3 v = player.getDeltaMovement();
        player.setDeltaMovement(v.x + x, v.y + y, v.z + z);
    }

    public void jump() {
        if (player.onGround()) {
            Vec3 v = player.getDeltaMovement();
            player.setDeltaMovement(v.x, 0.42, v.z);
        }
    }

    // --- state ---
    public boolean isSprinting() {
        return player.isSprinting();
    }

    public void setSprinting(boolean value) {
        player.setSprinting(value);
    }

    public boolean isSneaking() {
        return player.isShiftKeyDown();
    }

    public void setSneaking(boolean value) {
        player.setShiftKeyDown(value);
    }

    public boolean isInWater() {
        return player.isInWater();
    }

    public boolean isUsingItem() {
        return player.isUsingItem();
    }

    public double getEyeHeight() {
        return player.getEyeHeight();
    }

    public float getYawHead() {
        return player.getYHeadRot();
    }

    // --- interaction ---
    public void swingHand() {
        player.swing(InteractionHand.MAIN_HAND);
    }

    public String getHeldItemName() {
        return player.getMainHandItem().getHoverName().getString();
    }

    public double distanceTo(double x, double y, double z) {
        return Math.sqrt(player.distanceToSqr(x, y, z));
    }

    public LocalPlayer getHandle() {
        return player;
    }
}
