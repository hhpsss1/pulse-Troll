/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */

package cc.aerial.client.features.impl.combat.crystalaura.aim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Crosshair ray tests for the local player at an ARBITRARY rotation, mirroring the vanilla client pick.
 *
 * <p>Vanilla reference, in the shape 26.x gives it — the pick lives on {@code LocalPlayer}, not on {@code Player}:
 * {@code raycastHitResult} calls a private static {@code pick(cameraEntity, blockInteractionRange,
 * entityInteractionRange, partialTicks)} and then {@code filterHitResult}. {@code Entity.pick} clips from the eye
 * along the view vector with {@code ClipContext.Block.OUTLINE} and {@code Fluid.NONE};
 * {@code ProjectileUtil.getEntityHitResult} does the entity leg, with the pick radius, the eye-inside-box rule and
 * the shared-vehicle rule — it is called directly here rather than re-implemented, so all three stay vanilla.
 *
 * <p>Deliberate deviations, everything else is copied operation by operation:
 * <ol>
 *   <li>The eye is a parameter instead of the camera entity's interpolated eye, and the direction comes from the
 *       given {@link Rotation} instead of the interpolated view vector; the camera entity is always the local
 *       player.</li>
 *   <li>The entity predicate is the vanilla can-be-picked selector narrowed by the caller's filter, so callers can
 *       exclude crystals that are already attacked or expected to vanish.</li>
 *   <li>The attack-range item branch of {@code raycastHitResult} is NOT replicated. It runs when the active item
 *       carries an attack-range component, and on a miss vanilla falls back to the ordinary pick, which is what
 *       this mirrors. That branch only shapes the client's own crosshair; the server never validates a click
 *       against it.</li>
 * </ol>
 */
public final class RayTests {
    private RayTests() {
    }

    /**
     * Replica of the vanilla pick for the local player looking along a rotation from an eye.
     *
     * <p>The longer of the two ranges drives the block clip; unless that clip missed, the entity leg is shortened
     * to the block hit. The entity wins only when its hit is strictly closer than the block hit, and each leg then
     * passes through the range filter with its own range.
     *
     * @return a block hit — type BLOCK, or MISS with the vanilla miss location — or an entity hit
     */
    public static HitResult pick(Vec3 eye, Rotation rotation, double blockRange, double entityRange,
                                 Predicate<Entity> ignore) {
        LocalPlayer player = player();
        double maxDistance = Math.max(blockRange, entityRange);
        double maxDistanceSq = Mth.square(maxDistance);
        Vec3 direction = direction(rotation);
        BlockHitResult blockHitResult = clipBlocks(eye, endPoint(eye, direction, maxDistance));
        double blockDistanceSq = blockHitResult.getLocation().distanceToSqr(eye);
        if (blockHitResult.getType() != HitResult.Type.MISS) {
            maxDistanceSq = blockDistanceSq;
            maxDistance = Math.sqrt(blockDistanceSq);
        }

        Vec3 to = endPoint(eye, direction, maxDistance);
        AABB box = player.getBoundingBox().expandTowards(direction.scale(maxDistance)).inflate(1.0, 1.0, 1.0);
        EntityHitResult entityHitResult = ProjectileUtil.getEntityHitResult(
                player, eye, to, box, pickPredicate(ignore), maxDistanceSq);
        if (entityHitResult != null && entityHitResult.getLocation().distanceToSqr(eye) < blockDistanceSq) {
            return filterHitResult(entityHitResult, eye, entityRange);
        }
        return filterHitResult(blockHitResult, eye, blockRange);
    }

    /**
     * The pick with both ranges set to one value, kept only when it is a BLOCK hit on the given position.
     *
     * <p>Vanilla uses different ranges for the two legs, but passing one for both never changes whether this block
     * is the picked one: an entity in front of it masks it either way (vanilla merely reports that entity as a
     * miss beyond the entity range instead of returning it), and a block at or beyond the range is filtered to a
     * miss either way.
     */
    @Nullable
    public static BlockHitResult hitsBlock(Vec3 eye, Rotation rotation, BlockPos pos, double range,
                                           Predicate<Entity> ignore, boolean throughBlocks) {
        if (throughBlocks) {
            return clipTarget(eye, rotation, pos, range);
        }
        HitResult hit = pick(eye, rotation, range, range, ignore);
        if (hit instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK
                && blockHit.getBlockPos().equals(pos)) {
            return blockHit;
        }
        return null;
    }

    /**
     * The box the pick actually sees at a position, in world coordinates, or null when nothing there can be
     * picked.
     *
     * <p>The outline shape is what the pick clips against; its bounds, moved to where the block stands, is what a
     * look point search must sample when the block does not fill its cell. A one-layer snow is 2/16 of a block
     * tall and a fire 1/16, both a full footprint glued to the BOTTOM of their cell. Sampling the whole unit cube
     * for such a block yields points ABOVE the shape, and a ray aimed at one of those need not terminate on the
     * block at all; sampling these bounds aims at the slab that is really there.
     *
     * <p>An empty shape answers null — air has nothing to click, and asking for its bounds would throw.
     */
    @Nullable
    public static AABB pickBox(BlockPos pos) {
        ClientLevel level = level();
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos, CollisionContext.of(player()));
        return shape.isEmpty() ? null : shape.bounds().move(pos);
    }

    /**
     * The through-blocks path: the ray is clipped against this cell ALONE, so nothing standing between the eye and
     * that block can mask it.
     *
     * <p>This is the same per-cell primitive the full pick is built from, applied to one cell instead of walking
     * the whole segment, and on the same shape vanilla's pick would use there. An empty shape yields null, and the
     * range filter is the same strict closer-than test; the only rule dropped is occlusion.
     *
     * <p>The returned hit is what goes on the wire verbatim, and its direction is the face the ray entered
     * through — necessarily a face the eye is outside of, which is what a position check tests and what keeps the
     * face id inside the legal range.
     */
    @Nullable
    private static BlockHitResult clipTarget(Vec3 eye, Rotation rotation, BlockPos pos, double range) {
        ClientLevel level = level();
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos, CollisionContext.of(player()));
        if (shape.isEmpty()) {
            return null;
        }
        Vec3 to = endPoint(eye, direction(rotation), range);
        BlockHitResult hit = shape.clip(eye, to, pos);
        if (hit == null || !hit.getLocation().closerThan(eye, range)) {
            return null;
        }
        return hit;
    }

    /**
     * The pick with both ranges set to one value, kept only when it is an entity hit on this crystal, by identity.
     *
     * <p>Vanilla clips blocks farther than it searches entities. Passing one range for both legs deviates only in
     * the reach of the block leg, which cannot change the answer: the crystal wins only when its hit is strictly
     * closer than the block hit, so a block farther than the crystal never wins either way, a block nearer masks
     * it either way, and a block beyond the range that vanilla would still clip is farther than any crystal hit
     * accepted within the range. The entity search box only shrinks with the shorter ray, which cannot lose an
     * entity whose clip point lies on that ray, since the eye is inside the player box and the box is inflated.
     */
    @Nullable
    public static EntityHitResult hitsCrystal(Vec3 eye, Rotation rotation, Entity crystal, double range,
                                              Predicate<Entity> ignore) {
        HitResult hit = pick(eye, rotation, range, range, ignore);
        if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() == crystal) {
            return entityHit;
        }
        return null;
    }

    /**
     * Pure geometry, no world access: the entry point where the ray meets the box, the eye itself when the box
     * contains it, or null.
     *
     * <p>This is the per-entity test of the vanilla entity leg applied to an arbitrary box, without the pick-radius
     * inflation — a crystal overrides pickability but not the pick radius, so its radius is the default zero.
     */
    @Nullable
    public static Vec3 hitsBoxGeom(Vec3 eye, Rotation rotation, AABB box, double range) {
        Optional<Vec3> clipped = box.clip(eye, endPoint(eye, direction(rotation), range));
        if (clipped.isPresent()) {
            return clipped.get();
        }
        return box.contains(eye) ? eye : null;
    }

    /**
     * Id-prediction support: the geometric hit for a crystal that is not in the world yet, confirmed against the
     * world the way the real pick would confirm an entity hit at that point.
     *
     * <p>The block clip from the eye to the point must miss, or hit no nearer than the point — a nearer block wins
     * the pick. And the entity leg over the same search box, ending at the point, must not return a non-ignored
     * pickable entity strictly closer than the point.
     *
     * <p>Deviations: the search box is built from the point minus the eye instead of the scaled direction — the
     * same vector up to rounding, since the point lies on the ray; and an entity containing the eye, at distance
     * zero, does not block a point that is the eye itself, where vanilla would pick whichever such entity
     * iterates last.
     *
     * @return the geometric hit point, or null when a block or another entity would be picked first
     */
    @Nullable
    public static Vec3 hitsPhantomBox(Vec3 eye, Rotation rotation, AABB box, double range,
                                      Predicate<Entity> ignore) {
        Vec3 point = hitsBoxGeom(eye, rotation, box, range);
        if (point == null) {
            return null;
        }
        double pointDistanceSq = point.distanceToSqr(eye);
        BlockHitResult blockHit = clipBlocks(eye, point);
        if (blockHit.getType() != HitResult.Type.MISS
                && blockHit.getLocation().distanceToSqr(eye) < pointDistanceSq) {
            return null;
        }

        LocalPlayer player = player();
        AABB searchBox = player.getBoundingBox().expandTowards(point.subtract(eye)).inflate(1.0, 1.0, 1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                player, eye, point, searchBox, pickPredicate(ignore), pointDistanceSq);
        boolean entityBlocks = entityHit != null && entityHit.getLocation().distanceToSqr(eye) < pointDistanceSq;
        return entityBlocks ? null : point;
    }

    /**
     * The vanilla range filter: a hit whose location is not strictly closer than the range becomes a miss at that
     * location, facing the approximate direction from the eye.
     */
    private static HitResult filterHitResult(HitResult hitResult, Vec3 from, double maxRange) {
        Vec3 location = hitResult.getLocation();
        if (location.closerThan(from, maxRange)) {
            return hitResult;
        }
        Direction direction = Direction.getApproximateNearest(
                location.x - from.x, location.y - from.y, location.z - from.z);
        return BlockHitResult.miss(location, direction, BlockPos.containing(location));
    }

    /** The block leg of the vanilla pick: an outline clip with no fluids. */
    private static BlockHitResult clipBlocks(Vec3 from, Vec3 to) {
        return level().clip(new ClipContext(
                from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player()));
    }

    /** The vanilla can-be-picked selector, narrowed by the caller's filter. */
    private static Predicate<Entity> pickPredicate(Predicate<Entity> ignore) {
        return EntitySelector.CAN_BE_PICKED.and(entity -> !ignore.test(entity));
    }

    /** The view vector for a rotation, through the entity method so the vanilla rounding is kept. */
    private static Vec3 direction(Rotation rotation) {
        return player().calculateViewVector(rotation.pitch(), rotation.yaw());
    }

    /**
     * The far end of a ray, kept as three scalar products rather than one scaled vector so the vanilla rounding
     * is preserved.
     */
    private static Vec3 endPoint(Vec3 from, Vec3 direction, double distance) {
        return from.add(direction.x * distance, direction.y * distance, direction.z * distance);
    }

    private static LocalPlayer player() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            throw new IllegalStateException("RayTests requires a player");
        }
        return player;
    }

    private static ClientLevel level() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            throw new IllegalStateException("RayTests requires a level");
        }
        return level;
    }
}
