package cc.aerial.client.features.impl.visual.pet;

import java.util.List;
import java.util.Map;

public class PetAnimation {
    private Map<String, AnimationData> animations;

    public Map<String, AnimationData> getAnimations() {
        return animations;
    }

    public static class AnimationData {
        private boolean loop;
        private double animationLength;
        private Map<String, BoneAnimation> bones;

        public boolean isLoop() {
            return loop;
        }

        public double getAnimationLength() {
            return animationLength;
        }

        public Map<String, BoneAnimation> getBones() {
            return bones;
        }
    }

    public static class BoneAnimation {
        private Map<String, KeyframeData> rotation;
        private Map<String, KeyframeData> position;
        private Map<String, KeyframeData> scale;

        public Map<String, KeyframeData> getRotation() {
            return rotation;
        }

        public Map<String, KeyframeData> getPosition() {
            return position;
        }

        public Map<String, KeyframeData> getScale() {
            return scale;
        }
    }

    public static class KeyframeData {
        private Map<String, List<Float>> keyframes;

        public Map<String, List<Float>> getKeyframes() {
            return keyframes;
        }
    }
}
