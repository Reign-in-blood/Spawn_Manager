package com.reigninblood.spawnmanager.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Config globale du mod.
 * Objectif: stocker l'état enabled/disabled par mobId pour l'UI.
 */
public final class SpawnManagerConfig {

    private static final Logger LOGGER = Logger.getLogger(SpawnManagerConfig.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ---- PERSISTENCE ----
    private final Path configDir;
    private final Path configFile;

    private GlobalConfig globalConfig;

    public SpawnManagerConfig(@Nonnull Path configDir) {
        this.configDir = configDir;
        this.configFile = configDir.resolve("config.json");
        this.globalConfig = new GlobalConfig();
    }

    public void load() {
        try {
            Files.createDirectories(configDir);

            if (Files.exists(configFile, new LinkOption[0])) {
                try (BufferedReader reader = Files.newBufferedReader(configFile)) {
                    GlobalConfig loaded = GSON.fromJson(reader, GlobalConfig.class);
                    this.globalConfig = (loaded != null) ? loaded : new GlobalConfig();
                }
            } else {
                save();
            }

            LOGGER.info("[SpawnManager] Config loaded");
        } catch (Exception e) {
            LOGGER.warning("[SpawnManager] Failed to load config: " + e.getMessage());
            this.globalConfig = new GlobalConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(configDir);
            try (BufferedWriter w = Files.newBufferedWriter(configFile)) {
                GSON.toJson(this.globalConfig, w);
            }
            LOGGER.info("[SpawnManager] Config saved");
        } catch (IOException | JsonSyntaxException e) {
            LOGGER.warning("[SpawnManager] Failed to save config: " + e.getMessage());
        }
    }

    // ---- API UTILISÉE PAR L'UI ----

    /** Par défaut: si pas dans disabled => enabled */
    public boolean isEnabled(String mobId) {
        if (mobId == null) return true;
        return !globalConfig.disabledMobs.contains(mobId);
    }

    public void setEnabled(String mobId, boolean enabled) {
        if (mobId == null) return;
        if (enabled) globalConfig.disabledMobs.remove(mobId);
        else globalConfig.disabledMobs.add(mobId);
    }

    public Set<String> getDisabledMobsSnapshot() {
        return new HashSet<>(globalConfig.disabledMobs);
    }

    public void retainDisabledMobs(Set<String> knownMobIds) {
        if (knownMobIds == null) return;
        globalConfig.disabledMobs.retainAll(knownMobIds);
    }


    public boolean isCaveNpcEnabled() {
        return globalConfig.caveNpcEnabled;
    }

    public void setCaveNpcEnabled(boolean enabled) {
        globalConfig.caveNpcEnabled = enabled;
    }

    public Set<String> getActiveGroupsSnapshot() {
        return new LinkedHashSet<>(globalConfig.activeGroups);
    }

    public void setActiveGroups(Set<String> groups) {
        globalConfig.activeGroups.clear();
        if (groups != null) {
            globalConfig.activeGroups.addAll(groups);
        }
    }

    // ---- MODEL JSON ----
    public static final class GlobalConfig {
        /** On stocke uniquement les OFF (plus compact et plus stable) */
        public Set<String> disabledMobs = new LinkedHashSet<>();
        public boolean caveNpcEnabled = true;
        public Set<String> activeGroups = new LinkedHashSet<>();
    }
}