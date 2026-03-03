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
import com.reigninblood.spawnmanager.mapping.CaveListLoader;
import com.reigninblood.spawnmanager.mapping.CaveListLoader.CaveFileEntry;
import com.reigninblood.spawnmanager.mapping.CaveListLoader.CaveList;
import com.reigninblood.spawnmanager.mapping.FileMappingLoader;
import com.reigninblood.spawnmanager.mapping.FileMappingLoader.FileEntry;
import com.reigninblood.spawnmanager.mapping.FileMappingLoader.MobMapping;
import com.reigninblood.spawnmanager.mapping.FileMappingLoader.MarkerEntry;
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
    private static final String SPAWN_BLOCK_SET_KEY = "SpawnBlockSet";
    private static final String SPAWN_FLUID_TAG_KEY = "SpawnFluidTag";
    private static final String MARKER_KEY = "DeactivationDistance";
    private static final Set<String> ALL_FILTER_GROUPS = new LinkedHashSet<>(List.of(
            "Terrestrial", "Aquatic", "Flying",
            "Skeleton", "Scarak", "Void", "Golem", "Trork", "Outlander", "Goblin", "Undead", "Spirit",
            "Dinosaur", "Fen", "Dragon", "Boss", "Other",
            "Kweebec", "Feran", "Klops", "Temple"
    ));

    private final List<String> displayedMobs = new ArrayList<>();
    private String currentSearchFilter = "";
    private final Map<String, Boolean> stagedEnabled = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();
    private final Set<String> activeGroups = new LinkedHashSet<>();

    private final SpawnManagerConfig config;
    private final SpawnManagerMap mapping;
    private final SpawnManagerGroups groups;
    private final CaveList caveList;

    private volatile boolean caveNpcEnabled = true;

    public SpawnManagerPages(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss);

        SpawnManagerPlugin plugin = SpawnManagerPlugin.get();
        this.config = plugin != null ? plugin.getConfig() : new SpawnManagerConfig(Path.of("SpawnManager"));

        this.mapping = FileMappingLoader.loadFromAssetPacks(AssetModule.get());
        this.groups = GroupsLoader.loadFromAssetPacks(AssetModule.get());
        this.caveList = CaveListLoader.loadFromAssetPacks(AssetModule.get());
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
        applyCaveNpcButtonVisibility(cmd);
        buildMobList(cmd, events);

        events.addEventBinding(CustomUIEventBindingType.Activating, "#SearchBtn", EventData.of("Action", "search").put("@MobSearchField", "#MobSearchField.Value"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ClearSearchBtn", EventData.of("Action", "clearSearch"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SelectAllButton", EventData.of("Action", "selectAll"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ClearAllButton", EventData.of("Action", "clearAll"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ApplyButton", EventData.of("Action", "apply"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ReloadNpcButton", EventData.of("Action", "reloadNpc"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CaveNpcOnButton", EventData.of("Action", "toggleCaveNpc"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CaveNpcOffButton", EventData.of("Action", "toggleCaveNpc"), false);

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
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterDinosaurOff", EventData.of("Action", "toggleFilter").append("Value", "Dinosaur"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#FilterDinosaurOn", EventData.of("Action", "toggleFilter").append("Value", "Dinosaur"), false);
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
            activeGroups.addAll(ALL_FILTER_GROUPS);
            persistActiveGroups();
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
            persistActiveGroups();
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
            final Player player = store.getComponent(ref, Player.getComponentType());
            final SpawnManagerPlugin plugin = SpawnManagerPlugin.get();
            synchronized (stagedEnabled) {
                snapshot = new HashMap<>(stagedEnabled);
            }
            synchronized (dirty) {
                dirtySnapshot = new HashSet<>(dirty);
            }

            CompletableFuture.runAsync(() -> {
                applyAndSave(snapshot, dirtySnapshot);
                if (plugin != null) {
                    plugin.triggerSpawningPopulate(player);
                }
            }).exceptionally(error -> {
                LOGGER.warning("[SpawnManager] apply async failed: " + error.getMessage());
                return null;
            });
            rebuild();
            return;
        }


        if ("toggleCaveNpc".equals(action)) {
            caveNpcEnabled = !caveNpcEnabled;
            boolean targetEnabled = caveNpcEnabled;
            final Player player = store.getComponent(ref, Player.getComponentType());
            final SpawnManagerPlugin plugin = SpawnManagerPlugin.get();
            config.setCaveNpcEnabled(targetEnabled);
            config.save();
            CompletableFuture.runAsync(() -> {
                applyCaveNpcLightRanges(targetEnabled);
                if (plugin != null) {
                    plugin.triggerSpawningPopulate(player);
                }
            }).exceptionally(error -> {
                LOGGER.warning("[SpawnManager] cave toggle async failed: " + error.getMessage());
                return null;
            });
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
        caveNpcEnabled = config.isCaveNpcEnabled();
        activeGroups.clear();
        for (String g : config.getActiveGroupsSnapshot()) {
            if (ALL_FILTER_GROUPS.contains(g)) {
                activeGroups.add(g);
            }
        }
        ensureStagedForDisplayedMobs();
        if (activeGroups.isEmpty() && areAllDisplayedMobsEnabled()) {
            activeGroups.addAll(ALL_FILTER_GROUPS);
            persistActiveGroups();
        }
    }

    private void toggleFilter(String filterName) {
        if (filterName == null || filterName.isBlank() || mapping == null || mapping.mobs == null) {
            return;
        }

        String groupName = filterName.trim();
        Set<String> groupMobIds = resolveFilterMobIds(groupName);

        if (activeGroups.contains(groupName)) {
            activeGroups.remove(groupName);
            persistActiveGroups();
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
        persistActiveGroups();
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

    private void persistActiveGroups() {
        config.setActiveGroups(new LinkedHashSet<>(activeGroups));
        config.save();
    }

    private boolean areAllDisplayedMobsEnabled() {
        synchronized (stagedEnabled) {
            for (String mobId : displayedMobs) {
                if (!stagedEnabled.getOrDefault(mobId, config.isEnabled(mobId))) {
                    return false;
                }
            }
        }
        return true;
    }

    private void applyCaveNpcButtonVisibility(UICommandBuilder cmd) {
        boolean showOn = caveNpcEnabled;
        cmd.set("#CaveNpcOnButton.Visible", showOn);
        cmd.set("#CaveNpcOffButton.Visible", !showOn);
    }

    private void applyCaveNpcLightRanges(boolean enabled) {
        if (caveList == null || caveList.files == null || caveList.files.isEmpty()) {
            LOGGER.warning("[SpawnManager] cave toggle skipped: cave_list mapping unavailable");
            return;
        }

        int[] disabledRange = caveList.getDisabledLightRange();
        if (!enabled && (disabledRange == null || disabledRange.length != 2)) {
            LOGGER.warning("[SpawnManager] cave toggle skipped: disabled light range missing/invalid");
            return;
        }

        int patched = 0;
        int missing = 0;
        for (CaveFileEntry entry : caveList.files) {
            if (entry == null || entry.path == null || entry.path.isBlank()) continue;

            String relativePath = normalizeMappedPath(entry.path);
            if (relativePath == null) continue;

            Path targetFile = locateWorldPathInAssetPacks(AssetModule.get(), relativePath);
            if (targetFile == null) {
                missing++;
                LOGGER.warning("[SpawnManager] cave toggle: file not found path=Server/" + relativePath);
                continue;
            }

            int[] range = enabled ? entry.originalLight : disabledRange;
            if (range == null || range.length != 2) continue;

            try {
                if (setBeaconLightRangeInFile(targetFile, range[0], range[1])) {
                    patched++;
                }
            } catch (Exception e) {
                LOGGER.warning("[SpawnManager] cave toggle failed file=" + targetFile + " error=" + e.getMessage());
            }
        }

        LOGGER.info("[SpawnManager] cave toggle done: enabled=" + enabled + " patched=" + patched + " missing=" + missing);
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
        setFilterButtonVisibility(cmd, "#FilterDinosaur", activeGroups.contains("Dinosaur"));
        setFilterButtonVisibility(cmd, "#FilterFen", activeGroups.contains("Fen"));
        setFilterButtonVisibility(cmd, "#FilterDragon", activeGroups.contains("Dragon"));
        setFilterButtonVisibility(cmd, "#FilterBoss", activeGroups.contains("Boss"));
        setFilterButtonVisibility(cmd, "#FilterOther", activeGroups.contains("Other"));

        setFilterButtonVisibility(cmd, "#FilterKweebec", activeGroups.contains("Kweebec"));
        setFilterButtonVisibility(cmd, "#FilterFeran", activeGroups.contains("Feran"));
        setFilterButtonVisibility(cmd, "#FilterKlops", activeGroups.contains("Klops"));
        setFilterButtonVisibility(cmd, "#FilterTemple", activeGroups.contains("Temple"));

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
        if (mapping != null && mapping.mobs != null) {
            config.retainDisabledMobs(mapping.mobs.keySet());
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
            if (mobMapping == null) {
                LOGGER.warning("[SpawnManager] apply: no mapping entry for mob=" + mobId);
                continue;
            }

            boolean enabled = stagedSnapshot.getOrDefault(mobId, true);

            boolean hasWorldFiles = mobMapping.files != null && !mobMapping.files.isEmpty();
            boolean hasMarkers = mobMapping.markers != null && !mobMapping.markers.isEmpty();
            if (!hasWorldFiles && !hasMarkers) {
                LOGGER.warning("[SpawnManager] apply: no world/marker mapping for mob=" + mobId);
                continue;
            }

            String replacementSpawnBlockSet = mapping.getReplacementSpawnBlockSet();
            String replacementSpawnFluidTag = mapping.getReplacementSpawnFluidTag();

            Double replacementMarkerDistance = mapping.getReplacementMarkerDeactivationDistance();
            if (hasMarkers && !enabled && (replacementMarkerDistance == null || !Double.isFinite(replacementMarkerDistance) || replacementMarkerDistance <= 0.0d)) {
                LOGGER.warning("[SpawnManager] apply: ReplacementMarkerDeactivationDistance missing/invalid for mob=" + mobId);
            }

            if (hasWorldFiles) {
                for (FileEntry fileEntry : mobMapping.files) {
                    if (fileEntry == null) {
                        LOGGER.warning("[SpawnManager] apply: null world file entry for mob=" + mobId);
                        continue;
                    }

                    String mappedPath = fileEntry.path;
                    String relativePath = normalizeMappedPath(mappedPath);
                    if (relativePath == null) {
                        LOGGER.warning("[SpawnManager] apply: invalid world path for mob=" + mobId + " raw='" + mappedPath + "'");
                        continue;
                    }

                    String spawnPropertyKey = resolveSpawnPropertyKey(fileEntry);
                    String originalSpawnValue = resolveOriginalSpawnValue(fileEntry);
                    String replacementSpawnValue = SPAWN_FLUID_TAG_KEY.equals(spawnPropertyKey) ? replacementSpawnFluidTag : replacementSpawnBlockSet;

                    if (!enabled && (replacementSpawnValue == null || replacementSpawnValue.isBlank())) {
                        LOGGER.warning("[SpawnManager] apply: missing replacement value for key=" + spawnPropertyKey + " mob=" + mobId);
                        continue;
                    }

                    String targetSpawnValue = enabled ? originalSpawnValue : replacementSpawnValue;
                    if (targetSpawnValue == null || targetSpawnValue.isBlank()) {
                        LOGGER.warning("[SpawnManager] apply: missing target value for key=" + spawnPropertyKey + " mob=" + mobId + " file=" + mappedPath);
                        continue;
                    }

                    Path targetFile = locateWorldPathInAssetPacks(AssetModule.get(), relativePath);
                    if (targetFile == null) {
                        LOGGER.warning("[SpawnManager] apply: world file not found for mob=" + mobId + " path=Server/" + relativePath);
                        continue;
                    }

                    try {
                        PatchOutcome outcome = setMobSpawnPropertyInFile(targetFile, mobId, spawnPropertyKey, targetSpawnValue);
                        if (!outcome.foundTarget) {
                            LOGGER.warning("[SpawnManager] apply: Id not found in NPCs for mob=" + mobId + " file=" + targetFile);
                        }
                    } catch (Exception e) {
                        LOGGER.warning("[SpawnManager] apply: failed world patch for mob=" + mobId + " file=" + targetFile + " error=" + e.getMessage());
                    }
                }
            }

            if (hasMarkers) {
                for (MarkerEntry markerEntry : mobMapping.markers) {
                    if (markerEntry == null) {
                        LOGGER.warning("[SpawnManager] apply: null marker entry for mob=" + mobId);
                        continue;
                    }

                    String mappedPath = markerEntry.path;
                    String relativePath = normalizeMappedPath(mappedPath);
                    if (relativePath == null) {
                        LOGGER.warning("[SpawnManager] apply: invalid marker path for mob=" + mobId + " raw='" + mappedPath + "'");
                        continue;
                    }

                    Double targetDistance = enabled ? markerEntry.originalDeactivationDistance : replacementMarkerDistance;
                    if (targetDistance == null || !Double.isFinite(targetDistance) || targetDistance <= 0.0d) {
                        LOGGER.warning("[SpawnManager] apply: missing/invalid marker distance for mob=" + mobId + " file=" + mappedPath);
                        continue;
                    }

                    Path targetFile = locateWorldPathInAssetPacks(AssetModule.get(), relativePath);
                    if (targetFile == null) {
                        LOGGER.warning("[SpawnManager] apply: marker file not found for mob=" + mobId + " path=Server/" + relativePath);
                        continue;
                    }

                    try {
                        PatchOutcome outcome = setMarkerDeactivationDistanceInFile(targetFile, mobId, targetDistance);
                        if (!outcome.foundTarget) {
                            LOGGER.warning("[SpawnManager] apply: marker target not found for mob=" + mobId + " file=" + targetFile);
                        }
                    } catch (Exception e) {
                        LOGGER.warning("[SpawnManager] apply: failed marker patch for mob=" + mobId + " file=" + targetFile + " error=" + e.getMessage());
                    }
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

        Path pluginOverrideRoot = Path.of("mods", "SpawnManager", "Server");
        Path pluginOverrideTarget = pluginOverrideRoot.resolve(relativeUnderServer).normalize();
        if (Files.exists(pluginOverrideTarget) && Files.isRegularFile(pluginOverrideTarget)) {
            return pluginOverrideTarget;
        }

        Path foundMutable = null;
        Path foundAny = null;
        Path mutableRoot = null;

        for (AssetPack pack : assetModule.getAssetPacks()) {
            Path root = pack.getRoot();
            boolean immutable = pack.isImmutable();
            if (!immutable && mutableRoot == null) {
                mutableRoot = root;
            }

            Path candidate = root.resolve("Server").resolve(relativeUnderServer).normalize();
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                if (!immutable && foundMutable == null) foundMutable = candidate;
                if (foundAny == null) foundAny = candidate;
            }
        }

        if (foundMutable != null) {
            return foundMutable;
        }

        if (foundAny != null && mutableRoot != null) {
            Path writableTarget = mutableRoot.resolve("Server").resolve(relativeUnderServer).normalize();
            try {
                if (!Files.exists(writableTarget)) {
                    Path parent = writableTarget.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(foundAny, writableTarget, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.info("[SpawnManager] staged writable override for " + relativeUnderServer + " at " + writableTarget);
                }
                return writableTarget;
            } catch (Exception e) {
                LOGGER.warning("[SpawnManager] failed to stage writable override for " + relativeUnderServer + ": " + e.getMessage());
            }
        }

        if (foundAny != null) {
            try {
                Path parent = pluginOverrideTarget.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                if (!Files.exists(pluginOverrideTarget)) {
                    Files.copy(foundAny, pluginOverrideTarget, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.info("[SpawnManager] staged plugin override for " + relativeUnderServer + " at " + pluginOverrideTarget);
                }
                return pluginOverrideTarget;
            } catch (Exception e) {
                LOGGER.warning("[SpawnManager] failed to stage plugin override for " + relativeUnderServer + ": " + e.getMessage());
            }
        }

        return foundAny;
    }

    private static String resolveSpawnPropertyKey(FileEntry fileEntry) {
        if (fileEntry != null && fileEntry.originalSpawnFluidTag != null && !fileEntry.originalSpawnFluidTag.isBlank()) {
            return SPAWN_FLUID_TAG_KEY;
        }
        return SPAWN_BLOCK_SET_KEY;
    }

    private static String resolveOriginalSpawnValue(FileEntry fileEntry) {
        if (fileEntry == null) return null;
        if (fileEntry.originalSpawnFluidTag != null && !fileEntry.originalSpawnFluidTag.isBlank()) return fileEntry.originalSpawnFluidTag;
        return fileEntry.originalSpawnBlockSet;
    }

    private static PatchOutcome setMobSpawnPropertyInFile(Path file, String mobId, String propertyKey, String propertyValue) throws IOException {
        String json = Files.readString(file);
        JsonElement el = JsonParser.parseString(json);
        if (!el.isJsonObject()) return new PatchOutcome(false, false);

        JsonObject root = el.getAsJsonObject();

        if (SPAWN_BLOCK_SET_KEY.equals(propertyKey) && root.has(SPAWN_BLOCK_SET_KEY)) root.remove(SPAWN_BLOCK_SET_KEY);

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
                String before = npc.has(propertyKey) && npc.get(propertyKey).isJsonPrimitive() ? npc.get(propertyKey).getAsString() : null;
                if (!Objects.equals(before, propertyValue)) {
                    npc.addProperty(propertyKey, propertyValue);
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


    private static PatchOutcome setMarkerDeactivationDistanceInFile(Path file, String mobId, double deactivationDistance) throws IOException {
        String json = Files.readString(file);
        JsonElement el = JsonParser.parseString(json);

        boolean found = false;
        boolean modified = false;

        if (el.isJsonObject()) {
            JsonObject root = el.getAsJsonObject();

            if (root.has("Markers") && root.get("Markers").isJsonArray()) {
                JsonArray markers = root.getAsJsonArray("Markers");
                for (JsonElement markerEl : markers) {
                    if (!markerEl.isJsonObject()) continue;
                    JsonObject marker = markerEl.getAsJsonObject();
                    if (!markerMatchesMob(marker, mobId)) continue;
                    found = true;
                    double before = marker.has(MARKER_KEY) && marker.get(MARKER_KEY).isJsonPrimitive() ? marker.get(MARKER_KEY).getAsDouble() : Double.NaN;
                    if (!Double.isFinite(before) || Double.compare(before, deactivationDistance) != 0) {
                        marker.addProperty(MARKER_KEY, deactivationDistance);
                        modified = true;
                    }
                    break;
                }
            } else {
                if (markerMatchesMob(root, mobId) || markerRootContainsMob(root, mobId)) {
                    found = true;
                    double before = root.has(MARKER_KEY) && root.get(MARKER_KEY).isJsonPrimitive() ? root.get(MARKER_KEY).getAsDouble() : Double.NaN;
                    if (!Double.isFinite(before) || Double.compare(before, deactivationDistance) != 0) {
                        root.addProperty(MARKER_KEY, deactivationDistance);
                        modified = true;
                    }
                }
            }

            if (modified) {
                writeAtomic(file, GSON.toJson(root));
            }
            return new PatchOutcome(found, modified);
        }

        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            for (JsonElement markerEl : arr) {
                if (!markerEl.isJsonObject()) continue;
                JsonObject marker = markerEl.getAsJsonObject();
                if (!markerMatchesMob(marker, mobId)) continue;
                found = true;
                double before = marker.has(MARKER_KEY) && marker.get(MARKER_KEY).isJsonPrimitive() ? marker.get(MARKER_KEY).getAsDouble() : Double.NaN;
                if (!Double.isFinite(before) || Double.compare(before, deactivationDistance) != 0) {
                    marker.addProperty(MARKER_KEY, deactivationDistance);
                    modified = true;
                }
                break;
            }
            if (modified) {
                writeAtomic(file, GSON.toJson(arr));
            }
            return new PatchOutcome(found, modified);
        }

        return new PatchOutcome(false, false);
    }


    private static boolean markerRootContainsMob(JsonObject root, String mobId) {
        if (root == null || mobId == null) return false;
        if (!root.has("NPCs") || !root.get("NPCs").isJsonArray()) return false;

        JsonArray npcs = root.getAsJsonArray("NPCs");
        for (JsonElement npcEl : npcs) {
            if (!npcEl.isJsonObject()) continue;
            JsonObject npc = npcEl.getAsJsonObject();
            if (npc.has("Name") && npc.get("Name").isJsonPrimitive() && mobId.equals(npc.get("Name").getAsString())) {
                return true;
            }
            if (npc.has("Id") && npc.get("Id").isJsonPrimitive() && mobId.equals(npc.get("Id").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean markerMatchesMob(JsonObject marker, String mobId) {
        if (marker == null || mobId == null) return false;

        if (marker.has("Name") && marker.get("Name").isJsonPrimitive() && mobId.equals(marker.get("Name").getAsString())) {
            return true;
        }
        if (marker.has("Id") && marker.get("Id").isJsonPrimitive() && mobId.equals(marker.get("Id").getAsString())) {
            return true;
        }
        return false;
    }

    private static boolean setBeaconLightRangeInFile(Path file, int minLight, int maxLight) throws IOException {
        String json = Files.readString(file);
        JsonElement el = JsonParser.parseString(json);
        if (!el.isJsonObject()) return false;

        JsonObject root = el.getAsJsonObject();
        JsonObject lightRanges = root.has("LightRanges") && root.get("LightRanges").isJsonObject()
                ? root.getAsJsonObject("LightRanges")
                : new JsonObject();

        int beforeMin = Integer.MIN_VALUE;
        int beforeMax = Integer.MIN_VALUE;
        if (lightRanges.has("Light") && lightRanges.get("Light").isJsonArray()) {
            JsonArray arr = lightRanges.getAsJsonArray("Light");
            if (arr.size() >= 2 && arr.get(0).isJsonPrimitive() && arr.get(1).isJsonPrimitive()) {
                beforeMin = arr.get(0).getAsInt();
                beforeMax = arr.get(1).getAsInt();
            }
        }

        boolean modified = beforeMin != minLight || beforeMax != maxLight;
        if (!modified) return false;

        JsonArray newLight = new JsonArray();
        newLight.add(minLight);
        newLight.add(maxLight);
        lightRanges.add("Light", newLight);
        root.add("LightRanges", lightRanges);

        writeAtomic(file, GSON.toJson(root));
        return true;
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

    private record PatchOutcome(boolean foundTarget, boolean modified) {
    }
}
