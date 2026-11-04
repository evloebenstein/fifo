# Guía de Montaje · Caja Fifo Robot (XIAO ESP32-S3 Kit)

> **Modelo 3D:** `hardware/enclosure/enclosure.blend` (abrir con Blender 4.0+)  
> **Renders:** `render_enclosure.png` (hero), `render_exploded.png` (exploded), `render_internals.png` (internals)  
> **Script generador:** `hardware/enclosure/build_enclosure.py` (fuente de verdad)

---

## 1. Lista de materiales (BOM)

| Ref | Componente | Cantidad | Dimensiones (mm) | Notas |
|-----|------------|----------|------------------|-------|
| **MCU** | XIAO ESP32-S3 8 MB (sin PSRAM) | 1 | 21.0 × 17.8 × 4.0 | USB-C nativo, BLE, 2× I2S, **conector sale por pared -Y** |
| **Pantalla** | OLED SSD1306 0.96" I²C (GM009605 v4.3) | 1 | 24.7 × 27.0 × 1.2 | PCB; área activa 21.74 × 10.86; conector borde largo trasero |
| **Mic** | INMP441 I2S (bottom port, módulo redondo) | 1 | Ø 10.0 × 2.0 | SCK=GPIO7, WS=GPIO8, SD=GPIO9; **puerto en el piso** |
| **Amp** | MAX98357A I2S Class-D | 1 | 25.0 × 15.0 × 3.0 | BCLK=GPIO1, LRC=GPIO2, DIN=GPIO4 |
| **Parlante** | Mini plano 8 Ω 1 W (1609/1511/1508) | 1 | 16.0 × 9.0 × 3.0 | **Rejilla visible: círculo Ø24 con aro metálico (brim)** |
| **Batería** | LiPo 902030 500 mAh 3.7 V | 1 | 30.0 × 20.0 × 9.0 | Conector JST-PH 2-pin; cinta Kapton |
| **GNSS** | ATGM336H GPS + BeiDou | 1 | 25.0 × 25.0 × 4.5 | UART1 D6/D7 (GPIO43/44), antena cerámica 16×16×1.2 |
| **Switch** | SS12D07 slide switch | 1 | 10.5 × 4.0 × 4.5 | Encendido principal, pared +Y |
| **Pulsadores** | Tact switch 6×6×5 mm (PCB) | 2 | 6.0 × 6.0 × 5.8 | Vol + / Vol −, **colgados del techo de la tapa** |
| **Caja** | Impresión 3D (PETG/ASA) | 2 piezas | **56 × 70 × 22** | Contera (Z 0..14.85) + Tapa (Z 15..22, techo 2 mm, labio, 4 postes) |
| **Tornillos** | M2 × 10 mm auto-roscantes | 4 | — | Entran desde el piso; manga en contera Z 3..8, poste en tapa Z 8..20 |
| **Inserto** | M2 heat-set (opcional) | 4 | — | Para repetibilidad en los postes de esquina |
| **Cables** | AWG 28/30 silicona | — | — | Colores: rojo (PWR+), negro (PWR-/GND), ámbar (TX), azul (I2S) |
| **Cinta** | Kapton (poliimida) | — | — | Fija LiPo y aisla parche GPS |

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

Pulsadores (GPIO5/6 con pull-up interno):
  BTN_UP   ──► GPIO5 (ejemplo) con INPUT_PULLUP
  BTN_DOWN ──► GPIO6 (ejemplo) con INPUT_PULLUP
  BTN_MAIN ──► GPIO3 (hardware pulldown en placa XIAO)
```

---

## 4. Layout interno — Tres torres de apilado (cavidad 19 mm útiles)

```
Vista superior (Z = 3 a 22 mm, origen en centro de la caja 56×70):

       +Y (35)  ──────────────────────────────────────────►
        │
        │      ┌──────────────────────────────────────┐
        │      │          ATGM336H  (3..7.5)          │  ← Columna TRASERA
        │      │       GPS PCB  25×25×4.5             │     Centro (0, 16)
        │      │       Patch cerámico 16×16×1.2       │     (7.5..8.7)
        │      │              (0, 16)                 │
        │      └──────────────────────────────────────┘
        │
        │      ┌──────────────────────────────────────┐
        │      │          LiPo 902030  (3..12)        │  ← Columna CENTRO
        │      │       30×20×9   (0, -7)              │
        │      │          MAX98357A (12..15)          │     Centro (0, -7)
        │      │       25×15×3     (0, -7)            │
        │      └──────────────────────────────────────┘
        │
        │  ┌─────────────────────┐      ┌─────────────────────┐
        │  │   XIAO ESP32-S3     │      │     INMP441         │  ← Columna FRENTE
        │  │   (3..7)            │      │   (3..5)            │     XIAO: (0, -26)
        │  │   21×17.8×4         │      │   Ø10×2             │     MIC:  (20, -10)
        │  │   USB-C → Y=-36.6   │      │   puerto abajo      │
        │  └─────────────────────┘      └─────────────────────┘
        │
       -Y (-35)  ◄──────────────────────────────────────────
              (pared con corte USB-C: 9×2.2..7.0 mm en Z)

Postes M2 (columnas de esquina, libres de componentes):
  (-21, 28)  (21, 28)  (-21, -28)  (21, -28)
  Manga en contera Z 3..8  |  Poste roscado en tapa Z 8..20
  Tornillo M2×10 entra desde el piso (Z=0).
```

---

## 5. Secuencia de montaje (paso a paso)

### 5.1 Preparación de cables y PCB (antes de meter en la caja)

1. **Soldar headers hembra** en XIAO (opcional: soldar directo para perfil bajo).
2. **Cablear I²C OLED**: 4 hilos (VCC, GND, SDA=D2, SCL=D3) — 30 mm, silicona AWG30.
3. **Cablear I2S0 MIC**: 4 hilos (VCC, GND, SCK=D7, WS=D8, SD=D9) — blindado si es posible; cable hacia el piso (taladro en Y=-10).
4. **Cablear I2S1 AMP**: 4 hilos (VCC, GND, BCLK=D1, LRC=D2, DIN=D4) — hacia parlante en tapa.
4. **Cablear GPS UART1**: 4 hilos (VCC, GND, TX=D6, RX=D7) — hacia esquina trasera.
5. **Cablear batería**: JST-PH 2-pin a pines BAT+/BAT- del XIAO.
6. **Cablear switch SS12D07**: 2 hilos entre 3V3 y EN (o GPIO libre + pull-up).
7. **Cablear 2× pulsadores 6×6**: un lado a GND, otro a GPIO5/6 con `INPUT_PULLUP`.
8. **Verificar continuidad** y cortocircuitos con multímetro antes de encender.

### 5.2 Montaje en la contera (pieza inferior, Z 0..14.85)

**Pasos:**

1. Colocar **XIAO** en el borde -Y (centro X=0, Y=-26, Z=3). El conector USB-C nativo asoma por el corte de la pared -Y (no hay brida ni panel-mount). Fijar con 2-3 puntos de cianocrilato o hot-melt en los bordes de la PCB.
2. Pegar **blindaje RF** (lámina 14×12×0.1 mm) sobre antena del XIAO con cinta Kapton (Z≈7).
3. Encajar **INMP441** en su hueco frontal-derecho (X=20, Y=-10, Z=3) — el puerto inferior queda sobre el taladro de 1.1 mm en el piso. Pegar con cianocrilato.
4. Colocar **LiPo 902030** en el centro (X=0, Y=-7, Z=3) — cinta doble cara fina + cinta Kapton envolvente (4×17 mm a cada lado, Z=12).
5. Encajar **MAX98357A** sobre la LiPo (X=0, Y=-7, Z=12) — hot-melt en esquinas.
6. Colocar **ATGM336H** en la zona trasera (X=0, Y=16, Z=3) — pegar antena cerámica 16×16 encima (Z=7.5); cinta Kapton 16.6×3.6 aislando el parche (Z=8.7).
7. Atornillar **switch SS12D07** en la ranura de la pared +Y (X=0, Y≈32.9, Z=3).
8. Pasar cables por los canales internos (diseñados en la impresión):
   - PWR (rojo/negro): JST → XIAO por el frente
   - GPS TX/RX (ámbar/negro): GPS → XIAO por la izquierda
   - I2S MIC (azul): INMP441 → XIAO por la derecha
   - I2S AMP (azul): MAX98357A → parlante (sube a la tapa)
   - SW (rojo): Switch → XIAO por la izquierda

### 5.3 Montaje de la tapa (pieza superior, Z 15..22)

**Vista inferior de la tapa (lo que toca la contera al cerrar):**

```
       +X (28)
        │
        │    ┌────────────────────────────────────────────┐
        │    │           VENTANA OLED 23.4×12.6           │  ← Centro (-0.5, 17.4)
        │    │  ┌────────────────────────────────────┐    │     Lente acrílica flush 2.6 mm
        │    │  │ Lente acrílico `glass_lens()`      │    │     (Transmission=1, Fresnel blend)
        │    │  │ LCD azul emissive 0.4 mm           │    │     Rostro: 2 ojos + sonrisa
        │    │  │ Texto "FIFO · EL ASISTENTE..."     │    │
        │    │  └────────────────────────────────────┘    │
        │    └────────────────────────────────────────────┘
        │
        │         ┌───────────────────┐   ┌───────────────────┐
        │         │  BTN UP (VERDE)   │   │  BTN DN (ROJO)    │  ← Centros (21, -8) y (21, -21.5)
        │         │  + SUBIR VOL      │   │  - BAJAR VOL      │     Vástago 6 mm, tapa O12.4
        │         │  Pozo O13.8       │   │  Pozo O13.8       │     Hundidos 0.5 mm bajo cara
        │         └───────────────────┘   └───────────────────┘
        │
        │    ┌────────────────────────────────────────────┐
        │    │       REJILLA PARLANTE Ø24 + BRIM          │  ← Centro (-14, -20)
        │    │    Aro metálico en relieve (0.9 mm)        │     Driver 16×9×3 a Z=16.8
        │    │    Malla hundida Ø22 (agujeros 0.8 @2.4)   │     Junta 0.4 mm a Z=19.8
        │    │    Rótulo "ALTAVOZ" bajo la rejilla        │
        │    └────────────────────────────────────────────┘
        │
        │    ┌──────┐
        │    │ SWITCH│  ← Ranura 11×4.8 mm en pared +Y (X=0, Y≈33)
        │    └──────┘
        │
       -X (-28)
```

**Pasos:**

1. Pegar **lente acrílico** (transparente, 2.6 mm) en el bisel impreso — UV glue o cianocrilato; cara externa flush con la tapa.
2. Encajar **OLED** tras la lente (conector hacia abajo, cables por canal hacia la derecha) — Z=20.05..21.25.
3. Insertar **parlante** en su bolsillo circular (imán hacia arriba, Z=16.8..19.8) — hot-melt en 4 puntos.
4. Encajar **rejilla metamica + aro (brim)** a presión sobre el anillo impreso.
5. Insertar **botones** (tapa verde/roja + vástago 6 mm) en sus pozos O13.8 — encaje a presión; tact switches PCB cuelgan del techo a Z=14.2.
6. Atornillar **switch SS12D07** en ranura pared +Y.
7. Verificar que el **USB-C del XIAO** asoma correctamente por el corte de la pared -Y (centrado, sin brida).

### 5.4 Cierre final

1. Alinear tapa sobre contera (guías de encaje en perímetro + 4 postes M2).
2. Presionar uniformemente hasta oír *clic* de los clips internos.
3. Atornillar **4 tornillos M2×10** desde el piso (entran en las mangas de la contera y enganchan los postes de la tapa).
4. Verificar: OLED visible sin velo, rejilla libre, botones accionables, USB-C accesible, switch operable.

---

## 6. Vistas explotadas (para documentación)

En Blender (`enclosure.blend`), las colecciones están organizadas:

| Colección | Contenido | Visibilidad |
|-----------|-----------|-------------|
| `01_Carcasa` | Contera + tapa + biseles + anillo parlante + 4 postes + cutouts | Base |
| `02_Rejilla` | Aro relieve (brim) + malla hundida | Sobre parlante |
| `03_Pantalla` | Lente `glass_lens()` + LCD azul + curvas rostro + texto | Cara superior |
| `04_Botones` | 2× (vástago + tapa emisiva) + etiquetas "+ SUBIR VOL" / "- BAJAR VOL" | Pared +X |
| `05_Internos` | XIAO, shield RF, LiPo, MAX98357A, ATGM336H+patch, INMP441, switch, lever, 2× tact PCB, cables, Kapton | Dentro de contera |
| `00_Estudio` | Suelo + 4 áreas (key/fill/rim/bounce) + Cam_Hero 82mm | Solo render |

**Render explotado generado por script:** `move_group("lid", 46.0)` eleva la tapa **sin rotar** (para ver pantalla y botones); `move_group("internals", 16.0)` desplaza internos; `explode_wires()` dibuja cables que cruzan entre grupos; `place_labels()` pone etiquetas 3D billboard (ocultas en hero).

---

## 7. Impresión 3D (slicer settings)

| Parámetro | Valor recomendado |
|-----------|-------------------|
| **Material** | PETG (resistencia térmica) o ASA (UV) |
| **Altura capa** | 0.16 mm (calidad) / 0.2 mm (rápido) |
| **Perímetros** | 3 (pared 2 mm ≈ 12 perim @ 0.16) |
| **Relleno** | 15 % gyroid (estructura interna) |
| **Soportes** | Solo para: **corte USB-C** (overhang 90° en pared -Y) y **ranura switch** (pared +Y) — tree/organic |
| **Adhesión** | Brim 6 mm |
| **Temperatura** | PETG: 240/80 °C — ASA: 260/100 °C |
| **Velocidad** | 40 mm/s perim, 60 mm/s infill |
| **Tiempo estimado** | ~3 h (contera) + ~2 h (tapa) = 5 h total |

**Orientación:**  
- **Contera:** boca abajo (piso en cama) — evita soportes en cavidad.  
- **Tapa:** boca arriba (ventana OLED en cama con soporte árbol bajo el bisel/lente).

---

## 8. Firmware y puesta en marcha

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

## 9. Solución de problemas

| Síntoma | Causa probable | Acción |
|---------|----------------|--------|
| OLED no enciende | I²C cableado invertido / pull-ups faltantes | Verificar SDA/SCL, añadir 4.7 kΩ a 3V3 |
| Mic mudo / ruido | I2S0 pines mal / WS/SCK invertidos | Revisar `config.h`: SCK=7, WS=8, SD=9; taladro en piso alineado |
| Parlante mudo | I2S1 pines / MAX98357A SD flotante | BCLK=1, LRC=2, DIN=4; GND común; driver en bolsillo Z=16.8 |
| GPS sin fix | Antena cerámica no pegada / UART1 pines | D6=TX, D7=RX; antena plana hacia cielo; Kapton no tapa parche |
| BLE no conecta | UUIDs desincronizados / MTU | `ff10` servicio; MTU 517 negociada |
| Batería no carga | TP4054 no soldado / USB-C 5V no llega | Medir VBUS en XIAO, revisar diodo Schottky |
| USB-C no encaja | Corte pared -Y mal impreso / XIAO desplazado | Verificar corte 9×2.2..7.0 mm; XIAO en Y=-26 exacto |

---

## 10. Archivos generados (no commitear)

```
hardware/enclosure/
├── build_enclosure.py      # ← FUENTE DE VERDAD (commitear)
├── enclosure.blend         # ← Generado (NO commitear)
├── enclosure.blend1        # ← Backup Blender (NO commitear)
├── render_enclosure.png    # ← Hero 1800×1400 (NO commitear)
├── render_exploded.png     # ← Exploded (NO commitear)
├── render_internals.png    # ← Internals (NO commitear)
├── ASSEMBLY.md             # ← Esta guía (commitear)
└── README.md               # ← Resumen (commitear)
```

**Regenerar todo:**
```bash
# Rápido
$env:FIFO_QUICK="1"; blender --background --factory-startup --python hardware/enclosure/build_enclosure.py

# Calidad final
blender --background --factory-startup --python hardware/enclosure/build_enclosure.py
```

---

## 11. Licencia y crédito

Hardware: CERN-OHL-S-2.0 (abierto, modificable, comercial)  
Firmware: Apache-2.0  
Modelo 3D: CC-BY-4.0 (atribución: "Fifo Robot Team")

---

*Documentación generada desde `build_enclosure.py` — versión del 2026-11-04*