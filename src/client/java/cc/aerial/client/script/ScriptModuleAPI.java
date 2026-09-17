package cc.aerial.client.script;

import cc.aerial.client.AerialClient;
import cc.aerial.client.features.Module;
import cc.aerial.client.features.ModuleCategory;
import cc.aerial.client.features.repository.ModuleRepository;
import cc.aerial.client.event.EventDispatcher;
import cc.aerial.client.event.subscriber.Subscribe;
import cc.aerial.client.event.impl.game.PreGameTickEvent;

import java.util.ArrayList;
import java.util.List;

public final class ScriptModuleAPI {
    private final List<ScriptModule> scriptModules = new ArrayList<>();

    public ScriptModule registerModule(String name, String description, String category) {
        ModuleCategory moduleCategory = parseCategory(category);
        ScriptModule module = new ScriptModule(name, description, moduleCategory);
        scriptModules.add(module);
        // Expose script-created modules to the rest of the client (ClickGui, binds, etc.).
        ModuleRepository repo = AerialClient.getModuleRepository();
        if (repo != null) {
            repo.registerDynamic(module);
        }
        // Tear-down so deleting/re-running the script disables and removes this module immediately.
        ScriptContext.record(() -> {
            if (module.isEnabled()) {
                module.setEnabled(false);
            }
            module.setOnTick(null);
            module.setOnEnable(null);
            module.setOnDisable(null);
            scriptModules.remove(module);
            ModuleRepository r = AerialClient.getModuleRepository();
            if (r != null) {
                r.unregisterDynamic(module);
            }
        });
        return module;
    }

    public void unregisterModule(ScriptModule module) {
        scriptModules.remove(module);
        ModuleRepository repo = AerialClient.getModuleRepository();
        if (repo != null) {
            repo.unregisterDynamic(module);
        }
    }

    public List<ScriptModule> getScriptModules() {
        return new ArrayList<>(scriptModules);
    }

    private ModuleCategory parseCategory(String category) {
        try {
            return ModuleCategory.valueOf(category.toUpperCase());
        } catch (IllegalArgumentException e) {
            // Unknown categories (e.g. "OTHER"/"HUD" from older scripts) fall back to SCRIPTS.
            return ModuleCategory.SCRIPTS;
        }
    }
    
    public static class ScriptModule extends Module {
        private Runnable onEnableCallback;
        private Runnable onDisableCallback;
        private Runnable onTickCallback;
        
        public ScriptModule(String name, String description, ModuleCategory category) {
            super(name, description, category);
        }
        
        public void setOnEnable(Runnable callback) {
            this.onEnableCallback = callback;
        }
        
        public void setOnDisable(Runnable callback) {
            this.onDisableCallback = callback;
        }
        
        public void setOnTick(Runnable callback) {
            this.onTickCallback = callback;
            if (callback != null) {
                EventDispatcher.subscribe(this);
            }
        }
        
        @Override
        protected void onEnable() {
            if (onEnableCallback != null) {
                onEnableCallback.run();
            }
        }
        
        @Override
        protected void onDisable() {
            if (onDisableCallback != null) {
                onDisableCallback.run();
            }
        }
        
        @Subscribe
        public void onTick(PreGameTickEvent event) {
            if (isEnabled() && onTickCallback != null) {
                onTickCallback.run();
            }
        }
    }
}
