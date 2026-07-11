"""Extract the nether-portal build from the 'Portal Map' world save into a
vanilla structure template (portal.nbt).

Usage:
  python extract_portal.py                 # scan + report (checkpoint)
  python extract_portal.py --bbox x1 y1 z1 x2 y2 z2   # report a manual bbox
  python extract_portal.py [--bbox ...] --export OUT  # write structure nbt
"""
import argparse
import io
import math
import sys
import zlib
from collections import Counter, deque
from pathlib import Path

import nbtlib
from nbtlib import Compound, File, Int, List, String

WORLD = Path(__file__).parent / "Portal Map"
TARGETS = {"minecraft:nether_portal", "minecraft:obsidian"}

# ---------------------------------------------------------------- region I/O

def iter_region_chunks(path):
    """Yield (chunk_nbt_root) for every chunk present in a .mca file."""
    raw = path.read_bytes()
    for i in range(1024):
        entry = int.from_bytes(raw[i * 4 : i * 4 + 4], "big")
        offset, sectors = entry >> 8, entry & 0xFF
        if offset == 0 or sectors == 0:
            continue
        base = offset * 4096
        length = int.from_bytes(raw[base : base + 4], "big")
        comp = raw[base + 4]
        data = raw[base + 5 : base + 4 + length]
        if comp == 2:
            data = zlib.decompress(data)
        elif comp == 1:
            import gzip
            data = gzip.decompress(data)
        elif comp != 3:
            continue
        yield File.parse(io.BytesIO(data))


class ChunkStore:
    """Lazy access to decoded sections + block entities of the whole world."""

    def __init__(self, world):
        self.chunks = {}  # (cx, cz) -> chunk nbt
        for mca in sorted((world / "region").glob("r.*.mca")):
            for chunk in iter_region_chunks(mca):
                root = chunk
                if "Level" in root:  # pre-1.18 (not expected here)
                    root = root["Level"]
                cx, cz = int(root["xPos"]), int(root["zPos"])
                self.chunks[(cx, cz)] = root
        self._sections = {}  # (cx, cz, sy) -> list[str] of 4096 names or str

    @staticmethod
    def decode_section(sec):
        bs = sec.get("block_states")
        if bs is None:
            return "minecraft:air"
        palette = [
            str(e["Name"]) for e in bs["palette"]
        ]
        if "data" not in bs or len(palette) == 1:
            return palette[0]
        bits = max(4, (len(palette) - 1).bit_length())
        epl = 64 // bits
        mask = (1 << bits) - 1
        out = []
        data = bs["data"]
        for long in data:
            v = int(long) & 0xFFFFFFFFFFFFFFFF
            for _ in range(epl):
                out.append(palette[v & mask])
                v >>= bits
                if len(out) == 4096:
                    break
        return out

    def section(self, cx, cz, sy):
        key = (cx, cz, sy)
        if key not in self._sections:
            chunk = self.chunks.get((cx, cz))
            result = "minecraft:air"
            if chunk is not None:
                for sec in chunk.get("sections", []):
                    if int(sec["Y"]) == sy:
                        result = self.decode_section(sec)
                        break
            self._sections[key] = result
        return self._sections[key]

    def block(self, x, y, z):
        sec = self.section(x >> 4, z >> 4, y >> 4)
        if isinstance(sec, str):
            return sec
        return sec[((y & 15) * 16 + (z & 15)) * 16 + (x & 15)]

    def find_blocks(self, names):
        """Full scan: world coords of every block whose Name is in names."""
        found = {}
        for (cx, cz), chunk in self.chunks.items():
            for sec in chunk.get("sections", []):
                bs = sec.get("block_states")
                if bs is None:
                    continue
                palette = [str(e["Name"]) for e in bs["palette"]]
                if not any(p in names for p in palette):
                    continue
                sy = int(sec["Y"])
                blocks = self.decode_section(sec)
                if isinstance(blocks, str):
                    if blocks in names:
                        for i in range(4096):
                            y, r = divmod(i, 256)
                            z, x = divmod(r, 16)
                            found[(cx * 16 + x, sy * 16 + y, cz * 16 + z)] = blocks
                    continue
                for i, name in enumerate(blocks):
                    if name in names:
                        y, r = divmod(i, 256)
                        z, x = divmod(r, 16)
                        found[(cx * 16 + x, sy * 16 + y, cz * 16 + z)] = name
        return found

    def block_entities(self):
        out = {}
        for chunk in self.chunks.values():
            for be in chunk.get("block_entities", []):
                out[(int(be["x"]), int(be["y"]), int(be["z"]))] = be
        return out


# ---------------------------------------------------------------- clustering

def clusters(coords, max_gap=2):
    """Group coords into clusters (chebyshev distance <= max_gap)."""
    todo = set(coords)
    result = []
    while todo:
        seed = todo.pop()
        group = {seed}
        queue = deque([seed])
        while queue:
            cx, cy, cz = queue.popleft()
            near = [
                p
                for p in todo
                if abs(p[0] - cx) <= max_gap
                and abs(p[1] - cy) <= max_gap
                and abs(p[2] - cz) <= max_gap
            ]
            for p in near:
                todo.discard(p)
                group.add(p)
                queue.append(p)
        result.append(group)
    return result


def bbox_of(coords):
    xs = [c[0] for c in coords]
    ys = [c[1] for c in coords]
    zs = [c[2] for c in coords]
    return (min(xs), min(ys), min(zs)), (max(xs), max(ys), max(zs))


# ---------------------------------------------------------------- main logic

def census(store, lo, hi):
    c = Counter()
    for y in range(lo[1], hi[1] + 1):
        for z in range(lo[2], hi[2] + 1):
            for x in range(lo[0], hi[0] + 1):
                c[store.block(x, y, z)] += 1
    return c


def expand_bbox(store, lo, hi, natural, cap=24):
    lo, hi = list(lo), list(hi)
    grown = [0] * 6
    changed = True
    while changed:
        changed = False
        faces = [
            (0, -1, lambda: [(lo[0] - 1, y, z) for y in range(lo[1], hi[1] + 1) for z in range(lo[2], hi[2] + 1)]),
            (0, +1, lambda: [(hi[0] + 1, y, z) for y in range(lo[1], hi[1] + 1) for z in range(lo[2], hi[2] + 1)]),
            (1, -1, lambda: [(x, lo[1] - 1, z) for x in range(lo[0], hi[0] + 1) for z in range(lo[2], hi[2] + 1)]),
            (1, +1, lambda: [(x, hi[1] + 1, z) for x in range(lo[0], hi[0] + 1) for z in range(lo[2], hi[2] + 1)]),
            (2, -1, lambda: [(x, y, lo[2] - 1) for x in range(lo[0], hi[0] + 1) for y in range(lo[1], hi[1] + 1)]),
            (2, +1, lambda: [(x, y, hi[2] + 1) for x in range(lo[0], hi[0] + 1) for y in range(lo[1], hi[1] + 1)]),
        ]
        for fi, (axis, sign, shell) in enumerate(faces):
            if grown[fi] >= cap:
                continue
            if any(store.block(*p) not in natural for p in shell()):
                if sign < 0:
                    lo[axis] -= 1
                else:
                    hi[axis] += 1
                grown[fi] += 1
                changed = True
    return tuple(lo), tuple(hi)


def section_palette_entry(store, x, y, z):
    """Full palette entry (Name + Properties) for a block."""
    chunk = store.chunks.get((x >> 4, z >> 4))
    if chunk is None:
        return Compound({"Name": String("minecraft:air")})
    for sec in chunk.get("sections", []):
        if int(sec["Y"]) != y >> 4:
            continue
        bs = sec.get("block_states")
        if bs is None:
            break
        palette = bs["palette"]
        if "data" not in bs or len(palette) == 1:
            return palette[0]
        bits = max(4, (len(palette) - 1).bit_length())
        epl = 64 // bits
        mask = (1 << bits) - 1
        idx = ((y & 15) * 16 + (z & 15)) * 16 + (x & 15)
        long = int(bs["data"][idx // epl]) & 0xFFFFFFFFFFFFFFFF
        return palette[(long >> (bits * (idx % epl))) & mask]
    return Compound({"Name": String("minecraft:air")})


def export(store, lo, hi, out_path, data_version):
    size = (hi[0] - lo[0] + 1, hi[1] - lo[1] + 1, hi[2] - lo[2] + 1)
    bes = store.block_entities()
    palette = []
    palette_key = {}
    blocks = []
    for y in range(lo[1], hi[1] + 1):
        for z in range(lo[2], hi[2] + 1):
            for x in range(lo[0], hi[0] + 1):
                entry = section_palette_entry(store, x, y, z)
                if str(entry["Name"]) == "minecraft:structure_void":
                    continue
                key = nbtlib.serialize_tag(entry)
                if key not in palette_key:
                    palette_key[key] = len(palette)
                    palette.append(entry)
                block = Compound(
                    {
                        "state": Int(palette_key[key]),
                        "pos": List[Int]([x - lo[0], y - lo[1], z - lo[2]]),
                    }
                )
                be = bes.get((x, y, z))
                if be is not None:
                    nbt = Compound({k: v for k, v in be.items() if k not in ("x", "y", "z", "keepPacked")})
                    block["nbt"] = nbt
                blocks.append(block)
    root = Compound(
        {
            "size": List[Int](list(size)),
            "entities": List[Compound]([]),
            "blocks": List[Compound](blocks),
            "palette": List[Compound](palette),
            "DataVersion": Int(data_version),
        }
    )
    f = File(root)
    f.gzipped = True
    f.save(out_path)
    print(f"\n[export] ecrit: {out_path}")
    print(f"[export] size={size}, palette={len(palette)}, blocks={len(blocks)}")
    # sanity re-parse
    back = nbtlib.load(out_path, gzipped=True)
    print(f"[export] re-parse OK: size={[int(v) for v in back['size']]}, "
          f"palette={len(back['palette'])}, blocks={len(back['blocks'])}, "
          f"DataVersion={int(back['DataVersion'])}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bbox", nargs=6, type=int, metavar=("X1", "Y1", "Z1", "X2", "Y2", "Z2"))
    ap.add_argument("--export", metavar="OUT")
    ap.add_argument("--no-expand", action="store_true")
    args = ap.parse_args()

    level = nbtlib.load(WORLD / "level.dat")
    data = level["Data"] if "Data" in level else level[""]["Data"]
    data_version = int(data["DataVersion"])
    print(f"[map] '{data['LevelName']}' DataVersion={data_version}, chunks...")

    store = ChunkStore(WORLD)
    print(f"[map] {len(store.chunks)} chunks charges.")

    if args.bbox:
        lo = tuple(min(args.bbox[i], args.bbox[i + 3]) for i in range(3))
        hi = tuple(max(args.bbox[i], args.bbox[i + 3]) for i in range(3))
        print(f"[bbox] manuel: {lo} -> {hi}")
    else:
        found = store.find_blocks(TARGETS)
        portals = {p for p, n in found.items() if n == "minecraft:nether_portal"}
        print(f"[scan] {len(found)} blocs cibles ({len(portals)} nether_portal, "
              f"{len(found) - len(portals)} obsidian)")
        if not found:
            print("[scan] rien trouve — abandon.")
            sys.exit(1)
        groups = clusters(set(found))
        groups.sort(key=lambda g: (-len(g & portals), -len(g)))
        print(f"[scan] {len(groups)} cluster(s):")
        for i, g in enumerate(groups):
            lo_, hi_ = bbox_of(g)
            print(f"  #{i}: {len(g)} blocs ({len(g & portals)} portal), bbox {lo_} -> {hi_}")
        best = groups[0]
        lo, hi = bbox_of(best)
        print(f"[bbox] cadre retenu (cluster #0): {lo} -> {hi}")
        if not args.no_expand:
            cen = census(store, (lo[0] - 8, lo[1] - 4, lo[2] - 8), (hi[0] + 8, hi[1] + 4, hi[2] + 8))
            print("[census] voisinage (bbox+8):")
            for name, n in cen.most_common(15):
                print(f"    {n:7d}  {name}")
            natural = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air",
                       "minecraft:grass_block", "minecraft:dirt", "minecraft:stone",
                       "minecraft:water", "minecraft:bedrock", "minecraft:sand",
                       "minecraft:short_grass", "minecraft:grass", "minecraft:tall_grass"}
            lo, hi = expand_bbox(store, lo, hi, natural)
            print(f"[bbox] etendu (deco): {lo} -> {hi}")

    size = (hi[0] - lo[0] + 1, hi[1] - lo[1] + 1, hi[2] - lo[2] + 1)
    print(f"\n=== CHECKPOINT ===")
    print(f"bbox: {lo} -> {hi}   taille: {size[0]}x{size[1]}x{size[2]}")
    cen = census(store, lo, hi)
    total = sum(cen.values())
    print(f"census bbox ({total} blocs):")
    for name, n in cen.most_common(30):
        print(f"    {n:7d}  {name}")
    bes = {p: be for p, be in store.block_entities().items()
           if lo[0] <= p[0] <= hi[0] and lo[1] <= p[1] <= hi[1] and lo[2] <= p[2] <= hi[2]}
    print(f"block entities dans la bbox: {len(bes)}")
    for p, be in list(bes.items())[:10]:
        print(f"    {p}: {be.get('id')}")
    # portal plane axis
    axes = Counter()
    for y in range(lo[1], hi[1] + 1):
        for z in range(lo[2], hi[2] + 1):
            for x in range(lo[0], hi[0] + 1):
                if store.block(x, y, z) == "minecraft:nether_portal":
                    e = section_palette_entry(store, x, y, z)
                    props = e.get("Properties", {})
                    axes[str(props.get("axis", "?"))] += 1
    print(f"axe des blocs nether_portal: {dict(axes) or 'AUCUN (portail eteint ?)'}")

    if args.export:
        export(store, lo, hi, args.export, data_version)


if __name__ == "__main__":
    main()
