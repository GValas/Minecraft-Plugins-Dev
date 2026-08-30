"""Export the portal build core as portal.nbt (build blocks + clearing air only).

Template rules:
  - positions in the build cluster -> real block (with block-entity nbt)
  - other positions with y >= GROUND_Y -> air (clears space at paste time)
  - other positions with y <  GROUND_Y -> omitted (destination ground preserved)
"""
from collections import Counter, deque
from pathlib import Path

import nbtlib
from nbtlib import Compound, File, Int, List, String

from extract_portal import ChunkStore, WORLD, section_palette_entry

LO = (62, 91, 7)
HI = (93, 118, 35)
GROUND_Y = 93          # source walking level (plate top y92 + 1)
DATA_VERSION = 3465
OUT = Path(r"\\HORUS\appdata\minecraft\Kamoof-Lite\src\portal.nbt")

HARD = {
    "minecraft:nether_portal", "minecraft:obsidian", "minecraft:crying_obsidian",
    "minecraft:cobblestone", "minecraft:cobblestone_wall", "minecraft:cobblestone_stairs",
    "minecraft:mossy_cobblestone", "minecraft:mossy_cobblestone_wall",
    "minecraft:mossy_cobblestone_stairs", "minecraft:mossy_cobblestone_slab",
    "minecraft:andesite", "minecraft:andesite_wall", "minecraft:andesite_stairs",
    "minecraft:andesite_slab", "minecraft:polished_andesite",
    "minecraft:stone_brick_stairs", "minecraft:mossy_stone_brick_stairs",
    "minecraft:stone_bricks", "minecraft:mossy_stone_bricks",
    "minecraft:purple_candle", "minecraft:magenta_candle", "minecraft:chain",
    "minecraft:purple_banner",
}
SOFT = {"minecraft:moss_block", "minecraft:moss_carpet", "minecraft:tuff",
        "minecraft:dirt_path"}


def in_bbox(p):
    return (LO[0] <= p[0] <= HI[0] and LO[1] <= p[1] <= HI[1]
            and LO[2] <= p[2] <= HI[2])


def neighbors2(p):
    x, y, z = p
    for dx in (-2, -1, 0, 1, 2):
        for dy in (-2, -1, 0, 1, 2):
            for dz in (-2, -1, 0, 1, 2):
                if dx or dy or dz:
                    yield (x + dx, y + dy, z + dz)


def main():
    store = ChunkStore(WORLD)
    hard, soft = {}, {}
    for y in range(LO[1], HI[1] + 1):
        for z in range(LO[2], HI[2] + 1):
            for x in range(LO[0], HI[0] + 1):
                b = store.block(x, y, z)
                if b in HARD:
                    hard[(x, y, z)] = b
                elif b in SOFT:
                    soft[(x, y, z)] = b

    # flood-fill the hard cluster from the portal blocks (gap <= 2)
    seeds = deque(p for p, b in hard.items() if b == "minecraft:nether_portal")
    cluster = set(seeds)
    todo = set(hard) - cluster
    while seeds:
        p = seeds.popleft()
        near = [q for q in neighbors2(p) if q in todo]
        for q in near:
            todo.discard(q)
            cluster.add(q)
            seeds.append(q)
    print(f"[cluster] hard: {len(cluster)}/{len(hard)} blocs rattaches au portail "
          f"({len(hard) - len(cluster)} exclus, probablement filons naturels)")

    # attach soft decor (moss/tuff/path) touching the cluster, then grow among itself
    seeds = deque(cluster)
    todo = set(soft)
    build = set(cluster)
    while seeds:
        p = seeds.popleft()
        near = [q for q in neighbors2(p) if q in todo]
        for q in near:
            todo.discard(q)
            build.add(q)
            seeds.append(q)
    print(f"[cluster] soft: {len(build) - len(cluster)}/{len(soft)} blocs deco sol rattaches")

    excluded = Counter()
    for p, b in {**hard, **soft}.items():
        if p not in build:
            excluded[b] += 1
    print(f"[cluster] exclus: {dict(excluded)}")

    # build the template
    air_entry = Compound({"Name": String("minecraft:air")})
    bes = store.block_entities()
    palette, palette_key, blocks = [], {}, []
    census = Counter()
    for y in range(LO[1], HI[1] + 1):
        for z in range(LO[2], HI[2] + 1):
            for x in range(LO[0], HI[0] + 1):
                p = (x, y, z)
                if p in build:
                    entry = section_palette_entry(store, x, y, z)
                elif y >= GROUND_Y:
                    entry = air_entry
                else:
                    continue
                census[str(entry["Name"])] += 1
                key = nbtlib.serialize_tag(entry)
                if key not in palette_key:
                    palette_key[key] = len(palette)
                    palette.append(entry)
                block = Compound({
                    "state": Int(palette_key[key]),
                    "pos": List[Int]([x - LO[0], y - LO[1], z - LO[2]]),
                })
                be = bes.get(p)
                if be is not None and p in build:
                    block["nbt"] = Compound({k: v for k, v in be.items()
                                             if k not in ("x", "y", "z", "keepPacked")})
                blocks.append(block)

    size = (HI[0] - LO[0] + 1, HI[1] - LO[1] + 1, HI[2] - LO[2] + 1)
    root = Compound({
        "size": List[Int](list(size)),
        "entities": List[Compound]([]),
        "blocks": List[Compound](blocks),
        "palette": List[Compound](palette),
        "DataVersion": Int(DATA_VERSION),
    })
    f = File(root)
    f.gzipped = True
    f.save(str(OUT))
    print(f"\n[export] {OUT}")
    print(f"[export] size={size}, palette={len(palette)}, blocks={len(blocks)}")
    print("[export] census template:", census.most_common(12))

    back = nbtlib.load(str(OUT), gzipped=True)
    n_be = sum(1 for b in back["blocks"] if "nbt" in b)
    print(f"[verif] re-parse OK: size={[int(v) for v in back['size']]}, "
          f"palette={len(back['palette'])}, blocks={len(back['blocks'])}, "
          f"block-entities={n_be}, DataVersion={int(back['DataVersion'])}")


if __name__ == "__main__":
    main()
