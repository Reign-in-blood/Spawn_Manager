package com.reigninblood.spawnmanager.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.reigninblood.spawnmanager.SpawnManagerPlugin;
import com.reigninblood.spawnmanager.config.SpawnManagerConfig;
import com.reigninblood.spawnmanager.mapping.FileMappingLoader;
import com.reigninblood.spawnmanager.mapping.FileMappingLoader.MobMapping;
import com.reigninblood.spawnmanager.mapping.FileMappingLoader.SpawnManagerMap;
import com.reigninblood.spawnmanager.mapping.FileMappingLoader.WorldEntry;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.logging.Logger;

public final class SpawnManagerCommand extends AbstractPlayerCommand {

    private static final Logger LOGGER = Logger.getLogger(SpawnManagerCommand.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String KEY = "SpawnBlockSet";

    public SpawnManagerCommand() {
        super("spawnmanager", "Apply spawn_manager_map.json values using current SpawnManager config", false);
    }

    @Override
    protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
    ) {
        try {
            SpawnManagerPlugin plugin = SpawnManagerPlugin.get();
            SpawnManagerConfig config = plugin != null ? plugin.getConfig() : null;
            if (config == null) {
                LOGGER.warning("[SpawnManager] /spawnmanager: Config unavailable");
                return;
            }

            SpawnManagerMap mapping = FileMappingLoader.loadFromAssetPacks(AssetModule.get());
            if (mapping == null || mapping.mobs == null) {
                LOGGER.warning("[SpawnManager] /spawnmanager: Mapping unavailable or invalid");
                return;
            }

            int filesPatched = 0;
            int filesMissing = 0;
            int idsMissing = 0;

            for (var entry : mapping.mobs.entrySet()) {
                String mobId = entry.getKey();
                MobMapping mobMapping = entry.getValue();
                if (mobMapping == null || mobMapping.world == null) continue;

                boolean enabled = config.isEnabled(mobId);
                for (WorldEntry worldEntry : mobMapping.world) {
                    if (worldEntry == null || worldEntry.path == null || worldEntry.path.isBlank()) continue;

                    String value = enabled ? worldEntry.originalSpawnBlockSet : mapping.disabledBlockSet;
                    if (value == null || value.isBlank()) continue;

                    Path file = locateWorldPathInAssetPacks(AssetModule.get(), worldEntry.path);
                    if (file == null) {
                        filesMissing++;
                        continue;
                    }

                    PatchOutcome outcome = setMobSpawnBlockSetInFile(file, mobId, value);
                    if (!outcome.foundMobId) {
                        idsMissing++;
                    }
                    if (outcome.modified) {
                        filesPatched++;
                    }
                }
            }

            LOGGER.info("[SpawnManager] /spawnmanager done. patched=" + filesPatched + ", missingFiles=" + filesMissing + ", missingIds=" + idsMissing);
        } catch (Exception e) {
            LOGGER.warning("[SpawnManager] /spawnmanager error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Path locateWorldPathInAssetPacks(AssetModule assetModule, String relativeUnderServer) {
        if (assetModule == null || relativeUnderServer == null) return null;

        Path foundMutable = null;
        Path foundAny = null;

        for (AssetPack pack : assetModule.getAssetPacks()) {
            Path root = pack.getRoot();
            boolean immutable = pack.isImmutable();

            Path candidate = root.resolve("Server").resolve(relativeUnderServer).normalize();
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                if (!immutable && foundMutable == null) foundMutable = candidate;
                if (foundAny == null) foundAny = candidate;
            }
        }

        return (foundMutable != null) ? foundMutable : foundAny;
    }

    private static PatchOutcome setMobSpawnBlockSetInFile(Path file, String mobId, String spawnBlockSetValue) throws IOException {
        String json = Files.readString(file);
        JsonElement el = JsonParser.parseString(json);
        if (!el.isJsonObject()) return new PatchOutcome(false, false);

        JsonObject root = el.getAsJsonObject();
        if (root.has(KEY)) root.remove(KEY);

        if (!root.has("NPCs") || !root.get("NPCs").isJsonArray()) return new PatchOutcome(false, false);
        JsonArray npcs = root.getAsJsonArray("NPCs");

        boolean found = false;
        boolean modified = false;

        for (JsonElement npcEl : npcs) {
            if (!npcEl.isJsonObject()) continue;
            JsonObject npc = npcEl.getAsJsonObject();
            if (!npc.has("Id") || !npc.get("Id").isJsonPrimitive()) continue;

            String id = npc.get("Id").getAsString();
            if (mobId.equals(id)) {
                found = true;
                String before = npc.has(KEY) && npc.get(KEY).isJsonPrimitive() ? npc.get(KEY).getAsString() : null;
                if (!Objects.equals(before, spawnBlockSetValue)) {
                    npc.addProperty(KEY, spawnBlockSetValue);
                    modified = true;
                }
                break;
            }
        }

        if (modified) {
            writeAtomic(file, GSON.toJson(root));
        }

        return new PatchOutcome(found, modified);
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.writeString(tmp, content);

        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record PatchOutcome(boolean foundMobId, boolean modified) {}
}
