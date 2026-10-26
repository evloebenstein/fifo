# ADR-0044: Arnés de medición con corpus sintético para la invocación

**Status:** Accepted

**Date:** 2026-10-26

**Author:** Architecture Review — Medición de la invocación sin grabaciones

---

## Context

ADR-0043 dejó la cascada acústica enchufada pero sin modelo: `NoopWakeWordDetector`
es el valor por defecto y la mejora no se nota hasta que exista un detector
medido. Medir exige un corpus, y no hay grabaciones reales: el requisito explícito
es **simularlo**.

Sin números, la decisión "openWakeWord y no una huella espectral" es una opinión.
Con números, se puede afirmar dónde está el techo del enfoque barato y qué tiene
que superar un modelo entrenado. También hace falta una prueba de regresión que
corra en JVM, sin emulador, sin descargas y sin binarios en el repositorio.

Tres preguntas concretas:

1. ¿Cuánto aguanta un comparador de plantillas espectrales con ruido?
2. ¿Cuántos falsos aceptos por hora produce sobre habla continua?
3. ¿La costura `WakeWordDetector` se comporta como se espera cuando se le
   alimentan tramas reales de captura?

---

## Decision

Se construye un **arnés determinista** en el source set de pruebas, con tres
piezas: corpus sintético, detector de referencia y tabla de métricas.

### 1. Corpus sintético, determinista y versionable

`test/.../session/wakeword/`:

| Archivo | Papel |
| :--- | :--- |
| `SyntheticSpeech.kt` | Sintetizador por formantes (fuente glotal + resonadores), fonemas, locutores y palabras. |
| `SimulatedNoise.kt` | Ruido blanco, rosa (Kellet) y balbuceo de cinco voces; mezcla por SNR. |
| `WakeWordCorpus.kt` | Casos, condiciones, semillas estables y ventana temporal de la palabra. |

Composición:

| Familia | Casos | Para qué |
| :--- | :--- | :--- |
| Positivos ("fifo") | 6 locutores × 13 condiciones = **78** | FRR por SNR y latencia |
| Negativos difíciles | 8 palabras × 6 locutores × 3 condiciones = **144** | `fino`, `hijo`, `vivo`, `fifa`, `fito`, `pito`, `fido`, `cinco` |
| Conversación | 6 locutores × 4 condiciones = **24** (~0,10 h) | Falsos aceptos por hora |

- **Seis locutores, tres "enrollados" y tres no vistos.** Sin esa separación, un
  comparador de plantillas parecería mejor de lo que es: mide memoria, no
  generalización.
- **Condiciones**: limpio, y 20/10/5/0 dB con ruido blanco, rosa y balbuceo. El
  blanco enmascara las fricativas; el balbuceo es el peor caso porque suena a
  habla.
- **Semillas FNV-1a** derivadas del id del caso: el mismo audio en cada
  ejecución, en cualquier JVM. Nada de `String.hashCode`.
- **La etiqueta temporal** sale de `FormantSynth.wordBounds`, no de un umbral de
  energía: así la ventana de acierto no depende del detector que se evalúa.

### 2. El arnés alimenta la costura real

`WakeWordHarnessTest` no llama a funciones internas del detector: construye
tramas de `AudioConfig.CHUNK_BYTES` (1024 bytes, lo que entrega
`PhoneMicRecorder`) y llama a `WakeWordDetector.accept(frame, nowMillis)`. Lo que
se mide es exactamente lo que se enchufa en `VoiceSession`.

| Métrica | Definición |
| :--- | :--- |
| **Acierto (TP)** | Alguna detección en `[inicio − 300 ms, fin + 500 ms]` |
| **FRR** | 1 − aciertos / positivos, agrupado por SNR |
| **Falsos aceptos** | Negativo difícil con al menos una detección |
| **FA/h** | Eventos en conversación / horas de audio (un evento = tramas sobre el umbral separadas por < 400 ms) |
| **Latencia** | Instante de detección − fin de la palabra (p50 y p95) |

Una sola pasada por caso: el detector expone un observador de scores
(`onScore`, solo para el arnés) y todos los umbrales se recalibran sin volver a
analizar el audio.

### 3. Detector de referencia, solo en `test`

`SpectralTemplateWakeWordDetector` es la versión honesta de la idea de "huella
acústica", no un producto:

1. 16 bandas logarítmicas por trama (Goertzel, ventana de 512, salto de 160).
2. Rango dinámico relativo a la banda más fuerte de la trama (6 nats). Sin este
   recorte, una banda vacía vale −20 nats y el ruido de fondo la sube a −10: la
   diferencia aplasta la comparación.
3. CMVN por banda con **suelo de desviación** (0,5 nats) para que las bandas sin
   habla no amplifiquen ruido, y L2 global.
4. Rejilla temporal de 16 puntos: tolera el desfase de alineación que a 32 puntos
   hundía el coseno.
5. Barrido de duraciones de 300 a 660 ms: el wake word no dura lo mismo en todos
   los locutores, y un VAD no separa nada en ruido.
6. Coseno contra tres plantillas (una por locutor enrollado); gana la mejor.
7. Refractario de 800 ms y puntuación cada 32 ms (una por trama de captura).

Nunca se envía: vive en `app/src/test` y no tiene dependencias de Android.

---

## Resultados medidos

Los números siguientes son de `WakeWordHarnessTest` con el corpus descrito. Se
reproducen en cada ejecución.

### Curva de operación

| Umbral | FRR limpio | FRR 20 dB | FRR 10 dB | FRR 5 dB | FRR 0 dB | Negativos aceptados | FA/h | p50 | p95 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0,50 | 0,0 % | 33,3 % | 72,2 % | 94,4 % | 94,4 % | 44,4 % | 566,0 | −30 ms | 52 ms |
| 0,60 | 0,0 % | 50,0 % | 100 % | 100 % | 100 % | 24,3 % | 59,6 | −12 ms | 7 ms |
| **0,65** | **0,0 %** | **72,2 %** | **100 %** | **100 %** | **100 %** | **14,6 %** | **0,0** | **−15 ms** | **17 ms** |
| 0,70 | 50,0 % | 77,8 % | 100 % | 100 % | 100 % | 9,7 % | 0,0 | −15 ms | 7 ms |
| 0,78 | 50,0 % | 100 % | 100 % | 100 % | 100 % | 2,8 % | 0,0 | 7 ms | 17 ms |

Punto de trabajo documentado: **0,65**, el menor umbral que respeta el
presupuesto de **≤ 1 falso acepto por hora** en este corpus (0,645 exactos).

### Scores medianos

| Estímulo | Score mediano |
| :--- | ---: |
| "fifo" limpio | **0,830** |
| "fifo" a 20 dB | 0,605 |
| "fifo" a 10 dB | 0,449 |
| "fifo" a 5 dB | 0,394 |
| "fifo" a 0 dB | 0,321 |
| Negativos difíciles | 0,475 |
| Conversación | 0,563 |

Y por locutor, en limpio: **0,859** para los enrollados y **0,685** para los no
vistos. El margen entre "fifo" limpio (0,830) y conversación (0,563) es de 0,27;
el margen entre "fifo" a 20 dB (0,605) y conversación es de **0,04**. Ahí se
acaba el enfoque.

### Lectura

1. **En limpio funciona y generaliza algo.** FRR 0 % también con locutores no
   vistos, aunque con 0,17 menos de score. Esa caída es la medida de la
   dependencia de locutor.
2. **A 20 dB ya se rompe.** Un salón en silencio con la nevera de fondo está en
   ese orden. Un tercio de los positivos se pierde al umbral que respeta el
   presupuesto de falsos aceptos.
3. **A 10 dB o menos es inservible.** El score cae por debajo del de una
   conversación cualquiera (0,449 frente a 0,563).
4. **Bajar el umbral no es una salida.** A 0,50 el FRR a 10 dB baja al 72 %,
   pero aparecen 566 falsos aceptos por hora.
5. **El balbuceo es peor que el ruido blanco** a igual SNR: suena a habla y se
   parece a la plantilla. Es el enmascarador que hay que usar en el
   entrenamiento.
6. **La latencia no es el problema.** p95 de 17 ms y detecciones hasta 15 ms
   antes del final de la última vocal.

El arnés encontró dos defectos en su propio detector de referencia mientras se
construía: un desbordamiento al restar `Long.MIN_VALUE` en el instante inicial
(nunca detectaba) y una puerta de energía que descartaba justo el pico de
alineación. Ambos están corregidos y fijados por las pruebas.

---

## Qué NO dicen estos números

Es la parte importante del ADR.

- **No miden openWakeWord ni fifo en el mundo real.** Miden el arnés y el techo
  del enfoque por plantilla espectral. El corpus es voz sintética: no hay
  reverberación, ni efecto Lombard (hablar más fuerte con ruido), ni televisión,
  ni música, ni voces de niños o mayores. El arnés de campo de
  [ADR-0045](0045-field-simulation-for-invocation.md) cubre sala, Lombard y
  VTLP por simulación; televisión, música y voces reales siguen pendientes.
- **Los falsos aceptos por hora son optimistas.** La conversación sintética es
  más limpia y menos variada que una sala real con TV; un FA/h de 0 aquí no
  significa 0 en un salón.
- **El detector de referencia es dependiente de locutor por construcción.**
  Reportar solo el agregado escondería la diferencia 0,859 / 0,685; por eso el
  informe la separa.
- **La SNR sintética es nominal.** La mezcla se verifica con una prueba que mide
  la relación real tras restar la señal seca (±2 dB), pero sigue siendo una
  aproximación de la SNR perceptual.

Por eso el arnés sirve para **regresión y comparación de motores**, no para
certificar el rendimiento de campo. La decisión de producto se toma con
grabaciones reales.

---

## Consecuencias

**Positivas**

- Hay una prueba de regresión de la invocación que corre en JVM, sin modelo, sin
  red y sin emulador (~30 s).
- Cualquier motor que implemente `WakeWordDetector` se puede medir con el mismo
  corpus y la misma tabla: cambiar de openWakeWord a otra alternativa es una
  línea y una ejecución.
- El techo del enfoque barato queda documentado con números, no con una opinión.
- La costura queda verificada con tramas del tamaño real de captura, incluida la
  semántica de la marca de tiempo.

**Negativas / límites**

- El corpus hay que mantenerlo si cambia la definición de la invocación (por
  ejemplo, si se alarga a "oye Fifo").
- Los números envejecen: cualquier cambio en el sintetizador, en el ruido o en el
  umbral obliga a releer la tabla del ADR.
- El coste de ejecución (~30 s por variante) es real; por eso el corpus se
  construye una vez por clase y se cachea.

---

## Barra que debe superar el modelo entrenado

Con el mismo corpus y el mismo punto de operación (≤ 1 FA/h), un modelo
entrenado con aumentación de ruido debería cumplir:

| Métrica | Barra |
| :--- | :--- |
| FRR en limpio | ≤ 5 % |
| FRR a 20 dB | ≤ 10 % |
| FRR a 10 dB | ≤ 25 % |
| FRR a 5 dB | ≤ 50 % |
| Latencia p95 | ≤ 300 ms |
| FA/h en conversación sintética | ≤ 1 |

Si un modelo no supera esta barra en el corpus sintético, no se prueba con
grabaciones reales: se descarta. Si la supera, el siguiente paso es el corpus de
campo: [ADR-0045](0045-field-simulation-for-invocation.md) ya simula sala,
Lombard y VTLP sin dependencias, y sus números son diagnóstico, no certificación.
Grabar 10–15 hablantes reales en cuatro condiciones y con negativos difíciles
sigue siendo una decisión pendiente.

---

## Files Changed

| File | Change |
| :--- | :--- |
| `test/.../wakeword/SyntheticSpeech.kt` | **New** — sintetizador de formantes, fonemas, locutores y palabras. |
| `test/.../wakeword/SimulatedNoise.kt` | **New** — ruido blanco/rosa/balbuceo y mezcla por SNR. |
| `test/.../wakeword/WakeWordCorpus.kt` | **New** — 78 positivos, 144 negativos y 24 conversaciones con semillas estables. |
| `test/.../wakeword/SpectralTemplateDetector.kt` | **New** — detector de referencia por plantilla espectral (solo pruebas). |
| `test/.../wakeword/WakeWordHarnessTest.kt` | **New** — costura real, tabla de FRR/FA/latencia y aserciones. |

## Verification

- `testDebugUnitTest testReleaseUnitTest compileReleaseKotlin` en verde.
- `WakeWordHarnessTest` 5/5 en ambas variantes:
  - el corpus etiqueta la ventana y respeta el audio seco;
  - la mezcla alcanza la SNR nominal (±2 dB) y baja de forma monótona;
  - `NoopWakeWordDetector` falla el 100 % de los positivos;
  - el detector de referencia cumple el punto de trabajo (FRR limpio ≤ 20 %,
    ≤ 1 FA/h, scores monótonos con la SNR, p95 ≤ 250 ms);
  - el `accept()` real detecta el positivo limpio dentro de la ventana.
