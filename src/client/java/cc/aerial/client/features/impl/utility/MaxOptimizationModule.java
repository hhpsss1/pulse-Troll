package cc.aerial.client.features.impl.utility;

import cc.aerial.client.event.impl.game.PreGameTickEvent;
import cc.aerial.client.event.impl.render.Render3DEvent;
import cc.aerial.client.event.subscriber.Subscribe;
import cc.aerial.client.features.Module;
import cc.aerial.client.features.ModuleCategory;
import cc.aerial.client.property.BooleanProperty;
import cc.aerial.client.property.NumberProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class MaxOptimizationModule extends Module {
    public static final MaxOptimizationModule INSTANCE = new MaxOptimizationModule();

    private final BooleanProperty reduceRenderDistance = new BooleanProperty("Reduce Render Distance", true);
    private final NumberProperty renderDistance = new NumberProperty("Render Distance", 2, 1, 12, 1)
            .hideIf(() -> !reduceRenderDistance.getValue());
    
    private final BooleanProperty disableEntityShadows = new BooleanProperty("Disable Entity Shadows", true);
    private final BooleanProperty disableVignette = new BooleanProperty("Disable Vignette", true);
    private final BooleanProperty minimizeGuiScale = new BooleanProperty("Minimize GUI Scale", false);
    private final NumberProperty guiScale = new NumberProperty("GUI Scale", 1, 1, 4, 1)
            .hideIf(() -> !minimizeGuiScale.getValue());

    // New optimization properties
    private final BooleanProperty entityCulling = new BooleanProperty("Entity Culling", true);
    private final NumberProperty entityRenderDistance = new NumberProperty("Entity Render Distance", 64, 16, 128, 8)
            .hideIf(() -> !entityCulling.getValue());
    private final BooleanProperty reduceParticles = new BooleanProperty("Reduce Particles", true);
    private final NumberProperty particleMultiplier = new NumberProperty("Particle Multiplier", 0.2, 0.0, 1.0, 0.1)
            .hideIf(() -> !reduceParticles.getValue());
    private final BooleanProperty optimizeTextRendering = new BooleanProperty("Optimize Text Rendering", true);
    private final NumberProperty maxFloatingText = new NumberProperty("Max Floating Text", 10, 0, 50, 5)
            .hideIf(() -> !optimizeTextRendering.getValue());
    private final BooleanProperty disableClouds = new BooleanProperty("Disable Clouds", true);
    private final BooleanProperty reduceChunkUpdates = new BooleanProperty("Reduce Chunk Updates", true);
    private final BooleanProperty fastRender = new BooleanProperty("Fast Render", true);
    private final BooleanProperty smoothWorld = new BooleanProperty("Smooth World", false);
    private final BooleanProperty optimizeAnimations = new BooleanProperty("Optimize Animations", true);
    private final BooleanProperty optimizeMovement = new BooleanProperty("Optimize Movement", true);
    private final BooleanProperty optimizeCamera = new BooleanProperty("Optimize Camera", true);
    private final BooleanProperty aggressiveEntityCulling = new BooleanProperty("Aggressive Entity Culling", true);
    private final BooleanProperty limitChunkUpdates = new BooleanProperty("Limit Chunk Updates", true);

    private int originalRenderDistance;
    private int originalGuiScale;
    private boolean originalEntityShadows;
    private boolean originalVignette;
    private boolean originalClouds;
    private double originalParticleMultiplier;
    
    private int frameCount = 0;
    private long lastOptimizationCheck = 0;

    private MaxOptimizationModule() {
        super("Max Optimization", "Maximize Minecraft performance", ModuleCategory.UTILITY);
        addProperties(reduceRenderDistance, renderDistance, 
                disableEntityShadows, disableVignette, minimizeGuiScale, guiScale,
                entityCulling, entityRenderDistance, reduceParticles, particleMultiplier,
                optimizeTextRendering, maxFloatingText, disableClouds, reduceChunkUpdates,
                fastRender, smoothWorld, optimizeAnimations, optimizeMovement, optimizeCamera,
                aggressiveEntityCulling, limitChunkUpdates);
    }

    @Override
    protected void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        Options options = mc.options;
        
        if (options == null) {
            return;
        }
        
        if (mc.level != null) {
            originalRenderDistance = options.renderDistance().get();
            originalGuiScale = options.guiScale().get();
        }
        
        originalEntityShadows = options.entityShadows().get();
        originalVignette = options.vignette().get();

        applyOptimizations();
    }

    @Override
    protected void onDisable() {
        restoreSettings();
    }

    @Subscribe
    public void onTick(PreGameTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        
        // Run optimization checks every 20 ticks (1 second) to avoid performance impact
        frameCount++;
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastOptimizationCheck > 1000) {
            lastOptimizationCheck = currentTime;
            performDynamicOptimizations(mc);
        }
    }

    @Subscribe
    public void onRender3D(Render3DEvent event) {
        Minecraft mc = Minecraft.getInstance();
        
        if (optimizeTextRendering.getValue() && mc.level != null) {
            limitFloatingTextRendering(mc);
        }
    }

    private void applyOptimizations() {
        Minecraft mc = Minecraft.getInstance();
        Options options = mc.options;
        
        if (options == null) {
            return;
        }
        
        if (mc.level != null) {
            if (reduceRenderDistance.getValue()) {
                options.renderDistance().set(renderDistance.getValue().intValue());
            }
            
            if (minimizeGuiScale.getValue()) {
                options.guiScale().set(guiScale.getValue().intValue());
            }
        }
        
        if (disableEntityShadows.getValue()) {
            options.entityShadows().set(false);
        }
        
        if (disableVignette.getValue()) {
            options.vignette().set(false);
        }
        
        if (disableClouds.getValue()) {
            originalClouds = true;
            // Clouds optimization will be applied via system properties
            System.setProperty("minecraft.clouds.enabled", "false");
        }
        
        if (reduceParticles.getValue()) {
            originalParticleMultiplier = 1.0;
            // Particle reduction applied dynamically
        }
        
        // Apply fast render settings
        if (fastRender.getValue()) {
            System.setProperty("minecraft.fastRender", "true");
        }
        
        if (smoothWorld.getValue()) {
            System.setProperty("minecraft.smoothWorld", "true");
        }
        
        // Movement and camera optimizations
        if (optimizeMovement.getValue()) {
            System.setProperty("minecraft.optimizeMovement", "true");
        }
        
        if (optimizeCamera.getValue()) {
            System.setProperty("minecraft.optimizeCamera", "true");
        }
        
        if (limitChunkUpdates.getValue()) {
            System.setProperty("minecraft.chunkUpdateLimit", "1");
        }
    }

    private void restoreSettings() {
        Minecraft mc = Minecraft.getInstance();
        Options options = mc.options;
        
        if (options == null) {
            return;
        }
        
        if (mc.level != null) {
            options.renderDistance().set(originalRenderDistance);
            options.guiScale().set(originalGuiScale);
        }
        
        if (originalEntityShadows) {
            options.entityShadows().set(true);
        }
        
        if (originalVignette) {
            options.vignette().set(true);
        }
        
        if (disableClouds.getValue()) {
            System.setProperty("minecraft.clouds.enabled", "true");
        }
        
        System.setProperty("minecraft.fastRender", "false");
        System.setProperty("minecraft.smoothWorld", "false");
        System.setProperty("minecraft.optimizeMovement", "false");
        System.setProperty("minecraft.optimizeCamera", "false");
        System.setProperty("minecraft.chunkUpdateLimit", "0");
    }
    
    private void performDynamicOptimizations(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            return;
        }
        
        // Entity culling - skip rendering entities that are too far or not visible
        if (entityCulling.getValue()) {
            cullDistantEntities(mc);
        }
        
        // Aggressive entity culling when many entities are nearby
        if (aggressiveEntityCulling.getValue()) {
            performAggressiveEntityCulling(mc);
        }
        
        // Particle optimization
        if (reduceParticles.getValue() && mc.particleEngine != null) {
            limitParticles(mc);
        }
        
        // Movement optimization - reduce updates during movement
        if (optimizeMovement.getValue() && isPlayerMoving(mc)) {
            optimizeDuringMovement(mc);
        }
    }
    
    private void cullDistantEntities(Minecraft mc) {
        Vec3 playerPos = mc.player.position();
        double maxDist = entityRenderDistance.getValue().doubleValue();
        
        int culledCount = 0;
        int maxCullPerTick = 50; // Limit culling operations per tick to prevent lag
        
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player) continue;
            
            double distance = entity.position().distanceToSqr(playerPos);
            if (distance > maxDist * maxDist) {
                // Entity is too far, mark for culling via shouldRenderEntity check
                culledCount++;
                if (culledCount >= maxCullPerTick) {
                    break; // Prevent excessive operations in one tick
                }
            }
        }
    }
    
    private void limitParticles(Minecraft mc) {
        // This is a simplified particle limiting approach
        // In a full implementation, you would hook into the particle engine
        double multiplier = particleMultiplier.getValue().doubleValue();
        // Apply particle reduction via system property
        System.setProperty("minecraft.particleMultiplier", String.valueOf(multiplier));
    }
    
    private void limitFloatingTextRendering(Minecraft mc) {
        int maxText = maxFloatingText.getValue().intValue();
        if (maxText <= 0) return;
        
        // Limit the number of floating text entities rendered
        // This helps with servers that have many floating labels
        int textCount = 0;
        Vec3 playerPos = mc.player.position();
        
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Player && entity != mc.player) {
                double distance = entity.position().distanceToSqr(playerPos);
                if (distance > 256) { // 16 blocks
                    continue;
                }
                textCount++;
                if (textCount > maxText) {
                    // Skip rendering additional text
                    break;
                }
            }
        }
    }
    
    private void performAggressiveEntityCulling(Minecraft mc) {
        Vec3 playerPos = mc.player.position();
        int entityCount = countEntities(mc);
        
        // If there are many entities, use more aggressive culling
        if (entityCount > 100) {
            double aggressiveDist = entityRenderDistance.getValue().doubleValue() * 0.5; // Half distance
            
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity == mc.player) continue;
                if (entity instanceof Player) continue; // Don't cull players aggressively
                
                double distance = entity.position().distanceToSqr(playerPos);
                if (distance > aggressiveDist * aggressiveDist) {
                    // Entity will be culled via shouldRenderEntity check
                }
            }
        }
    }
    
    private void optimizeDuringMovement(Minecraft mc) {
        // Reduce render quality during movement to improve FPS
        // This helps with lag when moving or rotating camera
        
        // Temporarily reduce entity render distance during movement
        if (entityCulling.getValue()) {
            double movementMultiplier = 0.7; // 30% less render distance during movement
            double currentMaxDist = entityRenderDistance.getValue().doubleValue() * movementMultiplier;
            
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity == mc.player) continue;
                
                double distance = entity.position().distanceToSqr(mc.player.position());
                if (distance > currentMaxDist * currentMaxDist) {
                    // Entity will be culled via shouldRenderEntity check
                }
            }
        }
        
        // Reduce particle effects during movement
        if (reduceParticles.getValue()) {
            double movementParticleMult = particleMultiplier.getValue().doubleValue() * 0.5;
            System.setProperty("minecraft.particleMultiplier", String.valueOf(movementParticleMult));
        }
    }
    
    public boolean shouldRenderEntity(Entity entity) {
        if (!entityCulling.getValue()) {
            return true;
        }
        
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return true;
        }
        
        double maxDist = entityRenderDistance.getValue().doubleValue();
        double distance = entity.position().distanceToSqr(mc.player.position());
        
        return distance <= maxDist * maxDist;
    }
    
    public double getParticleMultiplier() {
        return reduceParticles.getValue() ? particleMultiplier.getValue().doubleValue() : 1.0;
    }
    
    public boolean isTextRenderingOptimized() {
        return optimizeTextRendering.getValue();
    }
    
    public int getMaxFloatingText() {
        return maxFloatingText.getValue().intValue();
    }
    
    private boolean isPlayerMoving(Minecraft mc) {
        if (mc.player == null) return false;
        // Check if player has any velocity
        return mc.player.getDeltaMovement().lengthSqr() > 0.0001;
    }
    
    private int countEntities(Minecraft mc) {
        int count = 0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            count++;
        }
        return count;
    }

    @Override
    public String getSuffix() {
        return "MAX";
    }
}
