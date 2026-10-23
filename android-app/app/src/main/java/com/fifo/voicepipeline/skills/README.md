# 🛠️ Voice Skills Catalog (`com.fifo.voicepipeline.skills`)

Catálogo de habilidades ejecutables por el LLM (Groq) y por intenciones locales de voz. Ver **ADR-0003** (`docs/adr/0003-knowledge-graph-and-intent-router.md`) para el diseño del enrutador y el catálogo.

---

## Módulos

| Archivo | Rol |
| :--- | :--- |
| [`FifoSkill.kt`](FifoSkill.kt) | Interfaz base: `execute(args): SkillResult`. Toda skill implementa esta interfaz. |
| [`FifoSkillRegistry.kt`](FifoSkillRegistry.kt) | **Fachada de delegación** sobre `GroqToolCatalog` y `VoiceIntentRouter`. Registra las 13 skills activas y las despacha por nombre. |
| [`VoiceIntentRouter.kt`](VoiceIntentRouter.kt) | Enrutador local sub-milisegundo para comandos de hardware sin ambigüedad (linterna, volumen, hora, batería, contestar/colgar llamada). Devuelve `null` para todo lo conversacional. |
| [`GroqToolCatalog.kt`](GroqToolCatalog.kt) | Catálogo OpenAI/Groq 100% dinámico. Genera los schemas JSON para el LLM sin acoplarse a un proveedor heredado. |
| [`CalendarSkill.kt`](CalendarSkill.kt) | Día actual, fechas y recordatorios de calendario. |
| [`ContactsSkill.kt`](ContactsSkill.kt) | Búsqueda, adición y notas en la agenda de contactos del teléfono. |
| [`ContextRecallSkill.kt`](ContextRecallSkill.kt) | Recuperación GraphRAG de memoria episódica pasada desde el backend (`recall_past_context`). |
| [`CurrentLocationSkill.kt`](CurrentLocationSkill.kt) | Dirección actual por GPS con geocodificación inversa. |
| [`FindFifoDeviceSkill.kt`](FindFifoDeviceSkill.kt) | Hace pitar el ESP32 físico para localizarlo. |
| [`MapsSkill.kt`](MapsSkill.kt) | Busca lugares cercanos (farmacias, supermercados, parques) por Overpass API. |
| [`NavigationDirectionsSkill.kt`](NavigationDirectionsSkill.kt) | Guía de navegación peatonal hacia un destino con orientación corporal. No usa hardcoded fallbacks. |
| [`PhoneCallSkill.kt`](PhoneCallSkill.kt) | Inicia llamadas telefónicas con identificación de caller contextual (backend knowledge graph). |
| [`PhoneCommunicationSkill.kt`](PhoneCommunicationSkill.kt) | Mensajería de texto y WhatsApp a contactos. |
| [`PhoneDeviceControlSkill.kt`](PhoneDeviceControlSkill.kt) | Linterna, volumen, batería, hora — con respuestas empáticas dinámicas. |
| [`ProfileDatabaseSkill.kt`](ProfileDatabaseSkill.kt) | Consulta y mutación del perfil de usuario activo. |
| [`ReminderSkill.kt`](ReminderSkill.kt) | Creación y listado de recordatorios de voz (medicamentos, citas). |
| [`WebSearchSkill.kt`](WebSearchSkill.kt) | Búsqueda web en tiempo real para noticias, clima y verificación de locales. |

---

## Reglas

1. **Sin respuestas hardcodeadas:** Toda skill devuelve datos estructurados en `SkillResult.data`; el LLM genera el texto hablado final con contexto y calidez. Ver ADR-0004.
2. **VoiceIntentRouter primero:** Comandos de hardware se resuelven en microsegundos localmente. El LLM solo ve lo conversacional.
3. **`spokenFeedback` como hint:** El campo `spokenFeedback` de `SkillResult` es solo un fallback de emergencia; el texto final siempre lo genera el LLM con los datos de `SkillResult.data`.
