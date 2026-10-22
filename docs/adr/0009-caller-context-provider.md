# ADR-0009: CallerContextProvider — contexto de llamante en dos fases

**Status:** Accepted  
**Date:** 2026-10-22  
**Author:** Architecture Review — Step 3 (Candidate 01)

---

## Context

El backend ya expone `POST /users/{user_id}/telephony/identify-contact`, que resuelve llamantes contra `user_contacts_relational` y `knowledge_nodes`, recupera parentesco, notas y menciones recientes, y cataloga desconocidos. Sin embargo, el camino de Android lo usaba de forma tardía e inconsistente:

- `FifoCallManager` anunciaba el nombre local del teléfono en el timbrado.
- `PhoneCallSkill` volvía a llamar a `identifyContact` al contestar, colgar o consultar estado.
- No había un contexto único que distinguiera **conocido**, **desconocido** y **desconocido con caller ID**.
- Existía el riesgo de leer en voz alta `recentTopics` o `contextualNotes` durante el timbrado, cuando el usuario todavía no ha preguntado nada.

El objetivo es que el anuncio sea inmediato con el nombre local, que el backend enriquezca en paralelo (~800 ms) y que un llamante desconocido siga siendo desconocido, sin nombres ni parentescos inventados.

---

## Decision

### 1. `CallerContext` es el modelo único de contexto

```kotlin
enum class CallerContextSource {
    UNKNOWN,
    LOCAL_CONTACT,
    BACKEND_KNOWN,
    BACKEND_NEW
}

data class CallerContext(
    val phase: CallPhase,
    val displayName: String,
    val phoneNumber: String? = null,
    val relationshipRole: String? = null,
    val isKnown: Boolean = false,
    val source: CallerContextSource = CallerContextSource.UNKNOWN,
    val contextualNotes: String? = null,
    val recentTopics: String? = null,
    val suggestedGreeting: String? = null,
    val suggestedFollowup: String? = null
) {
    val isUnknown: Boolean get() = !isKnown
}
```

Reglas:

- **Conocido** = hay un nombre local fiable (contacto del teléfono o grafo relacional) o el backend confirma `isKnown=true`.
- **Desconocido** = placeholder (`Llamada desconocida`, `Privado`, `Spam`), número crudo o llamada sin identificar. `isKnown=false`, `source=UNKNOWN` o `BACKEND_NEW`.
- Un `caller ID` con nombre pero sin registro en contactos/historial sigue siendo desconocido: se muestra el nombre, pero el anuncio lo marca como no registrado.
- Un backend que dice `isKnown=false` **no degrada** un contacto local ya conocido.
- Los roles genéricos (`Contacto telefónico`, `Contacto nuevo catalogado`, `Conocido`) no se presentan como parentesco.

### 2. `CallerContextProvider` separa local de remoto

```kotlin
fun local(snapshot: CallSnapshot): CallerContext
suspend fun enrich(snapshot: CallSnapshot, userId: String, local: CallerContext): CallerContext
```

- `local` es **síncrono**: resuelve el nombre que ya conoce el teléfono/grafo local. Es lo que se anuncia de inmediato.
- `enrich` consulta `CallerIdentitySource` (producción: `BackendCallerIdentitySource` → `FifoBackendClient.identifyContact`) con `withTimeoutOrNull(800)`.
- Timeout, error de red o `userId` vacío devuelven el contexto local sin lanzar.
- `CancellationException` se repropaga para no romper la cancelación estructurada.
- El proveedor no habla, no contesta, no cuelga y no toca `AudioManager`.

### 3. `CallerContextCoordinator` emite local y enriquece en paralelo

`CallerContextCoordinator` observa `TelephonyPort.callState`:

1. Al recibir una llamada, calcula y emite `CallerContext` local de inmediato.
2. Lanza el enriquecimiento backend en paralelo.
3. Si la llamada termina o cambia antes de que llegue el backend, descarta el resultado viejo.
4. Al terminar la llamada, limpia el contexto.
5. No inicia ni detiene la telefonía: el ciclo de vida es del facade.

Esto mantiene la telefonía fuera de `VoiceSession` (ADR-0008) y permite que OLED/memoria sean observadores del contexto.

### 4. El anuncio nunca lee temas recientes

`CallerContext.announcementText()` es la única función autorizada para construir el texto de timbrado:

- Conocido con parentesco: `"Le está llamando Carmen, su hija. Diga 'Fifo contesta' o 'Fifo cuelga'."`
- Conocido sin parentesco: `"Le está llamando Carmen. ..."`
- Desconocido con número: `"Le está llamando un número desconocido (600111222). ..."`
- Desconocido con caller ID: `"Le está llamando Juan (no está en sus contactos). ..."`

`recentTopics`, `contextualNotes`, `suggestedGreeting` y `suggestedFollowup` quedan disponibles para el agente, pero **no** se leen en el anuncio espontáneo. El usuario puede preguntar “¿quién llama?” o “¿de qué hablamos con Carmen?”.

### 5. Integración prevista en el paso 4

```kotlin
val callerContext = CallerContextCoordinator(
    telephony = androidTelephonyPort,
    provider = CallerContextProvider(
        identitySource = BackendCallerIdentitySource(),
        localContactResolver = { name, phone ->
            FifoDataRepository.findContactByRoleOrName(name ?: phone.orEmpty())?.let {
                LocalCallerContact(
                    name = it.contactName,
                    relationshipRole = it.relationshipRole,
                    contextualMemory = it.contextualMemory
                )
            }
        }
    ),
    scope = scope,
    userIdProvider = { FifoDataRepository.userProfile.value.id }
)
```

`start()` después de `telephony.start()`; `stop()` antes de `telephony.stop()`.

---

## Files Changed

| File | Change |
|---|---|
| `telephony/CallerContextProvider.kt` | **New** — modelo, clasificador, proveedor local/remoto y contrato de fuente |
| `telephony/BackendCallerIdentitySource.kt` | **New** — adaptador de producción contra `FifoBackendClient` |
| `telephony/CallerContextCoordinator.kt` | **New** — coordinación con `TelephonyPort`, emisión local y enriquecimiento paralelo |
| `test/.../CallerContextProviderTest.kt` | **New** — 18 pruebas de clasificación, merge, timeout, errores y anuncio |
| `test/.../CallerContextCoordinatorTest.kt` | **New** — 5 pruebas de emisión inmediata, limpieza, cancelación y user id |

---

## What Did NOT Change

- El anuncio legacy de `VoicePipelineManager` sigue usando el nombre local inmediato; no se agregó una segunda locución.
- `PhoneCallSkill` sigue funcionando igual y puede seguir consultando el backend bajo demanda hasta que el paso 4 lo conecte al contexto compartido.
- El endpoint `identify-contact` y el esquema de base de datos no cambian.
- `VoiceSession` no conoce `TelephonyPort` ni `CallerContext`; el coordinador es un observador externo.

---

## Consequences

**Positive:**

- El timbrado no espera a la red: el nombre local se anuncia de inmediato.
- El backend puede aportar parentesco y contexto sin bloquear ni duplicar el anuncio.
- Conocido y desconocido son estados explícitos y testeables.
- Los temas recientes no se leen sin que el usuario pregunte.
- La cancelación estructurada y el descarte de resultados viejos evitan contextos de llamadas anteriores.

**Negative / Trade-offs:**

- Durante la migración conviven el contexto nuevo y las consultas puntuales de `PhoneCallSkill`; el paso 4 debe unificarlos para no repetir llamadas al backend.
- El backend auto-cataloga desconocidos; el proveedor conserva `isKnown=false` para no convertir una catalogación automática en confianza.
- El coordinador depende de que `TelephonyPort.callState` sea un `StateFlow` con el estado actual; ya es el contrato.

---

## Relationship to Other ADRs

- **ADR-0008** define `TelephonyPort` y `CallSnapshot`; este ADR consume ese contrato.
- **ADR-0004** define el endpoint `identify-contact` y el grafo relacional; este ADR lo usa en el momento del timbrado, no solo al contestar.
- **ADR-0005** sigue vigente en cuanto al colapso de `VoicePipelineManager`, pero su invariante de telefonía queda detrás de `TelephonyPort` + `CallerContextCoordinator`.
