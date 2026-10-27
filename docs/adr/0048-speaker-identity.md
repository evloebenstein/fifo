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
- `EncryptedVoiceProfileStore` materializa el almacén: un blob versionado con
  todos los perfiles, cifrado y autenticado. Un blob corrupto, de otra versión o
  cifrado con otra clave se trata como "no hay perfiles" y nunca rompe el turno.
- `AndroidKeystoreProfileCipher` cifra con **AES-256-GCM** y una clave del
  Android Keystore que no sale del dispositivo. No añade dependencias: usa
  `KeyGenParameterSpec` y `Cipher` del sistema.
- `FileProfileBlobStore` escribe a un temporal y reemplaza de forma atómica, así
  que un cierre a mitad de escritura deja el perfil anterior intacto.
- `ConsentAwareVoiceProfileStore` implementa la puerta: sin consentimiento no se
  lee ni se enrola, pero **borrar siempre se permite** para poder revocar.
- `androidEncryptedVoiceProfileStore(filesDir, hasConsent)` compone las tres
  piezas. No se cablea todavía porque no hay extractor real ni UI de
  consentimiento; `Noop` sigue por defecto.
- **No se sincroniza con el backend.** El backend actual no tiene autenticación
  real; subir embeddings biométricos sería peor que no tener la función.
- El perfil lleva `modelId`; un cambio de modelo invalida los perfiles viejos en
  vez de comparar vectores incompatibles.
- Enrolamiento: 3–5 frases, 5–10 s de audio variado, misma condición/dispositivo.
  `SpeakerEnrollment` exige al menos 3 muestras y calcula el centroide.
- Si el usuario no consiente, `Noop` sigue y no pierde nada.

### 5. El arnés mide el simulador, no el salón

`SpeakerVerificationHarnessTest` (test-only, gated con
`FIFO_SPEAKER_EXPERIMENT=1`) usa 8 locutores sintéticos de evaluación (6
enrolados, 2 no enrolados), 4 condiciones y 2 duraciones. Genera 384 trials: 48
genuinos y 336 impostores. La referencia es un extractor simple (media +
desviación de 16 bandas log-mel por frase) y coseno; **no es un modelo de
producción**.

Resultados con umbral calibrado al punto de EER limpio (0,985):

| Condición | Trials | EER | FRR | FAR |
| :--- | ---: | ---: | ---: | ---: |
| `clean` | 96 | 8,3 % | 8,3 % | 8,3 % |
| `pink-10` | 96 | 21,4 % | 100,0 % | 0,0 % |
| `white-5` | 96 | 32,7 % | 100,0 % | 0,0 % |
| `living-lombard-pink-20` | 96 | 27,4 % | 83,3 % | 2,4 % |
| 4 sílabas (~1,17 s) | 192 | 35,1 % | 79,2 % | 1,8 % |
| 9 sílabas (~2,01 s) | 192 | 32,7 % | 66,7 % | 3,6 % |
| Global | 384 | 31,8 % | 72,9 % | 2,7 % |

Lectura honesta:

- La referencia separa voces limpias (EER 8,3 %), así que el arnés y la política
  funcionan.
- Con el umbral por defecto de 0,70, el FAR limpio es 100 %: **el umbral no es
  universal**. Hay que calibrarlo con el modelo real.
- En ruido la referencia se cae: FRR 100 % en `pink-10` y `white-5`. Con sala +
  Lombard el FRR es 83,3 %, peor que en limpio pero sin el artefacto del corpus
  anterior: `LombardEffect.adapt` renombraba al locutor y el arnés lo trataba como
  impostor, lo que fabricaba un EER 100 % falso. Corregido, `living-lombard-pink-20`
  deja un EER de 27,4 %. El ruido sigue siendo el límite real.
- Los locutores no enrolados se aceptan al 100 % con el umbral de 0,985: el
  corpus sintético no mide generalización real.

Por eso **no se envía ningún detector biométrico todavía**. `Noop` sigue por
defecto. El arnés sirve para comparar motores y detectar regresiones; sus
números no se presentan como validación de campo.

#### Calibración con locutores disjuntos

La tabla anterior fija el umbral con el **mismo** conjunto que reporta, así que
sirve como techo, no como punto de operación. El corpus ahora incluye cuatro
locutores de calibración (dos enrolados y dos no vistos), separados de los ocho
de evaluación. `SpeakerCalibration.thresholdAtFar()` elige el umbral mínimo que
deja como máximo el FAR objetivo sobre los impostores de calibración.

Con FAR objetivo 1 %:

| Métrica | Valor |
| :--- | ---: |
| Umbral | 0,9825 |
| FAR de calibración | 0,0 % (0 de 48; 1/48 = 2,1 %, no cabía) |
| FAR de evaluación | 4,8 % |
| FRR de evaluación | 70,8 % |
| FRR / FAR en `clean` | 8,3 % / 16,7 % |

La lectura es incómoda y por eso importa: a un punto de operación realista
(FAR ≤ 1 %), la referencia espectral **no sirve**. Con solo 48 impostores de
calibración la granularidad es de 2,1 %, así que el umbral se vuelve
conservador; aun así la evaluación incumple el FAR objetivo. Ese es exactamente
el trabajo que debe hacer un modelo entrenado, no un ajuste de umbral.

#### Exportación y herramienta offline

`SpeakerCorpusExportTest` (gated con `FIFO_SPEAKER_EXPORT_DIR` y sin commitear
WAV) escribe 120 clips y un `manifest.jsonl` con split (`enroll`, `calibration`,
`evaluation`), locutor, rol, condición, sílabas y duración. `tools/speaker/`
contiene la otra mitad: `score_embeddings.py` recibe un `embeddings.jsonl`
(`id` + vector) producido por el toolkit del modelo, calcula centroides, calibra
con el split de calibración y reporta EER/FRR/FAR por condición y duración. Solo
usa la biblioteca estándar de Python: no mete `numpy`, `torch` ni ONNX en la app
y no adivina el frontend de cada modelo.

`extract_embeddings_sherpa.py` cierra el contrato por el lado del modelo: lee el
manifiesto, extrae un vector por clip con sherpa-onnx (que hace su propio
frontend) y escribe `embeddings.jsonl`. Se probaron tres modelos públicos sobre
los 120 clips sintéticos, con el umbral fijado **solo** con `calibration`:

| Modelo | Dim | EER `clean` | EER `pink-10` | EER `white-5` | EER Lombard | EER global | FRR/FAR @FAR 1 % |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| WeSpeaker ResNet34 (en, VoxCeleb) | 256 | 9,5 % | 50,0 % | 48,8 % | 50,6 % | 49,9 % | 81,2 % / 2,4 % |
| 3D-Speaker CAM++ (zh-cn common) | 192 | 16,7 % | 32,1 % | 41,7 % | 41,7 % | 41,7 % | 77,1 % / 4,8 % |
| WeSpeaker CAM++ (en, VoxCeleb) | 512 | 41,7 % | 57,7 % | 44,0 % | 50,0 % | 50,1 % | 95,8 % / 0,6 % |

Lectura honesta:

- En limpio, un modelo real separa las voces sintéticas (ResNet34 9,5 %), pero en
  ruido y Lombard se cae a ~50 %, peor que la referencia espectral (31,8 %
  global). Eso no demuestra que el modelo sea peor: el ruido sintético
  (blanco/rosa a 5–10 dB, sala + Lombard) es un dominio hostil y fuera de la
  distribución de entrenamiento, y la referencia comparte familia de features
  con el corpus. **El corpus sintético no ordena motores reales.**
- La muestra es pequeña: 12 genuinos por condición y 16 en calibración. Un solo
  error mueve el EER ~8 puntos; la tabla sirve para tendencias, no para decidir
  entre modelos que difieren en un dígito.
- Un umbral global calibrado con todas las condiciones deja un FAR limpio alto
  (19 % en 3D-Speaker, 9,5 % en ResNet34): sin normalización por condición, un
  solo umbral no sirve cuando cambia la condición. Es otro argumento para
  calibrar con datos de campo.
- Ninguno se acerca a la barra. `Noop` sigue por defecto.
- El coste de extracción fue ~54 ms/clip en CPU de escritorio (120 clips en
  6,5 s con un hilo); no mide el móvil ni incluye el resto del turno.

#### Control con voz real (LibriSpeech)

Para descartar que el pipeline estuviera roto, se repitió el mismo proceso con
voz humana real: **LibriSpeech dev-clean**, 10 locutores disjuntos, 78 clips
(18 de enrolamiento, 24 de calibración y 36 de evaluación), convertidos a WAV
16 kHz PCM16 con el mismo manifiesto y el mismo `score_embeddings.py`
(`build_librispeech_control.py`).

| Modelo | Trials | EER global | FRR/FAR @FAR 1 % |
| :--- | ---: | ---: | ---: |
| WeSpeaker ResNet34 | 144 | **0,0 %** | 0,0 % / 0,0 % |
| 3D-Speaker CAM++ (zh-cn) | 144 | **0,0 %** | 0,0 % / 0,0 % |

El control es deliberadamente fácil —lectura limpia, mismas condiciones de
grabación— y **no certifica campo ni mide el móvil**. Pero separa las dos
preguntas: el pipeline de manifiesto, embeddings, centroides, calibración y
scoring funciona; el 42–50 % del corpus sintético es un problema del corpus, no
del código. Un modelo real entrenado con voz humana no reconoce como distintas
unas voces de formantes que la referencia espectral sí separa.

#### Campo simulado con voces reales

Sin 10–15 voluntarios, la alternativa no es generar más voz sintética: es
**aumentar voz humana real**. Se usaron los 40 locutores de LibriSpeech
dev-clean (40 personas reales, no TTS), 7 clips por persona, y se aplicó la
misma acústica del arnés —`RoomAcoustics`, `SimulatedNoise` y `LombardEffect`—
desde `RealSpeechAugmentationExportTest`. No hay un segundo simulador que pueda
divergir del primero.

Reparto: 12 locutores de calibración (6 enrolados, 6 no vistos) y 28 de
evaluación (14 y 14), disjuntos. Cada enrolado aporta 3 frases de enrolamiento y
4 de uso; los no vistos, 4 de uso. Esas 4 frases se aumentan con seis
condiciones: `clean`, `living`, `pink-10`, `white-5`, `babble-15` y
`living-lombard-pink-20`. Total: **1.020 clips**, recortados a 8 s para que un
turno no sea una locución de 30 s. El corpus se regenera desde
`prepare_librispeech_pool.py` y no se commitea.

Con el umbral fijado **solo** con calibración y 9.408 trials de evaluación:

| Modelo | EER crudo | FRR/FAR crudo | EER AS-Norm | FRR/FAR AS-Norm |
| :--- | ---: | ---: | ---: | ---: |
| WeSpeaker ResNet34 | 43,4 % | 83,9 % / 0,4 % | 35,7 % | 83,0 % / 0,5 % |
| WeSpeaker CAM++ | 45,6 % | 91,1 % / 1,4 % | 45,0 % | 87,5 % / 2,6 % |
| 3D-Speaker CAM++ (zh-cn) | 11,6 % | 47,0 % / 0,6 % | **5,2 %** | **10,1 % / 2,3 %** |

AS-Norm usa como cohorte solo los 6 perfiles y los 144 clips de calibración;
evaluación no participa en la media ni en la desviación. Es lo único que se
“entrena” aquí: un backend de puntuación, no la red de embeddings. El resultado
es consistente con el control limpio: los WeSpeaker no separan bien estas voces,
mientras 3D-Speaker sí.

Lectura por condición de 3D-Speaker con AS-Norm (EER / FRR / FAR al umbral
global):

| Condición | EER | FRR | FAR |
| :--- | ---: | ---: | ---: |
| `clean` | 3,5 % | 1,8 % | 4,4 % |
| `living` | 3,9 % | 10,7 % | 1,1 % |
| `pink-10` | 3,6 % | 1,8 % | 3,7 % |
| `white-5` | 7,1 % | 16,1 % | 3,2 % |
| `babble-15` | 5,2 % | 12,5 % | 0,9 % |
| `living-lombard-pink-20` | 3,5 % | 17,9 % | 0,9 % |

Lo honesto:

- **El ranking cambia con voz real.** 3D-Speaker CAM++ queda muy por delante de
  los dos WeSpeaker, algo que el corpus de formantes no mostraba. Es la primera
  señal de que el simulador con voces reales sirve para **prefiltar** motores.
- **AS-Norm es lo que hace utilizable el umbral.** Sin normalizar, un solo
  umbral deja FRR 47 % en 3D-Speaker y ~100 % en los demás; con AS-Norm baja a
  10,1 %. La normalización no cambia el embedding: cambia cómo se decide.
- **Todavía no pasa la barra.** El FRR al 1 % sigue por encima del 10 % en
  `living`, `white-5`, `babble-15` y Lombard; y esto es lectura limpia aumentada,
  no una calle ni un móvil. `Noop` sigue por defecto.
- **La cohorte es pequeña** (6 perfiles). Más locutores de calibración deberían
  estabilizar AS-Norm; con 40 personas ya se puede repetir el experimento, pero
  no sustituye a grabar campo.
- **Es inglés.** El modelo es zh-cn y las voces son inglesas; el idioma no debería
  definir al locutor, pero un corpus español real sigue siendo necesario.

#### Corpus de campo: protocolo y manifiesto

El paso que falta no es otro modelo ni otro umbral: son **grabaciones reales**.
Para que ese trabajo no se pierda en formatos y repartos, `build_field_manifest.py`
define el contrato de entrada:

```
<root>/<locutor>/enroll/<clip>.wav
<root>/<locutor>/test/<condición>/<clip>.wav
```

Un `plan.json` reparte los locutores entre `calibration` y `evaluation` y les
asigna `enrolled` o `unseen`. El script valida que ambos splits tengan las dos
clases, que no haya ids duplicados, que el audio sea mono a 16 kHz PCM16 y que
cada enrolado tenga al menos 3 frases; avisa si el corpus se aleja de 10–15
locutores. El manifiesto resultante entra en `extract_embeddings_sherpa.py` y
`score_embeddings.py` sin cambios.

Protocolo mínimo: consentimiento y borrado, 10–15 personas, 3–5 frases de
enrolamiento y 6–10 de uso, condiciones separadas (silencio, TV, calle, 1–3 m),
calibración y evaluación disjuntas, y anotación del móvil y micrófono. Sin esas
condiciones el número no es comparable con ninguna tabla.

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
- El corpus sintético no predice campo: la referencia espectral obtiene 31,8 %
  EER global y tres modelos reales 42–50 % sobre el mismo corpus. El control con
  LibriSpeech da **0,0 % EER** con el mismo pipeline, así que el problema es el
  corpus, no el código. El campo simulado con voces reales sí ordena motores
  (3D-Speaker 5,2 % con AS-Norm frente a 36–45 % de los WeSpeaker), pero sigue
  siendo lectura limpia aumentada: un buen número aquí **no** autoriza a enviar
  nada y un mal número tampoco descarta el modelo; solo un corpus real decide.
- Guardar biometría obliga a mantenimiento: rotación de claves, borrado,
  migración de modelo y auditoría.

---

## Files Changed

| File | Change |
| :--- | :--- |
| `pipeline/session/SpeakerIdentity.kt` | **New** — relación, identidad, evidencia, perfil, política pura y contexto de prompt. |
| `pipeline/session/SpeakerIdentityProvider.kt` | **New** — costura, `Noop`, extractor, almacén, proveedor por embeddings y enrolamiento. |
| `pipeline/session/EncryptedVoiceProfileStore.kt` | **New** — almacén cifrado y versionado, blob atómico y puerta de consentimiento. |
| `pipeline/session/AndroidKeystoreProfileCipher.kt` | **New** — AES-256-GCM con clave del Android Keystore y composición de producción. |
| `pipeline/session/VoiceSession.kt` | **Updated** — identifica en paralelo al STT con timeout, expone `speakerIdentity` y la pasa al LLM. |
| `pipeline/session/SpeechCognitionSeam.kt` | **Updated** — `generateDialogTurn`/`generateRepairTurn` aceptan la identidad; el adaptador en memoria la registra. |
| `pipeline/session/VoiceSessionPipeline.kt` | **Updated** — construye `NoopSpeakerIdentityProvider` detrás de la costura. |
| `network/CloudApiClient.kt` | **Updated** — propaga la identidad al motor de diálogo. |
| `network/GroqDialogClient.kt` | **Updated** — inyecta `SpeakerPromptContext` en el system prompt. |
| `test/.../session/SpeakerIdentityPolicyTest.kt` | **New** — tabla de verdad de la política y del prompt. |
| `test/.../session/SpeakerIdentityIntegrationTest.kt` | **New** — identidad por turno, `Noop`, timeout y fallo del proveedor. |
| `test/.../session/SpeakerIdentityProviderTest.kt` | **New** — enrolamiento, centroide, coseno, modelo distinto y borrado. |
| `test/.../session/EncryptedVoiceProfileStoreTest.kt` | **New** — cifrado, corrupción, persistencia, borrado y consentimiento. |
| `test/.../session/speaker/SpeakerVerificationHarness.kt` | **New** — corpus sintético, calibración disjunta y evaluador EER/FRR/FAR. |
| `test/.../session/speaker/SpeakerVerificationHarnessTest.kt` | **New** — determinismo, separación limpia, calibración y tabla gated. |
| `test/.../session/speaker/SpeakerCorpusExportTest.kt` | **New** — exportación WAV + `manifest.jsonl` para evaluar un modelo real. |
| `test/.../session/speaker/RealSpeechAugmentationExportTest.kt` | **New** — campo simulado con voces reales y la misma acústica del arnés. |
| `tools/speaker/score_embeddings.py` | **New** — puntuación, calibración, AS-Norm y tablas sobre embeddings externos (stdlib). |
| `tools/speaker/extract_embeddings_sherpa.py` | **New** — extracción de embeddings con sherpa-onnx para el corpus exportado (dev-only). |
| `tools/speaker/build_librispeech_control.py` | **New** — control con voz real (LibriSpeech dev-clean) y el mismo manifiesto. |
| `tools/speaker/prepare_librispeech_pool.py` | **New** — pool de voces reales para el campo simulado. |
| `tools/speaker/build_field_manifest.py` | **New** — layout, plan disjunto y validación del corpus de campo. |
| `tools/speaker/requirements.txt` | **New** — `sherpa-onnx` y `soundfile` solo para desarrollo; no entran en la app. |
| `tools/speaker/README.md` | **New** — contrato de exportación, embeddings, modelos medidos y reglas de validación. |

---

## Verification

- `testDebugUnitTest`, `testReleaseUnitTest` y `compileReleaseKotlin` en verde.
- 402 pruebas en debug y 402 en release, 0 fallos, 6 omitidas (3 del arnés de
  wake word + el experimento de locutor sin `FIFO_SPEAKER_EXPERIMENT=1` + la
  exportación sin `FIFO_SPEAKER_EXPORT_DIR` + el campo simulado sin
  `FIFO_REAL_SPEECH_POOL`/`FIFO_REAL_SPEECH_EXPORT_DIR`).
- `SpeakerIdentityPolicyTest` 14/14, `SpeakerIdentityProviderTest` 9/9,
  `SpeakerIdentityIntegrationTest` 5/5, `EncryptedVoiceProfileStoreTest` 9/9 y
  `SpeakerVerificationHarnessTest` 3/3 siempre + el experimento gated.
- La exportación se ejecutó a un directorio temporal y produjo 120 WAV y 120
  líneas de manifiesto; `score_embeddings.py` se probó de extremo a extremo con
  embeddings sintéticos y escribió su informe.
- Sobre esos 120 clips se extrajeron embeddings reales con sherpa-onnx 2.0.1
  para tres modelos (WeSpeaker CAM++, WeSpeaker ResNet34 y 3D-Speaker CAM++),
  512/256/192 dimensiones, 120 clips cada uno, sin pesos en el repo. La tabla
  anterior sale de esos informes; el corpus y los modelos viven en `%TEMP%`.
- El control con LibriSpeech dev-clean se ejecutó con el mismo pipeline: 78
  clips de 10 locutores, 144 trials de evaluación, **0,0 % EER y 0,0 % FRR/FAR**
  con ResNet34 y con 3D-Speaker CAM++. No se commiteó audio.
- El campo simulado se generó con 40 locutores reales y 1.020 clips: 12 de
  calibración y 28 de evaluación, 9.408 trials, seis condiciones. Con AS-Norm
  (cohorte solo de calibración), 3D-Speaker CAM++ dio **5,2 % EER, 10,1 % FRR y
  2,3 % FAR** al 1 %; los WeSpeaker quedaron en 35,7–45,0 %. No se commiteó
  audio ni pesos.
- `build_field_manifest.py` se probó de extremo a extremo con el control de
  LibriSpeech reorganizado como corpus de campo: 78 clips, 10 locutores,
  manifiesto válido y el mismo 0,0 % EER. También rechaza un plan incompleto y
  un WAV a 8 kHz. No se grabó ni se commiteó audio real.
- Ninguna prueba depende de un modelo descargado ni de un dispositivo; la
  extracción, el control y el importador son herramientas de desarrollo, no tests.

---

## Siguiente paso (fuera de este ADR)

**Hecho en esta iteración:** extracción con sherpa-onnx, medida de tres modelos
reales sobre el corpus sintético (resultado negativo), control con voz real de
LibriSpeech (0,0 % EER), protocolo/importador del corpus de campo y **campo
simulado con 40 voces reales aumentadas + AS-Norm**, que por primera vez ordena
motores y deja a 3D-Speaker CAM++ en 5,2 % EER. En la iteración anterior:
exportación del corpus, separación calibración/evaluación, herramienta offline
de puntuación y almacén cifrado con consentimiento.

1. **Hecho parcialmente:** extraer embeddings con un modelo real y pasarlos por
   `tools/speaker/score_embeddings.py`, con control de voz real y campo simulado
   para separar corpus y código. Se midieron WeSpeaker CAM++, WeSpeaker ResNet34
   y 3D-Speaker CAM++ con sherpa-onnx. 3D-Speaker CAM++ es el primer candidato
   serio (5,2 % EER con AS-Norm en el simulador), pero sigue sin superar la barra
   por condición. Falta repetirlo con un **ReDimNet-B0/B1** y, sobre todo, con
   grabaciones reales.
2. Grabar el corpus de campo **antes** de elegir motor: 10–15 personas, 3–5
   frases de enrolamiento y uso en silencio, TV, calle y a 1–3 m. El layout, el
   plan disjunto y la validación ya están en `build_field_manifest.py`; solo
   faltan las grabaciones y el consentimiento. Es el único número que puede
   autorizar un detector; el sintético y el campo simulado ya demostraron que no
   bastan.
3. Implementar la **UI de consentimiento, enrolamiento y borrado** en
   `ProfileScreen` y cablear `androidEncryptedVoiceProfileStore()` en
   `VoiceSessionPipeline` cuando exista un extractor validado.
4. El aumento de ruido, sala y Lombard ya está en la **validación** (campo
   simulado) y AS-Norm normaliza los scores con cohorte de calibración. Falta
   decidir si el extractor se afina con datos aumentados y si el móvil puede
   ejecutar AS-Norm dentro del presupuesto de 400 ms.
5. Evaluar una contramedida de spoofing (ASVspoof 5) antes de usar la identidad
   para cualquier acción sensible.
6. Solo si el modelo supera la barra, el almacén está cifrado y la UI tiene
   consentimiento, cambiar `NoopSpeakerIdentityProvider` por el proveedor real y
   actualizar este ADR con los números de campo.
