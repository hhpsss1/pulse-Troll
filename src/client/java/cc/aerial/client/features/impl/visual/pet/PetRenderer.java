package cc.aerial.client.features.impl.visual.pet;

import cc.aerial.client.event.impl.render.Render3DEvent;
import cc.aerial.client.render.Render3DUtility;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PetRenderer {
    private static final Gson GSON = new GsonBuilder().create();
    
    private PetGeometry geometry;
    private PetAnimation animation;
    private Identifier texture;
    
    private final Map<String, Vec3> bonePositions = new HashMap<>();
    private final Map<String, Vec3> boneRotations = new HashMap<>();
    private final Map<String, Vec3> boneScales = new HashMap<>();
    
    private double animationTime = 0.0;
    
    public boolean loadPet(int petId) {
        try {
            // Load geometry
            String geometryPath = "/assets/dev/cosmetics/pulse/pet/" + petId + "/geometry.json";
            InputStream geometryStream = getClass().getResourceAsStream(geometryPath);
            if (geometryStream != null) {
                geometry = GSON.fromJson(new InputStreamReader(geometryStream), PetGeometry.class);
                geometryStream.close();
            }
            
            // Load animation
            String animationPath = "/assets/dev/cosmetics/pulse/pet/" + petId + "/animation.json";
            InputStream animationStream = getClass().getResourceAsStream(animationPath);
            if (animationStream != null) {
                animation = GSON.fromJson(new InputStreamReader(animationStream), PetAnimation.class);
                animationStream.close();
            }
            
            // Load texture
            texture = Identifier.fromNamespaceAndPath("dev", "cosmetics/pulse/pet/" + petId + "/texture.png");
            
            // Register texture
            TextureManager textureManager = Minecraft.getInstance().getTextureManager();
            textureManager.register(texture, textureManager.getTexture(texture));
            
            return geometry != null;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
    
    public void render(Render3DEvent event, Vec3 position, float scale) {
        if (geometry == null || geometry.getGeometries().isEmpty()) {
            return;
        }
        
        // Update animation
        updateAnimation();
        
        PetGeometry.Geometry geom = geometry.getGeometries().get(0);

        // Render each bone
        for (PetGeometry.Bone bone : geom.getBones()) {
            renderBone(event, position, bone, scale, null);
        }
    }

    private void renderBone(Render3DEvent event, Vec3 position, PetGeometry.Bone bone, float scale, String parentTransform) {
        Vec3 bonePos = bonePositions.getOrDefault(bone.getName(), new Vec3(0, 0, 0));
        Vec3 boneRot = boneRotations.getOrDefault(bone.getName(), new Vec3(0, 0, 0));
        Vec3 boneScale = boneScales.getOrDefault(bone.getName(), new Vec3(1, 1, 1));

        // Apply bone pivot
        Vec3 pivot = bone.getPivot() != null ? new Vec3(
            bone.getPivot().get(0),
            bone.getPivot().get(1),
            bone.getPivot().get(2)
        ) : Vec3.ZERO;

        // Calculate render position
        Vec3 renderPos = position.add(bonePos).add(pivot.scale(scale));

        // Render cubes
        if (bone.getCubes() != null) {
            for (PetGeometry.Cube cube : bone.getCubes()) {
                renderCube(event, renderPos, cube, scale);
            }
        }

        // Render child bones
        for (PetGeometry.Bone childBone : geometry.getGeometries().get(0).getBones()) {
            if (bone.getName().equals(childBone.getParent())) {
                renderBone(event, renderPos, childBone, scale, bone.getName());
            }
        }
    }
    
    private void renderCube(Render3DEvent event, Vec3 position, PetGeometry.Cube cube, float scale) {
        Vec3 origin = cube.getOrigin() != null ? new Vec3(
            cube.getOrigin().get(0) * scale,
            cube.getOrigin().get(1) * scale,
            cube.getOrigin().get(2) * scale
        ) : Vec3.ZERO;

        Vec3 size = cube.getSize() != null ? new Vec3(
            cube.getSize().get(0) * scale,
            cube.getSize().get(1) * scale,
            cube.getSize().get(2) * scale
        ) : new Vec3(1, 1, 1);

        Vec3 cubePos = position.add(origin);

        // Render textured cube with UV mapping
        renderTexturedCube(event, cubePos, size, cube.getUv(), scale);
    }
    
    private void renderTexturedCube(Render3DEvent event, Vec3 pos, Vec3 size, 
                                    Map<String, PetGeometry.UVFace> uvFaces, float scale) {
        if (uvFaces == null || texture == null) {
            // Fallback to glow sprite if no UV data
            Render3DUtility.glowSprite(event, pos, (float) size.x * 0.5f, 0xFFFFFFFF, false);
            return;
        }
        
        float x = (float) pos.x;
        float y = (float) pos.y;
        float z = (float) pos.z;
        float w = (float) size.x;
        float h = (float) size.y;
        float d = (float) size.z;
        
        // Render each face with its UV coordinates
        renderFace(event, "north", x, y, z + d, w, h, uvFaces.get("north"), scale);
        renderFace(event, "south", x, y, z, w, h, uvFaces.get("south"), scale);
        renderFace(event, "east", x + w, y, z, d, h, uvFaces.get("east"), scale);
        renderFace(event, "west", x, y, z, d, h, uvFaces.get("west"), scale);
        renderFace(event, "up", x, y + h, z, w, d, uvFaces.get("up"), scale);
        renderFace(event, "down", x, y, z, w, d, uvFaces.get("down"), scale);
    }
    
    private void renderFace(Render3DEvent event, String faceName, float x, float y, float z, 
                           float width, float height, PetGeometry.UVFace uvFace, float scale) {
        if (uvFace == null) {
            return;
        }
        
        List<Float> uv = uvFace.getUv();
        List<Float> uvSize = uvFace.getUvSize();
        
        if (uv == null || uvSize == null || uv.size() < 2 || uvSize.size() < 2) {
            return;
        }
        
        float u0 = uv.get(0) / 64.0f; // Normalize to 0-1 (texture width is 64)
        float v0 = uv.get(1) / 64.0f;
        float u1 = (uv.get(0) + uvSize.get(0)) / 64.0f;
        float v1 = (uv.get(1) + uvSize.get(1)) / 64.0f;
        
        // For now, still use glow sprite as placeholder
        // TODO: Implement proper VertexConsumer with texture binding
        Vec3 faceCenter = new Vec3(x + width / 2, y + height / 2, z);
        float faceSize = Math.max(width, height) * 0.5f;
        Render3DUtility.glowSprite(event, faceCenter, faceSize, 0xFFFFFFFF, false);
    }
    
    private void updateAnimation() {
        if (animation == null || animation.getAnimations() == null) {
            return;
        }
        
        animationTime += 0.016; // ~60fps
        
        PetAnimation.AnimationData mainAnim = animation.getAnimations().get("main");
        if (mainAnim == null) {
            return;
        }
        
        double animLength = mainAnim.getAnimationLength();
        if (mainAnim.isLoop()) {
            animationTime = animationTime % animLength;
        } else {
            animationTime = Math.min(animationTime, animLength);
        }
        
        // Update bone transforms from animation
        for (Map.Entry<String, PetAnimation.BoneAnimation> entry : mainAnim.getBones().entrySet()) {
            String boneName = entry.getKey();
            PetAnimation.BoneAnimation boneAnim = entry.getValue();
            
            // Update position
            if (boneAnim.getPosition() != null) {
                Vec3 pos = interpolateKeyframes(boneAnim.getPosition(), animationTime);
                bonePositions.put(boneName, pos);
            }
            
            // Update rotation
            if (boneAnim.getRotation() != null) {
                Vec3 rot = interpolateKeyframes(boneAnim.getRotation(), animationTime);
                boneRotations.put(boneName, rot);
            }
            
            // Update scale
            if (boneAnim.getScale() != null) {
                Vec3 scl = interpolateKeyframes(boneAnim.getScale(), animationTime);
                boneScales.put(boneName, scl);
            }
        }
    }
    
    private Vec3 interpolateKeyframes(Map<String, PetAnimation.KeyframeData> keyframes, double time) {
        if (keyframes == null || keyframes.isEmpty()) {
            return new Vec3(0, 0, 0);
        }
        
        // Find the keyframes around the current time
        Double[] times = keyframes.keySet().toArray(new Double[0]);
        java.util.Arrays.sort(times);
        
        if (times.length == 1) {
            List<Float> values = keyframes.get(times[0].toString()).getKeyframes().get(times[0].toString());
            return new Vec3(values.get(0), values.get(1), values.get(2));
        }
        
        // Find surrounding keyframes
        int i = 0;
        while (i < times.length - 1 && times[i + 1] < time) {
            i++;
        }
        
        double t1 = times[i];
        double t2 = times[Math.min(i + 1, times.length - 1)];
        
        List<Float> v1 = keyframes.get(String.valueOf(t1)).getKeyframes().get(String.valueOf(t1));
        List<Float> v2 = keyframes.get(String.valueOf(t2)).getKeyframes().get(String.valueOf(t2));
        
        // Linear interpolation
        float alpha = (float) ((time - t1) / (t2 - t1));
        float x = v1.get(0) + (v2.get(0) - v1.get(0)) * alpha;
        float y = v1.get(1) + (v2.get(1) - v1.get(1)) * alpha;
        float z = v1.get(2) + (v2.get(2) - v1.get(2)) * alpha;
        
        return new Vec3(x, y, z);
    }
    
    public void cleanup() {
        geometry = null;
        animation = null;
        texture = null;
        bonePositions.clear();
        boneRotations.clear();
        boneScales.clear();
        animationTime = 0.0;
    }
}
