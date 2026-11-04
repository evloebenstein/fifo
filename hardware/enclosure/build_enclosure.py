"""Genera el modelo 3D paramétrico del robot Fifo (kit XIAO ESP32-S3).

Puede ejecutarse:
1. Directamente vía el puente MCP de Blender en http://localhost:3000:
       python hardware/enclosure/build_enclosure.py --mcp
2. En modo headless / background de Blender:
       blender --background --factory-startup --python hardware/enclosure/build_enclosure.py

Genera:
- hardware/enclosure/enclosure.blend (con objetos y materiales registrados como Assets)
- hardware/enclosure/render_enclosure.png (render de estudio en alta resolución)
"""

import math
import os
import sys

# Si se ejecuta desde Python externo con flag --mcp o si no está en entorno bpy:
if __name__ == "__main__" and ("--mcp" in sys.argv or "bpy" not in sys.modules):
    try:
        import bpy
    except ImportError:
        # Ejecutar mediante el cliente MCP SSE en localhost:3000
        cur_dir = os.path.dirname(os.path.abspath(__file__))
        sys.path.insert(0, cur_dir)
        from mcp_client import get_client

        print("[FIFO] Conectando a Blender MCP en http://localhost:3000/sse ...")
        client = get_client()

        # Leemos este mismo archivo para enviarlo a Blender
        with open(__file__, "r", encoding="utf-8") as f:
            script_code = f.read()

        # Inyectar ejecución interna en Blender
        remote_code = f"""
import sys
sys.argv = ['blender', '--internal-run']
{script_code}
main()
"""
        print("[FIFO] Enviando script generador a Blender vía MCP...")
        res = client.execute_python(remote_code, timeout=180)
        output = res.get("result", {}).get("result", "")
        print(output)
        if res.get("status") == "success":
            print("[FIFO] Modelo y render generados exitosamente en la sesion de Blender.")
            sys.exit(0)
        else:
            print(f"[FIFO] Error en ejecucion MCP: {res}")
            sys.exit(1)

import bpy
import bmesh
import mathutils

# ═══════════════════════════════════════════════════════════════════════
#  PARAMETROS FISICOS DEL HARDWARE (mm)
# ═══════════════════════════════════════════════════════════════════════

try:
    OUT_DIR = os.path.dirname(os.path.abspath(__file__))
except NameError:
    OUT_DIR = r"c:\Users\evloe\OneDrive\Escritorio\fifo\hardware\enclosure"

# Carcasa exterior
BODY_W = 62.0          # Ancho total (Eje X)
BODY_L = 74.0          # Largo total (Eje Y)
BODY_H = 24.0          # Alto total (Eje Z)
CORNER_R = 12.0        # Radio esquinas verticales
WALL = 2.0             # Espesor de paredes
FLOOR = 3.5            # Espesor piso de la contera
ROOF = 2.0             # Espesor techo de la tapa
PARTING_Z = 17.0       # Altura contera inferior (Z=0 a 17), Tapa (Z=17 a 24)
LIP_H = 1.4            # Pestaña interior de encaje a presion
EDGE_FILLET = 1.2      # Bisel suave de aristas horizontales

# Pantalla OLED 0.96" (SSD1306)
SCREEN_C = (-5.0, 17.0)
SCREEN_W = 26.0        # Ventana visible
SCREEN_H = 14.5
SCREEN_R = 2.0         # Radio esquinas ventana

# Parlante y Rejilla Acústica
SPEAKER_C = (-9.0, -13.0)
SPEAKER_R = 11.0       # Rejilla Ø22 mm
SPK_D = 20.0           # Parlante planar Ø20 mm
SPK_H = 4.0

# Botones de Volumen (+X)
BTN_R = 5.5            # Radio tapas Ø11 mm
BTN_UP_C = (19.0, 4.0)
BTN_DOWN_C = (19.0, -14.0)

# Micrófono INMP441
MIC_HOLE = (24.0, 27.0, 1.2)   # x, y, diámetro

# Puertos y Switches
USB_C = (0.0, -BODY_L / 2, 5.5, 9.2, 3.6)       # x, y, z, ancho, alto
SWITCH_SLOT = (BODY_W / 2, 24.0, 12.0, 11.0, 4.8) # pared +X

# Dimensiones componentes BOM
XIAO = (21.0, 17.8, 4.0)
OLED_MOD = (27.5, 19.2, 2.5)
INMP = (14.0, 12.5, 3.5)
AMP = (25.0, 15.0, 3.0)
LIPO = (30.0, 20.0, 9.0)
GPS = (25.0, 25.0, 4.5)
SWITCH = (10.5, 4.0, 4.5)

# Paleta de Materiales
NAVY = (0.052, 0.067, 0.115, 1.0)
NAVY_DARK = (0.028, 0.036, 0.062, 1.0)
LCD_BLUE = (0.31, 0.76, 0.97, 1.0)      # Azul brillante emissive OLED
INK_BLACK = (0.015, 0.015, 0.020, 1.0)
GREEN_ACCENT = (0.12, 0.58, 0.25, 1.0)
RED_ACCENT = (0.75, 0.15, 0.15, 1.0)
STEEL_METAL = (0.78, 0.80, 0.82, 1.0)
GOLD_PADS = (0.85, 0.68, 0.25, 1.0)
PCB_BLACK = (0.03, 0.04, 0.035, 1.0)
CERAMIC_GPS = (0.78, 0.76, 0.72, 1.0)
BATTERY_FOIL = (0.82, 0.84, 0.86, 1.0)
PRINT_WHITE = (0.92, 0.93, 0.95, 1.0)


# ═══════════════════════════════════════════════════════════════════════
#  UTILIDADES BLENDER & ASSETS
# ═══════════════════════════════════════════════════════════════════════

def select_only(*objs):
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs:
        if o and o.name in bpy.data.objects:
            o.select_set(True)
    if objs and objs[-1] and objs[-1].name in bpy.data.objects:
        bpy.context.view_layer.objects.active = objs[-1]


def link(obj):
    if obj.name not in bpy.context.scene.collection.objects:
        bpy.context.scene.collection.objects.link(obj)
    return obj


def get_or_create_collection(name):
    col = bpy.data.collections.get(name)
    if not col:
        col = bpy.data.collections.new(name)
        bpy.context.scene.collection.children.link(col)
    return col


def move_to_collection(obj, col):
    for c in list(obj.users_collection):
        c.objects.unlink(obj)
    if obj.name in bpy.context.scene.collection.objects:
        bpy.context.scene.collection.objects.unlink(obj)
    col.objects.link(obj)


def mark_as_asset(id_data, description="", tags=None, author="Fifo Team"):
    """Marca un objeto o material como Asset en el Asset Browser de Blender."""
    try:
        id_data.asset_mark()
        if hasattr(id_data, "asset_data") and id_data.asset_data:
            id_data.asset_data.author = author
            if description:
                id_data.asset_data.description = description
            if tags:
                for t in tags:
                    try:
                        id_data.asset_data.tags.new(t)
                    except Exception:
                        pass
            try:
                id_data.asset_generate_preview()
            except Exception:
                pass
    except Exception as e:
        print(f"[WARN] No se pudo marcar asset {getattr(id_data, 'name', '')}: {e}")


def shade_smooth(obj, angle_deg=35.0):
    for p in obj.data.polygons:
        p.use_smooth = True
    if hasattr(obj.data, "use_auto_smooth"):
        try:
            obj.data.use_auto_smooth = True
            obj.data.auto_smooth_angle = math.radians(angle_deg)
        except Exception:
            pass


def create_material(name, color, rough=0.5, metal=0.0, emit_color=None,
                    emit_strength=0.0, transmission=0.0, coat=0.0, alpha=1.0):
    mat = bpy.data.materials.get(name)
    if mat is None:
        mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    if bsdf:
        def put_val(*names, value):
            for n in names:
                sock = bsdf.inputs.get(n)
                if sock is not None:
                    try:
                        sock.default_value = value
                        return True
                    except Exception:
                        continue
            return False

        put_val("Base Color", value=color)
        put_val("Roughness", value=rough)
        put_val("Metallic", value=metal)
        if emit_color:
            put_val("Emission Color", "Emit", value=emit_color)
            put_val("Emission Strength", value=emit_strength)
        put_val("Transmission Weight", "Transmission", value=transmission)
        put_val("Coat Weight", value=coat)
        put_val("Alpha", value=alpha)
        put_val("IOR", value=1.45)

    if alpha < 1.0 or transmission > 0.5:
        mat.blend_method = "BLEND"
        try:
            mat.show_transparent_back = False
        except Exception:
            pass

    mark_as_asset(mat, description=f"Material Fifo: {name}", tags=["fifo", "material", "pbr"])
    return mat


def assign_material(obj, mat):
    if hasattr(obj.data, "materials"):
        obj.data.materials.clear()
        obj.data.materials.append(mat)


# ═══════════════════════════════════════════════════════════════════════
#  PRIMITIVAS PARAMETRICAS EXACTAS (mm)
# ═══════════════════════════════════════════════════════════════════════

def make_box(name, w, l, h, loc=(0, 0, 0)):
    """Crea una caja rectangular con dimensiones exactas w × l × h centrada en loc."""
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=loc)
    obj = bpy.context.active_object
    obj.name = name
    obj.scale = (w, l, h)
    bpy.ops.object.transform_apply(scale=True)
    return obj


def make_cylinder(name, radius, depth, loc=(0, 0, 0), vertices=36, rot=(0, 0, 0)):
    """Crea un cilindro con radio, profundidad y rotación exactos."""
    bpy.ops.mesh.primitive_cylinder_add(
        radius=radius, depth=depth, location=loc, vertices=vertices, rotation=rot
    )
    obj = bpy.context.active_object
    obj.name = name
    return obj


def make_rounded_box(name, w, l, h, radius, segments=12, z_base=0.0):
    """Crea un prisma rectangular redondeado en sus 4 esquinas verticales."""
    bpy.ops.mesh.primitive_cube_add(size=1.0)
    obj = bpy.context.active_object
    obj.name = name
    obj.scale = (w, l, h)
    obj.location = (0, 0, z_base + h / 2.0)
    bpy.ops.object.transform_apply(scale=True, location=True)

    # Identificar esquinas verticales para bisel paramétrico
    vg = obj.vertex_groups.new(name="corners")
    ids = []
    tol = 1e-3
    for v in obj.data.vertices:
        if abs(abs(v.co.x) - w / 2.0) < tol and abs(abs(v.co.y) - l / 2.0) < tol:
            ids.append(v.index)
    vg.add(ids, 1.0, "REPLACE")

    bev = obj.modifiers.new("corner_bevel", "BEVEL")
    bev.width = min(radius, min(w, l) / 2.0 - 0.1)
    bev.segments = segments
    bev.limit_method = "VGROUP"
    bev.miter_outer = "MITER_ARC"

    select_only(obj)
    bpy.ops.object.modifier_apply(modifier=bev.name)
    shade_smooth(obj)
    return obj


def apply_boolean(target, cutter, op="DIFFERENCE", keep_cutter=False):
    """Aplica operación booleana exacta entre target y cutter."""
    if not target or not cutter:
        return target
    mod = target.modifiers.new("bool_op", "BOOLEAN")
    mod.operation = op
    mod.object = cutter
    mod.solver = "EXACT"
    mod.use_self = True

    select_only(target)
    try:
        bpy.ops.object.modifier_apply(modifier=mod.name)
    except Exception as e:
        print(f"[WARN] Error booleano {op} en {target.name}: {e}")
        try:
            mod.solver = "FAST"
            bpy.ops.object.modifier_apply(modifier=mod.name)
        except Exception:
            pass

    if not keep_cutter and cutter.name in bpy.data.objects:
        bpy.data.objects.remove(cutter, do_unlink=True)
    return target


def join_objects(objs, name):
    """Une varios objetos en uno solo."""
    valid = [o for o in objs if o and o.name in bpy.data.objects]
    if not valid:
        return None
    select_only(*valid)
    bpy.ops.object.join()
    res = bpy.context.active_object
    res.name = name
    return res


# ═══════════════════════════════════════════════════════════════════════
#  CONSTRUCCION DE LA CARCASA (01_Carcasa)
# ═══════════════════════════════════════════════════════════════════════

def build_case(mats):
    col = get_or_create_collection("01_Carcasa")

    # 1. Contera inferior (Z = 0.0 a 17.0 mm)
    bot_h = PARTING_Z
    case_bottom = make_rounded_box("Fifo_Case_Bottom", BODY_W, BODY_L, bot_h, CORNER_R, z_base=0.0)

    # Cavidad interior contera
    cav_w = BODY_W - 2.0 * WALL
    cav_l = BODY_L - 2.0 * WALL
    cav_h = bot_h - FLOOR + 2.0
    bot_cav = make_rounded_box("bot_cavity", cav_w, cav_l, cav_h, CORNER_R - WALL, z_base=FLOOR)
    apply_boolean(case_bottom, bot_cav)

    # Puerto USB-C en pared -Y (Z=5.5)
    usb_cut = make_box("cut_usbc", USB_C[3], 10.0, USB_C[4], (USB_C[0], -BODY_L / 2.0, USB_C[2]))
    apply_boolean(case_bottom, usb_cut)

    # Ranura para interruptor slide en pared +X
    sw_cut = make_box("cut_switch", 10.0, SWITCH_SLOT[3], SWITCH_SLOT[4],
                      (BODY_W / 2.0, SWITCH_SLOT[1], SWITCH_SLOT[2]))
    apply_boolean(case_bottom, sw_cut)

    # 4 Postes de fijación M2 para placa electrónica
    post_objs = []
    post_coords = ((-22.0, 26.0), (22.0, 26.0), (-22.0, -26.0), (22.0, -26.0))
    p_h = PARTING_Z - FLOOR - 2.0
    for i, (px, py) in enumerate(post_coords):
        p = make_cylinder(f"standoff_{i}", radius=2.2, depth=p_h,
                          loc=(px, py, FLOOR + p_h / 2.0), vertices=20)
        hole = make_cylinder(f"hole_{i}", radius=0.9, depth=6.0,
                             loc=(px, py, FLOOR + p_h - 2.0), vertices=16)
        apply_boolean(p, hole)
        post_objs.append(p)
    posts = join_objects(post_objs, "Fifo_Mount_Posts")
    apply_boolean(case_bottom, posts, op="UNION")

    assign_material(case_bottom, mats["shell"])
    move_to_collection(case_bottom, col)
    mark_as_asset(case_bottom, description="Carcasa inferior contera Fifo Robot con puertos y soportes M2",
                  tags=["fifo", "hardware", "enclosure", "bottom_shell"])

    # 2. Tapa superior (Z = 17.0 a 24.0 mm, Altura = 7.0 mm)
    top_h = BODY_H - PARTING_Z
    case_top = make_rounded_box("Fifo_Case_Top", BODY_W, BODY_L, top_h, CORNER_R, z_base=PARTING_Z)

    # Cavidad interior tapa (deja techo de 2.0 mm)
    top_cav_h = top_h - ROOF + 2.0
    top_cav = make_rounded_box("top_cavity", cav_w, cav_l, top_cav_h, CORNER_R - WALL,
                               z_base=PARTING_Z - 1.0)
    apply_boolean(case_top, top_cav)

    # Ventana pantalla OLED en (-5.0, 17.0)
    win_cut = make_rounded_box("cut_screen_win", SCREEN_W, SCREEN_H, 10.0, SCREEN_R,
                               z_base=BODY_H - 4.0)
    win_cut.location.x = SCREEN_C[0]
    win_cut.location.y = SCREEN_C[1]
    apply_boolean(case_top, win_cut)

    # Calado para parlante Ø22 mm en (-9.0, -13.0)
    spk_cut = make_cylinder("cut_spk", radius=SPEAKER_R, depth=10.0,
                            loc=(SPEAKER_C[0], SPEAKER_C[1], BODY_H))
    apply_boolean(case_top, spk_cut)

    # Calados para botones Vol+ y Vol-
    btn1_cut = make_cylinder("cut_btn1", radius=BTN_R + 0.3, depth=10.0,
                             loc=(BTN_UP_C[0], BTN_UP_C[1], BODY_H))
    btn2_cut = make_cylinder("cut_btn2", radius=BTN_R + 0.3, depth=10.0,
                             loc=(BTN_DOWN_C[0], BTN_DOWN_C[1], BODY_H))
    apply_boolean(case_top, btn1_cut)
    apply_boolean(case_top, btn2_cut)

    # Orificio acústico de micrófono INMP441
    mic_cut = make_cylinder("cut_mic", radius=MIC_HOLE[2] / 2.0, depth=10.0,
                            loc=(MIC_HOLE[0], MIC_HOLE[1], BODY_H), vertices=18)
    apply_boolean(case_top, mic_cut)

    assign_material(case_top, mats["shell"])
    move_to_collection(case_top, col)
    mark_as_asset(case_top, description="Tapa superior con ventana OLED, rejilla de audio y botones",
                  tags=["fifo", "hardware", "enclosure", "top_lid"])

    return case_bottom, case_top


# ═══════════════════════════════════════════════════════════════════════
#  PANTALLA OLED Y ROSTRO FIFO (03_Pantalla)
# ═══════════════════════════════════════════════════════════════════════

def build_display(mats):
    col = get_or_create_collection("03_Pantalla")

    # Lente acrílico protector (enrasado con la cara superior)
    lens = make_rounded_box("Fifo_Acrylic_Lens", SCREEN_W - 0.2, SCREEN_H - 0.2, 1.6,
                            SCREEN_R, z_base=BODY_H - 1.6)
    lens.location.x = SCREEN_C[0]
    lens.location.y = SCREEN_C[1]
    assign_material(lens, mats["lens"])
    move_to_collection(lens, col)
    mark_as_asset(lens, description="Lente acrílico transparente para display OLED", tags=["fifo", "lens"])

    # Panel OLED activo emisivo (azul)
    panel = make_box("Fifo_OLED_Screen", 22.0, 11.2, 0.4,
                     loc=(SCREEN_C[0], SCREEN_C[1], BODY_H - 1.8))
    assign_material(panel, mats["lcd"])
    move_to_collection(panel, col)

    # Gráficos del rostro de Fifo en la pantalla: Ojos y sonrisa
    # Ojo izquierdo
    eye_l = make_cylinder("Fifo_Eye_L", radius=1.6, depth=0.1,
                          loc=(SCREEN_C[0] - 4.5, SCREEN_C[1] + 1.2, BODY_H - 1.55), vertices=24)
    # Ojo derecho
    eye_r = make_cylinder("Fifo_Eye_R", radius=1.6, depth=0.1,
                          loc=(SCREEN_C[0] + 4.5, SCREEN_C[1] + 1.2, BODY_H - 1.55), vertices=24)
    # Sonrisa expresiva (arco)
    smile = make_cylinder("smile_disc", radius=3.2, depth=0.1,
                          loc=(SCREEN_C[0], SCREEN_C[1] - 1.2, BODY_H - 1.55), vertices=32)
    smile_cut = make_cylinder("smile_cut", radius=2.5, depth=0.2,
                              loc=(SCREEN_C[0], SCREEN_C[1] - 0.8, BODY_H - 1.55), vertices=32)
    apply_boolean(smile, smile_cut)
    smile_box = make_box("smile_box", 10.0, 5.0, 0.5,
                         loc=(SCREEN_C[0], SCREEN_C[1] + 1.2, BODY_H - 1.55))
    apply_boolean(smile, smile_box)
    smile.name = "Fifo_Mouth_Smile"

    face = join_objects([eye_l, eye_r, smile], "Fifo_Face_Expression")
    assign_material(face, mats["ink"])
    move_to_collection(face, col)
    mark_as_asset(face, description="Rostro expresivo animado de Fifo en pantalla OLED", tags=["fifo", "face", "ui"])

    # Placa PCB SSD1306 detrás de la ventana
    oled_pcb = make_box("Fifo_OLED_PCB", OLED_MOD[0], OLED_MOD[1], OLED_MOD[2],
                        loc=(SCREEN_C[0], SCREEN_C[1], BODY_H - 3.4))
    assign_material(oled_pcb, mats["pcb"])
    move_to_collection(oled_pcb, col)
    mark_as_asset(oled_pcb, description="Modulo OLED SSD1306 I2C 0.96 pulgadas", tags=["fifo", "oled", "hardware"])


# ═══════════════════════════════════════════════════════════════════════
#  REJILLA Y PARLANTE (02_Rejilla)
# ═══════════════════════════════════════════════════════════════════════

def build_speaker(mats):
    col = get_or_create_collection("02_Rejilla")

    # Bisel perimetral metálico
    rim = make_cylinder("Fifo_Grille_Rim", radius=SPEAKER_R + 1.2, depth=1.2,
                        loc=(SPEAKER_C[0], SPEAKER_C[1], BODY_H - 0.6), vertices=48)
    rim_hole = make_cylinder("rim_hole", radius=SPEAKER_R - 0.2, depth=2.0,
                             loc=(SPEAKER_C[0], SPEAKER_C[1], BODY_H - 0.6), vertices=48)
    apply_boolean(rim, rim_hole)
    assign_material(rim, mats["metal"])
    move_to_collection(rim, col)

    # Disco rejilla microperforada
    grille = make_cylinder("Fifo_Acoustic_Grille", radius=SPEAKER_R, depth=0.8,
                           loc=(SPEAKER_C[0], SPEAKER_C[1], BODY_H - 0.5), vertices=48)
    # Agujeros acústicos concéntricos
    hole_objs = []
    for ring_r, count in ((3.0, 6), (6.0, 12), (9.0, 18)):
        for i in range(count):
            ang = 2.0 * math.pi * i / count
            hx = SPEAKER_C[0] + ring_r * math.cos(ang)
            hy = SPEAKER_C[1] + ring_r * math.sin(ang)
            h = make_cylinder(f"ghole_{ring_r}_{i}", radius=0.6, depth=1.5,
                              loc=(hx, hy, BODY_H - 0.5), vertices=12)
            hole_objs.append(h)
    holes = join_objects(hole_objs, "grille_cut_holes")
    apply_boolean(grille, holes)
    assign_material(grille, mats["grille"])
    move_to_collection(grille, col)
    mark_as_asset(grille, description="Rejilla acustica metalica microperforada para parlante",
                  tags=["fifo", "audio", "grille"])

    # Parlante planar 8Ω 1W dentro de la cavidad
    spk_body = make_cylinder("Fifo_Speaker_Driver", radius=SPK_D / 2.0, depth=SPK_H,
                             loc=(SPEAKER_C[0], SPEAKER_C[1], BODY_H - 5.5), vertices=40)
    assign_material(spk_body, mats["metal"])
    move_to_collection(spk_body, col)
    mark_as_asset(spk_body, description="Parlante planar 8 Ohm 1W para kit Fifo", tags=["fifo", "speaker"])


# ═══════════════════════════════════════════════════════════════════════
#  BOTONES FISICOS (04_Botones)
# ═══════════════════════════════════════════════════════════════════════

def build_buttons(mats):
    col = get_or_create_collection("04_Botones")

    # Botón Vol+ (Verde) con relieve '+'
    b1_cap = make_cylinder("Fifo_Button_VolUp", radius=BTN_R, depth=3.5,
                           loc=(BTN_UP_C[0], BTN_UP_C[1], BODY_H - 0.2), vertices=32)
    plus1 = make_box("plus1", 5.0, 1.2, 0.4, loc=(BTN_UP_C[0], BTN_UP_C[1], BODY_H + 1.6))
    plus2 = make_box("plus2", 1.2, 5.0, 0.4, loc=(BTN_UP_C[0], BTN_UP_C[1], BODY_H + 1.6))
    b1 = join_objects([b1_cap, plus1, plus2], "Fifo_Button_VolUp")
    assign_material(b1, mats["btn_green"])
    move_to_collection(b1, col)
    mark_as_asset(b1, description="Boton de volumen superior (+) en color verde distintivo",
                  tags=["fifo", "button", "vol_up"])

    # Botón Vol- (Rojo) con relieve '-'
    b2_cap = make_cylinder("Fifo_Button_VolDown", radius=BTN_R, depth=3.5,
                           loc=(BTN_DOWN_C[0], BTN_DOWN_C[1], BODY_H - 0.2), vertices=32)
    minus = make_box("minus", 5.0, 1.2, 0.4, loc=(BTN_DOWN_C[0], BTN_DOWN_C[1], BODY_H + 1.6))
    b2 = join_objects([b2_cap, minus], "Fifo_Button_VolDown")
    assign_material(b2, mats["btn_red"])
    move_to_collection(b2, col)
    mark_as_asset(b2, description="Boton de volumen inferior (-) en color rojo distintivo",
                  tags=["fifo", "button", "vol_down"])

    # Pulsadores táctiles interiores 6x6 mm sobre PCB
    t1 = make_box("Tactile_Switch_Up", 6.0, 6.0, 5.0,
                  loc=(BTN_UP_C[0], BTN_UP_C[1], FLOOR + 8.0))
    t2 = make_box("Tactile_Switch_Down", 6.0, 6.0, 5.0,
                  loc=(BTN_DOWN_C[0], BTN_DOWN_C[1], FLOOR + 8.0))
    assign_material(t1, mats["metal"])
    assign_material(t2, mats["metal"])
    move_to_collection(t1, col)
    move_to_collection(t2, col)


# ═══════════════════════════════════════════════════════════════════════
#  ELECTRONICA INTERNA (05_Internos)
# ═══════════════════════════════════════════════════════════════════════

def build_internals(mats):
    col = get_or_create_collection("05_Internos")
    z_pcb = FLOOR + 6.0

    # 1. XIAO ESP32-S3 (21.0 × 17.8 × 4.0 mm)
    xiao_pcb = make_box("Fifo_XIAO_ESP32S3", XIAO[0], XIAO[1], 1.2,
                        loc=(-2.0, 6.0, z_pcb))
    # Blindaje RF metálico
    rf_shield = make_box("XIAO_RF_Shield", 14.0, 12.0, 2.2,
                         loc=(-2.0, 9.0, z_pcb + 1.5))
    xiao = join_objects([xiao_pcb, rf_shield], "Fifo_XIAO_ESP32S3")
    assign_material(xiao, mats["pcb"])
    move_to_collection(xiao, col)
    mark_as_asset(xiao, description="Microcontrolador Seeed Studio XIAO ESP32-S3 con antena y blindaje",
                  tags=["fifo", "xiao", "esp32", "mcu"])

    # 2. Conector USB-C nativo en la pared
    usbc = make_box("Fifo_USBC_Connector", 9.0, 5.5, 3.2,
                    loc=(USB_C[0], -BODY_L / 2.0 + 3.0, USB_C[2]))
    assign_material(usbc, mats["metal"])
    move_to_collection(usbc, col)
    mark_as_asset(usbc, description="Conector USB-C para carga y flasheo de firmware",
                  tags=["fifo", "usbc", "port"])

    # 3. Batería LiPo 902030 (30.0 × 20.0 × 9.0 mm, 500 mAh)
    lipo = make_rounded_box("Fifo_LiPo_Battery", LIPO[0], LIPO[1], LIPO[2], 2.0,
                            z_base=FLOOR + 0.5)
    lipo.location.x = 13.0
    lipo.location.y = -18.0
    assign_material(lipo, mats["battery"])
    move_to_collection(lipo, col)
    mark_as_asset(lipo, description="Bateria LiPo 902030 3.7V 500mAh con circuito de proteccion",
                  tags=["fifo", "battery", "lipo"])

    # 4. Módulo GNSS ATGM336H con antena cerámica
    gps_pcb = make_box("Fifo_GPS_ATGM336H", GPS[0], GPS[1], 1.5,
                       loc=(-14.0, 20.0, z_pcb))
    gps_patch = make_box("Fifo_GPS_Antenna", 16.0, 16.0, 2.5,
                         loc=(-14.0, 20.0, z_pcb + 2.0))
    assign_material(gps_patch, mats["ceramic"])
    gps = join_objects([gps_pcb, gps_patch], "Fifo_GPS_Module")
    assign_material(gps, mats["pcb"])
    move_to_collection(gps, col)
    mark_as_asset(gps, description="Modulo GPS + BeiDou ATGM336H con antena ceramica",
                  tags=["fifo", "gps", "gnss"])

    # 5. Amplificador MAX98357A I2S
    amp = make_box("Fifo_Amp_MAX98357A", AMP[0], AMP[1], AMP[2],
                   loc=(SPEAKER_C[0] + 1.0, SPEAKER_C[1] + 1.0, FLOOR + 2.0))
    assign_material(amp, mats["pcb"])
    move_to_collection(amp, col)
    mark_as_asset(amp, description="Amplificador de audio clase D MAX98357A por I2S",
                  tags=["fifo", "amplifier", "audio"])

    # 6. Micrófono INMP441 I2S
    inmp = make_box("Fifo_Mic_INMP441", INMP[0], INMP[1], INMP[2],
                    loc=(MIC_HOLE[0] - 2.0, MIC_HOLE[1] - 2.0, z_pcb))
    assign_material(inmp, mats["pcb"])
    move_to_collection(inmp, col)
    mark_as_asset(inmp, description="Microfono omnidireccional digital I2S INMP441",
                  tags=["fifo", "mic", "inmp441"])

    # 7. Interruptor de encendido SS12D07
    sw_body = make_box("Switch_Body", SWITCH[1], SWITCH[0], SWITCH[2],
                       loc=(BODY_W / 2.0 - 4.5, SWITCH_SLOT[1], SWITCH_SLOT[2]))
    sw_lever = make_box("Switch_Lever", 3.0, 2.0, 3.0,
                        loc=(BODY_W / 2.0 - 0.5, SWITCH_SLOT[1], SWITCH_SLOT[2]))
    assign_material(sw_lever, mats["print"])
    sw = join_objects([sw_body, sw_lever], "Fifo_Switch_SS12D07")
    assign_material(sw, mats["metal"])
    move_to_collection(sw, col)
    mark_as_asset(sw, description="Interruptor deslizante de alimentacion SS12D07", tags=["fifo", "switch"])


# ═══════════════════════════════════════════════════════════════════════
#  ESTUDIO, ILUMINACION Y CAMARA (00_Estudio)
# ═══════════════════════════════════════════════════════════════════════

def build_studio():
    col = get_or_create_collection("00_Estudio")

    # Ciclorama de fondo curvado
    backdrop = make_box("Studio_Backdrop", 800.0, 800.0, 1.0, loc=(0.0, 0.0, -0.5))
    mat_back = create_material("Mat_Studio_Backdrop", (0.025, 0.028, 0.038, 1.0), rough=0.9)
    assign_material(backdrop, mat_back)
    move_to_collection(backdrop, col)

    def add_light(name, loc, rot, size, energy, color=(1.0, 1.0, 1.0)):
        ld = bpy.data.lights.new(name, "AREA")
        ld.size = size
        ld.energy = energy
        ld.color = color
        lo = bpy.data.objects.new(name, ld)
        lo.location = loc
        lo.rotation_euler = rot
        link(lo)
        move_to_collection(lo, col)
        return lo

    # 3-Point Studio Lighting
    add_light("Light_Key", (140.0, -110.0, 190.0), (math.radians(35), 0, math.radians(50)),
              size=160.0, energy=450000.0, color=(1.0, 0.98, 0.95))
    add_light("Light_Fill", (-130.0, -80.0, 150.0), (math.radians(30), 0, math.radians(-50)),
              size=140.0, energy=180000.0, color=(0.94, 0.97, 1.0))
    add_light("Light_Rim", (-20.0, 130.0, 180.0), (math.radians(40), 0, math.radians(140)),
              size=110.0, energy=120000.0, color=(1.0, 1.0, 1.0))

    # Cámara cinematográfica (ángulo 3/4 beauty view)
    cam_data = bpy.data.cameras.new("RenderCam")
    cam_data.lens = 45.0
    cam_data.sensor_width = 36.0
    cam_obj = bpy.data.objects.new("RenderCam", cam_data)
    cam_obj.location = (55.0, -100.0, 68.0)
    cam_obj.rotation_euler = (math.radians(65), 0, math.radians(150))
    link(cam_obj)
    move_to_collection(cam_obj, col)
    bpy.context.scene.camera = cam_obj


# ═══════════════════════════════════════════════════════════════════════
#  RENDER & GUARDADO (.blend y .png)
# ═══════════════════════════════════════════════════════════════════════

def render_and_save():
    sc = bpy.context.scene
    # Ajuste de motor de render Cycles / EEVEE
    engines = [item.identifier for item in bpy.types.RenderSettings.bl_rna.properties['engine'].enum_items]
    if "CYCLES" in engines:
        sc.render.engine = "CYCLES"
        try:
            sc.cycles.samples = 128
        except Exception:
            pass
    elif "BLENDER_EEVEE" in engines:
        sc.render.engine = "BLENDER_EEVEE"
    elif "BLENDER_EEVEE_NEXT" in engines:
        sc.render.engine = "BLENDER_EEVEE_NEXT"

    sc.render.resolution_x = 1920
    sc.render.resolution_y = 1080
    sc.render.resolution_percentage = 100
    sc.render.image_settings.file_format = "PNG"
    sc.render.image_settings.color_mode = "RGBA"
    sc.render.image_settings.color_depth = "8"

    png_path = os.path.join(OUT_DIR, "render_enclosure.png")
    sc.render.filepath = png_path
    print(f"[FIFO] Generando render en {png_path} ...")
    try:
        bpy.ops.render.render(write_still=True)
        print(f"[FIFO] Render guardado exitosamente en {png_path}")
    except Exception as e:
        print(f"[WARN] Error durante render: {e}")

    # Guardar archivo .blend canónico del proyecto
    blend_path = os.path.join(OUT_DIR, "enclosure.blend")
    print(f"[FIFO] Guardando archivo .blend en {blend_path} ...")
    bpy.ops.wm.save_as_mainfile(filepath=blend_path)
    print(f"[FIFO] Archivo {blend_path} guardado con exito.")


# ═══════════════════════════════════════════════════════════════════════
#  PIPELINE PRINCIPAL
# ═══════════════════════════════════════════════════════════════════════

def reset_scene():
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    for col in list(bpy.data.collections):
        bpy.data.collections.remove(col)


def main():
    print("[FIFO] Iniciando construccion parametrica de Fifo Robot Enclosure...")
    reset_scene()

    # Materiales PBR
    mats = {
        "shell": create_material("Mat_Fifo_Navy", NAVY, rough=0.38, coat=0.3),
        "bezel": create_material("Mat_Fifo_NavyDark", NAVY_DARK, rough=0.55),
        "lens": create_material("Mat_Fifo_Acrylic", (0.95, 0.97, 1.0, 1.0), rough=0.02, transmission=0.95),
        "lcd": create_material("Mat_Fifo_OLED_Blue", LCD_BLUE, rough=0.25, emit_color=LCD_BLUE, emit_strength=2.2),
        "ink": create_material("Mat_Fifo_InkBlack", INK_BLACK, rough=0.7),
        "btn_green": create_material("Mat_Fifo_Accent_Green", GREEN_ACCENT, rough=0.28, coat=0.35),
        "btn_red": create_material("Mat_Fifo_Accent_Red", RED_ACCENT, rough=0.28, coat=0.35),
        "metal": create_material("Mat_Fifo_Steel", STEEL_METAL, rough=0.28, metal=1.0),
        "grille": create_material("Mat_Fifo_Grille", STEEL_METAL, rough=0.35, metal=0.9),
        "pcb": create_material("Mat_Fifo_PCB", PCB_BLACK, rough=0.55),
        "battery": create_material("Mat_Fifo_BatteryFoil", BATTERY_FOIL, rough=0.35, metal=0.7),
        "ceramic": create_material("Mat_Fifo_Ceramic", CERAMIC_GPS, rough=0.65),
        "print": create_material("Mat_Fifo_PrintWhite", PRINT_WHITE, rough=0.6),
    }

    # Construcción de colecciones y ensamblaje
    build_studio()
    build_case(mats)
    build_display(mats)
    build_speaker(mats)
    build_buttons(mats)
    build_internals(mats)

    render_and_save()
    print("[FIFO] Construccion y guardado completados con exito.")


if __name__ == "__main__":
    main()
