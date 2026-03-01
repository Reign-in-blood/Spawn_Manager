import json
from pathlib import Path

REPLACEMENT_BLOCKSET = "Empty"

ROOT = Path(__file__).resolve().parent
SCAN_DIR = ROOT / "Server" / "NPC" / "Spawn" / "World"
OUTPUT = ROOT / "Server" / "FileMapping" / "spawn_mapping.generated.json"

def main():
    if not SCAN_DIR.exists():
        print("ERREUR: dossier introuvable:", SCAN_DIR.resolve())
        return

    mobs = {}

    for file_path in SCAN_DIR.rglob("*.json"):
        try:
            data = json.loads(file_path.read_text(encoding="utf-8"))
        except Exception:
            continue  # ignore les JSON cassés

        npcs = data.get("NPCs")
        if not isinstance(npcs, list):
            continue

        for npc in npcs:
            if not isinstance(npc, dict):
                continue

            mob_id = npc.get("Id")
            spawn_blockset = npc.get("SpawnBlockSet")

            if not isinstance(mob_id, str) or not mob_id:
                continue
            if not isinstance(spawn_blockset, str) or not spawn_blockset:
                continue

            rel_path = file_path.relative_to(ROOT).as_posix()

            mobs.setdefault(mob_id, {"files": []})
            mobs[mob_id]["files"].append({
                "path": rel_path,
                "originalSpawnBlockSet": spawn_blockset
            })

    # Dédup: si la même paire (path + blockset) apparaît plusieurs fois
    for mob_id, mob_data in mobs.items():
        seen = set()
        unique = []
        for e in mob_data["files"]:
            key = (e["path"], e["originalSpawnBlockSet"])
            if key in seen:
                continue
            seen.add(key)
            unique.append(e)
        mob_data["files"] = unique

    output_data = {
        "schemaVersion": 2,
        "ReplacementSpawnBlockSet": REPLACEMENT_BLOCKSET,
        "mobs": dict(sorted(mobs.items(), key=lambda kv: kv[0].lower()))
    }

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(output_data, indent=2, ensure_ascii=False), encoding="utf-8")

    print("OK: mapping généré ->", OUTPUT.resolve())
    print("Mobs trouvés:", len(output_data["mobs"]))

if __name__ == "__main__":
    main()