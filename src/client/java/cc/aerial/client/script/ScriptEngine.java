package cc.aerial.client.script;

import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

public final class ScriptEngine {
    private static ScriptEngine INSTANCE;

    private final javax.script.ScriptEngine engine;
    private final Map<String, Object> loadedScripts = new HashMap<>();
    private final File scriptsDirectory;
    
    private ScriptEngine() {
        ScriptEngineManager manager = new ScriptEngineManager();
        this.engine = manager.getEngineByName("nashorn");
        
        if (this.engine == null) {
            throw new RuntimeException("Nashorn JavaScript engine not available");
        }
        
        this.scriptsDirectory = new File("scripts");
        if (!scriptsDirectory.exists()) {
            scriptsDirectory.mkdirs();
        }
        
        // Register global API objects
        registerGlobalAPI();
    }
    
    private void registerGlobalAPI() {
        // Register game API
        engine.put("game", new ScriptGameAPI());
        
        // Register module API
        engine.put("modules", new ScriptModuleAPI());
        
        // Register HUD API
        engine.put("hud", new ScriptHUDAPI());
        
        // Register utility API
        engine.put("utils", new ScriptUtilsAPI());

        // Register event/visuals API (tick, render2d, render3d, chat, key, packets, ...)
        engine.put("events", new ScriptEventsAPI());
    }
    
    public static ScriptEngine getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ScriptEngine();
        }
        return INSTANCE;
    }
    
    public javax.script.ScriptEngine getEngine() {
        return engine;
    }
    
    public File getScriptsDirectory() {
        return scriptsDirectory;
    }
    
    public Object executeScript(String scriptName, String scriptCode) {
        ScriptContext.begin(scriptName);
        try {
            Object result = engine.eval(scriptCode);
            loadedScripts.put(scriptName, result);
            return result;
        } catch (ScriptException e) {
            System.err.println("Error executing script " + scriptName + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        } finally {
            ScriptContext.end();
        }
    }
    
    public Object executeScriptFile(File scriptFile) {
        try {
            String scriptCode = new String(java.nio.file.Files.readAllBytes(scriptFile.toPath()));
            return executeScript(scriptFile.getName(), scriptCode);
        } catch (Exception e) {
            System.err.println("Error reading script file " + scriptFile.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    public void reloadAllScripts() {
        loadedScripts.clear();
        ScriptLoader.getInstance().loadAllScripts();
    }
    
    public Map<String, Object> getLoadedScripts() {
        return new HashMap<>(loadedScripts);
    }
}
