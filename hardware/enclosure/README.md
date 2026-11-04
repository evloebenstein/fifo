# Enclosure 3D · Fifo Robot

Modelo paramétrico de la caja del robot Fifo (kit XIAO ESP32-S3).  
Medidas exactas: 62 × 74 × 24 mm, radios 12 mm, pared 2 mm, piso 3.5 mm.

## Ejecución

### Opción 1: Vía MCP (Nexus-Blender-Bridge en `http://localhost:3000/sse`)
Con Blender abierto y el puente MCP activo:

```powershell
python hardware/enclosure/build_enclosure.py
```

O usando el cliente MCP directamente:

```powershell
python hardware/enclosure/mcp_client.py
```

### Opción 2: Modo headless (CLI)
```powershell
& "C:\Program Files\Blender Foundation\Blender 5.2\blender.exe" --background --factory-startup --python .\hardware\enclosure\build_enclosure.py
```

Genera y actualiza:
- `hardware/enclosure/enclosure.blend` (archivo editable, con todos los componentes y materiales marcados como **Assets** en el Asset Browser)
- `hardware/enclosure/render_enclosure.png` (render 1920×1080)

## Documentación

- **[ASSEMBLY.md](ASSEMBLY.md)** — Guía completa de montaje con esquemas, wiring, vistas explotadas, slicer settings y troubleshooting.
- **[build_enclosure.py](build_enclosure.py)** — Script generador paramétrico con soporte dual (MCP / CLI).
- **[mcp_client.py](mcp_client.py)** — Cliente robusto para el protocolo MCP SSE JSON-RPC 2.0.

## Integración de Assets en Blender & MCP

Todos los objetos y materiales están registrados en el **Asset Browser** de Blender:
- **Carcasa:** `Fifo_Case_Bottom`, `Fifo_Case_Top`
- **Audio:** `Fifo_Acoustic_Grille`, `Fifo_Grille_Rim`, `Fifo_Speaker_Driver`
- **Pantalla y UI:** `Fifo_Acrylic_Lens`, `Fifo_OLED_Screen`, `Fifo_OLED_PCB`, `Fifo_Face_Expression`
- **Controles:** `Fifo_Button_VolUp` (Verde), `Fifo_Button_VolDown` (Rojo), `Fifo_Switch_SS12D07`
- **Electrónica:** `Fifo_XIAO_ESP32S3`, `Fifo_LiPo_Battery`, `Fifo_GPS_Module`, `Fifo_Amp_MAX98357A`, `Fifo_Mic_INMP441`, `Fifo_USBC_Connector`
- **Materiales PBR:** `Mat_Fifo_Navy`, `Mat_Fifo_OLED_Blue`, `Mat_Fifo_Accent_Green`, `Mat_Fifo_Accent_Red`, etc.

## Contenido del modelo (colecciones Blender)

| Colección | Descripción |
|-----------|-------------|
| `00_Estudio` | Suelo ciclorama + 3 áreas (key/fill/rim) + cámara 45 mm |
| `01_Carcasa` | Contera (Z=0..17) + tapa (Z=17..24), ventana OLED, rejilla, botones, USB-C, switch |
| `02_Rejilla` | Disco metálico Ø22 mm, agujeros concéntricos + bisel + parlante planar Ø20 |
| `03_Pantalla` | Lente acrílico 1.6 mm + LCD azul emissive + rostro (2 ojos + sonrisa) |
| `04_Botones` | 2× botón (Vol+ verde con '+', Vol- rojo con '-') + 2× tact switch PCB |
| `05_Internos` | XIAO ESP32-S3 con RF shield, LiPo 902030, GPS ATGM336H + patch, amp MAX98357A, mic INMP441, switch SS12D07, USB-C |

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
