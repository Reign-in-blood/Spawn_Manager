package com.reigninblood.spawnmanager;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.reigninblood.spawnmanager.command.SpawnManagerCommand;
import com.reigninblood.spawnmanager.command.SpawnManagerUICommand;
import com.reigninblood.spawnmanager.config.SpawnManagerConfig;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.logging.Logger;

public final class SpawnManagerPlugin extends JavaPlugin {
    private static final Logger LOGGER = Logger.getLogger(SpawnManagerPlugin.class.getName());
    private static SpawnManagerPlugin INSTANCE;

    private final SpawnManagerConfig config;

    public SpawnManagerPlugin(com.hypixel.hytale.server.core.plugin.JavaPluginInit init) {
        super(init);
        INSTANCE = this;
        this.config = new SpawnManagerConfig(Path.of("SpawnManager"));
    }

    public static SpawnManagerPlugin get() {
        return INSTANCE;
    }

    public SpawnManagerConfig getConfig() {
        return config;
    }

    @Override
    protected void start() {
        config.load();
        getCommandRegistry().registerCommand(new SpawnManagerCommand());
        getCommandRegistry().registerCommand(new SpawnManagerUICommand());
    }

    public void triggerSpawningPopulate(Object preferredSender) {
        final String command = "spawning populate";

        // 1) API suggérée côté user: HytaleServer.get().getCommandManager().handleCommand(...)
        if (invokeViaHytaleServer(preferredSender, command)) {
            LOGGER.info("[SpawnManager] populate triggered via HytaleServer CommandManager");
            return;
        }

        // 2) Fallback: command registry du plugin
        Object registry = getCommandRegistry();
        if (registry == null) {
            LOGGER.warning("[SpawnManager] populate failed: command registry unavailable");
            return;
        }

        if (invokeCommandObject(registry, preferredSender, command)) {
            LOGGER.info("[SpawnManager] populate triggered via plugin CommandRegistry");
            return;
        }

        LOGGER.warning("[SpawnManager] populate failed: no compatible command API found for '/" + command + "'");
    }

    private boolean invokeViaHytaleServer(Object preferredSender, String command) {
        try {
            Class<?> serverCls = Class.forName("com.hypixel.hytale.server.core.HytaleServer");
            Object server = serverCls.getMethod("get").invoke(null);
            if (server == null) return false;

            Object commandManager = server.getClass().getMethod("getCommandManager").invoke(server);
            if (commandManager == null) return false;

            return invokeCommandObject(commandManager, preferredSender, command);
        } catch (Exception e) {
            LOGGER.fine("[SpawnManager] HytaleServer command manager unavailable: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean invokeCommandObject(Object commandObject, Object preferredSender, String command) {
        if (commandObject == null) return false;

        // essayer d'abord avec sender joueur, puis null/console implicite.
        Object[] senders = preferredSender != null ? new Object[]{preferredSender, null} : new Object[]{null};

        for (Object sender : senders) {
            for (Method m : commandObject.getClass().getMethods()) {
                String name = m.getName();
                if (!("handleCommand".equals(name) || "executeCommand".equals(name) || "dispatchCommand".equals(name)
                        || "runCommand".equals(name) || "execute".equals(name))) {
                    continue;
                }

                Class<?>[] p = m.getParameterTypes();
                try {
                    if (p.length == 1 && p[0] == String.class) {
                        m.invoke(commandObject, command);
                        return true;
                    }

                    if (p.length == 2) {
                        // (sender, command)
                        if (p[1] == String.class && (sender == null || p[0].isInstance(sender))) {
                            m.invoke(commandObject, sender, command);
                            return true;
                        }
                        // (command, sender)
                        if (p[0] == String.class && (sender == null || p[1].isInstance(sender))) {
                            m.invoke(commandObject, command, sender);
                            return true;
                        }
                    }
                } catch (Exception ignored) {
                    // on tente la prochaine signature
                }
            }
        }

        return false;
    }
}
