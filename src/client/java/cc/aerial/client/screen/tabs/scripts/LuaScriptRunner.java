package cc.aerial.client.screen.tabs.scripts;

import cc.aerial.client.script.ScriptContext;
import cc.aerial.client.script.ScriptEventsAPI;
import cc.aerial.client.script.ScriptGameAPI;
import cc.aerial.client.script.ScriptHUDAPI;
import cc.aerial.client.script.ScriptModuleAPI;
import cc.aerial.client.script.ScriptUtilsAPI;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

/**
 * Lightweight pure-Java Lua runtime (LuaJ) for the advanced editor's Lua mode. The same
 * {@code game}/{@code modules}/{@code hud}/{@code utils} globals are exposed as the JavaScript
 * host, coerced from their plain Java API objects.
 */
public final class LuaScriptRunner {
    private LuaScriptRunner() {
    }

    private static Globals globals;

    private static Globals globals() {
        if (globals == null) {
            Globals g = JsePlatform.standardGlobals();
            g.set("game", CoerceJavaToLua.coerce(new ScriptGameAPI()));
            g.set("modules", CoerceJavaToLua.coerce(new ScriptModuleAPI()));
            g.set("hud", CoerceJavaToLua.coerce(new ScriptHUDAPI()));
            g.set("utils", CoerceJavaToLua.coerce(new ScriptUtilsAPI()));
            g.set("events", CoerceJavaToLua.coerce(new ScriptEventsAPI()));
            globals = g;
        }
        return globals;
    }

    public static String run(String name, String code) {
        ScriptContext.begin(name);
        try {
            LuaValue chunk = globals().load(code, name);
            chunk.call();
            return null;
        } catch (LuaError e) {
            String message = e.getMessage();
            System.err.println("[Lua] error in " + name + ": " + message);
            return message;
        } catch (Throwable t) {
            System.err.println("[Lua] failed to run " + name + ": " + t.getMessage());
            return String.valueOf(t.getMessage());
        } finally {
            ScriptContext.end();
        }
    }
}
