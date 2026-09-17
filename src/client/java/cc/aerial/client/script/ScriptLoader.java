package cc.aerial.client.script;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.List;

public final class ScriptLoader {
    private static ScriptLoader INSTANCE;
    
    private final ScriptEngine engine;
    private final List<File> loadedScriptFiles = new ArrayList<>();
    
    private ScriptLoader() {
        this.engine = ScriptEngine.getInstance();
    }
    
    public static ScriptLoader getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ScriptLoader();
        }
        return INSTANCE;
    }
    
    public void loadAllScripts() {
        File scriptsDir = engine.getScriptsDirectory();
        if (!scriptsDir.exists() || !scriptsDir.isDirectory()) {
            System.out.println("[ScriptLoader] Scripts directory not found, creating...");
            scriptsDir.mkdirs();
            return;
        }
        
        File[] jsFiles = scriptsDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".js");
            }
        });
        
        if (jsFiles == null || jsFiles.length == 0) {
            System.out.println("[ScriptLoader] No JavaScript files found in scripts directory");
            return;
        }
        
        System.out.println("[ScriptLoader] Found " + jsFiles.length + " JavaScript files");
        
        for (File jsFile : jsFiles) {
            loadScript(jsFile);
        }
    }
    
    public boolean loadScript(File scriptFile) {
        if (loadedScriptFiles.contains(scriptFile)) {
            System.out.println("[ScriptLoader] Script already loaded: " + scriptFile.getName());
            return false;
        }
        
        System.out.println("[ScriptLoader] Loading script: " + scriptFile.getName());
        Object result = engine.executeScriptFile(scriptFile);
        
        if (result != null) {
            loadedScriptFiles.add(scriptFile);
            System.out.println("[ScriptLoader] Successfully loaded: " + scriptFile.getName());
            return true;
        } else {
            System.out.println("[ScriptLoader] Failed to load: " + scriptFile.getName());
            return false;
        }
    }
    
    public void unloadScript(File scriptFile) {
        loadedScriptFiles.remove(scriptFile);
        // Note: In a full implementation, we would also clean up any registered modules/HUD elements
    }
    
    public void reloadScript(File scriptFile) {
        unloadScript(scriptFile);
        loadScript(scriptFile);
    }
    
    public void reloadAllScripts() {
        List<File> previouslyLoaded = new ArrayList<>(loadedScriptFiles);
        loadedScriptFiles.clear();
        
        for (File scriptFile : previouslyLoaded) {
            loadScript(scriptFile);
        }
    }
    
    public List<File> getLoadedScripts() {
        return new ArrayList<>(loadedScriptFiles);
    }
}
