# Enclosure 3D · Fifo Robot

Modelo paramétrico de la caja del robot Fifo (kit XIAO ESP32-S3).  
Medidas: 62 × 74 × 24 mm, radios 12 mm, pared 2 mm, piso 3.5 mm.

## Ejecutar

```powershell
& "C:\Program Files\Blender Foundation\Blender 5.2\blender.exe" --background --factory-startup --python .\hardware\enclosure\build_enclosure.py
```

Genera:
- `hardware/enclosure/render_enclosure.png` (1920×1080, Cycles 256 samples)
- `hardware/enclosure/enclosure.blend` (editable en Blender 4.0+)

## Documentación

- **[ASSEMBLY.md](ASSEMBLY.md)** — Guía completa de montaje con esquemas, wiring, vistas explotadas, slicer settings y troubleshooting.
- **[build_enclosure.py](build_enclosure.py)** — Script generador (fuente de verdad, paramétrico).

## Contenido del modelo (colecciones Blender)

| Colección | Descripción |
|-----------|-------------|
| `00_Estudio` | Suelo + 3 áreas (key/fill/rim) + cámara 42 mm |
| `01_Carcasa` | Contera + tapa (encaje a presión), ventana OLED, rejilla, botones, USB-C, switch |
| `02_Rejilla` | Disco metamica Ø22 mm, agujeros Ø0.85 pitch 2.4 + aro |
| `03_Pantalla` | Lente acrílico 2.6 mm + LCD azul emissive + rostro (2 ojos + sonrisa) |
| `04_Botones` | 2× botón (vástago 6 mm + tapa verde/roja) + etiquetas |
| `05_Internos` | XIAO, shield RF, OLED, INMP441, MAX98357A, parlante, LiPo, GPS+patch, switch, 2× pulsadores PCB, USB-C |

## Hardware mapeado

| Componente | Ref | Conexión XIAO |
|------------|-----|---------------|
| MCU | XIAO ESP32-S3 8 MB | — |
| Pantalla | OLED SSD1306 0.96" I²C | SDA=GPIO5 (D2), SCL=GPIO6 (D3) |
| Micrófono | INMP441 I2S0 RX | SCK=7, WS=8, SD=9 |
| Amplificador | MAX98357A I2S1 TX | BCLK=1, LRC=2, DIN=4 |
| Parlante | Planar 8 Ω 1 W | Salida MAX98357A |
| Batería | LiPo 902030 500 mAh | BAT+/BAT- (JST-PH) |
| GNSS | ATGM336H GPS+BeiDou | UART1 TX=D6, RX=D7 (GPIO43/44) |
| Switch | SS12D07 slide | EN / GPIO libre + pull-up |
| Pulsadores | 2× 6×6×5 mm | GPIO5/6 + INPUT_PULLUP |

## Notas

- No usa MCP for Blender; se ejecuta por línea de comandos para reproducibilidad.
- Los archivos `.blend` y `.png` **no se commitean**; el script `build_enclosure.py` es la fuente de verdad.
- Ver `ASSEMBLY.md` para secuencia de montaje paso a paso, wiring ASCII, slicer settings y troubleshooting.
