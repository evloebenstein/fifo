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

# 3. Embeddings y scoring con AS-Norm
& "$env:TEMP\fifo-speaker-eval\venv\Scripts\python.exe" tools\speaker\extract_embeddings_sherpa.py `
  --manifest "$env:TEMP\fifo-speaker-eval\real-field-corpus\manifest.jsonl" `
  --model "$env:TEMP\fifo-speaker-eval\models\3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx" `
  --out "$env:TEMP\fifo-speaker-eval\real-field-corpus\embeddings.jsonl"

python tools\speaker\score_embeddings.py `
  --manifest "$env:TEMP\fifo-speaker-eval\real-field-corpus\manifest.jsonl" `
  --embeddings "$env:TEMP\fifo-speaker-eval\real-field-corpus\embeddings.jsonl" `
  --model-id 3dspeaker-campplus-zh-cn --normalize as-norm --target-far 0.01
```

Diseño: 12 locutores de calibración y 28 de evaluación, disjuntos; dentro de
cada split, mitad `enrolled` y mitad `unseen`. 3 frases de enrolamiento y 4 de
uso por enrolado, aumentadas a seis condiciones (`clean`, `living`, `pink-10`,
`white-5`, `babble-15`, `living-lombard-pink-20`). 1.020 clips, 9.408 trials,
recortados a 8 s.

| Modelo | EER crudo | FRR/FAR crudo | EER AS-Norm | FRR/FAR AS-Norm |
| :--- | ---: | ---: | ---: | ---: |
| WeSpeaker ResNet34 | 43,4 % | 83,9 % / 0,4 % | 35,7 % | 83,0 % / 0,5 % |
| WeSpeaker CAM++ | 45,6 % | 91,1 % / 1,4 % | 45,0 % | 87,5 % / 2,6 % |
| 3D-Speaker CAM++ (zh-cn) | 11,6 % | 47,0 % / 0,6 % | **5,2 %** | **10,1 % / 2,3 %** |

**AS-Norm** normaliza cada score con la media y desviación de una cohorte. La
cohorte sale **solo de calibración**: 6 perfiles y 144 clips. Evaluación no
participa en esa media. Es lo único que se entrena aquí; la red de embeddings no
se toca.

Ablación de cohorte: con 20 locutores de calibración (10 perfiles) y 20 de
evaluación, AS-Norm da 5,0 % EER y 11,7 % / 0,8 % FRR/FAR (4.800 trials), sin
mejora decisiva frente al 5,2 % de la cohorte de 6 perfiles. Baja en `clean`,
`living`, `pink-10`, `white-5` y `babble-15`, pero Lombard sube a 7,2 % EER con
solo 40 trials genuinos por condición. Más cohorte no arregla el dominio; la
varianza por locutor pesa.

Lectura:

- El ranking cambia con voz real: 3D-Speaker CAM++ queda muy por delante de los
  WeSpeaker. El corpus de formantes no mostraba eso.
- Sin AS-Norm, un solo umbral deja FRR 47 % en 3D-Speaker y ~100 % en los demás.
  Con AS-Norm baja a 10,1 %; el problema era decidir, no solo el embedding.
- Sigue sin pasar la barra: el FRR al 1 % supera el 10 % en `living`, `white-5`,
  `babble-15` y Lombard. Y es lectura limpia aumentada, no una calle ni un móvil.
- Es inglés y la cohorte es pequeña (6 perfiles). Repetirlo con más locutores de
  calibración y con español sigue pendiente.

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
- Un modelo solo sustituye a `Noop` si cumple el presupuesto de 400 ms, mide
  FRR/FAR por condición, no sube audio ni embeddings y supera la validación real.
- Cualquier cambio de corpus, umbral o extractor se documenta en ADR-0048 y se
  compara con `SpeakerVerificationHarnessTest`, no con una tabla aislada.

Ver `docs/adr/0048-speaker-identity.md` para la comparativa, la tabla del
simulador y los criterios de envío.
