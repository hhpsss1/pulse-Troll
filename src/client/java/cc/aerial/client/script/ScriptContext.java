package cc.aerial.client.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks everything a script registers (modules, HUD elements, event handlers) so it can all be
 * torn down when the script is deleted or re-run. Registration APIs call {@link #record} while a
 * script executes; {@link #begin} first tears down any previous registration for that name, which
 * also stops "Run" from stacking duplicate handlers.
 */
public final class ScriptContext {
    private static String current;
    private static final Map<String, List<Runnable>> cleanups = new LinkedHashMap<>();

    private ScriptContext() {
    }

    /** Start executing a script by name; drops anything the previous run of the same name registered. */
    public static void begin(String name) {
        unload(name);
        current = name;
    }

    public static void end() {
        current = null;
    }

    /** Record an undo action for the script currently executing (no-op outside a begin/end block). */
    public static void record(Runnable undo) {
        if (current == null) {
            return;
        }
        cleanups.computeIfAbsent(current, k -> new ArrayList<>()).add(undo);
    }

    /** Immediately undo everything the named script registered. */
    public static void unload(String name) {
        List<Runnable> list = cleanups.remove(name);
        if (list == null) {
            return;
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            try {
                list.get(i).run();
            } catch (Throwable t) {
                System.err.println("[Script] cleanup error for " + name + ": " + t);
            }
        }
    }

    public static void unloadAll() {
        for (String name : new ArrayList<>(cleanups.keySet())) {
            unload(name);
        }
    }
}
