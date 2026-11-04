# ADR-0055: Kit de hardware XIAO ESP32-S3, voz de vuelta al robot y pulsador

**Status:** Accepted

**Date:** 2026-11-02

**Author:** Architecture Review — Adaptación al hardware nuevo

---

## Context

El firmware se escribió para un **ESP32-S3-DevKitC N16R8** con micrófono
analógico UCC, OLED SH1106 de 1.3" y un driver de parlante deshabilitado. El
robot real usa otro kit:

| Pieza | Antes en el código | Ahora |
| :--- | :--- | :--- |
| Placa | `esp32-s3-devkitc-1`, 16 MB, PSRAM octal | `seeed_xiao_esp32s3`, 8 MB, sin PSRAM |
| Pantalla | SH1106 1.3" | SSD1306 0.96" |
| Micrófono | ADC analógico en GPIO 1 | INMP441 por I2S |
| Parlante | `I2SSpeaker` compilado pero nunca instanciado | MAX98357A por I2S, siempre activo |
| Entrada física | ninguna | pulsador 6×6 |

Además, el audio solo viajaba en una dirección: el micrófono subía al teléfono
y el TTS sonaba **en el celular**. El kit incluye amplificador y parlante, así
que el robot debía hablar por su propia boca.

El problema de fondo: la XIAO estándar expone **nueve GPIO utilizables**
(D0–D5, D8–D10), y este kit necesita nueve exactos (2 I2C + 3 mic + 3 parlante
+ 1 pulsador). No hay margen para un pin de más.

---

## Decision

### 1. Placa y pinout cerrados

`platformio.ini` pasa a `seeed_xiao_esp32s3`. El board de PlatformIO describe
la variante **Plus** (con PSRAM octal), así que se corrige explícitamente:

```ini
board_build.arduino.memory_type = qio_qspi
build_unflags = -DBOARD_HAS_PSRAM
```

Sin ese ajuste, el bootloader espera PSRAM octal y la placa estándar no arranca.

| Bloque | Pines | Motivo |
| :--- | :--- | :--- |
| OLED SSD1306 | SDA = GPIO 5 (D4), SCL = GPIO 6 (D5) | Son los I2C por defecto de la XIAO. |
| INMP441 (I2S0 RX) | SCK = GPIO 7 (D8), WS = GPIO 8 (D9), SD = GPIO 9 (D10) | Los tres pines contiguos y sin función alternativa. |
| MAX98357A (I2S1 TX) | BCLK = GPIO 1 (D0), LRC = GPIO 2 (D1), DIN = GPIO 4 (D3) | Puerto I2S distinto del micrófono: RX y TX no comparten reloj porque corren a frecuencias distintas. |
| Pulsador | GPIO 3 (D2) → GND | GPIO 3 es strapping de JTAG, pero se mantiene alto con pull-up y solo se lee: no cambia el arranque. |

D6/D7 (GPIO 43/44) quedan fuera por ser UART; GPIO 0 es BOOT y GPIO 19/20 son USB.

### 2. El micrófono pasa a I2S digital

`i2s_mic.cpp` deja el `adc_digi_*` y usa `i2s_driver_install` en modo maestro RX
con ranuras de **32 bits** y solo canal izquierdo. El INMP441 entrega 24 bits
alineados a la izquierda, que se desplazan 16 bits a PCM 16-bit.

Se mantiene un bloqueador de continua de un polo y se elimina el filtro
pasa-altos de 150 Hz y la puerta de ruido: eran parches de un micrófono
analógico con bias y ya no aplican. El VAD real sigue viviendo en Android.

### 3. El parlante existe y tiene dueño de cola

`i2s_speaker.cpp` deja de estar detrás de `ENABLE_I2S_SPEAKER`. El audio que
llega por BLE entra en un **stream buffer de 400 ms** y una tarea dedicada la
vacía al DMA. El callback de BLE **nunca** espera al hardware: si no cabe, se
descarta el bloque completo, nunca media muestra.

### 4. La voz del asistente baja por BLE a 8 kHz

El enlace ya transporta 32 kB/s de micrófono a 16 kHz. Añadir otros 32 kB/s de
vuelta no cabe en BLE. La decisión: **el TTS baja a 8 kHz mono PCM16 (16 kB/s)**
y el MAX98357A reproduce a esa frecuencia, sin remuestreo en el ESP32.

- `TextToSpeech.synthesizeToFile()` produce un WAV.
- `RobotAudioFormat` lo decodifica a mono y lo baja a 8 kHz con media de bloque,
  que además hace de filtro paso-bajo.
- `RobotTtsSpeaker` envía bloques de 256 muestras (32 ms) marcando el ritmo real.
- Característica `0000ff13-…`: `0x00` descarta la cola, `0x01` + PCM.

`AndroidAudioHardwareAdapter` decide **una vez por locución** entre robot y
teléfono. Nunca suenan los dos. Si el robot no está conectado, todo sigue igual
que antes.

### 5. El pulsador clasifica, el celular decide

`button.cpp` hace antirrebote y distingue corta de larga. El firmware solo
publica un byte por `0000ff14-…`:

| Código | Gesto | Acción en el celular |
| :--- | :--- | :--- |
| `1` | Pulsación corta | despertar (`PushToTalk`) |
| `2` | Pulsación larga | alternar silencio (`ToggleMicMute`) |

Las acciones entran como `VoiceSessionIntent`, igual que las de la UI. El camino
`LEGACY` las ignora a propósito: no se agrega funcionalidad al legacy.

---

## Consequences

### Lo que mejora

- **El robot habla por su boca.** La respuesta del LLM sale del MAX98357A cuando
  hay enlace, y del teléfono cuando no. Es una sola decisión, no dos motores.
- **El hardware real está documentado y compila para él.** Board, flash, memoria
  y particiones coinciden con la placa que se va a flashear.
- **El micrófono es digital.** Desaparecen el bias, el filtro y la puerta de
  ruido; la señal llega limpia al VAD de Android.
- **El pulsador no es una frase enlatada.** El firmware no sabe qué significa
  "despertar": solo dice "hubo una pulsación corta".

### Lo que cuesta

- **8 kHz en el parlante.** La voz del robot suena a banda telefónica. Es el
  precio de no romper el enlace del micrófono; subirlo exige códec (Opus/ADPCM),
  que hoy sería una dependencia nueva.
- **Dos I2S y nueve pines, sin margen.** Cualquier pieza futura (LED de estado,
  sensor de batería) no cabe en la XIAO estándar.
- **`synthesizeToFile` depende del motor de TTS.** Un motor sin soporte de
  síntesis a archivo cae al parlante del teléfono; es el mismo comportamiento
  que había antes.
- **El pulsador no funciona en `LEGACY`.** Es deliberado: el camino legacy se
  borra en la release siguiente.

### Lo que **no** cambia

- **El pipeline de voz.** `CaptureArbiter`, `SpeechSegmenter`, `UtterancePolicy`,
  `TurnOrchestrator` y `VoiceSession` no se tocan.
- **La memoria durable.** El diario, la cola de pendientes y la reparación
  siguen igual.
- **El flag.** `VOICE_SESSION_ENABLED` sigue apagado en debug y encendido en
  release.
- **La captura.** El micrófono del robot sigue subiendo a 16 kHz por el mismo
  `CaptureArbiter`.

---

## Verification

| Prueba | Qué fija |
| :--- | :--- |
| `RobotAudioFormatTest` | WAV mono/estéreo, 24 kHz → 8 kHz, rechazo de formatos no PCM16 y serialización little endian |
| `FifoButtonEventTest` | los códigos del firmware se traducen a gestos y un código desconocido no produce acción |
| Suite completa | **470 tests debug / 470 release, 0 fallos, 6 omitidos** y `compileReleaseKotlin` |

El firmware no se compiló en esta pasada: el entorno no tiene la CLI de
PlatformIO. Los cambios se limitan a APIs del core Arduino 2.0.x ya usadas por
el proyecto (`driver/i2s.h`, `driver/adc.h` retirado, U8g2).

---

## Invariants

1. **El enlace es BLE, no Wi-Fi.** No se agrega hotspot ni dependencia de red al
   firmware.
2. **El micrófono sube a 16 kHz.** Si algún día se baja, hay que medirlo con el
   VAD y con `UtterancePolicy`, no por conveniencia de ancho de banda.
3. **El parlante baja a 8 kHz.** Subirlo exige códec y un ADR nuevo.
4. **Una sola decisión de salida.** `AndroidAudioHardwareAdapter` elige robot o
   teléfono; ningún otro punto reproduce TTS.
5. **El firmware no redacta ni decide.** Clasifica gestos y transporta audio; las
   respuestas las sigue escribiendo el LLM y las acciones las decide el celular.
6. **El callback de BLE no se bloquea.** La cola descarta antes que esperar.
7. **El pulsador no toca el legacy.** Sus eventos solo entran por
   `VoiceSessionIntent` en el camino `SESSION`.
