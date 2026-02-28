package com.reigninblood.spawnmanager.mapping;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.server.core.asset.AssetModule;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class FileMappingLoader {
    private static final Logger LOGGER = Logger.getLogger(FileMappingLoader.class.getName());
    private static final Gson GSON = new GsonBuilder().create();
    private static final String RELATIVE_MAPPING_PATH = "FileMapping/spawn_manager_map.json";

    private FileMappingLoader() {
    }

    @Nullable
    public static SpawnManagerMap loadFromAssetPacks(@Nullable AssetModule assetModule) {
        if (assetModule == null) {
            LOGGER.warning("[SpawnManager] mapping load failed: AssetModule is null");
            return null;
        }

        Path mutableCandidate = null;
        Path anyCandidate = null;

        for (AssetPack pack : assetModule.getAssetPacks()) {
            Path candidate = pack.getRoot().resolve("Server").resolve(RELATIVE_MAPPING_PATH).normalize();
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                if (!pack.isImmutable() && mutableCandidate == null) {
                    mutableCandidate = candidate;
                }
                if (anyCandidate == null) {
                    anyCandidate = candidate;
                }
            }
        }

        Path selected = mutableCandidate != null ? mutableCandidate : anyCandidate;
        if (selected == null) {
            LOGGER.warning("[SpawnManager] mapping file not found: Server/" + RELATIVE_MAPPING_PATH);
            return null;
        }

        try {
            String raw = Files.readString(selected);
            SpawnManagerMap map = GSON.fromJson(raw, SpawnManagerMap.class);
            if (!isValid(map)) {
                LOGGER.severe("[SpawnManager] mapping invalid: " + selected);
                return null;
            }
            LOGGER.info("[SpawnManager] mapping loaded from " + selected + " mobs=" + map.mobs.size());
            return map;
        } catch (Exception e) {
            LOGGER.severe("[SpawnManager] mapping read failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private static boolean isValid(SpawnManagerMap map) {
        if (map == null) return false;
        if (map.schemaVersion != 1) return false;
        if (map.disabledBlockSet == null || map.disabledBlockSet.isBlank()) return false;
        return map.mobs != null;
    }

    public static final class SpawnManagerMap {
        public int schemaVersion;
        public String disabledBlockSet;
        public Map<String, MobMapping> mobs = new LinkedHashMap<>();
    }

    public static final class MobMapping {
        public List<WorldEntry> world = new ArrayList<>();
        public List<String> tags = new ArrayList<>();
    }

    public static final class WorldEntry {
        public String path;
        public String originalSpawnBlockSet;
    }
}
