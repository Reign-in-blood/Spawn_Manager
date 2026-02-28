package com.reigninblood.spawnmanager;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;

import com.reigninblood.spawnmanager.command.SpawnManagerCommand;
import com.reigninblood.spawnmanager.command.SpawnManagerUICommand;

public final class SpawnManagerPlugin extends JavaPlugin {
    public SpawnManagerPlugin (com.hypixel.hytale.server.core.plugin.JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void start() {
        getCommandRegistry().registerCommand(new SpawnManagerCommand());
        getCommandRegistry().registerCommand(new SpawnManagerUICommand());
    }
}