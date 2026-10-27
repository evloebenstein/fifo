# Wake word offline (ADR-0047)

Herramientas de **desarrollo** para exportar el corpus sintético del arnés y
entrenar un detector log-mel + CNN fuera de la JVM. No forman parte de la app,
no se ejecutan en la suite normal y sus artefactos no se commitean.

El resultado de ADR-0047 es negativo: el FRR de campo baja a ~33 %, pero el
detector no cumple ≤1 FA/h. `NoopWakeWordDetector` sigue siendo el valor por
defecto. Estos pasos existen para reproducir la medida y comparar el siguiente
enfoque con la misma tabla.

## 1. Exportar el corpus

El exportador es una prueba de JUnit apagada por defecto. Escribe WAV mono a
16 kHz (PCM16) y un `manifest.jsonl` con etiqueta, split, locutor y condición.

```powershell
$env:FIFO_WAKEWORD_EXPORT_DIR="C:\Users\tu_usuario\AppData\Local\Temp\fifo-wakeword-corpus"
.\gradlew.bat testDebugUnitTest --tests "*WakeWordCorpusExportTest"
```

Sin la variable, la prueba se omite. El corpus se regenera de forma
determinista; no hace falta conservarlo entre ejecuciones. Con el corpus actual
son 5.905 clips y unos 456 MB.

## 2. Entrenar y evaluar

Requiere Python 3 con `torch` y `numpy` (ver `requirements.txt`). Son
dependencias solo de desarrollo: la app no las usa.

```powershell
python -m pip install -r tools/wakeword/requirements.txt
python tools/wakeword/train_mel_cnn.py `
  --corpus C:\Users\tu_usuario\AppData\Local\Temp\fifo-wakeword-corpus `
  --out C:\Users\tu_usuario\AppData\Local\Temp\fifo-wakeword-model `
  --epochs 16
```

El entrenador imprime una tabla por split, calibra el umbral en las
conversaciones de validación y guarda `modelo.pt` y `modelo.json`.

Para reevaluar un modelo ya entrenado, sin volver a entrenar:

```powershell
python tools/wakeword/train_mel_cnn.py `
  --corpus C:\Users\tu_usuario\AppData\Local\Temp\fifo-wakeword-corpus `
  --out C:\Users\tu_usuario\AppData\Local\Temp\fifo-wakeword-eval `
  --checkpoint C:\Users\tu_usuario\AppData\Local\Temp\fifo-wakeword-model.pt `
  --persistence 2
```

`--persistence` es el número de ventanas consecutivas por encima del umbral que
confirman una detección. Dos es el punto documentado en ADR-0047; una sola
ventana dispara mucho más en conversación.

## Qué mide

- **FRR**: porcentaje de positivos no detectados.
- **Negativos difíciles**: palabras que comparten fonemas con "fifo" y que el
  detector acepta.
- **FA/h**: eventos por hora en habla continua. Es la barra que decide: ≤1 FA/h.
- **p95 de latencia**: retraso entre el final de la palabra y la detección.

## Reglas

- No se commitean WAV, `.pt` ni `.json` de modelos.
- No se añade `torch` a la app ni al APK.
- Un buen número en el corpus sintético **no** certifica el rendimiento de
  campo: el corpus de campo tiene 0,076 h de conversación y un solo evento ya
  son 13,2 FA/h.
- Cualquier cambio de corpus, ventana o umbral se mide contra
  `WakeWordHarnessTest` y se documenta en un ADR; no se cambia el detector de
  producción por una tabla aislada.

Ver `docs/adr/0047-export-corpus-and-mel-cnn.md` para las tablas completas y los
cuatro fallos de implementación que el proceso dejó escritos.
