package cc.aerial.client.command;

import cc.aerial.client.AerialClient;
import cc.aerial.client.binding.BindRepository;
import cc.aerial.client.binding.BindingService;
import cc.aerial.client.binding.InputType;
import cc.aerial.client.config.ConfigUtility;
import cc.aerial.client.features.Module;
import cc.aerial.client.notification.NotificationManager;
import cc.aerial.client.notification.NotificationType;

import java.util.Arrays;
import java.util.Locale;
import cc.aerial.client.packet.PacketRateDebug;

public final class CommandHandler {
    private CommandHandler() {
    }

    public static void handle(String message) {
        String[] parts = message.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return;
        }

        if (parts[0].equalsIgnoreCase("bind")) {
            handleBind(parts);
        } else if (parts[0].equalsIgnoreCase("config")) {
            handleConfig(parts);
        } else if (parts[0].equalsIgnoreCase("packetrate")) {
            PacketRateDebug.setEnabled(!PacketRateDebug.isEnabled());
            success("Packet rate debug", PacketRateDebug.isEnabled() ? "on" : "off");
        } else if (parts[0].equalsIgnoreCase("hide")) {
            handleVisibility(parts, false);
        } else if (parts[0].equalsIgnoreCase("unhide")) {
            handleVisibility(parts, true);
        } else if (parts[0].equalsIgnoreCase("help")) {
            handleHelp(parts);
        } else {
            error("Command", "Unknown command: ." + parts[0] + ". Type .help for available commands.");
        }
    }

    private static void handleVisibility(String[] parts, boolean visible) {
        String commandName = visible ? "unhide" : "hide";
        if (parts.length < 2) {
            error("Usage", "." + commandName + " <module>");
            return;
        }

        String name = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
        Module module = findModule(name);
        if (module == null) {
            error("Unknown module", name);
            return;
        }

        module.setVisible(visible);
        info(commandName.substring(0, 1).toUpperCase(Locale.ROOT) + commandName.substring(1),
                module.getName() + (visible ? " is now visible." : " is now hidden."));
    }

    private static void handleConfig(String[] parts) {
        if (parts.length < 2) {
            error("Usage", ".config <save/load> [name]");
            return;
        }

        String name = parts.length >= 3 ? parts[2] : "config";

        if (parts[1].equalsIgnoreCase("save")) {
            ConfigUtility.save(name);
            success("Config saved", name);
        } else if (parts[1].equalsIgnoreCase("load")) {
            if (ConfigUtility.load(name)) {
                success("Config loaded", name);
            } else {
                error("Config not found", name);
            }
        } else {
            error("Usage", ".config <save/load> [name]");
        }
    }

    private static void handleBind(String[] parts) {
        if (parts.length < 3) {
            error("Usage", ".bind <module> <key/none>");
            return;
        }

        String name = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length - 1));
        Module module = findModule(name);
        if (module == null) {
            error("Unknown module", name);
            return;
        }

        BindingService bindingService = BindRepository.INSTANCE.getBindingService();
        String keyToken = parts[parts.length - 1];

        if (keyToken.equalsIgnoreCase("none")) {
            bindingService.clearBindings(module);
            success("Bind cleared", module.getName());
            return;
        }

        String keyName = keyToken.toUpperCase(Locale.ROOT);
        Integer code = BindRepository.INSTANCE.getNamedBindingMap().get(keyName);
        if (code == null) {
            error("Unknown key", keyToken);
            return;
        }
        InputType type = keyName.startsWith("MOUSE_") ? InputType.MOUSE : InputType.KEYBOARD;

        bindingService.clearBindings(module);
        bindingService.register(code, module, type);
        success("Bind set", module.getName() + " to " + keyName);
    }

    private static Module findModule(String name) {
        for (Module module : AerialClient.getModuleRepository().getModules()) {
            if (module.getName().equalsIgnoreCase(name) || module.getId().equalsIgnoreCase(name)) {
                return module;
            }
        }
        return null;
    }

    private static void success(String title, String detail) {
        report(NotificationType.SUCCESS, title, detail);
    }

    private static void info(String title, String detail) {
        report(NotificationType.INFO, title, detail);
    }

    private static void error(String title, String detail) {
        report(NotificationType.ERROR, title, detail);
    }

    private static void report(NotificationType type, String title, String detail) {
        NotificationManager.INSTANCE.builder(type)
                .title(title)
                .description(detail)
                .buildAndPublish();
    }

    private static void handleHelp(String[] parts) {
        if (parts.length > 1) {
            if (parts[1].equalsIgnoreCase("bind")) {
                info("Bind Command", ".bind <module> <key/none> - Binds a key to a module");
                return;
            } else if (parts[1].equalsIgnoreCase("config")) {
                info("Config Command", ".config <save/load> [name] - Saves or loads a config");
                return;
            } else if (parts[1].equalsIgnoreCase("hide")) {
                info("Hide Command", ".hide <module> - Hides a module from the arraylist");
                return;
            } else if (parts[1].equalsIgnoreCase("unhide")) {
                info("Unhide Command", ".unhide <module> - Shows a hidden module");
                return;
            } else if (parts[1].equalsIgnoreCase("packetrate")) {
                info("Packetrate Command", ".packetrate - Toggles packet rate debug");
                return;
            }
        }

        StringBuilder helpText = new StringBuilder();
        helpText.append("Available commands:\n");
        helpText.append(".bind <module> <key> - Bind key to module\n");
        helpText.append(".config <save/load> [name] - Save/load config\n");
        helpText.append(".hide <module> - Hide module\n");
        helpText.append(".unhide <module> - Show module\n");
        helpText.append(".packetrate - Toggle debug\n");
        helpText.append(".help [command] - Get help for specific command\n\n");
        helpText.append("Available modules:");
        
        info("Help", helpText.toString());
        
        // Send module list in separate notifications to avoid truncation
        java.util.List<String> categories = java.util.Arrays.asList(
            "COMBAT", "MOVEMENT", "VISUAL", "UTILITY", "WORLD", "HUD", "OTHER"
        );
        
        for (String category : categories) {
            try {
                cc.aerial.client.features.ModuleCategory moduleCategory = 
                    cc.aerial.client.features.ModuleCategory.valueOf(category);
                java.util.List<Module> modules = AerialClient.getModuleRepository()
                    .getModulesInCategory(moduleCategory);
                
                if (!modules.isEmpty()) {
                    StringBuilder moduleList = new StringBuilder();
                    moduleList.append(category).append(": ");
                    for (Module module : modules) {
                        moduleList.append(module.getName()).append(", ");
                    }
                    // Remove trailing comma and space
                    if (moduleList.length() > 2) {
                        moduleList.setLength(moduleList.length() - 2);
                    }
                    info(category, moduleList.toString());
                }
            } catch (IllegalArgumentException e) {
                // Skip invalid categories
            }
        }
    }
}
