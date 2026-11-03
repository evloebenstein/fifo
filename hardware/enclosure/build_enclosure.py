"""Genera la caja del robot Fifo y sus renders, en mm.

Se ejecuta sin interfaz:

    blender --background --hardware python hardware/enclosure/build_enclosure.py

Medidas tomadas del BOM real:

    XIAO ESP32-S3 ............ 21.0 x 17.8 x 4.0   (controlador + BLE + I2S)
    OLED 0.96" SSD1306 ....... 27.5 x 19.2 x 2.5   (panel 21.74 x 10.87 visible)
    INMP441 (mic I2S) ........ 14.0 x 12.5 x 3.5
    MAX98357A (amp I2S) ...... 25.0 x 15.0 x 3.0
    Parlante plano 8R 1W ..... O 20.0 x 4.0
    LiPo 902030 500 mAh ...... 30.0 x 20.0 x 9.0
    ATGM336H GPS+BeiDou ...... 25.0 x 25.0 x 4.5   (antena ceramica)
    Interruptor SS12D07 ...... 10.5 x  4.0 x 4.5
    Pulsadores 6x6 ...........  6.0 x  6.0 x 5.0

El sobre es a medida: 62 x 74 x 24 mm con esquinas radios 12 mm, dos piezas
(contera + tapa) encajadas a presion, ventana de acrylic sobre el OLED, rejilla
metamica para el parlante y dos botones de volumen.
"""

import math
import os
import sys

import bpy

# ═══════════════════════════════════════════════════════════════════════
#  PARAMETROS
# ═══════════════════════════════════════════════════════════════════════

OUT_DIR = os.path.dirname(os.path.abspath(__file__))

# Caja
BODY_W = 62.0          # X
BODY_L = 74.0          # Y
BODY_H = 24.0          # Z
CORNER_R = 12.0
WALL = 2.0
FLOOR = 3.5
PARTING_Z = 17.0       # linea de particion contera / tapa
EDGE_FILLET = 1.4

# cara superior (Z = BODY_H). +Y es "arriba" visto de frente.
SCREEN_C = (-5.0, 17.0)
SCREEN_W = 26.0        # ventana visible
SCREEN_H = 14.5
SCREEN_R = 2.0

SPEAKER_C = (-9.0, -13.0)
SPEAKER_R = 11.0
GRILLE_HOLE_R = 0.85
GRILLE_PITCH = 2.4

BTN_R = 5.6
BTN_UP_C = (19.0, 4.0)
BTN_DOWN_C = (19.0, -14.0)

MIC_HOLE = (24.0, 27.0, 1.0)
USB_C = (0.0, -BODY_L / 2, 5.5, 9.0, 3.4)     # x, pared -Y, z, ancho, alto
SWITCH_SLOT = (BODY_W / 2, 24.0, 12.0, 12.0, 5.0)  # pared +X, y, z, largo, alto

# Internos
XIAO = (21.0, 17.8, 4.0)
OLED_MOD = (27.5, 19.2, 2.5)
INMP = (14.0, 12.5, 3.5)
AMP = (25.0, 15.0, 3.0)
SPK_D, SPK_H = 20.0, 4.0
LIPO = (30.0, 20.0, 9.0)
GPS = (25.0, 25.0, 4.5)
SWITCH = (10.5, 4.0, 4.5)

# Colores
NAVY = (0.052, 0.067, 0.115, 1.0)
NAVY_DARK = (0.028, 0.036, 0.062, 1.0)
LCD_BLUE = (0.62, 0.83, 0.93, 1.0)
INK = (0.045, 0.055, 0.075, 1.0)
GREEN = (0.11, 0.45, 0.19, 1.0)
RED = (0.55, 0.11, 0.11, 1.0)
STEEL = (0.42, 0.43, 0.45, 1.0)
PCB_BLACK = (0.022, 0.022, 0.028, 1.0)
GOLD = (0.75, 0.58, 0.22, 1.0)
FOIL = (0.72, 0.73, 0.75, 1.0)
PRINT_WHITE = (0.88, 0.90, 0.94, 1.0)


# ═══════════════════════════════════════════════════════════════════════
#  UTILIDADES
# ═══════════════════════════════════════════════════════════════════════

def link(obj):
    bpy.context.scene.collection.objects.link(obj)
    return obj


def select_only(*objs):
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs:
        o.select_set(True)
    if objs:
        bpy.context.view_layer.objects.active = objs[-1]


def set_origin_top(obj):
    """Mueve el origen al centroide en XY y a z=0 del bbox."""
    xs = [v.co.x for v in obj.data.vertices]
    ys = [v.co.y for v in obj.data.vertices]
    zs = [v.co.z for v in obj.data.vertices]
    cx = (max(xs) + min(xs)) / 2
    cy = (max(ys) + min(ys)) / 2
    cz = min(zs)
    for v in obj.data.vertices:
        v.co.x -= cx
        v.co.y -= cy
        v.co.z -= cz
    obj.location = (cx, cy, cz)


def shade_smooth(obj, auto=True):
    for p in obj.data.polygons:
        p.use_smooth = True
    if auto and hasattr(obj.data, "use_auto_smooth"):
        try:
            obj.data.use_auto_smooth = True
            obj.data.auto_smooth_angle = math.radians(35)
        except Exception:
            pass


def material(name, color, rough=0.5, metal=0.0, emit=None, emit_strength=0.0,
             transmission=0.0, coat=0.0, alpha=1.0):
    mat = bpy.data.materials.get(name)
    if mat is None:
        mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    if bsdf is None:
        return mat

    def put(*names, value):
        for n in names:
            sock = bsdf.inputs.get(n)
            if sock is not None:
                try:
                    sock.default_value = value
                    return True
                except Exception:
                    continue
        return False

    bsdf.inputs["Base Color"].default_value = color
    put("Roughness", value=rough)
    put("Metallic", value=metal)
    put("Emission Color", "Emit", value=emit if emit else (0, 0, 0, 1))
    put("Emission Strength", value=emit_strength if emit else 0.0)
    put("Transmission Weight", "Transmission", value=transmission)
    put("Coat Weight", value=coat)
    put("Alpha", value=alpha)
    put("IOR", value=1.45)
    if alpha < 1.0:
        mat.blend_method = "BLEND"
        try:
            mat.show_transparent_back = False
        except Exception:
            pass
    return mat


def assign(obj, mat):
    obj.data.materials.clear()
    obj.data.materials.append(mat)


def rounded_box(name, w, l, h, radius, segments=10, z_base=0.0):
    """Caja con las 4 aristas verticales redondeadas (radio grande)."""
    bpy.ops.mesh.primitive_cube_add(size=1.0)
    obj = bpy.context.active_object
    obj.name = name
    obj.scale = (w / 2, l / 2, h / 2)
    obj.location = (0, 0, z_base + h / 2)
    bpy.ops.object.transform_apply(scale=True, location=True)

    # Vertices de las esquinas en planta -> grupo de vertices para el bisel
    vg = obj.vertex_groups.new(name="corners")
    ids = []
    for v in obj.data.vertices:
        if abs(abs(v.co.x) - w / 2) < 1e-4 and abs(abs(v.co.y) - l / 2) < 1e-4:
            ids.append(v.index)
    vg.add(ids, 1.0, "REPLACE")

    bev = obj.modifiers.new("corner", "BEVEL")
    bev.width = radius
    bev.segments = segments
    bev.limit_method = "VGROUP"
    bev.miter_outer = "MITER_ARC"
    select_only(obj)
    bpy.ops.object.modifier_apply(modifier=bev.name)

    # Redondeo suave del resto de aristas
    fin = obj.modifiers.new("fillet", "BEVEL")
    fin.width = EDGE_FILLET
    fin.segments = 4
    fin.limit_method = "ANGLE"
    fin.angle_limit = math.radians(40)
    bpy.ops.object.modifier_apply(modifier=fin.name)
    shade_smooth(obj)
    return obj


def cylinder(name, radius, depth, loc, vertices=48, rot=(0, 0, 0)):
    bpy.ops.mesh.primitive_cylinder_add(
        radius=radius, depth=depth, location=loc, vertices=vertices, rotation=rot
    )
    obj = bpy.context.active_object
    obj.name = name
    return obj


def box(name, w, l, h, loc):
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=loc)
    obj = bpy.context.active_object
    obj.name = name
    obj.scale = (w / 2, l / 2, h / 2)
    bpy.ops.object.transform_apply(scale=True)
    return obj


def boolean(target, cutter, op="DIFFERENCE", keep=False):
    mod = target.modifiers.new("bool", "BOOLEAN")
    mod.operation = op
    mod.object = cutter
    mod.solver = "EXACT"
    mod.use_self = True
    select_only(target)
    bpy.ops.object.modifier_apply(modifier=mod.name)
    if not keep:
        bpy.data.objects.remove(cutter, do_unlink=True)
    return target


def join(objs, name):
    select_only(*objs)
    bpy.ops.object.join()
    out = bpy.context.active_object
    out.name = name
    return out


def text_object(label, size, loc, thick=0.12, align="CENTER", mat=None,
                rot=(math.radians(90), 0, 0)):
    cu = bpy.data.curves.new("txt_" + label[:8], "FONT")
    cu.body = label
    cu.size = size
    cu.align_x = align
    cu.align_y = "CENTER"
    cu.extrude = thick
    cu.offset = 0.0
    obj = bpy.data.objects.new("TXT_" + label[:12], cu)
    obj.location = loc
    obj.rotation_euler = rot
    link(obj)
    if mat:
        assign(obj, mat)
    return obj


# ═══════════════════════════════════════════════════════════════════════
#  ESCENA
# ═══════════════════════════════════════════════════════════════════════

def reset_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.unit_settings.system = "METRIC"
    sc.unit_settings.length_unit = "MILLIMETERS"
    sc.unit_settings.scale_length = 0.001
    return sc


COLS = {}


def collection(name, parent=None):
    if name in COLS:
        return COLS[name]
    col = bpy.data.collections.new(name)
    if parent is None:
        bpy.context.scene.collection.children.link(col)
    else:
        parent.children.link(col)
    COLS[name] = col
    return col


def to_collection(obj, col):
    for c in obj.users_collection:
        c.objects.unlink(obj)
    col.objects.link(obj)


# ── La caja ────────────────────────────────────────────────────────────

def build_shell(mats):
    shell_col = collection("01_Carcasa")

    outer = rounded_box("shell_outer", BODY_W, BODY_L, BODY_H, CORNER_R)
    inner = rounded_box("shell_cavity", BODY_W - 2 * WALL, BODY_L - 2 * WALL,
                        BODY_H - FLOOR, CORNER_R - WALL, segments=8,
                        z_base=FLOOR)

    # Ventana del OLED
    win = rounded_box("cut_screen", SCREEN_W + 2.0, SCREEN_H + 2.0, 12,
                      SCREEN_R + 1.0, segments=6)
    win.location = (SCREEN_C[0], SCREEN_C[1], BODY_H - 6)

    # Agujero del parlante
    spk = cylinder("cut_speaker", SPEAKER_R, 20, (SPEAKER_C[0], SPEAKER_C[1], BODY_H - 5))

    # Botones
    b1 = cylinder("cut_btn_up", BTN_R + 0.4, 20, (BTN_UP_C[0], BTN_UP_C[1], BODY_H - 5))
    b2 = cylinder("cut_btn_dn", BTN_R + 0.4, 20, (BTN_DOWN_C[0], BTN_DOWN_C[1], BODY_H - 5))

    # Micfono
    mic = cylinder("cut_mic", MIC_HOLE[2], 20, (MIC_HOLE[0], MIC_HOLE[1], BODY_H - 5), vertices=20)

    # USB-C en la pared -Y
    usb = box("cut_usb", USB_C[3], 12, USB_C[4], (USB_C[0], -BODY_L / 2, USB_C[2]))
    usb = rounded_box_xy(usb, 1.5)

    # Interruptor en la pared +X
    sw = box("cut_switch", 12, SWITCH_SLOT[3], SWITCH_SLOT[4],
             (BODY_W / 2, SWITCH_SLOT[1], SWITCH_SLOT[2]))

    cutters = join([win, spk, b1, b2, mic, usb, sw], "cutters")
    boolean(outer, cutters)

    # Cavidad interior
    boolean(outer, inner)

    # Linea de particion contera / tapa
    ring_out = rounded_box("ring_out", BODY_W + 2, BODY_L + 2, 0.5, CORNER_R + 1)
    ring_out.location.z = PARTING_Z
    ring_in = rounded_box("ring_in", BODY_W - 2 * WALL - 2, BODY_L - 2 * WALL - 2,
                          3, CORNER_R - WALL - 1)
    ring_in.location.z = PARTING_Z - 1.25
    boolean(ring_out, ring_in)
    boolean(outer, ring_out)

    # Aletas internas que sostienen el OLED y el parlante
    bezel = rounded_box("screen_bezel", SCREEN_W + 6, SCREEN_H + 6, 3.0, 3.0, segments=6)
    bezel.location = (SCREEN_C[0], SCREEN_C[1], BODY_H - 3.2)
    hole = box("bezel_hole", SCREEN_W, SCREEN_H, 10, (SCREEN_C[0], SCREEN_C[1], BODY_H - 3))
    boolean(bezel, hole)

    speaker_ring = cylinder("speaker_ring", SPEAKER_R + 1.6, 2.5,
                            (SPEAKER_C[0], SPEAKER_C[1], BODY_H - 2.6))
    boolean(speaker_ring, cylinder("sr_hole", SPEAKER_R - 0.4, 12,
                                   (SPEAKER_C[0], SPEAKER_C[1], BODY_H - 2.6)))

    # Poste de montaje de la placa
    posts = []
    for px, py in ((-24, 30), (24, 30), (-24, -28), (24, -28)):
        posts.append(cylinder("post", 2.0, PARTING_Z - FLOOR, (px, py, FLOOR + (PARTING_Z - FLOOR) / 2), vertices=18))
    post_j = join(posts, "posts")

    for o in (outer, bezel, speaker_ring, post_j):
        to_collection(o, shell_col)
    assign(outer, mats["shell"])
    assign(bezel, mats["bezel"])
    assign(speaker_ring, mats["bezel"])
    assign(post_j, mats["shell"])
    shade_smooth(outer)
    return outer


def rounded_box_xy(obj, radius):
    """Redondea las 4 aristas paralelas a Z de una caja ya creada."""
    vg = obj.vertex_groups.new(name="c")
    xs = [abs(v.co.x) for v in obj.data.vertices]
    ys = [abs(v.co.y) for v in obj.data.vertices]
    mx, my = max(xs), max(ys)
    ids = [v.index for v in obj.data.vertices
           if abs(abs(v.co.x) - mx) < 1e-4 and abs(abs(v.co.y) - my) < 1e-4]
    vg.add(ids, 1.0, "REPLACE")
    bev = obj.modifiers.new("b", "BEVEL")
    bev.width = radius
    bev.segments = 5
    bev.limit_method = "VGROUP"
    select_only(obj)
    bpy.ops.object.modifier_apply(modifier=bev.name)
    return obj


# ── Rejilla del parlante ───────────────────────────────────────────────

def build_grille(mats):
    col = collection("02_Rejilla")
    disc = cylinder("grille", SPEAKER_R + 0.2, 0.9,
                    (SPEAKER_C[0], SPEAKER_C[1], BODY_H - 0.55), vertices=64)

    holes = []
    n = int(SPEAKER_R * 2 / GRILLE_PITCH) + 2
    for i in range(-n, n + 1):
        for j in range(-n, n + 1):
            x = SPEAKER_C[0] + i * GRILLE_PITCH + (GRILLE_PITCH / 2 if j % 2 else 0)
            y = SPEAKER_C[1] + j * GRILLE_PITCH * 0.87
            if math.hypot(x - SPEAKER_C[0], y - SPEAKER_C[1]) > SPEAKER_R - 0.9:
                continue
            holes.append(cylinder("h", GRILLE_HOLE_R, 6, (x, y, BODY_H - 0.55), vertices=10))
    if holes:
        h = join(holes, "holes")
        boolean(disc, h)
    rim = cylinder("grille_rim", SPEAKER_R + 1.3, 1.6,
                   (SPEAKER_C[0], SPEAKER_C[1], BODY_H - 0.9), vertices=64)
    boolean(rim, cylinder("rim_in", SPEAKER_R + 0.1, 8,
                          (SPEAKER_C[0], SPEAKER_C[1], BODY_H - 0.9), vertices=64))
    for o in (disc, rim):
        to_collection(o, col)
    assign(disc, mats["grille"])
    assign(rim, mats["bezel"])
    shade_smooth(disc)
    shade_smooth(rim)
    return disc, rim


# ── Pantalla: lente + LCD + cara ───────────────────────────────────────

def build_screen(mats):
    col = collection("03_Pantalla")

    lens = rounded_box("lens", SCREEN_W + 1.6, SCREEN_H + 1.6, 2.6, SCREEN_R, segments=6)
    lens.location = (SCREEN_C[0], SCREEN_C[1], BODY_H - 1.0)
    assign(lens, mats["lens"])
    shade_smooth(lens)

    lcd = box("lcd", SCREEN_W - 0.6, SCREEN_H - 0.6, 0.4,
              (SCREEN_C[0], SCREEN_C[1], BODY_H - 3.9))
    assign(lcd, mats["lcd"])

    # Rostro de Fifo: dos ojos en arco y una sonrisa (curvas con grosor)
    face_z = BODY_H - 3.6
    eyes = []
    for ex in (SCREEN_C[0] - 6.0, SCREEN_C[0] + 6.0):
        cu = bpy.data.curves.new("eye", "CURVE")
        cu.dimensions = "3D"
        sp = cu.splines.new("POLY")
        pts = []
        for k in range(17):
            a = math.radians(20 + k * (320 / 16))
            pts.append((ex + 3.0 * math.cos(a), SCREEN_C[1] + 1.4 + 3.0 * math.sin(a)))
        sp.points.add(len(pts) - 1)
        for p, (px, py) in zip(sp.points, pts):
            p.co = (px, py, 0, 1)
        cu.bevel_depth = 0.42
        cu.bevel_resolution = 3
        cu.use_fill_caps = True
        ob = bpy.data.objects.new("eye", cu)
        ob.location.z = face_z
        link(ob)
        eyes.append(ob)

    sm = bpy.data.curves.new("smile", "CURVE")
    sm.dimensions = "3D"
    sp = sm.splines.new("POLY")
    pts = []
    for k in range(21):
        t = -1 + k * (2 / 20)
        pts.append((SCREEN_C[0] + 8.2 * t, SCREEN_C[1] - 3.6 + 2.9 * (1 - t * t) * -1.0))
    sp.points.add(len(pts) - 1)
    for p, (px, py) in zip(sp.points, pts):
        p.co = (px, py, 0, 1)
    sm.bevel_depth = 0.42
    sm.bevel_resolution = 3
    sm.use_fill_caps = True
    smile = bpy.data.objects.new("smile", sm)
    smile.location.z = face_z
    link(smile)
    eyes.append(smile)

    faces = []
    for e in eyes:
        select_only(e)
        bpy.ops.object.convert(target="MESH")
        e = bpy.context.active_object
        assign(e, mats["ink"])
        to_collection(e, col)
        faces.append(e)

    tag = text_object("MAQUETA DEL PROTOTIPO v0.1  ·  EL ASISTENTE QUE ESCUCHA",
                      1.05, (SCREEN_C[0], SCREEN_C[1] - 6.1, BODY_H - 3.5),
                      thick=0.02, mat=mats["ink"])
    to_collection(tag, col)

    for o in (lens, lcd):
        to_collection(o, col)
    return lens, lcd, faces


# ── Botones ────────────────────────────────────────────────────────────

def build_buttons(mats):
    col = collection("04_Botones")
    out = []
    for c, mat, lab in ((BTN_UP_C, mats["btn_up"], "+ SUBIR VOL"),
                        (BTN_DOWN_C, mats["btn_dn"], "- BAJAR VOL")):
        stem = cylinder("stem", BTN_R - 1.2, 6.0, (c[0], c[1], BODY_H + 1.0), vertices=32)
        cap = cylinder("cap", BTN_R, 2.2, (c[0], c[1], BODY_H + 4.4), vertices=48)
        bev = cap.modifiers.new("b", "BEVEL")
        bev.width = 0.9
        bev.segments = 5
        bev.limit_method = "ANGLE"
        select_only(cap)
        bpy.ops.object.modifier_apply(modifier=bev.name)
        shade_smooth(cap)
        top = join([stem, cap], "button")
        assign(top, mat)
        to_collection(top, col)
        out.append(top)

        t = text_object(lab, 1.5, (c[0] - 0.2, c[1] + BTN_R + 2.6, BODY_H - 0.1),
                        thick=0.02, mat=mats["print"])
        to_collection(t, col)
    return out


# ── Piezas internas ────────────────────────────────────────────────────

def build_internals(mats):
    """Cada pieza en su propia coleccion para poder explotar la vista."""
    parts = {}

    def add(key, obj, mat, label):
        assign(obj, mat)
        to_collection(obj, collection("05_Internos"))
        obj["label"] = label
        parts[key] = obj
        return obj

    # Posicion real dentro de la caja (Z desde el piso de la contera)
    z0 = FLOOR

    add("xiao", box("XIAO_ESP32S3", XIAO[0], XIAO[1], XIAO[2],
                    (-2, 6, z0 + 6.5)), mats["pcb"],
        "XIAO ESP32-S3")
    shield = box("radio_shield", 14, 12, 2.6, (-2, 9.5, z0 + 6.5 + XIAO[2] / 2 + 1.3))
    assign(shield, mats["foil"])
    to_collection(shield, collection("05_Internos"))
    parts["shield"] = shield

    add("oled", box("OLED_SSD1306", OLED_MOD[0], OLED_MOD[1], OLED_MOD[2],
                    (SCREEN_C[0], SCREEN_C[1], BODY_H - 5.0)), mats["pcb"],
        "OLED 0.96\" SSD1306 I2C")

    add("inmp", box("INMP441", INMP[0], INMP[1], INMP[2],
                    (MIC_HOLE[0], MIC_HOLE[1], z0 + 12.0)), mats["pcb"],
        "INMP441  microfono I2S")

    add("amp", box("MAX98357A", AMP[0], AMP[1], AMP[2],
                   (SPEAKER_C[0] + 1, SPEAKER_C[1] - 1, z0 + 5.5)), mats["pcb"],
        "MAX98357A  amplificador I2S")

    spk = cylinder("PARLANTE", SPK_D / 2, SPK_H,
                   (SPEAKER_C[0], SPEAKER_C[1], BODY_H - 6.6), vertices=48)
    add("speaker", spk, mats["grille"], "Parlante plano 8\u03a9 1 W")

    add("lipo", rounded_box("LIPO_902030", LIPO[0], LIPO[1], LIPO[2], 2.0, segments=4),
        mats["battery"], "LiPo 902030  500 mAh")
    parts["lipo"].location = (14, -22, z0 + LIPO[2] / 2)

    add("gps", box("ATGM336H", GPS[0], GPS[1], GPS[2],
                   (-14, 22, z0 + 12.5)), mats["gps"], "ATGM336H  GPS + BeiDou")
    patch = box("gps_antenna", 16, 16, 1.2, (-14, 22, z0 + 12.5 + GPS[2] / 2 + 0.6))
    assign(patch, mats["ceramic"])
    to_collection(patch, collection("05_Internos"))
    parts["patch"] = patch

    sw = box("SWITCH_SS12D07", SWITCH[1], SWITCH[0], SWITCH[2],
             (BODY_W / 2 - 5.0, SWITCH_SLOT[1], SWITCH_SLOT[2]))
    add("switch", sw, mats["pcb"], "Interruptor SS12D07")
    lever = box("switch_lever", 3.0, 2.0, 2.6,
                (BODY_W / 2 - 1.6, SWITCH_SLOT[1], SWITCH_SLOT[2] + 2.4))
    assign(lever, mats["print"])
    to_collection(lever, collection("05_Internos"))
    parts["lever"] = lever

    for key in ("btn_up", "btn_dn"):
        pass
    b1 = cylinder("BTN1", 3.0, 5.0, (BTN_UP_C[0], BTN_UP_C[1], BODY_H - 6.5), vertices=28)
    add("button1", b1, mats["btn_up"], "Pulsador 6x6")
    b2 = cylinder("BTN2", 3.0, 5.0, (BTN_DOWN_C[0], BTN_DOWN_C[1], BODY_H - 6.5), vertices=28)
    add("button2", b2, mats["btn_dn"], "Pulsador 6x6")

    usb = box("USBC", 9.0, 5.0, 3.2, (USB_C[0], -BODY_L / 2 + 4.5, USB_C[2]))
    add("usbc", usb, mats["steel"], "USB-C  carga")

    return parts


# ── Materiales ─────────────────────────────────────────────────────────

def make_materials():
    return {
        "shell": material("Carcasa", NAVY, rough=0.42, coat=0.25),
        "bezel": material("Bisel", NAVY_DARK, rough=0.55),
        "lens": material("Lente", (0.92, 0.96, 1.0, 1.0), rough=0.03, transmission=0.92),
        "lcd": material("LCD", LCD_BLUE, rough=0.28, emit=LCD_BLUE, emit_strength=1.35),
        "ink": material("Tinta", INK, rough=0.6),
        "print": material("Impresion", PRINT_WHITE, rough=0.6),
        "grille": material("Rejilla", STEEL, rough=0.38, metal=1.0),
        "btn_up": material("Verde", GREEN, rough=0.28, coat=0.4),
        "btn_dn": material("Rojo", RED, rough=0.28, coat=0.4),
        "pcb": material("PCB", PCB_BLACK, rough=0.55),
        "foil": material("Blindaje", FOIL, rough=0.3, metal=1.0),
        "battery": material("Bateria", (0.80, 0.82, 0.85, 1.0), rough=0.35, metal=0.6),
        "gps": material("ModuloGPS", (0.10, 0.13, 0.20, 1.0), rough=0.5),
        "ceramic": material("Ceramica", (0.75, 0.72, 0.66, 1.0), rough=0.65),
        "steel": material("Metal", GOLD, rough=0.3, metal=1.0),
        "backdrop": material("Fondo", (0.030, 0.033, 0.045, 1.0), rough=0.85),
    }


# ═══════════════════════════════════════════════════════════════════════
#  ESTUDIO Y CAMARAS
# ═══════════════════════════════════════════════════════════════════════

def studio():
    col = collection("00_Estudio")
    ground = box("backdrop", 900, 900, 2, (0, 0, -1.2))
    assign(ground, bpy.data.materials["Fondo"])
    to_collection(ground, col)

    def area(name, loc, rot, size, power, color=(1, 1, 1)):
        d = bpy.data.lights.new(name, "AREA")
        d.size = size
        d.energy = power
        d.color = color
        o = bpy.data.objects.new(name, d)
        o.location = loc
        o.rotation_euler = rot
        link(o)
        to_collection(o, col)
        return o

    area("key", (150, -110, 210), (math.radians(32), 0, math.radians(52)), 180, 420000,
                    (1.0, 0.97, 0.93))
    area("fill", (-120, -90, 160), (math.radians(28), 0, math.radians(-48)), 140, 180000,
                    (0.94, 0.98, 1.0))
    area("rim",  (-20, 140, 190), (math.radians(35), 0, math.radians(135)), 100, 90000,
                    (1.0, 1.0, 1.0))

    # Camara
    cam_data = bpy.data.cameras.new("RenderCam")
    cam_data.lens = 42.0
    cam_data.sensor_width = 36.0
    cam_obj = bpy.data.objects.new("RenderCam", cam_data)
    cam_obj.location = (40, -85, 55)
    cam_obj.rotation_euler = (math.radians(68), 0, math.radians(145))
    link(cam_obj)
    to_collection(cam_obj, col)
    bpy.context.scene.camera = cam_obj


# ═══════════════════════════════════════════════════════════════════════
#  RENDER
# ═══════════════════════════════════════════════════════════════════════


def render():
    sc = bpy.context.scene
    sc.render.engine = "CYCLES"
    sc.cycles.device = "GPU"
    try:
        sc.cycles.use_denoising = True
        sc.cycles.denoiser = "OPTIX"
    except Exception:
        pass
    sc.render.resolution_x = 1920
    sc.render.resolution_y = 1080
    sc.render.resolution_percentage = 100
    sc.render.film_transparent = False
    sc.render.image_settings.file_format = "PNG"
    sc.render.image_settings.color_mode = "RGBA"
    sc.render.image_settings.color_depth = "16"
    sc.cycles.samples = 256

    out_path = os.path.join(OUT_DIR, "render_enclosure.png")
    sc.render.filepath = out_path
    bpy.ops.render.render(write_still=True)
    print(f"Render guardado en {out_path}")

    # Guardar .blend para inspección
    blend_path = os.path.join(OUT_DIR, "enclosure.blend")
    bpy.ops.wm.save_as_mainfile(filepath=blend_path)
    print(f".blend guardado en {blend_path}")


# ═══════════════════════════════════════════════════════════════════════
#  MAIN
# ═══════════════════════════════════════════════════════════════════════


def main():
    reset_scene()
    mats = make_materials()
    studio()
    build_shell(mats)
    build_grille(mats)
    build_screen(mats)
    build_buttons(mats)
    build_internals(mats)
    render()


if __name__ == "__main__":
    main()