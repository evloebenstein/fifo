# Enclosure 3D · Fifo Robot

Modelo paramétrico de la caja del robot Fifo (kit XIAO ESP32-S3).  
Medidas: **56 × 70 × 22 mm**, esquinas **R7**, pared **2 mm**, piso **3 mm**.  
Dos piezas: **contera** (Z 0..14.85) + **tapa** (Z 15..22, techo 2 mm, labio + 4 postes roscados).

## Ejecutar

```powershell
# Rápido (20 samples, 55 % resolución ~25 s)
$env:FIFO_QUICK="1"; & "C:\Program Files\Blender Foundation\Blender 5.2\blender.exe" --background --factory-startup --python .\hardware\enclosure\build_enclosure.py

# Calidad final (256 samples, 100 % resolución ~3 min)
& "C:\Program Files\Blender Foundation\Blender 5.2\blender.exe" --background --factory-startup --python .\hardware\enclosure\build_enclosure.py
```

Genera:
- `hardware/enclosure/render_enclosure.png` (1800×1400, Cycles 256 samples, hero casi frontal)
- `hardware/enclosure/render_exploded.png` (vista explotada: tapa +46 mm, internos +16 mm, cables + etiquetas)
- `hardware/enclosure/render_internals.png` (vista interna: tres torres de apilado)
- `hardware/enclosure/enclosure.blend` (editable en Blender 4.0+)

## Documentación

- **[ASSEMBLY.md](ASSEMBLY.md)** — Guía completa de montaje con esquemas, wiring, vistas explotadas, slicer settings y troubleshooting.
- **[build_enclosure.py](build_enclosure.py)** — Script generador (fuente de verdad, paramétrico).

## Contenido del modelo (colecciones Blender)

| Colección | Descripción |
|-----------|-------------|
| `00_Estudio` | Suelo + 3 áreas (key/fill/rim/bounce) + cámara 82 mm |
| `01_Carcasa` | Contera + tapa (encaje a presión), ventana OLED 23.4×12.6, lente acrílica flush, rejilla circular Ø24 con aro metálico (brim), rótulo "ALTAVOZ", 2× botón redondo + leyenda "+ SUBIR VOL" / "- BAJAR VOL" |
| `02_Rejilla` | Aro relieve Ø24 + malla hundida Ø22 (agujeros 0.8 mm pitch 2.4) |
| `03_Pantalla` | Lente acrílico `glass_lens()` (Fresnel blend, Transmission=1, sheen=0) + LCD azul emissive + rostro (2 ojos + sonrisa) + rótulo "FIFO · EL ASISTENTE QUE ESCUCHA" |
| `04_Botones` | 2× botón (vástago 6 mm + tapa O12.4 verde/roja) hundido en pozos O13.8 + etiquetas emisivas |
| `05_Internos` | Tres columnas: **frente** XIAO (3..7 mm, USB-C a Y=-36.6) + INMP441 (3..5 mm); **centro** LiPo 3..12 mm → MAX98357A 12..15 mm; **trasera** ATGM336H 3..7.5 mm → parche 7.5..8.7 mm; **tapa** OLED 20.05..21.25, parlante 16.8..19.8, tact 14.2..20; 4 postes M2 en esquinas (±21, ±28); Kapton LiPo + GPS |

## Hardware mapeado

| Componente | Ref | Conexión XIAO |
|------------|-----|---------------|
| MCU | XIAO ESP32-S3 8 MB | — |
| Pantalla | OLED SSD1306 0.96" I²C (GM009605 v4.3) | SDA=GPIO5 (D2), SCL=GPIO6 (D3) |
| Micrófono | INMP441 I2S0 RX (módulo redondo 10 mm, puerto inferior) | SCK=7, WS=8, SD=9 |
| Amplificador | MAX98357A I2S1 TX | BCLK=1, LRC=2, DIN=4 |
| Parlante | Mini plano 8 Ω 1 W (16×9×3 mm, módulos 1609/1511/1508) | Salida MAX98357A |
| Batería | LiPo 902030 ~500 mAh (30×20×9 mm) | BAT+/BAT- (JST-PH) |
| GNSS | ATGM336H GPS+BeiDou (25×25×4.5 mm) + parche cerámico 16×16×1.2 | UART1 TX=D6, RX=D7 (GPIO43/44) |
| Switch | SS12D07 slide (pared +Y) | EN / GPIO libre + pull-up |
| Pulsadores | 2× 6×6×5 mm (tapa) | GPIO5/6 + INPUT_PULLUP |
| USB | **Integrado en el XIAO** (conector sobresale por corte en pared -Y) | USB-C nativo del MCU |

## Notas de diseño

- **USB-C**: el conector es parte del propio XIAO; la pared -Y tiene un único corte (9×3.6..7 mm) por donde asoma el conector de la placa. **No hay panel-mount ni brida separada**.
- **Micrófono**: INMP441 módulo redondo 10 mm con puerto inferior; el taladro está en el **piso** (no en la cara superior) en (20, -10).
- **Altavoz**: driver real 16×9×3 mm; lo visible es la **rejilla circular Ø24 con aro metálico en relieve** + rótulo "ALTAVOZ".
- **Botones**: vástago 6 mm, tapa O12.4, pozos O13.8; centros en (21, -8) y (21, -21.5); alineados verticalmente, menos espacio libre al borde inferior, más cerca del borde derecho.
- **Postes**: 4 columnas de esquina (±21, ±28); **manga corta (Sleeve) en la contera Z 3..8**, **poste roscado cuelga de la tapa Z 8..20**; tornillo M2×10 entra desde el piso.
- **Lente acrílica**: material `glass_lens()` (Principled BSDF Transmission=1 + Glass BSDF blend Fresnel) porque Principled solo lavaba la pantalla; `sheen=0.0`.
- **Hero render**: `Cam_Hero` (20, -222, 178), lens 82 mm, 1800×1400.
- **Exploded render**: tapa elevada 46 mm (sin rotar), internos +16 mm; cables `explode_wires()` cruzan entre grupos; etiquetas 3D billboard (ocultas en hero).
- **Chequeo AABB**: solo `LAYOUT` + postes (`BOSS_XY`, 6×6, `BOSS_Z0=8`); cables y etiquetas no participan.
- **No usa MCP for Blender**; se ejecuta por línea de comandos para reproducibilidad.
- Los archivos `.blend`, `.blend1` y `.png` **no se commitean**; el script `build_enclosure.py` es la fuente de verdad.
- Ver `ASSEMBLY.md` para secuencia de montaje paso a paso, wiring ASCII, slicer settings y troubleshooting.