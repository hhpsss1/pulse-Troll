package cc.aerial.client.script;

import cc.aerial.client.event.EventDispatcher;
import cc.aerial.client.event.impl.game.PreGameTickEvent;
import cc.aerial.client.event.subscriber.IEventSubscriber;
import cc.aerial.client.event.subscriber.Subscribe;

public final class ScriptSystem implements IEventSubscriber {
    private static ScriptSystem INSTANCE;
    
    private final ScriptEngine engine;
    private final ScriptLoader loader;
    private boolean initialized = false;
    
    private ScriptSystem() {
        this.engine = ScriptEngine.getInstance();
        this.loader = ScriptLoader.getInstance();
    }
    
    public static ScriptSystem getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ScriptSystem();
        }
        return INSTANCE;
    }
    
    public void initialize() {
        if (initialized) {
            return;
        }
        
        System.out.println("[ScriptSystem] Initializing scripting system...");
        
        // Load all scripts from scripts directory
        loader.loadAllScripts();
        
        // Subscribe to events for hot-reloading and other features
        EventDispatcher.subscribe(this);
        
        initialized = true;
        System.out.println("[ScriptSystem] Scripting system initialized successfully");
    }
    
    @Subscribe
    public void onTick(PreGameTickEvent event) {
        // Can be used for script hot-reloading checks or other periodic tasks
    }
    
    public void reloadScripts() {
        System.out.println("[ScriptSystem] Reloading all scripts...");
        // Undo everything scripts registered (modules, HUD, events) so a reload starts clean.
        ScriptContext.unloadAll();
        ScriptEventBus.getInstance().clearAll();
        ScriptHudManager.getInstance().clearAll();
        loader.reloadAllScripts();
        engine.reloadAllScripts();
        System.out.println("[ScriptSystem] Scripts reloaded");
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    public ScriptEngine getEngine() {
        return engine;
    }
    
    public ScriptLoader getLoader() {
        return loader;
    }
}
