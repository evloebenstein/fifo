"""Fifo compacto (concepto redondeado) - construccion parametrica.

    blender --background --factory-startup --python hardware/enclosure/build_fifo_round.py

Objetivo: conservar el orden del STL del usuario (pantalla arriba, parlante
abajo-izquierda, dos botones a la derecha) pero con una carcasa tipo pastilla:
esquinas R9, cantos de 1,6 y 3,4 mm y una sola junta horizontal.  Medidas 38 x
58 x 21.5 mm, mas compacta que el modelo anterior (40 x 60 x 26).

Este archivo es la fuente de verdad; genera fifo_round.blend, renders y STLs.
"""

import math
import os
import sys

import bmesh
import bpy
from mathutils import Vector

OUT_DIR = os.path.dirname(os.path.abspath(__file__))
if OUT_DIR not in sys.path:
    sys.path.insert(0, OUT_DIR)

import build_fifo as base  # helpers, materiales y estudio reutilizados

# ---------------------------------------------------------------- dimensiones
W = 38.0
L = 58.0
H = 21.5
R_PLAN = 9.0
WALL = 1.8
BODY_H = 15.5
LID_H = H - BODY_H
FLOOR = 1.8
# La placa del XIAO va elevada 0,5 mm sobre 4 postes para que las 4 patas del
# USB-C y los filamentos de soldadura no toquen el piso; el hueco USB-C sube
# con el conector.  Los cables de la cara inferior (BAT+ / BAT-) no caben en
# esos 0,5 mm: van por un pocket de 0,8 mm en el piso (POCKET_*) que deja
# 1,30 mm libres bajo el PCB y sale por el canto de la placa.
XIAO_RAISE = 0.5
XIAO_BOTTOM = FLOOR + XIAO_RAISE
R_IN = R_PLAN - WALL

# junta horizontal: ranura en el cuerpo + lengueta en la tapa
SEAM_OFF = 0.60      # retranqueo de la ranura desde la cara exterior
SEAM_W = 0.65        # ancho de la ranura
TONGUE_W = 0.35      # ancho de la lengueta -> 0.15 mm de juego por lado
SEAM_GAP = 0.15
SEAM_DEPTH = 1.15    # profundidad de la ranura
SEAM_BOT_GAP = 0.10  # holgura vertical en el fondo de la ranura

# cara / features
SCR_C = (0.0, 14.0)
SCR_LENS_W, SCR_LENS_L, SCR_LENS_R = 25.6, 13.6, 3.5
SCR_WIN_W, SCR_WIN_L, SCR_WIN_R = 24.4, 12.4, 3.4
SPK_C = (-5.8, -4.8)
SPK_RING_R = 9.7
SPK_GRILLE_R = 8.6
SPK_DRIVER_W, SPK_DRIVER_L, SPK_DRIVER_H = 15.0, 8.0, 2.9
# El driver entra vertical en el hueco Ø17,5 y no se sostiene solo.  La
# retencion es una bandeja APARTE que se desliza desde el frente (-Y) por
# debajo del driver: el larguero izquierdo apoya en una repisa pegada a la
# pared y el derecho en el pie de un carril que cuelga del techo (los dos a
# 16,65); el tope trasero la frena y un resalte de Ø0,44 en cada pie entra en
# un bolsillo de 0,26 y la retiene en las dos direcciones.  La bandeja es un
# marco: dos largueros que apoyan en los pies y dos travesanos bajo los cantos
# del driver; el centro va abierto y los cables corren por debajo.  Su cara
# superior queda a 17,15, 0,05 por debajo del driver: entra sin rozar y la
# espuma de 0,5 mm (0,35 de hueco) es la que lo empuja contra la bandeja.
# El carril y el larguero derechos solo llegan hasta delante del bus de cables
# (y=-3,60): el bus pasa a 16,55 y cualquier carril que bajara a 16,20 por esa
# zona lo atravesaria.
SPK_TRAY_Z0, SPK_TRAY_Z1 = 16.65, 17.15
SPK_TINE_Z0 = 16.90                # los travesanos pasan sobre los cables
SPK_TRAY_Y0, SPK_TRAY_Y1 = -9.70, 0.80
SPK_BAR_L = (-17.10, -16.60)       # contra la pared: la guia es el muro
SPK_BAR_R = (1.80, 2.50)
SPK_TINE_X = (-17.00, 2.20)
SPK_TINE_FRONT = (-9.70, -8.40)
SPK_TINE_BACK = (-1.50, 0.50)
SPK_WIRE_GAP = (-8.60, -4.70)      # hueco del travesano delantero
SPK_LEDGE = (-17.20, -16.70)       # repisa del carril izquierdo
SPK_RAIL_R_WEB = (2.60, 3.40)
SPK_RAIL_R_FOOT = (2.00, 2.60)
SPK_RAIL_Y = (-10.60, 0.90)
SPK_RAIL_Z0 = 16.20
SPK_WALL_X = WALL - W / 2          # -17,20: cara interior de la pared
SPK_BUMP_D, SPK_BUMP_Y = 0.44, (-8.10, -7.40)
SPK_POCKET_Y, SPK_POCKET_H = (-8.15, -7.35), 0.26
SPK_STOP_Y = (0.90, 1.50)          # tope trasero, bajo el OLED
SPK_STOP_X = (-17.20, -13.00)      # solo el tramo izquierdo: no pisa cables
SPK_RAIL_R_Y = (-10.60, -3.60)     # el carril derecho no llega al bus (y≈-2)
SPK_BAR_R_Y = (-9.70, -3.60)
BTN_C = ((11.2, -7.0), (11.2, -19.0))
BTN_BORE_R = 4.70                 # pozo pasante Ø9,4: guia la pestaña
BTN_CAP_R = 4.10                  # Ø8,2 visible
BTN_FLANGE_R = 4.60               # pestaña Ø9,2 atrapada bajo el aro
# El táctil 6x6x5 no tiene un tetón fino: su pulsador es un cilindro liso de
# Ø3,5 que sobresale 0,50 mm.  No hay rebaje donde enganchar un clip, asi que
# el boton lo ENVUELVE con una copa guia: holgura radial 0,10 (conexion por
# forma, sin friccion ni precarga), techo interior apoyado en la cara del
# pulsador y borde a 0,30 mm del cuerpo para no topar antes que el recorrido.
TACT_ACT_R = 1.75                 # pulsador Ø3,5
BTN_CUP_IR = TACT_ACT_R + 0.10    # interior de la copa Ø3,7
BTN_CUP_WALL = 0.50
BTN_CUP_Z0 = 20.00                # borde de la copa, 0,30 sobre el cuerpo
BTN_ACT_Z0, BTN_STEM_Z0, BTN_STEM_Z1 = 19.70, 20.20, 20.70
BTN_BEZEL_R, BTN_BEZEL_IN_R = 5.10, 4.20    # aro Ø10,2 / Ø8,4
BTN_FLANGE_Z0, BTN_FLANGE_Z1 = 20.55, 21.40
BTN_BEZEL_Z0, BTN_BEZEL_Z1 = 21.40, 22.00   # labio del aro: apoya en la pestaña
BTN_SKIRT_Z0 = 20.90                        # faldon del aro dentro del rebaje
BTN_RECESS_Z0 = BTN_SKIRT_Z0 + 0.05         # fondo: 0,05 de apriete del faldon
BTN_RECESS_R = 5.20                         # rebaje Ø10,4, 0,55 bajo la cara
BTN_SEAT_W, BTN_SEAT_POCKET, BTN_HAT_W = 8.0, 6.2, 10.4
MIC_C = (0.0, -20.0)
# USB-C de 16 pines del XIAO: carcasa de 8,94 x 7,35 x 3,26 (R1,3) y boca de
# 8,34 x 2,56.  Va soldado sobre la cara superior del PCB, con la boca a
# 0,10 mm de la pared interior.
#
# El hueco se mide sobre la CARCASA, no sobre la boca: es lo que se ve y lo que
# no puede quedar tapado.  9,40 x 3,70 deja 0,23 mm por lado (el calculo de
# 0,25 mm por lado da 9,46 x 3,67) y es lo mas cerrado que aguanta FDM, donde
# los agujeros salen 0,1-0,2 mm pequeños.  Con 9,00 x 3,40 quedarian 0,03 mm
# por lado y el conector rozaria el canto.  El radio es 1,30, el mismo del
# receptaculo, para que la holgura sea constante en las esquinas.
USB_C_W, USB_C_H, USB_C_D = 8.94, 3.26, 7.35
USB_OPEN_W, USB_OPEN_H = 8.34, 2.56
USB_Y0 = -27.10                     # cara frontal del conector
USB_Z0 = XIAO_BOTTOM + 1.60         # apoyado en la cara superior del PCB
USB_ZC = USB_Z0 + USB_C_H / 2       # centro de la boca (5,53)
USB_HOLE_W, USB_HOLE_H = 9.40, 3.70
USB_BEZEL_W, USB_BEZEL_H = 10.60, 4.90
USB_BEZEL_IN_W, USB_BEZEL_IN_H = 9.40, 3.70
# cable de prueba: casquillo 8,25 x 2,40 x 6,50 dentro del puerto, alivio de
# 9,00 x 3,30 y sobre-molde 12,20 x 6,40.  El alivio empieza en la culata del
# casquillo (Y -33,6), o sea 4,0 mm por fuera del bisel: por el hueco solo
# pasa metal, como en un cable real.
PLUG_W, PLUG_H, PLUG_L = 8.25, 2.40, 6.50
PLUG_NECK_W, PLUG_NECK_H, PLUG_NECK_L = 9.00, 3.30, 1.10
PLUG_BODY_W, PLUG_BODY_H, PLUG_BODY_L = 12.20, 6.40, 12.00
PLUG_CABLE_D, PLUG_CABLE_L = 4.20, 7.00

# internos (medidas reales de los modulos, ver README)
XIAO_C = (0.0, -16.0)
XIAO_W, XIAO_L = 17.8, 21.0
XIAO_PCB_H = 1.6
XIAO_TOP = XIAO_BOTTOM + XIAO_PCB_H       # 3,90
XIAO_ANT_W, XIAO_ANT_L = 10.0, 4.0   # antena impresa del ESP32-S3

# Pocket del piso bajo la placa: 0,8 mm de rebaje que dejan 1,30 mm libres
# entre el piso y la cara inferior del PCB, para los hilos soldados a BAT+ y
# BAT-.  Sobresale 1,00 mm del contorno de la placa (salvo al frente, donde
# llega a la pared) para que el hilo pueda bajar desde el piso hasta el fondo
# del pocket antes de meterse debajo.  Deja 1,00 mm de piso bajo el rebaje.
POCKET_DEPTH = 0.80
POCKET_Z0 = FLOOR - POCKET_DEPTH                     # 1,00
POCKET_MARGIN = 1.00
POCKET_X = XIAO_W / 2 + POCKET_MARGIN                # 9,90
POCKET_Y0 = -L / 2 + WALL                            # -27,20, pegado a la pared
POCKET_Y1 = XIAO_C[1] + XIAO_L / 2 + POCKET_MARGIN   # -4,50
POCKET_R = 2.00
XIAO_GAP = XIAO_BOTTOM - POCKET_Z0                   # 1,30
# La celda 902030 va girada: 20 en X y 30 en Y.  Con 30 en X llegaba a X=15 y
# ocupaba todo el hueco bajo los dos botones; girada se queda en X -13,5..6,5 y
# deja libre X 6,5..14,2 para que los tactiles de 5,0 mm cuelguen hasta 15,2 sin
# tocarla.  El exterior no cambia.
BAT_C = (-3.5, -12.0)
BAT_W, BAT_L, BAT_H, BAT_R = 20.0, 30.0, 8.85, 3.5
BAT_Z0 = 7.25   # deja 0,09 mm sobre el conector USB-C (7,16) y 0,10 bajo la lata del micro (16,20)
# postes que fijan la altura de la celda: 3 puntos (no 4) para que no cojee y
# ninguno caiga sobre el XIAO, que ocupa X -8,9..8,9 / Y -26,5..-5,5.  El
# trasero se va a Y=-25,5 porque el mazo sube pegado a X=-11,55 y su curva
# llega a Y=-22,9; los dos delanteros se apartan a Y=0,6 / 1,5 del hilo de D7
# y del RX del GPS.
BAT_POSTS = ((-11.7, -25.5), (-11.7, 0.6), (5.3, 1.5))
# ATGM336H de breakout: el modulo que se compra hecho, no el chip suelto.  PCB
# de 16 x 13,5 con el ATGM336H-5N soldado por una cara (lata de 10,1 x 9,7 x
# 2,4) y los SMD + pila de respaldo + U.FL por la otra.  5,3 mm de alto sin
# pines y 5 pads a 2,54 en el borde delantero: VCC, GND, TX, RX, PPS.
GPS_C = (0.0, 13.0)
GPS_W, GPS_L, GPS_H = 16.0, 13.5, 5.3
GPS_PCB_H = 1.0
GPS_CAN_W, GPS_CAN_L, GPS_CAN_H = 10.1, 9.7, 2.4
GPS_PCB_Z0 = FLOOR + GPS_CAN_H            # 4,20: la lata apoya en el piso
GPS_PAD_Z = GPS_PCB_Z0 + GPS_PCB_H        # 5,20: cara superior del PCB
# Antena ceramica de 20 x 6 con pigtail U.FL que viene con el modulo.  Va en el
# piso trasero, plana hacia arriba y libre de metal: es la unica antena GPS.
ANT_C = (0.0, 23.5)
ANT_W, ANT_L, ANT_H = 20.0, 6.0, 4.2
SW_C = (-13.9, 6.0)
SW_BODY_W = 8.0                             # ancho del SS12D07 en Y
SW_SIDE_MARGIN = 2.0                        # de cada lado del cuerpo al hueco
SW_SLOT_W = SW_BODY_W - 2 * SW_SIDE_MARGIN  # hueco de 4,0 en Y
SW_SLOT_H = 3.4
SW_LEVER_W, SW_LEVER_H = 1.8, 2.8
SW_LEVER_OUT = 3.0                          # palanca fuera de la cara del cuerpo
SW_TRAVEL = SW_SLOT_W - SW_LEVER_W          # recorrido util de la palanca
SW_CAP_W, SW_CAP_H, SW_CAP_T = 4.8, 3.4, 0.4
SW_CAP_RECESS = 0.05                        # paddle bajo la cara exterior
SW_TRACK_D = 1.0                            # pista rehundida en la pared
SW_TRACK_GAP = 0.2
SW_TRACK_W = SW_CAP_W + SW_TRAVEL + 2 * SW_TRACK_GAP
SW_TRACK_H = SW_CAP_H + 2 * SW_TRACK_GAP
# MAX98357A real (19,4 x 17,8) sin el borne verde que el usuario desuelda:
# 1,6 de PCB + ~1,0 de componentes. Va pegado bajo el OLED y girado 90
# grados: el header de 7 pines queda en el borde derecho (X=8,4) y los dos
# pads del altavoz en el izquierdo (X=-9,4), mirando al driver.  Se corre a
# Y=14,0 para que su canto delantero (Y=4,3) deje 1,3 mm sobre la celda (Y=3).
AMP_C = (-0.5, 14.0)
AMP_W, AMP_L, AMP_H = 17.8, 19.4, 2.6
AMP_Z0 = 14.70            # techo 17,30 a 0,10 del PCB del OLED (17,40)
MIC_D, MIC_H = 14.0, 2.6   # modulo redondo INMP441
# El INMP441 es bottom-port: el puerto acustico esta en la cara de soldadura.
# El modulo se monta con esa cara hacia la ventana (pads arriba) y la lata
# hacia el cuerpo.  La cara superior del PCB queda a 18,30 y la lata cuelga
# 1,80 mm por debajo; una bota acustica impresa lleva el sonido al taladro.
MIC_PCB_H = 0.80
MIC_CAN_H = 1.80
MIC_TOP = 18.80
MIC_PCB_Z0 = MIC_TOP - MIC_PCB_H          # 18,00
MIC_CAN_Z0 = MIC_PCB_Z0 - MIC_CAN_H      # 16,20
# Retencion mecanica del INMP441.  La PCB baja desde arriba y apoya en dos
# repisas impresas en la tapa; cuatro postes las cuelgan del techo y dos guias
# centran el disco.  La bota acustica se acorta a 19,15: quedan 0,35 mm para
# una arandela de espuma de 0,5 mm.  El sello no depende de pegamento y el
# puerto queda alineado con el taladro de la cara.
MIC_SHELF_X0, MIC_SHELF_X1 = 6.40, 7.20
MIC_SHELF_Y0, MIC_SHELF_Y1 = -26.80, -13.20
MIC_SHELF_Z0, MIC_SHELF_Z1 = 17.70, 18.00
MIC_POST_X0, MIC_POST_X1 = 6.40, 7.10
MIC_POST_Y0, MIC_POST_Y1 = -13.90, -13.20   # poste trasero (detras del mazo)
MIC_POST_F0, MIC_POST_F1 = -26.80, -26.10  # poste delantero (junto a la pared)
MIC_GUIDE_X0, MIC_GUIDE_X1 = 7.20, 7.60
MIC_GUIDE_Y = 4.00
MIC_BOOT_Z0 = 19.15
MIC_FOAM_GAP = 0.35
# OLED 0,96" SSD1306 real: PCB de 27 x 27 x 1,4 + cristal de 1,2.  El modulo
# va pegado al techo de la tapa (cristal contra la ventana) y su header de 4
# pines cuelga hacia el cuerpo en el borde delantero.  Los pines se recortan a
# 0,8 mm: la celda girada llega a Y=3 y su techo (16,10) pasa justo bajo la
# punta (16,60); con los 2,5 mm de serie los pines se hundirian en la bateria.
OLED_C = (0.0, 13.6)
OLED_W, OLED_L, OLED_R = 27.0, 27.0, 2.2
OLED_PCB_Z0, OLED_PCB_H, OLED_GLASS_H = H - 4.1, 1.4, 1.2   # 17,4 / 1,4 / 1,2
OLED_PIN_L = 0.8
OLED_PIN_TIP = OLED_PCB_Z0 - OLED_PIN_L                     # 16,60

# ------------------------------------------------------- pines reales (2026)
# XIAO ESP32-S3 visto desde arriba con el USB al frente (-Y).  Borde derecho
# (X=+8,9) de cerca a lejos del USB: D0..D6.  Borde izquierdo: 5V, GND, 3V3,
# D10, D9, D8, D7.  Paso 2,54 mm, 7 pines por lado.  Los pads son castellados
# en el canto inferior de la placa, no en la cara superior.
XIAO_PAD_Z = XIAO_BOTTOM + 0.30        # centro del hilo sobre el canto inferior
XIAO_PAD_PITCH = 2.54
XIAO_PAD_Y0 = -24.0                    # pin mas proximo al USB
XIAO_RIGHT = ("D0", "D1", "D2", "D3", "D4", "D5", "D6")
XIAO_LEFT = ("5V", "GND", "3V3", "D10", "D9", "D8", "D7")

# MAX98357A: header de 7 pines en el borde largo (girado: X=8,4), con el
# borne del altavoz en el borde opuesto.  Orden con el borne arriba:
AMP_PINS = ("LRC", "BCLK", "DIN", "GAIN", "SD", "GND", "Vin")
# OLED 0,96": header GND/VDD/SCK/SDA en el borde delantero.  Al montar el
# cristal contra la ventana el orden se invierte en X: SDA en -X, GND en +X.
OLED_PINS = ("SDA", "SCK", "VDD", "GND")
# INMP441 redondo: dos filas de 3 pads pasantes en la cara de soldadura.
# Fila superior (lado +Y): SCK, WS, L/R.  Fila inferior: SD, VDD, GND.
MIC_PINS = ("SCK", "WS", "L/R", "SD", "VDD", "GND")
MIC_PAD_POS = {
    "SCK": (-2.54, +3.75), "WS": (0.0, +3.75), "L/R": (+2.54, +3.75),
    "SD": (-2.54, -3.75), "VDD": (0.0, -3.75), "GND": (+2.54, -3.75),
}
# ATGM336H: 5 pads a 2,54 en el borde delantero, de -X a +X.
GPS_PINS = ("VCC", "GND", "TX", "RX", "PPS")
GPS_PIN_PITCH = 2.54


# cables de silicona AWG30 (0,6 mm con aislamiento -> radio 0,3)
WIRE_R = 0.30
# La junta no lleva puerta: la cavidad del cuerpo (hasta z=16,0) y la de la
# tapa (desde z=15,3) se solapan, asi que el mazo cruza el plano de la junta
# por el interior sin taladrar la pared.
RISE = (15.55, -3.6)      # columna del mazo XIAO -> tapa
DP = (14.9, -3.0, 16.6)   # punto de reparto de alimentacion en la tapa

# --------------------------------------------------- coordenadas de los pads
def xiao_pad(side, pin):
    """Pad de un pin del XIAO; side=+1 borde derecho, -1 izquierdo."""
    pins = XIAO_RIGHT if side > 0 else XIAO_LEFT
    k = pins.index(pin)
    return (side * 8.9, XIAO_PAD_Y0 + k * XIAO_PAD_PITCH, XIAO_PAD_Z)


def amp_pad(pin):
    k = AMP_PINS.index(pin)
    return (AMP_C[0] + AMP_W / 2, AMP_C[1] + (k - 3) * 2.54, AMP_Z0 - 0.03)


AMP_SPK_PADS = ((-9.4, AMP_C[1] - 1.2, AMP_Z0 - 0.03),
                (-9.4, AMP_C[1] + 1.2, AMP_Z0 - 0.03))


def oled_pad(pin):
    """Punta del pin del OLED: el mazo se suelda ahi, bajo el PCB."""
    k = OLED_PINS.index(pin)
    return (-3.81 + k * 2.54, 0.95, OLED_PIN_TIP)


def mic_pad(pin):
    """Centro del pad pasante en la cara de soldadura del INMP441."""
    dx, dy = MIC_PAD_POS[pin]
    return (MIC_C[0] + dx, MIC_C[1] + dy, MIC_TOP + 0.08)


def gps_pad(pin):
    k = GPS_PINS.index(pin)
    return (GPS_C[0] + (k - 2) * GPS_PIN_PITCH,
            GPS_C[1] - GPS_L / 2 + 0.60, GPS_PAD_Z)


def tact_pad(i, term):
    cx, cy = BTN_C[i]
    # El tactil de 6x6x5 tiene las patas al pie del cuerpo (15,20): el pad de
    # soldadura queda a 15,16, 0,04 por debajo del canto.
    return (cx + (-3.25 if term == "A" else 3.25), cy, 15.16)


def xiao_bat_pad(name):
    """Centro del pad BAT+ / BAT- de la cara inferior del XIAO."""
    by = 0.03 if name == "pos" else -1.97
    return (4.57, XIAO_C[1] + by, XIAO_BOTTOM - 0.06)


def xiao_bat_tip(name):
    """Centro del hilo soldado al pad BAT: su cara toca el pad (2,24)."""
    px, py, pz = xiao_bat_pad(name)
    return (px, py, pz - WIRE_R)


def pocket_dist(x, y):
    """Distancia con signo al borde del pocket (positiva dentro)."""
    dx = POCKET_X - abs(x)
    dy = min(y - POCKET_Y0, POCKET_Y1 - y)
    if dx >= 0.0 and dy >= 0.0:
        return min(dx, dy)
    return -math.hypot(max(-dx, 0.0), max(-dy, 0.0))


SW_PADS = ((-12.2, 4.0, 2.6), (-12.2, 6.0, 2.6))
# Patas de la celda en su cara +X (X=6,5), a la altura del cuerpo: dos hilos
# por pata (uno a potencia y otro a los pads BAT del XIAO).
BAT_PADS = ((6.52, -5.60, 13.5), (6.52, -7.40, 13.5))
DRV_PADS = ((-8.0, -8.86, 17.55), (-6.2, -8.86, 17.55))


# explosivo / renders
EXPLODE_DZ = 40.0

COLS = {}
PARTS = {"body": [], "lid": [], "face": [], "internals": [], "studio": []}
base.COLS = COLS
base.PARTS = PARTS


def collection(name):
    if name in COLS:
        return COLS[name]
    col = bpy.data.collections.new(name)
    bpy.context.scene.collection.children.link(col)
    COLS[name] = col
    return col


def add(name, mesh, col, group=None):
    return base.add(name, mesh, col, group)


def prism(name, w, l, r, z0, z1, col, group=None, **kw):
    return base.prism(name, w, l, r, z0, z1, col, group, **kw)


def disc(name, radius, z0, z1, cx, cy, col, group=None, **kw):
    return base.disc(name, radius, z0, z1, cx, cy, col, group, **kw)


def block(name, w, l, h, cx, cy, z0, col, group=None):
    return base.block(name, w, l, h, cx, cy, z0, col, group)


def ring(name, ro, ri, z0, z1, col, group=None, seg=48, **kw):
    outer = disc(name + "_o", ro, z0, z1, 0, 0, col, group, verts=seg, **kw)
    inner = disc(name + "_i", ri, z0 - 0.5, z1 + 0.5, 0, 0, col, verts=seg)
    base.cut(outer, inner, "ring")
    return outer


def rounded_ring(name, w, l, r, thick, z0, z1, col, group=None):
    outer = prism(name + "_o", w, l, r, z0, z1, col, group, seg=32)
    inner = prism(name + "_i", w - 2 * thick, l - 2 * thick, r - thick,
                  z0 - 0.5, z1 + 0.5, col, seg=32)
    base.cut(outer, inner, "ring")
    return outer


def cut(target, cutter, name="cut"):
    return base.cut(target, cutter, name)


def assign(ob, mat):
    base.assign(ob, mat)


def mount(ob, where):
    ob["mount"] = where
    return ob


def wire(name, points, mat, radius=WIRE_R, where="body", targets=()):
    """Cable rigido de silicona: polilinea con grosor y mata de puntos."""
    cu = bpy.data.curves.new(name, "CURVE")
    cu.dimensions = "3D"
    cu.bevel_depth = radius
    cu.bevel_resolution = 2
    cu.use_fill_caps = True
    sp = cu.splines.new("POLY")
    sp.points.add(len(points) - 1)
    for p, (x, y, z) in zip(sp.points, points):
        p.co = (x, y, z, 1.0)
    ob = bpy.data.objects.new(name, cu)
    collection("06_Internos").objects.link(ob)
    assign(ob, mat)
    mount(ob, where)
    ob["flex"] = True
    ob["targets"] = list(targets)
    ob["path"] = [tuple(p) for p in points]
    PARTS["internals"].append(ob)
    return ob


def catmull(points, subdiv=5):
    """Suaviza una polilinea con Catmull-Rom para que el cable no quiebre."""
    pts = [Vector(p) for p in points]
    if len(pts) < 3:
        return pts
    ext = [pts[0]] + pts + [pts[-1]]
    out = [pts[0]]
    for i in range(1, len(ext) - 2):
        p0, p1, p2, p3 = ext[i - 1], ext[i], ext[i + 1], ext[i + 2]
        for k in range(1, subdiv + 1):
            t = k / subdiv
            t2, t3 = t * t, t * t * t
            out.append(0.5 * (2 * p1 + (-p0 + p2) * t
                              + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                              + (-p0 + 3 * p1 - 3 * p2 + p3) * t3))
    return out


def frames(points):
    """Seccion estable: referencia vertical del mundo, sin giros bruscos."""
    n = len(points)
    tan = []
    for i in range(n):
        if i == 0:
            t = points[1] - points[0]
        elif i == n - 1:
            t = points[-1] - points[-2]
        else:
            t = points[i + 1] - points[i - 1]
        tan.append(t.normalized())
    out = []
    side = Vector((0.0, -1.0, 0.0))
    for t in tan:
        s = t.cross(Vector((0.0, 0.0, 1.0)))
        if s.length > 1e-4:
            side = s.normalized()
        out.append((side, side.cross(t)))
    return out


def loom(name, waypoints, slots, mats, where="body", targets=(),
         radius=WIRE_R, subdiv=5):
    """Mazo de cables: misma ruta y un desplazamiento por hilo."""
    pts = catmull(waypoints, subdiv)
    fr = frames(pts)
    if not isinstance(mats, (list, tuple)):
        mats = [mats]
    wires = []
    for i, (ou, ov) in enumerate(slots):
        path = [p + side * ou + up * ov
                for p, (side, up) in zip(pts, fr)]
        wires.append(wire(f"{name}_{i}", [tuple(v) for v in path],
                          mats[i % len(mats)], radius, where, targets))
    return wires


def slot_xz(name, w, h, y0, y1, cx, cz, col, group=None, radius=1.6):
    """Ranura redondeada en un plano XZ, extruida sobre Y."""
    ob = prism(name, w, h, radius, -0.5 * (y1 - y0), 0.5 * (y1 - y0),
               col, group, seg=24)
    ob.rotation_euler.x = math.radians(90)
    ob.location = (cx, 0.5 * (y0 + y1), cz)
    base.select_only(ob)
    bpy.ops.object.transform_apply(location=True, rotation=True)
    return ob


def add_materials(mats):
    mats["btn_green"] = base.principled(
        "Boton_Verde", (0.02, 0.19, 0.08, 1.0), rough=0.34, coat=0.25)
    mats["btn_red"] = base.principled(
        "Boton_Rojo", (0.26, 0.025, 0.02, 1.0), rough=0.34, coat=0.25)
    mats["print_green"] = base.principled(
        "Serigrafia_Verde", (0.10, 0.55, 0.18, 1.0), rough=0.5,
        emit=(0.03, 0.16, 0.05, 1.0), emit_strength=0.6)
    mats["print_red"] = base.principled(
        "Serigrafia_Rojo", (0.62, 0.07, 0.05, 1.0), rough=0.5,
        emit=(0.20, 0.02, 0.01, 1.0), emit_strength=0.6)
    mats["ring"] = base.principled(
        "Aro_Metal", (0.30, 0.31, 0.34, 1.0), rough=0.28, metal=1.0)
    # acrilico transparente sobre el OLED encendido
    mats["lens_clear"] = base.principled(
        "Acrilico_Claro", (0.90, 0.94, 1.0, 1.0), rough=0.05,
        transmission=0.96, ior=1.49, spec=0.03)
    # cables de silicona AWG28-30
    mats["cable_red"] = base.principled(
        "Cable_Rojo", (0.55, 0.03, 0.03, 1.0), rough=0.55)
    mats["cable_black"] = base.principled(
        "Cable_Negro", (0.03, 0.03, 0.035, 1.0), rough=0.55)
    mats["cable_white"] = base.principled(
        "Cable_Blanco", (0.82, 0.82, 0.80, 1.0), rough=0.55)
    mats["cable_gray"] = base.principled(
        "Cable_Gris", (0.35, 0.36, 0.38, 1.0), rough=0.55)
    mats["cable_blue"] = base.principled(
        "Cable_Azul", (0.04, 0.12, 0.52, 1.0), rough=0.55)
    mats["cable_yellow"] = base.principled(
        "Cable_Amarillo", (0.62, 0.48, 0.03, 1.0), rough=0.55)
    mats["cable_green"] = base.principled(
        "Cable_Verde", (0.05, 0.32, 0.10, 1.0), rough=0.55)
    mats["cable_orange"] = base.principled(
        "Cable_Naranja", (0.62, 0.22, 0.02, 1.0), rough=0.55)
    mats["cable_purple"] = base.principled(
        "Cable_Morado", (0.24, 0.05, 0.38, 1.0), rough=0.55)
    mats["cable_brown"] = base.principled(
        "Cable_Marron", (0.20, 0.09, 0.035, 1.0), rough=0.55)
    mats["cable_pink"] = base.principled(
        "Cable_Rosa", (0.58, 0.10, 0.28, 1.0), rough=0.55)
    # pads dorados de los modulos
    mats["pad"] = base.principled(
        "Pad_Estano", (0.74, 0.66, 0.42, 1.0), rough=0.30, metal=0.85)
    # tinta encendida del OLED: pixeles claros sobre fondo apagado
    mats["ink_face"] = base.principled(
        "Tinta_OLED_Encendida", (0.80, 0.92, 1.0, 1.0), rough=0.30,
        emit=(0.70, 0.88, 1.0, 1.0), emit_strength=3.2)
    # cinta Kapton (ambar translucido)
    mats["kapton"] = base.principled(
        "Kapton", (0.52, 0.26, 0.05, 1.0), rough=0.22,
        transmission=0.45, ior=1.45)
    return mats


def build_shell(mats):
    body_col = collection("01_Cuerpo")
    lid_col = collection("02_Tapa")

    # --- cuerpo: fondo redondeado, pared vertical y borde de junta
    body = prism("Fifo_Body", W, L, R_PLAN, 0.0, BODY_H, body_col,
                 "body", seg=48, bevel_bottom=1.6, bevel_top=0.0,
                 bevel_seg=6)
    assign(body, mats["shell"])

    cav = prism("cav", W - 2 * WALL, L - 2 * WALL, R_IN, FLOOR,
                BODY_H + 0.5, body_col, seg=48)
    cut(body, cav, "cavity")

    # ranura perimetral para la lengueta de la tapa
    # (labio exterior 0.60 mm, ranura 0.65 mm, labio interior 0.55 mm)
    groove = rounded_ring("groove", W - 2 * SEAM_OFF, L - 2 * SEAM_OFF,
                          R_PLAN - SEAM_OFF, SEAM_W,
                          BODY_H - SEAM_DEPTH, BODY_H + 0.5, body_col)
    cut(body, groove, "groove")

    # --- tapa: misma silueta, canto superior muy redondeado
    lid = prism("Fifo_Lid", W, L, R_PLAN, BODY_H, H, lid_col,
                "lid", seg=48, bevel_top=3.4, bevel_bottom=0.0,
                bevel_seg=8)
    assign(lid, mats["shell"])

    # lengueta que entra en la ranura (0.15 mm de juego por lado)
    tongue = rounded_ring("tongue", W - 2 * (SEAM_OFF + SEAM_GAP),
                          L - 2 * (SEAM_OFF + SEAM_GAP),
                          R_PLAN - (SEAM_OFF + SEAM_GAP), TONGUE_W,
                          BODY_H - SEAM_DEPTH + SEAM_BOT_GAP, BODY_H + 0.6,
                          lid_col, "lid")
    # unir la lengueta a la tapa con boolean para dejar un solido limpio
    base.select_only(lid)
    bpy.context.view_layer.objects.active = lid
    mod = lid.modifiers.new("tongue_union", "BOOLEAN")
    mod.operation = "UNION"
    mod.solver = "EXACT"
    mod.object = tongue
    bpy.ops.object.modifier_apply(modifier=mod.name)
    bpy.data.objects.remove(tongue, do_unlink=True)
    lid.name = "Fifo_Lid"
    PARTS["lid"] = [lid]
    PARTS["body"] = [body]

    # hueco interior de la tapa (deja 1.5 mm de techo)
    lcav = prism("lcav", W - 2 * WALL, L - 2 * WALL, R_IN,
                 BODY_H - 0.2, H - 1.5, lid_col, seg=48)
    cut(lid, lcav, "lidcavity")

    return body, lid


def place(ob, x, y):
    ob.location = (x, y, 0)
    base.select_only(ob)
    bpy.ops.object.transform_apply(location=True)
    return ob


def smooth(ob, angle=35.0):
    base.select_only(ob)
    try:
        bpy.ops.object.shade_smooth_by_angle(angle=math.radians(angle))
    except Exception:
        base.shade_smooth(ob)
    return ob


def beveled_disc(name, radius, z0, z1, cx, cy, col, group=None, width=0.5,
                 seg=48):
    ob = disc(name, radius, z0, z1, cx, cy, col, group, verts=seg)
    bev = ob.modifiers.new("b", "BEVEL")
    bev.width = width
    bev.segments = 4
    bev.limit_method = "ANGLE"
    bev.angle_limit = math.radians(35)
    base.select_only(ob)
    bpy.ops.object.modifier_apply(modifier=bev.name)
    return ob


def build_speaker_retainer(mats, col):
    """Bandeja de apriete del altavoz: pieza aparte, se imprime sola.

    Marco que se desliza desde el frente (-Y): dos largueros que apoyan en los
    pies de los carriles y dos travesanos bajo los cantos del driver.  El
    centro queda abierto (por ahi pasan los cables del amplificador) y el
    travesano delantero lleva un hueco para las dos subidas a los pads.
    """
    tray = block("Spk_Retainer_L", SPK_BAR_L[1] - SPK_BAR_L[0],
                 SPK_TRAY_Y1 - SPK_TRAY_Y0, SPK_TRAY_Z1 - SPK_TRAY_Z0,
                 0.5 * (SPK_BAR_L[0] + SPK_BAR_L[1]),
                 0.5 * (SPK_TRAY_Y0 + SPK_TRAY_Y1), SPK_TRAY_Z0, col)
    rx = 0.5 * (SPK_BAR_R[0] + SPK_BAR_R[1])
    # larguero derecho: grueso donde lo sostiene el pie (delante del bus) y un
    # puente fino por encima del bus que lo une al travesano trasero; asi el
    # marco es una sola isla sin bajar al pasillo de los cables (16,55).
    union(tray, block("Spk_Retainer_R", SPK_BAR_R[1] - SPK_BAR_R[0],
                      SPK_BAR_R_Y[1] - SPK_BAR_R_Y[0],
                      SPK_TRAY_Z1 - SPK_TRAY_Z0, rx,
                      0.5 * (SPK_BAR_R_Y[0] + SPK_BAR_R_Y[1]),
                      SPK_TRAY_Z0, col))
    by1 = SPK_TINE_BACK[0] + 0.10
    by0 = SPK_BAR_R_Y[1] - 0.10
    union(tray, block("Spk_Retainer_Rb", SPK_BAR_R[1] - SPK_BAR_R[0],
                      by1 - by0, SPK_TRAY_Z1 - SPK_TINE_Z0, rx,
                      0.5 * (by0 + by1), SPK_TINE_Z0, col))
    for tag, (y0, y1) in (("Tf", SPK_TINE_FRONT), ("Tb", SPK_TINE_BACK)):
        union(tray, block(f"Spk_Retainer_{tag}",
                          SPK_TINE_X[1] - SPK_TINE_X[0], y1 - y0,
                          SPK_TRAY_Z1 - SPK_TINE_Z0,
                          0.5 * (SPK_TINE_X[0] + SPK_TINE_X[1]),
                          0.5 * (y0 + y1), SPK_TINE_Z0, col))

    # hueco de las dos subidas a los pads del driver
    cut(tray, block("Spk_Tray_gap", SPK_WIRE_GAP[1] - SPK_WIRE_GAP[0],
                    SPK_TINE_FRONT[1] - SPK_TINE_FRONT[0] + 0.40, 1.20,
                    0.5 * (SPK_WIRE_GAP[0] + SPK_WIRE_GAP[1]),
                    0.5 * (SPK_TINE_FRONT[0] + SPK_TINE_FRONT[1]),
                    SPK_TINE_Z0 - 0.30, col), "traygap")

    # bolsillos de los resaltes de los pies (0,26 de profundidad)
    pockets = []
    for x0, x1 in (SPK_BAR_L, SPK_BAR_R):
        pockets.append(block("Spk_Tray_p", x1 - x0 + 0.40,
                             SPK_POCKET_Y[1] - SPK_POCKET_Y[0],
                             SPK_POCKET_H + 0.20, 0.5 * (x0 + x1),
                             0.5 * (SPK_POCKET_Y[0] + SPK_POCKET_Y[1]),
                             SPK_TRAY_Z0 - 0.20, col))
    cut(tray, pockets, "pockets")
    tray.name = "Spk_Retainer"
    assign(tray, mats["ring"])
    mount(tray, "lid")
    return tray


def build_lid_features(mats, lid):
    col = collection("02_Tapa")

    # --- ventana y bisel de la pantalla, perfectamente centrados en X ---
    recess = prism("scr_recess", 26.4, 14.4, 4.1, H - 0.9, H + 0.5,
                   col, seg=32)
    place(recess, *SCR_C)
    win = prism("scr_win", SCR_WIN_W, SCR_WIN_L, SCR_WIN_R,
                H - 2.2, H + 0.5, col, seg=32)
    place(win, *SCR_C)
    cut(lid, [recess, win], "screen")

    gasket = rounded_ring("Screen_Gasket", 26.2, 14.2, 4.0, 0.9,
                          H - 0.85, H - 0.60, col, "face")
    place(gasket, *SCR_C)
    assign(gasket, mats["bezel"])
    mount(gasket, "lid")

    lens = prism("Acrylic_Lens", SCR_LENS_W, SCR_LENS_L, SCR_LENS_R,
                 H - 0.58, H + 0.06, col, "face", seg=32,
                 bevel_top=0.35, bevel_seg=4)
    place(lens, *SCR_C)
    assign(lens, mats["lens_clear"])
    mount(lens, "lid")

    # --- OLED real (27 x 27): PCB bajo el techo y cristal en la ventana ---
    oled = prism("OLED_096", OLED_W, OLED_L, OLED_R, OLED_PCB_Z0,
                 OLED_PCB_Z0 + OLED_PCB_H, col, "internals", seg=24)
    place(oled, *OLED_C)
    assign(oled, mats["pcb_oled"])
    mount(oled, "lid")
    lcd = block("OLED_LCD", 21.8, 11.0, OLED_GLASS_H, SCR_C[0], SCR_C[1],
                OLED_PCB_Z0 + OLED_PCB_H, col, "internals")
    assign(lcd, mats["lcd_off"])
    mount(lcd, "lid")
    # header de 4 pines mirando al cuerpo; el mazo se suelda a las puntas
    for pin in OLED_PINS:
        x, y, z = oled_pad(pin)
        ob = block(f"OLED_Pin_{pin}", 0.64, 0.64, OLED_PIN_L, x, y, z,
                   col, "internals")
        assign(ob, mats["pad"])
        mount(ob, "lid")

    z = H - 1.38
    for ex in (SCR_C[0] - 4.0, SCR_C[0] + 4.0):
        cu = bpy.data.curves.new("eye", "CURVE")
        cu.dimensions = "3D"
        sp = cu.splines.new("POLY")
        pts = [(ex + 1.55 * math.cos(math.radians(180 - k * 12)),
                SCR_C[1] + 0.55 + 1.0 * math.sin(math.radians(180 - k * 12)))
               for k in range(16)]
        sp.points.add(len(pts) - 1)
        for p, (px, py) in zip(sp.points, pts):
            p.co = (px, py, 0, 1)
        cu.bevel_depth = 0.26
        cu.bevel_resolution = 2
        cu.use_fill_caps = True
        ob = bpy.data.objects.new("eye", cu)
        ob.location.z = z
        col.objects.link(ob)
        PARTS["internals"].append(ob)
        assign(ob, mats["ink_face"])
        mount(ob, "lid")

    cu = bpy.data.curves.new("smile", "CURVE")
    cu.dimensions = "3D"
    sp = cu.splines.new("POLY")
    pts = [(SCR_C[0] + 4.3 * (-1 + k * 2 / 22),
            SCR_C[1] - 2.0 + 0.9 * (-1 + k * 2 / 22) ** 2)
           for k in range(23)]
    sp.points.add(len(pts) - 1)
    for p, (px, py) in zip(sp.points, pts):
        p.co = (px, py, 0, 1)
    cu.bevel_depth = 0.26
    cu.bevel_resolution = 2
    cu.use_fill_caps = True
    smile = bpy.data.objects.new("smile", cu)
    smile.location.z = z
    col.objects.link(smile)
    PARTS["internals"].append(smile)
    assign(smile, mats["ink_face"])
    mount(smile, "lid")

    # El firmware dibuja "FIFO" en la barra de estado superior; la cara va
    # debajo.  El texto se coloca arriba a la izquierda, como en el OLED.
    t = base.text("FIFO", 1.2, (SCR_C[0] - 7.4, SCR_C[1] + 4.4, z),
                  mats["ink_face"], col, "internals", space=1.4)
    mount(t, "lid")

    # --- parlante: rebaje, abertura y rejilla como pieza aparte ---
    spk_recess = disc("spk_recess", SPK_RING_R, H - 0.55, H + 0.5,
                      SPK_C[0], SPK_C[1], col, verts=64)
    cut(lid, spk_recess, "spkrecess")
    spk_open = disc("spk_open", SPK_GRILLE_R + 0.15, H - 2.2, H + 0.5,
                    SPK_C[0], SPK_C[1], col, verts=64)
    cut(lid, spk_open, "spkopen")

    plate = disc("Spk_Grille", SPK_GRILLE_R + 0.1, H - 1.05, H - 0.15,
                 SPK_C[0], SPK_C[1], col, "face", verts=64)
    holes = [disc("spk_h", 0.5, H - 1.6, H + 0.2, SPK_C[0], SPK_C[1],
                  col, verts=12)]
    for radius, count in ((2.35, 6), (4.35, 12), (6.30, 18)):
        for k in range(count):
            a = 2 * math.pi * k / count + (0.26 if radius > 4 else 0)
            holes.append(disc("spk_h", 0.5, H - 1.6, H + 0.2,
                              SPK_C[0] + radius * math.cos(a),
                              SPK_C[1] + radius * math.sin(a),
                              col, verts=12))
    cut(plate, holes, "grille")
    assign(plate, mats["ring"])
    mount(plate, "lid")

    rim = ring("Spk_Rim", SPK_RING_R, SPK_GRILLE_R + 0.05,
               H - 0.55, H + 0.52, col, "face", seg=64)
    place(rim, *SPK_C)
    assign(rim, mats["ring"])
    mount(rim, "lid")

    driver = prism("Speaker_Driver", SPK_DRIVER_W, SPK_DRIVER_L, 2.0,
                   H - 4.3, H - 1.4, col, "internals", seg=16,
                   bevel_top=0.5)
    place(driver, *SPK_C)
    assign(driver, mats["driver"])
    mount(driver, "lid")

    # --- retencion del altavoz: carriles, resaltes y tope trasero ---
    # El carril izquierdo es una repisa pegada a la pared (donde no sube ningun
    # cable): la propia pared guia el larguero, asi que no hay costilla que
    # choque con el mazo de X=-16,05.  El derecho cuelga del techo con un pie.
    # Cada pie lleva un resalte de Ø0,44 que entra en el bolsillo de la bandeja.
    # El tope trasero cierra el recorrido por detras y pasa bajo el OLED.
    yc = 0.5 * (SPK_RAIL_Y[0] + SPK_RAIL_Y[1])
    union(lid, block("Spk_Ledge", SPK_LEDGE[1] - SPK_LEDGE[0],
                     SPK_RAIL_Y[1] - SPK_RAIL_Y[0],
                     SPK_TRAY_Z0 - SPK_RAIL_Z0,
                     0.5 * (SPK_LEDGE[0] + SPK_LEDGE[1]), yc, SPK_RAIL_Z0,
                     col))
    union(lid, base.tube_y("Spk_Ledge_b", SPK_BUMP_D / 2,
                           SPK_BUMP_Y[0], SPK_BUMP_Y[1],
                           0.5 * (SPK_LEDGE[0] + SPK_LEDGE[1]),
                           0.5 * (SPK_BUMP_Y[0] + SPK_BUMP_Y[1]),
                           SPK_TRAY_Z0, col, verts=24))
    # El carril derecho cuelga del techo con pie y resalte, pero solo existe
    # delante del bus (SPK_RAIL_R_Y): detras, a 16,55, pasan los cables de la
    # OLED y el 3V3 y cualquier carril que bajara a 16,20 los atravesaria.
    yc = 0.5 * (SPK_RAIL_R_Y[0] + SPK_RAIL_R_Y[1])
    union(lid, block("Spk_Rail_R_w",
                     SPK_RAIL_R_WEB[1] - SPK_RAIL_R_WEB[0],
                     SPK_RAIL_R_Y[1] - SPK_RAIL_R_Y[0], 20.00 - SPK_RAIL_Z0,
                     0.5 * (SPK_RAIL_R_WEB[0] + SPK_RAIL_R_WEB[1]), yc,
                     SPK_RAIL_Z0, col))
    union(lid, block("Spk_Rail_R_f",
                     SPK_RAIL_R_FOOT[1] - SPK_RAIL_R_FOOT[0],
                     SPK_RAIL_R_Y[1] - SPK_RAIL_R_Y[0],
                     SPK_TRAY_Z0 - SPK_RAIL_Z0,
                     0.5 * (SPK_RAIL_R_FOOT[0] + SPK_RAIL_R_FOOT[1]), yc,
                     SPK_RAIL_Z0, col))
    union(lid, base.tube_y("Spk_Rail_R_b", SPK_BUMP_D / 2,
                           SPK_BUMP_Y[0], SPK_BUMP_Y[1],
                           0.5 * (SPK_RAIL_R_FOOT[0] + SPK_RAIL_R_FOOT[1]),
                           0.5 * (SPK_BUMP_Y[0] + SPK_BUMP_Y[1]),
                           SPK_TRAY_Z0, col, verts=24))
    union(lid, block("Spk_Stop", SPK_STOP_X[1] - SPK_STOP_X[0],
                     SPK_STOP_Y[1] - SPK_STOP_Y[0],
                     SPK_TRAY_Z1 - SPK_TRAY_Z0,
                     0.5 * (SPK_STOP_X[0] + SPK_STOP_X[1]),
                     0.5 * (SPK_STOP_Y[0] + SPK_STOP_Y[1]),
                     SPK_TRAY_Z0, col))
    build_speaker_retainer(mats, col)

    # --- botones: asiento del tactil, pozo, aro, tapa y copa guia ---
    # Cada tactil de 6x6x5 se pega en un asiento impreso en la tapa: un anillo
    # de 8,0 x 8,0 (pared 0,9) que cuelga del techo, con un ala de 10,4 que lo
    # ancla a la cara interior.  La tapa del boton apoya su pestaña en el aro
    # exterior y su copa envuelve el pulsador (holgura 0,10) empujandolo por
    # el techo interior: no hay clip, el pulsador es un cilindro liso sin rebaje.
    for i, center in enumerate(BTN_C):
        hat = prism(f"Btn_Seat_{i}", BTN_HAT_W, BTN_HAT_W, 2.0,
                    19.40, 20.10, col)
        place(hat, *center)
        lower = prism(f"Btn_Seat_{i}_b", BTN_SEAT_W, BTN_SEAT_W, 1.2,
                      17.40, 19.50, col)
        place(lower, *center)
        base.select_only(hat)
        bpy.context.view_layer.objects.active = hat
        mod = hat.modifiers.new("seat_union", "BOOLEAN")
        mod.operation = "UNION"
        mod.solver = "EXACT"
        mod.object = lower
        bpy.ops.object.modifier_apply(modifier=mod.name)
        bpy.data.objects.remove(lower, do_unlink=True)
        pocket = prism(f"Btn_Seat_{i}_p", BTN_SEAT_POCKET,
                       BTN_SEAT_POCKET, 0.6, 17.30, 20.20, col)
        place(pocket, *center)
        cut(hat, pocket, "btnseat")
        base.select_only(lid)
        bpy.context.view_layer.objects.active = lid
        mod = lid.modifiers.new(f"seat_{i}", "BOOLEAN")
        mod.operation = "UNION"
        mod.solver = "EXACT"
        mod.object = hat
        bpy.ops.object.modifier_apply(modifier=mod.name)
        bpy.data.objects.remove(hat, do_unlink=True)

    # pozo pasante de la tapa del boton (deja ver el pulsador del tactil)
    bores = [disc("btn_bore", BTN_BORE_R, 20.00, 21.60,
                  c[0], c[1], col, verts=48) for c in BTN_C]
    cut(lid, bores, "btnbore")
    # rebaje del aro: sin el, el aro se embutia 0,10 en la cara maciza
    recesses = [disc("btn_recess", BTN_RECESS_R, BTN_RECESS_Z0, 21.60,
                     c[0], c[1], col, verts=48) for c in BTN_C]
    cut(lid, recesses, "btnrecess")

    for i, (center, mat, label, label_mat, label_y) in enumerate((
            (BTN_C[0], mats["btn_green"], "+  SUBIR VOL",
             mats["print_green"], -1.0),
            (BTN_C[1], mats["btn_red"], "-  BAJAR VOL",
             mats["print_red"], -25.0))):
        bez = ring(f"Btn_Bezel_{i}", BTN_BEZEL_R, BTN_BEZEL_IN_R,
                   BTN_BEZEL_Z0, BTN_BEZEL_Z1, col, "face", seg=48)
        place(bez, *center)
        skirt = ring(f"Btn_Bezel_{i}_skirt", BTN_BEZEL_R, BTN_BORE_R,
                     BTN_SKIRT_Z0, BTN_BEZEL_Z0 + 0.10, col, seg=48)
        place(skirt, *center)
        base.select_only(bez)
        bpy.context.view_layer.objects.active = bez
        mod = bez.modifiers.new("skirt", "BOOLEAN")
        mod.operation = "UNION"
        mod.solver = "EXACT"
        mod.object = skirt
        bpy.ops.object.modifier_apply(modifier=mod.name)
        bpy.data.objects.remove(skirt, do_unlink=True)
        assign(bez, mats["bezel"])
        mount(bez, "lid")

        # tapa + pestaña + copa guia: un solo solido imprimible
        cap = beveled_disc(f"Button_{i}", BTN_CAP_R, 21.35, 22.40,
                           center[0], center[1], col, "face", width=0.40)
        flange = disc(f"Button_{i}_flange", BTN_FLANGE_R,
                      BTN_FLANGE_Z0, BTN_FLANGE_Z1,
                      center[0], center[1], col, verts=48)
        cup = disc(f"Button_{i}_stem", BTN_CUP_IR + BTN_CUP_WALL,
                   BTN_CUP_Z0, BTN_STEM_Z1, center[0], center[1], col, verts=32)
        bore = disc(f"Button_{i}_cup_bore", BTN_CUP_IR,
                    BTN_CUP_Z0 - 0.20, BTN_STEM_Z0,
                    center[0], center[1], col, verts=32)
        base.select_only(cup)
        bpy.context.view_layer.objects.active = cup
        mod = cup.modifiers.new("cup_bore", "BOOLEAN")
        mod.operation = "DIFFERENCE"
        mod.solver = "EXACT"
        mod.object = bore
        bpy.ops.object.modifier_apply(modifier=mod.name)
        bpy.data.objects.remove(bore, do_unlink=True)
        base.select_only(cap)
        bpy.context.view_layer.objects.active = cap
        for extra, tag in ((flange, "flange"), (cup, "stem")):
            mod = cap.modifiers.new(tag, "BOOLEAN")
            mod.operation = "UNION"
            mod.solver = "EXACT"
            mod.object = extra
            bpy.ops.object.modifier_apply(modifier=mod.name)
            bpy.data.objects.remove(extra, do_unlink=True)
        assign(cap, mat)
        mount(cap, "lid")

        # Tactil de 6x6x5: cuerpo de 4,5 (15,20..19,70) y pulsador de 0,5
        # (19,70..20,20).  Cuelga bajo el asiento y la celda girada (X<=6,5)
        # le deja libre X 8,2..14,2; sus cables bajan a 14,85, por debajo del
        # pad, y corren por fuera de las caras del cuerpo.
        tact = block(f"Tact_{i}", 6.0, 6.0, 4.5, center[0], center[1],
                     15.20, col, "internals")
        assign(tact, mats["switch"])
        mount(tact, "lid")
        bump = disc(f"Tact_{i}_actuator", TACT_ACT_R, BTN_ACT_Z0, BTN_STEM_Z0,
                    center[0], center[1], col, "internals", verts=32)
        assign(bump, mats["switch"])
        mount(bump, "lid")

        t = base.text(label, 0.9, (center[0], label_y, H + 0.02),
                      label_mat, col, "face", space=1.15)
        mount(t, "lid")

    # --- microfono INMP441 redondo: PCB, lata y dos filas de pads ---
    # Bottom-port: la cara de soldadura (con el puerto) mira a la ventana.
    mic_hole = disc("mic_hole", 0.55, H - 2.0, H + 0.5,
                    MIC_C[0], MIC_C[1], col, verts=20)
    cut(lid, mic_hole, "mic")

    # bota acustica: lleva el puerto al taladro de la cara sin abrir la
    # cavidad.  Se acorta a 19,15 para dejar los 0,35 mm de la arandela de
    # espuma; se une a la tapa para que sea una sola pieza imprimible.
    boot = rounded_ring("Mic_Acoustic_Boot", 8.8, 5.0, 1.2, 0.8,
                        MIC_BOOT_Z0, H - 1.4, col)
    place(boot, MIC_C[0] - 1.0, MIC_C[1])
    assign(boot, mats["shell"])
    base.select_only(lid)
    bpy.context.view_layer.objects.active = lid
    mod = lid.modifiers.new("mic_boot", "BOOLEAN")
    mod.operation = "UNION"
    mod.solver = "EXACT"
    mod.object = boot
    bpy.ops.object.modifier_apply(modifier=mod.name)
    bpy.data.objects.remove(boot, do_unlink=True)

    # zocalo del micro: dos repisas bajo el canto de la PCB (17,70..18,00),
    # cuatro postes que las cuelgan del techo y dos guias laterales.  La PCB
    # entra desde arriba y queda pinzada entre las repisas y la bota; no hay
    # clip ni pegamento.  Todo se fusiona a la tapa.
    for sx in (-1, 1):
        shelf = block(f"Mic_Shelf_{sx:+d}", MIC_SHELF_X1 - MIC_SHELF_X0,
                      MIC_SHELF_Y1 - MIC_SHELF_Y0,
                      MIC_SHELF_Z1 - MIC_SHELF_Z0,
                      sx * 0.5 * (MIC_SHELF_X0 + MIC_SHELF_X1),
                      0.5 * (MIC_SHELF_Y0 + MIC_SHELF_Y1),
                      MIC_SHELF_Z0, col)
        union(lid, shelf)
        guide = block(f"Mic_Guide_{sx:+d}", MIC_GUIDE_X1 - MIC_GUIDE_X0,
                      MIC_GUIDE_Y, MIC_SHELF_Z1 - MIC_SHELF_Z0,
                      sx * 0.5 * (MIC_GUIDE_X0 + MIC_GUIDE_X1),
                      MIC_C[1], MIC_SHELF_Z0, col)
        union(lid, guide)
        for tag, y0, y1 in (("rear", MIC_POST_Y0, MIC_POST_Y1),
                            ("front", MIC_POST_F0, MIC_POST_F1)):
            post = block(f"Mic_Post_{sx:+d}_{tag}",
                         MIC_POST_X1 - MIC_POST_X0, y1 - y0,
                         20.00 - MIC_SHELF_Z1,
                         sx * 0.5 * (MIC_POST_X0 + MIC_POST_X1),
                         0.5 * (y0 + y1), MIC_SHELF_Z1, col)
            union(lid, post)

    mic = disc("INMP441", MIC_D / 2, MIC_PCB_Z0, MIC_TOP,
               MIC_C[0], MIC_C[1], col, "internals", verts=64)
    assign(mic, mats["pcb_mic"])
    mount(mic, "lid")

    # muescas laterales del PCB redondo
    notches = [disc("mic_notch", 1.0, MIC_PCB_Z0 - 0.2, MIC_TOP + 0.2,
                    MIC_C[0] + sx * (MIC_D / 2), MIC_C[1], col, verts=24)
               for sx in (-1, 1)]
    cut(mic, notches, "micnotch")

    # lata del MEMS en la cara de componentes (hacia el cuerpo)
    can = block("INMP441_Can", 3.8, 3.0, MIC_CAN_H,
                MIC_C[0] + 1.2, MIC_C[1], MIC_CAN_Z0, col, "internals")
    assign(can, mats["steel"])
    mount(can, "lid")

    # componentes pasivos junto a la lata
    for i, dy in enumerate((-1.0, 1.0)):
        smd = block(f"INMP441_SMD_{i}", 1.0, 0.6, 0.5,
                    MIC_C[0] - 4.0, MIC_C[1] + dy, MIC_PCB_Z0 - 0.5,
                    col, "internals")
        assign(smd, mats["switch"])
        mount(smd, "lid")

    # dos filas de 3 pads pasantes; el hilo se suelda en la cara superior
    for pin in MIC_PINS:
        px, py, _ = mic_pad(pin)
        pad = ring(f"Pad_Mic_{pin}", 0.65, 0.38, MIC_TOP, MIC_TOP + 0.08,
                   col, "internals", seg=24)
        pad.location = (px, py, 0)
        assign(pad, mats["pad"])
        mount(pad, "lid")

    # ranura del puerto acustico y via central, como en el modulo real
    slot = prism("INMP441_PortSlot", 2.6, 1.1, 0.55, MIC_TOP, MIC_TOP + 0.03,
                 col, "internals", seg=24)
    place(slot, MIC_C[0] - 2.2, MIC_C[1])
    assign(slot, mats["print"])
    mount(slot, "lid")
    via = ring("Pad_Mic_Via", 0.80, 0.45, MIC_TOP, MIC_TOP + 0.08,
               col, "internals", seg=24)
    via.location = (MIC_C[0], MIC_C[1], 0)
    assign(via, mats["pad"])
    mount(via, "lid")

    # rotulos de las dos filas, pegados al canto y fuera de la bota acustica:
    # los tramos de WS y VDD corren por dentro (y=-14,55 / -25,45) para no
    # tapar el texto
    for label, ly in (("SCK WS L/R", MIC_C[1] + 6.25),
                      ("SD VDD GND", MIC_C[1] - 6.15)):
        t = base.text(label, 0.5, (MIC_C[0], ly, MIC_TOP + 0.01),
                      mats["print"], col, "internals", space=1.1)
        mount(t, "lid")

    # antena del XIAO (impresa en la placa, extremo opuesto al USB-C)
    ant = block("XIAO_Antenna", XIAO_ANT_W, XIAO_ANT_L, 0.3, XIAO_C[0],
                XIAO_C[1] + XIAO_L / 2 - XIAO_ANT_L / 2 - 0.2,
                XIAO_TOP, col, "internals")
    assign(ant, mats["ceramic"])
    mount(ant, "body")

    # antena FPC del XIAO pegada a la pared interior izquierda
    fpc = block("XIAO_FPC_Antenna", 0.4, 7.0, 4.0, -17.0, 2.0, 8.0,
                col, "internals")
    assign(fpc, mats["kapton"])
    mount(fpc, "body")

    t = base.text("ALTAVOZ", 1.05, (SPK_C[0], SPK_C[1] - 10.4, H + 0.02),
                  mats["print_dim"], col, "face", space=1.35)
    mount(t, "lid")

    # --- amplificador real, pegado bajo el OLED (sin borne verde) ---
    amp = block("MAX98357A", AMP_W, AMP_L, AMP_H, AMP_C[0], AMP_C[1],
                AMP_Z0, col, "internals")
    assign(amp, mats["pcb_amp"])
    mount(amp, "lid")

    for ob in PARTS["face"]:
        if ob.type == "MESH":
            smooth(ob, 35.0)
    return lid


def build_body_features(mats, body):
    col = collection("01_Cuerpo")

    # USB-C centrado en la pared -Y.  El hueco es PASANTE: entra 4 mm desde
    # fuera y llega 0,4 mm dentro de la cara interior de la pared (no deja
    # membrana).  Esta centrado en la boca real del conector (USB_ZC).
    usb = slot_xz("usb_cut", USB_HOLE_W, USB_HOLE_H, -L / 2 - 4.0,
                  -L / 2 + WALL + 0.40, 0.0, USB_ZC, col, radius=1.3)
    cut(body, usb, "usb")

    # bisel exterior: marco de 0,60 mm que tapa el canto vivo del hueco
    usb_bez = slot_xz("usb_bez_o", USB_BEZEL_W, USB_BEZEL_H,
                      -L / 2 - 0.60, -L / 2 + 0.02, 0.0, USB_ZC, col,
                      radius=1.8)
    usb_in = slot_xz("usb_bez_i", USB_BEZEL_IN_W, USB_BEZEL_IN_H,
                     -L / 2 - 0.90, -L / 2 + 0.60, 0.0, USB_ZC, col,
                     radius=1.3)
    cut(usb_bez, usb_in, "usbbez")
    assign(usb_bez, mats["bezel"])
    mount(usb_bez, "body")

    # ranura del interruptor en la pared -X: 2 mm por lado desde el cuerpo
    sw = block("sw_cut", 5.0, SW_SLOT_W, SW_SLOT_H, -W / 2, SW_C[1], 3.1, col)
    cut(body, sw, "switch")
    # pista rehundida estilo slider: el paddle impreso corre dentro de ella
    track = slot_xz("sw_track", SW_TRACK_W, SW_TRACK_H, -0.8, 0.8,
                    0.0, 4.8, col, radius=1.7)
    track.rotation_euler.z = math.radians(90)
    base.select_only(track)
    bpy.ops.object.transform_apply(rotation=True)
    track.location = (-W / 2 + 0.2, SW_C[1], 0.0)
    cut(body, [track], "swtrack")

    # postes que sostienen la bateria sobre el XIAO (entran 0,2 mm en el
    # piso para que el booleano de union los fusione con el cuerpo).  Tres
    # puntos: el XIAO ocupa el centro (X -8,9..8,9 / Y -26,5..-5,5) y un
    # cuarto poste caeria sobre su PCB.
    for i, (px, py) in enumerate(BAT_POSTS):
        post = disc(f"Battery_Post_{i}", 1.00, FLOOR - 0.20, BAT_Z0,
                    px, py, col, "body", verts=32)
        assign(post, mats["shell"])
        mount(post, "body")

    # pocket del piso bajo el XIAO: 0,8 mm de rebaje para que los hilos de
    # BAT+ / BAT- pasen bajo la placa y salgan por el canto.  Llega hasta la
    # pared delantera y sobresale 1,0 mm del contorno por los tres lados.
    pocket = prism("xiao_pocket", POCKET_X * 2, POCKET_Y1 - POCKET_Y0,
                   POCKET_R, POCKET_Z0, FLOOR + 0.10, col)
    place(pocket, 0.0, (POCKET_Y0 + POCKET_Y1) / 2)
    cut(body, pocket, "pocket")

    # 4 postes bajo el XIAO: la placa apoya en ellos y las 4 patas del USB-C
    # quedan 0,5 mm sobre el piso.  Nacen en el fondo del pocket (0,8 mm
    # dentro del piso) para que el booleano de union sea limpio.
    for sx in (-1, 1):
        for sy, py in ((-1, XIAO_C[1] - XIAO_L / 2 + 1.0),
                       (1, XIAO_C[1] + XIAO_L / 2 - 1.0)):
            post = disc(f"Xiao_Post_{sx:+d}{sy:+d}", 0.90,
                        POCKET_Z0 - 0.20, XIAO_BOTTOM, sx * 7.20, py, col,
                        "body", verts=24)
            assign(post, mats["shell"])
            mount(post, "body")

    return body


def build_body_internals(mats):
    col = collection("06_Internos")

    xiao = block("XIAO_ESP32S3", XIAO_W, XIAO_L, XIAO_PCB_H,
                 XIAO_C[0], XIAO_C[1], XIAO_BOTTOM, col, "internals")
    assign(xiao, mats["pcb"])
    mount(xiao, "body")
    shield = block("RF_Shield", 9.0, 10.0, 1.50, XIAO_C[0],
                   XIAO_C[1] + 1.30, XIAO_TOP, col, "internals")
    assign(shield, mats["foil"])
    mount(shield, "body")

    # --- conector USB-C real: carcasa metalica con boca oscura ---
    # 8,94 x 7,35 x 3,26 sobre la cara superior del PCB; la boca queda a
    # 0,10 mm de la pared interior y centrada con el hueco pasante.
    usb = block("USB_C", USB_C_W, USB_C_D, USB_C_H, 0.0,
                USB_Y0 + USB_C_D / 2, USB_Z0, col, "internals")
    bev = usb.modifiers.new("b", "BEVEL")
    bev.width = 0.35
    bev.segments = 3
    bev.limit_method = "ANGLE"
    bev.angle_limit = math.radians(40)
    base.select_only(usb)
    bpy.context.view_layer.objects.active = usb
    bpy.ops.object.modifier_apply(modifier=bev.name)
    assign(usb, mats["steel"])
    mount(usb, "body")

    mouth = block("USB_C_mouth", USB_OPEN_W, 1.20, USB_OPEN_H, 0.0,
                  USB_Y0 + 0.05, USB_ZC - USB_OPEN_H / 2, col, "internals")
    cut(usb, mouth, "usbmouth")
    contact = block("USB_C_Contact", USB_OPEN_W - 0.10, 0.10,
                    USB_OPEN_H - 0.10, 0.0, USB_Y0 + 0.60,
                    USB_ZC - (USB_OPEN_H - 0.10) / 2, col, "internals")
    assign(contact, mats["switch"])
    mount(contact, "body")

    bat = prism("LiPo_902030", BAT_W, BAT_L, BAT_R, BAT_Z0,
                BAT_Z0 + BAT_H, col, "internals", seg=20,
                bevel_bottom=0.35, bevel_top=0.35, bevel_seg=3)
    place(bat, *BAT_C)
    assign(bat, mats["battery"])
    mount(bat, "body")
    label = block("LiPo_Label", 12.0, 24.0, 0.04, BAT_C[0], BAT_C[1],
                  BAT_Z0 + BAT_H, col, "internals")
    assign(label, mats["print_dim"])
    mount(label, "body")

    # ATGM336H de breakout: la lata del chip va soldada por debajo, el PCB de
    # 1,0 encima y en la cara de arriba los SMD, la pila de respaldo y el
    # U.FL.  Los 5 pads (VCC GND TX RX PPS) quedan en el borde delantero.
    gps = block("GPS_ATGM336H", GPS_W, GPS_L, GPS_PCB_H, GPS_C[0], GPS_C[1],
                GPS_PCB_Z0, col, "internals")
    assign(gps, mats["gps"])
    mount(gps, "body")
    can = prism("GPS_Can", GPS_CAN_W, GPS_CAN_L, 0.8, FLOOR, GPS_PCB_Z0,
                col, "internals", seg=24)
    place(can, *GPS_C)
    assign(can, mats["steel"])
    mount(can, "body")
    # pila de respaldo redonda, con el «+» grabado en la cara superior
    cell = disc("GPS_Backup_Cell", 3.40, GPS_PAD_Z, GPS_PAD_Z + 1.90,
                4.00, 15.50, col, "internals", verts=40)
    assign(cell, mats["steel"])
    mount(cell, "body")
    for tag, w, l in (("", 1.10, 0.24), ("2", 0.24, 1.10)):
        plus = block("GPS_Backup_Plus" + tag, w, l, 0.04, 4.00, 15.50,
                     GPS_PAD_Z + 1.90, col, "internals")
        assign(plus, mats["print_dim"])
        mount(plus, "body")
    # conector U.FL y los tres SMD de la cara de componentes
    ufl = block("GPS_UFL", 3.00, 3.10, 1.40, -5.90, 17.80, GPS_PAD_Z,
                col, "internals")
    assign(ufl, mats["steel"])
    mount(ufl, "body")
    for i, (sx, sy) in enumerate(((-6.00, 8.90), (-3.00, 8.90),
                                  (0.00, 8.90))):
        smd = block(f"GPS_SMD_{i}", 2.00, 1.20, 0.50, sx, sy, GPS_PAD_Z,
                    col, "internals")
        assign(smd, mats["switch"])
        mount(smd, "body")
    # antena ceramica de 20 x 6 con pigtail U.FL: PCB de 0,8 + parche de 3,4.
    # Va en el piso trasero, plana y libre de metal.  El parche se corre al
    # fondo (Y 21,9..26,5) para dejar libre la franja delantera del PCB, donde
    # aterriza el pigtail.
    ant = block("GPS_Antenna", ANT_W, ANT_L, 0.80, ANT_C[0], ANT_C[1],
                FLOOR, col, "internals")
    assign(ant, mats["pcb"])
    mount(ant, "body")
    patch = block("GPS_Antenna_Patch", ANT_W - 2.0, ANT_L - 1.4,
                  ANT_H - 0.80, ANT_C[0], ANT_C[1] + 0.70, FLOOR + 0.80,
                  col, "internals")
    assign(patch, mats["ceramic"])
    mount(patch, "body")

    sw = block("Switch_Body", 3.4, SW_BODY_W, 4.0, SW_C[0], SW_C[1], FLOOR,
               col, "internals")
    assign(sw, mats["switch"])
    mount(sw, "body")
    lever_tip = SW_C[0] - 1.7 - SW_LEVER_OUT
    lever_root = SW_C[0] - 1.7 + 0.4  # 0,4 dentro del cuerpo para la union
    lever = block("Switch_Lever", lever_root - lever_tip, SW_LEVER_W,
                  SW_LEVER_H, (lever_root + lever_tip) / 2, SW_C[1], 3.2,
                  col, "internals")
    assign(lever, mats["steel"])
    mount(lever, "body")
    # paddle impreso que se pega en la punta de la palanca y corre rehundido
    # en la pista, como el slider de mute de un telefono
    cap = slot_xz("Switch_Slider", SW_CAP_W, SW_CAP_H,
                  -SW_CAP_T / 2, SW_CAP_T / 2, 0.0, 4.8, col, radius=1.5)
    cap.rotation_euler.z = math.radians(90)
    base.select_only(cap)
    bpy.ops.object.transform_apply(rotation=True)
    cap.location = (-W / 2 + SW_CAP_RECESS + SW_CAP_T / 2, SW_C[1], 0.0)
    assign(cap, mats["shell"])
    mount(cap, "body")
    smooth(cap, 35.0)

    return xiao


def build_pads(mats):
    """Pads dorados en la posicion real de cada pin (solo referencia visual)."""
    col = collection("06_Internos")

    def pad(name, x, y, z0, w, l, h, where):
        ob = block("Pad_" + name, w, l, h, x, y, z0, col, "internals")
        assign(ob, mats["pad"])
        mount(ob, where)

    # --- cara inferior del XIAO (la que se ve desde abajo) ---
    # La placa va elevada 0,5 mm: los pads de la cara inferior quedan a 2,24
    # y dejan sitio a los cables soldados (BAT±, test points y patas USB-C).
    face = XIAO_BOTTOM - 0.06          # 2,24, cara vista de los pads
    for side, pins in ((1, XIAO_RIGHT), (-1, XIAO_LEFT)):
        for pin in pins:
            x, y, z = xiao_pad(side, pin)
            # pad castellado que envuelve el canto (visible desde abajo)
            pad(f"XIAO_{pin}_face", side * 7.92, y, face, 2.10, 1.66, 0.06,
                "body")
            # gota de estano en el canto, donde se suelda el hilo
            pad(f"XIAO_{pin}", x, y, z - 0.12, 0.9, 0.9, 0.12, "body")
            # rotulo del pin en la serigrafia de la cara inferior
            t = base.text(pin, 0.42, (side * 6.35, y, face - 0.015),
                          mats["print"], col, "internals", space=1.0)
            t.rotation_euler = (0.0, math.pi,
                                -math.pi / 2 if side > 0 else math.pi / 2)
            t.name = "TXT_XIAO_" + pin
            mount(t, "body")

    # 8 test points redondos: MTDO/MTDI, GND/EN, MTCK/MTMS y D+/D-
    tests = ((("MTDO", "MTDI"), -8.56), (("GND", "EN"), -5.90),
             (("MTCK", "MTMS"), -3.28), (("D+", "D-"), -0.62))
    for (lname, rname), dy in tests:
        for lx, name in ((-1.22, lname), (1.35, rname)):
            cx, cy = XIAO_C[0] + lx, XIAO_C[1] + dy
            tag = name.replace("/", "").replace("+", "P").replace("-", "M")
            gold = disc("Pad_XIAO_Test_" + tag, 0.575, face, face + 0.06,
                        cx, cy, col, "internals", verts=24)
            assign(gold, mats["pad"])
            mount(gold, "body")
            dot = disc("Pad_XIAO_TestDot_" + tag, 0.30, face - 0.02,
                       face + 0.02, cx, cy, col, "internals", verts=20)
            assign(dot, mats["steel"])
            mount(dot, "body")
            t = base.text(name, 0.45, (cx, cy + 1.42, face - 0.015),
                          mats["print"], col, "internals", space=1.05)
            t.rotation_euler = (0.0, math.pi, 0.0)
            t.name = "TXT_XIAO_T_" + tag
            mount(t, "body")

    # BAT- (lado USB) y BAT+ (lado antena), junto al borde D0..D6
    for name in ("neg", "pos"):
        px, cy, pz = xiao_bat_pad(name)
        pad(f"XIAO_BAT_{name}", px, cy, pz, 2.03, 1.04, 0.06, "body")
        # rotulo centrado bajo su pad (0,95 mm): si va a la altura del pad, el
        # hilo soldado lo tapa; y a X=2,72 quedaba pegado a los test points
        # redondos y parecia rotularlos a ellos.
        t = base.text("BAT-" if name == "neg" else "BAT+", 0.42,
                      (px, cy - 0.95, face - 0.015), mats["print"], col,
                      "internals", space=1.0)
        t.rotation_euler = (0.0, math.pi, 0.0)
        t.name = "TXT_XIAO_BAT_" + name
        mount(t, "body")

    # pad grande de masa bajo el modulo (GND del blindaje)
    pad("XIAO_GND_pad", XIAO_C[0] - 1.01, XIAO_C[1] + 2.56, face, 2.77, 2.62,
        0.06, "body")

    # 4 patas pasantes del USB-C: ovalos dorados con ranura oscura
    for sx in (-1, 1):
        for ty in (-9.43, -5.01):
            cy = XIAO_C[1] + ty
            pad(f"XIAO_USB_{sx:+d}_{ty:+.1f}", sx * 4.32, cy, face,
                0.95, 1.60, 0.06, "body")
            slot = block(f"XIAO_USB_Slot_{sx:+d}_{ty:+.1f}", 0.42, 1.02,
                         0.04, sx * 4.32, cy, face - 0.02, col, "internals")
            assign(slot, mats["steel"])
            mount(slot, "body")

    # serigrafia "seeed studio / XIAO ESP32S3" en el extremo de la antena
    for body, ty in (("seeed studio", -8.15), ("XIAO ESP32S3", -6.75)):
        t = base.text(body, 0.72, (0.0, ty, face - 0.015), mats["print"],
                      col, "internals", space=1.05)
        t.rotation_euler = (0.0, math.pi, 0.0)
        t.name = "TXT_XIAO_Silk"
        mount(t, "body")
    for pin in AMP_PINS:
        x, y, z = amp_pad(pin)
        pad(f"Amp_{pin}", x, y, z - 0.12, 0.9, 0.9, 0.12, "lid")
    for i, (x, y, z) in enumerate(AMP_SPK_PADS):
        pad(f"Amp_Spk{i}", x, y, z - 0.12, 0.9, 0.9, 0.12, "lid")
    for pin in OLED_PINS:
        x, y, z = oled_pad(pin)
        # gota de estano en la punta del pin, donde se suelda el hilo
        pad(f"OLED_{pin}", x, y, z - 0.20, 0.70, 0.70, 0.40, "lid")
    for pin in GPS_PINS:
        x, y, z = gps_pad(pin)
        pad(f"GPS_{pin}", x, y, z - 0.06, 0.9, 0.9, 0.12, "body")
    for i in range(2):
        for term in ("A", "B"):
            x, y, z = tact_pad(i, term)
            pad(f"Tact{i}_{term}", x, y, z - 0.12, 0.8, 0.8, 0.12, "lid")
    for i, (x, y, z) in enumerate(SW_PADS):
        pad(f"SW_{i}", x, y, z - 0.4, 0.12, 0.8, 0.8, "body")
    for i, (x, y, z) in enumerate(BAT_PADS):
        pad(f"BAT_{i}", x, y, z - 0.4, 0.12, 0.8, 0.8, "body")
    for i, (x, y, z) in enumerate(DRV_PADS):
        pad(f"DRV_{i}", x, y, z - 0.4, 0.8, 0.12, 0.8, "lid")


def build_cables(mats):
    """Mazo real: cada hilo sale de un pad del XIAO y llega al pad de su modulo.

    Los pads del XIAO estan en el canto inferior: el mazo sale a ras de suelo,
    corre por el canalillo de 2,2 mm entre la bateria y la pared, pasa por
    delante del XIAO y sube por las dos columnas de Y=-3 hasta el bus frontal
    de la tapa.
    """
    R, B = mats["cable_red"], mats["cable_black"]
    Wt, G = mats["cable_white"], mats["cable_green"]
    Y, Bl = mats["cable_yellow"], mats["cable_blue"]
    O, Gr = mats["cable_orange"], mats["cable_gray"]
    P, Br, Pk = mats["cable_purple"], mats["cable_brown"], mats["cable_pink"]
    xiao = ("XIAO_ESP32S3",)

    def smooth_wire(name, pts, mat, targets, radius=WIRE_R):
        return wire(name, [tuple(p) for p in catmull(pts, 6)], mat,
                    radius=radius, targets=targets)

    def trunk(pad, lane, dest, targets, mat, name, rise=True, front=True):
        """Pad -> suelo -> canalillo -> columna de Y=-3 -> bus frontal.

        El canalillo mide 2,2 mm (X 15,0..17,2) y el riel de la bateria llega
        hasta X=14,8, asi que el hilo entra al canalillo por delante del riel
        (Y=-21,5) en vez de cruzar la diagonal sobre el soporte.
        """
        sx = 1.0 if pad[0] > 0 else -1.0
        lx, lz = lane
        fx = sx * (10.85 + (0.70 if abs(lx) > 15.75 else 0.0))
        pts = [pad,
               (sx * 10.40, pad[1], 2.40),
               (fx, pad[1], lz + 0.90)]
        if front:
            pts += [(fx, -21.90, lz),
                    (lx, -21.50, lz),
                    (lx, -6.40, lz),
                    (lx, -5.00, lz)]
        else:
            pts += [(fx, -2.00, lz)]
        if rise:
            pts += [(lx, -4.60, lz),
                    (lx, -3.50, lz + 0.55),
                    (lx, -3.05, lz + 2.40),
                    (lx, -3.00, 9.00),
                    (lx, -3.00, 14.50),
                    (lx, -3.00, 16.55)]
        return smooth_wire(name, pts + list(dest), mat, targets)

    # ---- destinos: cada uno arranca en el bus frontal (Y=-3, Z=16,55) ----
    # El amplificador baja a 14,70: los hilos corren por debajo de su canto
    # derecho (X=8,4) y suben a los pads desde abajo, sin atravesar el PCB.
    AMP_LANE = {"BCLK": (9.10, 14.20), "LRC": (9.42, 14.20),
                "DIN": (9.74, 14.20), "SD": (9.10, 14.00),
                "GND": (9.42, 14.00), "Vin": (9.74, 14.00)}

    def amp_dest(pin):
        x, z = AMP_LANE[pin]
        px, py, pz = amp_pad(pin)
        return [(x, -2.30, 15.45), (x, -1.40, z + 0.35),
                (x, py - 1.80, z), (x - 0.35, py - 0.55, z + 0.05),
                (px + 0.02, py, pz)]

    def oled_dest(pin):
        """Bus frontal -> punta recortada del pin del OLED (Z=16,60).

        El driver del altavoz (Y -8,8..-0,8, Z 17,20..20,10) obliga a pasar
        por debajo hasta Y=-0,8; despues el hilo ya puede subir a la punta.
        Todo el tramo va a 16,55 (el techo del pasillo sobre la celda): a esa
        cota el hilo pasa a 0,05 de los travesanos de la bandeja y sube a la
        punta justo tras ellos.
        """
        px, py, pz = oled_pad(pin)
        return [(10.0, -3.30, 16.55), (px + 2.60, -1.30, 16.55),
                (px + 1.00, 0.20, 16.55), (px, py - 0.40, 16.55),
                (px, py, pz)]

    def mic_dest(pin):
        """Bus frontal -> cara de soldadura del INMP441.

        Los pads miran hacia la cara; el hilo llega por el costado, sube fuera
        del PCB y corre por encima hasta el pad.  Los pads de la columna
        derecha (L/R, GND) pasan por debajo del PCB, entre la lata y el canto.
        """
        px, py, pz = mic_pad(pin)
        # El tramo Y -3..-9,1 va a 16,55 bajo el driver; recien despues sube.
        lead = [(-15.35, -4.20, 16.55), (-10.60, -5.60, 16.55),
                (-9.50, -9.60, 16.55), (-9.50, -12.20, 17.60),
                (-9.50, -14.00, 18.20)]
        if pin == "WS":
            return lead + [(-8.6, -14.55, 19.05), (0.0, -14.55, 19.05),
                           (px, py, pz)]
        if pin == "VDD":
            return lead + [(-9.5, -25.45, 17.20), (-8.6, -25.45, 19.05),
                           (0.0, -25.45, 19.05), (px, py, pz)]
        if pin == "L/R":
            # por debajo del PCB y giro en y=-15,2, fuera del tactil (y<=-16);
            # sube por x=8,1, fuera del zocalo del micro.
            return lead + [(-9.5, -15.20, 16.90), (8.1, -15.20, 16.90),
                           (8.1, -15.20, 19.05), (px, py, pz)]
        if pin == "GND":
            return lead + [(-9.5, py, 16.90), (8.1, py, 16.90),
                           (8.1, py, 19.05), (px, py, pz)]
        # SCK y SD: por el costado izquierdo y por encima del PCB
        return lead + [(-9.5, py, 18.30), (-8.6, py, 19.05),
                       (px, py, pz)]

    def gps_dest(pin):
        """Suelo -> pad del GPS por delante del modulo, sin pisar el PCB.

        El modulo apoya la lata en el piso (1,80) y su PCB acaba a 5,20; el
        hilo sube por delante (Y<6,25) y cae sobre el pad desde arriba.
        """
        px, py, pz = gps_pad(pin)
        return [(px, -1.60, 2.90), (px, 3.20, 3.40), (px, 5.40, 4.40),
                (px, 5.90, 5.55), (px, py, pz)]

    # ---- carriles del canalillo (x, z): 2 columnas dentro de X 15..17,2 ----
    # Los pads mas cercanos al USB (D0..D3, GND, 3V3) van por la columna
    # interior; los del fondo (D4..D6, D7..D9) por la exterior.  Asi el mazo
    # no se cruza al entrar al canalillo.
    RIGHT = {"D0": (15.45, 2.55), "D1": (15.45, 3.17),
             "D2": (15.45, 3.79), "D3": (15.45, 4.41),
             "D4": (16.05, 2.55), "D5": (16.05, 3.17),
             "D6": (16.05, 3.79)}
    LEFT = {"GND": (-15.45, 2.55), "3V3": (-15.45, 3.17),
            "D10": (-15.45, 3.79), "D9": (-16.05, 2.55),
            "D8": (-16.05, 3.17), "D7": (-16.05, 3.79)}

    # El bus va a 16,55: por debajo del altavoz (17,20) y por encima de la
    # celda (16,10) y su etiqueta (16,14).  Ese es el unico pasillo que cruza
    # de lado a lado sin tocar el driver.
    JL3 = (-15.35, -2.90, 16.55)
    JLG = (-15.35, -2.95, 16.55)
    JR3 = (15.35, -2.90, 16.55)
    JRG = (15.35, -2.95, 16.55)

    # ---------------- borde derecho del XIAO: D0..D6 ----------------
    trunk(xiao_pad(1, "D0"), RIGHT["D0"], amp_dest("BCLK"),
          xiao + ("MAX98357A",), G, "Cable_Amp_BCLK")
    trunk(xiao_pad(1, "D1"), RIGHT["D1"], amp_dest("LRC"),
          xiao + ("MAX98357A",), Wt, "Cable_Amp_LRC")
    trunk(xiao_pad(1, "D3"), RIGHT["D3"], amp_dest("DIN"),
          xiao + ("MAX98357A",), O, "Cable_Amp_DIN")
    trunk(xiao_pad(1, "D4"), RIGHT["D4"], oled_dest("SDA"),
          xiao + ("OLED_096",), Bl, "Cable_OLED_SDA")
    trunk(xiao_pad(1, "D5"), RIGHT["D5"], oled_dest("SCK"),
          xiao + ("OLED_096",), Y, "Cable_OLED_SCL")
    # D2 = GPIO3: los dos tactiles en paralelo (A a GPIO, B a GND).  El hilo
    # baja a 14,85 para pasar bajo el pad (15,16) y corre a X=7,6, separado de
    # la cara del tactil (8,2); un puente corto llega al pad de cada uno.
    trunk(xiao_pad(1, "D2"), RIGHT["D2"],
          [(13.5, -3.20, 16.60), (7.90, -3.20, 16.20),
           (7.60, -4.00, 15.80), (7.60, -7.00, 14.85),
           (7.60, -19.00, 14.85), (7.95, -19.00, 15.16)],
          xiao + ("Tact_0", "Tact_1"), Gr, "Cable_Btn_Sig")
    smooth_wire("Cable_Btn_Jumper",
                [tact_pad(0, "A"), (7.75, -7.00, 15.00),
                 (7.60, -7.00, 14.85)], Gr, ("Tact_0",))
    # D6 = TX -> GPS RX; corre por el suelo y sube por delante del GPS
    trunk(xiao_pad(1, "D6"), RIGHT["D6"],
          gps_dest("RX"),
          xiao + ("GPS_ATGM336H",), Gr, "Cable_GPS_RX", rise=False,
          front=False)

    # ---------------- borde izquierdo: 3V3, GND, D7..D10 ----------------
    trunk(xiao_pad(-1, "3V3"), LEFT["3V3"], [JL3], xiao, R, "Cable_3V3")
    trunk(xiao_pad(-1, "GND"), LEFT["GND"], [JLG], xiao, B, "Cable_GND")
    trunk(xiao_pad(-1, "D8"), LEFT["D8"], mic_dest("SCK"),
          xiao + ("INMP441",), Pk, "Cable_Mic_SCK")
    trunk(xiao_pad(-1, "D9"), LEFT["D9"], mic_dest("WS"),
          xiao + ("INMP441",), Br, "Cable_Mic_WS")
    trunk(xiao_pad(-1, "D10"), LEFT["D10"], mic_dest("SD"),
          xiao + ("INMP441",), P, "Cable_Mic_SD")
    # D7 = RX <- GPS TX
    trunk(xiao_pad(-1, "D7"), LEFT["D7"],
          gps_dest("TX"),
          xiao + ("GPS_ATGM336H",), Br, "Cable_GPS_TX", rise=False,
          front=False)

    # ---- 3V3: micro, GPS y cruce al lado derecho (amp + OLED) ----
    smooth_wire("Cable_3V3_Mic", [JL3] + mic_dest("VDD"), R, ("INMP441",))
    smooth_wire("Cable_3V3_GPS",
                [(-16.05, -5.00, 2.55)] + gps_dest("VCC"),
                R, ("GPS_ATGM336H",))
    smooth_wire("Cable_3V3_Cross",
                [JL3, (-13.0, -1.50, 16.55), (0.0, -1.50, 16.55),
                 (6.0, -1.60, 16.55), (10.0, -2.10, 16.55), JR3], R, ())
    smooth_wire("Cable_3V3_AmpVin", [JR3] + amp_dest("Vin"), R,
                ("MAX98357A",))
    smooth_wire("Cable_3V3_AmpSD", [JR3] + amp_dest("SD"), R,
                ("MAX98357A",))
    smooth_wire("Cable_3V3_OLED", [JR3] + oled_dest("VDD"), R, ("OLED_096",))

    # ---- GND: micro (y L/R), GPS, cruce al lado derecho ----
    smooth_wire("Cable_GND_Mic", [JLG] + mic_dest("GND"), B, ("INMP441",))
    smooth_wire("Cable_GND_MicLR", [JLG] + mic_dest("L/R"), B, ("INMP441",))
    smooth_wire("Cable_GND_GPS",
                [(-16.05, -5.00, 3.17)] + gps_dest("GND"),
                B, ("GPS_ATGM336H",))
    smooth_wire("Cable_GND_Cross",
                [JLG, (-13.0, -2.50, 16.55), (0.0, -2.50, 16.55),
                 (6.0, -2.60, 16.55), (10.0, -2.90, 16.55), JRG], B, ())
    smooth_wire("Cable_GND_Amp", [JRG] + amp_dest("GND"), B, ("MAX98357A",))
    smooth_wire("Cable_GND_OLED", [JRG] + oled_dest("GND"), B, ("OLED_096",))
    smooth_wire("Cable_GND_Tact",
                [JRG, (14.80, -3.90, 16.30), (14.80, -6.20, 15.05),
                 (14.80, -7.00, 14.85), (14.80, -19.00, 14.85),
                 (14.45, -19.00, 15.16)], B, ("Tact_0", "Tact_1"))
    smooth_wire("Cable_GND_TactJumper",
                [tact_pad(0, "B"), (14.65, -7.00, 15.00),
                 (14.80, -7.00, 14.85)], B, ("Tact_0",))

    # ---- altavoz: pads del amp (borde izq.) al driver, ambos en la tapa ----
    # El tramo central va a 16,30: por debajo de la bandeja de apriete, que
    # ocupa 16,70..17,15.  Las dos subidas a los pads entran por el hueco del
    # travesano delantero (SPK_WIRE_GAP) y el resto del recorrido pasa por el
    # centro abierto del marco.
    for i, (px, py, pz) in enumerate(AMP_SPK_PADS):
        x = -10.55 - 0.55 * i
        ax = DRV_PADS[i][0] + 0.8
        smooth_wire(f"Cable_Spk_{i}",
                    [(px, py, pz), (x, py - 0.40, 15.50), (x, 6.00, 16.55),
                     (x, 1.60, 16.55), (x + 0.9, -6.60, 16.55),
                     (ax, -8.60, 16.55), (ax, -9.55, 16.55),
                     (ax, -9.55, 17.55), (DRV_PADS[i][0], -8.86, 17.55)],
                    R if i == 0 else B, ("MAX98357A", "Speaker_Driver"))

    # ---- pigtail U.FL: antena ceramica -> conector del GPS ----
    # Aterriza en la franja delantera del PCB de la antena (Y 20,5..21,9) y
    # sube por delante del modulo GPS hasta la boca del U.FL, sin pisar el PCB.
    smooth_wire("Cable_GPS_Ant",
                [(-8.20, 20.90, 3.02), (-8.40, 20.30, 4.20),
                 (-8.20, 19.90, 5.80), (-7.20, 19.30, 6.30),
                 (-6.20, 18.40, 6.70), (-5.90, 17.90, 6.72)],
                B, ("GPS_UFL", "GPS_Antenna"), radius=0.42)

    # ---- bateria e interruptor (solo cuerpo) ----
    # Las patas salen por la cara +X de la celda (6,5).  El positivo baja por
    # el costado derecho y cruza por detras de la bateria (Y=4,5, el pasillo
    # entre la celda y el GPS) hasta el interruptor; el negativo baja por el
    # mismo costado, cruza por detras del XIAO y llega a su pad GND.
    bpos, bneg = BAT_PADS
    smooth_wire("Cable_Bat_Pos",
                [bpos, (7.30, -5.20, 13.40), (7.30, -4.40, 12.00),
                 (7.30, -4.40, 3.00), (7.30, 3.60, 2.35),
                 (6.00, 4.55, 2.10), (0.0, 4.60, 2.10),
                 (-8.0, 4.60, 2.10), (-11.00, 3.60, 2.30),
                 (-11.60, 3.80, 2.45), SW_PADS[0]],
                R, xiao + ("LiPo_902030", "Switch_Body"))
    smooth_wire("Cable_Sw_5V",
                [SW_PADS[1], (-11.60, 6.60, 2.80), (-10.60, 4.60, 3.60),
                 (-9.90, 2.60, 3.90), (-9.90, -3.00, 3.60),
                 (-13.00, -9.00, 3.00), (-11.50, -15.00, 2.50),
                 (-10.00, -20.00, 2.20), (-9.60, -23.00, 2.15),
                 xiao_pad(-1, "5V")],
                R, xiao + ("Switch_Body",))
    smooth_wire("Cable_Bat_Neg",
                [bneg, (7.40, -7.10, 13.30), (9.30, -6.20, 12.00),
                 (9.50, -5.00, 3.20), (9.70, -4.70, 2.55),
                 (9.40, -4.60, 2.25), (0.0, -4.60, 2.25),
                 (-10.50, -4.60, 2.25), (-10.50, -19.00, 2.15),
                 xiao_pad(-1, "GND")],
                B, xiao + ("LiPo_902030",))

    # ---- BAT+ / BAT- en paralelo con 5V y GND (bajo el XIAO) ----
    # El pocket del piso deja 1,30 mm libres bajo el PCB.  Cada hilo se
    # empalma en el cable de potencia a la altura del canto trasero del XIAO,
    # baja al fondo del pocket y corre a 1,36 (apoyado en el piso, sin flotar)
    # hasta el pad BAT de la cara inferior; en el pad sube a 1,94 para tocar el
    # cobre (2,24) sin entrar en la placa.  El positivo va por X=5,4 y el
    # negativo por X=6,2 hasta pasar el pad positivo: asi no se cruzan bajo el
    # PCB.  La bajada cruza el canto del pocket (Z 1,80) todavia por encima de
    # el, y recien se apoya cuando ya esta dentro.
    smooth_wire("Cable_Bat_Pos_BAT",
                [(7.30, -4.40, 3.00), (6.60, -4.55, 2.60),
                 (5.90, -4.80, 2.10), (5.60, -5.20, 1.90),
                 (5.50, -6.20, 1.45), (5.45, -8.00, 1.36),
                 (5.40, -12.00, 1.36), (5.20, -15.00, 1.36),
                 (4.80, -15.80, 1.70), xiao_bat_tip("pos")],
                R, xiao + ("Switch_Body",))
    smooth_wire("Cable_Bat_Neg_BAT",
                [(9.50, -4.60, 2.15), (9.40, -6.20, 1.85),
                 (9.20, -8.00, 1.45), (8.40, -9.20, 1.36),
                 (7.00, -10.40, 1.36), (6.20, -13.00, 1.36),
                 (6.20, -17.00, 1.36), (5.40, -17.70, 1.60),
                 (4.80, -17.90, 1.80), xiao_bat_tip("neg")],
                B, xiao + ("LiPo_902030",))

    # --- cinta Kapton: dos vueltas a los lados de la bateria ---
    # La celda llega a Y=-27, pero en las esquinas traseras la pared interior
    # se cierra (R7,2): a X=13,54 el limite es Y=-26,27.  La cinta se queda en
    # el tramo recto de la celda (Y -23..-5) para no salirse de la cavidad.
    for tag, tx in (("-", -13.54), ("+", 6.54)):
        tape = block(f"Kapton_Bat_{tag}", 0.08, 18.0, 5.0,
                     tx, -14.0, 9.18, collection("06_Internos"),
                     "internals")
        assign(tape, mats["kapton"])
        mount(tape, "body")



def check_cable_routes(tolerance=0.02):
    """Ningun cable atraviesa un componente ni la pared interior."""
    inner_w = W - 2 * WALL
    inner_l = L - 2 * WALL
    hw, hl, r = inner_w / 2, inner_l / 2, R_IN
    boxes = {}

    def add_box(ob):
        pts = [ob.matrix_world @ Vector(c) for c in ob.bound_box]
        boxes[ob.name] = (Vector((min(p.x for p in pts),
                                  min(p.y for p in pts),
                                  min(p.z for p in pts))),
                          Vector((max(p.x for p in pts),
                                  max(p.y for p in pts),
                                  max(p.z for p in pts))))

    for ob in PARTS["internals"]:
        if ob.type != "MESH" or ob.name.startswith(
                ("Pad_", "Kapton", "LiPo_Label", "OLED_Pin")):
            continue
        add_box(ob)
    # los postes de la bateria y los del XIAO son del cuerpo, pero el mazo
    # pasa a su lado y bajo la placa
    for ob in bpy.data.objects:
        if ob.type == "MESH" and ob.name.startswith(
                ("Battery_Post_", "Xiao_Post_")):
            add_box(ob)
    problems = []
    count = 0
    for ob in PARTS["internals"]:
        if ob.type != "CURVE" or not ob.get("flex"):
            continue
        count += 1
        skip = set(ob.get("targets", []))
        path = [Vector(p) for p in ob["path"]]
        samples = []
        for a, b in zip(path, path[1:]):
            d = (b - a).length
            n = max(1, int(d / 0.4))
            samples += [a.lerp(b, k / n) for k in range(n)] + [b]
        for p in samples[1:-1]:
            dx = max(abs(p.x) - (hw - r), 0.0)
            dy = max(abs(p.y) - (hl - r), 0.0)
            wall = math.hypot(dx, dy) - r
            if wall > -WIRE_R - tolerance:
                problems.append((ob.name, "pared", wall, p))
                break
            # el piso baja en el pocket: fuera del rebaje el hilo no puede
            # hundirse; sobre el canto se apoya en la esquina (piso 1,80)
            sd = pocket_dist(p.x, p.y)
            if sd >= 0.0:
                z_min = POCKET_Z0 + WIRE_R - 0.05
            elif sd > -WIRE_R:
                z_min = FLOOR + math.sqrt(WIRE_R ** 2 - sd ** 2) - 0.05
            else:
                z_min = FLOOR + WIRE_R - 0.05
            if not (z_min < p.z < H - 1.5 - WIRE_R):
                problems.append((ob.name, "altura", p.z, p))
                break
            hit = None
            for name, (mn, mx) in boxes.items():
                if name in skip or name.startswith("Kapton"):
                    continue
                if (mn.x - WIRE_R < p.x < mx.x + WIRE_R
                        and mn.y - WIRE_R < p.y < mx.y + WIRE_R
                        and mn.z - WIRE_R < p.z < mx.z + WIRE_R):
                    hit = name
                    break
            if hit:
                problems.append((ob.name, hit, 0.0, p))
                break
    for name, what, d, p in problems:
        print(f"[cables] {name} toca {what} en "
              f"({p.x:.1f},{p.y:.1f},{p.z:.1f}) d={d:+.2f}")
    if problems:
        raise SystemExit("Cables fuera de sitio: revisar rutas")
    print(f"[cables] OK: {count} hilos sin tocar componentes ni pared")


def check_xiao_underpass(tolerance=0.10):
    """Los hilos BAT+ / BAT- pasan bajo el PCB con holgura y sin tocar postes."""
    gap = XIAO_BOTTOM - POCKET_Z0
    need = 2 * WIRE_R + tolerance
    if gap < need:
        raise SystemExit(f"[under] hueco {gap:.2f} < {need:.2f} bajo el XIAO")
    hx, hy = XIAO_W / 2, XIAO_L / 2
    posts = ((7.20, XIAO_C[1] - XIAO_L / 2 + 1.0),
             (7.20, XIAO_C[1] + XIAO_L / 2 - 1.0),
             (-7.20, XIAO_C[1] - XIAO_L / 2 + 1.0),
             (-7.20, XIAO_C[1] + XIAO_L / 2 - 1.0))
    wires = {"Cable_Bat_Pos_BAT": "pos", "Cable_Bat_Neg_BAT": "neg"}
    problems = []
    min_gap = 99.0
    min_post = 99.0
    count = 0
    for name, side in wires.items():
        ob = bpy.data.objects.get(name)
        if ob is None or ob.type != "CURVE":
            problems.append((name, "no existe", 0.0, None))
            continue
        count += 1
        tip = Vector(xiao_bat_tip(side))
        path = [Vector(p) for p in ob["path"]]
        samples = []
        for a, b in zip(path, path[1:]):
            d = (b - a).length
            n = max(1, int(d / 0.20))
            samples += [a.lerp(b, k / n) for k in range(n)] + [b]
        under_n = 0
        for p in samples:
            # bajo el contorno de la placa (con el radio del hilo de margen)
            under = (abs(p.x) <= hx + WIRE_R
                     and abs(p.y - XIAO_C[1]) <= hy + WIRE_R)
            near_tip = (Vector((p.x, p.y)) - Vector(tip[:2])).length <= 1.60
            if under:
                under_n += 1
                top = p.z + WIRE_R
                if near_tip:
                    # el hilo sube a tocar el cobre del pad (2,24)
                    if top > XIAO_BOTTOM + 0.02:
                        problems.append((name, "pad", top - XIAO_BOTTOM, p))
                        break
                else:
                    g = XIAO_BOTTOM - top
                    min_gap = min(min_gap, g)
                    if g < tolerance - 1e-6:
                        problems.append((name, "PCB", g, p))
                        break
            # no hundirse en el piso del pocket
            if (abs(p.x) <= POCKET_X - WIRE_R
                    and POCKET_Y0 + WIRE_R <= p.y <= POCKET_Y1 - WIRE_R
                    and p.z - WIRE_R < POCKET_Z0 - 0.05):
                problems.append((name, "piso pocket", p.z - WIRE_R, p))
                break
            # postes: radio 0,90 + hilo + 0,20 de holgura
            if p.z - WIRE_R < XIAO_BOTTOM and p.z + WIRE_R > POCKET_Z0:
                hit = None
                for px, py in posts:
                    d = math.hypot(p.x - px, p.y - py)
                    min_post = min(min_post, d)
                    if d < 0.90 + WIRE_R + 0.20 - 1e-6:
                        hit = (px, py, d)
                        break
                if hit:
                    problems.append(
                        (name, f"poste ({hit[0]:+.1f},{hit[1]:.1f})",
                         hit[2], p))
                    break
        else:
            if under_n == 0:
                problems.append((name, "no pasa bajo el PCB", 0.0, None))
    for name, what, d, p in problems:
        loc = "" if p is None else f" en ({p.x:.2f},{p.y:.2f},{p.z:.2f})"
        print(f"[under] {name} toca {what}{loc} d={d:+.2f}")
    if problems:
        raise SystemExit("Cables bajo el XIAO fuera de sitio")
    print(f"[under] OK: {count} hilos bajo el XIAO, holgura {min_gap:.2f} mm, "
          f"postes a {min_post:.2f} mm")


def report_packing():
    """Volumen de cavidad frente a componentes y cables."""
    area = (W - 2 * WALL) * (L - 2 * WALL) - (4 - math.pi) * R_IN ** 2
    cavity = area * ((16.0 - FLOOR) + (H - 1.5 - (BODY_H - 0.2)))
    comp = 0.0
    rows = []
    for ob in PARTS["internals"]:
        if ob.type != "MESH" or ob.name.startswith(("Pad_", "Kapton")):
            continue
        bm = bmesh.new()
        bm.from_mesh(ob.data)
        vol = abs(bm.calc_volume(signed=True))
        bm.free()
        comp += vol
        dim = ob.dimensions
        rows.append((ob.name, dim.x, dim.y, dim.z, vol))
    cable = 0.0
    for ob in PARTS["internals"]:
        if ob.type != "CURVE" or not ob.get("flex"):
            continue
        path = [Vector(p) for p in ob["path"]]
        length = sum((b - a).length for a, b in zip(path, path[1:]))
        cable += length * math.pi * WIRE_R ** 2
    free = cavity - comp - cable
    print(f"[packing] cavidad {cavity:.0f} mm3, componentes {comp:.0f} mm3 "
          f"({100 * comp / cavity:.1f}%), cables {cable:.0f} mm3 "
          f"({100 * cable / cavity:.1f}%), libre {free:.0f} mm3 "
          f"({100 * free / cavity:.1f}%)")
    for name, dx, dy, dz, vol in sorted(rows, key=lambda r: -r[4])[:12]:
        print(f"[packing]   {name:16s} {dx:5.1f} x {dy:5.1f} x {dz:4.1f} "
              f"= {vol:6.0f} mm3")


def check_seam(body, lid, tolerance=0.5):
    """Comprueba que la lengueta cabe en la ranura y que los labios son imprimibles."""
    groove = (SEAM_OFF, SEAM_OFF + SEAM_W)
    tongue = (SEAM_OFF + SEAM_GAP, SEAM_OFF + SEAM_GAP + TONGUE_W)
    outer_lip = SEAM_OFF
    inner_lip = WALL - (SEAM_OFF + SEAM_W)
    play_out = tongue[0] - groove[0]
    play_in = groove[1] - tongue[1]
    play_down = SEAM_BOT_GAP
    problems = []
    for label, value in (("labio exterior", outer_lip),
                         ("labio interior", inner_lip),
                         ("juego radial exterior", play_out),
                         ("juego radial interior", play_in),
                         ("juego en el fondo", play_down)):
        if value < 0.10:
            problems.append(f"{label} = {value:.2f} mm")
    if problems:
        raise SystemExit("Junta demasiado ajustada: " + ", ".join(problems))

    # comprobacion real: tapa y cuerpo no deben intersecarse en la junta
    probe = lid.copy()
    probe.data = lid.data.copy()
    bpy.context.scene.collection.objects.link(probe)
    mod = probe.modifiers.new("fit", "BOOLEAN")
    mod.operation = "INTERSECT"
    mod.solver = "EXACT"
    mod.object = body
    base.select_only(probe)
    bpy.context.view_layer.objects.active = probe
    bpy.ops.object.modifier_apply(modifier=mod.name)
    bm = bmesh.new()
    bm.from_mesh(probe.data)
    clash = abs(bm.calc_volume(signed=True))
    bm.free()
    bpy.data.objects.remove(probe, do_unlink=True)
    if clash > tolerance:
        raise SystemExit(f"La lengueta choca con la ranura: {clash:.3f} mm3")

    print(f"[seam] OK: labios {outer_lip:.2f}/{inner_lip:.2f} mm, "
          f"juego radial {play_out:.2f}/{play_in:.2f} mm, "
          f"fondo {play_down:.2f} mm, penetracion "
          f"{SEAM_DEPTH - SEAM_BOT_GAP:.2f} mm, interferencia "
          f"{clash:.3f} mm3")


def check_clearance(tolerance=0.10):
    """Comprueba que ningun componente cruce la pared interior."""
    inner_w = W - 2 * WALL
    inner_l = L - 2 * WALL
    inner_r = R_IN
    half_w = inner_w / 2
    half_l = inner_l / 2
    allowed = ("Switch_Lever", "Switch_Body", "Switch_Slider", "usb_bez",
               "USB_C")
    problems = []
    for ob in bpy.data.objects:
        if ob.type != "MESH" or ob.get("mount") not in ("body", "lid"):
            continue
        if ob.name.startswith(allowed) or ob.name.startswith("Pad_"):
            continue
        mw = ob.matrix_world
        worst = -99.0
        at = None
        for v in ob.data.vertices:
            c = mw @ v.co
            if not (FLOOR - 0.5 < c.z < H - 1.0):
                continue
            dx = max(abs(c.x) - (half_w - inner_r), 0.0)
            dy = max(abs(c.y) - (half_l - inner_r), 0.0)
            d = math.hypot(dx, dy) - inner_r
            if d > worst:
                worst, at = d, c
        if worst > tolerance:
            problems.append((ob.name, worst, at))
    for name, d, c in sorted(problems, key=lambda r: -r[1]):
        loc = "" if c is None else f"({c.x:.1f},{c.y:.1f},{c.z:.1f})"
        print(f"[clearance] {name}: {d:+.2f} mm fuera {loc}")
    if problems:
        raise SystemExit("Componentes fuera de la cavidad: revisar clearance")
    print("[clearance] OK: todos los componentes dentro de la cavidad")


def check_component_overlap(tolerance=0.05):
    """Comprueba que los componentes internos no se interpenetren."""
    intended = {
        frozenset(("USB_C", "XIAO_ESP32S3")),
        frozenset(("USB_C", "USB_C_Contact")),
        frozenset(("Switch_Body", "Switch_Lever")),
        frozenset(("Switch_Slider", "Switch_Lever")),
    }
    boxes = {}
    for ob in PARTS["internals"]:
        if ob.type != "MESH" or ob.name.startswith(("Pad_", "Kapton")):
            continue
        pts = [ob.matrix_world @ Vector(c) for c in ob.bound_box]
        boxes[ob.name] = (
            Vector((min(p.x for p in pts), min(p.y for p in pts),
                    min(p.z for p in pts))),
            Vector((max(p.x for p in pts), max(p.y for p in pts),
                    max(p.z for p in pts))),
        )
    names = sorted(boxes)
    problems = []
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            if frozenset((a, b)) in intended:
                continue
            (amn, amx), (bmn, bmx) = boxes[a], boxes[b]
            ox = min(amx.x, bmx.x) - max(amn.x, bmn.x)
            oy = min(amx.y, bmx.y) - max(amn.y, bmn.y)
            oz = min(amx.z, bmx.z) - max(amn.z, bmn.z)
            if ox > tolerance and oy > tolerance and oz > tolerance:
                problems.append((a, b, ox, oy, oz))
    for a, b, ox, oy, oz in problems:
        print(f"[overlap] {a} x {b}: ({ox:.2f},{oy:.2f},{oz:.2f})")
    if problems:
        raise SystemExit("Componentes internos solapados: revisar placement")
    print(f"[overlap] OK: {len(names)} componentes sin choques")


def check_usb_fit(tolerance=0.10):
    """Verifica que un cable USB-C real entra por el hueco y no choca.

    El conector queda 0,10 mm dentro de la pared, asi que lo que cruza la
    pared es el casquillo metalico del cable (8,25 x 2,40).  El alivio
    (9,00 x 3,30) y el sobre-molde (12,20 x 6,40) arrancan 4 mm fuera del
    bisel.  Se comprueba el juego en el bisel y en el hueco, que el hueco no
    tape la carcasa del conector, que el casquillo entre hasta el fondo y que
    no toque la bateria.
    """
    outer_face = -L / 2
    inner_face = -L / 2 + WALL
    bezel_outer = outer_face - 0.60
    hole_back = -L / 2 + WALL + 0.40
    problems = []

    if hole_back < inner_face + 0.10:
        problems.append("el hueco no atraviesa la pared")

    if abs(USB_ZC - (USB_Z0 + USB_C_H / 2)) > 1e-6:
        problems.append("boca del conector descentrada")

    # el hueco no puede tapar la carcasa del conector (8,94 x 3,26)
    for label, play in (("carcasa X", USB_HOLE_W - USB_C_W),
                        ("carcasa Z", USB_HOLE_H - USB_C_H)):
        if play < 0.30:
            problems.append(f"{label}: solo {play:+.2f} mm")

    # lo que cruza el hueco es el casquillo metalico del cable, no la goma
    plays = (
        ("bisel X", USB_BEZEL_IN_W - PLUG_W),
        ("bisel Z", USB_BEZEL_IN_H - PLUG_H),
        ("hueco X", USB_HOLE_W - PLUG_W),
        ("hueco Z", USB_HOLE_H - PLUG_H),
    )
    for label, play in plays:
        if play < tolerance:
            problems.append(f"{label}: juego {play:+.2f} mm")

    depth = USB_C_D - PLUG_L
    if depth < 0.50:
        problems.append(f"el casquillo no entra: sobran {depth:+.2f} mm")

    # el cuerpo del conector debe quedar DENTRO de la caja, con la boca a
    # 0,05..0,30 mm de la pared interior (nunca hacia fuera)
    front_gap = USB_Y0 - inner_face
    if not 0.05 <= front_gap <= 0.30:
        problems.append(f"boca a {front_gap:+.2f} mm de la pared interior")
    usb_rear = USB_Y0 + USB_C_D
    if usb_rear > XIAO_C[1] + XIAO_L / 2:
        problems.append(f"el conector sobresale por detras: {usb_rear:+.2f}")
    if USB_Y0 < inner_face:
        problems.append(f"el conector sale de la caja: boca en {USB_Y0:+.2f}")

    # el alivio y el sobre-molde arrancan en la culata del casquillo, que
    # queda fuera del bisel: por el hueco solo pasa metal
    boot_front = USB_Y0 - PLUG_L
    boot_gap = bezel_outer - boot_front
    if boot_gap < 0.50:
        problems.append(f"el alivio entra en el bisel: {boot_gap:+.2f} mm")

    # el casquillo no debe tocar nada mas que el propio conector
    shell = (Vector((-PLUG_W / 2, USB_Y0, USB_ZC - PLUG_H / 2)),
             Vector((PLUG_W / 2, USB_Y0 + PLUG_L, USB_ZC + PLUG_H / 2)))
    for ob in PARTS["internals"]:
        if ob.type != "MESH" or ob.name.startswith(
                ("Pad_", "Kapton", "USB_C")):
            continue
        pts = [ob.matrix_world @ Vector(c) for c in ob.bound_box]
        mn = Vector((min(p.x for p in pts), min(p.y for p in pts),
                     min(p.z for p in pts)))
        mx = Vector((max(p.x for p in pts), max(p.y for p in pts),
                     max(p.z for p in pts)))
        if (shell[0].x < mx.x and shell[1].x > mn.x
                and shell[0].y < mx.y and shell[1].y > mn.y
                and shell[0].z < mx.z and shell[1].z > mn.z):
            problems.append(f"el casquillo toca {ob.name}")

    if problems:
        for p in problems:
            print(f"[usb] {p}")
        raise SystemExit("El cable USB-C no entra: revisar el puerto")

    print(f"[usb] OK: conector {USB_C_W:.2f} x {USB_C_D:.2f} x "
          f"{USB_C_H:.2f} en Y {USB_Y0:+.2f}..{usb_rear:+.2f} (boca a "
          f"{front_gap:.2f} mm de la pared), hueco "
          f"{USB_HOLE_W:.2f} x {USB_HOLE_H:.2f} pasante (pared {WALL:.1f}, "
          f"{0.5 * (USB_HOLE_W - USB_C_W):.2f} mm por lado sobre la carcasa), "
          f"bisel interior {USB_BEZEL_IN_W:.2f} x {USB_BEZEL_IN_H:.2f}, "
          f"casquillo {PLUG_W:.2f} x {PLUG_H:.2f} con juego "
          f"{plays[2][1] / 2:.2f}/{plays[3][1] / 2:.2f} mm por lado, "
          f"{PLUG_L:.1f}/{USB_C_D:.1f} mm dentro y alivio a "
          f"{boot_gap:.2f} mm del bisel")


def check_switch_fit(tolerance=0.05):
    """Hueco de 2 mm por lado, pista rehundida y paddle sin asomo.

    Mide el hueco pasante y la pista con rayos sobre la malla del cuerpo, no
    contra las constantes: si la pared o el corte cambian, falla aqui.
    """
    from mathutils.bvhtree import BVHTree
    body = bpy.data.objects.get("Fifo_Body")
    lever = bpy.data.objects.get("Switch_Lever")
    sw = bpy.data.objects.get("Switch_Body")
    cap = bpy.data.objects.get("Switch_Slider")
    if not all((body, lever, sw, cap)):
        raise SystemExit("[sw] faltan cuerpo, palanca, switch o paddle")
    bvh = BVHTree.FromObject(body, bpy.context.evaluated_depsgraph_get())

    def ray(origin, direction):
        loc, _, _, _ = bvh.ray_cast(Vector(origin), Vector(direction))
        if loc is None:
            raise SystemExit(f"[sw] rayo sin impacto desde {origin}")
        return loc

    problems = []
    p = ray((-W / 2 + 1.4, SW_C[1], 4.8), (0, 1, 0))
    slot_w = 2 * (p.y - SW_C[1])
    p = ray((-W / 2 + 1.4, SW_C[1], 4.8), (0, 0, 1))
    slot_h = 2 * (p.z - 4.8)
    p = ray((-W / 2 + 0.5, SW_C[1] + 3.0, 4.8), (0, 1, 0))
    track_w = 2 * (p.y - SW_C[1])

    def bbox(ob):
        pts = [ob.matrix_world @ Vector(c) for c in ob.bound_box]
        return (Vector((min(q.x for q in pts), min(q.y for q in pts),
                        min(q.z for q in pts))),
                Vector((max(q.x for q in pts), max(q.y for q in pts),
                        max(q.z for q in pts))))

    sw_lo, sw_hi = bbox(sw)
    lv_lo, lv_hi = bbox(lever)
    cp_lo, cp_hi = bbox(cap)
    sw_w = sw_hi.y - sw_lo.y
    margin = (sw_w - slot_w) / 2
    if abs(margin - SW_SIDE_MARGIN) > tolerance:
        problems.append(f"margen del hueco {margin:.2f} mm por lado "
                        f"(esperado {SW_SIDE_MARGIN:.2f})")
    if abs(slot_h - SW_SLOT_H) > tolerance:
        problems.append(f"alto del hueco {slot_h:.2f} mm")
    out = sw_lo.x - lv_lo.x
    if abs(out - SW_LEVER_OUT) > tolerance:
        problems.append(f"la palanca sobresale {out:.2f} mm del cuerpo")
    recess = cp_lo.x - (-W / 2)
    if not -0.01 <= recess <= 0.15:
        problems.append(f"el paddle asoma {-recess:.2f} mm de la cara")
    travel = (slot_w - (lv_hi.y - lv_lo.y)) / 2
    if travel < 1.0 - 1e-6:
        problems.append(f"recorrido de la palanca {travel:.2f} mm por lado")
    side = (track_w - (cp_hi.y - cp_lo.y)) / 2 - travel
    if not 0.10 <= side <= 0.30:
        problems.append(f"holgura del paddle en la pista {side:.2f} mm")
    zgap = (slot_h - (lv_hi.z - lv_lo.z)) / 2
    if zgap < 0.20 - 1e-6:
        problems.append(f"holgura vertical de la palanca {zgap:.2f} mm")
    for msg in problems:
        print("[sw]", msg)
    if problems:
        raise SystemExit("Ajuste del interruptor fuera de especificacion")
    print(f"[sw] OK: hueco {slot_w:.2f} x {slot_h:.2f} ({margin:.2f} por lado "
          f"del cuerpo de {sw_w:.2f}), pista {track_w:.2f} de "
          f"{SW_TRACK_D:.2f} de fondo, paddle rehundido {recess:.2f}, "
          f"palanca {out:.2f} del cuerpo y recorrido ±{travel:.2f}")


def check_button_fit(tolerance=0.05):
    """La copa del boton debe envolver el pulsador del tactil sin aprisionarlo.

    El 6x6x5 no tiene un teton fino ni un rebaje donde enganchar un clip: su
    pulsador es un cilindro liso de Ø3,5.  La copa lo envuelve con holgura
    radial (conexion por forma, sin friccion), apoya su techo interior en la
    cara del pulsador y deja su borde por encima del cuerpo mas que el
    recorrido del switch.  Se compara contra la malla del pulsador y del
    cuerpo, no contra las constantes, para que un cambio en uno de los dos
    lados falle aqui.
    """
    problems = []
    for i in range(len(BTN_C)):
        act = bpy.data.objects.get(f"Tact_{i}_actuator")
        body = bpy.data.objects.get(f"Tact_{i}")
        if act is None or body is None:
            problems.append(f"Tact_{i} o su pulsador ausentes")
            continue
        pts = [act.matrix_world @ Vector(c) for c in act.bound_box]
        d_act = max(p.x for p in pts) - min(p.x for p in pts)
        act_top = max(p.z for p in pts)
        body_top = max((body.matrix_world @ Vector(c)).z
                       for c in body.bound_box)
        gap = BTN_CUP_IR - d_act / 2
        if not 0.05 <= gap <= 0.15:
            problems.append(
                f"holgura de la copa {gap:.2f} mm sobre pulsador "
                f"Ø{d_act:.2f} (fuera de 0,05..0,15)")
        if abs(act_top - BTN_STEM_Z0) > tolerance:
            problems.append(
                f"el techo de la copa esta a {BTN_STEM_Z0:.2f} y el pulsador "
                f"acaba a {act_top:.2f}")
        engage = BTN_STEM_Z0 - BTN_CUP_Z0
        if engage < 0.15:
            problems.append(f"la copa envuelve solo {engage:.2f} mm")
        rim = BTN_CUP_Z0 - body_top
        if rim < 0.30 - 1e-6:
            problems.append(
                f"el borde de la copa topa a {rim:.2f} mm del cuerpo "
                f"(recorrido del tactil)")
        if BTN_CUP_IR + BTN_CUP_WALL > BTN_SEAT_POCKET / 2 - 0.20:
            problems.append("la copa roza el pozo del asiento")

    lip = BTN_FLANGE_R - BTN_BEZEL_IN_R
    if lip < 0.30:
        problems.append(f"la pestaña no queda retenida: labio {lip:.2f} mm")
    if BTN_BORE_R - BTN_FLANGE_R < 0.05:
        problems.append("la pestaña no entra en el pozo")
    if BTN_FLANGE_R - BTN_BORE_R > 0.30:
        problems.append("la pestaña baila en el pozo")
    groove = BTN_RECESS_R - BTN_BEZEL_R
    if not 0.05 <= groove <= 0.15:
        problems.append(f"holgura del rebaje del aro {groove:.2f} mm")
    skirt = BTN_BEZEL_Z0 - BTN_SKIRT_Z0
    if skirt < 0.40:
        problems.append(f"el faldon del aro entra solo {skirt:.2f} mm")
    if abs(BTN_BEZEL_Z0 - BTN_FLANGE_Z1) > tolerance:
        problems.append("el labio del aro no apoya en la cara de la pestaña")
    if H - BTN_RECESS_Z0 < 0.50:
        problems.append("el rebaje del aro es poco profundo")

    if problems:
        for p in problems:
            print(f"[btn] {p}")
        raise SystemExit("El boton no empuja bien el tactil")

    print(f"[btn] OK: copa Ø{2 * BTN_CUP_IR:.2f} envuelve el pulsador "
          f"Ø{2 * TACT_ACT_R:.2f} (holgura {BTN_CUP_IR - TACT_ACT_R:.2f}, "
          f"envolvencia {BTN_STEM_Z0 - BTN_CUP_Z0:.2f}), techo a "
          f"{BTN_STEM_Z0:.2f} y borde a {BTN_CUP_Z0 - BTN_ACT_Z0:.2f} del "
          f"cuerpo; pestaña Ø{2 * BTN_FLANGE_R:.1f} retenida por el aro "
          f"Ø{2 * BTN_BEZEL_IN_R:.1f} (labio {lip:.2f} mm) y centrada en el "
          f"pozo Ø{2 * BTN_BORE_R:.1f}; aro con faldon de "
          f"{BTN_BEZEL_Z0 - BTN_SKIRT_Z0:.2f} en rebaje de "
          f"{H - BTN_RECESS_Z0:.2f} bajo la cara")


def check_mic_mount(tolerance=0.05):
    """La PCB del INMP441 queda pinzada entre las repisas y la bota.

    La bota ya no apoya en la PCB: se acorto para alojar una arandela de
    espuma.  La PCB baja desde arriba y apoya en dos repisas; los cuatro
    postes y las guias no pueden invadir el disco.
    """
    problems = []
    mic = bpy.data.objects.get("INMP441")
    if mic is None:
        raise SystemExit("[mic] falta el INMP441")
    pts = [mic.matrix_world @ Vector(c) for c in mic.bound_box]
    z0 = min(p.z for p in pts)
    z1 = max(p.z for p in pts)
    if abs(MIC_SHELF_Z1 - z0) > tolerance:
        problems.append(f"repisas a {MIC_SHELF_Z1:.2f} y PCB a {z0:.2f}")
    gap = MIC_BOOT_Z0 - z1
    if not (0.30 <= gap <= 0.45):
        problems.append(f"hueco para espuma {gap:.2f} fuera de 0,30..0,45")
    for y0, y1, tag in ((MIC_POST_Y0, MIC_POST_Y1, "trasero"),
                        (MIC_POST_F0, MIC_POST_F1, "delantero")):
        yc = 0.5 * (y0 + y1)
        half = math.sqrt(max(0.0, (MIC_D / 2) ** 2 -
                             (yc - MIC_C[1]) ** 2))
        if MIC_POST_X0 - half < 0.10:
            problems.append(
                f"poste {tag} a x={MIC_POST_X0:.2f} pisa el PCB "
                f"(medio ancho {half:.2f} en y={yc:.2f})")
    over = MIC_D / 2 - MIC_SHELF_X0
    if over < 0.30:
        problems.append(f"las repisas solo solapan {over:.2f} mm")
    if MIC_GUIDE_X0 - MIC_D / 2 < 0.10:
        problems.append("las guias rozan el canto de la PCB")
    if problems:
        for p in problems:
            print(f"[mic] {p}")
        raise SystemExit("El micro no queda retenido")
    print(f"[mic] OK: PCB Ø{MIC_D:.1f} apoyada en 2 repisas a "
          f"{MIC_SHELF_Z1:.2f} (solape {over:.2f} mm), bota a "
          f"{MIC_BOOT_Z0:.2f} con hueco {gap:.2f} mm para espuma, 4 postes "
          f"fuera del disco y 2 guias a {MIC_GUIDE_X0 - MIC_D / 2:.2f} mm")


def check_speaker_fit(tolerance=0.05):
    """El driver apoya en la bandeja aparte y los cables pasan por debajo.

    La bandeja se desliza desde el frente entre los dos carriles, apoya en los
    pies, la frena el tope trasero y los resaltes de los pies entran en los
    bolsillos: en las dos direcciones del deslizamiento queda retenida.  Los
    dos cables del altavoz corren por debajo de los travesanos (el centro del
    marco va abierto) y suben a los pads por el hueco delantero.
    """
    problems = []
    driver = bpy.data.objects.get("Speaker_Driver")
    tray = bpy.data.objects.get("Spk_Retainer")
    if driver is None or tray is None:
        raise SystemExit("[spk] falta el driver o la bandeja de apriete")

    def bounds(ob):
        pts = [ob.matrix_world @ Vector(c) for c in ob.bound_box]
        return (Vector((min(p.x for p in pts), min(p.y for p in pts),
                        min(p.z for p in pts))),
                Vector((max(p.x for p in pts), max(p.y for p in pts),
                        max(p.z for p in pts))))

    dmn, dmx = bounds(driver)
    tmn, tmx = bounds(tray)
    gap = dmn.z - tmx.z
    if not (-0.02 <= gap <= 0.10):
        problems.append(f"la bandeja queda a {gap:+.2f} mm del driver")
    for tag, got, want in (("x0", tmn.x, SPK_BAR_L[0]),
                           ("x1", tmx.x, SPK_BAR_R[1]),
                           ("y0", tmn.y, SPK_TRAY_Y0),
                           ("y1", tmx.y, SPK_TRAY_Y1),
                           ("z0", tmn.z, SPK_TRAY_Z0),
                           ("z1", tmx.z, SPK_TRAY_Z1)):
        if abs(got - want) > tolerance:
            problems.append(f"el marco {tag} = {got:.2f} (esperado {want:.2f})")
    if SPK_TRAY_Z1 - SPK_TINE_Z0 < 0.22:
        problems.append("los travesanos son demasiado finos")
    if SPK_TRAY_Z1 - SPK_TRAY_Z0 < 0.35:
        problems.append("los largueros son demasiado finos")

    # los dos travesanos tienen que pisar los cantos del driver
    for tag, (y0, y1) in (("delantero", SPK_TINE_FRONT),
                          ("trasero", SPK_TINE_BACK)):
        over = min(y1, dmx.y) - max(y0, dmn.y)
        if over < 0.30:
            problems.append(f"el travesano {tag} solo pisa {over:.2f} mm")
    if SPK_TRAY_Z1 > OLED_PCB_Z0 - 0.15:
        problems.append("la bandeja llega al OLED")
    if SPK_TRAY_Y1 > AMP_C[1] - AMP_L / 2 - 0.40:
        problems.append("la bandeja llega al amplificador")
    if SPK_TRAY_Y0 < MIC_C[1] + MIC_D / 2 + 0.40:
        problems.append("la bandeja llega al micro")

    # apoyos, juego y retencion en los dos carriles
    play = foot_over = 0.0
    for tag, web, foot, bar in (("izq", (SPK_WALL_X, SPK_WALL_X), SPK_LEDGE,
                                 SPK_BAR_L),
                                ("der", SPK_RAIL_R_WEB, SPK_RAIL_R_FOOT,
                                 SPK_BAR_R)):
        over = min(foot[1], bar[1]) - max(foot[0], bar[0])
        foot_over = max(foot_over, over)
        if over < 0.40:
            problems.append(f"el larguero {tag} solo apoya {over:.2f} mm")
        play = bar[0] - web[1] if bar[0] >= web[1] else web[0] - bar[1]
        if not (0.05 <= play <= 0.20):
            problems.append(f"juego del larguero {tag} = {play:.2f} mm")
    stop_gap = SPK_STOP_Y[0] - SPK_TRAY_Y1
    if not (0.05 <= stop_gap <= 0.25):
        problems.append(f"el tope trasero queda a {stop_gap:.2f} mm")
    bump, pocket = SPK_BUMP_D / 2, SPK_POCKET_H
    if not (pocket - 0.06 <= bump <= pocket + 0.06):
        problems.append(f"resalte {bump:.2f} y bolsillo {pocket:.2f} no encajan")
    if not (SPK_BUMP_Y[0] > SPK_POCKET_Y[0] + 0.02
            and SPK_BUMP_Y[1] < SPK_POCKET_Y[1] - 0.02):
        problems.append("el bolsillo es mas corto que el resalte")

    # Ningun cable puede atravesar el marco ni los carriles: el unico paso es
    # el hueco delantero del travesano (para los dos cables del altavoz) y el
    # carril derecho no llega al bus.  Se comprueba contra cajas, con 0,04 mm
    # de holgura (distancia menos el radio del cable).
    pad = 0.04
    rails = ((SPK_BAR_L[0], SPK_BAR_L[1], SPK_TRAY_Y0, SPK_TRAY_Y1,
              SPK_TRAY_Z0, SPK_TRAY_Z1),
             (SPK_BAR_R[0], SPK_BAR_R[1], SPK_BAR_R_Y[0], SPK_BAR_R_Y[1],
              SPK_TRAY_Z0, SPK_TRAY_Z1),
             (SPK_BAR_R[0], SPK_BAR_R[1], SPK_BAR_R_Y[1] - 0.10,
              SPK_TINE_BACK[0] + 0.10, SPK_TINE_Z0, SPK_TRAY_Z1),
             (SPK_TINE_X[0], SPK_WIRE_GAP[0], SPK_TINE_FRONT[0],
              SPK_TINE_FRONT[1], SPK_TINE_Z0, SPK_TRAY_Z1),
             (SPK_WIRE_GAP[1], SPK_TINE_X[1], SPK_TINE_FRONT[0],
              SPK_TINE_FRONT[1], SPK_TINE_Z0, SPK_TRAY_Z1),
             (SPK_TINE_X[0], SPK_TINE_X[1], SPK_TINE_BACK[0], SPK_TINE_BACK[1],
              SPK_TINE_Z0, SPK_TRAY_Z1),
             (SPK_LEDGE[0], SPK_LEDGE[1], SPK_RAIL_Y[0], SPK_RAIL_Y[1],
              SPK_RAIL_Z0, SPK_TRAY_Z0),
             (SPK_RAIL_R_WEB[0], SPK_RAIL_R_WEB[1], SPK_RAIL_R_Y[0],
              SPK_RAIL_R_Y[1], SPK_RAIL_Z0, 20.00),
             (SPK_RAIL_R_FOOT[0], SPK_RAIL_R_FOOT[1], SPK_RAIL_R_Y[0],
              SPK_RAIL_R_Y[1], SPK_RAIL_Z0, SPK_TRAY_Z0),
             (SPK_STOP_X[0], SPK_STOP_X[1], SPK_STOP_Y[0], SPK_STOP_Y[1],
              SPK_TRAY_Z0, SPK_TRAY_Z1))
    wires = 0
    tight = 9.9
    for ob in PARTS["internals"]:
        if ob.type != "CURVE" or not ob.get("flex"):
            continue
        wires += 1
        for q in ob["path"]:
            p = Vector(q)
            for x0, x1, y0, y1, z0, z1 in rails:
                d = math.hypot(max(x0 - p.x, p.x - x1, 0.0),
                               max(y0 - p.y, p.y - y1, 0.0),
                               max(z0 - p.z, p.z - z1, 0.0)) - WIRE_R
                tight = min(tight, d)
                if d < pad - 1e-6:
                    problems.append(f"{ob.name} pasa a {d:+.2f} mm del carril "
                                    f"en ({p.x:.1f},{p.y:.1f},{p.z:.2f})")
                    break
            else:
                continue
            break

    # el marco tampoco puede meterse dentro de ningun componente (mismo
    # criterio que [overlap]: mas de 0,05 mm en los tres ejes).  Cubre los
    # pines del OLED, que cuelgan hasta 16,60 justo detras del travesano.
    for ob in PARTS["internals"]:
        if ob.type != "MESH" or ob.name.startswith(("Pad_", "Kapton")):
            continue
        pts = [ob.matrix_world @ Vector(c) for c in ob.bound_box]
        bmn = Vector((min(p.x for p in pts), min(p.y for p in pts),
                      min(p.z for p in pts)))
        bmx = Vector((max(p.x for p in pts), max(p.y for p in pts),
                      max(p.z for p in pts)))
        for x0, x1, y0, y1, z0, z1 in rails:
            ox = min(x1, bmx.x) - max(x0, bmn.x)
            oy = min(y1, bmx.y) - max(y0, bmn.y)
            oz = min(z1, bmx.z) - max(z0, bmn.z)
            if ox > 0.05 and oy > 0.05 and oz > 0.05:
                problems.append(f"el marco se mete en {ob.name} "
                                f"({ox:.2f},{oy:.2f},{oz:.2f})")
                break

    if problems:
        for p in problems:
            print(f"[spk] {p}")
        raise SystemExit("El altavoz no queda retenido")
    print(f"[spk] OK: marco {tmx.x - tmn.x:.1f} x {tmx.y - tmn.y:.1f} x "
          f"{tmx.z - tmn.z:.2f} a {gap:.2f} mm bajo el driver, apoya "
          f"{foot_over:.2f} mm en los 2 pies (juego {play:.2f}), resalte "
          f"Ø{SPK_BUMP_D:.2f} en bolsillo {pocket:.2f} y tope a "
          f"{stop_gap:.2f} mm; {wires} hilos pasan a {tight:.2f} mm de la "
          f"bandeja y del carril")


def build_usb_plug(mats):
    """Cable USB-C de prueba para el render: casquillo, cuello y sobre-molde."""
    col = collection("09_Cable_USB")
    # acero satinado del casquillo: el acero pulido de los conectores se
    # apaga en un estudio oscuro y el metal no se distingue del hueco.
    if "steel_plug" not in mats:
        mats["steel_plug"] = base.principled(
            "Acero_Casquillo", (0.70, 0.72, 0.76, 1.0), rough=0.30, metal=0.40)
        mats["cable_step"] = base.principled(
            "Cable_Gris_Paso", (0.13, 0.13, 0.14, 1.0), rough=0.60)
    # el casquillo sale hacia fuera (-Y): punta en USB_Y0 y culata 6,5 mm
    # afuera; el alivio y el sobre-molde empiezan en esa culata.
    shell = block("Plug_Shell", PLUG_W, PLUG_L, PLUG_H, 0.0,
                  USB_Y0 - PLUG_L / 2, USB_ZC - PLUG_H / 2, col)
    assign(shell, mats["steel_plug"])
    neck = slot_xz("Plug_Neck", PLUG_NECK_W, PLUG_NECK_H,
                   USB_Y0 - PLUG_L - PLUG_NECK_L,
                   USB_Y0 - PLUG_L + 0.02, 0.0, USB_ZC, col,
                   radius=1.2)
    assign(neck, mats["cable_step"])
    body = slot_xz("Plug_Body", PLUG_BODY_W, PLUG_BODY_H,
                   USB_Y0 - PLUG_L - PLUG_NECK_L - PLUG_BODY_L,
                   USB_Y0 - PLUG_L - PLUG_NECK_L + 0.05, 0.0, USB_ZC, col,
                   radius=2.6)
    assign(body, mats["cable_gray"])
    # latiguillo redondo que sale del sobre-molde: sin el, el cable parece un
    # taco macizo y no se lee como un cable enchufado.
    body_back = USB_Y0 - PLUG_L - PLUG_NECK_L - PLUG_BODY_L
    cord = slot_xz("Plug_Cord", PLUG_CABLE_D, PLUG_CABLE_D,
                   body_back - PLUG_CABLE_L, body_back + 0.10, 0.0, USB_ZC,
                   col, radius=PLUG_CABLE_D / 2)
    assign(cord, mats["cable_gray"])
    for ob in (shell, neck, body, cord):
        smooth(ob, 40.0)
    return [shell, neck, body, cord]


def camera(name, loc, target, lens=None, ortho=None):
    data = bpy.data.cameras.new(name)
    if ortho:
        data.type = "ORTHO"
        data.ortho_scale = ortho
    else:
        data.lens = lens
    ob = bpy.data.objects.new(name, data)
    ob.location = loc
    bpy.context.scene.collection.objects.link(ob)
    base.look_at(ob, target)
    return ob


def render_to(path, cam, res, samples=128):
    # FIFO_RENDER=xiao|under|... regenera solo esa vista (iteracion rapida)
    only = os.environ.get("FIFO_RENDER")
    if only and only not in os.path.basename(path):
        return
    sc = bpy.context.scene
    sc.camera = cam
    sc.render.resolution_x, sc.render.resolution_y = res
    sc.render.resolution_percentage = 100
    sc.cycles.samples = samples
    sc.render.filepath = path
    bpy.ops.render.render(write_still=True)
    print(f"[render] {os.path.basename(path)}")


def bulb(name, loc, energy, radius, col=None):
    """Luz puntual para las vistas de interior (las de area salen a cero)."""
    data = bpy.data.lights.new(name, "POINT")
    data.energy = energy
    data.shadow_soft_size = radius
    ob = bpy.data.objects.new(name, data)
    (col or bpy.context.scene.collection).objects.link(ob)
    ob.location = loc
    return ob


def ghost_material(name="Carcasa_Fantasma", alpha=0.94,
                   tint=(0.45, 0.62, 0.88, 1.0), rough=0.12, spec=0.5):
    """Material translucido: deja ver el interior sin quitar la pieza.

    `alpha` es el peso del shader transparente (1,0 = invisible).
    """
    mat = bpy.data.materials.new(name)
    nt = mat.node_tree          # Blender 5.x ya crea el arbol de nodos
    gb = nt.nodes["Principled BSDF"]
    gb.inputs["Base Color"].default_value = tint
    gb.inputs["Roughness"].default_value = rough
    for key in ("Specular IOR Level", "Specular"):
        if key in gb.inputs:
            gb.inputs[key].default_value = spec
            break
    transparent = nt.nodes.new("ShaderNodeBsdfTransparent")
    mix = nt.nodes.new("ShaderNodeMixShader")
    mix.inputs[0].default_value = alpha
    out = nt.nodes["Material Output"]
    nt.links.new(gb.outputs[0], mix.inputs[1])
    nt.links.new(transparent.outputs[0], mix.inputs[2])
    nt.links.new(mix.outputs[0], out.inputs["Surface"])
    return mat


def export_stl(ob, filename):
    base.select_only(ob)
    bpy.context.view_layer.objects.active = ob
    path = os.path.join(OUT_DIR, "print", filename)
    try:
        bpy.ops.wm.stl_export(filepath=path, export_selected_objects=True,
                              apply_modifiers=True, ascii_format=False)
    except Exception:
        bpy.ops.export_mesh.stl(filepath=path, use_selection=True,
                                apply_modifiers=True)
    print(f"[stl] {filename}")


# ------------------------------------------------- version "todo unido"
# Las piezas decorativas (junta, rejilla, aros, bisel) se imprimen aparte y
# encajan con 0,05-0,10 mm de holgura: no se pueden fusionar tal cual.  Para
# la variante de una sola tirada se duplican, se les da un mordisco de 0,10-
# 0,30 mm dentro de la pieza madre (siempre en zonas ocultas) y se unen con un
# booleano EXACT.  El resultado exterior es el mismo; por dentro queda un
# solido unico.  Los botones siguen sueltos: se meten desde dentro por el aro.

def duplicate(ob, name):
    dup = ob.copy()
    dup.data = ob.data.copy()
    dup.name = name
    dup.data.name = name
    col = ob.users_collection[0] if ob.users_collection else sc_collection()
    col.objects.link(dup)
    return dup


def sc_collection():
    return bpy.context.scene.collection


def union(target, tool):
    base.select_only(target)
    bpy.context.view_layer.objects.active = target
    mod = target.modifiers.new("u_" + tool.name, "BOOLEAN")
    mod.operation = "UNION"
    mod.solver = "EXACT"
    mod.object = tool
    bpy.ops.object.modifier_apply(modifier=mod.name)
    bpy.data.objects.remove(tool, do_unlink=True)
    return target


def scale_xy(ob, factor, cx=None, cy=None):
    """Escala en X/Y alrededor del centro de la pieza (no del origen)."""
    me = ob.data
    if cx is None or cy is None:
        xs = [v.co.x for v in me.vertices]
        ys = [v.co.y for v in me.vertices]
        cx = 0.5 * (min(xs) + max(xs))
        cy = 0.5 * (min(ys) + max(ys))
    for v in me.vertices:
        v.co.x = cx + (v.co.x - cx) * factor
        v.co.y = cy + (v.co.y - cy) * factor
    return ob


def extend_down(ob, z):
    """Baja la cara inferior de una extrusion hasta `z` (sin tocar el resto)."""
    me = ob.data
    zmin = min(v.co.z for v in me.vertices)
    for v in me.vertices:
        if v.co.z <= zmin + 1e-4:
            v.co.z = z
    return ob


def build_full_parts(body, lid):
    """Cuerpo y tapa con sus piezas decorativas ya fusionadas."""
    col = collection("01_Cuerpo")

    # --- cuerpo + bisel USB ---------------------------------------------
    body_full = duplicate(body, "Fifo_Body_Full")
    bez = slot_xz("usb_bez_full", USB_BEZEL_W, USB_BEZEL_H,
                  -L / 2 - 0.60, -L / 2 + 0.30, 0.0, USB_ZC, col,
                  radius=1.8)
    # el hueco crece 0,02 mm: asi el bisel muerde la pared del cuerpo sin
    # dejar caras coplanares con el agujero pasante.
    bez_in = slot_xz("usb_bez_full_i", USB_BEZEL_IN_W + 0.04,
                     USB_BEZEL_IN_H + 0.04, -L / 2 - 0.90, -L / 2 + 0.60,
                     0.0, USB_ZC, col, radius=1.32)
    cut(bez, bez_in, "usbbezfull")
    union(body_full, bez)

    # --- tapa + junta + rejilla + aros ----------------------------------
    lid_full = duplicate(lid, "Fifo_Lid_Full")

    # junta de pantalla: mismo exterior, 0,06 mm mas gruesa hacia la ventana
    # y hundida 0,25 mm en el marco (el labio queda bajo la lente).
    gasket = rounded_ring("Screen_Gasket_Full", 26.2, 14.2, 4.0, 0.96,
                          H - 1.15, H - 0.60, col)
    place(gasket, *SCR_C)
    union(lid_full, gasket)

    # rejilla: +0,15 mm de radio para morder el aro y la pared del hueco.
    grille = duplicate(bpy.data.objects["Spk_Grille"], "Spk_Grille_Full")
    scale_xy(grille, (SPK_GRILLE_R + 0.25) / (SPK_GRILLE_R + 0.10))
    union(lid_full, grille)

    # aro: crece 0,05 mm (evita caras coplanares con el rebaje) y baja
    # 0,35 mm dentro de la tapa.
    rim = duplicate(bpy.data.objects["Spk_Rim_o"], "Spk_Rim_Full")
    scale_xy(rim, (SPK_RING_R + 0.05) / SPK_RING_R)
    extend_down(rim, H - 0.90)
    union(lid_full, rim)

    # aros de los botones: ya apoyan 0,10 mm sobre la cara de la tapa.
    for i in (0, 1):
        bez_i = duplicate(bpy.data.objects[f"Btn_Bezel_{i}_o"],
                          f"Btn_Bezel_{i}_Full")
        union(lid_full, bez_i)

    return body_full, lid_full


def main():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.unit_settings.system = "METRIC"
    sc.unit_settings.length_unit = "MILLIMETERS"
    sc.unit_settings.scale_length = 0.001

    mats = base.make_materials()
    add_materials(mats)
    base.studio()
    body, lid = build_shell(mats)
    build_body_features(mats, body)
    build_lid_features(mats, lid)
    build_body_internals(mats)
    build_pads(mats)
    build_cables(mats)
    for ob in (body, lid):
        smooth(ob, 38.0)

    for ob in (body, lid):
        me = ob.data
        bm = bmesh.new()
        bm.from_mesh(me)
        bad = sum(1 for e in bm.edges if not e.is_manifold)
        print(f"[mesh] {ob.name}: verts={len(me.vertices)} edges_bad={bad}")
        bm.free()

    check_seam(body, lid)
    check_clearance()
    check_component_overlap()
    check_cable_routes()
    check_xiao_underpass()
    check_usb_fit()
    check_switch_fit()
    check_button_fit()
    check_mic_mount()
    check_speaker_fit()
    report_packing()

    if os.environ.get("FIFO_QUICK"):
        print("[quick] geometria y chequeos OK (sin renders ni STL)")
        return

    base.setup_render()
    sc.cycles.use_denoising = True
    sc.cycles.adaptive_threshold = 0.03

    cam_hero = camera("Cam_Round_Hero", (58, -140, 112), (0, 0, 9.5), lens=84)
    cam_top = camera("Cam_Round_Top", (0, 0, 160), (0, 0, 0), ortho=62)
    cam_exploded = camera("Cam_Round_Exploded", (72, -192, 150),
                          (0, 0, 30), lens=80)
    cam_body = camera("Cam_Round_Body", (12, -74, 118), (0, -6, 7.5), lens=62)
    cam_lid = camera("Cam_Round_Lid", (0, -2, 0), (0, -2, 18), ortho=62)
    cam_lid.rotation_euler = (math.pi, 0.0, 0.0)   # mirando hacia arriba
    cam_harness = camera("Cam_Round_Harness", (76, -122, 50), (0, -5, 10.5),
                         lens=70)
    cam_power = camera("Cam_Round_Power", (58, 40, 74), (0, -8, 8), lens=66)
    cam_face = camera("Cam_Round_Face", (0, 12, 120), (0, 12, 21.5),
                      ortho=27)
    cam_mic = camera("Cam_Round_Mic", (0, -20, 70), (0, -20, 19.1), ortho=24)
    # vista del altavoz desde abajo-delante: el marco de la bandeja se ve por
    # el hueco de la rejilla y los dos cables entran por delante del travesano.
    # planta desde abajo y centrada en el marco (x -7,3 / y -4,5): a 40 mm de
    # orto la bandeja quedaba pequena y perdida en la tapa; el marco mide
    # 19,6 x 10,5 y a 26 mm se lee entero con margen.
    cam_spk = camera("Cam_Round_Spk", (-7.3, -4.5, -92), (-7.3, -4.5, 17.0),
                     ortho=26)
    # misma orientacion que la vista de la tapa: imagen a la derecha = +X y la
    # cara frontal (-Y) abajo, asi el marco no sale espejado.
    cam_spk.rotation_euler = (math.pi, 0.0, 0.0)
    cam_xiao = camera("Cam_Round_Xiao", (0, -14, -90), (0, -14, 4.5),
                      ortho=42)
    # vista lateral del puerto: desde el frente el sobre-molde tapa el
    # casquillo, asi que la camara se abre ~72 grados sobre el eje -Y.
    cam_usb = camera("Cam_Round_Usb", (98, -62, 40), (0, -33, 5.6), lens=96)
    cam_under = camera("Cam_Round_Under", (38, -92, -62), (0, -14, 2.4),
                       lens=58)

    render_to(os.path.join(OUT_DIR, "render_fifo_round.png"),
              cam_hero, (1280, 1000), 160)
    render_to(os.path.join(OUT_DIR, "render_fifo_round_top.png"),
              cam_top, (900, 1200), 96)
    # cara frontal: vista recta del OLED para comprobar que no esta invertida
    render_to(os.path.join(OUT_DIR, "render_fifo_round_face.png"),
              cam_face, (1100, 760), 150)

    # puerto USB-C con un cable real insertado: prueba visual del hueco.
    # El sobre-molde queda entre la Key y el casquillo, asi que el metal
    # saldria en sombra: un relleno lateral lo saca a la luz.
    plug = build_usb_plug(mats)
    usb_fill = bulb("UsbFill", (70, -45, 45), 90000, 8.0)
    render_to(os.path.join(OUT_DIR, "render_fifo_round_usb.png"),
              cam_usb, (1200, 900), 140)
    for ob in plug + [usb_fill]:
        bpy.data.objects.remove(ob, do_unlink=True)

    # explotado: la tapa y todo lo montado en ella suben enteros.  Los cables
    # se ocultan: el render del mazo ya muestra el cableado completo y, si no,
    # quedarian colgando del cuerpo como hilos flotantes.
    lifted = [lid] + [o for o in bpy.data.objects
                      if o.get("mount") == "lid"]
    cables = [o for o in bpy.data.objects if o.name.startswith("Cable_")]
    for ob in cables:
        ob.hide_render = True
    for ob in lifted:
        ob.location.z += EXPLODE_DZ
    render_to(os.path.join(OUT_DIR, "render_fifo_round_exploded.png"),
              cam_exploded, (1000, 1150), 140)
    for ob in lifted:
        ob.location.z -= EXPLODE_DZ
    for ob in cables:
        ob.hide_render = False

    # 1) interior del cuerpo: sin tapa ni nada montado en ella y sin bateria
    lidstuff = [lid] + [o for o in bpy.data.objects
                        if o.get("mount") == "lid"]
    lidstuff += [o for o in PARTS["face"]]
    for ob in lidstuff:
        ob.hide_render = True
    hidden = [o for o in bpy.data.objects
              if o.name.startswith(("LiPo_", "Kapton_Bat_", "Cable_Bat"))]
    for ob in hidden:
        ob.hide_render = True
    inspect = bpy.data.objects.get("Inspect")
    if inspect:
        inspect.hide_render = False
    render_to(os.path.join(OUT_DIR, "render_fifo_round_body.png"),
              cam_body, (1200, 1000), 150)
    if inspect:
        inspect.hide_render = True
    for ob in hidden:
        ob.hide_render = False
    for ob in lidstuff:
        ob.hide_render = False

    # 2) interior de la tapa: ortografico desde abajo.  El Backdrop del
    #    estudio (z -1,05) taparia las luces inferiores, asi que se oculta.
    bodystuff = [body] + [o for o in bpy.data.objects
                          if o.get("mount") == "body"]
    bodystuff += [o for o in PARTS["face"]]
    bodystuff += [o for o in bpy.data.objects
                  if o.type == "CURVE" and o.get("mount") == "body"]
    for ob in bodystuff:
        ob.hide_render = True
    backdrop = bpy.data.objects.get("Backdrop")
    if backdrop:
        backdrop.hide_render = True
    lid_light = bulb("LidFill_1", (0, -2, -8), 320000, 10.0)
    lid_fill = bulb("LidFill_2", (0, -26, -16), 120000, 14.0)
    render_to(os.path.join(OUT_DIR, "render_fifo_round_lid.png"),
              cam_lid, (1000, 1200), 180)
    for light in (lid_light, lid_fill):
        bpy.data.objects.remove(light, do_unlink=True)
    if backdrop:
        backdrop.hide_render = False
    for ob in bodystuff:
        ob.hide_render = False

    # 3) todo montado con la carcasa fantasma para ver el paso de cables.
    #    Ojo: con transparent_max_bounces=2 los rayos morian y salia opaca.
    sc = bpy.context.scene
    sc.cycles.transparent_max_bounces = 16
    ghost = ghost_material("Carcasa_Fantasma", 0.94)
    harness_fill = bulb("HarnessFill", (0, -8, 10), 20000, 6.0)
    shells = (body, lid)
    for ob in shells:
        assign(ob, ghost)
    # la bateria tapa el XIAO y el canalillo: se oculta solo en esta vista
    hidden_harness = [o for o in bpy.data.objects
                      if o.name.startswith(("LiPo_", "Kapton_Bat_"))]
    for ob in hidden_harness:
        ob.hide_render = True
    render_to(os.path.join(OUT_DIR, "render_fifo_round_harness.png"),
              cam_harness, (1200, 1100), 190)
    for ob in hidden_harness:
        ob.hide_render = False
    for ob in shells:
        assign(ob, mats["shell"])
    bpy.data.objects.remove(harness_fill, do_unlink=True)

    # 3b) micro desde arriba: dos filas de pads y llegada del mazo.  Se oculta
    #     todo lo demas (incluidos los rotulos del cuerpo) para que se vean los
    #     seis pads y la bota acustica.
    mic_keep = {"INMP441", "INMP441_Can", "INMP441_SMD_0", "INMP441_SMD_1",
                "INMP441_PortSlot"}
    mic_keep |= {o.name for o in bpy.data.objects
                 if o.name.startswith(("Pad_Mic_", "TXT_SCK", "TXT_SD"))}
    mic_keep |= {o.name for o in bpy.data.objects
                 if o.name.startswith("Cable_") and "Mic" in o.name}
    hidden_mic = [o for o in bpy.data.objects
                  if o.type in {"MESH", "CURVE", "FONT"}
                  and o.name not in mic_keep]
    for ob in hidden_mic:
        ob.hide_render = True
    mic_fill = bulb("MicFill", (0, -28, 32), 14000, 6.0)
    render_to(os.path.join(OUT_DIR, "render_fifo_round_mic.png"),
              cam_mic, (1000, 900), 150)
    bpy.data.objects.remove(mic_fill, do_unlink=True)
    for ob in hidden_mic:
        ob.hide_render = False

    # 3c) altavoz: se deja la tapa con sus carriles, la bandeja aparte, el
    #     driver y los dos cables.  La camara mira desde abajo y delante, para
    #     ver el marco, los dos apoyos y el paso de los cables por el hueco.
    spk_keep = {"Fifo_Lid", "Spk_Retainer", "Speaker_Driver",
                "Pad_DRV_0", "Pad_DRV_1", "Cable_Spk_0", "Cable_Spk_1"}
    hidden_spk = [o for o in bpy.data.objects
                  if o.type in {"MESH", "CURVE", "FONT"}
                  and o.name not in spk_keep]
    for ob in hidden_spk:
        ob.hide_render = True
    # la vista mira hacia arriba desde z=-92: los rellenos van por debajo del
    # driver (z~17) y con la misma potencia que la vista de la tapa, porque a
    # 12 kW el marco de la bandeja salia casi negro.
    spk_fill = bulb("SpkFill_1", (0, -30, -8), 150000, 9.0)
    spk_fill2 = bulb("SpkFill_2", (14, 2, -16), 90000, 12.0)
    # la bandeja se tine de verde solo aqui: impresa en el mismo filamento que
    # la tapa, en gris se confundia con el suelo de la tapa y el marco no se
    # leia como pieza aparte.
    tray = bpy.data.objects["Spk_Retainer"]
    tray_accent = base.principled("Bandeja_Verde", (0.06, 0.30, 0.16, 1.0),
                                  rough=0.45)
    assign(tray, tray_accent)
    render_to(os.path.join(OUT_DIR, "render_fifo_round_spk.png"),
              cam_spk, (1100, 900), 160)
    assign(tray, mats["ring"])
    bpy.data.materials.remove(tray_accent)
    for light in (spk_fill, spk_fill2):
        bpy.data.objects.remove(light, do_unlink=True)
    for ob in hidden_spk:
        ob.hide_render = False

    # 4) alimentacion: sin tapa y con el LiPo en material fantasma.  Solo se
    #    dejan los tres hilos de potencia (LiPo+ -> switch -> 5V, LiPo- -> GND)
    #    para que se vea el recorrido completo por el suelo del cuerpo.
    power_ghost = ghost_material("Bateria_Fantasma", 0.88,
                                 (0.35, 0.65, 0.90, 1.0), rough=0.55,
                                 spec=0.08)
    power_wires = {"Cable_Bat_Pos", "Cable_Bat_Neg", "Cable_Sw_5V",
                   "Cable_Bat_Pos_BAT", "Cable_Bat_Neg_BAT"}
    for ob in lidstuff:
        ob.hide_render = True
    hidden_power = [o for o in bpy.data.objects
                    if o.type == "CURVE" and o.name not in power_wires]
    for ob in hidden_power:
        ob.hide_render = True
    battery = bpy.data.objects.get("LiPo_902030")
    battery_label = bpy.data.objects.get("LiPo_Label")
    if battery:
        assign(battery, power_ghost)
    if battery_label:
        battery_label.hide_render = True
    power_fill = bulb("PowerFill", (0, -26.5, 12), 9000, 6.0)
    power_fill2 = bulb("PowerFill2", (0, 8, 12), 9000, 7.0)
    power_fill3 = bulb("PowerFill3", (12, -20, 10), 5000, 5.0)
    power_fill4 = bulb("PowerFill4", (0, -2, 7), 4500, 5.0)
    render_to(os.path.join(OUT_DIR, "render_fifo_round_power.png"),
              cam_power, (1200, 1050), 170)
    for light in (power_fill, power_fill2, power_fill3, power_fill4):
        bpy.data.objects.remove(light, do_unlink=True)
    if battery_label:
        battery_label.hide_render = False
    if battery:
        assign(battery, mats["battery"])
    for ob in hidden_power:
        ob.hide_render = False
    for ob in lidstuff:
        ob.hide_render = False

    # 5) XIAO desde abajo: pads castellados, BAT/test y salida del mazo.  Se
    #    oculta la carcasa para que se vea como se suelda cada hilo al canto.
    xiao_keep = {"XIAO_ESP32S3", "USB_C"}
    xiao_keep |= {o.name for o in bpy.data.objects
                  if o.name.startswith(("Pad_XIAO_", "Cable_", "TXT_XIAO_",
                                        "Xiao_Post_"))}
    # los FONT tambien: si no, los rotulos de la tapa (VOL+/VOL-/ALTAVOZ)
    # flotan sobre el fondo de la vista
    hidden_xiao = [o for o in bpy.data.objects
                   if o.type in {"MESH", "CURVE", "FONT"}
                   and o.name not in xiao_keep]
    for ob in hidden_xiao:
        ob.hide_render = True
    xiao_fill = bulb("XiaoFill", (0, -14, -16), 90000, 9.0)
    xiao_fill2 = bulb("XiaoFill2", (10, -24, -10), 30000, 6.0)
    render_to(os.path.join(OUT_DIR, "render_fifo_round_xiao.png"),
              cam_xiao, (1100, 1300), 160)
    for light in (xiao_fill, xiao_fill2):
        bpy.data.objects.remove(light, do_unlink=True)
    for ob in hidden_xiao:
        ob.hide_render = False

    # 6) cables BAT+ / BAT- bajo el XIAO: cuerpo fantasma y vista baja para
    #    ver el pocket, la holgura bajo el PCB y los cuatro postes.
    under_ghost = ghost_material("Carcasa_Fantasma_Under", 0.93)
    under_keep = {body.name, "XIAO_ESP32S3"}
    under_keep |= {o.name for o in bpy.data.objects
                   if o.name.startswith(("Pad_XIAO_", "TXT_XIAO_",
                                         "Xiao_Post_"))}
    under_keep |= {"Cable_Bat_Pos", "Cable_Bat_Neg", "Cable_Sw_5V",
                   "Cable_Bat_Pos_BAT", "Cable_Bat_Neg_BAT"}
    for ob in lidstuff:
        ob.hide_render = True
    hidden_under = [o for o in bpy.data.objects
                    if o.type in {"MESH", "CURVE", "FONT"}
                    and o.name not in under_keep]
    for ob in hidden_under:
        ob.hide_render = True
    backdrop = bpy.data.objects.get("Backdrop")
    if backdrop:
        backdrop.hide_render = True
    assign(body, under_ghost)
    under_fill = bulb("UnderFill", (0, -16, -24), 90000, 8.0)
    under_fill2 = bulb("UnderFill2", (24, -28, -14), 30000, 6.0)
    render_to(os.path.join(OUT_DIR, "render_fifo_round_under.png"),
              cam_under, (1200, 1000), 160)
    for light in (under_fill, under_fill2):
        bpy.data.objects.remove(light, do_unlink=True)
    if backdrop:
        backdrop.hide_render = False
    assign(body, mats["shell"])
    for ob in hidden_under:
        ob.hide_render = False
    for ob in lidstuff:
        ob.hide_render = False

    # Los postes de la bateria y los 4 del XIAO son parte impresa del cuerpo,
    # pero se dejan como objetos sueltos durante los renders para poder verlos.
    # Antes de guardar/exportar se unen al cuerpo: si no, no salen en el STL.
    for ob in [o for o in bpy.data.objects
               if o.type == "MESH" and o.name.startswith(
                   ("Battery_Post_", "Xiao_Post_"))]:
        base.select_only(body)
        bpy.context.view_layer.objects.active = body
        mod = body.modifiers.new("u_" + ob.name, "BOOLEAN")
        mod.operation = "UNION"
        mod.solver = "EXACT"
        mod.object = ob
        bpy.ops.object.modifier_apply(modifier=mod.name)
        bpy.data.objects.remove(ob, do_unlink=True)
    PARTS["body"] = [body]

    sc.camera = cam_hero
    blend = os.path.join(OUT_DIR, "fifo_round.blend")
    bpy.ops.wm.save_as_mainfile(filepath=blend)
    print(f"[blend] {blend}")

    os.makedirs(os.path.join(OUT_DIR, "print"), exist_ok=True)
    export_stl(body, "fifo_round_body.stl")
    export_stl(lid, "fifo_round_lid.stl")
    export_stl(bpy.data.objects["Spk_Grille"], "fifo_round_speaker_grille.stl")
    export_stl(bpy.data.objects["Spk_Rim_o"], "fifo_round_speaker_ring.stl")
    export_stl(bpy.data.objects["Spk_Retainer"],
               "fifo_round_speaker_retainer.stl")
    export_stl(bpy.data.objects["Screen_Gasket_o"], "fifo_round_screen_gasket.stl")
    export_stl(bpy.data.objects["usb_bez_o"], "fifo_round_usb_bezel.stl")
    export_stl(bpy.data.objects["Switch_Slider"], "fifo_round_switch_slider.stl")
    for suffix, i in (("up", 0), ("down", 1)):
        export_stl(bpy.data.objects[f"Button_{i}"],
                   f"fifo_round_button_{suffix}.stl")
        export_stl(bpy.data.objects[f"Btn_Bezel_{i}_o"],
                   f"fifo_round_button_bezel_{suffix}.stl")

    # variante de una sola tirada: las mismas piezas con los accesorios ya
    # fusionados (los botones siguen sueltos)
    body_full, lid_full = build_full_parts(body, lid)
    for ob in (body_full, lid_full):
        bm = bmesh.new()
        bm.from_mesh(ob.data)
        bad = sum(1 for e in bm.edges if not e.is_manifold)
        print(f"[mesh] {ob.name}: verts={len(ob.data.vertices)} edges_bad={bad}")
        bm.free()
    export_stl(body_full, "fifo_round_body_full.stl")
    export_stl(lid_full, "fifo_round_lid_full.stl")

    print("[shell] body", tuple(round(v, 2) for v in body.dimensions),
          "lid", tuple(round(v, 2) for v in lid.dimensions))
    print("[shell] body_full", tuple(round(v, 2) for v in body_full.dimensions),
          "lid_full", tuple(round(v, 2) for v in lid_full.dimensions))


if __name__ == "__main__":
    main()
