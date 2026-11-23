"""Verifica los STL imprimibles de hardware/enclosure/print.

Reimporta cada pieza y comprueba que sea cerrada (0 aristas de borde),
manifold (0 aristas no-manifold), de una sola isla conectada y que sus
dimensiones coincidan con lo esperado.
"""

import glob
import os

import bmesh
import bpy
from mathutils import Vector

PRINT_DIR = os.path.join(os.path.dirname(os.path.realpath(__file__)), "print")

# limpia la escena
bpy.ops.wm.read_factory_settings(use_empty=True)

rows = []
problems = []

# caja envolvente esperada por pieza (mm) -> detecta piezas que cambian de forma
EXPECTED = {
    "fifo_round_body.stl": (38.00, 58.00, 15.50),
    "fifo_round_lid.stl": (38.00, 58.00, 7.05),
    "fifo_round_body_full.stl": (38.00, 58.60, 15.50),
    "fifo_round_lid_full.stl": (38.00, 58.00, 7.57),
    "fifo_round_speaker_grille.stl": (17.40, 17.40, 0.90),
    "fifo_round_speaker_ring.stl": (19.40, 19.40, 1.07),
    "fifo_round_speaker_retainer.stl": (19.60, 10.50, 0.50),
    "fifo_round_screen_gasket.stl": (26.20, 14.20, 0.25),
    "fifo_round_usb_bezel.stl": (10.60, 0.62, 4.90),
    "fifo_round_switch_slider.stl": (0.40, 4.80, 3.40),
    "fifo_round_button_up.stl": (9.20, 9.20, 2.40),
    "fifo_round_button_down.stl": (9.20, 9.20, 2.40),
    "fifo_round_button_bezel_up.stl": (10.20, 10.20, 1.10),
    "fifo_round_button_bezel_down.stl": (10.20, 10.20, 1.10),
}

for path in sorted(glob.glob(os.path.join(PRINT_DIR, "*.stl"))):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.wm.stl_import(filepath=path)
    obs = [o for o in bpy.context.scene.objects if o.type == "MESH"]
    name = os.path.basename(path)

    if len(obs) != 1:
        problems.append(f"{name}: {len(obs)} objetos importados (se esperaba 1)")

    tris = 0
    verts = 0
    nonmanifold = 0
    boundary = 0
    volume = 0.0
    islands = 0
    mn = Vector((1e9, 1e9, 1e9))
    mx = Vector((-1e9, -1e9, -1e9))

    for ob in obs:
        me = ob.data
        bm = bmesh.new()
        bm.from_mesh(me)
        bm.transform(ob.matrix_world)
        bm.faces.ensure_lookup_table()
        bm.edges.ensure_lookup_table()
        bm.verts.ensure_lookup_table()

        # islas conectadas: una pieza maciza debe tener exactamente 1
        seen = set()
        for seed in bm.verts:
            if seed.index in seen:
                continue
            islands += 1
            stack = [seed]
            seen.add(seed.index)
            while stack:
                v = stack.pop()
                for e in v.link_edges:
                    w = e.other_vert(v)
                    if w.index not in seen:
                        seen.add(w.index)
                        stack.append(w)

        tris += sum(len(f.verts) - 2 for f in bm.faces)
        verts += len(bm.verts)
        nonmanifold += sum(1 for e in bm.edges if not e.is_manifold)
        boundary += sum(1 for e in bm.edges if e.is_boundary)
        try:
            volume += abs(bm.calc_volume(signed=True))
        except Exception:
            pass

        for v in bm.verts:
            p = v.co
            mn.x = min(mn.x, p.x); mn.y = min(mn.y, p.y); mn.z = min(mn.z, p.z)
            mx.x = max(mx.x, p.x); mx.y = max(mx.y, p.y); mx.z = max(mx.z, p.z)
        bm.free()

    size = mx - mn
    exp = EXPECTED.get(name)
    dev = 0.0
    if exp is None:
        problems.append(f"{name}: sin medidas esperadas")
    else:
        dev = max(abs(size.x - exp[0]), abs(size.y - exp[1]),
                  abs(size.z - exp[2]))
        if dev > 0.05:
            problems.append(f"{name}: bbox "
                            f"{tuple(round(v, 2) for v in size)} != {exp}")
    rows.append(
        f"{name:34s} tris={tris:7d} verts={verts:7d} "
        f"nonman={nonmanifold:4d} bound={boundary:4d} isl={islands:2d} "
        f"vol={volume:9.2f}mm3 "
        f"bbox=({size.x:5.2f},{size.y:5.2f},{size.z:5.2f}) dev={dev:.3f}"
    )
    if nonmanifold or boundary:
        problems.append(f"{name}: nonmanifold={nonmanifold} boundary={boundary}")
    if islands != 1:
        problems.append(f"{name}: {islands} islas (geometria suelta)")

print("=" * 110)
for r in rows:
    print(r)
print("=" * 110)
if problems:
    print("[stl] PROBLEMAS:")
    for p in problems:
        print("  -", p)
else:
    print(f"[stl] OK: {len(rows)} piezas cerradas y manifold")
