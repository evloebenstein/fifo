# ADR-0045: Simulación de campo para la invocación (RIR, Lombard y VTLP)

**Status:** Accepted

**Date:** 2026-10-25

**Author:** Architecture Review — Del laboratorio al salón

---

## Context

ADR-0044 dejó un arnés determinista que mide la invocación sin grabaciones. Sus
propios límites están escritos en el ADR: voz sintética sin reverberación, sin
efecto Lombard, sin televisión ni música, sin voces infantiles o mayores, y un
FA/h optimista porque la conversación sintética es demasiado limpia.

Esos límites no son cosméticos. El corpus base mide **campo cercano y sala
seca**, y ahí el detector de referencia parece aceptable (0 % de FRR en limpio).
La pregunta que decide el producto es otra: ¿qué pasa cuando la persona está a
dos metros, en una cocina, con gente hablando y hablando más fuerte porque hay
ruido?

Antes de entrenar nada hacía falta saber si el arnés podía responder eso. Se
investigaron las tres familias de solución que existen y se implementó la que no
añade dependencias.

---

## Opciones investigadas

### Reverberación

| Opción | Qué aporta | Coste | Licencia |
| :--- | :--- | :--- | :--- |
| MIT Environmental Impulse Responses | RIRs reales de salas; es lo que usa openWakeWord | ~50 MB, convolución | libre |
| OpenSLR SLR28 | RIRs reales y simuladas + ruidos a 16 kHz | ~1 GB | Apache 2.0 |
| BIRD | RIRs de microWakeWord | descarga | CC |
| **Método de imagen (Allen & Berkley)** | RIR de caja sintetizada desde T60 y distancia | **Kotlin puro, determinista** | ninguna |

### Ruido y campo lejano

| Dataset | Contenido | Licencia |
| :--- | :--- | :--- |
| MUSAN | 109 h: música, habla en 12 idiomas, ruido | CC BY 4.0 |
| DEMAND | 18 entornos reales multicanal (cocina, calle, coche) | CC BY-SA 3.0 |
| MS-SNSD | mezclador escalable habla + ruido por SNR | MIT |
| FSD50K / FMA / WHAM! | eventos, música y ruido de fondo (microWakeWord) | mixtas |
| AudioSet | 2.000 clips de eventos reales (openWakeWord) | mixta |
| DiPCo | 5,5 h de cena real con música, 32 hablantes, far-field | CDLA-Permissive |

DiPCo es el patrón de facto para falsos aceptos por hora: openWakeWord lo usa
como referencia de "≤1 activación falsa en 5,5 h". AudioSet, FMA y FSD50K
**mezclan licencias**, así que un modelo entrenado con ellos puede quedar
restringido a uso personal; eso condiciona cualquier publicación.

### Voz

| Idea | Qué resuelve | Dónde |
| :--- | :--- | :--- |
| Efecto Lombard | la persona habla más fuerte y más claro con ruido | se aproxima aquí |
| TTS con control de esfuerzo vocal | Lombard realista por síntesis | arXiv 2606.23176, 2601.12966; voice conversion 2507.09310 |
| Lombard GRID / Lombard TIMIT | validación con voz humana | inglés |
| VTLP + speed perturbation | niños, voces grandes, acentos, sin datos nuevos | estándar en ASR; KWS en arXiv 2501.03523 |
| MyST, CSLU/OGI Kids, CID, CMU Kids | niños reales | inglés, no comercial |

Para español no hay corpus abierto de voces infantiles utilizable. Ese eje se
graba o no se mide.

### Enfoque del detector

| Idea | Comentario |
| :--- | :--- |
| DNN pequeño entrenado (openWakeWord, microWakeWord) | ONNX ~400 KB / INT8 ~100 KB; es la barra de ADR-0044 |
| Embeddings autosupervisados + DTW | 2,4 MB, 3–4 grabaciones del usuario (`local-wake`) |
| KWS de vocabulario abierto (sherpa-onnx Zipformer 3.3M) | sin reentrenar por palabra, pero **solo inglés/chino** |
| Enrolamiento few-shot en dispositivo | 16 repeticiones, one-class (TinySV) |
| Wake word + verificación de hablante | solo si únicamente el dueño puede controlar |

---

## Decision

Se implementa **el bloque que no añade dependencias ni datos externos**: sala,
Lombard y VTLP dentro del arnés, en Kotlin puro y determinista. El corpus base
de ADR-0044 **no se toca**, de modo que su tabla sigue siendo comparable; el
corpus de campo es un conjunto aparte.

Quedan explícitamente fuera de este ADR, por necesitar una decisión propia:

- descargar DiPCo/MUSAN/DEMAND (licencias y ~5 GB),
- entrenar openWakeWord o microWakeWord (pipeline Python + ONNX/TFLite),
- el flywheel de datos reales desde la cascada (requiere consentimiento,
  cifrado local y política de retención).

### 1. Sala por método de imagen

`RoomAcoustics.kt` genera la respuesta al impulso de una caja con el método de
Allen & Berkley:

1. La sala es un paralelepípedo con T60 declarado.
2. El coeficiente de absorción sale de **Sabine** (`α = 0,161 · V / (S · T60)`)
   y la amplitud reflejada es `√(1−α)`.
3. Cada imagen aporta `r^orden / distancia` en su retardo.
4. Se trunca a 1,5 × T60, se filtra en agudos (pared + micrófono, 3,5 kHz) y se
   normaliza **por pico**: el sonido directo llega a nivel unitario y la cola
   suma energía, como en una sala real.
5. La convolución es por solapamiento-suma con FFT (la directa serían decenas de
   miles de millones de operaciones).

Tres salas, elegidas por T60 conservador: despacho 0,25 s, cocina 0,35 s, salón
0,50 s. El locutor está a **1,2–2,6 m** del móvil: es el caso de un teléfono
sobre la mesa, no el de hablarle a 20 cm.

### 2. Efecto Lombard

`LombardEffect` aproxima el esfuerzo vocal en el origen, antes de la sala:

| Rasgo | Cambio |
| :--- | :--- |
| Nivel | +6 dB |
| f0 | +10 % |
| Formantes | +5 % |
| Duración de vocales | +12 % |
| Pendiente espectral | +3 dB sobre 800 Hz |

Es un proxy declarado, no una medida de personas: sirve para que el arnés deje
de suponer que alguien sigue hablando igual de suave con una cocina en marcha.

### 3. VTLP

`SpeakerVariation.vtlp` escala los formantes, que es exactamente lo que
distingue un tracto corto de uno largo. Se derivan dos locutores que **nunca se
enrollan**:

| Locutor | Origen | α | Efecto |
| :--- | :--- | ---: | :--- |
| `vtlp-child` | `enrolled-b` | 1,20 | tracto corto, voz infantil |
| `vtlp-large` | `unseen-a` | 0,86 | tracto largo, voz grande |

VTLP conserva el ritmo (a diferencia de una perturbación de velocidad), así que
el detector no puede "acertar por duración".

### 4. Corpus de campo

| Familia | Casos | Composición |
| :--- | ---: | :--- |
| Positivos | **24** | 8 locutores × 3 condiciones |
| Negativos difíciles | **128** | 8 palabras × 8 locutores × 2 condiciones con ruido |
| Conversación | **16** | 8 locutores × 2 condiciones (~0,076 h) |

Condiciones, de menor a mayor dificultad:

| Id | Sala | T60 | Ruido | Lombard |
| :--- | :--- | ---: | :--- | :--- |
| `office-clean` | despacho | 0,25 s | — | no |
| `living-pink-20` | salón | 0,50 s | rosa 20 dB | sí |
| `kitchen-babble-10` | cocina | 0,35 s | balbuceo 10 dB | sí |

---

## Resultados medidos

`WakeWordHarnessTest`, umbral de referencia 0,65. Se reproducen en cada
ejecución.

| Condición | Casos | FRR | Score mediano | p95 |
| :--- | ---: | ---: | ---: | ---: |
| **Base, limpio (seco)** | 78 | **0,0 %** | **0,830** | 17 ms |
| `office-clean` | 8 | **87,5 %** | 0,570 | 49 ms |
| `living-pink-20` | 8 | 100 % | 0,500 | — |
| `kitchen-babble-10` | 8 | 100 % | 0,472 | — |

Negativos de campo aceptados: **0,0 %**. FA/h: 0,0 sobre 0,076 h — sin
resolución, no es una medida.

### Diagnóstico: ¿es la sala o el enrolamiento?

La misma prueba repite `office-clean` con plantillas construidas **en la misma
sala**:

| Plantillas | FRR en `office-clean` |
| :--- | ---: |
| Secas (campo cercano) | 87,5 % |
| De la misma sala | **12,5 %** |

Ese contraste es el resultado central del ADR.

### Lectura

1. **La reverberación, sola, tumba el enfoque por plantilla.** T60 de 0,25 s y
   1,2–2,6 m llevan el FRR de 0 % a 87,5 % sin ruido y sin esfuerzo vocal.
2. **El problema no son las features, es el desajuste de canal.** Si la
   plantilla se graba en la misma sala, el FRR cae a 12,5 %. Un comparador de
   plantillas no puede pedirle al usuario que grabe en cada habitación; un
   modelo entrenado con salas sí las ha visto todas.
3. **Lombard + ruido no admite matices.** A 20 dB con esfuerzo vocal ya se
   pierde todo; a 10 dB con balbuceo no hay nada que medir.
4. **Los negativos de campo no disparan** a 0,65: el detector no es
   promiscuo, es sordo. Bajar el umbral para recuperar sensibilidad volvería a
   disparar los falsos aceptos que ADR-0044 midió.
5. **Los FA/h de campo siguen sin ser una medida.** 0,076 h no resuelve 1
   evento por hora; por eso el ADR deja DiPCo como siguiente paso.

---

## Qué NO dicen estos números

- **La sala es una caja.** No hay difusión, ni muebles, ni difracción, ni T60
  dependiente de la frecuencia. Es un simulador, no una medición.
- **Lombard es un proxy de parámetros.** Los valores son razonables, no son la
  respuesta de una persona concreta, y el efecto real varía mucho entre
  hablantes.
- **Dos locutores VTLP no son una población.** Son una demostración de que el
  eje existe; no sustituyen a niños y mayores grabados.
- **1,2–2,6 m es campo lejano.** A 0,5 m el detector de referencia aguanta
  mucho más; el arnés elige el caso difícil a propósito.
- **Sigue sin haber televisión, música ni conversación real.** Eso es DiPCo,
  MUSAN y DEMAND, y este ADR no los descarga.

Por eso el resultado se usa para **decidir** (hace falta un modelo entrenado con
salas y ruido), no para **certificar** el rendimiento de campo.

---

## Consecuencias

**Positivas**

- El arnés ya distingue "funciona en el laboratorio" de "funciona en el salón",
  y lo hace con números reproducibles y sin descargas.
- El diagnóstico de plantillas de la misma sala convierte una intuición en una
  medida: el techo del enfoque depende del canal, no solo del locutor.
- Toda la maquinaria es Kotlin puro y determinista: sigue corriendo en JVM, en
  CI, sin emulador y sin binarios.
- La simulación es reutilizable: cualquier `WakeWordDetector` futuro se evalúa
  con las mismas condiciones de campo.

**Negativas / límites**

- El corpus de campo añade ~10 s a la ejecución del arnés (son 24 positivos, 128
  negativos y 16 conversaciones con convolución por FFT).
- El simulador hay que mantenerlo si cambia la definición de la invocación.
- Da una falsa sensación de cobertura: sala y Lombard no son televisión, ni
  música, ni niños reales.

---

## Decisiones pendientes (no en este ADR)

1. **Flywheel de datos reales.** La cascada ya etiqueta gratis: candidato
   acústico confirmado por el STT = positivo real; candidato rechazado =
   negativo difícil real. Requiere consentimiento, cifrado local y retención.
2. **Evaluación con datos reales.** DiPCo (5,5 h, CDLA-Permissive) para FA/h y
   MUSAN/DEMAND para ruido, fuera de CI.
3. **Entrenamiento.** openWakeWord con TTS español (Piper `es_ES`/`es_MX`), RIRs
   y ruido, medido contra la barra de ADR-0044. Cuidado con la pronunciación de
   "Fifo" y con los negativos adversariales en español. El primer intento, con
   modelos ligeros entrenados sobre el corpus sintético, está en
   [ADR-0046](0046-synthetic-corpus-does-not-train.md): no alcanzó la barra y
   confirma que hace falta un frontend de embeddings con muchas voces.
4. **Licencias.** Decidir si el modelo puede usar AudioSet/FMA/FSD50K o si se
   restringe a datasets con licencia compatible con la publicación.

---

## Files Changed

| File | Change |
| :--- | :--- |
| `test/.../wakeword/Seeding.kt` | **New** — semilla FNV-1a compartida por los dos corpus. |
| `test/.../wakeword/RoomAcoustics.kt` | **New** — salas, RIR por método de imagen y convolución FFT. |
| `test/.../wakeword/VoiceVariation.kt` | **New** — proxy Lombard y VTLP / perturbación de velocidad. |
| `test/.../wakeword/WakeWordCorpus.kt` | Condiciones con sala y Lombard; corpus de campo; `dryPcm` → `preNoisePcm`. |
| `test/.../wakeword/WakeWordHarnessTest.kt` | Tabla de campo, diagnóstico de plantillas de la misma sala y pruebas de la RIR, Lombard, VTLP y FFT. |
| `docs/adr/0044-synthetic-wake-word-harness.md` | Enlace al arnés de campo. |
| `docs/README.md`, `AGENTS.md`, `GLOSSARY.md`, `pipeline/README.md` | Índices, términos y reglas. |

## Verification

- `testDebugUnitTest testReleaseUnitTest compileReleaseKotlin` en verde.
- `WakeWordHarnessTest` 10/10 en ambas variantes:
  - el corpus base sigue intacto (mismos 78/144/24 y misma tabla);
  - la RIR llega antes de 20 ms, tiene cola que decae y conserva el nivel;
  - la convolución por FFT coincide con la directa muestra a muestra;
  - Lombard sube f0, formantes y duración;
  - VTLP cambia el tracto sin tocar el ritmo;
  - el campo degrada de forma monótona (`office` > `living` > `kitchen`);
  - las plantillas de la misma sala no son peores que las secas.
