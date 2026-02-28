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
import com.hypixel.hytale.server.core.entity.entities.player.pages.BasicCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

public final class SpawnManagerPages extends BasicCustomUIPage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    // UI paths
    private static final String PAGE_PATH = "UI/Custom/Pages/SpawnManagerPage.ui";
    private static final String ROW_PATH = "UI/Custom/Pages/MobRow.ui";

    // Fichier cible (test)
    private static final String FILE_NAME = "Spawns_Zone1_Forests_Predator.json";
    private static final String RELATIVE_UNDER_SERVER = "NPC/Spawn/World/Zone1/" + FILE_NAME;

    // Test: on ne gère que Spider
    private static final String TARGET_ID = "Spider";

    // Valeurs
    private static final String KEY = "SpawnBlockSet";
    private static final String ENABLED_VALUE = "Soil";
    private static final String DISABLED_VALUE = "Volcanic";

    // Liste affichée (test)
    private final List<String> displayedMobs = new ArrayList<>();

    // staging
    private final Map<String, Boolean> stagedEnabled = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();

    public SpawnManagerPages(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismiss);

        // test list
        displayedMobs.add("Spider");
        displayedMobs.add("Bear_Grizzly");
    }

    /**
     * OBLIGATOIRE: BasicCustomUIPage exige cette méthode.
     * On met juste la page (sans events).
     * (La vraie build avec events est dans l'override ci-dessous.)
     */
    @Override
    public void build(@Nonnull UICommandBuilder cmd) {
        cmd.append(PAGE_PATH);
    }

    /**
     * Build complet (avec events) — ici tu peux faire comme AnimalTracker.
     */
    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
                      @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {

        // On construit nous-même tout (et on ne dépend pas de build(cmd) ici)
        cmd.append(PAGE_PATH);

        buildMobList(cmd, events);

        // Si tu veux un bouton "Apply" plus tard:
        // events.addEventBinding(CustomUIEventBindingType.Activating, "#ApplyButton", EventData.of("Action", "apply"));
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
                stagedEnabled.put(mobId, value);
                dirty.add(mobId);
            }
            return;
        }

        if ("apply".equals(action)) {
            applyChanges();
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        applyChanges();
    }

    private void buildMobList(UICommandBuilder cmd, UIEventBuilder events) {
        cmd.clear("#MobListContainer");

        for (int i = 0; i < displayedMobs.size(); i++) {
            String mobId = displayedMobs.get(i);

            boolean enabled = stagedEnabled.getOrDefault(mobId, true);

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

    private void applyChanges() {
        // phase test: on applique uniquement Spider
        if (!dirty.contains(TARGET_ID)) {
            dirty.clear();
            return;
        }

        Boolean enabled = stagedEnabled.get(TARGET_ID);
        if (enabled == null) {
            dirty.clear();
            return;
        }

        try {
            Path targetFile = locateTargetInAssetPacks(AssetModule.get());
            if (targetFile == null) {
                dirty.clear();
                return;
            }

            setMobEnabledInFile(targetFile, TARGET_ID, enabled);
        } catch (Exception ignored) {
        } finally {
            dirty.clear();
        }
    }

    private static Path locateTargetInAssetPacks(AssetModule assetModule) {
        if (assetModule == null) return null;

        Path foundMutable = null;
        Path foundAny = null;

        for (AssetPack pack : assetModule.getAssetPacks()) {
            Path root = pack.getRoot();
            boolean immutable = pack.isImmutable();

            Path candidate = root.resolve("Server").resolve(RELATIVE_UNDER_SERVER).normalize();
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                if (!immutable && foundMutable == null) foundMutable = candidate;
                if (foundAny == null) foundAny = candidate;
            }
        }

        return (foundMutable != null) ? foundMutable : foundAny;
    }

    private static void setMobEnabledInFile(Path file, String mobId, boolean enabled) throws IOException {
        String json = Files.readString(file);
        JsonElement el = JsonParser.parseString(json);
        if (!el.isJsonObject()) return;

        JsonObject root = el.getAsJsonObject();

        // nettoyage: évite SpawnBlockSet au root
        if (root.has(KEY)) root.remove(KEY);

        if (!root.has("NPCs") || !root.get("NPCs").isJsonArray()) return;
        JsonArray npcs = root.getAsJsonArray("NPCs");

        String targetValue = enabled ? ENABLED_VALUE : DISABLED_VALUE;

        boolean modified = false;
        for (JsonElement npcEl : npcs) {
            if (!npcEl.isJsonObject()) continue;
            JsonObject npc = npcEl.getAsJsonObject();
            if (!npc.has("Id") || !npc.get("Id").isJsonPrimitive()) continue;

            String id = npc.get("Id").getAsString();
            if (mobId.equals(id)) {
                npc.addProperty(KEY, targetValue);
                modified = true;
                break;
            }
        }

        if (modified) {
            writeAtomic(file, GSON.toJson(root));
        }
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
}
