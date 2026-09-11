package cc.aerial.client.features.impl.visual.pet;

import java.util.List;
import java.util.Map;

public class PetGeometry {
    private String formatVersion;
    private List<Geometry> geometries;

    public String getFormatVersion() {
        return formatVersion;
    }

    public List<Geometry> getGeometries() {
        return geometries;
    }

    public static class Geometry {
        private Description description;
        private List<Bone> bones;

        public Description getDescription() {
            return description;
        }

        public List<Bone> getBones() {
            return bones;
        }
    }

    public static class Description {
        private String identifier;
        private int textureWidth;
        private int textureHeight;
        private float visibleBoundsWidth;
        private float visibleBoundsHeight;
        private List<Float> visibleBoundsOffset;

        public String getIdentifier() {
            return identifier;
        }

        public int getTextureWidth() {
            return textureWidth;
        }

        public int getTextureHeight() {
            return textureHeight;
        }
    }

    public static class Bone {
        private String name;
        private String parent;
        private List<Float> pivot;
        private List<Float> rotation;
        private List<Cube> cubes;

        public String getName() {
            return name;
        }

        public String getParent() {
            return parent;
        }

        public List<Float> getPivot() {
            return pivot;
        }

        public List<Float> getRotation() {
            return rotation;
        }

        public List<Cube> getCubes() {
            return cubes;
        }
    }

    public static class Cube {
        private List<Float> origin;
        private List<Float> size;
        private List<Float> pivot;
        private Map<String, UVFace> uv;

        public List<Float> getOrigin() {
            return origin;
        }

        public List<Float> getSize() {
            return size;
        }

        public List<Float> getPivot() {
            return pivot;
        }

        public Map<String, UVFace> getUv() {
            return uv;
        }
    }

    public static class UVFace {
        private List<Float> uv;
        private List<Float> uvSize;

        public List<Float> getUv() {
            return uv;
        }

        public List<Float> getUvSize() {
            return uvSize;
        }
    }
}
