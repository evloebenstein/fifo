"""Llavero Fifo (kit XIAO ESP32-S3) en mm, parametrico y verificable.

    blender --background --factory-startup --python hardware/enclosure/build_fifo.py

El sobre exterior es 38 x 55 x 22 mm con esquinas R4 (aristas verticales y
caras frontal/trasera) y dos piezas reales:

    cuerpo    Z = -1.50 .. 19.00   (piso 1.5, paredes 1.5, frente abierto)
    frente    Z = 19.00 .. 20.50   (placa de 1.5 con ventanas y rejilla)

La cara frontal (Z = 20.5) lleva, de arriba abajo:

    ventana OLED      23.0 x 12.5 mm (modulo real 27 x 27 x 3, activa 24x12)
    pulsadores        dos botones 6x6 bajo la ventana, lado derecho
    parlante          rejilla de agujeros sobre driver O16 mm
    microfono         poro O1 bajo el INMP441

Los internos van en un sandwich a lo largo de Z, con 1 mm de aire entre
capas. De la trasera a la frontal:

    capa 1 (Z 0..5.7)   ATGM336H GPS + parche ceramico 16x16x1.2
    capa 2 (Z 5.7..10.7) LiPo 20x30x5 (300 mAh)
    capa 3 (Z 10.7..15.7) XIAO 17.8x21x4 (izq) + MAX98357A 15x18x2.5 (der)
    capa 4 (Z 16..19)   OLED 27x27x3 (arriba) + parlante O16x3 (abajo-izq)
                         + INMP441 O14x2 (centro-izq) + 2 pulsadores 6x6

Medidas del BOM (mm):

    XIAO ESP32-S3 ............ 17.8 x 21.0 x 4.0
    OLED 1.3" 128x64 ......... 27.0 x 27.0 x 3.0   (activa 24.0 x 12.0)
    INMP441 (mic I2S) ........ O14.0 x 2.0
    MAX98357A (amp I2S) ...... 15.0 x 18.0 x 2.5
    Parlante plano 8R 1W ..... O16.0 x 3.0
    LiPo 203050 300 mAh ...... 20.0 x 30.0 x 5.0
    ATGM336H GPS+BeiDou ...... 25.0 x 25.0 x 4.5   (+ parche ceramico 16x16x1.2)
    Interruptor SS12D07 ...... 8.5 x 3.5 x 3.5
    Pulsadores 6x6 ........... 6.0 x 6.0 x 3.5

Salidas: fifo.blend, render_fifo.png (hero), render_fifo_exploded.png y
render_fifo_internals.png. El script corre la comprobacion de interpenetracion
y falla si dos piezas comparten volumen.
"""

import math
import os
import sys

import bpy
import bmesh
from mathutils import Matrix, Vector

# ─────────────────────────────────────────────────────────────────────────────
#  PARAMETROS
# ─────────────────────────────────────────────────────────────────────────────

OUT_DIR = os.path.dirname(os.path.abspath(__file__))
QUICK = os.environ.get("FIFO_QUICK") == "1"

# Sobre exterior: llavero compacto 38 x 55 x 22, centrado en el origen.
BODY_W = 38.0
BODY_L = 55.0
BODY_H = 22.0
CORNER_R = 4.0
WALL = 1.5

# Coordenadas centradas. El sobre va de X -19..19, Y -27.5..27.5,
# Z -1.5..20.5. La cara frontal (pantalla) mira hacia +Z.
EXT_X = BODY_W / 2            # 19.0
EXT_Y = BODY_L / 2            # 27.5
EXT_Z0 = -WALL                # -1.5
EXT_Z1 = BODY_H - WALL        # 20.5
IN_X = EXT_X - WALL           # 17.5
IN_Y = EXT_Y - WALL           # 26.0
IN_Z0 = 0.0
IN_Z1 = 19.0                  # interior hasta la cara interior del frente

# Particion cuerpo / frente: el cuerpo llega a Z=19 (cara interior del
# frente), el frente es una placa de 1.5 que se encaja a presion.
BODY_Z1 = 19.0
FACE_Z0 = 19.0
FACE_Z1 = 20.5

# Sandwich interno (capas de Z, con 1 mm de aire entre capas):
#   capa 1  Z 0.0..5.7    GPS + parche
#   capa 2  Z 5.7..10.7   LiPo
#   capa 3  Z 10.7..15.7  XIAO + MAX98357A
#   capa 4  Z 16.0..19.0  OLED + parlante + microfono + pulsadores
GPS_Z0 = 0.0
GPS_H = 4.5
PATCH_Z0 = GPS_H
PATCH_H = 1.2
LIPO_Z0 = 5.7
LIPO_H = 5.0
XIAO_Z0 = 10.7
XIAO_H = 4.0
AMP_Z0 = 10.7
AMP_H = 2.5
OLED_Z0 = 16.0
OLED_H = 3.0

# OLED 1.3" 128x64: modulo 27x27x3, area activa 24x12. El modulo sube al
# borde superior de la cavidad (Y=26) para dejar sitio al resto.
OLED_W, OLED_L, OLED_H = 27.0, 27.0, 3.0
ACT_W, ACT_L = 24.0, 12.0
OLED_C = (0.0, 12.5)               # centro del modulo (X, Y)
ACT_C = (0.0, 12.5)                # centro del area activa
WIN_W, WIN_L, WIN_R = 23.0, 12.5, 2.0

# Parlante O16x3 en la cara inferior izquierda. La rejilla es una matriz de
# agujeros de 1.5 en un circulo O16.
SPK_C = (4.0, -18.0)
SPK_R = 8.0
SPK_H = 3.0
GRILLE_R = 8.0
GRILLE_HOLE = 1.5

# INMP441 O14x2, centro-izquierda. El poro del micro va en el frente.
MIC_C = (-10.5, -8.0)
MIC_R = 7.0
MIC_H = 2.0
MIC_PORT = (-10.5, -8.0)           # poro O1 en el frente

# Pulsadores 6x6x3.5 bajo la ventana, lado derecho.
BTN1_C = (14.5, -4.5)
BTN2_C = (14.5, -11.0)
BTN_W, BTN_L, BTN_H = 6.0, 6.0, 3.5
BTN_BORE_R = 1.75                  # agujero de paso O3.5

# XIAO en el borde izquierdo, USB-C hacia la pared -Y (hacia abajo).
# El XIAO se rota 90° en el plano XY para que el USB-C mire hacia el
# borde inferior de la caja (Y negativo), quedando oculto en la vista
# frontal y menos expuesto en los renders.
XIAO_CX = -8.4
XIAO_CY = 0.0
# USB-C ahora en la pared -Y (borde inferior). Ancho en X, alto en Z.
USB_SLOT = (8.0, 2.6)              # ancho en X, alto en Z (pared -Y)
USB_ZC = 12.7                      # centro Z del corte USB-C
USB_XC = -8.4                      # centro X del corte USB-C

# Interruptor en la pared +X, a la altura de la capa 3.
SW_SLOT = (8.5, 3.5)               # largo en Y, alto en Z (pared +X)
SW_ZC = 12.7

# Llavero: asa en la cara superior (+Y), en el eje X.
BAIL_R = 3.5
BAIL_T = 1.5

# Tolerancia booleana de los cortes
TOL = 0.30

# Colores
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
    # capa 1: GPS + parche
    ("GPS_ATGM336H", 25.0, 25.0, 4.5, 0.0, 0.0, GPS_Z0, "gps"),
    ("GPS_Patch", 16.0, 16.0, 1.2, 0.0, 0.0, PATCH_Z0, "ceramic"),
    # capa 2: LiPo
    ("LiPo_203050", 20.0, 30.0, 5.0, 0.0, 0.0, LIPO_Z0, "battery"),
    # capa 3: XIAO + MAX98357A
    ("XIAO_ESP32S3", 21.0, 17.8, 4.0, XIAO_CX, XIAO_CY, XIAO_Z0, "pcb"),
    ("MAX98357A", 15.0, 18.0, 2.5, 9.7, 0.0, AMP_Z0, "pcb_amp"),
    # capa 4: OLED + parlante + micro + pulsadores
    ("OLED_1in3", OLED_W, OLED_L, OLED_H, OLED_C[0], OLED_C[1],
     OLED_Z0, "pcb_oled"),
    ("Speaker_Driver", 16.0, 16.0, 3.0, SPK_C[0], SPK_C[1],
     OLED_Z0, "driver"),
    ("INMP441", 14.0, 14.0, 2.0, MIC_C[0], MIC_C[1], 17.0, "pcb_mic"),
    ("Tact_Btn1", 6.0, 6.0, 3.5, BTN1_C[0], BTN1_C[1], 15.5, "switch"),
    ("Tact_Btn2", 6.0, 6.0, 3.5, BTN2_C[0], BTN2_C[1], 15.5, "switch"),
)

# Pares que se tocan a proposito (contacto, no interpenetracion)
TOUCHING = {
    frozenset(("GPS_ATGM336H", "GPS_Patch")),
    frozenset(("LiPo_203050", "XIAO_ESP32S3")),
    frozenset(("OLED_1in3", "Speaker_Driver")),
}

# Piezas redondas: se comprueban como circulos en XY, no como cajas.
ROUND = {"Speaker_Driver", "INMP441"}


# ─────────────────────────────────────────────────────────────────────────────
#  UTILIDADES
# ─────────────────────────────────────────────────────────────────────────────

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


# ── materiales ────────────────────────────────────────────────────────────────

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
        "shell": principled("Carcasa_Midnight", (0.048, 0.058, 0.105, 1.0),
                            rough=0.42, coat=0.0, spec=0.28),
        "bezel": principled("Bisel_Negro", (0.021, 0.025, 0.043, 1.0),
                            rough=0.38, spec=0.25),
        "glass": principled("Bisel_Cristal", (0.006, 0.008, 0.012, 1.0),
                            rough=0.60, spec=0.05),
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
        "btn_cap": principled("Boton_Cap", BTN_CAP, rough=0.42, coat=0.10, spec=0.25),
        "green_ink": principled("Leyenda_Verde", (0.10, 0.60, 0.26, 1.0),
                                rough=0.45, emit=(0.10, 0.60, 0.26, 1.0),
                                emit_strength=0.55),
        "red_ink": principled("Leyenda_Roja", (0.72, 0.11, 0.13, 1.0),
                              rough=0.45, emit=(0.72, 0.11, 0.13, 1.0),
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
        "backdrop": principled("Fondo", (0.012, 0.013, 0.017, 1.0), rough=0.30),
    }


# ─────────────────────────────────────────────────────────────────────────────
#  CUERPO (contera + paredes, frente abierto)
# ─────────────────────────────────────────────────────────────────────────────

def build_body(mats):
    col = collection("01_Cuerpo")

    # Sobre exterior desde Z=-1.5 hasta Z=19, con chaflan en el piso.
    body = prism("Fifo_Body", BODY_W, BODY_L, CORNER_R,
                 EXT_Z0, BODY_Z1, col, "body",
                 bevel_bottom=1.0)
    assign(body, mats["shell"])

    # Cavidad interior (frente abierto en Z=19).
    cav = prism("cav", 2 * IN_X, 2 * IN_Y, CORNER_R - WALL,
                IN_Z0 - 0.5, BODY_Z1 + 0.5, col)
    cut(body, cav, "cav")

    # Hueco USB-C en la pared -Y (borde inferior): el conector del XIAO
    # pasa por aqui, mirando hacia abajo como el concepto.
    usb = prism("usb", USB_SLOT[0] + TOL, WALL + TOL, 0.6,
                USB_ZC - USB_SLOT[1] / 2 - TOL / 2,
                USB_ZC + USB_SLOT[1] / 2 + TOL / 2, col)
    usb.location = (USB_XC, -EXT_Y, 0.0)
    select_only(usb)
    bpy.ops.object.transform_apply(location=True)
    cut(body, usb, "usb")

    # Ranura del interruptor en la pared trasera (-Z), oculta en la vista
    # frontal y menos expuesta que en la pared lateral. Se coloca en Y=+20
    # para no interferir con el GPS (centrado en 0,0, 25x25). La ranura
    # corta 4.5 mm hacia adentro desde la superficie exterior: atraviesa la
    # pared de 1.5 mm y deja un rebajo de 3.0 mm donde se asienta el cuerpo
    # del interruptor; solo la palanca sobresale 0.5 mm, casi al ras.
    slot = block("sw_slot", SW_SLOT[0], 6.0, 4.5,
                 0.0, 20.0, EXT_Z0 + 2.25, col)
    cut(body, slot, "sw")

    shade_smooth(body)
    return body


# ─────────────────────────────────────────────────────────────────────────────
#  FRENTE (placa de 1.5 con ventanas)
# ─────────────────────────────────────────────────────────────────────────────

def build_face(mats):
    col = collection("02_Frente")

    face = prism("Fifo_Face", BODY_W, BODY_L, CORNER_R,
                 FACE_Z0, FACE_Z1, col, "face",
                 bevel_top=0.8)
    assign(face, mats["shell"])

    # Ventana OLED: bolsillo para el modulo + ventana del area activa.
    pocket = prism("oled_pocket", OLED_W + TOL, OLED_L + TOL,
                   CORNER_R - WALL + 0.5, FACE_Z0 - 1.0, FACE_Z0 + 1.0, col)
    pocket.location = (OLED_C[0], OLED_C[1], 0.0)
    win = prism("win", WIN_W, WIN_L, WIN_R, FACE_Z0 - 0.05, FACE_Z1 + 1.0, col)
    win.location = (ACT_C[0], ACT_C[1], 0.0)
    for ob in (pocket, win):
        select_only(ob)
        bpy.ops.object.transform_apply(location=True)
    cut(face, [pocket, win], "screen")

    # Rejilla del parlante: aro metálico circular en relieve + matriz de
    # agujeros O1.5 en un círculo O16, como el concepto.
    ring = disc("Grille_Ring", 9.5, FACE_Z0 - 0.3, FACE_Z1 + 0.45,
                SPK_C[0], SPK_C[1], col, "face", verts=64)
    inner = disc("ring_i", 8.0, FACE_Z0 - 0.3, FACE_Z1 + 1.0,
                 SPK_C[0], SPK_C[1], col, verts=64)
    cut(ring, inner, "ring")
    assign(ring, mats["grille"])
    shade_smooth(ring)

    holes = []
    pitch = 2.2
    for j in range(-6, 7):
        for i in range(-6, 7):
            x = SPK_C[0] + i * pitch + (pitch / 2 if j % 2 else 0.0)
            y = SPK_C[1] + j * pitch * 0.866
            if math.hypot(x - SPK_C[0], y - SPK_C[1]) > GRILLE_R - 0.8:
                continue
            holes.append(disc("gh", GRILLE_HOLE / 2, FACE_Z0 - 0.5, FACE_Z1 + 1.0,
                              x, y, col, verts=8))
    if holes:
        cut(face, join(holes, "Grille_Holes", col), "gholes")

    # Pulsadores: agujero de paso O3.5 en el frente.
    bores = [disc("btn_bore", BTN_BORE_R + TOL / 2, FACE_Z0 - 0.5,
                  FACE_Z1 + 1.0, c[0], c[1], col, verts=32)
             for c in (BTN1_C, BTN2_C)]
    cut(face, bores, "btn")

    # Poro del microfono O1 en el frente, sobre el INMP441.
    mic = disc("mic_port", 0.5, FACE_Z0 - 0.5, FACE_Z1 + 1.0,
               MIC_PORT[0], MIC_PORT[1], col, verts=24)
    cut(face, mic, "micport")

    # Rótulo "ALTAVOZ" debajo de la rejilla, como el concepto.
    text("ALTAVOZ", 1.30, (SPK_C[0], SPK_C[1] - 9.0, FACE_Z1 + 0.015),
         mats["print_dim"], col, "face", space=1.30)

    shade_smooth(face)
    return face


# ─────────────────────────────────────────────────────────────────────────────
#  ASA DE LLAVERO
# ─────────────────────────────────────────────────────────────────────────────

def build_bail(mats):
    col = collection("03_Asa")
    bm = bmesh.new()
    mat = Matrix.Translation((0.0, EXT_Y + BAIL_R, 10.0))
    bmesh.ops.create_circle(bm, cap_ends=False, segments=48, radius=BAIL_T)
    bmesh.ops.rotate(bm, verts=bm.verts, cent=(0, 0, 0),
                     matrix=Matrix.Rotation(math.pi / 2, 4, "X"))
    bmesh.ops.translate(bm, verts=bm.verts, vec=(0, 0, 0))
    bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=24,
                          radius1=BAIL_T, radius2=BAIL_T, depth=2 * math.pi * BAIL_R,
                          matrix=mat)
    # Reemplazo simple: toro por primitiva.
    bm.free()
    bm = bmesh.new()
    # Torus: mayor=BAIL_R, menor=BAIL_T, en el plano XZ (vertical), en Y=EXT_Y.
    import mathutils
    seg_u, seg_v = 48, 16
    verts = []
    for i in range(seg_u):
        u = 2 * math.pi * i / seg_u
        for j in range(seg_v):
            v = 2 * math.pi * j / seg_v
            x = (BAIL_R + BAIL_T * math.cos(v)) * math.cos(u)
            z = (BAIL_R + BAIL_T * math.cos(v)) * math.sin(u)
            y = BAIL_T * math.sin(v)
            verts.append(bm.verts.new((x, y + EXT_Y, z + 10.0)))
    for i in range(seg_u):
        for j in range(seg_v):
            a = i * seg_v + j
            b = i * seg_v + (j + 1) % seg_v
            c = ((i + 1) % seg_u) * seg_v + (j + 1) % seg_v
            d = ((i + 1) % seg_u) * seg_v + j
            bm.faces.new((verts[a], verts[b], verts[c], verts[d]))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    me = bpy.data.meshes.new("Bail")
    bm.to_mesh(me)
    bm.free()
    ob = add("Fifo_Bail", me, col, "body")
    assign(ob, mats["shell"])
    shade_smooth(ob)
    return ob


# ─────────────────────────────────────────────────────────────────────────────
#  PANTALLA
# ─────────────────────────────────────────────────────────────────────────────

def build_screen(mats):
    col = collection("04_Pantalla")

    # Lente acrilica enrasada en la ventana.
    lens = prism("Acrylic_Lens", WIN_W - 0.06, WIN_L - 0.06, WIN_R - 0.04,
                 FACE_Z0 - 0.02, FACE_Z1 + 0.02, col, "face", seg=12,
                 bevel_top=0.18, bevel_seg=2)
    assign(lens, mats["lens"])
    shade_smooth(lens)
    lens.location = (ACT_C[0], ACT_C[1], 0.0)

    # Modulo OLED 1.3": PCB negro 27x27x3 + vidrio + area activa.
    pcb = block("OLED_1in3", OLED_W, OLED_L, 2.8,
                OLED_C[0], OLED_C[1], OLED_Z0 + 0.02, col, "internals")
    assign(pcb, mats["pcb_oled"])

    glass = block("OLED_Glass", WIN_W, WIN_L, 0.12,
                  ACT_C[0], ACT_C[1], OLED_Z0 + 2.82, col, "internals")
    assign(glass, mats["clear"])

    lcd = block("OLED_LCD", ACT_W, ACT_L, 0.05,
                ACT_C[0], ACT_C[1], OLED_Z0 + 2.94, col, "internals")
    assign(lcd, mats["lcd"])

    # Cara de Fifo: dos ojos en arco y una sonrisa, dentro del area activa.
    face_z = OLED_Z0 + 3.00
    for ex in (ACT_C[0] - 4.6, ACT_C[0] + 4.6):
        cu = bpy.data.curves.new("eye", "CURVE")
        cu.dimensions = "3D"
        sp = cu.splines.new("POLY")
        pts = []
        for k in range(19):
            a = math.radians(-72 + k * (324 / 18))
            pts.append((ex + 2.0 * math.cos(a),
                        ACT_C[1] + 1.0 + 2.0 * math.sin(a)))
        sp.points.add(len(pts) - 1)
        for p, (px, py) in zip(sp.points, pts):
            p.co = (px, py, 0, 1)
        cu.bevel_depth = 0.32
        cu.bevel_resolution = 2
        cu.use_fill_caps = True
        ob = bpy.data.objects.new("eye", cu)
        ob.location.z = face_z
        col.objects.link(ob)
        PARTS["internals"].append(ob)
        assign(ob, mats["lcd_off"])

    cu = bpy.data.curves.new("smile", "CURVE")
    cu.dimensions = "3D"
    sp = cu.splines.new("POLY")
    pts = []
    for k in range(23):
        t = -1 + k * (2 / 22)
        pts.append((ACT_C[0] + 5.0 * t,
                    ACT_C[1] - 1.6 + 1.3 * (t * t)))
    sp.points.add(len(pts) - 1)
    for p, (px, py) in zip(sp.points, pts):
        p.co = (px, py, 0, 1)
    cu.bevel_depth = 0.32
    cu.bevel_resolution = 2
    cu.use_fill_caps = True
    smile = bpy.data.objects.new("smile", cu)
    smile.location.z = face_z
    col.objects.link(smile)
    PARTS["internals"].append(smile)
    assign(smile, mats["lcd_off"])

    return lens, lcd


# ─────────────────────────────────────────────────────────────────────────────
#  BOTONES
# ─────────────────────────────────────────────────────────────────────────────

def build_buttons(mats):
    col = collection("05_Botones")

    def cap(name, c, big, small, ink):
        # Anillo metálico que encaja el botón en el frente, como el concepto.
        ring_o = disc(name + "_ring_o", 2.2, FACE_Z0 - 0.3, FACE_Z1 + 0.02,
                      c[0], c[1], col, "face", verts=48)
        ring_i = disc(name + "_ring_i", 1.95, FACE_Z0 - 0.3, FACE_Z1 + 1.0,
                      c[0], c[1], col, verts=48)
        cut(ring_o, ring_i, "ring")
        assign(ring_o, mats["grille"])
        shade_smooth(ring_o)

        # Tapa del botón, ligeramente hundida en el anillo.
        ob = disc(name, 1.9, FACE_Z0 - 0.05, FACE_Z1 + 0.02,
                  c[0], c[1], col, "face", verts=48)
        bev = ob.modifiers.new("b", "BEVEL")
        bev.width = 0.25
        bev.segments = 3
        bev.limit_method = "ANGLE"
        bev.angle_limit = math.radians(35)
        select_only(ob)
        bpy.ops.object.modifier_apply(modifier=bev.name)
        assign(ob, mats["btn_cap"])
        shade_smooth(ob)

        # Vástago que empuja el tact switch por el hueco del frente.
        stem = disc(name + "_stem", 1.2, OLED_Z0 - 0.5, FACE_Z0 + 0.1,
                    c[0], c[1], col, "internals", verts=24)
        assign(stem, mats["switch"])

        # Leyenda dentro del botón: "+ SUBIR VOL" / "- BAJAR VOL".
        t1 = text(big, 0.95, (c[0], c[1] + 0.35, FACE_Z1 + 0.04), ink, col, "face")
        t1.data.space_character = 1.02
        t2 = text(small, 0.72, (c[0], c[1] - 0.55, FACE_Z1 + 0.04), ink, col, "face")
        t2.data.space_character = 1.02
        return ob

    cap("Button_Up", BTN1_C, "+", "SUBIR VOL", mats["green_ink"])
    cap("Button_Down", BTN2_C, "-", "BAJAR VOL", mats["red_ink"])


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


# ─────────────────────────────────────────────────────────────────────────────
#  INTERNOS
# ─────────────────────────────────────────────────────────────────────────────

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
    col = collection("06_Internos")
    parts = {}
    for name, w, l, h, cx, cy, z0, mat in LAYOUT:
        ob = block(name, w, l, h, cx, cy, z0, col, "internals")
        assign(ob, mats[mat])
        parts[name] = ob

    # XIAO en el centro-izquierda: almohadillas, LED y blindaje RF.
    # El XIAO mide 21.0 (X) x 17.8 (Y) x 4.0 (Z) y el USB-C va en el
    # borde -Y (hacia abajo), alineado con el corte de la pared inferior.
    for k in range(7):
        x = XIAO_CX + (k - 3) * 2.3
        for sy in (-1, 1):
            pad = block("pad", 1.4, 0.9, 0.2, x, XIAO_CY + sy * 8.2,
                        XIAO_Z0 + XIAO_H - 0.2, col, "internals")
            assign(pad, mats["gold"])
    led = block("led", 1.0, 1.4, 0.3, XIAO_CX + 8.0, XIAO_CY + 4.4,
                XIAO_Z0 + XIAO_H - 0.2, col, "internals")
    assign(led, mats["btn_cap"])
    shield = block("RF_Shield", 12.0, 14.0, 0.2, XIAO_CX, XIAO_CY,
                   XIAO_Z0 + XIAO_H - 0.2, col, "internals")
    assign(shield, mats["foil"])

    # Conector USB-C del XIAO: sobresale por la pared -Y (hacia abajo).
    usbc = block("USB_C_XIAO", 8.5, 7.5, 2.6, USB_XC, -IN_Y - 0.5,
                 USB_ZC - 1.3, col, "internals")
    assign(usbc, mats["steel"])
    mouth = block("USB_C_Mouth", 7.0, 0.6, 2.0, USB_XC, -EXT_Y - 0.2,
                  USB_ZC - 1.0, col, "internals")
    assign(mouth, mats["rubber"])

    # GPS: conector.
    gps_conn = block("GPS_Conn", 5.0, 3.0, 2.0, 0.0, 12.5, GPS_Z0,
                     col, "internals")
    assign(gps_conn, mats["steel"])

    # Bateria: etiqueta y conector JST.
    label = block("Battery_Label", 20.3, 5.0, 5.1, 0.0, 12.5,
                  LIPO_Z0 - 0.05, col, "internals")
    assign(label, mats["steel"])
    jst = block("Battery_JST", 6.0, 4.0, 2.5, 0.0, -12.0, LIPO_Z0 + 1.0,
                col, "internals")
    assign(jst, mats["print"])

    # MAX98357A: chip y bornera de parlante.
    amp_chip = block("Amp_Chip", 6.0, 4.0, 0.6, 9.7, 0.0,
                     AMP_Z0 + AMP_H - 0.6, col, "internals")
    assign(amp_chip, mats["switch"])
    amp_term = block("Amp_Terminal", 4.0, 3.0, 2.0, 14.5, 0.0,
                     AMP_Z0 + AMP_H - 2.0, col, "internals")
    assign(amp_term, mats["pcb_mic"])

    # Parlante: cono y magneto.
    cone = block("Speaker_Cono", 8.0, 8.0, 0.5, SPK_C[0], SPK_C[1],
                 OLED_Z0 + 2.5, col, "internals")
    assign(cone, mats["foil"])
    magnet = block("Speaker_Magnet", 6.0, 6.0, 1.0, SPK_C[0], SPK_C[1],
                   OLED_Z0 + 0.2, col, "internals")
    assign(magnet, mats["steel"])

    # Tact switches de los pulsadores.
    for c, name in ((BTN1_C, "Tact_Btn1"), (BTN2_C, "Tact_Btn2")):
        sw = block(name, BTN_W, BTN_L, BTN_H, c[0], c[1],
                   OLED_Z0 - 0.5, col, "internals")
        assign(sw, mats["switch"])

    # Interruptor en la pared trasera (-Z): cuerpo + palanca (SS12D07 real 8.5x3.5x3.5).
    # Se coloca en Y=+20 para no interferir con el GPS (centrado en 0,0, 25x25).
    sw = block("Switch_SS12D07", 8.5, 3.5, 3.5, 0.0, 20.0,
               EXT_Z0 + 1.75, col, "internals")
    assign(sw, mats["switch"])
    lever = block("Switch_Lever", 3.6, 2.5, 1.0, 0.0, 20.0,
                  EXT_Z0 + 3.9, col, "internals")
    assign(lever, mats["print"])

    # Cableado: todos los cables corren por los bordes de la cavidad.
    # PWR LiPo (JST en 0,-12) -> XIAO por el borde izquierdo.
    for dx, mat in ((-0.7, mats["wire_red"]), (0.7, mats["wire_black"])):
        wire(f"Wire_PWR_{dx:+.1f}",
             [(dx, -12.0, LIPO_Z0 + LIPO_H - 0.5), (dx, -18.0, 12.0),
              (-IN_X - 0.5, -18.0, 13.0), (-IN_X - 0.5, 0.0, 14.0),
              (XIAO_CX - 8.0, 0.0, 14.7)],
             0.5, mat, col)
    # GPS TX/RX: GPS (0,0,4.5) -> por pared +Y -> bajada por -X -> XIAO.
    wire("Wire_GPS_TX",
         [(0.0, 2.0, GPS_Z0 + 2.0), (0.0, IN_Y - 0.5, 6.0),
          (-IN_X - 0.5, IN_Y - 0.5, 8.0), (-IN_X - 0.5, 0.0, 12.0),
          (XIAO_CX - 8.0, 0.0, 13.5)],
         0.42, mats["wire_amber"], col)
    wire("Wire_GPS_RX",
         [(0.0, 2.0, GPS_Z0 + 1.5), (0.0, IN_Y - 0.5, 5.5),
          (-IN_X - 0.5, IN_Y - 0.5, 7.5), (-IN_X - 0.5, 0.0, 11.5),
          (XIAO_CX - 8.0, 0.0, 13.0)],
         0.42, mats["wire_black"], col)
    # I2S MIC: INMP441 (-10.5,-8,18) -> por borde izquierdo -> XIAO.
    wire("Wire_I2S",
         [(MIC_C[0], MIC_C[1], 18.0), (-IN_X - 0.5, MIC_C[1], 17.5),
          (-IN_X - 0.5, 0.0, 14.5), (XIAO_CX - 8.0, 0.0, 14.0)],
         0.42, mats["wire_blue"], col)
    # Altavoz: driver (4,-18,18.5) -> MAX98357A (9.7,0,13) por el borde izq.
    wire("Wire_SPK",
         [(SPK_C[0], SPK_C[1], 18.5), (-IN_X - 0.5, SPK_C[1], 17.5),
          (-IN_X - 0.5, 0.0, 13.5), (4.7, 0.0, 13.2)],
         0.42, mats["wire_black"], col)
    # Switch (0,20,-1.5) -> por pared trasera -> bajada por -X -> XIAO.
    wire("Wire_SW",
         [(0.0, 20.0, EXT_Z0 + 1.0), (0.0, 16.0, 2.0),
          (-IN_X - 0.5, 16.0, 3.0), (-IN_X - 0.5, -8.0, 4.0),
          (XIAO_CX - 8.0, -8.0, 5.0), (XIAO_CX - 8.0, 0.0, 6.0)],
         0.42, mats["wire_red"], col)

    return parts


def auto_smooth_all(angle=32.0):
    """Caras planas horizontales sin promediar normales; curvas suaves."""
    for ob in list(bpy.data.objects):
        if ob.type != "MESH":
            continue
        for p in ob.data.polygons:
            if abs(p.normal.z) > 0.999:
                p.use_smooth = False


# ─────────────────────────────────────────────────────────────────────────────
#  COMPROBACION DE INTERPENETRACION
# ─────────────────────────────────────────────────────────────────────────────

def aabb(name, w, l, h, cx, cy, z0):
    return (name, cx - w / 2, cx + w / 2, cy - l / 2, cy + l / 2, z0, z0 + h)


def circ_rect_dist(cx, cy, r, x0, x1, y0, y1):
    """Distancia del centro del circulo al rectangulo (0 si esta dentro)."""
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
                # Las piezas redondas se comprueban como circulos en XY.
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


# ─────────────────────────────────────────────────────────────────────────────
#  ESTUDIO, CAMARAS Y RENDER
# ─────────────────────────────────────────────────────────────────────────────

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

    area("Key", (115, -85, 195), (0, 0, 12), 90, 190000, (1.0, 0.97, 0.93))
    area("Fill", (-215, -120, 85), (0, 0, 12), 120, 26000, (0.90, 0.95, 1.0))
    area("Rim", (-25, 225, 165), (0, 0, 14), 140, 210000, (1.0, 0.98, 0.95))
    area("Bounce", (-40, -245, 25), (0, 0, 10), 150, 12000, (0.85, 0.90, 1.0))
    inspect = area("Inspect", (30, -60, 240), (0, -2, 10), 240, 260000, (1.0, 0.99, 0.96))
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
        ("Cam_Hero", (14, -175, 150), (0, 1, 11), 82),
        ("Cam_Exploded", (100, -150, 140), (0, 0, 30), 76),
        ("Cam_Internals", (40, -105, 105), (0, -4, 9), 58),
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
    sc.cycles.use_denoising = True
    try:
        sc.cycles.denoiser = "OPENIMAGEDENOISE"
    except Exception:
        pass
    sc.cycles.max_bounces = 4
    sc.cycles.transmission_bounces = 4
    sc.cycles.transparent_max_bounces = 4
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


EXPLODE_DZ = 40.0
INTERNALS_DZ = 14.0


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
    build_bail(mats)
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

    render_view("render_fifo.png", cams["Cam_Hero"], (1360, 1070), 128)

    # Vista explotada: frente elevado 40 mm, con los internos visibles.
    bpy.data.objects["Inspect"].hide_render = False
    move_group("face", EXPLODE_DZ)
    move_group("internals", INTERNALS_DZ)
    render_view("render_fifo_exploded.png", cams["Cam_Exploded"], (1200, 940), 96)
    move_group("face", -EXPLODE_DZ)
    move_group("internals", -INTERNALS_DZ)

    # Vista interna: sin frente, para juzgar el empaquetado.
    for ob in PARTS["face"]:
        ob.hide_render = True
    render_view("render_fifo_internals.png", cams["Cam_Internals"], (1200, 940), 96)
    bpy.data.objects["Inspect"].hide_render = True
    for ob in PARTS["face"]:
        ob.hide_render = False

    bpy.context.scene.camera = cams["Cam_Hero"]
    blend = os.path.join(OUT_DIR, "fifo.blend")
    bpy.ops.wm.save_as_mainfile(filepath=blend)
    print(f"[blend] {blend}")


if __name__ == "__main__":
    main()
