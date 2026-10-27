# ADR-0048: Identidad de locutor — personalización, no autenticación

**Status:** Accepted

**Date:** 2026-10-26

**Author:** Architecture Review — Identidad de voz

---

## Context

Fifo ya distingue **qué se dijo** (STT), **cuándo se dijo** (turno, memoria,
reparación) y **quién llama** (contexto de llamante). No distingue **quién
habla** cuando el micrófono está abierto.

Alexa y Google resolvieron eso con un perfil de voz por persona:

- **Alexa Voice ID.** Se crea con varias frases ("Learn my voice" o Voice ID en
  la app) y Alexa compara cada turno contra ese perfil para personalizar la
  respuesta. Amazon combina un modelo dependiente del texto (la propia
  invocación) con uno independiente del texto, y ha movido parte del
  reconocimiento al chip local AZ1; el perfil y la personalización siguen
  dependiendo de la nube y de conservar grabaciones.
- **Google Voice Match.** Hasta 6 personas por hogar. El modelo se crea en los
  servidores de Google y se guarda en los dispositivos; si la voz no coincide,
  el turno se trata como invitado y no se muestran resultados personales. En
  varias jurisdicciones el modelo de voz se considera dato biométrico.

La idea que interesa a Fifo es la misma: **un perfil acústico local que permita
decir "esta voz es la de Ana" o "esta voz no es la del dueño"**. La diferencia
es que Fifo no necesita autenticar una cuenta ni desbloquear datos en la nube:
necesita atribuir un turno para personalizar el trato y no mezclar la memoria de
distintas personas.

Hoy no existe en el repositorio ningún código de speaker ID, voiceprint,
diarización, enrolamiento, almacenamiento cifrado ni runtime ONNX/TFLite. El
punto natural de inserción es `VoiceSession.processUserAudio(wavData)`, justo
después de que `SpeechSegmenter` cierra el WAV y antes de que el turno llegue al
LLM.

### La pregunta correcta

No es "¿cómo copio Voice ID?". Es:

1. ¿Qué modelo on-device separa voces con latencia y tamaño de móvil?
2. ¿Cómo se integra sin abrir un segundo micrófono, un segundo VAD ni un segundo
   camino?
3. ¿Qué se guarda, dónde, con qué consentimiento y hasta cuándo?
4. ¿Qué pasa cuando la voz no coincide, el audio es corto o el modelo falla?
5. ¿Cómo se mide sin confundir un buen número en un corpus sintético con una
   validación de campo?

---

## Research: estado del arte en 2026

### Modelos

| Modelo | Tamaño | EER reportado | Nota |
| :--- | :--- | :--- | :--- |
| **ReDimNet-B0/B1** | ~1,0–2,2 M parámetros | ~1,07 % (B0, VoxCeleb1-O) | MIT; 0,5–20 GMACs según escala; sucesor ReDimNet2 (Interspeech 2026) mejora la relación precisión/cómputo. |
| **ReDimNet-B6** | ~15 M | ~0,37 % (VoxCeleb1-O) | Demasiado grande para el primer envío móvil, útil como techo de calidad. |
| **ECAPA-TDNN** | ~6,4 M | ~0,8–1,0 % (VoxCeleb1-O) | Ampliamente soportado por WeSpeaker/sherpa-onnx; más pesado que ReDimNet-B0. |
| **WavLM Base/Base+** | ~90–95 M | 15–20 % en SVeritas (mezcla de estilos) | El desajuste Lombard/estilo lo castiga más que a los modelos con agregación; no es la primera opción móvil. |
| **Picovoice Eagle** | SDK propietario | 0,18 % declarado por el proveedor | On-device, language-agnostic, text-independent; requiere AccessKey y revisar licencia/cuota. |
| **WeSpeaker** | Toolkit | Depende del modelo | Apache-2.0; exporta a ONNX; modelos de VoxCeleb/CN-Celeb. |
| **sherpa-onnx** | Runtime + modelos | Depende del modelo | Apache-2.0; API Kotlin/Java, speaker identification/diarization y APKs Android. |

Fuentes: paper de ReDimNet (Interspeech 2024) y ReDimNet2 (Interspeech 2026);
SVeritas (EMNLP 2025 Findings); documentación de Picovoice Eagle, WeSpeaker y
sherpa-onnx. Los EER son de VoxCeleb salvo que se indique otra cosa y no
incluyen el desajuste de micrófono, sala o esfuerzo vocal de un salón real.

### Lo que de verdad falla

El problema no es VoxCeleb: es el **desajuste de dominio**. SVeritas muestra que
en condiciones emparejadas ReDimNet, ECAPA-TDNN, MFA-Conformer y TitaNet quedan
por debajo del 2,5 % de EER, mientras WavLM-Base/Base+ llegan a 15–20 % al
mezclar voz normal y Lombard. En un móvil sobre la mesa, la voz de enrolamiento
(cerca, silencio) y la del uso real (a 2 m, con televisión, hablando más fuerte)
no son la misma señal.

De ahí las decisiones de diseño:

- El enrolamiento y el uso deben compartir dispositivo y, en lo posible,
  condición.
- Hay que medir por condición y por duración, no solo un EER global.
- Un umbral de coseno **no es universal**: cambia con el modelo, el corpus y la
  normalización. Se calibra por modelo con un conjunto de validación.
- La voz sintética sirve para regresión, no para certificar campo.

### Ataques y privacidad

ASVspoof y ASVspoof 5 documentan que un sistema ASV es vulnerable a replay, TTS
y deepfakes. Si la identidad se usara para algo sensible haría falta una
contramedida de spoofing. En Fifo **no se usa para autorizar**, así que el riesgo
se reduce a atribución errónea, no a suplantación de una acción.

GDPR: la voz y su embedding pueden ser datos biométricos. El consentimiento debe
ser "freely given, specific, informed and unambiguous", con finalidad concreta,
minimización, retención y borrado. Un embedding no es "audio inocente": permite
reconocer a una persona y debe tratarse como dato sensible.

---

## Decision

### 1. La identidad personaliza; no autentica

`SpeakerIdentity` es contexto para el LLM, no una llave. Ninguna llamada, pago,
cambio de configuración ni dato privado se autoriza solo porque la voz suene
como el dueño.

- `OWNER` y `KNOWN` con confianza suficiente pueden personalizar el trato.
- `UNKNOWN` se atiende como invitado y no recibe memoria privada del dueño.
- `UNCERTAIN` no se atribuye: si hace falta, el LLM pide confirmación.
- `UNAVAILABLE` es el estado normal sin modelo o sin perfiles.

La telefonía sigue exigiendo la frase completa ("Fifo contesta" / "Fifo cuelga")
y `UtterancePolicy.classifyCallAction()` sigue siendo la única autoridad. La
identidad no toca esa cadena.

### 2. Una costura opcional, con `Noop` por defecto

`pipeline/session/SpeakerIdentity.kt` y `SpeakerIdentityProvider.kt` definen:

```kotlin
interface SpeakerIdentityProvider {
    val modelId: String
    suspend fun identify(wavData: ByteArray, nowMillis: Long): SpeakerIdentity
}

object NoopSpeakerIdentityProvider : SpeakerIdentityProvider
```

Reglas de la costura:

- **No abre micrófono.** Recibe el WAV que ya cerró `SpeechSegmenter`; no hay
  segundo dueño de captura ni segundo VAD.
- **Corre en paralelo al STT, con timeout de 400 ms.** Si tarda o falla, el turno
  continúa como `UNAVAILABLE`; nunca bloquea ni alarga la conversación.
- **No habla ni decide la respuesta.** Devuelve una etiqueta; el LLM redacta.
- **No conoce Android, telefonía, TTS ni `UtterancePolicy`.**
- **Se apaga con el mismo criterio que el resto del camino nuevo:** se construye
  en `VoiceSessionPipeline`, no en el facade legacy.

`VoiceSession` expone `speakerIdentity: StateFlow<SpeakerIdentity>` para la UI y
pasa la identidad a `SpeechCognitionAdapter.generateDialogTurn()`. El prompt la
recibe como dato (`SpeakerPromptContext`), nunca como frase enlatada.

### 3. Política pura, umbrales por modelo

`SpeakerIdentityPolicy.resolve()` aplica, en orden:

| Evidencia | Resultado |
| :--- | :--- |
| Sin evidencia o sin perfiles | `UNAVAILABLE` |
| Audio < 1,2 s | `UNCERTAIN` |
| Mejor similitud < 0,70 | `UNKNOWN` |
| Diferencia con el segundo perfil < 0,06 | `UNCERTAIN` |
| Coincide con el usuario principal | `OWNER` |
| Coincide con otro perfil | `KNOWN` |

Los valores viven en `SpeakerPolicyConfig` y son **provisionales**: cada modelo
trae otra distribución de similitudes. El arnés de esta ADR demuestra que un
umbral de 0,70 puede aceptar al 100 % de los impostores en una referencia
espectral; el umbral de producción se fija con la validación del modelo real.

### 4. Solo embeddings locales, cifrados y con consentimiento

- Se guarda el **centroide de embeddings**, nunca el audio crudo.
- El almacén (`VoiceProfileStore`) es una costura inyectable. La implementación
  de producción debe cifrar en reposo (Android Keystore + AES-GCM o SQLCipher),
  pedir consentimiento explícito y ofrecer borrado completo.
- **No se sincroniza con el backend.** El backend actual no tiene autenticación
  real; subir embeddings biométricos sería peor que no tener la función.
- El perfil lleva `modelId`; un cambio de modelo invalida los perfiles viejos en
  vez de comparar vectores incompatibles.
- Enrolamiento: 3–5 frases, 5–10 s de audio variado, misma condición/dispositivo.
  `SpeakerEnrollment` exige al menos 3 muestras y calcula el centroide.
- Si el usuario no consiente, `Noop` sigue y no pierde nada.

### 5. El arnés mide el simulador, no el salón

`SpeakerVerificationHarnessTest` (test-only, gated con
`FIFO_SPEAKER_EXPERIMENT=1`) usa 8 locutores sintéticos (6 enrolados, 2 no
enrolados), 4 condiciones y 2 duraciones. Genera 384 trials: 36 genuinos y 348
impostores. La referencia es un extractor simple (media + desviación de 16 bandas
log-mel por frase) y coseno; **no es un modelo de producción**.

Resultados con umbral calibrado al punto de EER limpio (0,985):

| Condición | Trials | EER | FRR | FAR |
| :--- | ---: | ---: | ---: | ---: |
| `clean` | 96 | 8,3 % | 8,3 % | 8,3 % |
| `pink-10` | 96 | 21,4 % | 100,0 % | 0,0 % |
| `white-5` | 96 | 32,7 % | 100,0 % | 0,0 % |
| `living-lombard-pink-20` | 96 | 100,0 % | 100,0 % | 100,0 % |
| 4 sílabas (~1,17 s) | 192 | 37,8 % | 72,2 % | 1,7 % |
| 9 sílabas (~2,01 s) | 192 | 35,1 % | 66,7 % | 4,6 % |
| Global | 384 | 40,0 % | 69,4 % | 3,2 % |

Lectura honesta:

- La referencia separa voces limpias (EER 8,3 %), así que el arnés y la política
  funcionan.
- Con el umbral por defecto de 0,70, el FAR limpio es 100 %: **el umbral no es
  universal**. Hay que calibrarlo con el modelo real.
- En ruido y, sobre todo, con sala + Lombard, la referencia se cae (FRR 100 % o
  EER 100 %). Eso no es un defecto del arnés: es la prueba de que un resumen
  espectral barato no sustituye a un modelo entrenado con aumento de ruido,
  normalización de scores y datos de campo.
- Los locutores no enrolados se aceptan al 100 % con el umbral de 0,985: el
  corpus sintético no mide generalización real.

Por eso **no se envía ningún detector biométrico todavía**. `Noop` sigue por
defecto. El arnés sirve para comparar motores y detectar regresiones; sus
números no se presentan como validación de campo.

### 6. Elección de motor: diferida, con criterios claros

No se elige un modelo en esta ADR porque no hay pesos, runtime ni corpus real.
Criterios para el siguiente paso:

| Criterio | Barra |
| :--- | :--- |
| EER en validación propia | Comparable al estado del arte en condiciones emparejadas; medido por condición. |
| FRR a FAR ≤ 1 % | Debe bajar del 10 % en limpio y no dispararse en ruido. |
| Latencia | ≤ 400 ms por turno en el móvil objetivo (presupuesto de `VoiceSession`). |
| Tamaño | Modelo + runtime razonables para un APK; sin descargas en runtime. |
| Licencia | Apache-2.0/MIT preferible; propietario solo con revisión legal y coste. |
| Español y texto libre | Entrenado o adaptado a español; text-independent para no exigir frases fijas. |
| Privacidad | Todo on-device; sin audio ni embeddings al backend. |

Candidatos, en orden:

1. **ReDimNet-B0/B1 exportado a ONNX** con WeSpeaker o sherpa-onnx. MIT,
   ~1–2 M parámetros, el mejor equilibrio tamaño/precisión para móvil.
2. **ECAPA-TDNN vía sherpa-onnx/WeSpeaker.** Más maduro y con API Kotlin, pero
   ~6,4 M parámetros.
3. **Picovoice Eagle.** Atajo comercial si la licencia y el coste encajan; no
   añade runtime propio, pero ata la función a un proveedor.

WavLM no es primera opción por tamaño y por su sensibilidad al desajuste de
estilo. ReDimNet-B6 queda como techo de calidad, no como primer envío.

---

## Arquitectura

```
CaptureArbiter ──► SpeechSegmenter (VAD + pre-roll) ──► WAV
                                                        │
                        ┌───────────────────────────────┴──────────────────┐
                        ▼                                                  ▼
                 STT (Whisper)                              SpeakerIdentityProvider
                        │                                                  │
                        ▼                                     (timeout 400 ms, paralelo)
                 VoiceSession                                       │
                        │                                     SpeakerIdentityPolicy
                        │                                                  │
                        └──────────────► LLM ◄──────────── SpeakerIdentity
                                          ▲
                                SpeakerPromptContext
                                (OWNER/KNOWN/UNKNOWN/UNCERTAIN)
```

El camino legacy no construye nada de esto. `VoiceSessionPipeline` es el único
lugar que elige el proveedor; hoy elige `Noop`.

### Flujo de enrolamiento (diseño, aún sin UI)

1. `ProfileScreen` → "Enseñar mi voz".
2. Pantalla de consentimiento: qué se guarda (embedding, no audio), para qué
   (personalizar y atribuir turnos), que no se sincroniza y cómo borrarlo.
3. 3–5 frases guiadas, 5–10 s, misma condición/dispositivo.
4. `SpeakerEnrollment` extrae embeddings, exige ≥ 3 muestras y guarda el
   centroide en `VoiceProfileStore`.
5. Estado "voz aprendida" con opción de volver a enseñar o borrar.
6. Si el usuario rechaza, no se crea perfil y todo sigue como hoy.

No se implementa la UI en esta ADR porque no hay extractor real; una pantalla
que promete "te reconozco" sin modelo sería una mentira de producto.

---

## Consequences

**Positive:**

- La identidad entra por una costura única, testeable en JVM, sin segundo
  micrófono, segundo VAD ni segundo camino.
- El comportamiento actual no cambia: `Noop` por defecto y timeout que nunca
  bloquea el turno.
- La política es pura y separa evidencia de decisión; se puede recalibrar por
  modelo sin tocar `VoiceSession`.
- El LLM recibe contexto de locutor y sigue redactando él; no hay frases
  enlatadas ni una segunda autoridad léxica.
- Los embeddings se quedan en el dispositivo y el diseño ya contempla
  consentimiento, cifrado y borrado.
- El arnés deja una barra reproducible y documenta por qué un umbral fijo no
  sirve.

**Negative / Trade-offs:**

- Una dependencia futura pesada: runtime ONNX o SDK propietario, más un modelo
  que versionar y medir.
- El enrolamiento añade fricción y una pantalla de consentimiento; mal hecho,
  el usuario percibe que Fifo "lo estudia".
- La identidad puede fallar en ruido o con voces parecidas; por eso nunca
  autoriza nada sensible.
- El corpus sintético es demasiado fácil y no representa micrófonos, televisión
  ni voces reales. El siguiente número que importe tiene que salir de
  grabaciones.
- Guardar biometría obliga a mantenimiento: rotación de claves, borrado,
  migración de modelo y auditoría.

---

## Files Changed

| File | Change |
| :--- | :--- |
| `pipeline/session/SpeakerIdentity.kt` | **New** — relación, identidad, evidencia, perfil, política pura y contexto de prompt. |
| `pipeline/session/SpeakerIdentityProvider.kt` | **New** — costura, `Noop`, extractor, almacén, proveedor por embeddings y enrolamiento. |
| `pipeline/session/VoiceSession.kt` | **Updated** — identifica en paralelo al STT con timeout, expone `speakerIdentity` y la pasa al LLM. |
| `pipeline/session/SpeechCognitionSeam.kt` | **Updated** — `generateDialogTurn`/`generateRepairTurn` aceptan la identidad; el adaptador en memoria la registra. |
| `pipeline/session/VoiceSessionPipeline.kt` | **Updated** — construye `NoopSpeakerIdentityProvider` detrás de la costura. |
| `network/CloudApiClient.kt` | **Updated** — propaga la identidad al motor de diálogo. |
| `network/GroqDialogClient.kt` | **Updated** — inyecta `SpeakerPromptContext` en el system prompt. |
| `test/.../session/SpeakerIdentityPolicyTest.kt` | **New** — tabla de verdad de la política y del prompt. |
| `test/.../session/SpeakerIdentityIntegrationTest.kt` | **New** — identidad por turno, `Noop`, timeout y fallo del proveedor. |
| `test/.../session/SpeakerIdentityProviderTest.kt` | **New** — enrolamiento, centroide, coseno, modelo distinto y borrado. |
| `test/.../session/speaker/SpeakerVerificationHarness.kt` | **New** — corpus sintético y evaluador EER/FRR/FAR. |
| `test/.../session/speaker/SpeakerVerificationHarnessTest.kt` | **New** — determinismo, separación limpia y tabla gated. |

---

## Verification

- `testDebugUnitTest`, `testReleaseUnitTest` y `compileReleaseKotlin` en verde.
- 390 pruebas en debug y 390 en release, 0 fallos, 4 omitidas (3 del arnés de
  wake word + el experimento de locutor sin `FIFO_SPEAKER_EXPERIMENT=1`).
- `SpeakerIdentityPolicyTest` 14/14, `SpeakerIdentityProviderTest` 9/9,
  `SpeakerIdentityIntegrationTest` 5/5, `SpeakerVerificationHarnessTest` 2/2
  siempre y el experimento gated cuando se activa.
- Ninguna prueba depende de un modelo descargado ni de un dispositivo.

---

## Siguiente paso (fuera de este ADR)

1. Exportar el corpus sintético de locutores a WAV + manifiesto (mismo contrato
   que ADR-0047) y evaluar offline un ReDimNet-B0/B1 ONNX real.
2. Separar locutores de calibración y de evaluación; fijar el umbral por modelo
   con FAR objetivo (≤ 1 %) y reportar FRR por condición y duración.
3. Añadir aumento de ruido, sala y Lombard al entrenamiento/validación del
   extractor, no solo al test.
4. Implementar `EncryptedVoiceProfileStore` con Android Keystore + AES-GCM
   (o SQLCipher) y la pantalla de consentimiento/borrado.
5. Evaluar una contramedida de spoofing (ASVspoof 5) antes de usar la identidad
   para cualquier acción sensible.
6. Grabar un corpus real: 10–15 personas, 3–5 frases de enrolamiento y uso en
   silencio, TV, calle y a 1–3 m. Sin ese corpus no se envía el detector.
7. Solo si el modelo supera la barra y el almacén es cifrado, cambiar
   `NoopSpeakerIdentityProvider` por el proveedor real y actualizar este ADR con
   los números de campo.
