package cc.aerial.client.script;

import cc.aerial.client.event.EventDispatcher;
import cc.aerial.client.event.impl.render.Render2DEvent;
import cc.aerial.client.event.subscriber.IEventSubscriber;
import cc.aerial.client.event.subscriber.Subscribe;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Renders all script HUD elements from one place, exposes them to the HUD-editor tab, and persists
 * positions the user drags to {@code aerial/script_hud.json}.
 */
public final class ScriptHudManager implements IEventSubscriber {
    private static ScriptHudManager INSTANCE;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final List<ScriptHUDAPI.ScriptHUDElement> elements = new CopyOnWriteArrayList<>();
    private final Map<String, float[]> saved = new HashMap<>(); // name -> [x, y]

    private ScriptHudManager() {
        load();
        EventDispatcher.subscribe(this);
    }

    public static ScriptHudManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ScriptHudManager();
        }
        return INSTANCE;
    }

    public void add(ScriptHUDAPI.ScriptHUDElement element) {
        float[] pos = saved.get(element.getName());
        if (pos != null) {
            element.applyPinned(pos[0], pos[1]);
        }
        elements.add(element);
    }

    public void remove(ScriptHUDAPI.ScriptHUDElement element) {
        elements.remove(element);
    }

    public List<ScriptHUDAPI.ScriptHUDElement> getElements() {
        return new ArrayList<>(elements);
    }

    public void clearAll() {
        elements.clear();
    }

    /** Called by the HUD editor after dragging an element. */
    public void pin(ScriptHUDAPI.ScriptHUDElement element) {
        saved.put(element.getName(), new float[]{element.getX(), element.getY()});
        save();
    }

    @Subscribe
    public void onRender(Render2DEvent event) {
        if (elements.isEmpty()) {
            return;
        }
        ScriptRender2D base = new ScriptRender2D(event.extractor(), event.partialTick());
        for (ScriptHUDAPI.ScriptHUDElement element : elements) {
            try {
                element.render(base);
            } catch (Throwable t) {
                System.err.println("[Script] HUD render error (" + element.getName() + "): " + t);
            }
        }
    }

    private File file() {
        return new File(Minecraft.getInstance().gameDirectory, "aerial" + File.separator + "script_hud.json");
    }

    private void save() {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, float[]> e : saved.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("x", e.getValue()[0]);
                o.addProperty("y", e.getValue()[1]);
                root.add(e.getKey(), o);
            }
            File f = file();
            f.getParentFile().mkdirs();
            Files.writeString(f.toPath(), GSON.toJson(root));
        } catch (Exception ignored) {
        }
    }

    private void load() {
        try {
            File f = file();
            if (!f.exists()) {
                return;
            }
            JsonObject root = JsonParser.parseString(Files.readString(f.toPath())).getAsJsonObject();
            for (String name : root.keySet()) {
                JsonObject o = root.getAsJsonObject(name);
                saved.put(name, new float[]{o.get("x").getAsFloat(), o.get("y").getAsFloat()});
            }
        } catch (Exception ignored) {
        }
    }
}
