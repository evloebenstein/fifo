# Speaker identity offline (ADR-0048)

Herramientas de **desarrollo** para evaluar un modelo real de verificación de
locutor contra el corpus sintético de Fifo. No forman parte de la app, no se
ejecutan en la suite normal y no se commitea ningún WAV, peso ni embedding.

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

## Reglas

- No se commitean WAV, embeddings, pesos ni informes.
- No se añade ONNX/TensorFlow/torch a la app para ejecutar esto.
- El corpus es sintético: un buen número aquí **no** certifica campo y un mal
  número **no** descarta el modelo. Hace falta un corpus real de 10–15 personas
  en silencio, TV, calle y 1–3 m.
- No elijas motor comparando EER en este corpus; solo sirve para reproducir el
  contrato y detectar regresiones del pipeline.
- Un modelo solo sustituye a `Noop` si cumple el presupuesto de 400 ms, mide
  FRR/FAR por condición, no sube audio ni embeddings y supera la validación real.
- Cualquier cambio de corpus, umbral o extractor se documenta en ADR-0048 y se
  compara con `SpeakerVerificationHarnessTest`, no con una tabla aislada.

Ver `docs/adr/0048-speaker-identity.md` para la comparativa, la tabla del
simulador y los criterios de envío.
