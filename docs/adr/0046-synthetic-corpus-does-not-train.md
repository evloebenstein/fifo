# ADR-0046: El corpus sintético mide, pero no entrena el modelo

**Status:** Accepted (resultado negativo; no se envía)

**Date:** 2026-10-26

**Author:** Architecture Review — Del simulador al modelo

---

## Context

ADR-0045 dejó el diagnóstico escrito: con plantillas secas, el FRR en un despacho
reverberante es 87,5 %; con plantillas de la misma sala cae a 12,5 %. La
conclusión natural era entrenar un modelo con las condiciones simuladas
(sala, Lombard, VTLP y ruido) y medirlo contra el mismo arnés.

Este ADR documenta ese intento. Se hicieron tres cosas, todas en Kotlin puro y
solo en `test`, sin dependencias ni descargas:

1. **Regresión logística** sobre el vector CMVN de 256 bandas × rejilla, más la
   duración como variable explícita.
2. **Red pequeña** 257 → 24 (ReLU) → 1, con el mismo corpus.
3. **Plantillas aumentadas**: un solo enrolamiento se convierte en plantillas de
   sala, de ruido y de esfuerzo vocal.

La pregunta era si alguno alcanzaba el punto de trabajo de ADR-0044: **≤1 FA/h**
y un FRR de campo razonable sin disparar en habla continua.

---

## Lo que se midió

Umbral calibrado con un conjunto de validación de locutores que el modelo nunca
vio. El campo se midió después, con el corpus de ADR-0045.

### Modelos entrenados

| Modelo | Locutores | Ventanas | Umbral | FRR despacho | FRR salón | FRR cocina | Negativos aceptados | FA/h |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Regresión logística | 12 | 20.476 | 0,90 | 37,5 % | 25,0 % | 62,5 % | 62,5 % | **3.229** |
| Red 257→24→1 | 12 | 4.354 | 0,90 | 12,5 % | 12,5 % | 0,0 % | 98,4 % | **1.924** |
| Regresión logística | 40 | 68.009 | 0,90 | 100 % | 75,0 % | 100 % | 7,0 % | **343** |

La red con 12 locutores **memoriza**: acierta los positivos de campo (0–12,5 %
de FRR) y a la vez dispara en el 98,4 % de los negativos difíciles. La logística
con 40 locutores no llega a aprender: en el corpus base, que es limpio, tiene
**73,1 % de FRR y 834 FA/h**. El problema no es el umbral; es la representación.

### Plantillas aumentadas

Mismo detector de plantilla, mismo umbral 0,65, distintos bancos de enrolamiento:

| Banco | Plantillas | FRR despacho | FRR salón | FRR cocina | Negativos | FA/h |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| Secas (ADR-0045) | 3 | 87,5 % | 100 % | 100 % | 0,0 % | 0,0 |
| Salas (seco + 3 salas + esfuerzo) | 24 | **0,0 %** | 87,5 % | 100 % | 9,4 % | 26,4 |
| Condiciones (sala + ruido + esfuerzo) | 9 | 37,5 % | 87,5 % | 100 % | 0,0 % | 13,2 |

A 0,70 las plantillas de sala bajan a 0 FA/h, pero el despacho sube a 25 % y el
salón y la cocina siguen al 100 %. **Aumentar el enrolamiento arregla el canal,
no el ruido.** Pedirle al usuario que grabe en su salón con la televisión puesta
tampoco cierra la brecha: el score de la palabra en balbuceo se solapa con el de
la conversación.

---

## Por qué falla

1. **La cola de negativos manda.** El detector evalúa 19 duraciones cada 32 ms:
   unas 600 ventanas por segundo. Un 1 % de ventanas negativas con score alto se
   convierte en miles de falsos aceptos por hora. Para cumplir 1 FA/h hace falta
   una precisión por ventana del orden de 10⁻⁶, no del 99 %.
2. **El modelo aprende el locutor y la condición, no la palabra.** Con 12
   locutores sintéticos, la red separa perfectamente el entrenamiento y falla en
   los 8 locutores de campo. Con 40, la clase positiva sigue siendo multimodelo
   (tono, tracto, ritmo, sala, ruido) y una frontera lineal no la cubre.
3. **El vector CMVN no es un embedding.** El detector de plantilla funciona en
   limpio porque compara contra ejemplos concretos; el modelo entrenado promedia
   esa información y pierde la especificidad de "fifo" frente a "fino", "fifa" o
   "fito".
4. **El corpus sintético es demasiado pequeño para entrenar y demasiado fácil
   para servir de validación.** 24 positivos de campo y 0,076 h de conversación
   no resuelven 1 FA/h: el propio arnés lo dice en ADR-0044. Entrenar con 622
   positivos y validar con 24 es una receta de sobreajuste.

La causa de fondo es la misma que documentó openWakeWord: un KWS pequeño
necesita un **frontend de embeddings entrenado con muchas voces** y un
clasificador entrenado con datos aumentados. Un modelo de 257 dimensiones
entrenado en JVM sobre voz de formantes no es ese modelo.

---

## Decision

**No se envía ninguno de los modelos.** `NoopWakeWordDetector` sigue siendo el
valor por defecto y la invocación sigue dependiendo de la transcripción.

Los tres experimentos quedan en `app/src/test/.../wakeword/` como evidencia
reproducible, **apagados por defecto**:

```powershell
$env:FIFO_WAKEWORD_EXPERIMENT="1"
.\gradlew.bat testDebugUnitTest --tests "*WakeWordLearningTest" --tests "*AugmentedTemplateTest"
```

Sin esa variable, JUnit los marca como omitidos y la suite normal no paga los
minutos de entrenamiento.

### Lo que sí se conserva

- El arnés de ADR-0044/0045 sigue siendo la **tabla de verdad** para comparar
  motores: FRR por condición, negativos difíciles, FA/h y latencia.
- El corpus de entrenamiento (`WakeWordTrainingCorpus`) es reusable: cuando
  exista el pipeline de openWakeWord, sus clips se exportan tal cual, con salas,
  Lombard, VTLP y ruido.
- El diagnóstico de canal de ADR-0045 se confirma con una medida nueva: las
  plantillas de sala llevan el despacho de 87,5 % a 0 %, y el ruido sigue
  ganando. No es un problema de umbral.

### Lo que NO se hace todavía

1. **Entrenar openWakeWord o microWakeWord.** Requiere Python, TensorFlow/ONNX y
   un corpus de voces mucho mayor; no cabe en una prueba de JVM.
2. **Usar TTS para multiplicar voces.** Es la vía correcta para el corpus
   español, pero hay que decidir voces, licencias y pronunciación de "Fifo".
3. **Recolectar datos reales con la cascada.** Sigue pendiente de consentimiento,
   cifrado local y retención (flywheel).
4. **Cambiar el umbral del detector de plantilla para "mejorar" el campo.** Ya
   se probó: bajar el umbral recupera sensibilidad a costa de falsos aceptos que
   ADR-0044 ya había medido.

---

## Consecuencias

**Positivas**

- Se evitó enviar un detector que se ve bien en el corpus y dispara en una
  conversación real. El resultado negativo es más valioso que un número
 optimista.
- La causa está aislada: no es el umbral, ni el tamaño del modelo, ni la
  calibración; es la representación y la cantidad de voces.
- El experimento es reproducible y determinista, y no añade dependencias.
- El camino de producción queda claro: openWakeWord/microWakeWord con embeddings
  y un corpus multi-locutor; el arnés ya existe.

**Negativas / límites**

- El repositorio no tiene todavía un modelo que mejore la invocación léxica.
- El corpus sintético no puede sustituir a las grabaciones reales para entrenar.
- Los números de FA/h siguen teniendo poca resolución (0,076 h de conversación).
- Los experimentos apagados se pudren si nadie los ejecuta; el ADR fija la fecha
  y los números para detectarlo.

---

## Próximo paso recomendado

1. **Exportar el corpus** (`WakeWordCorpus` + `WakeWordTrainingCorpus`) a WAV y
   metadatos, fuera de la app, con una tarea de Gradle o un `main` de pruebas.
2. **Entrenar openWakeWord** con TTS español (Piper `es_ES`/`es_MX`), RIRs
   sintéticas y ruido; validar con locutores no vistos.
3. **Medir contra la barra de ADR-0044** en el arnés actual: FRR limpio ≤5 %,
   20 dB ≤10 %, 10 dB ≤25 %, 5 dB ≤50 %, p95 ≤300 ms y ≤1 FA/h.
4. **Solo si supera la barra**, enchufar el detector en `VoiceSessionPipeline` y
   validar con grabaciones de campo (DiPCo/MUSAN/DEMAND o una campaña propia).
5. **Flywheel**: cuando haya usuarios, usar la cascada para etiquetar positivos
   confirmados y negativos rechazados, con consentimiento y retención explícita.

---

## Files Changed

| File | Change |
| :--- | :--- |
| `test/.../wakeword/LearnedWakeWord.kt` | **New** — corpus de entrenamiento, ventanas etiquetadas, logística, red 257→24→1 y detector aprendido (solo pruebas). |
| `test/.../wakeword/WakeWordLearningTest.kt` | **New** — entrena, calibra con validación y mide campo y corpus base; apagado por defecto. |
| `test/.../wakeword/AugmentedTemplateTest.kt` | **New** — compara plantillas secas, de sala y de condición; apagado por defecto. |
| `docs/adr/0045-field-simulation-for-invocation.md` | Enlace al intento de entrenamiento. |
| `docs/README.md`, `AGENTS.md`, `GLOSSARY.md`, `pipeline/README.md` | Índices, términos y reglas. |

## Verification

- `testDebugUnitTest testReleaseUnitTest compileReleaseKotlin` en verde.
- `WakeWordHarnessTest` sigue 10/10 en ambas variantes y con los mismos números.
- Los dos experimentos nuevos se omiten sin `FIFO_WAKEWORD_EXPERIMENT=1` y, con
  la variable, imprimen las tablas de este ADR.
- El detector de producción no cambia: `NoopWakeWordDetector` sigue por defecto.
