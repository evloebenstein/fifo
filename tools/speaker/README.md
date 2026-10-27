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

Este paso lo hace el toolkit del modelo, no este repositorio. El contrato es un
JSONL con una línea por clip del manifiesto:

```json
{"id": "test-spk-a-clean-0", "embedding": [0.0123, -0.0456, "..."]}
```

Pseudo-código de extracción:

```python
import json
from pathlib import Path

root = Path(r"C:\Users\tu_usuario\AppData\Local\Temp\fifo-speaker-corpus")
manifest = [json.loads(line) for line in open(root / "manifest.jsonl", encoding="utf-8")]

with open(root / "embeddings.jsonl", "w", encoding="utf-8") as out:
    for row in manifest:
        wav = read_wav(root / row["audio"])      # mono 16 kHz
        vector = model.embed(wav)                # 1-D float, L2-normalizable
        out.write(json.dumps({"id": row["id"], "embedding": vector.tolist()}) + "\n")
```

Candidatos: ReDimNet-B0/B1 exportado a ONNX con WeSpeaker o sherpa-onnx,
ECAPA-TDNN con sherpa-onnx, o Picovoice Eagle. Cualquier extractor sirve si
produce un vector por clip; no se asume un frontend concreto.

## 3. Puntuar y calibrar

`score_embeddings.py` solo usa la biblioteca estándar (no necesita `numpy`):

```powershell
python tools/speaker/score_embeddings.py `
  --manifest "C:\Users\tu_usuario\AppData\Local\Temp\fifo-speaker-corpus\manifest.jsonl" `
  --embeddings "C:\Users\tu_usuario\AppData\Local\Temp\fifo-speaker-corpus\embeddings.jsonl" `
  --model-id redimnet-b0-onnx `
  --target-far 0.01 `
  --out "C:\Users\tu_usuario\AppData\Local\Temp\fifo-speaker-corpus\report.json"
```

Qué reporta:

- umbral calibrado con `calibration` al FAR objetivo;
- FAR de calibración y FRR/FAR de `evaluation`;
- EER de referencia (umbral óptimo, solo como techo);
- tablas por condición y por duración.

## Reglas

- No se commitean WAV, embeddings, pesos ni informes.
- No se añade ONNX/TensorFlow/torch a la app para ejecutar esto.
- El corpus es sintético: un buen número aquí **no** certifica campo. Hace falta
  un corpus real de 10–15 personas en silencio, TV, calle y 1–3 m.
- Un modelo solo sustituye a `Noop` si cumple el presupuesto de 400 ms, mide
  FRR/FAR por condición, no sube audio ni embeddings y supera la validación real.
- Cualquier cambio de corpus, umbral o extractor se documenta en ADR-0048 y se
  compara con `SpeakerVerificationHarnessTest`, no con una tabla aislada.

Ver `docs/adr/0048-speaker-identity.md` para la comparativa, la tabla del
simulador y los criterios de envío.
