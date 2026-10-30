# Speaker identity offline (ADR-0048)

Herramientas de **desarrollo** para evaluar un modelo real de verificación de
locutor contra el corpus sintético de Fifo, un control con voz real y un corpus
de campo. No forman parte de la app, no se ejecutan en la suite normal y no se
commitea ningún WAV, peso ni embedding.

La app sigue con `NoopSpeakerIdentityProvider`: estas herramientas existen para
decidir si algún día un modelo merece sustituirlo, no para afirmar que ya lo
hace.

## 1. Exportar el corpus

El exportador es una prueba JUnit apagada por defecto. Escribe WAV mono a
16 kHz (PCM16) y un `manifest.jsonl` con split, locutor, rol, condición,
sílabas y duración.

```powershell
$env:FIFO_SPEAKER_EXPORT_DIR="C:\Users\tu_usuario\AppData\Local\Temp\fifo-speaker-corpus"
.\gradlew.bat testDebugUnitTest --tests "*SpeakerCorpusExportTest"
```

Sin la variable, la prueba se omite. El corpus actual tiene **120 clips**:

| Split | Contenido |
| :--- | :--- |
| `enroll` | 3 frases por locutor enrolado (6 de evaluación + 2 de calibración) |
| `calibration` | 4 locutores × 4 condiciones × 2 duraciones |
| `evaluation` | 8 locutores × 4 condiciones × 2 duraciones |

`calibration` y `evaluation` **no comparten locutores**. El umbral se fija con
los primeros y se reporta con los segundos; mezclarlos sería una profecía.

## 2. Extraer embeddings

El contrato es un JSONL con una línea por clip del manifiesto:

```json
{"id": "test-spk-a-clean-0", "embedding": [0.0123, -0.0456, "..."]}
```

`extract_embeddings_sherpa.py` hace la extracción con sherpa-onnx, que incluye su
propio frontend (no hay que reproducir fbank a mano). En un venv aparte:

```powershell
py -3.12 -m venv "$env:TEMP\fifo-speaker-eval\venv"
& "$env:TEMP\fifo-speaker-eval\venv\Scripts\python.exe" -m pip install -r tools\speaker\requirements.txt

& "$env:TEMP\fifo-speaker-eval\venv\Scripts\python.exe" tools\speaker\extract_embeddings_sherpa.py `
  --manifest "$env:TEMP\fifo-speaker-corpus\manifest.jsonl" `
  --model "$env:TEMP\fifo-speaker-eval\models\wespeaker_en_voxceleb_resnet34.onnx" `
  --out "$env:TEMP\fifo-speaker-corpus\embeddings.jsonl"
```

Modelos medidos (descarga desde el release `speaker-recongition-models` de
k2-fsa/sherpa-onnx; no se commitean):

| Modelo | Dim | Tamaño | SHA-256 (primeros 16) |
| :--- | ---: | ---: | :--- |
| `wespeaker_en_voxceleb_resnet34.onnx` | 256 | 26,5 MB | `5ef208a9da145333` |
| `wespeaker_en_voxceleb_CAM++.onnx` | 512 | 29,3 MB | `c46fad10b5f81e1a` |
| `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx` | 192 | 28,3 MB | `f682b514c05d947e` |

Cualquier otro extractor sirve si produce un vector por clip.

## 3. Puntuar y calibrar

`score_embeddings.py` solo usa la biblioteca estándar (no necesita `numpy`):

```powershell
python tools/speaker/score_embeddings.py `
  --manifest "$env:TEMP\fifo-speaker-corpus\manifest.jsonl" `
  --embeddings "$env:TEMP\fifo-speaker-corpus\embeddings.jsonl" `
  --model-id wespeaker-en-voxceleb-resnet34 `
  --target-far 0.01 `
  --out "$env:TEMP\fifo-speaker-corpus\report.json"
```

Qué reporta:

- umbral calibrado con `calibration` al FAR objetivo;
- FAR de calibración y FRR/FAR de `evaluation`;
- EER por condición y por duración (umbral óptimo, solo como techo);
- tablas por condición y por duración con el umbral calibrado.

### Resultado de los modelos reales

Sobre el corpus sintético, tres modelos reales quedaron entre 42 % y 50 % de EER
global, **peor** que la referencia espectral (31,8 %). En limpio, ResNet34 baja a
9,5 % EER, pero en ruido/Lombard se cae a ~50 %. La lectura no es "estos modelos
son malos": el ruido sintético (5–10 dB, sala + Lombard) es un dominio hostil y
la referencia comparte familia de features con el corpus. **Este corpus no
ordena motores reales.** Además, con 12 genuinos por condición, un solo error
mueve el EER ~8 puntos. La tabla completa y el umbral por modelo están en
ADR-0048.

### Control con voz real (LibriSpeech)

Para saber si el 42–50 % es culpa del corpus o del código, repite el mismo
proceso con voz humana real. `build_librispeech_control.py` convierte 10
locutores disjuntos de **LibriSpeech dev-clean** (18 clips de enrolamiento, 24 de
calibración y 36 de evaluación) al mismo manifiesto de 16 kHz PCM16:

```powershell
# Descarga y extracción de LibriSpeech (solo desarrollo; no se commitea)
curl.exe -L -o "$env:TEMP\fifo-speaker-eval\librispeech-dev-clean.tar.gz" `
  "https://www.openslr.org/resources/12/dev-clean.tar.gz"
tar -xzf "$env:TEMP\fifo-speaker-eval\librispeech-dev-clean.tar.gz" `
  -C "$env:TEMP\fifo-speaker-eval\librispeech"

& "$env:TEMP\fifo-speaker-eval\venv\Scripts\python.exe" tools\speaker\build_librispeech_control.py `
  --librispeech-root "$env:TEMP\fifo-speaker-eval\librispeech\LibriSpeech\dev-clean" `
  --out "$env:TEMP\fifo-speaker-eval\librispeech-control"
```

Después se extraen embeddings y se puntúa igual que el corpus sintético. Con 144
trials de evaluación y el umbral fijado con los 2 locutores de calibración:

| Modelo | EER global | FRR/FAR @FAR 1 % |
| :--- | ---: | ---: |
| WeSpeaker ResNet34 | **0,0 %** | 0,0 % / 0,0 % |
| 3D-Speaker CAM++ (zh-cn) | **0,0 %** | 0,0 % / 0,0 % |

El control es deliberadamente fácil: lectura limpia, misma grabación y mismas
condiciones. **No certifica campo ni mide el móvil.** Su valor es separar las dos
preguntas: el pipeline (manifiesto, embeddings, centroides, calibración, scoring)
funciona; el problema del 42–50 % es que el corpus sintético no representa voces
humanas. Es la razón por la que el siguiente paso es grabar campo y no seguir
ajustando umbrales.

## 4. Corpus de campo (el paso bloqueante)

Ni el simulador ni LibriSpeech pueden autorizar un detector: el primero no
ordena motores y el segundo es lectura limpia. El único número que cuenta sale
de 10–15 personas reales grabadas con el mismo móvil y en las condiciones de uso.
`build_field_manifest.py` convierte esas grabaciones en el mismo manifiesto, con
validación de formato y de reparto.

Layout (una carpeta por locutor, sin nombres que adivinar):

```
<root>/<locutor>/enroll/<clip>.wav
<root>/<locutor>/test/<condición>/<clip>.wav
```

`enroll` lleva 3–5 frases en limpio. `test` lleva las frases de uso, una carpeta
por condición (`clean`, `tv`, `street`, `far`…). Todo WAV mono a 16 kHz PCM16:

```powershell
ffmpeg -i entrada.m4a -ac 1 -ar 16000 -c:a pcm_s16le salida.wav
```

El plan reparte locutores disjuntos entre calibración y evaluación:

```json
{
  "speakers": [
    {"id": "ana", "split": "calibration", "role": "enrolled"},
    {"id": "bea", "split": "calibration", "role": "unseen"},
    {"id": "carla", "split": "evaluation", "role": "enrolled"},
    {"id": "diego", "split": "evaluation", "role": "unseen"}
  ]
}
```

```powershell
python tools/speaker/build_field_manifest.py `
  --root "$env:TEMP\fifo-speaker-field" `
  --plan "$env:TEMP\fifo-speaker-field\plan.json" `
  --out "$env:TEMP\fifo-speaker-field\manifest.jsonl"
```

El ejemplo es el mínimo que valida el reparto; para que el número signifique
algo, escala a 10–15 personas. El script rechaza un plan sin `enrolled` y
`unseen` en cada split, un locutor duplicado, un clip que no sea
mono/16 kHz/PCM16, WAV sueltos fuera de una condición o un enrolado con menos de
3 frases. Avisa si el corpus se sale de 10–15 locutores, pero no inventa datos.
Después se ejecutan los pasos 2 y 3 con ese manifiesto.

Protocolo mínimo:

- **Consentimiento explícito** y borrado a petición; el audio no se sube ni se
  commitea, y solo se guardan centroides cifrados.
- 10–15 personas, 3–5 frases de enrolamiento y 6–10 de uso por persona.
- Condiciones separadas: silencio, TV, calle y distancia de 1–3 m.
- Calibración y evaluación **nunca** comparten locutores; 2+2 y 4+2 como mínimo.
- Anotar móvil, micrófono y condiciones junto al corpus: sin eso el número no es
  reproducible.

## 5. Campo simulado con voces reales

Cuando no hay voluntarios, la alternativa no es generar más voz de formantes: es
partir de **personas reales** y aumentarles la acústica. `RealSpeechAugmentationExportTest`
usa los 40 locutores de LibriSpeech dev-clean y les aplica `RoomAcoustics`,
`SimulatedNoise` y `LombardEffect` —los mismos del arnés—, así que no hay un
segundo simulador que pueda divergir.

```powershell
# 1. Pool de voces reales: 7 WAV por locutor (3 de enrolamiento + 4 de uso)
& "$env:TEMP\fifo-speaker-eval\venv\Scripts\python.exe" tools\speaker\prepare_librispeech_pool.py `
  --librispeech-root "$env:TEMP\fifo-speaker-eval\librispeech\LibriSpeech\dev-clean" `
  --out "$env:TEMP\fifo-speaker-eval\real-pool" `
  --speakers 40 --clips-per-speaker 7

# 2. Aumento en Kotlin con la acústica canónica
$env:FIFO_REAL_SPEECH_POOL="$env:TEMP\fifo-speaker-eval\real-pool"
$env:FIFO_REAL_SPEECH_EXPORT_DIR="$env:TEMP\fifo-speaker-eval\real-field-corpus"
# Opcional: tamaño de la cohorte de calibración (por defecto un tercio, máx. 12)
$env:FIFO_REAL_SPEECH_CALIBRATION="20"
.\gradlew.bat testDebugUnitTest --tests "*RealSpeechAugmentationExportTest"

# 3. Embeddings y scoring. El mejor candidato hasta ahora es el avanzado,
#    que funciona mejor sin AS-Norm; prueba ambos y decide con el informe.
& "$env:TEMP\fifo-speaker-eval\venv\Scripts\python.exe" tools\speaker\extract_embeddings_sherpa.py `
  --manifest "$env:TEMP\fifo-speaker-eval\real-field-corpus\manifest.jsonl" `
  --model "$env:TEMP\fifo-speaker-eval\models\3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx" `
  --out "$env:TEMP\fifo-speaker-eval\real-field-corpus\embeddings.jsonl"

python tools\speaker\score_embeddings.py `
  --manifest "$env:TEMP\fifo-speaker-eval\real-field-corpus\manifest.jsonl" `
  --embeddings "$env:TEMP\fifo-speaker-eval\real-field-corpus\embeddings.jsonl" `
  --model-id 3dspeaker-campplus-zh-en-advanced --target-far 0.01
```

Diseño: 12 locutores de calibración y 28 de evaluación, disjuntos; dentro de
cada split, mitad `enrolled` y mitad `unseen`. 3 frases de enrolamiento y 4 de
uso por enrolado, aumentadas a seis condiciones (`clean`, `living`, `pink-10`,
`white-5`, `babble-15`, `living-lombard-pink-20`). 1.020 clips, 9.408 trials,
recortados a 8 s.

| Modelo | Dim. | EER crudo | FRR/FAR crudo | EER AS-Norm | FRR/FAR AS-Norm |
| :--- | ---: | ---: | ---: | ---: | ---: |
| **CAM++ zh_en advanced** | 192 | **1,2 %** | **2,4 % / 0,7 %** | 1,9 % | 3,6 % / 0,6 % |
| NeMo TitaNet large | 192 | 2,4 % | 8,9 % / 0,7 % | 2,4 % | 16,4 % / 0,4 % |
| ERes2Net base 200k | 512 | 4,8 % | 23,8 % / 0,3 % | 3,6 % | 3,3 % / 4,0 % |
| ERes2NetV2 | 192 | 5,4 % | 23,8 % / 0,5 % | 2,7 % | 2,7 % / 2,9 % |
| CAM++ zh-cn | 192 | 11,6 % | 47,0 % / 0,6 % | 5,2 % | 10,1 % / 2,3 % |
| WeSpeaker ResNet34 | 256 | 43,4 % | 83,9 % / 0,4 % | 35,7 % | 83,0 % / 0,5 % |
| WeSpeaker CAM++ | 512 | 45,6 % | 91,1 % / 1,4 % | 45,0 % | 87,5 % / 2,6 % |

**AS-Norm** normaliza cada score con la media y desviación de una cohorte. La
cohorte sale **solo de calibración**: 6 perfiles y 288 clips. Evaluación no
participa en esa media. Es lo único que se entrena aquí; la red de embeddings no
se toca. No es una receta universal: el avanzado ya está bien calibrado en crudo
y AS-Norm lo empeora; ERes2NetV2 mejora de 5,4 % a 2,7 % pero sube el FAR de
evaluación a 2,9 %. Mide las dos variantes antes de elegir.

Ablación de cohorte: con 20 locutores de calibración (10 perfiles) y 20 de
evaluación, CAM++ zh-cn da 5,0 % EER con AS-Norm frente a 5,2 %, y el avanzado
1,3 % crudo frente a 1,2 % (4.800 trials). Más cohorte no arregla el dominio; la
varianza por locutor pesa.

Lectura:

- El ranking cambia con voz real: CAM++ zh_en advanced (1,2 %) queda muy por
  delante de los WeSpeaker (43–46 %). El corpus de formantes no mostraba eso.
- `white-5` es el único punto débil del candidato: 3,5 % EER y 10,7 % FRR con el
  umbral global; `clean`, `living`, `pink-10`, `babble-15` y Lombard están en
  0–1,8 % FRR.
- Sigue sin pasar la barra: es lectura limpia aumentada, no una calle ni un
  móvil, y el FRR de `white-5` roza el 10 %. No autoriza a cambiar `Noop`.
- Es inglés. Repetirlo con español: hecho en la sección 6.

## 6. Campo simulado en español

El corpus inglés no basta para un usuario que habla español. Se probaron tres
fuentes reales; solo una sirve para el arnés:

| Corpus | Locutores | Clips/locutor | Veredicto |
| :--- | ---: | ---: | :--- |
| FLEURS `es_419` | 1.450 | 1–3 | Descartado: no hay 7 clips por locutor. |
| VoxForge Spanish | 2.180 | ~10 | Descartado como principal: 43 frases compartidas y micrófonos domésticos. |
| **TEDx Spanish (SLR67)** | 142 | 5–246 (mediana 75) | **Elegido**: habla espontánea, contenido distinto, 16 kHz mono. |

TEDx se lee en streaming del `.tgz` (2,18 GB) sin descomprimirlo entero y se
extraen solo los 280 clips elegidos:

```powershell
curl.exe -L -o "$env:TEMP\fifo-speaker-eval\tedx-es\tedx_spanish_corpus.tgz" `
  "https://www.openslr.org/resources/67/tedx_spanish_corpus.tgz"

& "$env:TEMP\fifo-speaker-eval\venv\Scripts\python.exe" tools\speaker\prepare_tedx_spanish_pool.py `
  --archive "$env:TEMP\fifo-speaker-eval\tedx-es\tedx_spanish_corpus.tgz" `
  --out "$env:TEMP\fifo-speaker-eval\real-pool-es-tedx" `
  --speakers 40 --clips-per-speaker 7

$env:FIFO_REAL_SPEECH_POOL="$env:TEMP\fifo-speaker-eval\real-pool-es-tedx"
$env:FIFO_REAL_SPEECH_EXPORT_DIR="$env:TEMP\fifo-speaker-eval\real-field-corpus-es-tedx"
$env:FIFO_REAL_SPEECH_CALIBRATION="12"
.\gradlew.bat cleanTestDebugUnitTest testDebugUnitTest --tests "*RealSpeechAugmentationExportTest"
```

`cleanTestDebugUnitTest` es necesario: si solo cambian las variables de entorno,
Gradle considera la tarea *up-to-date* y no regenera el corpus.

40 locutores (20/20), 7 clips repartidos a lo largo de la charla, 12 de
calibración y 28 de evaluación, seis condiciones, 1.020 clips, 9.408 trials:

| Modelo | Dim. | EER crudo | FRR/FAR crudo | EER AS-Norm | FRR/FAR AS-Norm |
| :--- | ---: | ---: | ---: | ---: | ---: |
| **CAM++ zh_en advanced** | 192 | 2,4 % | **5,7 % / 0,4 %** | 5,3 % | 4,8 % / 5,7 % |
| NeMo TitaNet large | 192 | **1,8 %** | 12,8 % / 0,1 % | 8,3 % | 3,6 % / 10,3 % |
| ERes2NetV2 | 192 | 3,8 % | 14,3 % / 0,8 % | 8,3 % | 9,2 % / 7,6 % |
| ERes2Net base 200k | 512 | 4,1 % | 11,9 % / 0,7 % | 8,9 % | 6,2 % / 9,5 % |
| CAM++ zh-cn | 192 | 11,0 % | 36,9 % / 1,0 % | 12,5 % | 11,6 % / 13,1 % |
| WeSpeaker CAM++ | 512 | 41,2 % | 88,4 % / 1,5 % | 39,9 % | 90,8 % / 2,1 % |
| WeSpeaker ResNet34 | 256 | 40,2 % | 82,4 % / 1,2 % | 37,9 % | 78,0 % / 4,0 % |

Lectura: el español cuesta ~1 punto (2,4 % frente a 1,2 % en inglés), el orden
de los modelos se mantiene, la calibración **sí** transfiere (1,4 % en
calibración frente a 2,4 % en evaluación) y AS-Norm empeora al avanzado. TitaNet
gana en EER pero pierde en el punto de operación (12,8 % FRR a FAR 1 %). Sigue
sin autorizar `Noop`: es audio de conferencia aumentado, no una llamada.

VoxForge se conserva como evidencia del descarte
(`prepare_voxforge_spanish_pool.py`): 10,1 % EER con el avanzado, pero
calibración 30,6 % frente a evaluación 10,1 % y **94,9 % FRR** con el umbral
calibrado, porque cada voluntario graba con su micrófono. Repartir prompts al
azar por locutor (para que dos personas no reciten la misma frase) no cambió el
número. Un corpus así exige normalización por locutor, no un umbral global.

## 7. Spoofing: medir con un CM real antes de prometer nada

`score_spoofing.py` ejecuta un CM ONNX y compara voz real con voz sintética. Se
usó AASIST (297 k parámetros, MIT, checkpoint oficial de ASVspoof2019 LA, 0,83 %
EER in-domain) y voz sintética en español generada con Piper:

```powershell
curl.exe -L -o "$env:TEMP\fifo-speaker-eval\spoof\aasist.onnx" `
  "https://huggingface.co/SpeechAntiSpoofingBenchmarks/AASIST/resolve/main/aasist.onnx"

& "$env:TEMP\fifo-speaker-eval\venv\Scripts\python.exe" tools\speaker\make_spoofed_spanish_pool.py `
  --voices "$env:TEMP\fifo-speaker-eval\spoof\tts" `
  --out "$env:TEMP\fifo-speaker-eval\spoofed-pool-es" --utterances 20
# --voices necesita un subdirectorio por voz con el .onnx y su .onnx.json; los
# bundles vits-piper-* de sherpa-onnx sirven tal cual.

& "$env:TEMP\fifo-speaker-eval\venv\Scripts\python.exe" tools\speaker\score_spoofing.py `
  --model "$env:TEMP\fifo-speaker-eval\spoof\aasist.onnx" `
  --bonafide "$env:TEMP\fifo-speaker-eval\real-field-corpus-es-tedx\evaluation" `
  --bonafide-filter=-clean- `
  --spoof "$env:TEMP\fifo-speaker-eval\spoofed-pool-es" `
  --normalize raw --out "$env:TEMP\fifo-speaker-eval\spoof\report-es.json"
```

`--normalize raw|peak|rms` cambia el nivel de entrada: AASIST no normaliza por
energía y el mismo audio escalado ×10 cambia el signo del logit, así que el
número solo vale si se declara el nivel.

| Voz real | Voz sintética | EER | FRR a riesgo 0,50 | FAR |
| :--- | :--- | ---: | ---: | ---: |
| LibriSpeech EN `clean` | Piper ES (5 voces, 100 clips) | 7,1 % | 14,3 % | 0,0 % |
| TEDx ES `clean` | Piper ES | 33,0 % | 74,1 % | 0,0 % |
| TEDx ES 6 condiciones | Piper ES | 45,9 % | 95,5 % | 0,0 % |

Con `peak` y `rms` el FRR español se queda en 65–74 %: no es un artefacto de
nivel. AASIST detecta el TTS y **rechaza la voz real española** (desajuste de
dominio, coherente con su tabla publicada: HABLA 37,4 %, InTheWild 43,0 %).
Por eso `NoopSpoofingDetector` sigue por defecto y la UI no promete detección de
voces sintéticas.

## 8. Canal telefónico y códec

El corpus de TEDx comparte micrófono y sala por locutor. Para quitar ese atajo
sin grabar personas, el arnés añade un canal por clip y el corpus se pasa por
códecs reales.

Canal (dentro de `RealSpeechAugmentationExportTest`, no hay segundo simulador):

```powershell
$env:FIFO_REAL_SPEECH_POOL="$env:TEMP\fifo-speaker-eval\real-pool-es-tedx"
$env:FIFO_REAL_SPEECH_EXPORT_DIR="$env:TEMP\fifo-speaker-eval\real-field-corpus-es-tedx-channel"
$env:FIFO_REAL_SPEECH_CALIBRATION="12"
$env:FIFO_REAL_SPEECH_CHANNEL="telephony"
.\gradlew.bat cleanTestDebugUnitTest testDebugUnitTest --tests "*RealSpeechAugmentationExportTest"
```

`TelephonyChannel` recorta banda (300–3400 Hz o 50–7000 Hz), inclina ±4 dB y
aplica AGC entre −30 y −22 dBFS. El enrolamiento usa una sola sesión de canal
por locutor y cada clip de uso usa otra: la llamada no suena como el
enrolamiento.

Códec (transporte, fuera del arnés):

```powershell
& "$env:TEMP\fifo-speaker-eval\venv\Scripts\python.exe" tools\speaker\apply_telephony_codec.py `
  --corpus "$env:TEMP\fifo-speaker-eval\real-field-corpus-es-tedx-channel" `
  --out "$env:TEMP\fifo-speaker-eval\real-field-corpus-es-tedx-channel-amrwb" `
  --codec amrwb --jobs 6
```

`--codec amrwb` es VoLTE (16 kHz, 23,85 kbit/s), `amrnb` es GSM (8 kHz,
12,2 kbit/s) y `opus` es VoIP (16 kHz, 24 kbit/s). Siempre devuelve WAV mono
16 kHz PCM16 y copia el manifiesto, así que las herramientas de extracción y
puntuación no cambian. Necesita `ffmpeg` con `libvo_amrwbenc`,
`libopencore_amrnb` y `libopus`; no añade dependencias de Python.

Mismo reparto (12/28) y mismo candidato, 9.408 trials:

| Variante | EER | EER cal. | FRR/FAR @FAR 1 % |
| :--- | ---: | ---: | ---: |
| TEDx base | 2,4 % | 1,4 % | 5,7 % / 0,4 % |
| + canal por clip | **2,5 %** | 1,4 % | 5,4 % / 0,8 % |
| + canal + Opus 24 kbit/s | 3,0 % | 3,6 % | 6,5 % / 0,9 % |
| + canal + AMR-WB 23,85 kbit/s | 4,2 % | 4,0 % | 12,8 % / 0,6 % |
| + canal + AMR-NB 12,2 kbit/s | 6,2 % | 7,1 % | 22,9 % / 0,8 % |
| AMR-WB sin canal | 3,6 % | 4,2 % | 12,8 % / 0,3 % |

Lectura: el canal solo no cuesta (2,4 % → 2,5 %); el códec sí (Opus +0,6,
VoLTE +1,8, GSM +3,8) y lo que se rompe es el FRR al 1 % de FAR. Con AMR-NB el
clip limpio da 0 % de FRR pero `white-5` sube a 51,8 % y `babble-15` a 37,5 %.
AS-Norm empeora todos los casos (6,2 % → 14,7 % con GSM). La calibración
transfiere dentro de ~1,5 puntos.

El micrófono del teléfono no comprime, así que AMR-NB no es el camino normal de
Fifo; sí lo es un auricular Bluetooth (mSBC/LC3) o el BLE ESP32. Por eso el
protocolo de campo debe grabar por el **camino real de captura**, no con un
micrófono USB. Y esto no es una cota rigurosa: el canal original de la charla
sigue dentro, y faltan pérdida de paquetes, distancia y salas reales. Sirve para
descartar y dimensionar, no para autorizar `Noop`.

## Reglas

- No se commitean WAV, embeddings, pesos ni informes.
- No se añade ONNX/TensorFlow/torch a la app para ejecutar esto.
- El corpus es sintético: un buen número aquí **no** certifica campo y un mal
  número **no** descarta el modelo. El corpus de campo (sección 4) es el único
  que puede autorizar un detector.
- El campo simulado (sección 5) usa voces humanas reales y sirve para prefiltar
  motores y detectar regresiones; no es campo y no autoriza a cambiar `Noop`.
- El corpus de formantes no ordena motores reales; no lo uses para elegir modelo
  ni para afirmar que un motor "no funciona" con personas. El campo simulado y,
  en última instancia, las grabaciones reales son los que deciden.
- AS-Norm se calibra **solo** con el split de calibración. Usar evaluación para
  la cohorte convertiría la tabla en una profecía.
- Canal y códec se reportan juntos: un EER sin banda y códec declarados no es
  comparable. El canal se simula en el arnés (`TelephonyChannel`); el códec, en
  `apply_telephony_codec.py`, porque es transporte y no acústica.
- Un modelo solo sustituye a `Noop` si cumple el presupuesto de 400 ms, mide
  FRR/FAR por condición, no sube audio ni embeddings y supera la validación real.
- Cualquier cambio de corpus, umbral o extractor se documenta en ADR-0048 y se
  compara con `SpeakerVerificationHarnessTest`, no con una tabla aislada.
- Para español, el corpus de referencia es TEDx (habla espontánea). VoxForge
  queda como evidencia de que un corpus leído con micrófonos domésticos necesita
  normalización por locutor: su umbral calibrado deja 94,9 % FRR.
- Un CM de spoofing se mide con `score_spoofing.py` antes de mencionarlo en la
  app. AASIST rechaza el 74 % de la voz real española limpia: un modelo con ese
  FRR no puede degradar cada turno a `UNCERTAIN`.

Ver `docs/adr/0048-speaker-identity.md` para la comparativa, la tabla del
simulador y los criterios de envío.
