# ADR-0047: Exportar el corpus y medir un detector log-mel con CNN

**Status:** Accepted (resultado negativo; no se envía)

**Date:** 2026-10-26

**Author:** Architecture Review — Del corpus al modelo, fuera de la JVM

---

## Context

ADR-0046 cerró con una conclusión y un encargo: el vector CMVN de 257
dimensiones no separa "fifo" del habla continua, y el siguiente paso era
exportar el corpus y entrenar con una representación mejor y muchas más voces.
La vía natural era openWakeWord o microWakeWord, pero este entorno no tiene
TensorFlow ni ONNX, y sus pipelines exigen descargas y versiones que Python 3.14
todavía no resuelve. Lo que sí hay es `torch` 2.12 (CPU), `numpy` y `scipy`.

Este ADR documenta el paso que sí se pudo ejecutar de verdad:

1. **Exportar** el corpus sintético de ADR-0044/0045/0046 a WAV + manifiesto,
   como contrato estable entre la JVM y cualquier entrenador.
2. **Entrenar** un detector log-mel + CNN pequeño con esos clips, en Python,
   sin modelos preentrenados, sin descargas y sin tocar la app.
3. **Medir** con la misma tabla que el arnés: FRR por condición, negativos
   difíciles, FA/h y latencia.

La pregunta era si la representación y la cobertura de voces cierran la brecha
hacia **≤1 FA/h**. La respuesta corta es que mejoran mucho el número, pero no
lo suficiente para enviar nada.

---

## Lo que se construyó

### 1. Contrato de exportación

`WakeWordCorpusExportTest` escribe un WAV por clip y un `manifest.jsonl` con una
línea por clip. Está apagado por defecto y se activa con una variable de
entorno:

```powershell
$env:FIFO_WAKEWORD_EXPORT_DIR="C:\ruta\al\corpus"
.\gradlew.bat testDebugUnitTest --tests "*WakeWordCorpusExportTest"
```

| Campo | Contenido |
| :--- | :--- |
| `id`, `audio` | Identificador estable y ruta relativa del WAV |
| `split` | `train`, `validation`, `base`, `field` o `enroll` |
| `family` | `positive`, `hard_negative` o `conversation` |
| `label` | `1` para "fifo", `0` para el resto |
| `speaker`, `enrolled` | Locutor simulado y si está en el banco de enrolamiento |
| `condition`, `snr_db`, `noise`, `room`, `lombard` | Condición acústica |
| `duration_ms`, `word_start_ms`, `word_end_ms` | Etiqueta temporal de la palabra |
| `sample_rate` | Siempre 16.000 Hz |

Formato de audio: **WAV mono, PCM 16 bits, 16 kHz, little-endian**. No se usa
`javax.sound`, así que la exportación no depende de la máquina ni del sistema
operativo. Los WAV **no se commitean**: se regeneran de forma determinista y el
manifiesto es la fuente de verdad del experimento.

Exportación medida con el corpus actual:

| Split | Clips |
| :--- | ---: |
| `train` | 5.200 |
| `validation` | 288 |
| `base` | 246 |
| `field` | 168 |
| `enroll` | 3 |
| **Total** | **5.905** |

Ocupa unos 456 MB en disco y el manifiesto pesa 2,16 MB. Por eso vive fuera del
repositorio y por eso el test está apagado: la suite normal no paga ni el tiempo
ni el espacio.

### 2. Entrenador log-mel + CNN

`tools/wakeword/train_mel_cnn.py` es una herramienta de desarrollo, no forma
parte de la app ni de la suite de pruebas.

- **Frontend:** log-mel de 40 bandas, ventana de 25 ms, salto de 10 ms, FFT de
  512, rango de 20 a 7.600 Hz. Normalización CMVN **por ventana**, que es lo que
  puede hacer el detector en producción (no ve el clip completo).
- **Ventana:** 0,8 s (80 tramas). Cabe entera en cualquier clip de palabra del
  corpus (910 ms el más corto). Si un clip es más corto, se replica el borde;
  **nunca se rellena con ceros**.
- **Modelo:** CNN pequeña (16 → 32 → 64 canales, `BatchNorm`, `ReLU`,
  `MaxPool`), cabeza densa de 64 unidades, dropout 0,3 y una salida.
- **Entrenamiento:** `BCEWithLogitsLoss` con peso positivo limitado a 4,
  AdamW, `CosineAnnealingLR`, 16 epochs, 20.000 ventanas (7.200 positivas y
  12.800 negativas) y `SpecAugment` suave (dos máscaras de tiempo y dos de
  frecuencia).
- **Umbral:** se calibra sobre las **conversaciones de validación**, no sobre
  palabras sueltas. Se barre de menor a mayor y se queda el primer umbral que
  deja ≤1 FA/h. Los negativos difíciles se reportan aparte para no inflar el
  umbral con ellos.
- **Persistencia:** una detección exige **dos ventanas consecutivas** por encima
  del umbral. Una palabra real mantiene el score cientos de milisegundos; un
  pico aislado de conversación, no.
- **Evaluación:** compara **logits**, no probabilidades. `sigmoid` en `float32`
  satura en 1,0 y vuelve indistinguibles dos negativos justo en la cola, que es
  donde se decide el FA/h. La probabilidad se calcula solo para el informe.

Uso:

```powershell
python tools/wakeword/train_mel_cnn.py --corpus C:\ruta\al\corpus --out C:\ruta\al\modelo
python tools/wakeword/train_mel_cnn.py --corpus C:\ruta\al\corpus --out C:\ruta\al\eval --checkpoint C:\ruta\al\modelo.pt --persistence 2
```

Dependencias: `torch` y `numpy`. Se documentan en
`tools/wakeword/requirements.txt` como dependencia **solo de desarrollo**. La
app no las usa y el APK no cambia.

### 3. Corpus de entrenamiento

ADR-0046 midió que con 40 locutores el modelo memoriza. Aquí se subió a **100
locutores de entrenamiento** y **16 de validación**, y se alinearon las
conversaciones: antes solo había habla continua en condiciones secas, mientras
que el corpus de campo lleva sala, Lombard y balbuceo. El modelo aprendía a
ignorar un tipo de negativo que nunca veía.

Cada locutor aporta:

- 1 positivo y 4 negativos difíciles por cada una de las 9 condiciones;
- 1 conversación por cada condición con ruido (7), incluidas las de sala y
  Lombard.

Eso da 5.200 clips de entrenamiento y 288 de validación, con locutores,
semillas y posiciones de sala que no aparecen en la evaluación.

El corpus de ADR-0046 no se sobrescribe: `adr0046TrainingClips()` y
`adr0046ValidationClips()` conservan los 40 + 8 locutores y las conversaciones
secas con los que se midió esa tabla. `WakeWordLearningTest` los usa, de modo
que el resultado negativo anterior sigue siendo reproducible aunque el corpus
de exportación crezca.

---

## Lo que se midió

Punto de trabajo con el umbral calibrado en validación (logit 1,529,
p = 0,8219) y persistencia de 2 ventanas:

| Corpus | Clips | FRR | Negativos difíciles | FA/h | p95 latencia |
| :--- | ---: | ---: | ---: | ---: | ---: |
| Validación | 288 | 37,5 % | 11,5 % | 0,0 | 229 ms |
| Base | 246 | 50,0 % | 7,6 % | 0,0 | 267 ms |
| **Campo** | 168 | **33,3 %** | **6,2 %** | **13,2** | 267 ms |

Por condición de campo:

| Condición | FRR | Negativos difíciles | FA/h |
| :--- | ---: | ---: | ---: |
| `office-clean` | 0,0 % | 0,0 % | 0,0 |
| `living-pink-20db-lombard` | 37,5 % | 7,8 % | 26,3 |
| `kitchen-babble-10db-lombard` | 62,5 % | 4,7 % | 0,0 |

El único falso acepto de campo ocurre en el salón. La cocina, que es la
condición más difícil (balbuceo a 10 dB con Lombard), no dispara pero se come el
62,5 % de los positivos: el modelo prefiere callarse antes que equivocarse.

### El compromiso de la persistencia

Sin persistencia (una sola ventana) el umbral calibrado sube a logit 8,658 y el
campo queda así:

| Persistencia | Umbral | FRR campo | Negativos difíciles | FA/h |
| :--- | ---: | ---: | ---: | ---: |
| 1 ventana | 8,658 | 79,2 % | 1,6 % | 0,0 |
| 2 ventanas | 1,529 | 33,3 % | 6,2 % | 13,2 |

Exigir dos ventanas es lo que baja el FRR de 79,2 % a 33,3 % sin aceptar nada
en validación. Pero en campo aparece un pico sostenido de 200 ms en el salón que
la validación no tenía. **La persistencia no inventa información: cambia el
punto de trabajo y traslada el problema a la cola de conversación.**

### El techo del enfoque

Si se elige el umbral mirando el propio corpus de campo (oráculo, no
desplegable), el mejor punto con ≤1 FA/h es:

| Umbral | FRR campo | Negativos difíciles | FA/h |
| ---: | ---: | ---: | ---: |
| 3,0 | 41,7 % | 3,9 % | 0,0 |

Es decir: incluso con la respuesta del examen en la mano, **no se baja del 40 %
de FRR**. El techo del enfoque con este corpus está ahí, no en la calibración.

### Comparación con ADR-0046

| Enfoque | FRR campo | Negativos difíciles | FA/h |
| :--- | ---: | ---: | ---: |
| Logística 257 dimensiones (40 locutores) | 100 % | 7,0 % | 343 |
| Red 257→24→1 (12 locutores) | 12,5 % | 98,4 % | 1.924 |
| Log-mel + CNN (100 locutores, 1 ventana) | 79,2 % | 1,6 % | 0,0 |
| **Log-mel + CNN (100 locutores, 2 ventanas)** | **33,3 %** | **6,2 %** | **13,2** |

La representación importa: con la misma barra, el FRR de campo baja de 100 % a
33 % y los negativos difíciles dejan de dispararse. Pero el detector sigue
**sin cumplir ≤1 FA/h** y el FRR es demasiado alto para uso real.

---

## Lo que enseñó el proceso

Estos cuatro fallos no son anécdotas: son trampas que cualquier entrenador
sobre este corpus va a encontrar.

1. **Evaluar en `train()`.** BatchNorm y Dropout activos en evaluación
   saturaron todos los scores a 1,0. La primera tabla era basura.
2. **Rellenar con ceros.** Las ventanas que se salían del clip se completaban
   con ceros, y el modelo aprendió que un bloque de ceros al final era la
   palabra. En el corpus sintético todos los clips de palabra duran ~1 s, así
   que el atajo funcionaba en entrenamiento y enmascaraba todo lo demás.
3. **Calibrar contra negativos difíciles.** El umbral se fijaba con el peor
   negativo, no con la conversación. Eso lo dejaba altísimo y el FRR en 100 %.
   La barra es FA/h en habla continua; los negativos difíciles son diagnóstico,
   no calibración.
4. **Desajuste de canal en los negativos.** Las conversaciones de entrenamiento
   eran secas y las de campo llevaban sala y Lombard. El modelo no había visto
   el negativo que luego le tocaba juzgar.

El quinto aprendizaje es de producto: **la persistencia pertenece al detector**,
no al script de evaluación. Si algún día se envía un detector acústico, su
interfaz tendrá que exponer scores por trama o una histéresis equivalente.
Posponerlo al consumidor convierte una decisión de seguridad en un detalle.

---

## Decision

**No se envía el modelo.** `NoopWakeWordDetector` sigue siendo el valor por
defecto y la invocación sigue dependiendo de la transcripción. El `.pt` no se
commitea y ningún artefacto del entrenador entra en el APK.

Se conservan como herramientas de desarrollo:

- `WakeWordCorpusExportTest`, apagado por defecto con `FIFO_WAKEWORD_EXPORT_DIR`;
- `tools/wakeword/train_mel_cnn.py`, con `--checkpoint` para reevaluar sin
  reentrenar;
- `tools/wakeword/README.md` y `requirements.txt`;
- el corpus sintético ampliado a 100 + 16 locutores.

La barra de producción no cambia: **≤1 FA/h y validación con grabaciones
reales** antes de enchufar nada en `VoiceSessionPipeline`.

### Lo que NO se hace todavía

1. **Enviar el mel-CNN.** 33 % de FRR y 13 FA/h no es un detector, es un
   experimento.
2. **Subir el umbral para "cumplir" la barra.** Ya se midió: a 0 FA/h el FRR de
   campo es 41,7 % en el mejor caso y 79,2 % con una sola ventana. Cumplir la
   barra escondiendo la sensibilidad no es cumplirla.
3. **Sustituir voces reales por más sintéticas.** El simulador ya no es el
   cuello de botella principal; el siguiente salto necesita voces y horas de
   conversación reales.
4. **Meter `torch` en la app.** El entrenamiento es offline; el runtime de
   producción, si llega, será ONNX o un modelo cuantizado, no PyTorch.

---

## Consecuencias

**Positivas**

- Queda demostrado que la representación era parte del problema: log-mel + CNN
  baja el FRR de campo de 100 % a 33 % con la misma barra.
- El contrato de exportación es estable y reusable por openWakeWord,
  microWakeWord o cualquier entrenador futuro.
- Los cuatro fallos de implementación quedan escritos con su síntoma, para que
  nadie los repita.
- El experimento es determinista y reproducible, y no añade dependencias a la
  app ni binarios al repositorio.

**Negativas / límites**

- Sigue sin haber un detector enviable; el trabajo de invocación acústica no
  avanza en producción.
- El corpus de campo tiene **0,076 h de conversación**: un solo evento ya son
  13,2 FA/h. La métrica no tiene resolución para certificar nada.
- El corpus sintético no cubre voces reales, micrófonos reales ni televisión
  real; sus números miden el simulador.
- El modelo y el corpus viven fuera del repo; si nadie los regenera, el
  experimento se pudre. El ADR fija la fecha y los comandos para detectarlo.

---

## Próximo paso recomendado

1. **Voces reales o TTS:** sustituir los locutores de formantes por Piper
   `es_ES`/`es_MX` y grabaciones propias, manteniendo el mismo manifiesto.
2. **Horas de conversación:** llevar el negativo de validación y campo a decenas
   de horas; con 0,076 h no se puede medir 1 FA/h.
3. **Entrenar openWakeWord/microWakeWord** sobre el mismo manifiesto y comparar
   con esta tabla, no con números de otro corpus.
4. **Persistencia en la interfaz** de `WakeWordDetector` (score por trama o
   histéresis) antes de considerar el envío.
5. **Validación de campo real** por condición; solo si supera la barra de
   ADR-0044 se enchufa en `VoiceSessionPipeline`.

---

## Files Changed

| File | Change |
| :--- | :--- |
| `test/.../wakeword/WakeWordCorpusExportTest.kt` | **New** — exporta WAV PCM16 + `manifest.jsonl`; apagado por defecto. |
| `test/.../wakeword/LearnedWakeWord.kt` | 100 locutores de entrenamiento, 16 de validación, metadatos de clip y conversaciones en todas las condiciones con ruido; copia congelada de 40 + 8 para ADR-0046. |
| `test/.../wakeword/WakeWordLearningTest.kt` | Usa la copia congelada de ADR-0046 en vez del corpus ampliado. |
| `tools/wakeword/train_mel_cnn.py` | **New** — log-mel, CNN, calibración por FA/h, persistencia, evaluación y `--checkpoint`. |
| `tools/wakeword/README.md` | **New** — flujo exportar → entrenar → evaluar. |
| `tools/wakeword/requirements.txt` | **New** — `torch` y `numpy`, solo desarrollo. |
| `docs/adr/0046-synthetic-corpus-does-not-train.md` | Enlace al paso siguiente. |
| `docs/README.md`, `AGENTS.md`, `GLOSSARY.md`, `pipeline/README.md` | Índices, términos y reglas. |
| `.gitignore` | Corpus y checkpoints locales fuera del repositorio. |

## Verification

- `testDebugUnitTest testReleaseUnitTest compileReleaseKotlin` en verde.
- `WakeWordHarnessTest` sigue 10/10 en ambas variantes y con los mismos números.
- `WakeWordCorpusExportTest` se omite sin `FIFO_WAKEWORD_EXPORT_DIR`;
  `WakeWordLearningTest` y `AugmentedTemplateTest` se omiten sin
  `FIFO_WAKEWORD_EXPERIMENT=1`.
- Con la variable, la exportación produce 5.905 clips y un manifiesto con una
  línea por clip; dos exportaciones seguidas dan el mismo SHA-256 del
  manifiesto.
- El entrenador completa 16 epochs, calibra en validación y escribe `.pt` y
  `.json`; `--checkpoint` reevalúa sin reentrenar.
- El detector de producción no cambia: `NoopWakeWordDetector` sigue por defecto.
