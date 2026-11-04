"""Caja del robot Fifo (kit XIAO ESP32-S3) en mm, paramÃ©trica y verificable.

    blender --background --factory-startup --python hardware/enclosure/build_enclosure.py

El sobre exterior es 62 x 74 x 24 mm con esquinas R7 y dos piezas reales:

    contera   Z = 0.00 .. 16.35   (baÃ±era de 2 mm de pared, piso 3 mm)
    tapa      Z = 16.50 .. 24.00  (techo de 2 mm + labio de encaje)

La cara superior (Z = 24) lleva, de arriba abajo:

    ventana OLED      23.4 x 12.6 mm (modulo real 24.7 x 27 x 1.2) con lente
    rotulos           marca FIFO + "EL ASISTENTE QUE ESCUCHA"
    parlante          rejilla metalica O27.2 sobre driver O20 mm
    pulsadores        Vol+ verde / Vol- rojo, O12.4 con leyenda impresa
    rotulo            "ALTAVOZ" bajo la rejilla

Los internos van en dos torres verticales que agotan los 19 mm de cavidad:

    torre pantalla (centro-atras)   LiPo 3..12 -> XIAO 12..16 -> MAX98357A
                                    16.3..19.3 -> OLED en el techo 22..23.2
    torre acustica (frente-izq.)    ATGM336H 3..7.5 -> parche 7.5..8.7 ->
                                    parlante en la tapa 17.6..21.6

Medidas del BOM (mm):

    XIAO ESP32-S3 ............ 21.0 x 17.8 x 4.0
    OLED 0.96" SSD1306 ....... 24.7 x 27.0 x 1.2   (area activa 21.74 x 10.86)
    INMP441 (mic I2S) ........ 14.0 x 12.5 x 3.5
    MAX98357A (amp I2S) ...... 25.0 x 15.0 x 3.0
    Parlante plano 8R 1W ..... O20.0 x 4.0
    LiPo 902030 500 mAh ...... 30.0 x 20.0 x 9.0
    ATGM336H GPS+BeiDou ...... 25.0 x 25.0 x 4.5   (+ parche ceramico 16 x 16)
    Interruptor SS12D07 ...... 10.5 x 4.0 x 4.5
    Pulsadores 6x6 ........... 6.0 x 6.0 x 5.0
    Cinta Kapton ............. tiras de 0.06 mm

Salidas: enclosure.blend, render_enclosure.png (hero), render_exploded.png y
render_internals.png. El script corre la comprobacion de interpenetracion y
falla si dos piezas comparten volumen.
"""

import math
import os
import sys

import bpy
import bmesh
from mathutils import Matrix, Vector

# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
#  PARAMETROS
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

OUT_DIR = os.path.dirname(os.path.abspath(__file__))
QUICK = os.environ.get("FIFO_QUICK") == "1"

# Sobre exterior: formato vertical como el concepto (alto, no ancho).
BODY_W = 56.0
BODY_L = 70.0
BODY_H = 22.0
CORNER_R = 7.0
WALL = 2.0
FLOOR = 3.0

# Particion contera / tapa
PARTING_Z = 15.0
BOTTOM_TOP = 14.85       # 0.15 mm de luz: la linea de union se lee
CEILING = 2.0
INNER_TOP = BODY_H - CEILING          # 20.0
LIP_Z0, LIP_Z1 = 13.8, 15.2
LIP_W, LIP_L, LIP_T = 51.6, 65.6, 1.2

CAV_W = BODY_W - 2 * WALL             # 52.0
CAV_L = BODY_L - 2 * WALL             # 66.0
CAV_R = CORNER_R - WALL               # 5.0

# Cara superior (formato vertical como el concepto: alto, no ancho).
# OLED GM009605 v4.3: PCB 24.7 x 27.0 x 1.2, area activa 21.74 x 10.86.
# El area activa no esta centrada en el PCB: el conector vive en el borde
# corto, asi que el vidrio (y el area activa) sube ~3.9 mm.
MOD_W, MOD_L, MOD_H = 24.7, 27.0, 1.2
ACT_DY = 3.9                      # centro del area activa sobre el del PCB
ACT_W, ACT_L = 21.74, 10.86
SCREEN_C = (0.0, 16.0)            # centro del area activa
MOD_C = (0.0, SCREEN_C[1] - ACT_DY)
WIN_W, WIN_L, WIN_R = 23.4, 12.6, 4.6
LENS_W, LENS_L = WIN_W - 0.06, WIN_L - 0.06
POCKET_W, POCKET_L, POCKET_R = 25.6, 28.2, 5.0
POCKET_Z0 = INNER_TOP            # 20.00
POCKET_Z1 = POCKET_Z0 + MOD_H + 0.15   # 21.35
LENS_Z0, LENS_Z1 = POCKET_Z1, BODY_H

SPK_C = (-14.0, -16.0)
# Altavoz plano real 16 x 9 x 3 mm (modulo 1609/1511/1508, 8R 1W).
SPK_DW, SPK_DL, SPK_H = 16.0, 9.0, 3.0
# La rejilla visible es redonda como el concepto; el driver rectangular va
# detras.
SPK_RING_R = 12.0          # aro metalico exterior O24
SPK_MESH_R = 11.0          # malla interior O22
SPK_POCKET_R = 12.2        # rebaje circular en la cara O24.4
SPK_RING_POCKET = 20.8     # fondo del rebaje: aro sobresale 0.9
SPK_PORT_R = 6.0           # paso de sonido O12

BTN_UP_C = (19.0, -8.0)
BTN_DOWN_C = (19.0, -21.5)
BTN_R = 6.2               # tapa O12.4
BTN_WELL_R = 6.9          # rebaje donde se hunde la tapa
BTN_TOP = 22.5            # 0.5 mm sobre la cara
BTN_BOTTOM = 21.0

MIC_PORT = (20.0, -10.0)         # taladro en el piso, debajo del INMP441

# El XIAO va en el borde -Y con su propio USB-C hacia afuera; la pared luce
# un solo corte para el conector de la placa, no un panel-mount separado.
USB_X = 0.0
USB_SLOT = (9.0, 2.2, 7.0)       # ancho, z0, z1 del corte en la pared -Y

SWITCH_X = 0.0
SWITCH_Z = 3.0                   # el cuerpo apoya en el piso
SWITCH_SLOT = (11.0, 4.8)        # largo en X, alto en Z (pared +Y)

# Postes / tornillos: columnas de esquina. La manga corta vive en la contera
# (Z 3..8) y el poste roscado cuelga de la tapa (Z 8..22); ningun componente
# ocupa esas columnas, asi que el tornillo M2x10 entra desde el piso.
BOSS_XY = ((-21.0, 28.0), (21.0, 28.0), (-21.0, -28.0), (21.0, -28.0))
BOSS_R = 3.0
BOSS_Z0 = 8.0
SLEEVE_R = 3.0
SCREW_R = 1.1

# Internos declarados: nombre, W, L, H, CX, CY, Z0, material
# El XIAO vive en el borde -Y con su propio USB-C hacia afuera; el resto se
# apila sobre el piso y la tapa, sin tocar las columnas de tornillo de esquina:
#   frente  XIAO 3..7 (USB-C al aire) | INMP441 der 3..5
#   centro  LiPo 3..12 -> MAX98357A 12..15
#  trasera ATGM336H 3..7.5 -> parche 7.5..8.7
#   tapa   OLED 20.05..21.25 | parlante 16.8..19.8 | tact 14.2..20
LAYOUT = (
    ("XIAO_ESP32S3", 21.0, 17.8, 4.0, 0.0, -26.0, 3.0, "pcb"),
    ("USB_C_XIAO", 8.5, 3.2, 2.6, 0.0, -36.6, 4.0, "steel"),
    ("LiPo_902030", 30.0, 20.0, 9.0, 0.0, -7.0, 3.0, "battery"),
    ("MAX98357A", 25.0, 15.0, 3.0, 0.0, -7.0, 12.0, "pcb_amp"),
    ("ATGM336H", 25.0, 25.0, 4.5, 0.0, 16.0, 3.0, "gps"),
    ("GPS_Patch", 16.0, 16.0, 1.2, 0.0, 16.0, 7.5, "ceramic"),
    ("INMP441", 10.0, 10.0, 2.0, 20.0, -10.0, 3.0, "pcb_mic"),
    ("Switch_SS12D07", 10.5, 4.0, 4.5, SWITCH_X, 32.9, 3.0, "switch"),
    ("Switch_Lever", 2.5, 3.6, 2.4, SWITCH_X, 36.2, 4.05, "print"),
    ("Speaker_Driver", 16.0, 9.0, 3.0, SPK_C[0], SPK_C[1], 16.8, "driver"),
    ("Speaker_Gasket", 18.0, 11.0, 0.4, SPK_C[0], SPK_C[1], 19.8, "rubber"),
    ("OLED_SSD1306", MOD_W, MOD_L, MOD_H, MOD_C[0], MOD_C[1],
     POCKET_Z0 + 0.05, "pcb_oled"),
    ("Tact_VolUp", 6.5, 6.5, 5.8, BTN_UP_C[0], BTN_UP_C[1], 14.2, "switch"),
    ("Tact_VolDown", 6.5, 6.5, 5.8, BTN_DOWN_C[0], BTN_DOWN_C[1], 14.2, "switch"),
)

# Pares que se tocan a proposito (contacto, no interpenetracion)
TOUCHING = {
    frozenset(("XIAO_ESP32S3", "USB_C_XIAO")),
    frozenset(("XIAO_ESP32S3", "LiPo_902030")),
    frozenset(("ATGM336H", "GPS_Patch")),
    frozenset(("Switch_SS12D07", "Switch_Lever")),
    frozenset(("Speaker_Driver", "Speaker_Gasket")),
    frozenset(("LiPo_902030", "MAX98357A")),
}

# Colores
NAVY = (0.048, 0.058, 0.105, 1.0)
NAVY_DARK = (0.021, 0.025, 0.043, 1.0)
GLASS_BLACK = (0.006, 0.008, 0.012, 1.0)
LCD_BLUE = (0.56, 0.79, 0.92, 1.0)
INK = (0.030, 0.042, 0.062, 1.0)
GREEN = (0.045, 0.29, 0.13, 1.0)
RED = (0.42, 0.06, 0.075, 1.0)
GREEN_INK = (0.10, 0.60, 0.26, 1.0)
RED_INK = (0.72, 0.11, 0.13, 1.0)
BTN_CAP = (0.030, 0.033, 0.045, 1.0)
STEEL = (0.52, 0.54, 0.57, 1.0)
PCB_TEAL = (0.022, 0.115, 0.098, 1.0)
PCB_GREEN = (0.035, 0.155, 0.085, 1.0)
PCB_BLACK = (0.030, 0.032, 0.038, 1.0)
GOLD = (0.72, 0.55, 0.20, 1.0)
PRINT_WHITE = (0.90, 0.92, 0.95, 1.0)

COLS = {}
PARTS = {"bottom": [], "lid": [], "internals": [], "studio": []}


# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
#  UTILIDADES
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

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
    """Perimetro CCW de un rectangulo redondeado, sin duplicados."""
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
    """Prisma de seccion rectangular redondeada, con chaflan solo en los aros."""
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


def cut(target, cutters, name="cut"):
    """Diferencia booleana exacta. Acepta uno o varios cortadores."""
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
    if hasattr(ob.data, "use_auto_smooth"):          # Blender < 4.1
        try:
            ob.data.use_auto_smooth = True
            ob.data.auto_smooth_angle = math.radians(angle_deg)
        except Exception:
            pass


# â”€â”€ materiales â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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


def glass_lens(name, tint=(0.90, 0.95, 1.0, 1.0), ior=1.49, rough=0.04, sheen=0.35):
    """Lente acrilica: transmite casi todo y solo deja un brillo de Fresnel.

    Un Principled con Transmission=1 devolvia un velo lechoso sobre la pantalla
    (el reflejo del panel de luz se sumaba al LCD). Mezclar Transparent con un
    Glossy ponderado por Fresnel da el vidrio limpio sin lavar el contenido.
    """
    mat = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    mix = nt.nodes.new("ShaderNodeMixShader")
    trans = nt.nodes.new("ShaderNodeBsdfTransparent")
    trans.inputs["Color"].default_value = tint
    gloss = nt.nodes.new("ShaderNodeBsdfGlossy")
    gloss.inputs["Roughness"].default_value = rough
    gloss.inputs["Color"].default_value = (1.0, 1.0, 1.0, 1.0)
    fres = nt.nodes.new("ShaderNodeFresnel")
    fres.inputs["IOR"].default_value = ior
    scale = nt.nodes.new("ShaderNodeMath")
    scale.operation = "MULTIPLY"
    scale.inputs[1].default_value = sheen
    nt.links.new(fres.outputs["Fac"], scale.inputs[0])
    nt.links.new(scale.outputs[0], mix.inputs["Fac"])
    nt.links.new(trans.outputs[0], mix.inputs[1])
    nt.links.new(gloss.outputs[0], mix.inputs[2])
    nt.links.new(mix.outputs[0], out.inputs["Surface"])
    return mat


def make_materials():
    return {
        "shell": principled("Carcasa_Navy", NAVY, rough=0.42, coat=0.0, spec=0.28),
        "bezel": principled("Bisel_Negro", NAVY_DARK, rough=0.38, spec=0.25),
        "glass": principled("Bisel_Cristal", GLASS_BLACK, rough=0.60, spec=0.05),
        "clear": principled("Vidrio_Limpio", (0.94, 0.96, 0.98, 1.0),
                            rough=0.03, transmission=1.0, ior=1.5,
                            spec=0.02),
        "lens": glass_lens("Lente_Acrilico", ior=1.49, rough=0.08, sheen=0.0),
        "lcd": principled("OLED_Blanco", (0.82, 0.87, 0.95, 1.0), rough=0.35,
                          emit=(0.86, 0.92, 1.0, 1.0), emit_strength=0.70),
        "lcd_off": principled("OLED_Apagado", (0.004, 0.004, 0.006, 1.0),
                              rough=0.25),
        "ink": principled("Tinta", INK, rough=0.55),
        "print": principled("Serigrafia", PRINT_WHITE, rough=0.55),
        "print_dim": principled("Serigrafia_Tenue", (0.55, 0.58, 0.64, 1.0),
                                rough=0.60),
        "kapton": principled("Kapton", (0.62, 0.34, 0.03, 1.0), rough=0.22,
                             transmission=0.35, coat=0.3),
        "grille": principled("Rejilla_Aro", (0.50, 0.51, 0.54, 1.0), rough=0.26, metal=1.0),
        "mesh": principled("Rejilla_Malla", (0.19, 0.195, 0.21, 1.0), rough=0.40, metal=1.0),
        "btn_up": principled("Boton_Verde", GREEN, rough=0.30, coat=0.20),
        "btn_dn": principled("Boton_Rojo", RED, rough=0.30, coat=0.20),
        "btn_cap": principled("Boton_Cap", BTN_CAP, rough=0.42, coat=0.10, spec=0.25),
        "green_ink": principled("Leyenda_Verde", GREEN_INK, rough=0.45, emit=GREEN_INK,
                                emit_strength=0.55),
        "red_ink": principled("Leyenda_Roja", RED_INK, rough=0.45, emit=RED_INK,
                              emit_strength=0.55),
        "pcb": principled("PCB_Teal", PCB_TEAL, rough=0.48),
        "pcb_mic": principled("PCB_Verde", PCB_GREEN, rough=0.45),
        "pcb_amp": principled("PCB_Negro", PCB_BLACK, rough=0.42),
        "pcb_oled": principled("PCB_OLED", (0.012, 0.012, 0.016, 1.0), rough=0.4),
        "foil": principled("Blindaje", (0.78, 0.79, 0.81, 1.0), rough=0.24, metal=1.0),
        "battery": principled("Bateria", (0.13, 0.24, 0.44, 1.0), rough=0.38),
        "gps": principled("GPS_PCB", (0.045, 0.075, 0.14, 1.0), rough=0.45),
        "ceramic": principled("Antena_Ceramica", (0.58, 0.57, 0.53, 1.0), rough=0.65),
        "steel": principled("Acero", (0.80, 0.78, 0.74, 1.0), rough=0.25, metal=1.0),
        "driver": principled("Parlante", (0.035, 0.035, 0.042, 1.0), rough=0.6),
        "rubber": principled("Goma", (0.018, 0.018, 0.021, 1.0), rough=0.8),
        "switch": principled("Switch_Negro", (0.020, 0.021, 0.026, 1.0), rough=0.42),
        "gold": principled("Oro", GOLD, rough=0.28, metal=1.0),
        "wire_red": principled("Cable_Rojo", (0.30, 0.025, 0.02, 1.0), rough=0.45),
        "wire_black": principled("Cable_Negro", (0.014, 0.014, 0.016, 1.0), rough=0.45),
        "wire_amber": principled("Cable_Ambar", (0.42, 0.24, 0.03, 1.0), rough=0.45),
        "wire_blue": principled("Cable_Azul", (0.03, 0.10, 0.32, 1.0), rough=0.45),
        "label_bg": principled("Etiqueta_Fondo", (0.010, 0.012, 0.018, 1.0),
                               rough=0.55, emit=(0.03, 0.04, 0.06, 1.0),
                               emit_strength=0.35),
        "label_ink": principled("Etiqueta_Tinta", (0.92, 0.94, 0.97, 1.0),
                                rough=0.50, emit=(0.95, 0.97, 1.0, 1.0),
                                emit_strength=0.85),
        "label_lead": principled("Etiqueta_Guia", (0.50, 0.54, 0.60, 1.0),
                                 rough=0.5),
        "backdrop": principled("Fondo", (0.012, 0.013, 0.017, 1.0), rough=0.30),
    }


# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
#  CONTERA
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

def build_bottom(mats):
    col = collection("01_Contra")

    body = prism("Case_Bottom", BODY_W, BODY_L, CORNER_R,
                 0.0, BOTTOM_TOP, col, "bottom",
                 bevel_bottom=1.2)
    assign(body, mats["shell"])

    # BaÃ±era interior
    cav = prism("cav", CAV_W, CAV_L, CAV_R, FLOOR, BOTTOM_TOP + 0.5, col)
    cut(body, cav, "cav")

    # Hueco USB-C en la pared -Y: el conector del XIAO pasa por aqui, sin brida
    # ni conector panel-mount; la placa queda a ras de la pared por fuera.
    usb = prism("usb", USB_SLOT[0], 6.0, 1.6, USB_SLOT[1], USB_SLOT[2], col)
    usb.location = (USB_X, -BODY_L / 2, 0.0)
    select_only(usb)
    bpy.ops.object.transform_apply(location=True)
    cut(body, usb, "usb")

    # Ranura del interruptor en la pared +Y (oculta en la vista frontal)
    slot = block("sw_slot", SWITCH_SLOT[0], 6.0, SWITCH_SLOT[1],
                 SWITCH_X, BODY_L / 2 - 1.0, 3.2, col)
    cut(body, slot, "sw")

    # Puerto del microfono en el piso (no hay agujero en la cara superior)
    mic = disc("mic_port", 1.1, -0.3, FLOOR + 1.0,
               MIC_PORT[0], MIC_PORT[1], col, verts=32)
    mic_cb = disc("mic_cb", 1.9, -0.3, 0.9,
                  MIC_PORT[0], MIC_PORT[1], col, verts=32)
    cut(body, [mic, mic_cb], "micport")

    # Alojamientos de los tornillos (cabeza embutida en el piso)
    holes = []
    for x, y in BOSS_XY:
        holes.append(disc("hole", SCREW_R, -0.2, 3.2, x, y, col, verts=20))
        holes.append(disc("cbore", 2.5, -0.2, 1.2, x, y, col, verts=24))
    cut(body, holes, "screw")

    # Mangas de esquina: guian el tornillo desde el piso hasta el poste de la
    # tapa y rigidizan las cuatro esquinas (columnas libres de componentes).
    for x, y in BOSS_XY:
        sleeve = disc("Screw_Sleeve", SLEEVE_R, FLOOR, BOSS_Z0, x, y, col,
                      "bottom", verts=32)
        assign(sleeve, mats["bezel"])
        bore = disc("sv_bore", SCREW_R, FLOOR - 0.4, BOSS_Z0 + 0.2, x, y, col,
                    verts=20)
        cut(sleeve, bore, "sv")
        shade_smooth(sleeve)

    shade_smooth(body)
    return body


# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
#  TAPA
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

def build_lid(mats):
    col = collection("02_Tapa")

    lid = prism("Case_Top", BODY_W, BODY_L, CORNER_R,
                PARTING_Z, BODY_H, col, "lid", bevel_top=2.2)
    assign(lid, mats["shell"])

    # Vacio interior de la tapa (deja techo de 2 mm)
    cav = prism("cav", CAV_W, CAV_L, CAV_R, PARTING_Z - 0.5, INNER_TOP, col)
    cut(lid, cav, "cav")

    # OLED: bolsillo para el PCB de 24.7 x 27 x 1.2 + ventana del area activa
    pocket = prism("oled_pocket", POCKET_W, POCKET_L, POCKET_R,
                   POCKET_Z0 - 1.0, POCKET_Z1, col)
    pocket.location = (MOD_C[0], MOD_C[1], 0.0)
    win = prism("win", WIN_W, WIN_L, WIN_R, POCKET_Z1 - 0.05, BODY_H + 1.0, col)
    win.location = (SCREEN_C[0], SCREEN_C[1], 0.0)
    for ob in (pocket, win):
        select_only(ob)
        bpy.ops.object.transform_apply(location=True)
    cut(lid, [pocket, win], "screen")

    # Parlante: rebaje para el aro (el aro sobresale) + paso de sonido
    spk_rec = disc("spk_pocket", SPK_POCKET_R, SPK_RING_POCKET, BODY_H + 1.0,
                   SPK_C[0], SPK_C[1], col)
    spk_port = disc("spk_port", SPK_PORT_R, 21.0, SPK_RING_POCKET + 0.6,
                    SPK_C[0], SPK_C[1], col)
    cut(lid, [spk_rec, spk_port], "spk")

    # Pulsadores: agujero de paso + concha donde se hunde la tapa del botón
    bores = [disc("btn_bore", BTN_R + 0.08, 21.0, BODY_H + 1.0, x, y, col, verts=48)
             for x, y in (BTN_UP_C, BTN_DOWN_C)]
    wells = [disc("btn_well", BTN_WELL_R, 21.5, BODY_H + 1.0, x, y, col, verts=48)
             for x, y in (BTN_UP_C, BTN_DOWN_C)]
    cut(lid, bores + wells, "btn")


    # Labio de encaje (anillo que entra en la contera)
    lip_o = prism("lip_o", LIP_W, LIP_L, CAV_R - 0.2, LIP_Z0, LIP_Z1, col)
    lip_i = prism("lip_i", LIP_W - 2 * LIP_T, LIP_L - 2 * LIP_T, CAV_R - 0.2 - LIP_T,
                  LIP_Z0 - 0.5, LIP_Z1 + 0.5, col)
    cut(lip_o, lip_i, "lip")
    lip = lip_o
    lip.name = "Lid_Lip"
    PARTS["lid"].append(lip)
    assign(lip, mats["bezel"])

    # Postes de tornillo de la tapa
    bosses = []
    for x, y in BOSS_XY:
        b = disc("boss", BOSS_R, BOSS_Z0, INNER_TOP, x, y, col, "lid", verts=32)
        assign(b, mats["shell"])
        bosses.append(b)
    boss_holes = [disc("bh", SCREW_R, BOSS_Z0 - 0.4, INNER_TOP - 1.0, x, y, col, verts=16)
                  for x, y in BOSS_XY]
    for b, h in zip(bosses, boss_holes):
        cut(b, h, "bhole")

    for ob in (lid, lip, *bosses):
        shade_smooth(ob)
    return lid


# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
#  PANTALLA
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

def build_screen(mats):
    col = collection("03_Pantalla")

    # Lente acrilica enrasada en la ventana (0.65 mm sobre el modulo)
    lens = prism("Acrylic_Lens", LENS_W, LENS_L, WIN_R - 0.04,
                 LENS_Z0, LENS_Z1, col, "lid", seg=12,
                 bevel_top=0.22, bevel_seg=2)
    assign(lens, mats["lens"])
    shade_smooth(lens)
    lens.location = (SCREEN_C[0], SCREEN_C[1], 0.0)

    # Modulo GM009605 v4.3: PCB negro 24.7 x 27 x 1.2 + vidrio + area activa.
    # El vidrio se pega al borde superior del PCB; el conector queda al frente.
    pcb = block("OLED_SSD1306", MOD_W, MOD_L, 1.10,
                MOD_C[0], MOD_C[1], POCKET_Z0 + 0.03, col, "lid")
    assign(pcb, mats["pcb_oled"])

    glass = block("OLED_Glass", WIN_W, WIN_L, 0.16,
                  SCREEN_C[0], SCREEN_C[1], POCKET_Z0 + 1.13, col, "lid")
    assign(glass, mats["clear"])

    lcd = block("OLED_LCD", ACT_W, ACT_L, 0.05,
                SCREEN_C[0], SCREEN_C[1], POCKET_Z0 + 1.29, col, "lid")
    assign(lcd, mats["lcd"])

    # Cara de Fifo: dos ojos en arco y una sonrisa, dentro del area activa
    face_z = POCKET_Z0 + 1.35
    face_objs = []
    for ex in (SCREEN_C[0] - 4.6, SCREEN_C[0] + 4.6):
        cu = bpy.data.curves.new("eye", "CURVE")
        cu.dimensions = "3D"
        sp = cu.splines.new("POLY")
        pts = []
        for k in range(19):
            a = math.radians(-72 + k * (324 / 18))
            pts.append((ex + 2.2 * math.cos(a),
                        SCREEN_C[1] + 1.25 + 2.2 * math.sin(a)))
        sp.points.add(len(pts) - 1)
        for p, (px, py) in zip(sp.points, pts):
            p.co = (px, py, 0, 1)
        cu.bevel_depth = 0.38
        cu.bevel_resolution = 2
        cu.use_fill_caps = True
        ob = bpy.data.objects.new("eye", cu)
        ob.location.z = face_z
        col.objects.link(ob)
        PARTS["lid"].append(ob)
        face_objs.append(ob)

    cu = bpy.data.curves.new("smile", "CURVE")
    cu.dimensions = "3D"
    sp = cu.splines.new("POLY")
    pts = []
    for k in range(23):
        t = -1 + k * (2 / 22)
        pts.append((SCREEN_C[0] + 5.6 * t,
                    SCREEN_C[1] - 1.9 + 1.5 * (t * t)))
    sp.points.add(len(pts) - 1)
    for p, (px, py) in zip(sp.points, pts):
        p.co = (px, py, 0, 1)
    cu.bevel_depth = 0.38
    cu.bevel_resolution = 2
    cu.use_fill_caps = True
    smile = bpy.data.objects.new("smile", cu)
    smile.location.z = face_z
    col.objects.link(smile)
    PARTS["lid"].append(smile)
    face_objs.append(smile)

    for ob in face_objs:
        assign(ob, mats["lcd_off"])

    # Pigtail I2C: de los pads del conector a la XIAO
    pad_y = MOD_C[1] + MOD_L / 2 - 1.5
    for k in range(4):
        x = -3.81 + 2.54 * k
        wire("OLED_Wire_%d" % k,
             [(x, pad_y - 0.6, POCKET_Z0 + 1.20), (x, pad_y - 0.8,
              POCKET_Z0 + 1.06), (x, 4.78, 21.9), (x, 4.78, 18.3),
              (x, 5.4, 16.10)],
             0.30, mats["wire_amber"] if k % 2 else mats["wire_black"],
             col, "lid")
    return lens, lcd


# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
#  REJILLA Y BOTONES
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

def build_grille(mats):
    col = collection("04_Rejilla")

    # Aro metalico circular en relieve: sobresale 0.45 mm de la cara
    ring = disc("Grille_Ring", SPK_RING_R, SPK_RING_POCKET, BODY_H + 0.45,
                SPK_C[0], SPK_C[1], col, "lid", verts=96)
    inner = disc("ring_i", SPK_MESH_R, SPK_RING_POCKET - 0.2, BODY_H + 1.0,
                 SPK_C[0], SPK_C[1], col, verts=96)
    cut(ring, inner, "ring")
    assign(ring, mats["grille"])
    shade_smooth(ring)

    # Malla: disco agujereado hundido dentro del aro
    mesh = disc("Grille_Mesh", SPK_MESH_R, SPK_RING_POCKET, BODY_H - 0.35,
                SPK_C[0], SPK_C[1], col, "lid", verts=96)
    holes = []
    pitch = 1.30
    for j in range(-8, 9):
        for i in range(-8, 9):
            x = SPK_C[0] + i * pitch + (pitch / 2 if j % 2 else 0.0)
            y = SPK_C[1] + j * pitch * 0.866
            if math.hypot(x - SPK_C[0], y - SPK_C[1]) > SPK_MESH_R - 0.8:
                continue
            holes.append(disc("gh", 0.38, 22.0, BODY_H + 1.0, x, y, col, verts=8))
    if holes:
        cut(mesh, join(holes, "Grille_Holes", col), "gholes")
    assign(mesh, mats["mesh"])
    shade_smooth(mesh)
    return ring, mesh


def build_buttons(mats):
    col = collection("05_Botones")

    def cap(name, c, big, small, ink):
        ob = disc(name, BTN_R, BTN_BOTTOM, BTN_TOP, c[0], c[1], col, "lid", verts=64)
        bev = ob.modifiers.new("b", "BEVEL")
        bev.width = 0.45
        bev.segments = 3
        bev.limit_method = "ANGLE"
        bev.angle_limit = math.radians(35)
        select_only(ob)
        bpy.ops.object.modifier_apply(modifier=bev.name)
        assign(ob, mats["btn_cap"])
        shade_smooth(ob)

        # Vastago que empuja el tact switch por el hueco del techo
        stem = disc(name + "_stem", 1.7, 21.9, BTN_BOTTOM + 0.15,
                    c[0], c[1], col, "lid", verts=24)
        assign(stem, mats["switch"])

        t1 = text(big, 1.55, (c[0], c[1] + 1.35, BTN_TOP + 0.015), ink, col, "lid")
        t2 = text(small, 0.80, (c[0], c[1] - 1.75, BTN_TOP + 0.015), ink, col, "lid")
        for t in (t1, t2):
            t.data.space_character = 1.06
        return ob

    cap("Button_VolUp", BTN_UP_C, "+", "SUBIR VOL", mats["green_ink"])
    cap("Button_VolDown", BTN_DOWN_C, "-", "BAJAR VOL", mats["red_ink"])


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


# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
#  ROTULOS DE LA CARA
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

def build_prints(mats):
    col = collection("06_Rotulos")
    text("ALTAVOZ", 1.55, (SPK_C[0], -31.0, BODY_H + 0.02),
         mats["print_dim"], col, "lid", space=1.30)


# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
#  INTERNOS
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

def wire(name, pts, r, mat, col, group="internals"):
    cu = bpy.data.curves.new(name, "CURVE")
    cu.dimensions = "3D"
    cu.bevel_depth = r
    cu.bevel_resolution = 2
    cu.use_fill_caps = True
    sp = cu.splines.new("POLY")
    sp.points.add(len(pts) - 1)
    for p, co in zip(sp.points, pts):
        p.co = (co[0], co[1], co[2], 1)
    ob = bpy.data.objects.new(name, cu)
    col.objects.link(ob)
    if group:
        PARTS[group].append(ob)
    assign(ob, mat)
    return ob


def build_internals(mats):
    col = collection("07_Internos")
    parts = {}
    for name, w, l, h, cx, cy, z0, mat in LAYOUT:
        if name in ("Speaker_Driver", "Speaker_Gasket", "OLED_SSD1306",
                    "Tact_VolUp", "Tact_VolDown", "RF_Shield", "GPS_Patch",
                    "Switch_SS12D07", "Switch_Lever", "USB_C_XIAO"):
            continue
        ob = block(name, w, l, h, cx, cy, z0, col, "internals")
        assign(ob, mats[mat])
        parts[name] = ob

    # XIAO en el borde -Y: almohadillas en los bordes, LED y blindaje RF
    for k in range(7):
        y = -26.0 + (k - 3) * 2.3
        for sx in (-1, 1):
            pad = block("pad", 0.9, 1.4, 0.2, sx * 10.2, y, 7.0,
                        col, "internals")
            assign(pad, mats["gold"])
    led = block("led", 1.4, 1.0, 0.3, 6.8, -31.0, 7.0, col, "internals")
    assign(led, mats["btn_dn"])
    shield = block("RF_Shield", 14.0, 12.0, 0.2, 0.0, -26.0, 7.0, col, "internals")
    assign(shield, mats["foil"])

    # Conector USB-C del propio XIAO, asomando por la pared -Y
    usbc = block("USB_C_XIAO", 8.5, 3.2, 2.6, 0.0, -36.6, 4.0, col, "internals")
    assign(usbc, mats["steel"])
    mouth = block("USB_C_Mouth", 7.0, 0.5, 2.0, 0.0, -38.3, 4.5, col, "internals")
    assign(mouth, mats["rubber"])

    # GPS: PCB + antena ceramica + conector
    patch = block("GPS_Patch", 16.0, 16.0, 1.2, 0.0, 16.0, 7.5, col, "internals")
    assign(patch, mats["ceramic"])
    parts["GPS_Patch"] = patch
    gps_conn = block("GPS_Conn", 5.0, 3.0, 2.0, 0.0, 28.6, 3.0, col, "internals")
    assign(gps_conn, mats["steel"])

    # Bateria: etiqueta plateada envolvente y conector JST
    label = block("Battery_Label", 30.3, 5.0, 9.1, 0.0, -7.0, 3.0, col, "internals")
    label.location.z -= 0.05
    assign(label, mats["steel"])
    jst = block("Battery_JST", 6.0, 4.0, 3.0, 9.0, -16.4, 3.0, col, "internals")
    assign(jst, mats["print"])

    # INMP441 redondo: puerto sobre el taladro del piso y chip
    mic_port = disc("Mic_Port", 4.6, 3.0, 5.0, MIC_PORT[0], MIC_PORT[1],
                    col, "internals", verts=48)
    assign(mic_port, mats["pcb_mic"])
    mic_chip = block("Mic_Chip", 3.2, 3.2, 0.5, MIC_PORT[0], MIC_PORT[1], 5.0,
                     col, "internals")
    assign(mic_chip, mats["switch"])

    # MAX98357A apilado sobre la LiPo: chip y bornera de parlante
    amp_chip = block("Amp_Chip", 7.0, 5.0, 0.6, -7.8, -7.0, 15.0, col, "internals")
    assign(amp_chip, mats["switch"])
    amp_term = block("Amp_Terminal", 5.0, 4.0, 2.6, 9.5, -7.0, 15.0, col, "internals")
    assign(amp_term, mats["pcb_mic"])

    # Parlante (vive en la tapa, sobre la rejilla)
    drv = block("Speaker_Driver", SPK_DW, SPK_DL, SPK_H, SPK_C[0], SPK_C[1],
                 16.8, col, "lid")
    assign(drv, mats["driver"])
    cone = block("Speaker_Cono", 8.0, 4.5, 0.6, SPK_C[0], SPK_C[1], 19.6,
                 col, "lid")
    assign(cone, mats["foil"])
    magnet = block("Speaker_Magnet", 6.0, 5.0, 1.4, SPK_C[0], SPK_C[1], 16.8,
                   col, "lid")
    assign(magnet, mats["steel"])
    gasket = block("Speaker_Gasket", SPK_DW + 2.0, SPK_DL + 2.0, 0.4,
                    SPK_C[0], SPK_C[1], 19.8, col, "lid")
    assign(gasket, mats["rubber"])

    # Tact switches de los pulsadores, colgados del techo
    for c, name in ((BTN_UP_C, "Tact_VolUp"), (BTN_DOWN_C, "Tact_VolDown")):
        sw = block(name, 6.5, 6.5, 5.8, c[0], c[1], 14.2, col, "lid")
        assign(sw, mats["switch"])

    # Interruptor en la pared +Y: cuerpo + palanca
    sw = block("Switch_SS12D07", 10.5, 4.0, 4.5, SWITCH_X, 32.9, 3.0, col, "internals")
    assign(sw, mats["switch"])
    lever = block("Switch_Lever", 2.5, 3.6, 2.4, SWITCH_X, 36.2, 4.05, col, "internals")
    assign(lever, mats["print"])

    # Tornillos M2 embutidos en el piso
    for x, y in BOSS_XY:
        screw = disc("Screw", 2.2, 0.4, 1.2, x, y, col, "internals", verts=24)
        assign(screw, mats["gold"])

    # Cinta Kapton: fija la LiPo y aisla el parche del GPS
    for sx in (-1, 1):
        kap = block("Kapton_Battery", 4.0, 17.0, 0.07,
                    sx * 12.6, -7.0, 12.0, col, "internals")
        assign(kap, mats["kapton"])
    kap = block("Kapton_GPS", 16.6, 3.6, 0.07, -16.0, 18.5, 8.70,
                col, "internals")
    assign(kap, mats["kapton"])

    # Cableado del camino nuevo
    # Alimentacion LiPo (JST) -> XIAO por el borde delantero
    for dx, mat in ((-0.7, mats["wire_red"]), (0.7, mats["wire_black"])):
        wire(f"Wire_PWR_{dx:+.1f}",
             [(9.0 + dx, -16.4, 4.0), (10.0 + dx, -22.0, 5.0),
              (10.0 + dx, -32.0, 6.0), (10.0 + dx, -34.0, 6.5)],
             0.5, mat, col)
    # UART GPS -> XIAO por la pared izquierda
    wire("Wire_GPS_TX",
         [(0.0, 28.6, 4.0), (-8.0, 20.0, 5.0), (-12.0, -6.0, 6.0),
          (-10.0, -26.0, 6.5)],
         0.42, mats["wire_amber"], col)
    wire("Wire_GPS_RX",
         [(0.0, 28.6, 3.5), (-8.5, 20.0, 4.5), (-12.5, -6.0, 5.5),
          (-10.5, -26.0, 6.0)],
         0.42, mats["wire_black"], col)
    # I2S del INMP441 -> XIAO por el borde derecho
    wire("Wire_I2S",
         [(20.0, -10.0, 4.0), (18.0, -12.0, 5.0), (15.0, -15.0, 5.5),
          (12.0, -16.0, 6.5), (10.5, -26.0, 6.5)],
         0.42, mats["wire_blue"], col)
    # Switch -> XIAO por el lado izquierdo
    wire("Wire_SW",
         [(0.0, 32.9, 5.0), (-8.0, 15.0, 6.0), (-8.0, -17.0, 7.0),
          (0.0, -34.0, 6.0)],
         0.42, mats["wire_red"], col)

    # Pigtails de la tapa: bajan hasta el plano de union y reaparecen en la
    # vista explotada como cables entre grupos.
    for sx, mat in ((-0.9, mats["wire_amber"]), (0.9, mats["wire_black"])):
        wire(f"Wire_SPK_{sx:+.1f}",
             [(-16.4 + sx, -14.0, 17.8), (-18.0 + sx, -12.0, 17.4),
              (-18.0 + sx, -8.0, 16.8), (-17.4 + sx, -6.0, 16.55)],
             0.45, mat, col, "lid")
    for sx, mat in ((-0.8, mats["wire_black"]), (0.8, mats["wire_red"])):
        wire(f"Wire_BTN_U_{sx:+.1f}",
             [(BTN_UP_C[0] + sx, BTN_UP_C[1] - 2.4, 16.25),
              (BTN_UP_C[0] - 1.0 + sx, BTN_UP_C[1] - 4.0, 16.4),
              (BTN_UP_C[0] - 4.0 + sx, BTN_UP_C[1] - 5.0, 16.6)],
             0.38, mat, col, "lid")
        wire(f"Wire_BTN_D_{sx:+.1f}",
             [(BTN_DOWN_C[0] + sx, BTN_DOWN_C[1] - 2.4, 16.25),
              (BTN_DOWN_C[0] - 1.0 + sx, BTN_DOWN_C[1] - 4.0, 16.4),
              (BTN_DOWN_C[0] - 4.0 + sx, BTN_DOWN_C[1] - 5.0, 16.6)],
             0.38, mat, col, "lid")

    return parts


def auto_smooth_all(angle=32.0):
    """Caras planas horizontales sin promediar normales; curvas suaves.

    Un ngon grande con shading suave interpola las normales del borde por toda
    la cara y la hace parecer una almohada. Las tapas planas se dejan 'flat'
    (todas sus caras son coplanares, no hay costura visible) y los laterales,
    biseles y cilindros siguen suaves.
    """
    for ob in list(bpy.data.objects):
        if ob.type != "MESH":
            continue
        for p in ob.data.polygons:
            if abs(p.normal.z) > 0.999:
                p.use_smooth = False



# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
#  COMPROBACION DE INTERPENETRACION
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

# texto, objeto, dx, dy (en alturas de texto), vista donde aparece
LABELS = (
    ("XIAO ESP32-S3 8 MB", "XIAO_ESP32S3", 3.4, 1.4, "base"),
    ("LiPo 902030 500 mAh", "LiPo_902030", -3.3, -1.9, "base"),
    ("MAX98357A (I2S)", "MAX98357A", 2.4, 3.1, "base"),
    ("ATGM336H (GNSS)", "ATGM336H", -3.4, 2.0, "base"),
    ("INMP441 (I2S)", "INMP441", 2.9, -2.6, "base"),
    ("USB-C XIAO", "USB_C_XIAO", 3.4, -2.8, "base"),
    ("SS12D07 ON/OFF", "Switch_SS12D07", 0.0, 3.6, "base"),
    ("Cinta Kapton", "Kapton_Battery", 1.8, 1.0, "base"),
    ("Cables AWG 28/30", "Wire_SW", 2.4, 2.2, "base"),
    ("OLED 0.96\" I2C", "OLED_SSD1306", 3.0, 1.8, "lid"),
    ("Parlante 8R 1W", "Speaker_Driver", -2.6, -1.6, "lid"),
    ("Pulsador 6x6", "Tact_VolUp", 2.6, -1.8, "lid"),
)

LBL = {}


def build_labels(mats):
    col = collection("08_Etiquetas")
    for i, (body, target, dx, dy, where) in enumerate(LABELS):
        txt = text(body, 1.0, (0, 0, 0), mats["label_ink"], col, None,
                   space=1.02)
        txt.data.align_x = "CENTER"
        txt.data.align_y = "CENTER"
        txt.data.extrude = 0.0
        txt.data.use_fill_caps = False
        lead = wire("lbl_lead_%d" % i, [(0, 0, 0), (0, 0, 1)], 0.03,
                    mats["label_lead"], col, None)
        for ob in (txt, lead):
            ob.hide_render = True
            ob.hide_viewport = True
        LBL[i] = (body, target, dx, dy, where, txt, lead)


def bbox_center(ob):
    cs = [ob.matrix_world @ Vector(v) for v in ob.bound_box]
    lo = Vector((min(c.x for c in cs), min(c.y for c in cs),
                 min(c.z for c in cs)))
    hi = Vector((max(c.x for c in cs), max(c.y for c in cs),
                 max(c.z for c in cs)))
    return (lo + hi) * 0.5


def set_curve(ob, pts):
    sp = ob.data.splines[0]
    while len(sp.points) > len(pts):
        sp.points.remove(sp.points[-1])
    while len(sp.points) < len(pts):
        sp.points.add(1)
    for p, co in zip(sp.points, pts):
        p.co = (co[0], co[1], co[2], 1)


def place_labels(cam, kinds=("base", "lid"), k=0.0055):
    bpy.context.view_layer.update()
    m = cam.matrix_world
    rot = m.to_euler()
    right = (m.to_3x3() @ Vector((1, 0, 0))).normalized()
    up = (m.to_3x3() @ Vector((0, 1, 0))).normalized()
    back = (m.to_3x3() @ Vector((0, 0, 1))).normalized()
    eye = m.translation
    for i, (body, target, dx, dy, where, txt, lead) in LBL.items():
        ob = bpy.data.objects.get(target)
        on = ob is not None and where in kinds
        if on:
            anchor = bbox_center(ob)
            dist = max((anchor - eye).length, 40.0)
            h = k * dist
            txt.scale = (h, h, h)
            txt.rotation_euler = rot
            w = h * (0.50 * len(body) + 1.4)
            pos = (anchor + right * (dx * h) + up * (dy * h)
                   - back * (dist * 0.0075))
            txt.location = pos
            end = pos - right * (0.5 * w * math.copysign(1.0, dx or 1.0))
            set_curve(lead, [anchor, end])
            lead.data.bevel_depth = h * 0.035
        for o in (txt, lead):
            o.hide_render = not on
            o.hide_viewport = not on


def aabb(name, w, l, h, cx, cy, z0):
    return (name, cx - w / 2, cx + w / 2, cy - l / 2, cy + l / 2, z0, z0 + h)


def check_layout():
    boxes = [aabb(*row[:7]) for row in LAYOUT]
    extra = [
        # nombre, w, l, h, cx, cy, z0
        ("Boss_%s_%s" % (int(x), int(y)), 2 * BOSS_R, 2 * BOSS_R,
         INNER_TOP - BOSS_Z0, x, y, BOSS_Z0)
        for x, y in BOSS_XY
    ]
    extra += [
        ("Sleeve_%s_%s" % (int(x), int(y)), 2 * SLEEVE_R, 2 * SLEEVE_R,
         BOSS_Z0 - FLOOR, x, y, FLOOR)
        for x, y in BOSS_XY
    ]
    boxes += [aabb(*row[:7]) for row in extra]
    problems = []
    for i, a in enumerate(boxes):
        for b in boxes[i + 1:]:
            if frozenset((a[0], b[0])) in TOUCHING:
                continue
            ox = min(a[2], b[2]) - max(a[1], b[1])
            oy = min(a[4], b[4]) - max(a[3], b[3])
            oz = min(a[6], b[6]) - max(a[5], b[5])
            if ox > 0.05 and oy > 0.05 and oz > 0.05:
                problems.append((a[0], b[0], ox, oy, oz))
    if problems:
        print("[layout] INTERPENETRACION:")
        for n1, n2, ox, oy, oz in problems:
            print(f"  - {n1} <-> {n2}:  {ox:.2f} x {oy:.2f} x {oz:.2f} mm")
    else:
        print("[layout] OK: 0 interpenetraciones entre piezas")
    return problems


# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
#  ESTUDIO, CAMARAS Y RENDER
# â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

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

    area("Key", (115, -85, 195), (0, 0, 14), 90, 190000, (1.0, 0.97, 0.93))
    area("Fill", (-215, -120, 85), (0, 0, 14), 120, 26000, (0.90, 0.95, 1.0))
    area("Rim", (-25, 225, 165), (0, 0, 16), 140, 210000, (1.0, 0.98, 0.95))
    area("Bounce", (-40, -245, 25), (0, 0, 10), 150, 12000, (0.85, 0.90, 1.0))
    inspect = area("Inspect", (30, -60, 240), (0, -2, 8), 240, 260000, (1.0, 0.99, 0.96))
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
        ("Cam_Hero", (20, -222, 178), (0, 1, 11.5), 82),
        ("Cam_Exploded", (118, -182, 158), (0, 0, 34), 76),
        ("Cam_Internals", (48, -128, 120), (0, -4, 8), 58),
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
    sc.cycles.device = "GPU"
    try:
        prefs = bpy.context.preferences.addons["cycles"].preferences
        prefs.compute_device_type = "OPTIX"
        prefs.get_devices()
        for d in prefs.devices:
            d.use = True
    except Exception as exc:
        print("[render] sin OPTIX, se usa CPU:", exc)
        sc.cycles.device = "CPU"
    sc.cycles.use_denoising = True
    try:
        sc.cycles.denoiser = "OPTIX"
    except Exception:
        pass
    sc.cycles.max_bounces = 8
    sc.cycles.transmission_bounces = 8
    sc.render.film_transparent = False
    sc.render.image_settings.file_format = "PNG"
    sc.render.image_settings.color_mode = "RGBA"
    sc.render.image_settings.color_depth = "8"
    sc.view_settings.view_transform = "AgX"
    try:
        sc.view_settings.look = "AgX - Medium High Contrast"
    except Exception:
        pass
    sc.view_settings.exposure = 0.15


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


def flip_group(group, dz):
    """Voltea el grupo 180 grados sobre Y para ensenar su cara interior."""
    piv = Vector((0.0, 0.0, PARTING_Z))
    m = (Matrix.Translation((0.0, 0.0, dz)) @ Matrix.Translation(piv)
         @ Matrix.Rotation(math.pi, 4, "Y") @ Matrix.Translation(-piv))
    for ob in PARTS[group]:
        ob.matrix_world = m @ ob.matrix_world


EXPLODE_DZ = 46.0
INTERNALS_DZ = 16.0


def explode_wires(mats):
    """Cables que cruzan de la tapa (elevada) a la base en la vista explotada."""
    col = collection("07_Internos")
    out = []

    for k in range(4):
        x = -3.81 + 2.54 * k
        a = (x, 23.5, POCKET_Z0 + 1.22 + EXPLODE_DZ)
        b = (x, 6.4, 16.1 + INTERNALS_DZ)
        out.append(wire("EX_OLED_%d" % k,
                        [a, (x, 18.0, a[2] - 8), (x, 10.0, (a[2] + b[2]) * 0.5),
                         (x, 6.4, b[2] + 6), b],
                        0.30, mats["wire_amber"] if k % 2 else mats["wire_black"],
                        col, None))

    for sx, mat in ((-0.9, mats["wire_amber"]), (0.9, mats["wire_black"])):
        a = (-16.4 + sx, -14.0, 17.8 + EXPLODE_DZ)
        b = (9.5 + sx, -7.0, 16.0 + INTERNALS_DZ)
        out.append(wire("EX_SPK_%+.1f" % sx,
                        [a, (a[0] + 4, -8, a[2] - 8),
                         (18.0, 8.0, a[2] - 20), (13.0, 15.0, b[2] + 6), b],
                        0.42, mat, col, None))

    for c, tag in ((BTN_UP_C, "U"), (BTN_DOWN_C, "D")):
        for sx, mat in ((-0.8, mats["wire_black"]), (0.8, mats["wire_red"])):
            a = (c[0] - 4.0 + sx, c[1] - 5.0, 16.0 + EXPLODE_DZ)
            b = (10.0 + sx, -17.0, 7.0 + INTERNALS_DZ)
            out.append(wire("EX_BTN_%s_%+.1f" % (tag, sx),
                            [a, (a[0] - 3, a[1] - 6, a[2] - 7),
                             (-4.0 + sx, 2.0, (a[2] + b[2]) * 0.5),
                             (-9.0 + sx, 8.0, b[2] + 5), b],
                            0.34, mat, col, None))

    return out


def main():
    sc = bpy.context.scene
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.unit_settings.system = "METRIC"
    sc.unit_settings.length_unit = "MILLIMETERS"
    sc.unit_settings.scale_length = 0.001

    mats = make_materials()
    studio()
    build_bottom(mats)
    build_lid(mats)
    build_screen(mats)
    build_grille(mats)
    build_buttons(mats)
    build_prints(mats)
    build_internals(mats)
    build_labels(mats)

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

    place_labels(cams["Cam_Hero"], ())
    render_view("render_enclosure.png", cams["Cam_Hero"], (1800, 1400), 256)

    # Vista explotada: tapa elevada 46 mm (sin rotar) para ver la pantalla y
    # los botones, con los cables de connection entre tapa y base.
    bpy.data.objects["Inspect"].hide_render = False
    move_group("lid", EXPLODE_DZ)
    move_group("internals", INTERNALS_DZ)
    for ob in PARTS["lid"]:
        if ob.name.startswith(("OLED_Wire_", "Wire_SPK_", "Wire_BTN_")):
            ob.hide_render = True
    extra = explode_wires(mats)
    place_labels(cams["Cam_Exploded"], ("base", "lid"))
    render_view("render_exploded.png", cams["Cam_Exploded"], (1800, 1400), 160)
    place_labels(cams["Cam_Exploded"], ())
    for ob in extra:
        bpy.data.objects.remove(ob, do_unlink=True)
    for ob in PARTS["lid"]:
        if ob.name.startswith(("OLED_Wire_", "Wire_SPK_", "Wire_BTN_")):
            ob.hide_render = False
    move_group("lid", -EXPLODE_DZ)
    move_group("internals", -INTERNALS_DZ)

    # Vista interna: sin tapa, para juzgar el empaquetado
    for ob in PARTS["lid"]:
        ob.hide_render = True
    place_labels(cams["Cam_Internals"], ("base",))
    render_view("render_internals.png", cams["Cam_Internals"], (1800, 1400), 160)
    place_labels(cams["Cam_Internals"], ())
    bpy.data.objects["Inspect"].hide_render = True
    for ob in PARTS["lid"]:
        ob.hide_render = False

    bpy.context.scene.camera = cams["Cam_Hero"]
    bpy.context.scene.render.filepath = os.path.join(OUT_DIR, "render_enclosure.png")
    blend = os.path.join(OUT_DIR, "enclosure.blend")
    bpy.ops.wm.save_as_mainfile(filepath=blend)
    print(f"[blend] {blend}")


if __name__ == "__main__":
    main()

