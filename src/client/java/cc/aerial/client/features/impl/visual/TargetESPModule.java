package cc.aerial.client.features.impl.visual;

import cc.aerial.client.event.impl.game.JoinWorldEvent;
import cc.aerial.client.event.impl.game.player.interaction.AttackEvent;
import cc.aerial.client.event.impl.game.server.ServerDisconnectEvent;
import cc.aerial.client.event.impl.render.Render3DEvent;
import cc.aerial.client.event.subscriber.Subscribe;
import cc.aerial.client.features.Module;
import cc.aerial.client.features.ModuleCategory;
import cc.aerial.client.features.impl.combat.killaura.KillauraModule;
import cc.aerial.client.features.impl.combat.killaura.target.CurrentTarget;
import cc.aerial.client.property.BooleanProperty;
import cc.aerial.client.property.ModeProperty;
import cc.aerial.client.property.NumberProperty;
import cc.aerial.client.render.Render3DUtility;
import cc.aerial.client.screen.animation.Animation;
import cc.aerial.client.screen.animation.Easing;
import cc.aerial.client.theme.Theme;
import cc.aerial.client.theme.ThemeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * World-space highlight for the entity Kill Aura is working on (or the last entity the player hit).
 * Drawn through {@link Render3DUtility}, so it works alongside Sodium.
 */
public final class TargetESPModule extends Module {
    public static final TargetESPModule INSTANCE = new TargetESPModule();

    private static final long ATTACK_GRACE_MS = 1200L;
    private static final long TRAIL_SAMPLE_MS = 14L;
    private static final long START_NANOS = System.nanoTime();

    private final ModeProperty<Mode> mode = new ModeProperty<>("Mode", Mode.FIREFLY);
    private final NumberProperty count = new NumberProperty("Fireflies", 12, 3, 40, 1)
            .hideIf(() -> mode.getValue() != Mode.FIREFLY);
    private final NumberProperty radius = new NumberProperty("Radius", 0.8, 0.2, 2.5, 0.05)
            .hideIf(() -> mode.getValue() == Mode.BOX);
    private final NumberProperty size = new NumberProperty("Size", 0.08, 0.02, 0.3, 0.01)
            .hideIf(() -> mode.getValue() != Mode.FIREFLY);
    private final NumberProperty speed = new NumberProperty("Speed", 1.0, 0.1, 3.0, 0.1);
    private final NumberProperty trail = new NumberProperty("Trail", 18, 0, 40, 1)
            .hideIf(() -> mode.getValue() != Mode.FIREFLY);
    private final NumberProperty lineWidth = new NumberProperty("Line width", 2.0, 0.5, 5.0, 0.25)
            .hideIf(() -> mode.getValue() == Mode.FIREFLY);
    private final BooleanProperty throughWalls = new BooleanProperty("Through walls", true);
    private final BooleanProperty hideBehindTarget = new BooleanProperty("Hide behind target", true)
            .hideIf(() -> mode.getValue() == Mode.BOX);
    private final BooleanProperty trackAttacked = new BooleanProperty("Track attacked", true);

    private LivingEntity lastAttacked;
    private long lastAttackMs;

    private LivingEntity renderTarget;
    private final Animation fade = new Animation(Easing.EASE_OUT_EXPO, 300);
    private final List<Firefly> fireflies = new ArrayList<>();

    private TargetESPModule() {
        super("Target ESP", "Highlights the current combat target in the world", ModuleCategory.VISUAL);
        addProperties(mode, count, radius, size, speed, trail, lineWidth, throughWalls, hideBehindTarget, trackAttacked);
    }

    @Override
    public String getSuffix() {
        return mode.getValue().toString();
    }

    @Override
    protected void onDisable() {
        release();
    }

    @Subscribe
    public void onAttack(AttackEvent event) {
        if (event.getTarget() instanceof LivingEntity living) {
            this.lastAttacked = living;
            this.lastAttackMs = System.currentTimeMillis();
        }
    }

    @Subscribe
    public void onJoinWorld(JoinWorldEvent event) {
        release();
    }

    @Subscribe
    public void onServerDisconnect(ServerDisconnectEvent event) {
        release();
    }

    private void release() {
        this.lastAttacked = null;
        this.renderTarget = null;
        this.fireflies.clear();
        this.fade.setValue(0.0f);
    }

    private LivingEntity resolveTarget() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return null;
        }

        KillauraModule killaura = KillauraModule.INSTANCE;
        if (killaura.isEnabled()) {
            CurrentTarget current = killaura.getTargeting().getTarget();
            if (current == null) {
                current = killaura.getTargeting().getRotationTarget();
            }
            if (current != null) {
                LivingEntity entity = current.getEntity();
                if (entity != null && entity.isAlive() && entity.level() == mc.level) {
                    return entity;
                }
            }
        }

        if (trackAttacked.getValue() && lastAttacked != null && lastAttacked.isAlive()
                && lastAttacked.level() == mc.level
                && System.currentTimeMillis() - lastAttackMs < ATTACK_GRACE_MS) {
            return lastAttacked;
        }
        return null;
    }

    @Subscribe
    public void onRender3D(Render3DEvent event) {
        LivingEntity target = resolveTarget();
        if (target != null) {
            if (target != renderTarget) {
                fireflies.clear();
            }
            renderTarget = target;
        }

        fade.run(target != null ? 1.0f : 0.0f);
        float alpha = fade.getValue();

        if (renderTarget == null) {
            return;
        }
        if (target == null && (alpha <= 0.01f || !renderTarget.isAlive())) {
            renderTarget = null;
            fireflies.clear();
            return;
        }

        double time = (System.nanoTime() - START_NANOS) / 1.0e9 * speed.getValue();
        switch (mode.getValue()) {
            case FIREFLY -> drawFireflies(event, renderTarget, alpha, time);
            case BOX -> drawBox(event, renderTarget, alpha);
            case RING -> drawRings(event, renderTarget, alpha, time);
        }
    }

    // ---------------------------------------------------------------- firefly

    private void ensureFireflies(int wanted) {
        while (fireflies.size() < wanted) {
            fireflies.add(new Firefly(fireflies.size()));
        }
        while (fireflies.size() > wanted) {
            fireflies.removeLast();
        }
    }

    private void drawFireflies(Render3DEvent event, LivingEntity target, float alpha, double time) {
        Vec3 feet = Render3DUtility.interpolatedPosition(target, event.partialTick());
        double height = target.getBbHeight();
        Vec3 center = feet.add(0.0, height * 0.5, 0.0);

        int wanted = count.getValue().intValue();
        ensureFireflies(wanted);

        double orbit = radius.getValue() + target.getBbWidth() * 0.5;
        float spriteSize = size.getValue().floatValue();
        int trailLength = trail.getValue().intValue();
        AABB occluder = occluderBox(target, event.partialTick());
        Theme theme = ThemeManager.getTheme();
        long now = System.currentTimeMillis();

        for (int i = 0; i < wanted; i++) {
            Firefly firefly = fireflies.get(i);
            Vec3 position = firefly.position(center, orbit, height, time);
            firefly.sample(position, now, trailLength);

            // Layering: with a clear line of sight the glow is depth-tested, so the target's model
            // covers it pixel by pixel like any other object. Only when a block is in the way do we
            // switch to the through-wall pipeline, and there the hitbox check stands in for depth.
            boolean walls = throughWall(event, position);

            int rgb = theme.getAccentColor(0.0, (i * 100.0) / wanted).getRGB() | 0xFF000000;
            float flicker = 0.7f + 0.3f * (float) Math.sin(time * 5.0 + firefly.phase * 4.0);

            // Comet: the tail is a chain of shrinking, dimming round glows ending in the bright head.
            List<Vec3> points = firefly.trail;
            int pointCount = points.size();
            if (trailLength > 1 && pointCount > 1) {
                float tailAlpha = alpha * 0.55f;
                Render3DUtility.glowTrail(event, points, spriteSize * 1.6f, index -> {
                    if (walls && isHidden(occluder, event, points.get(index))) {
                        return 0;
                    }
                    float t = (float) index / (pointCount - 1);
                    return Render3DUtility.withAlpha(rgb, tailAlpha * t * t);
                }, walls);
            }

            if (!walls || !isHidden(occluder, event, position)) {
                Render3DUtility.glowSprite(event, position, spriteSize * 2.2f,
                        Render3DUtility.withAlpha(rgb, alpha * flicker), walls);
            }
        }
    }

    /**
     * True when {@code point} should be drawn with the through-wall pipeline: the option is on and a
     * block sits between the camera and the point. With a clear line of sight the depth-tested
     * pipeline is used so the world (and the target's own model) occludes it properly.
     */
    private boolean throughWall(Render3DEvent event, Vec3 point) {
        if (!throughWalls.getValue()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return false;
        }
        Vec3 from = event.camera().pos;
        ClipContext context = new ClipContext(from, point, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player);
        return mc.level.clip(context).getType() == HitResult.Type.BLOCK;
    }

    /** Slightly grown hitbox used to hide glows that pass behind the target. Null when disabled. */
    private AABB occluderBox(LivingEntity target, float partialTick) {
        if (!hideBehindTarget.getValue()) {
            return null;
        }
        return Render3DUtility.interpolatedBox(target, partialTick).inflate(0.08);
    }

    private static boolean isHidden(AABB occluder, Render3DEvent event, Vec3 point) {
        return occluder != null && Render3DUtility.occludedBy(occluder, event.camera(), point);
    }

    private static final class Firefly {
        private final double phase, radiusPhase, heightPhase, speedMultiplier, tilt;
        private final List<Vec3> trail = new ArrayList<>();
        private long lastSampleMs;

        Firefly(int index) {
            Random random = new Random(index * 7919L + 17L);
            this.phase = random.nextDouble() * Math.PI * 2.0;
            this.radiusPhase = random.nextDouble() * Math.PI * 2.0;
            this.heightPhase = random.nextDouble() * Math.PI * 2.0;
            double direction = random.nextBoolean() ? 1.0 : -1.0;
            this.speedMultiplier = direction * (0.75 + random.nextDouble() * 0.5);
            this.tilt = (random.nextDouble() - 0.5) * 0.7;
        }

        Vec3 position(Vec3 center, double radius, double height, double time) {
            double t = time * speedMultiplier + phase;
            double r = radius * (0.7 + 0.3 * Math.sin(t * 0.55 + radiusPhase));
            double x = Math.cos(t) * r;
            double z = Math.sin(t) * r;
            double y = Math.sin(t * 0.8 + heightPhase) * height * 0.45 + Math.cos(t) * r * tilt;
            return center.add(x, y, z);
        }

        void sample(Vec3 position, long now, int maxPoints) {
            if (maxPoints <= 1) {
                trail.clear();
                return;
            }
            if (now - lastSampleMs < TRAIL_SAMPLE_MS && !trail.isEmpty()) {
                trail.set(trail.size() - 1, position);
            } else {
                trail.add(position);
                lastSampleMs = now;
            }
            while (trail.size() > maxPoints) {
                trail.removeFirst();
            }
        }
    }

    // ---------------------------------------------------------------- box

    private void drawBox(Render3DEvent event, LivingEntity target, float alpha) {
        AABB box = Render3DUtility.interpolatedBox(target, event.partialTick()).inflate(0.05);
        int rgb = ThemeManager.getTheme().getAccentColor().getRGB() | 0xFF000000;
        boolean walls = throughWall(event, box.getCenter());
        Render3DUtility.boxFilled(event, box, Render3DUtility.withAlpha(rgb, alpha * 0.18f), walls);
        Render3DUtility.boxOutline(event, box, Render3DUtility.withAlpha(rgb, alpha),
                lineWidth.getValue().floatValue(), walls);
    }

    // ---------------------------------------------------------------- ring

    private void drawRings(Render3DEvent event, LivingEntity target, float alpha, double time) {
        Vec3 feet = Render3DUtility.interpolatedPosition(target, event.partialTick());
        double height = target.getBbHeight();
        double ringRadius = radius.getValue() + target.getBbWidth() * 0.5;
        int segments = 64;
        float width = lineWidth.getValue().floatValue();
        AABB occluder = occluderBox(target, event.partialTick());
        Theme theme = ThemeManager.getTheme();

        // Spinner ring at the feet: a bright arc that runs around the circle.
        double spin = time * 2.5;
        int rgb = theme.getAccentColor().getRGB() | 0xFF000000;
        Vec3 feetCenter = feet.add(0.0, 0.02, 0.0);
        boolean feetWalls = throughWall(event, feetCenter);
        Render3DUtility.circle(event, feetCenter, ringRadius, segments, index -> {
            double angle = (Math.PI * 2.0 * index) / segments;
            if (feetWalls && isHidden(occluder, event, feetCenter.add(Math.cos(angle) * ringRadius, 0.0, Math.sin(angle) * ringRadius))) {
                return 0;
            }
            double glow = Math.max(0.0, Math.cos(angle - spin));
            float a = (float) (0.2 + 0.8 * glow * glow * glow) * alpha;
            return Render3DUtility.withAlpha(rgb, a);
        }, width, feetWalls);

        // Scanning ring that sweeps the entity height.
        double sweep = (Math.sin(time * 1.6) * 0.5 + 0.5) * height;
        double sweepRadius = ringRadius * 0.85;
        Vec3 sweepCenter = feet.add(0.0, sweep, 0.0);
        boolean sweepWalls = throughWall(event, sweepCenter);
        int rgbTop = theme.getAccentColor(0.0, 100.0).getRGB() | 0xFF000000;
        Render3DUtility.circle(event, sweepCenter, sweepRadius, segments, index -> {
            double angle = (Math.PI * 2.0 * index) / segments;
            if (sweepWalls && isHidden(occluder, event, sweepCenter.add(Math.cos(angle) * sweepRadius, 0.0, Math.sin(angle) * sweepRadius))) {
                return 0;
            }
            return Render3DUtility.withAlpha(rgbTop, alpha * 0.75f);
        }, width, sweepWalls);
    }

    public enum Mode {
        FIREFLY("Firefly"),
        BOX("Box"),
        RING("Ring");

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
