# 🛠️ Voice Skills Catalog (`com.fifo.voicepipeline.skills`)

Catálogo de habilidades ejecutables mediante comandos de voz directos o a través de llamadas a herramientas (*Tool Calling*) de Claude / Groq.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`FifoSkill.kt`](FifoSkill.kt) | **Interfaz Base**: Define el contrato `FifoSkill`, parámetros JSON para el LLM y el objeto `FifoSkillResult` (resultado estructurado y mensaje hablado de retroalimentación). |
| [`FifoSkillRegistry.kt`](FifoSkillRegistry.kt) | **Registro y Despachador Central**: Coordina las 14 habilidades. Dispone de un despachador rápido por expresiones regulares (para ejecución instantánea sin latencia de red, ej: *"Fifo qué hora es"*, *"Fifo enciende la linterna"*) y genera los esquemas JSON de herramientas para Claude / Gemini. |
| [`CalendarSkill.kt`](CalendarSkill.kt) | **Calendario y Fechas**: Consulta el día actual, festivos y eventos programados. |
| [`ContactsSkill.kt`](ContactsSkill.kt) | **Agenda de Contactos**: Busca números telefónicos, añade nuevos familiares y actualiza notas relacionales. |
| [`ContextRecallSkill.kt`](ContextRecallSkill.kt) | **Recuperación de Memoria Pasada**: Busca en la base de datos recuerdos de conversaciones anteriores para responder preguntas personales. |
| [`CurrentLocationSkill.kt`](CurrentLocationSkill.kt) | **Ubicación Actual**: Indica en voz alta la calle, número y comuna donde se encuentra la persona. |
| [`FindFifoDeviceSkill.kt`](FindFifoDeviceSkill.kt) | **Encontrar el Robot**: Hace pitar al ESP32 o parpadear su pantalla OLED si el usuario no sabe dónde dejó a Fifo. |
| [`MapsSkill.kt`](MapsSkill.kt) | **Búsqueda de Lugares**: Localiza farmacias, hospitales, almacenes o plazas cercanas. |
| [`NavigationDirectionsSkill.kt`](NavigationDirectionsSkill.kt) | **Navegación GPS**: Inicia la guía paso a paso para llegar a una dirección o destino solicitado. |
| [`PhoneCallSkill.kt`](PhoneCallSkill.kt) | **Llamadas Telefónicas**: Inicia llamadas telefónicas celulares a contactos (ej: *"Fifo llama a mi hija Carmen"*). |
| [`PhoneCommunicationSkill.kt`](PhoneCommunicationSkill.kt) | **Mensajería**: Envía mensajes de texto o WhatsApp a familiares. |
| [`PhoneDeviceControlSkill.kt`](PhoneDeviceControlSkill.kt) | **Control del Dispositivo**: Enciende/apaga la linterna, ajusta el volumen y consulta el porcentaje de batería. |
| [`ProfileDatabaseSkill.kt`](ProfileDatabaseSkill.kt) | **Gestión de Perfiles y Gustos**: Cambia de usuario activo y consulta gustos registrados en la base de datos. |
| [`ReminderSkill.kt`](ReminderSkill.kt) | **Recordatorios de Voz**: Crea recordatorios (medicamentos, citas médicas, tareas) y los lista en voz alta. |
| [`WebSearchSkill.kt`](WebSearchSkill.kt) | **Búsqueda Web**: Consulta información enciclopédica general en internet mediante DuckDuckGo. |

---

## 🧭 Reglas de Programación en `skills/`
1. **Doble Vía de Ejecución:** Siempre que sea posible, implementar un `fastIntentMatcher` con regex en `FifoSkillRegistry` para dar respuesta inmediata sin esperar los 2-3 segundos de inferencia del LLM.
2. **Retroalimentación Afectuosa:** El texto en `spokenFeedback` debe ser conciso, empático y en español claro para personas mayores.
