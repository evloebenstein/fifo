# 🛠️ Voice Skills Catalog (`com.fifo.voicepipeline.skills`)

Catálogo de habilidades ejecutables por el LLM (Groq) y por intenciones locales de voz. Ver **ADR-0003** (`docs/adr/0003-knowledge-graph-and-intent-router.md`) para el diseño del enrutador y el catálogo.

---

## Módulos

| Archivo | Rol |
| :--- | :--- |
| [`FifoSkill.kt`](FifoSkill.kt) | Interfaz base: `execute(args): SkillResult`. Toda skill implementa esta interfaz. |
| [`FifoSkillRegistry.kt`](FifoSkillRegistry.kt) | **Fachada de delegación** sobre `GroqToolCatalog` y `VoiceIntentRouter`. Registra las 14 skills activas y las despacha por nombre. |
| [`VoiceIntentRouter.kt`](VoiceIntentRouter.kt) | Enrutador local sub-milisegundo para comandos de hardware sin ambigüedad (linterna, volumen, hora, batería, contestar/colgar llamada, cancelar ruta). Devuelve `null` para todo lo conversacional e informativo, incluida la ubicación y la orientación. No depende de `Context`. |
| [`GroqToolCatalog.kt`](GroqToolCatalog.kt) | Catálogo OpenAI/Groq 100% dinámico. Genera los schemas JSON para el LLM sin acoplarse a un proveedor heredado. |
| [`CalendarSkill.kt`](CalendarSkill.kt) | Día actual, fechas y recordatorios de calendario. |
| [`ContactsSkill.kt`](ContactsSkill.kt) | Búsqueda, adición y notas en la agenda de contactos del teléfono. |
| [`ContextRecallSkill.kt`](ContextRecallSkill.kt) | Memoria profunda bajo demanda (`recall_past_context`). Devuelve payload (`context_status`, `synthesized_context`, `excerpts`, `found_entities`, `conversations_searched`) y `spokenFeedback` vacío; `no_evidence` es un hecho, no una frase (ADR-0049). |
| [`CurrentLocationSkill.kt`](CurrentLocationSkill.kt) | Dirección actual por GPS con geocodificación inversa. |
| [`FindFifoDeviceSkill.kt`](FindFifoDeviceSkill.kt) | Hace pitar el ESP32 físico para localizarlo. |
| [`MapsSkill.kt`](MapsSkill.kt) | Busca lugares cercanos (farmacias, supermercados, parques) por Overpass API. |
| [`NavigationDirectionsSkill.kt`](NavigationDirectionsSkill.kt) | Guía de navegación peatonal hacia un destino con orientación corporal. No usa hardcoded fallbacks. |
| [`NavigationSessionSkill.kt`](NavigationSessionSkill.kt) | Estado conversacional de una ruta activa (`manage_navigation_session`): encargos en el camino, pausas, reanudación y correcciones. Devuelve datos; el LLM redacta (ADR-0040). |
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
4. **Consultas informativas nunca se responden en el router:** ubicación (“¿dónde estoy?”), orientación activa (“¿dónde voy?”, “¿cuánto falta?”, “me desvié”) y cualquier pregunta de memoria o recomendación retornan `null`. El router solo ejecuta acciones mecánicas (contestar/colgar, hardware, cancelar ruta). Ver ADR-0039.
5. **Resolución compartida de personas:** llamadas, mensajería y recordatorios resuelven nombres, roles y apodos con `FifoDataRepository.findContactByRoleOrName()`, respaldado por `ContactMatcher`. No reimplementes normalización de acentos ni comparación de tokens en una skill (ADR-0034).
6. **La memoria profunda devuelve datos, no frases:** `ContextRecallSkill` recibe `ConversationContext.searchDeep()` por costura, no llama a la red ni a SQLite, y deja que el LLM redacte. La decisión de buscar vive en la política de recuperación del prompt, no en la skill (ADR-0049).
