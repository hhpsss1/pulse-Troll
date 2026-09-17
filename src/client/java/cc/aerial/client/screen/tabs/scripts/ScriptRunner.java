package cc.aerial.client.screen.tabs.scripts;

import cc.aerial.client.script.ScriptContext;
import cc.aerial.client.script.ScriptSystem;

/** Runs script source in the chosen language, returning {@code null} on success or an error message. */
public final class ScriptRunner {
    private ScriptRunner() {
    }

    public static String run(ScriptLanguage language, String name, String code) {
        return switch (language) {
            case JAVASCRIPT -> runJs(name, code);
            case LUA -> LuaScriptRunner.run(name, code);
        };
    }

    private static String runJs(String name, String code) {
        ScriptContext.begin(name);
        try {
            // Reuse the host engine so the game/modules/hud/utils globals are already registered.
            ScriptSystem.getInstance().getEngine().getEngine().eval(code);
            return null;
        } catch (javax.script.ScriptException e) {
            System.err.println("[JS] error in " + name + ": " + e.getMessage());
            return e.getMessage();
        } catch (Throwable t) {
            System.err.println("[JS] failed to run " + name + ": " + t.getMessage());
            return String.valueOf(t.getMessage());
        } finally {
            ScriptContext.end();
        }
    }
}
