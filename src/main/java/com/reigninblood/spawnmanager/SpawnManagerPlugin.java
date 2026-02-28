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

    public void triggerSpawningPopulate() {
        Object registry = getCommandRegistry();
        if (registry == null) {
            LOGGER.warning("[SpawnManager] populate skipped: command registry unavailable");
            return;
        }

        if (tryInvoke(registry, "executeCommand", "spawning populate")
                || tryInvoke(registry, "dispatchCommand", "spawning populate")
                || tryInvoke(registry, "runCommand", "spawning populate")
                || tryInvoke(registry, "execute", "spawning populate")) {
            LOGGER.info("[SpawnManager] populate triggered: /spawning populate");
            return;
        }

        LOGGER.warning("[SpawnManager] populate not triggered automatically; no compatible command method found");
    }

    private boolean tryInvoke(Object registry, String methodName, String cmd) {
        try {
            Method m = registry.getClass().getMethod(methodName, String.class);
            m.setAccessible(true);
            m.invoke(registry, cmd);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
