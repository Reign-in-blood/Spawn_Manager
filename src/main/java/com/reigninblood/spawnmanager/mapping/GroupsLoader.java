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

public final class GroupsLoader {
    private static final Logger LOGGER = Logger.getLogger(GroupsLoader.class.getName());
    private static final Gson GSON = new GsonBuilder().create();
    private static final String RELATIVE_GROUPS_PATH = "FileMapping/groups.json";

    private GroupsLoader() {
    }

    @Nullable
    public static SpawnManagerGroups loadFromAssetPacks(@Nullable AssetModule assetModule) {
        if (assetModule == null) {
            LOGGER.warning("[SpawnManager] groups load skipped: AssetModule is null");
            return null;
        }

        Path mutableCandidate = null;
        Path anyCandidate = null;

        for (AssetPack pack : assetModule.getAssetPacks()) {
            Path candidate = pack.getRoot().resolve("Server").resolve(RELATIVE_GROUPS_PATH).normalize();
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
            LOGGER.info("[SpawnManager] groups file not found: Server/" + RELATIVE_GROUPS_PATH + " (Skeletons fallback enabled)");
            return null;
        }

        try {
            String raw = Files.readString(selected);
            SpawnManagerGroups groups = GSON.fromJson(raw, SpawnManagerGroups.class);
            if (!isValid(groups)) {
                LOGGER.warning("[SpawnManager] groups invalid: " + selected + " (Skeletons fallback enabled)");
                return null;
            }
            LOGGER.info("[SpawnManager] groups loaded from " + selected + " groups=" + groups.groups.size());
            return groups;
        } catch (Exception e) {
            LOGGER.warning("[SpawnManager] groups read failed: " + e.getClass().getSimpleName() + ": " + e.getMessage() + " (Skeletons fallback enabled)");
            return null;
        }
    }

    private static boolean isValid(SpawnManagerGroups groups) {
        if (groups == null) return false;
        if (groups.schemaVersion != 1) return false;
        if (groups.groups == null) return false;

        for (Map.Entry<String, List<String>> entry : groups.groups.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) return false;
            if (entry.getValue() == null) return false;
            for (String mobId : entry.getValue()) {
                if (mobId == null || mobId.isBlank()) return false;
            }
        }
        return true;
    }

    public static final class SpawnManagerGroups {
        public int schemaVersion;
        public Map<String, List<String>> groups = new LinkedHashMap<>();

        public List<String> getGroup(String groupName) {
            List<String> members = groups.get(groupName);
            return members != null ? members : new ArrayList<>();
        }
    }
}
