package cc.aerial.client.script;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/** Lightweight scripting wrapper around a Minecraft entity. */
public final class ScriptEntity {
    private final Entity entity;

    public ScriptEntity(Entity entity) {
        this.entity = entity;
    }

    public String getName() {
        return entity.getName().getString();
    }

    public String getType() {
        return entity.getType().toString();
    }

    public int getId() {
        return entity.getId();
    }

    public double getX() {
        return entity.getX();
    }

    public double getY() {
        return entity.getY();
    }

    public double getZ() {
        return entity.getZ();
    }

    public double getEyeY() {
        return entity.getEyeY();
    }

    public float getYaw() {
        return entity.getYRot();
    }

    public float getPitch() {
        return entity.getXRot();
    }

    public boolean isPlayer() {
        return entity instanceof Player;
    }

    public boolean isLiving() {
        return entity instanceof LivingEntity;
    }

    public boolean isAlive() {
        return entity.isAlive();
    }

    public float getHealth() {
        return entity instanceof LivingEntity living ? living.getHealth() : 0.0f;
    }

    public float getMaxHealth() {
        return entity instanceof LivingEntity living ? living.getMaxHealth() : 0.0f;
    }

    public double distanceToPlayer() {
        Entity self = Minecraft.getInstance().player;
        return self == null ? -1.0 : Math.sqrt(entity.distanceToSqr(self));
    }

    public double[] getMin() {
        AABB box = entity.getBoundingBox();
        return new double[]{box.minX, box.minY, box.minZ};
    }

    public double[] getMax() {
        AABB box = entity.getBoundingBox();
        return new double[]{box.maxX, box.maxY, box.maxZ};
    }

    public double getWidth() {
        return entity.getBoundingBox().getXsize();
    }

    public double getHeight() {
        return entity.getBoundingBox().getYsize();
    }

    public Entity getHandle() {
        return entity;
    }
}
