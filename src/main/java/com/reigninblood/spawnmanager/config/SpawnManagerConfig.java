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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Config globale + sets de mobs (inspiré AnimalTracker).
 * Objectif: stocker l'état enabled/disabled par mobId pour l'UI.
 */
public final class SpawnManagerConfig {

    private static final Logger LOGGER = Logger.getLogger(SpawnManagerConfig.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum EntityCategory {
        PASSIVE,
        AGGRESSIVE_ANIMAL,
        MONSTER,
        UNKNOWN
    }

    // ---- LISTES (tu peux garder exactement tes sets actuels) ----
    public static final Set<String> PASSIVE_MOBS = Set.of("Deer_Doe", "Deer_Stag", "Horse", "Foal", "Rabbit", "Bunny", "Chicken", "Chick", "Chicken_Chick", "Chicken_Desert", "Chicken_Desert_Chick", "Cow", "Cow_Calf", "Pig", "Piglet", "Pig_Piglet", "Pig_Wild", "Pig_Wild_Piglet", "Sheep", "Sheep_Lamb", "Goat", "Goat_Kid", "Turkey", "Turkey_Chick", "Duck", "Ram", "Sheep_Ram_Lamb", "Camel", "Camel_Calf", "Bison", "Bison_Calf", "Mouflon", "Mouflon_Lamb", "Warthog", "Warthog_Piglet", "Boar_Piglet", "Skrill", "Skrill_Chick", "Antelope", "Moose_Cow", "Moose_Bull", "Armadillo", "Mouse", "Meerkat", "Gecko", "Squirrel", "Temple_Squirrel", "Frog", "Frog_Blue", "Frog_Green", "Frog_Orange", "Temple_Frog_Green", "Temple_Frog_Orange", "Rat", "Sparrow", "Parrot", "Finch_Green", "Bluebird", "Temple_Bluebird", "Owl_Brown", "Owl_Snow", "Crow", "Raven", "Pigeon", "Hawk", "Tetrabird", "Vulture", "Woodpecker", "Flamingo", "Penguin", "Bat", "Bat_Ice", "Pterodactyl", "Archaeopteryx", "Trout_Rainbow", "Pike", "Bluegill", "Salmon", "Catfish", "Minnow", "Frostgill", "Clownfish", "Pufferfish", "Tang_Sailfin", "Tang_Blue", "Tang_Lemon_Peel", "Tang_Chevron", "Crab", "Lobster", "Trilobite", "Trilobite_Black", "Whale_Humpback", "Tortoise", "Jellyfish_Red", "Jellyfish_Green", "Jellyfish_Yellow", "Jellyfish_Cyan", "Jellyfish_Blue", "Kweebec_Elder", "Kweebec_Sapling", "Kweebec_Sapling_Orange", "Kweebec_Sapling_Pink", "Kweebec_Seedling", "Kweebec_Sproutling", "Kweebec_Sproutling_Patrol", "Kweebec_Rootling", "Kweebec_Merchant", "Rootling_Merchant", "Temple_Kweebec_Seedling", "Temple_Kweebec_Static", "Feran_Civilian", "Feran_Cub");
    public static final Set<String> AGGRESSIVE_MOBS = Set.of("Wolf_Black", "Wolf_White", "Hunting_Wolf", "Wolf_Outlander_Priest", "Wolf_Outlander_Sorcerer", "Wolf_Trork_Hunter", "Bear_Grizzly", "Bear_Polar", "Leopard_Snow", "Tiger_Sabertooth", "Boar", "Fox", "Hyena", "Mosshorn", "Mosshorn_Plain", "Rex_Cave", "Raptor_Cave", "Crocodile", "Snake_Marsh", "Snake_Cobra", "Snake_Rattle", "Lizard_Sand", "Toad_Rhino", "Fen_Stalker", "Emberwulf", "Yeti", "Snapdragon", "Trillodon", "Scorpion", "Shark_Hammerhead", "Piranha", "Piranha_Black", "Snapjaw", "Eel_Moray", "Jellyfish_Man_Of_War", "Feran_Burrower", "Feran_Longtooth", "Feran_Sharptooth", "Feran_Windwalker");
    public static final Set<String> MONSTER_MOBS = Set.of("Skeleton", "Skeleton_Fighter", "Skeleton_Fighter_Wander", "Skeleton_Archer", "Skeleton_Scout", "Skeleton_Soldier", "Skeleton_Knight", "Skeleton_Ranger", "Skeleton_Mage", "Skeleton_Archmage", "Skeleton_Frost_Scout", "Skeleton_Frost_Ranger", "Skeleton_Frost_Fighter", "Skeleton_Frost_Archer", "Skeleton_Frost_Mage", "Skeleton_Frost_Soldier", "Skeleton_Frost_Knight", "Skeleton_Frost_Archmage", "Skeleton_Incandescent_Mage", "Skeleton_Incandescent_Footman", "Skeleton_Incandescent_Head", "Skeleton_Incandescent_Fighter", "Skeleton_Sand_Archmage", "Skeleton_Sand_Assassin", "Skeleton_Sand_Scout", "Skeleton_Sand_Soldier", "Skeleton_Sand_Archer", "Skeleton_Sand_Ranger", "Skeleton_Sand_Guard", "Skeleton_Sand_Mage", "Dungeon_Skeleton_Sand_Archer", "Dungeon_Skeleton_Sand_Assassin", "Dungeon_Skeleton_Sand_Mage", "Dungeon_Skeleton_Sand_Soldier", "Skeleton_Pirate_Captain", "Skeleton_Pirate_Striker", "Skeleton_Pirate_Gunner", "Skeleton_Burnt_Alchemist", "Skeleton_Burnt_Praetorian", "Skeleton_Burnt_Knight", "Skeleton_Burnt_Gunner", "Skeleton_Burnt_Wizard", "Skeleton_Burnt_Archer", "Skeleton_Burnt_Lancer", "Skeleton_Burnt_Soldier", "Horse_Skeleton", "Horse_Skeleton_Armored", "Zombie", "Zombie_Sand", "Zombie_Frost", "Zombie_Burnt", "Zombie_Aberrant", "Zombie_Aberrant_Small", "Zombie_Aberrant_Big", "Ghoul", "Wraith", "Wraith_Lantern", "Shadow_Knight", "Hound_Bleached", "Werewolf", "Cow_Undead", "Pig_Undead", "Chicken_Undead", "Larva_Void", "Crawler_Void", "Eye_Void", "Spawn_Void", "Golem_Firesteel", "Golem_Crystal_Earth", "Golem_Crystal_Frost", "Golem_Crystal_Flame", "Golem_Crystal_Sand", "Golem_Crystal_Thunder", "Spirit_Frost", "Spirit_Thunder", "Spirit_Root", "Spirit_Ember", "Spider", "Spider_Cave", "Larva_Silk", "Hatworm", "Cactee", "Spark_Living", "Toad_Rhino_Magma", "Snail_Magma", "Slug_Magma", "Snail_Frost", "Shellfish_Lava", "Trork", "Trork_Brawler", "Trork_Chieftain", "Trork_Elder", "Trork_Guard", "Trork_Doctor_Witch", "Trork_Hunter", "Trork_Mauler", "Trork_Sentry", "Trork_Shaman", "Trork_Warrior", "Trork_Warrior_Patrol", "Goblin_Duke", "Goblin_Hermit", "Goblin_Lobber", "Goblin_Lobber_Patrol", "Goblin_Miner", "Goblin_Ogre", "Goblin_Scavenger", "Goblin_Scrapper", "Goblin_Scrapper_Patrol", "Goblin_Thief", "Goblin_Thief_Patrol", "Outlander_Berserker", "Outlander_Brute", "Outlander_Hunter", "Outlander_Initiate", "Outlander_Marauder", "Outlander_Priest", "Outlander_Sorcerer", "Outlander_Stalker", "Outlander_Unsworn", "Scarak_Broodmother", "Scarak_Defender", "Scarak_Fighter", "Scarak_Louse", "Scarak_Seeker", "Scarak_Imperial_Guard", "Dungeon_Scarak_Broodmother", "Dungeon_Scarak_Defender", "Dungeon_Scarak_Defender_Patrol", "Dungeon_Scarak_Fighter", "Dungeon_Scarak_Louse", "Dungeon_Scarak_Seeker", "Dungeon_Scarak_Seeker_Patrol", "Fledgling_Scarak_Broodmother", "Kweebec_Razorleaf", "Kweebec_Razorleaf_Patrol", "Temple_Kweebec_Razorleaf_Patrol", "Dragon_Ember", "Dragon_Frost", "The_Hedera", "Klops_Gentleman", "Klops_Miner", "Klops_Merchant", "Vorthak");

    /** Union triée + stable pour affichage UI */
    public static final Set<String> ALL_KNOWN_MOBS;
    static {
        // TreeSet => tri alpha stable
        Set<String> all = new TreeSet<>();
        all.addAll(PASSIVE_MOBS);
        all.addAll(AGGRESSIVE_MOBS);
        all.addAll(MONSTER_MOBS);
        ALL_KNOWN_MOBS = Collections.unmodifiableSet(all);
    }

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

            // Nettoyage: supprime les ids inconnus si tu veux éviter de gonfler le fichier
            this.globalConfig.disabledMobs.retainAll(ALL_KNOWN_MOBS);

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

    public Set<String> getAllKnownMobs() {
        return ALL_KNOWN_MOBS;
    }

    public EntityCategory getCategoryForMob(String mobId) {
        if (mobId == null) return EntityCategory.UNKNOWN;
        if (PASSIVE_MOBS.contains(mobId)) return EntityCategory.PASSIVE;
        if (AGGRESSIVE_MOBS.contains(mobId)) return EntityCategory.AGGRESSIVE_ANIMAL;
        if (MONSTER_MOBS.contains(mobId)) return EntityCategory.MONSTER;
        return EntityCategory.UNKNOWN;
    }

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

    // ---- MODEL JSON ----
    public static final class GlobalConfig {
        /** On stocke uniquement les OFF (plus compact et plus stable) */
        public Set<String> disabledMobs = new LinkedHashSet<>();
    }
}