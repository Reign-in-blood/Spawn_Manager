package com.reigninblood.spawnmanager.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.reigninblood.spawnmanager.pages.SpawnManagerPages;

import javax.annotation.Nonnull;

public final class SpawnManagerUICommand extends AbstractPlayerCommand {

    public SpawnManagerUICommand() {
        super("spawnmanager", "Opens Spawn Manager UI", false);
    }

    @Override
    protected void execute(
            @Nonnull CommandContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        Player player = store.getComponent(ref, Player.getComponentType());
        SpawnManagerPages page = new SpawnManagerPages(playerRef);
        player.getPageManager().openCustomPage(ref, store, page);
    }
}