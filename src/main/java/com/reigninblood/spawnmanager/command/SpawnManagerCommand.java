package com.reigninblood.spawnmanager.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SpawnManagerCommand extends CommandBase {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Fichier cible (test)
    private static final String FILE_NAME = "Spawns_Zone1_Forests_Predator.json";
    private static final String RELATIVE_UNDER_SERVER = "NPC/Spawn/World/Zone1/" + FILE_NAME;

    // Cible unique
    private static final String TARGET_ID = "Spider";

    // Toggle valeurs
    private static final String KEY = "SpawnBlockSet";
    private static final String ON_VALUE = "Soil";
    private static final String OFF_VALUE = "SpawnBlockSet_NotUsed";

    public SpawnManagerCommand() {
        super("spawnmanager", "Toggle Spider SpawnBlockSet Soil <-> SpawnBlockSet_NotUsed dans " + FILE_NAME);
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        try {
            AssetModule assetModule = AssetModule.get();
            if (assetModule == null) {
                context.sendMessage(Message.raw("[SpawnManager] AssetModule.get() == null"));
                return;
            }

            LocateResult locate = locateTargetInAssetPacks(assetModule);
            if (locate.target == null) {
                context.sendMessage(Message.raw("[SpawnManager] Fichier introuvable: Server/" + RELATIVE_UNDER_SERVER));
                for (String line : locate.debugLines) {
                    context.sendMessage(Message.raw(" - " + line));
                }
                return;
            }

            Path target = locate.target;

            PatchResult res = toggleOnlySpider(target);

            context.sendMessage(Message.raw("[SpawnManager] Target = " + target));

            if (!res.foundSpider) {
                context.sendMessage(Message.raw("[SpawnManager] Spider introuvable dans NPCs[]. IDs présents: " + String.join(", ", res.knownIds)));
                return;
            }

            context.sendMessage(Message.raw("[SpawnManager] Spider SpawnBlockSet: " + res.before + " -> " + res.after));

        } catch (Exception e) {
            context.sendMessage(Message.raw("[SpawnManager] ERREUR: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private static PatchResult toggleOnlySpider(Path file) throws IOException {
        String json = Files.readString(file);
        JsonElement el = JsonParser.parseString(json);

        if (!el.isJsonObject()) throw new IOException("JSON root n'est pas un objet: " + file);
        JsonObject root = el.getAsJsonObject();

        // Nettoyage: supprime un SpawnBlockSet root (si ajouté par une ancienne version)
        if (root.has(KEY)) root.remove(KEY);

        if (!root.has("NPCs") || !root.get("NPCs").isJsonArray()) {
            throw new IOException("Champ 'NPCs' manquant ou non-array: " + file);
        }

        JsonArray npcs = root.getAsJsonArray("NPCs");

        List<String> knownIds = new ArrayList<>();
        boolean found = false;
        String before = null;
        String after = null;

        // 1) match exact d'abord
        for (JsonElement npcEl : npcs) {
            if (!npcEl.isJsonObject()) continue;
            JsonObject npc = npcEl.getAsJsonObject();
            String id = getId(npc);
            if (id != null) knownIds.add(id);

            if (TARGET_ID.equals(id)) {
                found = true;
                before = getSpawnBlockSet(npc);
                after = ON_VALUE.equals(before) ? OFF_VALUE : ON_VALUE;
                npc.addProperty(KEY, after);
                break;
            }
        }

        // 2) fallback case-insensitive si pas trouvé
        if (!found) {
            String targetLower = TARGET_ID.toLowerCase(Locale.ROOT);
            for (JsonElement npcEl : npcs) {
                if (!npcEl.isJsonObject()) continue;
                JsonObject npc = npcEl.getAsJsonObject();
                String id = getId(npc);
                if (id == null) continue;

                if (id.toLowerCase(Locale.ROOT).equals(targetLower)) {
                    found = true;
                    before = getSpawnBlockSet(npc);
                    after = ON_VALUE.equals(before) ? OFF_VALUE : ON_VALUE;
                    npc.addProperty(KEY, after);
                    break;
                }
            }
        }

        if (found) {
            writeAtomic(file, GSON.toJson(root));
        }

        return new PatchResult(found, before, after, knownIds);
    }

    private static String getId(JsonObject npc) {
        if (!npc.has("Id") || !npc.get("Id").isJsonPrimitive()) return null;
        return npc.get("Id").getAsString();
    }

    private static String getSpawnBlockSet(JsonObject npc) {
        if (!npc.has(KEY) || !npc.get(KEY).isJsonPrimitive()) return null;
        return npc.get(KEY).getAsString();
    }

    private static LocateResult locateTargetInAssetPacks(AssetModule assetModule) {
        List<String> debug = new ArrayList<>();
        Path foundMutable = null;
        Path foundAny = null;

        List<AssetPack> packs = assetModule.getAssetPacks();
        debug.add("assetPacks=" + packs.size());

        for (AssetPack pack : packs) {
            Path root = pack.getRoot();
            boolean immutable = pack.isImmutable();

            Path candidate = root.resolve("Server").resolve(RELATIVE_UNDER_SERVER).normalize();
            debug.add(pack.getName() + " | immutable=" + immutable + " | root=" + root + " | candidate=" + candidate);

            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                if (!immutable && foundMutable == null) foundMutable = candidate;
                if (foundAny == null) foundAny = candidate;
            }
        }

        return new LocateResult(foundMutable != null ? foundMutable : foundAny, debug);
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

    private record LocateResult(Path target, List<String> debugLines) {}
    private record PatchResult(boolean foundSpider, String before, String after, List<String> knownIds) {}
}