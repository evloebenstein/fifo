# Guía de Montaje · Caja Fifo Robot (XIAO ESP32-S3 Kit)

> **Modelo 3D:** `hardware/enclosure/enclosure.blend` (abrir con Blender 4.0+)  
> **Render:** `hardware/enclosure/render_enclosure.png`  
> **Script generador:** `hardware/enclosure/build_enclosure.py` (fuente de verdad)

---

## 1. Lista de materiales (BOM)

| Ref | Componente | Cantidad | Dimensiones (mm) | Notas |
|-----|------------|----------|------------------|-------|
| **MCU** | XIAO ESP32-S3 8 MB (sin PSRAM) | 1 | 21.0 × 17.8 × 4.0 | USB-C nativo, BLE, 2× I2S |
| **Pantalla** | OLED SSD1306 0.96" I²C (128×64) | 1 | 27.5 × 19.2 × 2.5 | Panel visible 21.74 × 10.87 |
| **Mic** | INMP441 I2S (bottom port) | 1 | 14.0 × 12.5 × 3.5 | SCK=GPIO7, WS=GPIO8, SD=GPIO9 |
| **Amp** | MAX98357A I2S Class-D | 1 | 25.0 × 15.0 × 3.0 | BCLK=GPIO1, LRC=GPIO2, DIN=GPIO4 |
| **Parlante** | Planar 8 Ω 1 W (Ø20×4) | 1 | Ø 20.0 × 4.0 | Tras rejilla metamica |
| **Batería** | LiPo 902030 500 mAh 3.7 V | 1 | 30.0 × 20.0 × 9.0 | Conector JST-PH 2-pin |
| **GNSS** | ATGM336H GPS + BeiDou | 1 | 25.0 × 25.0 × 4.5 | UART1 D6/D7 (GPIO43/44), antena cerámica |
| **Switch** | SS12D07 slide switch | 1 | 10.5 × 4.0 × 4.5 | Encendido principal, pared +X |
| **Pulsadores** | Tact switch 6×6×5 mm | 2 | 6.0 × 6.0 × 5.0 | Vol + / Vol −, pared +X |
| **Caja** | Impresión 3D (PETG/ASA) | 2 piezas | 62 × 74 × 24 | Contera + tapa, encaje a presión |
| **Tornillos** | M2 × 6 mm auto-roscantes | 4 | — | Postes internos |
| **Inserto** | M2 heat-set (opcional) | 4 | — | Para repetibilidad |

---

## 2. Pinout XIAO ESP32-S3 (resumen)

```
          +─────────────────────────────+
  3V3 ────|●                        ●|── GND
  D0/TX ──|                            |── D1/RX
  D2/SDA ─|                            |── D3/SCL  ← OLED I²C
  D4 ─────|        XIAO ESP32-S3       |── D5
  D6 ─────|                            |── D7      ← GPS UART1
  D8 ─────|                            |── D9
  D10 ────|                            |── D11
          +─────────────────────────────+
  GPIO0 ── Boot | RST ──────────────────|
  GPIO1 ── BCLK (MAX98357A)             |
  GPIO2 ── LRC  (MAX98357A)             |
  GPIO4 ── DIN  (MAX98357A)             |
  GPIO3 ── BTN (pulldown interno)       |
  GPIO7 ── SCK  (INMP441)               |
  GPIO8 ── WS   (INMP441)               |
  GPIO9 ── SD   (INMP441)               |
  5V ──── USB-C entrada (carga LiPo)    |
  3V3 ── Salida 3.3 V (máx 300 mA)      |
```

---

## 3. Esquema de conexiones (ASCII wiring)

```
┌─────────────────────────────────────────────────────────────────┐
│                        XIAO ESP32-S3                            │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐            │
│  │  I2C0   │  │  I2S0   │  │  I2S1   │  │  UART1  │            │
│  │ SDA=D2  │  │ SCK=D7  │  │ BCLK=D1 │  │ TX=D6   │            │
│  │ SCL=D3  │  │ WS =D8  │  │ LRC =D2 │  │ RX=D7   │            │
│  └────┬────┘  │ SD =D9  │  │ DIN =D4 │  └────┬────┘            │
│       │       └────┬────┘  └────┬────┘       │                 │
│       ▼            ▼            ▼            ▼                 │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐            │
│  │ OLED    │  │ INMP441 │  │MAX98357A│  │ ATGM336H│            │
│  │ SSD1306 │  │  MIC    │  │  AMP    │  │  GPS    │            │
│  └─────────┘  └────┬────┘  └────┬────┘  └─────────┘            │
│                    │            │                              │
│                    │      ┌─────┴─────┐                         │
│                    │      ▼           ▼                         │
│                    │  ┌─────┐    ┌─────────┐                   │
│                    └──►│ SPK │    │  SPK    │  (paralelo)       │
│                       └─────┘    └─────────┘                   │
└─────────────────────────────────────────────────────────────────┘

Alimentación:
  USB-C (5 V) ──► XIAO VBUS ──► TP4054 (carga LiPo) ──► BAT+ ──► XIAO 3V3 (LDO interno)
  LiPo 3.7 V ──► XIAO BAT ──► 3V3 rail (OLED, INMP441, MAX98357A, GPS, Switches)

Pulsadores (GPIO3 + 2× GPIO libres con pull-up interno):
  BTN_UP   ──► GPIO5 (ejemplo) con INPUT_PULLUP
  BTN_DOWN ──► GPIO6 (ejemplo) con INPUT_PULLUP
  BTN_MAIN ──► GPIO3 (hardware pulldown en placa XIAO)
```

---

## 4. Secuencia de montaje (paso a paso)

### 4.1 Preparación de la PCB (antes de meter en la caja)

1. **Soldar headers hembra** en XIAO (opcional: soldar directo para perfil bajo).
2. **Cablear I²C OLED**: 4 hilos (VCC, GND, SDA=D2, SCL=D3) — 30 mm, silicona AWG30.
3. **Cablear I2S0 MIC**: 4 hilos (VCC, GND, SCK=D7, WS=D8, SD=D9) — blindado si es posible.
4. **Cablear I2S1 AMP**: 4 hilos (VCC, GND, BCLK=D1, LRC=D2, DIN=D4).
5. **Cablear GPS UART1**: 4 hilos (VCC, GND, TX=D6, RX=D7).
6. **Cablear batería**: JST-PH 2-pin a pines BAT+/BAT- del XIAO.
7. **Cablear switch SS12D07**: 2 hilos entre 3V3 y EN (o GPIO libre + pull-up).
8. **Cablear 2× pulsadores 6×6**: un lado a GND, otro a GPIO5/6 con `INPUT_PULLUP`.
9. **Verificar continuidad** y cortocircuitos con multímetro antes de encender.

### 4.2 Montaje en la contera (pieza inferior)

```
Vista superior de la contera (Z = 0 a 17 mm):
┌────────────────────────────────────────────────────────────┐
│  (0,0)                                         (62,0)      │
│   +X                                                          │
│    │  ┌──────────────┐        ┌──────────┐                  │
│    │  │   ATGM336H   │        │  LiPo    │                  │
│    │  │  (-14, 22)   │        │ (14,-22) │                  │
│    │  │  GPS + patch │        │ 902030   │                  │
│    │  └──────────────┘        └──────────┘                  │
│    │                                                         │
│    │         ┌─────────────────────┐                         │
│    │         │   XIAO ESP32-S3     │                         │
│    │         │    (-2, 6)          │                         │
│    │         │  + blindaje RF      │                         │
│    │         └─────────────────────┘                         │
│    │                                                         │
│    │  ┌──────────┐                          ┌──────────┐     │
│    │  │ INMP441  │                          │ MAX98357A│     │
│    │  │ (24, 27) │                          │(SPK_C+1) │     │
│    │  └──────────┘                          └──────────┘     │
│    │                                                         │
│    └─────────────────────────────────────────────────────────┘
  +Y (74 mm)
```

**Pasos:**
1. Colocar **XIAO** en los 4 postes M2 (coordenadas ±24, ±28/30) — atornillar M2×6.
2. Pegar **blindaje RF** (lámina 14×12×0.1 mm) sobre antena del XIAO con cinta Kapton.
3. Encajar **INMP441** en su hueco (x=24, y=27) — pegar con cianocrilato o hot-melt.
4. Encajar **MAX98357A** junto al parlante (x≈SPK_C.x+1, y≈SPK_C.y-1).
4. Colocar **LiPo 902030** en cavidad (14, -22) — cinta doble cara fina.
5. Colocar **ATGM336H** en (-14, 22) — pegar antena cerámica 16×16 encima.
6. Pasar cables por los canales internos (diseñados en la印刷).

### 4.3 Montaje de la tapa (pieza superior)

```
Vista inferior de la tapa (Z = 17 a 24 mm):
┌────────────────────────────────────────────────────────────┐
│                                                            │
│    ┌────────────────────────────────────────────┐         │
│    │           VENTANA OLED 26×14.5             │         │
│    │         (centro -5, 17)                    │         │
│    │  ┌────────────────────────────────────┐    │         │
│    │  │ LENTE acrílico 2.6 mm (transmisión)│    │         │
│    │  │ LCD azul 0.4 mm (emissive)         │    │         │
│    │  │ Rostro: 2 ojos + sonrisa (curvas)  │    │         │
│    │  └────────────────────────────────────┘    │         │
│    └────────────────────────────────────────────┘         │
│                                                            │
│         ┌─────────────────────┐   ┌─────────────────┐     │
│         │  BTN UP (verde)     │   │  BTN DN (rojo)  │     │
│         │  (19, 4)  Ø11.2     │   │  (19, -14) Ø11.2│     │
│         │  stem 6 mm + cap    │   │  stem 6 mm + cap│     │
│         └─────────────────────┘   └─────────────────┘     │
│                                                            │
│    ┌────────────────────────────────────────────┐         │
│    │       REJILLA PARLANTE metamica            │         │
│    │       centro (-9, -13)  R=11 mm            │         │
│    │       agujeros Ø0.85 pitch 2.4 mm          │         │
│    └────────────────────────────────────────────┘         │
│                                                            │
│    ┌──────┐                        ┌────────────────┐     │
│    │ USB-C│                        │  SWITCH SS12D07│     │
│    │ (0,  │                        │  ranura 12×5   │     │
│    │ -37) │                        │  (31, 24)      │     │
│    └──────┘                        └────────────────┘     │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

**Pasos:**
1. Pegar **lente acrílico** (transparente, 2.6 mm) en bisel impreso — UV glue o ciano.
2. Encajar **OLED** tras la lente (conector hacia abajo, cables por canal).
3. Insertar **parlante** en anillo impreso (imán hacia arriba).
4. Encajar **rejilla metamica** a presión sobre anillo.
5. Insertar **botones** (tapa verde/roja + vástago) en sus huecos — encaje a presión.
6. Atornillar **switch SS12D07** en ranura pared +X.
7. Verificar que **USB-C** del XIAO asoma por hueco pared -Y.

### 4.4 Cierre final

1. Alinear tapa sobre contera (guías de encaje en perímetro).
2. Presionar uniformemente hasta oír *clic* de los 4 clips internos.
3. Verificar: OLED visible, rejilla libre, botones accionables, USB-C accesible, switch operable.

---

## 5. Vistas explotadas (para documentación)

En Blender (`enclosure.blend`), las colecciones están organizadas:

| Colección | Contenido | Visibilidad |
|-----------|-----------|-------------|
| `01_Carcasa` | Contera + tapa + biseles + anillo parlante + postes | Base |
| `02_Rejilla` | Disco agujereado + aro | Sobre parlante |
| `03_Pantalla` | Lente + LCD + curvas rostro (ojos/sonrisa) + texto | Cara superior |
| `04_Botones` | 2× (vástago + tapa) + etiquetas | Pared +X |
| `05_Internos` | XIAO, shield, OLED, INMP441, MAX98357A, parlante, LiPo, GPS, patch, switch, lever, 2× pulsadores PCB, USB-C | Dentro de contera |
| `00_Estudio` | Suelo + 3 áreas (key/fill/rim) + cámara | Solo render |

**Para generar vista explotada en Blender:**
```python
# En consola Python de Blender con enclosure.blend abierto
import bpy
for obj in bpy.data.objects:
    if "Internos" in [c.name for c in obj.users_collection]:
        obj.location.z += 40  # separar hacia arriba
    if "Botones" in [c.name for c in obj.users_collection]:
        obj.location.z += 30
        obj.location.y -= 20
```

---

## 6. Impresión 3D (slicer settings)

| Parámetro | Valor recomendado |
|-----------|-------------------|
| **Material** | PETG (resistencia térmica) o ASA (UV) |
| **Altura capa** | 0.16 mm (calidad) / 0.2 mm (rápido) |
| **Perímetros** | 3 (pared 2 mm ≈ 12 perim @ 0.16) |
| **Relleno** | 15 % gyroid (estructura interna) |
| **Soportes** | Solo para USB-C overhang y ranura switch (tree/organic) |
| **Adhesión** | Brim 6 mm |
| **Temperatura** | PETG: 240/80 °C — ASA: 260/100 °C |
| **Velocidad** | 40 mm/s perim, 60 mm/s infill |
| **Tiempo estimado** | ~3.5 h (contera) + ~2.5 h (tapa) = 6 h total |

**Orientación:** Contera boca abajo (piso en cama), tapa boca arriba (ventana OLED en cama con soporte árbol).

---

## 7. Firmware y puesta en marcha

```bash
# 1. Compilar firmware (PlatformIO)
cd esp32-firmware
pio run -e seeed_xiao_esp32s3

# 2. Flashear (botón BOOT + RST → modo descarga)
pio run -e seeed_xiao_esp32s3 -t upload

# 3. Monitor serie (115200 8N1)
pio device monitor -e seeed_xiao_esp32s3

# 4. En Android: compilar app y conceder permisos BLE/Ubicación
cd ../android-app
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Pruebas rápidas:**
- OLED: debe mostrar "FIFO" + cara animada al encender.
- Mic: hablar cerca → ver nivel RMS en logcat `BleVoiceClient`.
- Parlante: TTS desde app → sale por MAX98357A.
- GPS: al aire libre → fix en < 60 s, notificación BLE `ff15` cada 1 s.
- Botones: pulsar → evento BLE `ff14` código 1 (corta) / 2 (larga).

---

## 8. Solución de problemas

| Síntoma | Causa probable | Acción |
|---------|----------------|--------|
| OLED no enciende | I²C cableado invertido / pull-ups faltantes | Verificar SDA/SCL, añadir 4.7 kΩ a 3V3 |
| Mic mudo / ruido | I2S0 pines mal / WS/SCK invertidos | Revisar `config.h`: SCK=7, WS=8, SD=9 |
| Parlante mudo | I2S1 pines / MAX98357A SD flotante | BCLK=1, LRC=2, DIN=4; GND común |
| GPS sin fix | Antena cerámica no pegada / UART1 pines | D6=TX, D7=RX; antena plana hacia cielo |
| BLE no conecta | UUIDs desincronizados / MTU | `ff10` servicio; MTU 517 negociada |
| Batería no carga | TP4054 no soldado / USB-C 5V no llega | Medir VBUS en XIAO, revisar diodo Schottky |

---

## 9. Archivos generados (no commitear)

```
hardware/enclosure/
├── build_enclosure.py      # ← FUENTE DE VERDAD (commitear)
├── enclosure.blend         # ← Generado (NO commitear)
├── render_enclosure.png    # ← Generado (NO commitear)
├── ASSEMBLY.md             # ← Esta guía (commitear)
└── README.md               # ← Resumen (commitear)
```

**Regenerar todo:**
```bash
blender --background --factory-startup --python hardware/enclosure/build_enclosure.py
```

---

## 10. Licencia y crédito

Hardware: CERN-OHL-S-2.0 (abierto, modificable, comercial)  
Firmware: Apache-2.0  
Modelo 3D: CC-BY-4.0 (atribución: "Fifo Robot Team")

---

*Documentación generada automáticamente desde `build_enclosure.py` — versión del 2026-11-03*