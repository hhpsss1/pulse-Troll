package cc.aerial.client.features.impl.visual;

import cc.aerial.client.event.impl.game.JoinWorldEvent;
import cc.aerial.client.event.impl.game.server.ServerDisconnectEvent;
import cc.aerial.client.event.impl.render.Render3DEvent;
import cc.aerial.client.event.subscriber.Subscribe;
import cc.aerial.client.features.Module;
import cc.aerial.client.features.ModuleCategory;
import cc.aerial.client.property.NumberProperty;
import cc.aerial.client.render.Render3DUtility;
import cc.aerial.client.theme.Theme;
import cc.aerial.client.theme.ThemeManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class FireflyModule extends Module {
    public static final FireflyModule INSTANCE = new FireflyModule();

    private static final long START_NANOS = System.nanoTime();
    private static final long TRAIL_SAMPLE_MS = 14L;
    private static final double SPAWN_RADIUS = 32.0;

    private final NumberProperty count = new NumberProperty("Count", 20, 5, 100, 1);
    private final NumberProperty size = new NumberProperty("Size", 0.06, 0.02, 0.2, 0.01);
    private final NumberProperty speed = new NumberProperty("Speed", 1.0, 0.1, 3.0, 0.1);
    private final NumberProperty trail = new NumberProperty("Trail", 15, 0, 40, 1);
    private final NumberProperty range = new NumberProperty("Range", 64.0, 32.0, 128.0, 1.0);

    private final List<Firefly> fireflies = new ArrayList<>();

    private FireflyModule() {
        super("Firefly", "Spawns free-flying fireflies in the world", ModuleCategory.VISUAL);
        addProperties(count, size, speed, trail, range);
    }

    @Override
    protected void onEnable() {
        spawnFireflies();
    }

    @Override
    protected void onDisable() {
        fireflies.clear();
    }

    @Subscribe
    public void onJoinWorld(JoinWorldEvent event) {
        fireflies.clear();
        spawnFireflies();
    }

    @Subscribe
    public void onServerDisconnect(ServerDisconnectEvent event) {
        fireflies.clear();
    }

    private void spawnFireflies() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        int wanted = count.getValue().intValue();
        fireflies.clear();

        Random random = new Random();
        Vec3 playerPos = mc.player.position();

        for (int i = 0; i < wanted; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = random.nextDouble() * SPAWN_RADIUS;
            double x = playerPos.x + Math.cos(angle) * distance;
            double z = playerPos.z + Math.sin(angle) * distance;
            double y = playerPos.y + random.nextDouble() * 4.0 - 2.0;

            fireflies.add(new Firefly(i, new Vec3(x, y, z)));
        }
    }

    @Subscribe
    public void onRender3D(Render3DEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        double time = (System.nanoTime() - START_NANOS) / 1.0e9 * speed.getValue();
        Vec3 playerPos = mc.player.position();
        double maxRange = range.getValue();

        float spriteSize = size.getValue().floatValue();
        int trailLength = trail.getValue().intValue();
        Theme theme = ThemeManager.getTheme();
        long now = System.currentTimeMillis();

        // Remove fireflies that are too far and spawn new ones
        fireflies.removeIf(firefly -> {
            Vec3 pos = firefly.getCurrentPosition(playerPos, time);
            return pos.distanceToSqr(playerPos) > maxRange * maxRange;
        });

        // Spawn new fireflies if needed
        while (fireflies.size() < count.getValue().intValue()) {
            Random random = new Random();
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = random.nextDouble() * SPAWN_RADIUS;
            double x = playerPos.x + Math.cos(angle) * distance;
            double z = playerPos.z + Math.sin(angle) * distance;
            double y = playerPos.y + random.nextDouble() * 4.0 - 2.0;

            fireflies.add(new Firefly(fireflies.size(), new Vec3(x, y, z)));
        }

        for (int i = 0; i < fireflies.size(); i++) {
            Firefly firefly = fireflies.get(i);
            Vec3 position = firefly.getCurrentPosition(playerPos, time);
            firefly.sample(position, now, trailLength);

            // Occlusion culling - don't render if behind blocks
            if (isOccluded(event, position)) {
                continue;
            }

            int rgb = theme.getAccentColor(0.0, (i * 100.0) / fireflies.size()).getRGB() | 0xFF000000;
            float flicker = 0.7f + 0.3f * (float) Math.sin(time * 5.0 + firefly.phase * 4.0);

            // Draw trail
            List<Vec3> points = firefly.trail;
            int pointCount = points.size();
            if (trailLength > 1 && pointCount > 1) {
                float tailAlpha = 0.55f;
                Render3DUtility.glowTrail(event, points, spriteSize * 1.6f, index -> {
                    Vec3 point = points.get(index);
                    if (isOccluded(event, point)) {
                        return 0;
                    }
                    float t = (float) index / (pointCount - 1);
                    return Render3DUtility.withAlpha(rgb, tailAlpha * t * t);
                }, false);
            }

            // Draw firefly sprite
            Render3DUtility.glowSprite(event, position, spriteSize * 2.2f,
                    Render3DUtility.withAlpha(rgb, flicker), false);
        }
    }

    private boolean isOccluded(Render3DEvent event, Vec3 point) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return false;
        }

        Vec3 from = event.camera().pos;
        ClipContext context = new ClipContext(from, point, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player);
        return mc.level.clip(context).getType() == HitResult.Type.BLOCK;
    }

    private static final class Firefly {
        private final double phase, speedMultiplier, verticalSpeed, horizontalSpeed;
        private final Vec3 origin;
        private final List<Vec3> trail = new ArrayList<>();
        private long lastSampleMs;

        Firefly(int index, Vec3 origin) {
            Random random = new Random(index * 7919L + 17L);
            this.phase = random.nextDouble() * Math.PI * 2.0;
            this.speedMultiplier = 0.5 + random.nextDouble() * 0.5;
            this.verticalSpeed = (random.nextDouble() - 0.5) * 0.3;
            this.horizontalSpeed = (random.nextDouble() - 0.5) * 0.3;
            this.origin = origin;
        }

        Vec3 getCurrentPosition(Vec3 playerPos, double time) {
            double t = time * speedMultiplier + phase;
            
            // Circular motion around origin with slow drift
            double orbitRadius = 2.0 + Math.sin(t * 0.3) * 1.0;
            double x = origin.x + Math.cos(t) * orbitRadius + Math.sin(t * 0.1) * 5.0;
            double z = origin.z + Math.sin(t) * orbitRadius + Math.cos(t * 0.1) * 5.0;
            
            // Vertical bobbing
            double y = origin.y + Math.sin(t * 0.7) * 1.5 + verticalSpeed * t;
            
            return new Vec3(x, y, z);
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
}
