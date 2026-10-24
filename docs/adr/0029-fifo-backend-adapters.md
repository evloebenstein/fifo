# ADR-0029: Adaptadores por dominio para el backend propio

**Status:** Accepted

**Date:** 2026-10-23

**Author:** Architecture Review — Network ownership

---

## Context

`FifoBackendClient` era un `object` de ~900 líneas que mezclaba:

- el `OkHttpClient`, los timeouts y la `baseUrl` mutable USB/emulador;
- salud y descarga del bundle completo;
- autenticación, alta de perfil y edición de identidad;
- escrituras de gustos, posts, recuerdos, recordatorios y rutinas;
- consolidación profunda y GraphRAG;
- contactos relacionales y telefonía;
- ubicación del dispositivo y navegación;
- comunidad, amigos y actividades.

Todos los consumidores (`FifoDataRepository`, skills de voz, telefonía,
`LoginScreen`, `FriendsScreen`, `EpisodicMemoryModule`) dependían de la misma
fachada, así que un cambio en cualquier dominio obligaba a leer el archivo
completo y aumentaba el riesgo de tocar telefonía o voz por accidente.

Ya existía el precedente de `CloudApiClient` (ADR-0012/0013/0014): fachada
delgada + adaptadores de dominio.

---

## Decision

### 1. `BackendHttp` es el único dueño del transporte

`network/BackendHttp.kt` concentra:

- `OkHttpClient` con los mismos timeouts (4 s conexión, 6 s lectura/escritura);
- `Content-Type: application/json; charset=utf-8`;
- la `baseUrl` `@Volatile` (USB `127.0.0.1` / emulador `10.0.2.2`);
- `url(path)`, `jsonBody(JSONObject|JSONArray)`;
- `postOrPatchJson(url, method, payload)` con el mismo log de error;
- `isHealthy(targetUrl)`.

Los adaptadores no crean clientes ni conocen timeouts.

### 2. Siete adaptadores por dominio

| Adaptador | Responsabilidad |
|---|---|
| `BackendSyncAdapter` | `checkHealth` (con conmutación USB/emulador), `fetchUserBundle` y el parseo del bundle |
| `BackendAccountAdapter` | login, Google, alta de perfil, listado de usuarios, demografía, bio e intereses |
| `BackendContentAdapter` | gustos, historias, posts, recuerdos, recordatorios, conversaciones y rutinas |
| `BackendMemoryAdapter` | `analyze-and-consolidate` y `recall-context` |
| `BackendContactAdapter` | contactos relacionales, lote, `identify-contact` y `log-call-event` |
| `BackendDeviceAdapter` | ubicación del dispositivo y contexto de navegación |
| `BackendCommunityAdapter` | recomendaciones de amigos y actividades comunitarias |

Cada uno recibe el `BackendHttp` compartido y conserva sus firmas públicas.

### 3. La fachada delega, no duplica

`FifoBackendClient` mantiene:

- la API pública exacta que ya usaban UI, skills y repositorio;
- los tipos anidados `SyncedUserBundle`, `AuthResult` y `UserProfileSummary`;
- la propiedad `baseUrl`, ahora respaldada por `BackendHttp`;
- métodos de una línea que delegan en el adaptador correspondiente.

No quedan cuerpos HTTP, payloads ni parseos en la fachada.

### 4. El comportamiento observable no cambia

Se conservan:

- rutas, verbos, payloads y valores por defecto;
- timeouts y `baseUrl` inicial;
- la conmutación `127.0.0.1` ↔ `10.0.2.2` en `checkHealth`;
- los logs con el mismo tag `FifoBackendClient`;
- los fallbacks (`null`, `emptyList()`, `false`) y sus mensajes;
- los tipos anidados que consumen `SyncBundleWriter` y `LoginScreen`.

También se eliminaron dos variables muertas que el compilador reportó al
mover el código: `postsArr` en recomendaciones y `contactsCount` en la
consolidación. No tenían efecto observable.

---

## Files Changed

| File | Change |
|---|---|
| `network/BackendHttp.kt` | **New** — transporte, baseUrl y helper JSON |
| `network/BackendSyncAdapter.kt` | **New** — salud y bundle |
| `network/BackendAccountAdapter.kt` | **New** — cuenta y perfil |
| `network/BackendContentAdapter.kt` | **New** — contenido del usuario |
| `network/BackendMemoryAdapter.kt` | **New** — consolidación y GraphRAG |
| `network/BackendContactAdapter.kt` | **New** — contactos y telefonía |
| `network/BackendDeviceAdapter.kt` | **New** — dispositivo y navegación |
| `network/BackendCommunityAdapter.kt` | **New** — comunidad |
| `network/FifoBackendClient.kt` | **Updated** — fachada delgada con delegaciones |
| `network/README.md` | **Updated** — mapa de adaptadores |
| `docs/README.md` | **Updated** — índice de ADR |

---

## What Did NOT Change

- No cambian endpoints, contratos JSON ni firmas públicas.
- No cambian `SyncedUserBundle`, `AuthResult` ni `UserProfileSummary`.
- No cambian los llamadores (`FifoDataRepository`, skills, telefonía, UI).
- No se tocaron `VoiceSession`, `VoiceSessionPipeline`, el camino legacy ni
  la memoria durable.
- No se agregaron dependencias ni un segundo cliente HTTP.

---

## Consequences

**Positive:**

- Cada dominio se lee y se cambia en un archivo acotado.
- El transporte y la `baseUrl` tienen un único dueño.
- La fachada conserva compatibilidad total con los consumidores existentes.
- Un cambio en comunidad o perfil ya no obliga a revisar telefonía o voz.

**Negative / Trade-offs:**

- Hay más archivos y una indirección por llamada.
- Los adaptadores comparten `BackendHttp` como estado mutable de `baseUrl`; la
  conmutación de salud sigue siendo global, igual que antes.
- No se agregaron tests JVM de red: no hay `MockWebServer` en las dependencias
  y no se introdujo infraestructura nueva. La validación es de compilación y de
  la suite existente.

---

## Related

- [ADR-0006](0006-cloud-cognition-groq.md) — cognición en la nube.
- [ADR-0012](0012-cloud-api-facade.md) — fachada de `CloudApiClient`.
- [ADR-0013](0013-cloud-api-adapters.md) — adaptadores STT/TTS/diálogo.
- [ADR-0014](0014-session-memory-adapter.md) — memoria de sesión.
- [ADR-0028](0028-sync-bundle-writer.md) — consumidor de `SyncedUserBundle`.
