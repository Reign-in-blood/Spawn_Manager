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
    private static final String RELATIVE_GENERATED_MAPPING_PATH = "FileMapping/spawn_mapping.generated.json";

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
            Path primary = pack.getRoot().resolve("Server").resolve(RELATIVE_MAPPING_PATH).normalize();
            Path generated = pack.getRoot().resolve("Server").resolve(RELATIVE_GENERATED_MAPPING_PATH).normalize();
            Path candidate = null;
            if (Files.exists(generated) && Files.isRegularFile(generated)) {
                candidate = generated;
            } else if (Files.exists(primary) && Files.isRegularFile(primary)) {
                candidate = primary;
            }
            if (candidate != null) {
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
            LOGGER.warning("[SpawnManager] mapping file not found: Server/" + RELATIVE_MAPPING_PATH + " or Server/" + RELATIVE_GENERATED_MAPPING_PATH);
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
        if (map.schemaVersion != 2) return false;
        if (map.getReplacementSpawnBlockSet() == null || map.getReplacementSpawnBlockSet().isBlank()) return false;
        if (map.mobs == null || map.mobs.isEmpty()) return false;

        for (Map.Entry<String, MobMapping> entry : map.mobs.entrySet()) {
            String mobId = entry.getKey();
            MobMapping mob = entry.getValue();
            if (mobId == null || mobId.isBlank() || mob == null) return false;
            if (mob.files == null || mob.files.isEmpty()) return false;
            for (FileEntry f : mob.files) {
                if (f == null) return false;
                if (f.path == null || f.path.isBlank()) return false;
                if (f.originalSpawnBlockSet == null || f.originalSpawnBlockSet.isBlank()) return false;
            }
        }
        return true;
    }

    public static final class SpawnManagerMap {
        public int schemaVersion;
        public String ReplacementSpawnBlockSet;
        public String replacementSpawnBlockSet;
        public Map<String, MobMapping> mobs = new LinkedHashMap<>();

        public String getReplacementSpawnBlockSet() {
            if (replacementSpawnBlockSet != null && !replacementSpawnBlockSet.isBlank()) return replacementSpawnBlockSet;
            return ReplacementSpawnBlockSet;
        }
    }

    public static final class MobMapping {
        public List<FileEntry> files = new ArrayList<>();
    }

    public static final class FileEntry {
        public String path;
        public String originalSpawnBlockSet;
    }
}
