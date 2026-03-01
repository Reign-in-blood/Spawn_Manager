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
import com.reigninblood.spawnmanager.mapping.FileMappingLoader.FileEntry;
import com.reigninblood.spawnmanager.mapping.FileMappingLoader.MobMapping;
import com.reigninblood.spawnmanager.mapping.FileMappingLoader.SpawnManagerMap;
import com.reigninblood.spawnmanager.mapping.GroupsLoader;
import com.reigninblood.spawnmanager.mapping.GroupsLoader.SpawnManagerGroups;

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

    private static final String PAGE_PATH = "Pages/SpawnManagerPage.ui";
    private static final String ROW_PATH = "Pages/MobRow.ui";
    private static final String KEY = "SpawnBlockSet";

    private final List<String> displayedMobs = new ArrayList<>();
    private String currentSearchFilter = "";
    private final Map<String, Boolean> stagedEnabled = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();
    private final Set<String> activeGroups = new LinkedHashSet<>();

    private final SpawnManagerConfig config;
    private final SpawnManagerMap mapping;
    private final SpawnManagerGroups groups;

    public SpawnManagerPages(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss);

        SpawnManagerPlugin plugin = SpawnManagerPlugin.get();
        this.config = plugin != null ? plugin.getConfig() : new SpawnManagerConfig(Path.of("SpawnManager"));

        this.mapping = FileMappingLoader.loadFromAssetPacks(AssetModule.get());
        this.groups = GroupsLoader.loadFromAssetPacks(AssetModule.get());
        rebuildDisplayedMobs();

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

        rebuildDisplayedMobs();
        ensureStagedForDisplayedMobs();
        if (currentSearchFilter != null && !currentSearchFilter.isBlank()) {
            cmd.set("#MobSearchField.Value", currentSearchFilter);
        }
        applyFilterButtonVisibility(cmd);
        buildMobList(cmd, events);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#SearchBtn", EventData.of("Action", "search").put("@MobSearchField", "#MobSearchField.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ClearSearchBtn", EventData.of("Action", "clearSearch"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SelectAllButton", EventData.of("Action", "selectAll"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ClearAllButton", EventData.of("Action", "clearAll"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ApplyButton", EventData.of("Action", "apply"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ReloadNpcButton", EventData.of("Action", "reloadNpc"), false);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterTerrestrialOff", EventData.of("Action", "toggleFilter").append("Value", "Terrestrial"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterTerrestrialOn", EventData.of("Action", "toggleFilter").append("Value", "Terrestrial"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterAquaticOff", EventData.of("Action", "toggleFilter").append("Value", "Aquatic"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterAquaticOn", EventData.of("Action", "toggleFilter").append("Value", "Aquatic"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterFlyingOff", EventData.of("Action", "toggleFilter").append("Value", "Flying"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterFlyingOn", EventData.of("Action", "toggleFilter").append("Value", "Flying"), false);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterSkeletonOff", EventData.of("Action", "toggleFilter").append("Value", "Skeleton"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterSkeletonOn", EventData.of("Action", "toggleFilter").append("Value", "Skeleton"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterScarakOff", EventData.of("Action", "toggleFilter").append("Value", "Scarak"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterScarakOn", EventData.of("Action", "toggleFilter").append("Value", "Scarak"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterVoidOff", EventData.of("Action", "toggleFilter").append("Value", "Void"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterVoidOn", EventData.of("Action", "toggleFilter").append("Value", "Void"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterGolemOff", EventData.of("Action", "toggleFilter").append("Value", "Golem"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterGolemOn", EventData.of("Action", "toggleFilter").append("Value", "Golem"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterTrorkOff", EventData.of("Action", "toggleFilter").append("Value", "Trork"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterTrorkOn", EventData.of("Action", "toggleFilter").append("Value", "Trork"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterOutlanderOff", EventData.of("Action", "toggleFilter").append("Value", "Outlander"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterOutlanderOn", EventData.of("Action", "toggleFilter").append("Value", "Outlander"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterGoblinOff", EventData.of("Action", "toggleFilter").append("Value", "Goblin"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterGoblinOn", EventData.of("Action", "toggleFilter").append("Value", "Goblin"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterUndeadOff", EventData.of("Action", "toggleFilter").append("Value", "Undead"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterUndeadOn", EventData.of("Action", "toggleFilter").append("Value", "Undead"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterSpiritOff", EventData.of("Action", "toggleFilter").append("Value", "Spirit"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterSpiritOn", EventData.of("Action", "toggleFilter").append("Value", "Spirit"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterDinosaursOff", EventData.of("Action", "toggleFilter").append("Value", "Dinosaurs"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterDinosaursOn", EventData.of("Action", "toggleFilter").append("Value", "Dinosaurs"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterFenOff", EventData.of("Action", "toggleFilter").append("Value", "Fen"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterFenOn", EventData.of("Action", "toggleFilter").append("Value", "Fen"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterDragonOff", EventData.of("Action", "toggleFilter").append("Value", "Dragon"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterDragonOn", EventData.of("Action", "toggleFilter").append("Value", "Dragon"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterBossOff", EventData.of("Action", "toggleFilter").append("Value", "Boss"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterBossOn", EventData.of("Action", "toggleFilter").append("Value", "Boss"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterOtherOff", EventData.of("Action", "toggleFilter").append("Value", "Other"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterOtherOn", EventData.of("Action", "toggleFilter").append("Value", "Other"), false);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterKweebecOff", EventData.of("Action", "toggleFilter").append("Value", "Kweebec"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterKweebecOn", EventData.of("Action", "toggleFilter").append("Value", "Kweebec"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterFeranOff", EventData.of("Action", "toggleFilter").append("Value", "Feran"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterFeranOn", EventData.of("Action", "toggleFilter").append("Value", "Feran"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterKlopsOff", EventData.of("Action", "toggleFilter").append("Value", "Klops"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterKlopsOn", EventData.of("Action", "toggleFilter").append("Value", "Klops"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterTempleOff", EventData.of("Action", "toggleFilter").append("Value", "Temple"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterTempleOn", EventData.of("Action", "toggleFilter").append("Value", "Temple"), false);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterZone0Off", EventData.of("Action", "toggleFilter").append("Value", "Zone 0"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterZone0On", EventData.of("Action", "toggleFilter").append("Value", "Zone 0"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterZone1Off", EventData.of("Action", "toggleFilter").append("Value", "Zone 1"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterZone1On", EventData.of("Action", "toggleFilter").append("Value", "Zone 1"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterZone2Off", EventData.of("Action", "toggleFilter").append("Value", "Zone 2"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterZone2On", EventData.of("Action", "toggleFilter").append("Value", "Zone 2"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterZone3Off", EventData.of("Action", "toggleFilter").append("Value", "Zone 3"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterZone3On", EventData.of("Action", "toggleFilter").append("Value", "Zone 3"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterZone4Off", EventData.of("Action", "toggleFilter").append("Value", "Zone 4"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterZone4On", EventData.of("Action", "toggleFilter").append("Value", "Zone 4"), false);
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

        if ("search".equals(action)) {
            String search = data.get("@MobSearchField");
            currentSearchFilter = search != null ? search.trim() : "";
            rebuildDisplayedMobs();
            rebuild();
            return;
        }

        if ("clearSearch".equals(action)) {
            currentSearchFilter = "";
            rebuildDisplayedMobs();
            rebuild();
            return;
        }

        if ("toggleFilter".equals(action)) {
            String filterName = data.get("Value");
            toggleFilter(filterName);
            rebuild();
            return;
        }

        if ("selectAll".equals(action)) {
            activeGroups.clear();
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
            activeGroups.clear();
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

    private void toggleFilter(String filterName) {
        if (filterName == null || filterName.isBlank() || mapping == null || mapping.mobs == null) {
            return;
        }

        String groupName = filterName.trim();
        Set<String> groupMobIds = resolveFilterMobIds(groupName);

        if (activeGroups.contains(groupName)) {
            activeGroups.remove(groupName);
            synchronized (stagedEnabled) {
                synchronized (dirty) {
                    for (String mobId : groupMobIds) {
                        if (!isCoveredByAnyActiveGroup(mobId)) {
                            boolean previous = stagedEnabled.getOrDefault(mobId, config.isEnabled(mobId));
                            if (previous) {
                                stagedEnabled.put(mobId, false);
                                dirty.add(mobId);
                            }
                        }
                    }
                }
            }
            return;
        }

        activeGroups.add(groupName);
        if (groupMobIds.isEmpty()) {
            LOGGER.info("[SpawnManager] toggleFilter: group '" + groupName + "' has no mapped mobs");
            return;
        }

        synchronized (stagedEnabled) {
            synchronized (dirty) {
                for (String mobId : groupMobIds) {
                    boolean previous = stagedEnabled.getOrDefault(mobId, config.isEnabled(mobId));
                    if (!previous) {
                        stagedEnabled.put(mobId, true);
                        dirty.add(mobId);
                    }
                }
            }
        }
    }

    private boolean isCoveredByAnyActiveGroup(String mobId) {
        for (String activeGroup : activeGroups) {
            Set<String> covered = resolveFilterMobIds(activeGroup);
            if (covered.contains(mobId)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> resolveFilterMobIds(String filterName) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (mapping == null || mapping.mobs == null || filterName == null || filterName.isBlank()) {
            return selected;
        }

        selected.addAll(resolveConfiguredGroup(filterName));

        if (selected.isEmpty()) {
            String normalized = filterName.trim().toLowerCase(Locale.ROOT);
            if ("skeleton".equals(normalized) || "skeletons".equals(normalized) || "monsters".equals(normalized) || "enemies".equals(normalized)) {
                selected.addAll(resolveSkeletonFallback());
            }
        }

        return selected;
    }

    private Set<String> resolveConfiguredGroup(String groupName) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (groups == null || groups.groups == null || mapping == null || mapping.mobs == null || groupName == null) {
            return selected;
        }

        List<String> configured = groups.groups.get(groupName);
        if (configured == null) {
            for (Map.Entry<String, List<String>> entry : groups.groups.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(groupName)) {
                    configured = entry.getValue();
                    break;
                }
            }
        }

        if (configured == null) {
            return selected;
        }

        for (String mobId : configured) {
            if (mobId != null && mapping.mobs.containsKey(mobId)) {
                selected.add(mobId);
            }
        }

        return selected;
    }

    private Set<String> resolveSkeletonFallback() {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        if (mapping == null || mapping.mobs == null) {
            return selected;
        }

        for (String mobId : mapping.mobs.keySet()) {
            if (mobId != null && mobId.startsWith("Skeleton")) {
                selected.add(mobId);
            }
        }
        return selected;
    }

    private void applyFilterButtonVisibility(UICommandBuilder cmd) {
        setFilterButtonVisibility(cmd, "#FilterTerrestrial", activeGroups.contains("Terrestrial"));
        setFilterButtonVisibility(cmd, "#FilterAquatic", activeGroups.contains("Aquatic"));
        setFilterButtonVisibility(cmd, "#FilterFlying", activeGroups.contains("Flying"));

        setFilterButtonVisibility(cmd, "#FilterSkeleton", activeGroups.contains("Skeleton"));
        setFilterButtonVisibility(cmd, "#FilterScarak", activeGroups.contains("Scarak"));
        setFilterButtonVisibility(cmd, "#FilterVoid", activeGroups.contains("Void"));
        setFilterButtonVisibility(cmd, "#FilterGolem", activeGroups.contains("Golem"));
        setFilterButtonVisibility(cmd, "#FilterTrork", activeGroups.contains("Trork"));
        setFilterButtonVisibility(cmd, "#FilterOutlander", activeGroups.contains("Outlander"));
        setFilterButtonVisibility(cmd, "#FilterGoblin", activeGroups.contains("Goblin"));
        setFilterButtonVisibility(cmd, "#FilterUndead", activeGroups.contains("Undead"));
        setFilterButtonVisibility(cmd, "#FilterSpirit", activeGroups.contains("Spirit"));
        setFilterButtonVisibility(cmd, "#FilterDinosaurs", activeGroups.contains("Dinosaurs"));
        setFilterButtonVisibility(cmd, "#FilterFen", activeGroups.contains("Fen"));
        setFilterButtonVisibility(cmd, "#FilterDragon", activeGroups.contains("Dragon"));
        setFilterButtonVisibility(cmd, "#FilterBoss", activeGroups.contains("Boss"));
        setFilterButtonVisibility(cmd, "#FilterOther", activeGroups.contains("Other"));

        setFilterButtonVisibility(cmd, "#FilterKweebec", activeGroups.contains("Kweebec"));
        setFilterButtonVisibility(cmd, "#FilterFeran", activeGroups.contains("Feran"));
        setFilterButtonVisibility(cmd, "#FilterKlops", activeGroups.contains("Klops"));
        setFilterButtonVisibility(cmd, "#FilterTemple", activeGroups.contains("Temple"));

        setFilterButtonVisibility(cmd, "#FilterZone0", activeGroups.contains("Zone 0"));
        setFilterButtonVisibility(cmd, "#FilterZone1", activeGroups.contains("Zone 1"));
        setFilterButtonVisibility(cmd, "#FilterZone2", activeGroups.contains("Zone 2"));
        setFilterButtonVisibility(cmd, "#FilterZone3", activeGroups.contains("Zone 3"));
        setFilterButtonVisibility(cmd, "#FilterZone4", activeGroups.contains("Zone 4"));
    }

    private static void setFilterButtonVisibility(UICommandBuilder cmd, String baseSelector, boolean active) {
        cmd.set(baseSelector + "On.Visible", active);
        cmd.set(baseSelector + "Off.Visible", !active);
    }

    private void rebuildDisplayedMobs() {
        displayedMobs.clear();
        if (mapping == null || mapping.mobs == null || mapping.mobs.isEmpty()) return;

        List<String> allMobs = new ArrayList<>(mapping.mobs.keySet());
        if (currentSearchFilter == null || currentSearchFilter.isBlank()) {
            Collections.sort(allMobs);
            displayedMobs.addAll(allMobs);
            return;
        }

        String query = currentSearchFilter.trim();
        List<ScoredMob> scored = new ArrayList<>();
        for (String mobId : allMobs) {
            int score = calculateSearchScore(mobId, query);
            if (score > 0) scored.add(new ScoredMob(mobId, score));
        }

        scored.sort((a, b) -> a.score != b.score ? Integer.compare(b.score, a.score) : a.name.compareTo(b.name));
        for (ScoredMob s : scored) displayedMobs.add(s.name);
    }

    private static int calculateSearchScore(String mobName, String query) {
        String lowerName = mobName.toLowerCase().replace("_", " ");
        String lowerQuery = query.toLowerCase();
        String lowerIdName = mobName.toLowerCase();

        if (lowerName.equals(lowerQuery) || lowerIdName.equals(lowerQuery)) return 100;
        if (lowerName.startsWith(lowerQuery) || lowerIdName.startsWith(lowerQuery)) return 80;

        for (String word : lowerName.split("\\s+")) {
            if (word.startsWith(lowerQuery)) return 60;
        }

        if (lowerName.contains(lowerQuery)) return 40;
        if (lowerIdName.contains(lowerQuery)) return 20;
        return 0;
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
            if (mobMapping == null || mobMapping.files == null || mobMapping.files.isEmpty()) {
                LOGGER.warning("[SpawnManager] apply: no file mapping for mob=" + mobId);
                continue;
            }

            boolean enabled = stagedSnapshot.getOrDefault(mobId, true);
            String replacementSpawnBlockSet = mapping.getReplacementSpawnBlockSet();
            if (!enabled && (replacementSpawnBlockSet == null || replacementSpawnBlockSet.isBlank())) {
                LOGGER.warning("[SpawnManager] apply: ReplacementSpawnBlockSet missing in mapping for mob=" + mobId);
                continue;
            }

            for (FileEntry fileEntry : mobMapping.files) {
                if (fileEntry == null) {
                    LOGGER.warning("[SpawnManager] apply: null file entry for mob=" + mobId);
                    continue;
                }

                String mappedPath = fileEntry.path;
                String relativePath = normalizeMappedPath(mappedPath);
                if (relativePath == null) {
                    LOGGER.warning("[SpawnManager] apply: invalid path for mob=" + mobId + " raw='" + mappedPath + "'");
                    continue;
                }

                String targetSpawnBlockSet = enabled ? fileEntry.originalSpawnBlockSet : replacementSpawnBlockSet;
                if (targetSpawnBlockSet == null || targetSpawnBlockSet.isBlank()) {
                    LOGGER.warning("[SpawnManager] apply: missing target SpawnBlockSet for mob=" + mobId + " file=" + mappedPath);
                    continue;
                }

                Path targetFile = locateWorldPathInAssetPacks(AssetModule.get(), relativePath);
                if (targetFile == null) {
                    LOGGER.warning("[SpawnManager] apply: file not found for mob=" + mobId + " path=Server/" + relativePath);
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

    private static String normalizeMappedPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return null;

        String normalized = rawPath.replace('\\', '/').trim();
        if (normalized.startsWith("src/main/resources/Server/")) {
            return normalized.substring("src/main/resources/Server/".length());
        }
        if (normalized.startsWith("main/resources/Server/")) {
            return normalized.substring("main/resources/Server/".length());
        }
        if (normalized.startsWith("Server/")) {
            return normalized.substring("Server/".length());
        }
        return normalized;
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

    private record ScoredMob(String name, int score) {
    }

    private record PatchOutcome(boolean foundMobId, boolean modified) {
    }
}
