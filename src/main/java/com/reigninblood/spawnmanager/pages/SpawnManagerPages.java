package com.reigninblood.spawnmanager.pages;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.BasicCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.reigninblood.spawnmanager.SpawnManagerPlugin;
import com.reigninblood.spawnmanager.config.SpawnManagerConfig;
import com.reigninblood.spawnmanager.mapping.FileMappingLoader;
import com.reigninblood.spawnmanager.mapping.FileMappingLoader.MobMapping;
import com.reigninblood.spawnmanager.mapping.FileMappingLoader.SpawnManagerMap;
import com.reigninblood.spawnmanager.mapping.FileMappingLoader.WorldEntry;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public final class SpawnManagerPages extends BasicCustomUIPage {

    private static final Logger LOGGER = Logger.getLogger(SpawnManagerPages.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    // UI paths (style AnimalTracker: Pages/...)
    private static final String PAGE_PATH = "Pages/SpawnManagerPage.ui";
    private static final String ROW_PATH = "Pages/MobRow.ui";

    // Valeurs
    private static final String KEY = "SpawnBlockSet";

    // Liste affichée
    private final List<String> displayedMobs = new ArrayList<>();

    // Source de vérité UI (chargée depuis config puis modifiée au toggle)
    private final Map<String, Boolean> stagedEnabled = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();

    private final SpawnManagerConfig config;
    private final SpawnManagerMap mapping;

    public SpawnManagerPages(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss);

        SpawnManagerPlugin plugin = SpawnManagerPlugin.get();
        this.config = plugin != null ? plugin.getConfig() : new SpawnManagerConfig(Path.of("SpawnManager"));

        this.mapping = FileMappingLoader.loadFromAssetPacks(AssetModule.get());
        if (mapping != null && mapping.mobs != null) {
            displayedMobs.addAll(mapping.mobs.keySet());
            Collections.sort(displayedMobs);
        }

        initializeFromConfig();
    }

    @Override
    public void build(@Nonnull UICommandBuilder cmd) {
        cmd.append(PAGE_PATH);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {

        cmd.append(PAGE_PATH);

        ensureStagedForDisplayedMobs();
        buildMobList(cmd, events);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#SelectAllButton", EventData.of("Action", "selectAll"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ClearAllButton", EventData.of("Action", "clearAll"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ApplyButton", EventData.of("Action", "apply"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ReloadNpcButton", EventData.of("Action", "reloadNpc"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, String rawData) {
        Map<String, String> data = GSON.fromJson(rawData, MAP_TYPE);
        if (data == null) return;

        String action = data.get("Action");
        if (action == null) return;

        if ("toggleMob".equals(action)) {
            String mobId = data.get("MobId");
            boolean value = parseBool(data.get("@ValueBool"));

            if (mobId != null) {
                synchronized (stagedEnabled) {
                    stagedEnabled.put(mobId, value);
                }
                synchronized (dirty) {
                    dirty.add(mobId);
                }
            }
            return;
        }

        if ("selectAll".equals(action)) {
            synchronized (stagedEnabled) {
                for (String mobId : displayedMobs) {
                    stagedEnabled.put(mobId, true);
                }
            }
            synchronized (dirty) {
                dirty.addAll(displayedMobs);
            }
            rebuild();
            return;
        }

        if ("clearAll".equals(action)) {
            synchronized (stagedEnabled) {
                for (String mobId : displayedMobs) {
                    stagedEnabled.put(mobId, false);
                }
            }
            synchronized (dirty) {
                dirty.addAll(displayedMobs);
            }
            rebuild();
            return;
        }

        if ("apply".equals(action)) {
            final Map<String, Boolean> snapshot;
            final Set<String> dirtySnapshot;
            synchronized (stagedEnabled) {
                snapshot = new HashMap<>(stagedEnabled);
            }
            synchronized (dirty) {
                dirtySnapshot = new HashSet<>(dirty);
            }

            CompletableFuture.runAsync(() -> applyAndSave(snapshot, dirtySnapshot));
            rebuild();
            return;
        }

        if ("reloadNpc".equals(action)) {
            Player player = store.getComponent(ref, Player.getComponentType());
            SpawnManagerPlugin plugin = SpawnManagerPlugin.get();
            if (plugin != null) {
                CompletableFuture.runAsync(() -> plugin.triggerSpawningPopulate(player));
            }
            rebuild();
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        final Map<String, Boolean> snapshot;
        final Set<String> dirtySnapshot;
        synchronized (stagedEnabled) {
            snapshot = new HashMap<>(stagedEnabled);
        }
        synchronized (dirty) {
            dirtySnapshot = new HashSet<>(dirty);
        }
        CompletableFuture.runAsync(() -> applyAndSave(snapshot, dirtySnapshot));
    }

    private void initializeFromConfig() {
        Set<String> disabled = config.getDisabledMobsSnapshot();
        LOGGER.info("[SpawnManager] UI open: disabledMobs loaded=" + disabled.size());
        if (mapping == null) {
            LOGGER.severe("[SpawnManager] UI open: mapping unavailable, apply will be skipped");
        } else {
            LOGGER.info("[SpawnManager] UI open: mapping mobs=" + displayedMobs.size());
        }
        ensureStagedForDisplayedMobs();
    }

    private void ensureStagedForDisplayedMobs() {
        for (String mobId : displayedMobs) {
            stagedEnabled.putIfAbsent(mobId, config.isEnabled(mobId));
        }
    }

    private void buildMobList(UICommandBuilder cmd, UIEventBuilder events) {
        cmd.clear("#MobListContainer");

        for (int i = 0; i < displayedMobs.size(); i++) {
            String mobId = displayedMobs.get(i);

            boolean enabled = stagedEnabled.getOrDefault(mobId, config.isEnabled(mobId));

            String itemPath = "#MobListContainer[" + i + "]";
            cmd.append("#MobListContainer", ROW_PATH);
            cmd.set(itemPath + " #Name.Text", mobId.replace("_", " "));
            cmd.set(itemPath + " #Toggle.Value", enabled);

            events.addEventBinding(
                    CustomUIEventBindingType.ValueChanged,
                    itemPath + " #Toggle",
                    EventData.of("Action", "toggleMob")
                            .append("MobId", mobId)
                            .put("@ValueBool", itemPath + " #Toggle.Value"),
                    false
            );
        }
    }

    private static boolean parseBool(String s) {
        if (s == null) return false;
        return s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("yes");
    }

    private void applyAndSave(Map<String, Boolean> stagedSnapshot, Set<String> dirtySnapshot) {
        int dirtyCount = dirtySnapshot.size();
        LOGGER.info("[SpawnManager] UI close/apply async: dirty mobs=" + dirtyCount);

        applyChanges(stagedSnapshot, dirtySnapshot);

        for (Map.Entry<String, Boolean> e : stagedSnapshot.entrySet()) {
            config.setEnabled(e.getKey(), e.getValue());
        }
        config.save();

        synchronized (dirty) {
            dirty.removeAll(dirtySnapshot);
        }
    }

    private void applyChanges(Map<String, Boolean> stagedSnapshot, Set<String> dirtySnapshot) {
        if (mapping == null || mapping.mobs == null) {
            LOGGER.severe("[SpawnManager] apply skipped: mapping is unavailable or invalid");
            return;
        }

        for (String mobId : dirtySnapshot) {
            MobMapping mobMapping = mapping.mobs.get(mobId);
            if (mobMapping == null || mobMapping.world == null || mobMapping.world.isEmpty()) {
                LOGGER.warning("[SpawnManager] apply: no world mapping for mob=" + mobId);
                continue;
            }

            boolean enabled = stagedSnapshot.getOrDefault(mobId, true);
            for (WorldEntry entry : mobMapping.world) {
                if (entry == null || entry.path == null || entry.path.isBlank()) {
                    LOGGER.warning("[SpawnManager] apply: invalid world entry for mob=" + mobId);
                    continue;
                }

                String targetSpawnBlockSet = enabled
                        ? entry.originalSpawnBlockSet
                        : resolveDisabledBlockSet(mapping.disabledBlockSet, mobId, entry.path);

                if (targetSpawnBlockSet == null || targetSpawnBlockSet.isBlank()) {
                    LOGGER.warning("[SpawnManager] apply: missing target SpawnBlockSet for mob=" + mobId + " path=" + entry.path);
                    continue;
                }

                Path targetFile = locateWorldPathInAssetPacks(AssetModule.get(), entry.path);
                if (targetFile == null) {
                    LOGGER.warning("[SpawnManager] apply: file not found for mob=" + mobId + " path=Server/" + entry.path);
                    continue;
                }

                try {
                    PatchOutcome outcome = setMobSpawnBlockSetInFile(targetFile, mobId, targetSpawnBlockSet);
                    if (!outcome.foundMobId) {
                        LOGGER.warning("[SpawnManager] apply: Id not found in NPCs for mob=" + mobId + " file=" + targetFile);
                    }
                } catch (Exception e) {
                    LOGGER.warning("[SpawnManager] apply: failed for mob=" + mobId + " file=" + targetFile + " error=" + e.getMessage());
                }
            }
        }
    }
    private static String resolveDisabledBlockSet(String desiredDisabledBlockSet, String mobId, String path) {
        if (desiredDisabledBlockSet == null || desiredDisabledBlockSet.isBlank()) {
            LOGGER.warning("[SpawnManager] mapping disabledBlockSet is blank for mob=" + mobId + " path=" + path);
            return null;
        }
        return desiredDisabledBlockSet;
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

        // nettoyage: évite SpawnBlockSet au root
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

    private record PatchOutcome(boolean foundMobId, boolean modified) {
    }
}
