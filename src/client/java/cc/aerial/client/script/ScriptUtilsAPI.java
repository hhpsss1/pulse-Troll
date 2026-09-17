package cc.aerial.client.script;

import java.util.Random;

public final class ScriptUtilsAPI {
    private final Random random = new Random();
    
    public int randomInt(int min, int max) {
        return random.nextInt(max - min + 1) + min;
    }
    
    public double randomDouble(double min, double max) {
        return min + (max - min) * random.nextDouble();
    }
    
    public void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }
    
    public String formatTime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        return String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60);
    }
    
    public void log(String message) {
        System.out.println("[Script] " + message);
    }

    public void logError(String message) {
        System.err.println("[Script Error] " + message);
    }

    // --- colour helpers for visuals (all return ARGB ints) ---
    public int color(int r, int g, int b, int a) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public int colorRGB(int r, int g, int b) {
        return color(r, g, b, 255);
    }

    public int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /** Rainbow colour that cycles over time. speed = cycles per second, offset shifts the hue 0..1. */
    public int rainbow(double speed, double offset) {
        double hue = (System.currentTimeMillis() % (long) (1000.0 / Math.max(0.001, speed)))
                / (1000.0 / Math.max(0.001, speed));
        return 0xFF000000 | (java.awt.Color.HSBtoRGB((float) ((hue + offset) % 1.0), 0.8f, 1.0f) & 0x00FFFFFF);
    }

    public int hsb(float hue, float saturation, float brightness) {
        return 0xFF000000 | (java.awt.Color.HSBtoRGB(hue, saturation, brightness) & 0x00FFFFFF);
    }

    // --- math helpers ---
    public double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public double distance2D(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public double distance3D(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
