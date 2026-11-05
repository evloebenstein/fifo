"""Llavero Fifo (kit XIAO ESP32-S3) en mm, parametrico y verificable.

    blender --background --factory-startup --python hardware/enclosure/build_fifo.py

Caja exterior 40 (X) x 60 (Y) x 26 (Z) con esquinas R5 y trasera redondeada.
Eje Z = profundidad (fondo = 0); la cara frontal mira hacia +Z.

    cuerpo    Z = -1.50 .. 23.00   (piso 1.5, paredes 1.5, frente abierto)
    frente    Z = 23.00 .. 24.50   (placa de 1.5 con ventanas y rejilla)

Sandwich interno a lo largo de Z, con 1 mm de aire entre capas:

    Z 0..9      LiPo 902030 20x30x9, centrada en X=0
    Z 10..18    GPS ATGM336H (mitad superior Y) / XIAO (mitad inferior Y)
    Z 12..16    MAX98357A y SS12D07 en los laterales pegados a las paredes
    Z 15..23    OLED 0.96, parlante, micro y pulsadores (cara frontal)

Reglas de diseno:
  - Cilindros para acustica (parlante 16x9x3, micro 5x7x2.5) y vastagos.
  - Prismas compuestos para placas y conectores (OLED, XIAO, GPS, USB-C).
  - El USB-C queda matematicamente centrado en X=0 y sale por la pared -Y.
  - Los puertos se abren con cortadores independientes; los componentes
    nunca sirven como cortador.
  - La tapa de acrilico sobre la pantalla sobresale 0.5 mm, con el borde
    redondeado, como el concepto.
  - El parlante lleva un aro en relieve y una tapa plana perforada.
"""

import math
import os

import bpy
import bmesh
from mathutils import Matrix, Vector

OUT_DIR = os.path.dirname(os.path.abspath(__file__))
QUICK = os.environ.get("FIFO_QUICK") == "1"
# chunk 1
BODY_W = 40.0
BODY_L = 60.0
BODY_H = 26.0
CORNER_R = 5.0
WALL = 1.5
EXT_X = BODY_W / 2
EXT_Y = BODY_L / 2
EXT_Z0 = -WALL
BODY_Z1 = 23.0
IN_X = EXT_X - WALL
IN_Y = EXT_Y - WALL
IN_Z0 = 0.0
FACE_Z0 = 23.0
FACE_Z1 = 24.5
LIPO_Z0, LIPO_H = 0.0, 9.0
GPS_Z0, GPS_PCB_H = 10.0, 1.5
ANT_Z0, ANT_H = 11.5, 4.0
XIAO_Z0, XIAO_PCB_H = 10.0, 1.5
AMP_Z0, AMP_H = 12.0, 2.0
AMP_PCB_H = 1.2
SW_Z0, SW_H = 12.0, 4.0
OLED_Z0, OLED_PCB_H = 19.5, 1.5
GLASS_Z0, GLASS_H = 21.0, 2.25
SPK_Z0, SPK_H = 16.0, 3.0
MIC_Z0, MIC_H = 17.0, 2.5
BTN_Z0, BTN_H = 19.5, 3.0
STEM_Z0, STEM_H = 22.5, 2.0
GPS_C = (0.0, 15.0)
GPS_W, GPS_L = 25.0, 25.0
ANT_W, ANT_L = 12.0, 12.0
XIAO_C = (0.0, -16.5)
XIAO_W, XIAO_L = 17.5, 21.0
USB_C = (0.0, -27.65)
USB_CUT = (10.0, 5.0, 4.0)
USB_CUT_C = (0.0, -29.5, 8.0)
AMP_C = (11.0, -8.0)
AMP_W, AMP_L = 15.0, 18.0
SW_C = (-16.5, -8.0)
SW_BW, SW_BL = 4.0, 8.0
LEVER_C = (-19.5, -8.0, 13.5)
LEVER_W, LEVER_L, LEVER_H = 2.0, 2.0, 3.0
SW_CUT = (2.5, 4.0, 3.0)
SW_CUT_C = (-19.25, -8.0, 13.5)
OLED_C = (0.0, 14.0)
OLED_W, OLED_L = 27.0, 27.0
ACRY_W, ACRY_L = 21.6, 11.6
ACRY_Z0, ACRY_Z1 = 23.30, 25.00
ACRY_BEVEL = 0.35
WIN_W, WIN_L, WIN_R = 22.5, 12.5, 3.0
SPK_C = (-8.5, -9.5)
SPK_W, SPK_L = 15.0, 9.0
GRILLE_R = 7.5
GRILLE_HOLE = 0.8
MIC_C = (2.0, -3.0)
MIC_W, MIC_L, MIC_H = 5.0, 7.0, 2.5
MIC_PORT = MIC_C
BTN1_C = (13.0, -7.0)
BTN2_C = (13.0, -16.0)
BTN_W, BTN_L = 6.0, 6.0
STEM_R = 1.5
BTN_BORE_R = 1.75
TOL = 0.30
INK = (0.030, 0.042, 0.062, 1.0)
BTN_CAP = (0.030, 0.033, 0.045, 1.0)
STEEL = (0.52, 0.54, 0.57, 1.0)
PCB_TEAL = (0.022, 0.115, 0.098, 1.0)
PCB_GREEN = (0.035, 0.155, 0.085, 1.0)
PCB_BLACK = (0.030, 0.032, 0.038, 1.0)
GOLD = (0.72, 0.55, 0.20, 1.0)
PRINT_WHITE = (0.90, 0.92, 0.95, 1.0)
COLS = {}
PARTS = {"body": [], "face": [], "internals": [], "studio": []}
LAYOUT = (
    ("LiPo_902030", 20.0, 30.0, 9.0, 0.0, 0.0, LIPO_Z0, "battery"),
    ("GPS_ATGM336H", GPS_W, GPS_L, GPS_PCB_H, GPS_C[0], GPS_C[1],
     GPS_Z0, "gps"),
    ("GPS_Antenna", ANT_W, ANT_L, ANT_H, GPS_C[0], GPS_C[1],
     ANT_Z0, "ceramic"),
    ("XIAO_ESP32S3", XIAO_W, XIAO_L, XIAO_PCB_H, XIAO_C[0], XIAO_C[1],
     XIAO_Z0, "pcb"),
    ("MAX98357A", AMP_W, AMP_L, AMP_PCB_H, AMP_C[0], AMP_C[1], AMP_Z0, "pcb_amp"),
    ("Switch_Body", SW_BW, SW_BL, SW_H, SW_C[0], SW_C[1], SW_Z0, "switch"),
    ("Switch_Lever", LEVER_W, LEVER_L, LEVER_H,
     LEVER_C[0], LEVER_C[1], LEVER_C[2], "steel"),
    ("OLED_096", OLED_W, OLED_L, OLED_PCB_H, OLED_C[0], OLED_C[1],
     OLED_Z0, "pcb_oled"),
    ("OLED_Glass", ACRY_W, ACRY_L, GLASS_H, OLED_C[0], OLED_C[1],
     GLASS_Z0, "glass"),
    ("Speaker_Driver", SPK_W, SPK_L, SPK_H, SPK_C[0], SPK_C[1],
     SPK_Z0, "driver"),
    ("INMP441", MIC_W, MIC_L, MIC_H, MIC_C[0], MIC_C[1],
     MIC_Z0, "pcb_mic"),
    ("Tact_Btn1", BTN_W, BTN_L, BTN_H, BTN1_C[0], BTN1_C[1], BTN_Z0, "switch"),
    ("Tact_Btn2", BTN_W, BTN_L, BTN_H, BTN2_C[0], BTN2_C[1], BTN_Z0, "switch"),
    ("XIAO_FPC_Ant", 7.0, 4.0, 0.4, -5.0, -23.5, 11.5, "flex"),
)
TOUCHING = {
    frozenset(("GPS_ATGM336H", "GPS_Antenna")),
    frozenset(("OLED_096", "OLED_Glass")),
}
ROUND = set()


def collection(name):
    if name in COLS:
        return COLS[name]
    col = bpy.data.collections.new(name)
    bpy.context.scene.collection.children.link(col)
    COLS[name] = col
    return col


def add(name, mesh, col, group, smooth=True):
    ob = bpy.data.objects.new(name, mesh)
    col.objects.link(ob)
    if smooth:
        for p in mesh.polygons:
            p.use_smooth = True
    if group:
        PARTS[group].append(ob)
    return ob


def select_only(*objs):
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs:
        o.select_set(True)
    if objs:
        bpy.context.view_layer.objects.active = objs[-1]


def rounded_rect_pts(w, l, r, seg=10):
    r = min(r, w / 2 - 0.01, l / 2 - 0.01)
    hw, hl = w / 2, l / 2
    cx, cy = hw - r, hl - r
    pts = []
    for ccx, ccy, a0 in ((cx, -cy, -90), (cx, cy, 0), (-cx, cy, 90), (-cx, -cy, 180)):
        for i in range(seg + 1):
            a = math.radians(a0 + 90.0 * i / seg)
            pts.append((ccx + r * math.cos(a), ccy + r * math.sin(a)))
    return pts
def prism(name, w, l, r, z0, z1, col, group=None, seg=10,
          bevel_bottom=0.0, bevel_top=0.0, bevel_seg=3):
    bm = bmesh.new()
    pts = rounded_rect_pts(w, l, r, seg)
    verts = [bm.verts.new((x, y, z0)) for x, y in pts]
    face = bm.faces.new(verts)
    res = bmesh.ops.extrude_face_region(bm, geom=[face])
    top = [g for g in res["geom"] if isinstance(g, bmesh.types.BMVert)]
    bmesh.ops.translate(bm, verts=top, vec=(0, 0, z1 - z0))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    for z, width in ((z0, bevel_bottom), (z1, bevel_top)):
        if width > 0:
            edges = [e for e in bm.edges
                     if all(abs(v.co.z - z) < 1e-5 for v in e.verts)]
            if edges:
                bmesh.ops.bevel(bm, geom=edges, offset=width, segments=bevel_seg,
                                affect="EDGES", clamp_overlap=True, profile=0.5)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    return add(name, me, col, group)


def disc(name, radius, z0, z1, cx, cy, col, group=None, verts=64, smooth=True):
    bm = bmesh.new()
    mat = Matrix.Translation((cx, cy, (z0 + z1) / 2.0))
    bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=verts,
                          radius1=radius, radius2=radius, depth=(z1 - z0),
                          matrix=mat)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    return add(name, me, col, group, smooth=smooth)


def block(name, w, l, h, cx, cy, z0, col, group=None):
    bm = bmesh.new()
    mat = Matrix.Translation((cx, cy, z0 + h / 2.0)) @ Matrix.Diagonal(
        Vector((w, l, h, 1.0)))
    bmesh.ops.create_cube(bm, size=1.0, matrix=mat)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    return add(name, me, col, group, smooth=False)


def tube_y(name, radius, y0, y1, cx, cy, cz, col, group=None, verts=32):
    bm = bmesh.new()
    mat = (Matrix.Translation((cx, cy, cz)) @
           Matrix.Rotation(math.radians(90), 4, "X"))
    bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=verts,
                          radius1=radius, radius2=radius, depth=(y1 - y0),
                          matrix=mat)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    return add(name, me, col, group)


def cut(target, cutters, name="cut"):
    if not isinstance(cutters, (list, tuple)):
        cutters = [cutters]
    for i, c in enumerate(cutters):
        mod = target.modifiers.new(f"{name}{i}", "BOOLEAN")
        mod.operation = "DIFFERENCE"
        mod.object = c
        mod.solver = "EXACT"
        mod.use_self = True
        select_only(target)
        bpy.ops.object.modifier_apply(modifier=mod.name)
        mesh = c.data
        # 将被删除的 cutter 从所有 PARTS 列表中清理，避免后续引用失效。
        for lst in PARTS.values():
            while c in lst:
                lst.remove(c)
        bpy.data.objects.remove(c, do_unlink=True)
        if mesh.users == 0:
            bpy.data.meshes.remove(mesh)
    return target


def join(objs, name, col, group=None):
    select_only(*objs)
    bpy.ops.object.join()
    out = bpy.context.active_object
    out.name = name
    if group:
        PARTS[group].append(out)
    return out


def assign(ob, mat):
    ob.data.materials.clear()
    ob.data.materials.append(mat)


def shade_smooth(ob, angle_deg=30.0):
    for p in ob.data.polygons:
        p.use_smooth = True
    if hasattr(ob.data, "use_auto_smooth"):
        try:
            ob.data.use_auto_smooth = True
            ob.data.auto_smooth_angle = math.radians(angle_deg)
        except Exception:
            pass
def principled(name, color, rough=0.5, metal=0.0, emit=None, emit_strength=0.0,
               transmission=0.0, coat=0.0, coat_rough=0.08, ior=1.45, spec=None):
    mat = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    if bsdf is None:
        return mat

    def put(names, value):
        for n in names:
            sock = bsdf.inputs.get(n)
            if sock is None:
                continue
            try:
                sock.default_value = value
                return True
            except Exception:
                continue
        return False

    put(("Base Color",), color)
    put(("Roughness",), rough)
    put(("Metallic",), metal)
    put(("IOR",), ior)
    if spec is not None:
        put(("Specular IOR Level", "Specular"), spec)
    put(("Emission Color", "Emit"), emit if emit else (0, 0, 0, 1))
    put(("Emission Strength",), emit_strength if emit else 0.0)
    put(("Transmission Weight", "Transmission"), transmission)
    put(("Coat Weight",), coat)
    put(("Coat Roughness",), coat_rough)
    return mat
def make_materials():
    return {
        "shell": principled("Carcasa_Midnight", (0.048, 0.058, 0.105, 1.0),
                            rough=0.42, spec=0.28),
        "bezel": principled("Bisel_Negro", (0.021, 0.025, 0.043, 1.0),
                            rough=0.38, spec=0.25),
        "glass": principled("Bisel_Cristal", (0.006, 0.008, 0.012, 1.0),
                            rough=0.60, spec=0.05),
        "clear": principled("Vidrio_Limpio", (1.0, 1.0, 1.0, 1.0),
                            rough=0.03, transmission=1.0, ior=1.49,
                            spec=0.02),
        "lcd": principled("OLED_Blanco", (0.82, 0.87, 0.95, 1.0), rough=0.35,
                          emit=(0.72, 0.87, 1.0, 1.0), emit_strength=1.10),
        "lcd_on": principled("OLED_Activo", (0.74, 0.80, 0.89, 1.0),
                             rough=0.28, emit=(0.82, 0.90, 1.0, 1.0),
                             emit_strength=1.30),
        "lcd_off": principled("OLED_Apagado", (0.004, 0.004, 0.006, 1.0),
                              rough=0.25),
        "ink": principled("Tinta", INK, rough=0.55),
        "ink_dark": principled("Tinta_OLED", (0.016, 0.019, 0.026, 1.0),
                               rough=0.55),
        "print": principled("Serigrafia", PRINT_WHITE, rough=0.55),
        "print_dim": principled("Serigrafia_Tenue", (0.55, 0.58, 0.64, 1.0),
                                rough=0.60),
        "grille": principled("Rejilla_Aro", (0.24, 0.25, 0.28, 1.0),
                             rough=0.32, metal=1.0),
        "btn_cap": principled("Boton_Cap", BTN_CAP, rough=0.42, coat=0.10,
                              spec=0.25),
        "pcb": principled("PCB_Teal", PCB_TEAL, rough=0.55, spec=0.18),
        "pcb_mic": principled("PCB_Verde", PCB_GREEN, rough=0.52, spec=0.18),
        "pcb_amp": principled("PCB_Negro", PCB_BLACK, rough=0.50, spec=0.16),
        "pcb_oled": principled("PCB_OLED", (0.012, 0.012, 0.016, 1.0),
                               rough=0.48, spec=0.14),
        "foil": principled("Blindaje", (0.78, 0.79, 0.81, 1.0), rough=0.24,
                           metal=1.0),
        "battery": principled("Bateria", (0.13, 0.24, 0.44, 1.0), rough=0.38),
        "gps": principled("GPS_PCB", (0.045, 0.075, 0.14, 1.0), rough=0.45),
        "ceramic": principled("Antena_Ceramica", (0.58, 0.57, 0.53, 1.0),
                              rough=0.65),
        "steel": principled("Acero", STEEL, rough=0.25, metal=1.0),
        "driver": principled("Parlante", (0.035, 0.035, 0.042, 1.0), rough=0.6),
        "rubber": principled("Goma", (0.018, 0.018, 0.021, 1.0), rough=0.8),
        "switch": principled("Switch_Negro", (0.020, 0.021, 0.026, 1.0),
                             rough=0.42),
        "gold": principled("Oro", GOLD, rough=0.28, metal=1.0),
        "flex": principled("FPC_Flex", (0.055, 0.075, 0.055, 1.0), rough=0.55),
        "wire_red": principled("Cable_Rojo", (0.30, 0.025, 0.02, 1.0),
                               rough=0.45),
        "wire_black": principled("Cable_Negro", (0.014, 0.014, 0.016, 1.0),
                                 rough=0.45),
        "wire_amber": principled("Cable_Ambar", (0.42, 0.24, 0.03, 1.0),
                                 rough=0.45),
        "wire_blue": principled("Cable_Azul", (0.03, 0.10, 0.32, 1.0),
                                rough=0.45),
        "backdrop": principled("Fondo", (0.012, 0.013, 0.017, 1.0), rough=0.30),
    }
def build_body(mats):
    col = collection("01_Cuerpo")

    body = prism("Fifo_Body", BODY_W, BODY_L, CORNER_R,
                 EXT_Z0, BODY_Z1, col, "body",
                 bevel_bottom=5.0, seg=12)
    assign(body, mats["shell"])

    cav = prism("cav", 2 * IN_X, 2 * IN_Y, CORNER_R - WALL,
                IN_Z0, BODY_Z1 + 0.5, col, seg=12)
    cut(body, cav, "cav")

    usb = prism("usb", USB_CUT[0], USB_CUT[1], 0.6,
                USB_CUT_C[1] - USB_CUT[1] / 2,
                USB_CUT_C[1] + USB_CUT[1] / 2, col)
    usb.location = (USB_CUT_C[0], -EXT_Y, 0.0)
    select_only(usb)
    bpy.ops.object.transform_apply(location=True)
    cut(body, usb, "usb")

    sw = block("sw_cut", SW_CUT[0], SW_CUT[1], SW_CUT[2],
               SW_CUT_C[0], SW_CUT_C[1], SW_CUT_C[2], col)
    cut(body, sw, "sw")

    shade_smooth(body)
    return body


def build_face(mats):
    col = collection("02_Frente")

    face = prism("Fifo_Face", BODY_W, BODY_L, CORNER_R,
                 FACE_Z0, FACE_Z1, col, "face",
                 bevel_top=1.0, seg=12)
    assign(face, mats["shell"])

    win = prism("win", WIN_W, WIN_L, WIN_R,
                FACE_Z0 - 0.5, FACE_Z1 + 1.0, col, seg=10)
    win.location = (OLED_C[0], OLED_C[1], 0.0)
    select_only(win)
    bpy.ops.object.transform_apply(location=True)
    cut(face, win, "screen")

    bez = prism("Screen_Bezel", WIN_W, WIN_L, WIN_R,
                23.30, FACE_Z1, col, "face", seg=12)
    bez.location = (OLED_C[0], OLED_C[1], 0.0)
    select_only(bez)
    bpy.ops.object.transform_apply(location=True)
    bhole = prism("bez_i", WIN_W - 0.7, WIN_L - 0.7, WIN_R - 0.55,
                  23.00, FACE_Z1 + 0.5, col, seg=12)
    bhole.location = (OLED_C[0], OLED_C[1], 0.0)
    select_only(bhole)
    bpy.ops.object.transform_apply(location=True)
    cut(bez, bhole, "bez")
    assign(bez, mats["glass"])
    shade_smooth(bez)

    rim = prism("Spk_Rim", 18.0, 12.0, 2.5, 23.50, 24.90, col, "face", seg=12)
    rim.location = (SPK_C[0], SPK_C[1], 0.0)
    select_only(rim)
    bpy.ops.object.transform_apply(location=True)
    rhole = prism("rh_i", 15.2, 8.2, 1.5, 23.00, 25.00, col, seg=12)
    rhole.location = (SPK_C[0], SPK_C[1], 0.0)
    select_only(rhole)
    bpy.ops.object.transform_apply(location=True)
    cut(rim, rhole, "rim")
    assign(rim, mats["shell"])
    shade_smooth(rim)

    plate = prism("Spk_Grille", 15.0, 8.0, 1.5, 24.40, 24.85, col, "face", seg=10)
    plate.location = (SPK_C[0], SPK_C[1], 0.0)
    select_only(plate)
    bpy.ops.object.transform_apply(location=True)
    holes = []
    pitch = 1.6
    for j in range(-6, 7):
        for i in range(-6, 7):
            x = SPK_C[0] + i * pitch + (pitch / 2 if j % 2 else 0.0)
            y = SPK_C[1] + j * pitch * 0.866
            if math.hypot(x - SPK_C[0], y - SPK_C[1]) > 6.3:
                continue
            holes.append(disc("gh", GRILLE_HOLE / 2, 23.00, 25.00,
                              x, y, col, verts=8))
    if holes:
        cut(plate, join(holes, "Grille_Holes", col), "gholes")
    assign(plate, mats["grille"])
    shade_smooth(plate)

    bores = [disc("btn_bore", BTN_BORE_R + TOL / 2, FACE_Z0 - 0.5,
                  FACE_Z1 + 1.0, c[0], c[1], col, verts=32)
             for c in (BTN1_C, BTN2_C)]
    cut(face, bores, "btn")

    mic = disc("mic_port", 0.30, FACE_Z0 - 0.5, FACE_Z1 + 1.0,
               MIC_PORT[0], MIC_PORT[1], col, verts=24)
    cut(face, mic, "micport")

    text("ALTAVOZ", 1.20, (SPK_C[0], SPK_C[1] - 8.6, FACE_Z1 + 0.015),
         mats["print_dim"], col, "face", space=1.30)

    shade_smooth(face)
    return face
def text(body, size, loc, mat, col, group=None, space=1.0):
    cu = bpy.data.curves.new("txt_" + body[:6], "FONT")
    cu.body = body
    cu.size = size
    cu.align_x = "CENTER"
    cu.align_y = "CENTER"
    cu.extrude = 0.03
    cu.offset = 0.0
    cu.space_character = space
    cu.resolution_u = 3
    ob = bpy.data.objects.new("TXT_" + body[:10], cu)
    ob.location = loc
    col.objects.link(ob)
    if group:
        PARTS[group].append(ob)
    if mat:
        assign(ob, mat)
    return ob


def build_screen(mats):
    col = collection("04_Pantalla")

    lens = prism("Acrylic_Lens", ACRY_W, ACRY_L, 2.5,
                 ACRY_Z0, ACRY_Z1, col, "face", seg=12,
                 bevel_top=ACRY_BEVEL, bevel_seg=3)
    assign(lens, mats["clear"])
    shade_smooth(lens)
    lens.location = (OLED_C[0], OLED_C[1], 0.0)

    cx, cy = OLED_C
    face_z = GLASS_Z0 + GLASS_H + 0.01
    for ex in (cx - 4.2, cx + 4.2):
        cu = bpy.data.curves.new("eye", "CURVE")
        cu.dimensions = "3D"
        sp = cu.splines.new("POLY")
        pts = []
        for k in range(15):
            a = math.radians(180 - k * (180 / 14))
            pts.append((ex + 2.0 * math.cos(a),
                        cy + 0.55 + 1.25 * math.sin(a)))
        sp.points.add(len(pts) - 1)
        for p, (px, py) in zip(sp.points, pts):
            p.co = (px, py, 0, 1)
        cu.bevel_depth = 0.30
        cu.bevel_resolution = 2
        cu.use_fill_caps = True
        ob = bpy.data.objects.new("eye", cu)
        ob.location.z = face_z
        col.objects.link(ob)
        PARTS["internals"].append(ob)
        assign(ob, mats["ink_dark"])

    cu = bpy.data.curves.new("smile", "CURVE")
    cu.dimensions = "3D"
    sp = cu.splines.new("POLY")
    pts = []
    for k in range(23):
        t = -1 + k * (2 / 22)
        pts.append((cx + 4.6 * t, cy - 2.55 + 1.05 * (t * t)))
    sp.points.add(len(pts) - 1)
    for p, (px, py) in zip(sp.points, pts):
        p.co = (px, py, 0, 1)
    cu.bevel_depth = 0.30
    cu.bevel_resolution = 2
    cu.use_fill_caps = True
    smile = bpy.data.objects.new("smile", cu)
    smile.location.z = face_z
    col.objects.link(smile)
    PARTS["internals"].append(smile)
    assign(smile, mats["ink_dark"])

    return lens


def build_buttons(mats):
    col = collection("05_Botones")

    def cap(name, c):
        ring_o = disc(name + "_ring_o", 2.25, FACE_Z0 - 0.4, FACE_Z1 + 0.02,
                      c[0], c[1], col, "face", verts=48)
        ring_i = disc(name + "_ring_i", 1.85, FACE_Z0 - 0.4, FACE_Z1 + 1.0,
                      c[0], c[1], col, verts=48)
        cut(ring_o, ring_i, "ring")
        assign(ring_o, mats["bezel"])
        shade_smooth(ring_o)

        ob = disc(name, 1.70, FACE_Z1 - 0.05, FACE_Z1 + 0.42,
                  c[0], c[1], col, "face", verts=48)
        bev = ob.modifiers.new("b", "BEVEL")
        bev.width = 0.16
        bev.segments = 3
        bev.limit_method = "ANGLE"
        bev.angle_limit = math.radians(35)
        select_only(ob)
        bpy.ops.object.modifier_apply(modifier=bev.name)
        assign(ob, mats["btn_cap"])
        shade_smooth(ob)

        stem = disc(name + "_stem", STEM_R, STEM_Z0, STEM_Z0 + STEM_H,
                    c[0], c[1], col, "internals", verts=32)
        assign(stem, mats["switch"])

    cap("Button_Up", BTN1_C)
    cap("Button_Down", BTN2_C)
def build_internals(mats):
    col = collection("06_Internos")
    parts = {}
    for name, w, l, h, cx, cy, z0, mat in LAYOUT:
        if name.startswith("Tact_"):
            continue
        ob = block(name, w, l, h, cx, cy, z0, col, "internals")
        assign(ob, mats[mat])
        parts[name] = ob

    lab = block("LiPo_Label", 17.0, 11.0, 0.15, 0.0, 1.0,
                LIPO_Z0 + LIPO_H - 0.12, col, "internals")
    assign(lab, mats["gps"])
    jst = block("LiPo_JST", 5.0, 3.5, 2.0, 0.0, -13.5,
                LIPO_Z0 + LIPO_H - 2.0, col, "internals")
    assign(jst, mats["steel"])

    gps_conn = block("GPS_Conn", 5.0, 2.5, 1.5, 0.0, GPS_C[1] - 10.6,
                     GPS_Z0 + 0.05, col, "internals")
    assign(gps_conn, mats["steel"])

    for k in range(6):
        x = XIAO_C[0] + (k - 2.5) * 2.6
        for sy in (-1, 1):
            pad = block("pad", 1.3, 0.8, 0.15, x, XIAO_C[1] + sy * 10.1,
                        XIAO_Z0 + XIAO_PCB_H - 0.12, col, "internals")
            assign(pad, mats["gold"])
    shield = block("RF_Shield", 9.0, 10.0, 0.2, XIAO_C[0], XIAO_C[1] + 1.0,
                   XIAO_Z0 + XIAO_PCB_H - 0.1, col, "internals")
    assign(shield, mats["foil"])
    led = block("LED", 0.9, 1.2, 0.2, XIAO_C[0] + 6.0, XIAO_C[1] + 6.0,
                XIAO_Z0 + XIAO_PCB_H - 0.1, col, "internals")
    assign(led, mats["btn_cap"])

    usb_cz = 10.0
    ushield = block("USB_C_Shield", 9.4, 2.7, 3.4, 0.0, -28.35,
                    usb_cz - 1.7, col, "internals")
    assign(ushield, mats["steel"])
    throat = tube_y("USB_C_Throat", 1.55, -30.0, -26.0, 0.0, -28.35,
                    usb_cz, col, "internals", verts=32)
    cut(ushield, throat, "usbthroat")
    tongue = block("USB_C_Tongue", 6.6, 2.0, 0.9, 0.0, -28.35,
                   usb_cz - 0.45, col, "internals")
    assign(tongue, mats["switch"])

    fpc = prism("XIAO_FPC_Ant_Patch", 7.0, 4.0, 0.4, 11.5, 11.9,
                col, "internals", seg=6)
    fpc.location = (-5.0, -23.5, 0.0)
    select_only(fpc)
    bpy.ops.object.transform_apply(location=True)
    assign(fpc, mats["flex"])
    ant = block("XIAO_FPC_Ant_Trace", 3.0, 1.0, 0.06, -5.0, -23.5, 11.9,
                col, "internals")
    assign(ant, mats["foil"])

    gps_smd = (
        (-9.0, 8.0, 1.6, 0.9, "ceramic"), (-9.0, 11.0, 1.2, 0.8, "switch"),
        (-9.0, 14.0, 1.6, 0.9, "ceramic"), (-9.0, 17.0, 1.2, 0.8, "switch"),
        (9.0, 8.0, 1.6, 0.9, "switch"), (9.0, 11.0, 1.2, 0.8, "ceramic"),
        (9.0, 14.0, 1.6, 0.9, "ceramic"), (9.0, 17.0, 1.2, 0.8, "switch"),
        (0.0, 4.4, 2.0, 1.2, "switch"), (3.4, 2.5, 1.2, 0.8, "ceramic"),
    )
    for sx, sy, sw, sl, key in gps_smd:
        part = block("SMD_GPS", sw, sl, 0.45, sx, sy, GPS_Z0 + GPS_PCB_H,
                     col, "internals")
        assign(part, mats[key])

    base = block("GPS_Ant_Base", ANT_W + 0.6, ANT_L + 0.6, 0.2,
                 GPS_C[0], GPS_C[1], ANT_Z0 - 0.2, col, "internals")
    assign(base, mats["foil"])

    chip = block("Amp_Chip", 5.0, 5.0, AMP_H - AMP_PCB_H, AMP_C[0], AMP_C[1],
                 AMP_Z0 + AMP_PCB_H, col, "internals")
    assign(chip, mats["switch"])
    term = block("Amp_Terminal", 3.5, 4.0, AMP_H - AMP_PCB_H, AMP_C[0] + 5.0,
                 AMP_C[1], AMP_Z0 + AMP_PCB_H, col, "internals")
    assign(term, mats["pcb_mic"])

    for sx, sy, sw, sl, key in ((6.0, -14.0, 1.4, 0.9, "ceramic"),
                                (8.2, -14.0, 1.4, 0.9, "switch"),
                                (6.0, -2.0, 1.6, 1.0, "ceramic"),
                                (9.0, -2.0, 1.0, 0.8, "switch")):
        part = block("SMD_Amp", sw, sl, 0.45, sx, sy, AMP_Z0 + AMP_PCB_H,
                     col, "internals")
        assign(part, mats[key])
    lcd = block("OLED_LCD", ACRY_W - 0.2, ACRY_L - 0.2, 0.08,
                    OLED_C[0], OLED_C[1], GLASS_Z0 + GLASS_H - 0.08,
                    col, "internals")
    assign(lcd, mats["lcd_off"])

    spk = prism("Speaker_Driver", SPK_W, SPK_L, 2.0, SPK_Z0,
                SPK_Z0 + SPK_H, col, "internals", seg=10, bevel_top=0.6)
    assign(spk, mats["driver"])
    rim_o = prism("Spk_Rim_o", SPK_W + 1.4, SPK_L + 1.4, 1.2,
                  SPK_Z0 + SPK_H, SPK_Z0 + SPK_H + 1.2, col, "internals",
                  seg=10, bevel_top=0.5)
    rim_i = prism("Spk_Rim_i", SPK_W - 0.6, SPK_L - 0.6, 1.6,
                  SPK_Z0 + SPK_H - 0.2, SPK_Z0 + SPK_H + 1.4,
                  col, "internals", seg=10)
    cut(rim_o, rim_i, "spkrim")
    assign(rim_o, mats["steel"])
    shade_smooth(rim_o)

    mic = prism("INMP441", MIC_W, MIC_L, 0.6, MIC_Z0, MIC_Z0 + MIC_H,
                col, "internals", seg=8)
    assign(mic, mats["pcb_mic"])
    port = disc("Mic_Port", 0.35, MIC_Z0 + MIC_H, MIC_Z0 + MIC_H + 0.05,
                MIC_C[0], MIC_C[1], col, "internals", verts=24)
    assign(port, mats["rubber"])
    for name, c in (("Tact_Btn1", BTN1_C), ("Tact_Btn2", BTN2_C)):
        sw = block(name, BTN_W, BTN_L, BTN_H, c[0], c[1],
                   BTN_Z0, col, "internals")
        assign(sw, mats["switch"])
        stem = disc(name + "_stem", STEM_R, STEM_Z0, STEM_Z0 + STEM_H,
                    c[0], c[1], col, "internals", verts=32)
        assign(stem, mats["btn_cap"])

    for ob in PARTS["internals"]:
        ob["layer"] = layer_of(ob.name)

    return parts
LAYER_TAGS = {
    0: {"LiPo_902030", "LiPo_Label", "LiPo_JST"},
    1: {"GPS_ATGM336H", "GPS_Antenna", "GPS_Conn", "XIAO_ESP32S3",
        "USB_C_Shield", "USB_C_Tongue", "RF_Shield", "LED", "pad",
        "XIAO_FPC_Ant_Patch", "XIAO_FPC_Ant_Trace"},
    2: {"MAX98357A", "Amp_Chip", "Amp_Terminal", "Switch_Body",
        "Switch_Lever", "SMD_Amp"},
    3: {"Speaker_Driver", "Spk_Rim_o", "INMP441", "Mic_Port",
        "Tact_Btn1", "Tact_Btn2", "Tact_Btn1_stem", "Tact_Btn2_stem",
        "SMD_GPS"},
    4: {"OLED_096", "OLED_Glass", "OLED_LCD", "eye", "smile"},
}


def layer_of(name):
    if name.startswith("pad"):
        return 1
    base = name.split(".")[0]
    for idx, names in LAYER_TAGS.items():
        if name in names or base in names:
            return idx
    return 1


def auto_smooth_all(angle=32.0):
    for ob in list(bpy.data.objects):
        if ob.type != "MESH":
            continue
        for p in ob.data.polygons:
            if abs(p.normal.z) > 0.999:
                p.use_smooth = False


def aabb(name, w, l, h, cx, cy, z0):
    return (name, cx - w / 2, cx + w / 2, cy - l / 2, cy + l / 2, z0, z0 + h)


def circ_rect_dist(cx, cy, r, x0, x1, y0, y1):
    dx = max(x0 - cx, 0.0, cx - x1)
    dy = max(y0 - cy, 0.0, cy - y1)
    return math.hypot(dx, dy)


def check_layout():
    boxes = [aabb(*row[:7]) for row in LAYOUT]
    problems = []
    for i, a in enumerate(boxes):
        for b in boxes[i + 1:]:
            if frozenset((a[0], b[0])) in TOUCHING:
                continue
            ox = min(a[2], b[2]) - max(a[1], b[1])
            oy = min(a[4], b[4]) - max(a[3], b[3])
            oz = min(a[6], b[6]) - max(a[5], b[5])
            if ox > 0.05 and oy > 0.05 and oz > 0.05:
                if a[0] in ROUND or b[0] in ROUND:
                    rn = a[0] if a[0] in ROUND else b[0]
                    r2n = b[0] if a[0] in ROUND else a[0]
                    rb = a if a[0] == rn else b
                    r2b = b if a[0] == rn else a
                    rcx = (rb[1] + rb[2]) / 2.0
                    rcy = (rb[3] + rb[4]) / 2.0
                    rr = (rb[2] - rb[1]) / 2.0
                    d = circ_rect_dist(rcx, rcy, rr, r2b[1], r2b[2],
                                       r2b[3], r2b[4])
                    if d >= rr - 0.05:
                        continue
                problems.append((a[0], b[0], ox, oy, oz))
    if problems:
        print("[layout] INTERPENETRACION:")
        for n1, n2, ox, oy, oz in problems:
            print(f"  - {n1} <-> {n2}:  {ox:.2f} x {oy:.2f} x {oz:.2f} mm")
    else:
        print("[layout] OK: 0 interpenetraciones entre piezas")
    return problems
def look_at(ob, target):
    d = Vector(target) - ob.location
    ob.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()


def studio():
    col = collection("00_Estudio")

    floor = block("Backdrop", 1400, 1400, 2.0, 0, 0, -2.05, col, "studio")
    assign(floor, bpy.data.materials["Fondo"])

    def area(name, loc, target, size, power, color=(1, 1, 1)):
        d = bpy.data.lights.new(name, "AREA")
        d.shape = "RECTANGLE"
        d.size = size
        d.size_y = size * 0.8
        d.energy = power
        d.color = color
        ob = bpy.data.objects.new(name, d)
        ob.location = loc
        col.objects.link(ob)
        PARTS["studio"].append(ob)
        look_at(ob, target)
        return ob

    area("Key", (115, -85, 195), (0, 0, 14), 90, 300000, (1.0, 0.97, 0.93))
    area("Fill", (-215, -120, 85), (0, 0, 14), 120, 55000, (0.90, 0.95, 1.0))
    area("Rim", (-25, 225, 175), (0, 0, 16), 140, 300000, (1.0, 0.98, 0.95))
    area("Bounce", (-40, -245, 25), (0, 0, 12), 150, 26000, (0.85, 0.90, 1.0))
    inspect = area("Inspect", (30, -60, 260), (0, -2, 12), 240, 380000,
                   (1.0, 0.99, 0.96))
    inspect.hide_render = True

    world = bpy.data.worlds.new("Mundo")
    world.use_nodes = True
    bg = world.node_tree.nodes.get("Background")
    if bg:
        bg.inputs[0].default_value = (0.010, 0.011, 0.016, 1.0)
        bg.inputs[1].default_value = 1.0
    bpy.context.scene.world = world

    cams = {}
    for name, loc, target, lens in (
        ("Cam_Hero", (24, -188, 160), (0, 0, 12), 90),
        ("Cam_Exploded", (120, -300, 225), (-5, 0, 54), 75),
        ("Cam_Internals", (46, -122, 96), (0, -2, 14), 54),
    ):
        data = bpy.data.cameras.new(name)
        data.lens = lens
        data.sensor_width = 36.0
        ob = bpy.data.objects.new(name, data)
        ob.location = loc
        col.objects.link(ob)
        PARTS["studio"].append(ob)
        look_at(ob, target)
        cams[name] = ob
    bpy.context.scene.camera = cams["Cam_Hero"]
    return cams
def setup_render():
    sc = bpy.context.scene
    sc.render.engine = "CYCLES"
    sc.cycles.device = "CPU"
    sc.cycles.use_denoising = False
    try:
        sc.cycles.denoiser = "OPENIMAGEDENOISE"
    except Exception:
        pass
    sc.cycles.max_bounces = 4
    sc.cycles.transmission_bounces = 2
    sc.cycles.transparent_max_bounces = 2
    sc.cycles.use_adaptive_sampling = True
    sc.cycles.adaptive_threshold = 0.05
    sc.render.film_transparent = False
    sc.render.image_settings.file_format = "PNG"
    sc.render.image_settings.color_mode = "RGBA"
    sc.render.image_settings.color_depth = "8"
    sc.view_settings.view_transform = "AgX"
    try:
        sc.view_settings.look = "AgX - Medium High Contrast"
    except Exception:
        pass
    sc.view_settings.exposure = 0.30


def render_view(name, cam, res, samples):
    sc = bpy.context.scene
    sc.camera = cam
    sc.render.resolution_x, sc.render.resolution_y = res
    sc.render.resolution_percentage = 55 if QUICK else 100
    sc.cycles.samples = 20 if QUICK else samples
    sc.render.filepath = os.path.join(OUT_DIR, name)
    bpy.ops.render.render(write_still=True)
    print(f"[render] {name} -> {sc.render.filepath}")


def move_group(group, dz):
    for ob in PARTS[group]:
        ob.location.z += dz


def move_layers(factor):
    for ob in PARTS["internals"]:
        ob.location.z += LAYER_DZ[int(ob.get("layer", 0))] * factor


LAYER_DZ = (0.0, 15.0, 30.0, 45.0, 60.0)
EXPLODE_DZ = 74.0

FRONT_LAYER = {"eye", "smile", "OLED_096", "OLED_Glass", "OLED_LCD",
               "Speaker_Driver", "Spk_Rim_o", "INMP441", "Mic_Port",
               "Tact_Btn1", "Tact_Btn2", "Tact_Btn1_stem", "Tact_Btn2_stem"}


def main():
    sc = bpy.context.scene
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.unit_settings.system = "METRIC"
    sc.unit_settings.length_unit = "MILLIMETERS"
    sc.unit_settings.scale_length = 0.001

    mats = make_materials()
    studio()
    build_body(mats)
    build_face(mats)
    build_screen(mats)
    build_buttons(mats)
    build_internals(mats)

    problems = check_layout()
    if problems:
        raise SystemExit("Layout con interpenetraciones: revisar LAYOUT")

    auto_smooth_all()
    setup_render()
    cams = {
        "Cam_Hero": bpy.data.objects["Cam_Hero"],
        "Cam_Exploded": bpy.data.objects["Cam_Exploded"],
        "Cam_Internals": bpy.data.objects["Cam_Internals"],
    }

    render_view("render_fifo.png", cams["Cam_Hero"], (1280, 1000), 200)

    bpy.data.objects["Inspect"].hide_render = False
    move_group("face", EXPLODE_DZ)
    move_layers(1.0)
    render_view("render_fifo_exploded.png", cams["Cam_Exploded"],
                (1100, 860), 160)
    move_group("face", -EXPLODE_DZ)
    move_layers(-1.0)

    hidden = []
    for ob in PARTS["face"] + PARTS["internals"]:
        if ob.name.startswith("eye") or ob.name in FRONT_LAYER \
                or ob in PARTS["face"]:
            ob.hide_render = True
            hidden.append(ob)
    render_view("render_fifo_internals.png", cams["Cam_Internals"],
                (1100, 860), 160)
    bpy.data.objects["Inspect"].hide_render = True
    for ob in hidden:
        ob.hide_render = False

    bpy.context.scene.camera = cams["Cam_Hero"]
    blend = os.path.join(OUT_DIR, "fifo.blend")
    bpy.ops.wm.save_as_mainfile(filepath=blend)
    print(f"[blend] {blend}")


if __name__ == "__main__":
    main()
