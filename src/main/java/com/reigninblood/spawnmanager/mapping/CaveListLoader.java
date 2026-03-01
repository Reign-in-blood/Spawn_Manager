package com.reigninblood.spawnmanager.mapping;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.server.core.asset.AssetModule;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public final class CaveListLoader {
    private static final Logger LOGGER = Logger.getLogger(CaveListLoader.class.getName());
    private static final Gson GSON = new GsonBuilder().create();
    private static final String RELATIVE_CAVE_LIST_PATH = "FileMapping/cave_list.json";

    private CaveListLoader() {
    }

    @Nullable
    public static CaveList loadFromAssetPacks(@Nullable AssetModule assetModule) {
        if (assetModule == null) {
            LOGGER.warning("[SpawnManager] cave list load failed: AssetModule is null");
            return null;
        }

        Path mutableCandidate = null;
        Path anyCandidate = null;

        for (AssetPack pack : assetModule.getAssetPacks()) {
            Path candidate = pack.getRoot().resolve("Server").resolve(RELATIVE_CAVE_LIST_PATH).normalize();
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
            LOGGER.warning("[SpawnManager] cave list file not found: Server/" + RELATIVE_CAVE_LIST_PATH);
            return null;
        }

        try {
            String raw = Files.readString(selected);
            CaveList caveList = GSON.fromJson(raw, CaveList.class);
            if (!isValid(caveList)) {
                LOGGER.severe("[SpawnManager] cave list invalid: " + selected);
                return null;
            }
            LOGGER.info("[SpawnManager] cave list loaded from " + selected + " files=" + caveList.files.size());
            return caveList;
        } catch (Exception e) {
            LOGGER.severe("[SpawnManager] cave list read failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    private static boolean isValid(CaveList caveList) {
        if (caveList == null) return false;
        if (caveList.schemaVersion != 1) return false;
        if (caveList.files == null || caveList.files.isEmpty()) return false;

        int[] replacement = caveList.getDisabledLightRange();
        if (replacement == null || replacement.length != 2) return false;

        for (CaveFileEntry f : caveList.files) {
            if (f == null) return false;
            if (f.path == null || f.path.isBlank()) return false;
            if (f.originalLight == null || f.originalLight.length != 2) return false;
        }
        return true;
    }

    public static final class CaveList {
        public int schemaVersion;
        public int[] DisabledLightRange;
        public int[] disabledLightRange;
        public List<CaveFileEntry> files = new ArrayList<>();

        public int[] getDisabledLightRange() {
            if (disabledLightRange != null && disabledLightRange.length == 2) return disabledLightRange;
            return DisabledLightRange;
        }
    }

    public static final class CaveFileEntry {
        public String path;
        public int[] originalLight;
    }
}
