# 🗺️ Mapa de Base de Datos, Arquitectura y Sistema de Skills de Fifo

> **Filosofía Central de Producto:**  
> **El adulto mayor nunca necesita ingresar a la app del celular.** Solo debe hablar con el robot físico Fifo (o mediante voz). Fifo actúa como su operador personal y asistente de bienestar: comprende sus necesidades, ejecuta *mutaciones en la base de datos* y activa *herramientas del sistema del teléfono* (recordatorios, calendario, mapas, llamadas). Toda la información se sincroniza automáticamente y se ve reflejada en tiempo real en la aplicación móvil para el usuario y su red familiar.

---

## 📑 Tabla de Contenidos
1. [Flujo de Arquitectura Global](#1-flujo-de-arquitectura-global)
2. [Esquema de Base de Datos (Tablas y Colecciones)](#2-esquema-de-base-de-datos-tablas-y-colecciones)
3. [Matriz de Mapeo: Pantallas de la App ↔ Base de Datos ↔ Acciones de Voz de Fifo](#3-matriz-de-mapeo-pantallas-de-la-app--base-de-datos--acciones-de-voz-de-fifo)
4. [Sistema de Skills y Herramientas del Teléfono para Fifo](#4-sistema-de-skills-y-herramientas-del-teléfono-para-fifo)
5. [Guía de Implementación Técnica (Firestore / Supabase / SQLite Room)](#5-guía-de-implementación-técnica)
6. [Reglas de Privacidad y Manejo de Información Sensible](#6-reglas-de-privacidad-y-manejo-de-información-sensible)

---

## 1. Flujo de Arquitectura Global

```
┌────────────────────────────────────────────────────────────────────────┐
│                   1. INTERACCIÓN POR VOZ HUMANA                        │
│   El adulto mayor habla con el robot Fifo (o al micrófono del celular):│
│   "Fifo, mi cumpleaños es el 14 de mayo de 1958"                       │
│   "Fifo, recuérdame tomar el Enalapril a las 8 de la noche"             │
│   "Fifo, ¿dónde queda la farmacia más cercana?"                        │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   2. CAPTURA Y PIPELINE DE VOZ                         │
│   • ESP32-S3 (Mic UCC I2S + Filtro IIR + Noise Gate) vía BLE           │
│   • VAD & Wake Word ("FIFO")                                           │
│   • Speech-To-Text (Whisper / Google Voice Recognizer)                 │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│             3. CEREBRO IA CON FUNCTION CALLING (CLAUDE)                │
│   • Modelo: Claude Haiku 4.5                                           │
│   • System Prompt empático para tercera edad                           │
│   • Catálogo de Skills (Tools API con JSON Schema)                     │
│   • Claude decide qué Tool ejecutar según lo conversado                │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                  ┌─────────────────┴─────────────────┐
                  ▼                                   ▼
┌───────────────────────────────────┐ ┌──────────────────────────────────┐
│     4A. SKILLS DEL TELÉFONO       │ │     4B. MUTACIONES EN LA BD      │
│  • AlarmManager / Recordatorios   │ │  • Actualizar perfil / edad / bday│
│  • Calendario nativo de Android   │ │  • Agregar/Quitar gustos         │
│  • Google Maps (Farmacias, etc.)  │ │  • Crear historia biográfica     │
│  • Discado rápido a familiares    │ │  • Publicar en Fifo Amigos       │
└─────────────────┬─────────────────┘ └───────────────────┬──────────────┘
                  │                                       │
                  │                                       ▼
                  │                    ┌─────────────────────────────────┐
                  │                    │   5. BASE DE DATOS CENTRAL      │
                  │                    │ (Firestore / Supabase / Cloud)  │
                  │                    └──────────────────┬──────────────┘
                  │                                       │ Snapshot
                  │                                       ▼ Listener
                  │                    ┌─────────────────────────────────┐
                  │                    │ 6. APP ANDROID (ESTADO EN VIVO) │
                  │                    │ StateFlow / Jetpack Compose     │
                  │                    │ • Pantalla de Perfil            │
                  │                    │ • Dashboard Principal           │
                  │                    │ • Comunidad Fifo Amigos         │
                  │                    │ • Catálogo de Actividades       │
                  │                    └─────────────────────────────────┘
                  │
                  ▼
┌────────────────────────────────────────────────────────────────────────┐
│                 7. RESPUESTA POR VOZ CÁLIDA Y NATURAL                  │
│   Fifo responde verbalmente por el parlante (Android TTS / OpenAI TTS):│
│   "¡Listo Lucía! Ya guardé tu cumpleaños para celebrarlo con cariño."   │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Esquema de Base de Datos (Tablas y Colecciones)

A continuación se define la estructura de datos en formato relacional / NoSQL (compatible con **Firebase Firestore**, **Supabase / PostgreSQL** o **Android Room**).

### 2.1. Colección: `users`
Contiene la identidad principal de la persona y sus datos demográficos básicos.

| Campo | Tipo | Requerido | Descripción | Ejemplo |
| :--- | :--- | :---: | :--- | :--- |
| `id` | `String` (UUID) | Sí | Identificador único del usuario | `"usr_lucia_01"` |
| `full_name` | `String` | Sí | Nombre completo del usuario | `"Lucía González"` |
| `email` | `String` | Sí | Correo de la cuenta | `"lucia.gonzalez@correo.cl"` |
| `birth_date` | `String` | Sí | Fecha de cumpleaños completa | `"14 de Mayo, 1958"` |
| `birth_year` | `Int` | Sí | Año de nacimiento | `1958` |
| `estimated_age` | `Int` | Sí | Edad calculada automáticamente | `68` |
| `gender_identity`| `String` | Sí | Identidad para trato afectuoso | `"Mujer"` / `"Hombre"` / `"No binario"` |
| `city` | `String` | Sí | Ciudad o comuna de residencia | `"Santiago, Chile"` |
| `avatar_url` | `String?` | No | Foto o avatar del usuario | `null` |
| `created_at` | `Timestamp` | Sí | Fecha de registro | `2026-10-01T10:00:00Z` |
| `updated_at` | `Timestamp` | Sí | Última actualización | `2026-10-03T21:40:00Z` |

```json
{
  "id": "usr_lucia_01",
  "full_name": "Lucía González",
  "email": "lucia.gonzalez@correo.cl",
  "birth_date": "14 de Mayo, 1958",
  "birth_year": 1958,
  "estimated_age": 68,
  "gender_identity": "Mujer",
  "city": "Santiago, Chile",
  "avatar_url": null,
  "created_at": "2026-10-01T10:00:00Z",
  "updated_at": "2026-10-03T21:40:00Z"
}
```

---

### 2.2. Colección: `user_profiles`
Información enriquecida gestionada por la IA a partir de las charlas.

| Campo | Tipo | Requerido | Descripción | Ejemplo |
| :--- | :--- | :---: | :--- | :--- |
| `user_id` | `String` | Sí | Clave foránea que referencia a `users.id` | `"usr_lucia_01"` |
| `bio_ai` | `String` | Sí | Biografía redactada por Fifo a partir de las conversaciones | `"Amante de las novelas de historia, la música clásica de piano y las mañanas tranquilas con café..."` |
| `bio_last_updated`| `Timestamp` | Sí | Fecha en que Fifo actualizó la biografía | `2026-10-03T18:00:00Z` |
| `emergency_contact_name` | `String?` | No | Nombre del contacto de apoyo | `"Carmen (Hija)"` |
| `emergency_contact_phone`| `String?` | No | Teléfono de emergencia | `"+56987654321"` |
| `preferred_address` | `String?` | No | Dirección para mapas y traslados | `"Av. Providencia 1234, Santiago"` |

---

### 2.3. Colección: `tastes` (Gustos e Intereses)
Intereses y aficiones descubiertos orgánicamente por Fifo en sus charlas diarias.

| Campo | Tipo | Requerido | Descripción | Ejemplo |
| :--- | :--- | :---: | :--- | :--- |
| `id` | `String` | Sí | ID único del gusto | `"tst_01"` |
| `user_id` | `String` | Sí | Referencia a `users.id` | `"usr_lucia_01"` |
| `name` | `String` | Sí | Nombre del pasatiempo o gusto | `"Música clásica"` |
| `category` | `String` | Sí | Categoría (`musica`, `arte`, `naturaleza`, `lectura`, `cocina`) | `"musica"` |
| `is_active` | `Boolean` | Sí | Si está activo o el usuario pidió quitarlo | `true` |
| `source_conversation_id` | `String?` | No | ID de la charla donde se aprendió | `"chat_20261002_01"` |
| `learned_at` | `Timestamp` | Sí | Fecha en que Fifo lo incorporó | `2026-10-02T16:30:00Z` |

---

### 2.4. Colección: `taste_stories` (Gustos en Detalle / Mini-Blogs)
Historias detalladas y artículos de blog que Fifo redacta automáticamente sobre los gustos del usuario (sin incluir información privada sensible).

| Campo | Tipo | Requerido | Descripción | Ejemplo |
| :--- | :--- | :---: | :--- | :--- |
| `id` | `String` | Sí | ID único de la historia | `"story_bday_01"` |
| `user_id` | `String` | Sí | Referencia a `users.id` | `"usr_lucia_01"` |
| `title` | `String` | Sí | Título atractivo | `"Cumpleaños y orígenes familiares"` |
| `subtitle` | `String` | Sí | Resumen corto | `"Nacida el 14 de Mayo de 1958 en Santiago"` |
| `description` | `String` | Sí | Relato o reflexión generada por Fifo | `"Para Lucía el mes de mayo siempre trae recuerdos cálidos de reuniones familiares..."` |
| `tags` | `List<String>` | Sí | Etiquetas temáticas | `["Familia", "Mayo", "Tradiciones"]` |
| `icon_category` | `String` | Sí | Categoría visual para icono | `"cake"` / `"music"` / `"book"` / `"leaf"` |
| `learned_from` | `String` | Sí | Contexto de origen | `"Charla sobre cumpleaños y juventud"` |
| `created_at` | `Timestamp` | Sí | Fecha de creación | `2026-10-03T19:00:00Z` |

---

### 2.5. Colección: `social_posts` (Fifo Amigos)
Publicaciones que el usuario comparte con otros amigos mayores en la comunidad Fifo.

| Campo | Tipo | Requerido | Descripción | Ejemplo |
| :--- | :--- | :---: | :--- | :--- |
| `id` | `String` | Sí | ID de la publicación | `"post_lucia_01"` |
| `user_id` | `String` | Sí | Referencia a `users.id` | `"usr_lucia_01"` |
| `author_name` | `String` | Sí | Nombre para mostrar | `"Lucía"` |
| `author_age` | `Int` | Sí | Edad del autor | `68` |
| `category` | `String` | Sí | Categoría (`"Jardinería"`, `"Música"`, `"Lectura"`) | `"Jardinería"` |
| `content` | `String` | Sí | Contenido del post | `"Hoy mis orquídeas dieron su primera flor blanca..."` |
| `likes_count` | `Int` | Sí | Número de apoyos recibidos | `12` |
| `comments_count`| `Int` | Sí | Número de comentarios | `3` |
| `accent_color_hex`| `Long` | Sí | Color estético de la tarjeta | `0xFF10B981` |
| `created_at` | `Timestamp` | Sí | Fecha de publicación | `2026-10-03T14:00:00Z` |

---

### 2.6. Colección: `reminders_and_events` (Recordatorios y Calendario)
Recordatorios de salud, bienestar, familia y eventos de calendario programados por voz.

| Campo | Tipo | Requerido | Descripción | Ejemplo |
| :--- | :--- | :---: | :--- | :--- |
| `id` | `String` | Sí | ID del recordatorio | `"rem_pills_01"` |
| `user_id` | `String` | Sí | Referencia a `users.id` | `"usr_lucia_01"` |
| `title` | `String` | Sí | Título del recordatorio | `"Tomar pastilla de la presión (Enalapril)"` |
| `due_datetime` | `Timestamp` | Sí | Fecha y hora programada | `2026-10-04T20:00:00Z` |
| `repeat_rule` | `String` | Sí | Frecuencia (`"once"`, `"daily"`, `"weekly"`) | `"daily"` |
| `category` | `String` | Sí | Categoría (`"medication"`, `"hobby"`, `"family"`, `"doctor"`) | `"medication"` |
| `is_completed` | `Boolean` | Sí | Estado de cumplimiento | `false` |
| `calendar_event_id` | `Long?` | No | ID si se sincronizó con el Calendario de Android | `4523` |
| `spoken_text` | `String` | Sí | Frase exacta con la que Fifo avisará al usuario | `"Lucía, son las ocho de la noche: es hora de su pastilla de la presión con un vaso de agua."` |

---

### 2.7. Colección: `past_conversations` y `memories`
Bitácora de bienestar y recuerdos entrañables aprendidos por Fifo.

* **`past_conversations`**:
  * `id`: `"conv_103"`
  * `title`: `"Charla sobre orquídeas y té de manzanilla"`
  * `summary`: `"Lucía compartió cómo cuidaba su jardín en el sur..."`
  * `duration_seconds`: `185`
  * `topic_tag`: `"Jardinería"`
  * `recorded_at`: `2026-10-03T16:20:00Z`

* **`memories`**:
  * `id`: `"mem_501"`
  * `emoji`: `"🌸"`
  * `title`: `"Su flor favorita es la orquídea blanca"`
  * `detail`: `"Le recuerda el patio de su madre en Valdivia"`
  * `learned_date`: `"Hace 2 días"`

---

## 3. Matriz de Mapeo: Pantallas de la App ↔ Base de Datos ↔ Acciones de Voz de Fifo

Esta tabla es la guía definitiva para saber **qué pantalla y qué componente visual se actualiza cuando Fifo ejecuta una acción por voz**:

| Pantalla Android | Componente Visual en la App | Tabla y Campo en BD | Skill de Fifo que lo Modifica | Ejemplo de Frase del Usuario por Voz |
| :--- | :--- | :--- | :--- | :--- |
| **`ProfileScreen`** | Nombre en tarjeta de perfil | `users.full_name` | `update_user_demographics` | *"Fifo, mi nombre completo es Lucía González"* |
| **`ProfileScreen`** | Insignia: `🎂 Cumpleaños: [fecha]` | `users.birth_date`, `birth_year`, `estimated_age` | `update_user_demographics` | *"Fifo, nací el 14 de Mayo de 1958"* |
| **`ProfileScreen`** | Chips de demografía (`Edad`, `Género`, `Ciudad`) | `users.estimated_age`, `gender_identity`, `city` | `update_user_demographics` | *"Fifo, vivo en Providencia, Santiago"* |
| **`ProfileScreen`** | Tarjeta: `Biografía creada por Fifo (Actualizada por IA)` | `user_profiles.bio_ai`, `bio_last_updated` | `generate_user_bio` | Fifo sintetiza automáticamente la bio cada 3 conversaciones |
| **`ProfileScreen`** | Chips: `Gustos que Fifo recuerda de ti` | `tastes` (`name`, `category`, `is_active`) | `add_user_taste`, `remove_user_taste` | *"Fifo, me encanta hacer crucigramas por las tardes"* |
| **`ProfileScreen`** | Pestaña: `Gustos en detalle (Historias)` | `taste_stories` (todas las filas del usuario) | `create_taste_story` | *"Fifo, cuéntale a mi perfil por qué me gusta tanto hornear queques"* |
| **`ProfileScreen`** | Pestaña: `Mis publicaciones` | `social_posts` (`where user_id = me`) | `publish_social_post` | *"Fifo, publica en la comunidad que florecieron mis orquídeas"* |
| **`HomeScreen`** | Saludo personalizado (*"Buenas tardes, Lucía"*) | `users.full_name` | `update_user_demographics` | Refleja automáticamente el nombre |
| **`HomeScreen`** | Indicador de Recordatorios del día | `reminders_and_events` (`where due_date = today`) | `set_reminder`, `complete_reminder` | *"Fifo, recuérdame a las 5 salir a caminar"* |
| **`FriendsScreen`** | Muro de la comunidad | `social_posts` | `publish_social_post` | *"Fifo, comparte con mis amigos que hoy leí un poema hermoso"* |
| **`ActivitiesScreen`**| Recomendaciones adaptadas a sus gustos | `tastes` + `users.estimated_age` | `add_user_taste` | Las actividades sugeridas se recalculan con los nuevos gustos |
| **`Ajustes / Dialog`**| Diálogo *Editar Perfil* | `users` (`full_name`, `birth_date`, `age`, `gender`) | Pre-poblado con los datos de `users` | El diálogo manual lee siempre la misma BD |

---

## 4. Sistema de Skills y Herramientas del Teléfono para Fifo

Fifo cuenta con un sistema de **Skills (Herramientas / Function Calling)** que le permite interactuar directamente con el hardware del teléfono y con la base de datos central sin que el usuario toque la pantalla.

### 4.1. Catálogo de Skills Implementadas

#### ⏰ 1. `ReminderSkill` (Recordatorios y Medicamentos)
* **Objetivo:** Garantizar que el adulto mayor nunca olvide una medicina o compromiso.
* **Funciones:**
  * `set_reminder(title, time_str, category, repeat_rule)`: Programa una alarma con voz y notificación en el teléfono.
  * `list_reminders()`: Consulta los recordatorios pendientes para hoy.
  * `cancel_reminder(reminder_id)`: Cancela un recordatorio.
* **Respuesta hablada de Fifo:**  
  *"Listo Lucía, le he programado el recordatorio para tomar su pastilla de la presión hoy a las ocho de la noche."*

#### 📅 2. `CalendarSkill` (Calendario del Celular)
* **Objetivo:** Anotar citas médicas, visitas familiares o talleres en Google Calendar / Calendario nativo.
* **Funciones:**
  * `add_calendar_event(title, date_str, time_str, duration_minutes, description)`: Crea un evento en el calendario mediante `Intent.ACTION_INSERT` o `CalendarContract`.
  * `query_calendar_events(days_ahead)`: Lee citas programadas para mantener informado al usuario.
* **Respuesta hablada de Fifo:**  
  *"Anoté en su calendario la cita con el oftalmólogo para este jueves a las diez de la mañana."*

#### 🗺️ 3. `MapsSkill` (Mapas y Lugares Cercanos)
* **Objetivo:** Ayudar al usuario a encontrar servicios esenciales a pie o en transporte.
* **Funciones:**
  * `search_nearby_places(place_type, query_hint)`: Busca farmacias, centros de salud (CESFAM), hospitales, parques o panaderías cercanas y abre la ruta en Google Maps.
  * `open_directions(destination)`: Genera la ruta paso a paso hacia un destino.
* **Respuesta hablada de Fifo:**  
  *"He buscado las farmacias más cercanas a su domicilio y le he dejado el mapa abierto en el teléfono con la ruta más directa."*

#### 📞 4. `PhoneCommunicationSkill` (Llamadas y Mensajes de Tranquilidad)
* **Objetivo:** Conectar de inmediato con seres queridos o servicios de urgencia sin necesidad de marcar números.
* **Funciones:**
  * `call_contact(contact_name_or_role)`: Inicia una llamada al familiar (hija, hijo) o emergencia (131 SAMU, 132 Bomberos) mediante `Intent.ACTION_DIAL`.
  * `send_checkin_sms(contact_name, message)`: Envía un mensaje breve a la familia notificando que el usuario se encuentra bien o necesita ayuda.
* **Respuesta hablada de Fifo:**  
  *"Llamando de inmediato a su hija Carmen por teléfono."*

#### 💾 5. `ProfileDatabaseSkill` (Mutaciones de Perfil y Comunidad)
* **Objetivo:** Guardar en la base de datos cada dato personal y gusto aprendido en la conversación.
* **Funciones:**
  * `update_demographics(full_name, birth_date, birth_year, gender, city)`: Actualiza identidad y cumpleaños.
  * `add_taste(taste_name, category, reason)`: Registra un nuevo pasatiempo.
  * `remove_taste(taste_name)`: Da de baja un gusto que ya no practica.
  * `create_taste_story(title, subtitle, description, tags, icon_category)`: Publica una historia detallada en el perfil.
  * `publish_social_post(content, category)`: Publica en la comunidad Fifo Amigos.
  * `save_conversation_summary(title, summary, topic_tag, duration_sec)`: Registra la bitácora de bienestar.

---

### 4.2. Definición JSON Schema para Claude (Anthropic Tools API)

```json
[
  {
    "name": "set_reminder",
    "description": "Programa un recordatorio o alarma en el teléfono del usuario para medicamentos, agua, familia o actividades.",
    "input_schema": {
      "type": "object",
      "properties": {
        "title": { "type": "string", "description": "Motivo del recordatorio (ej: 'Tomar Enalapril')" },
        "time_str": { "type": "string", "description": "Hora en formato 24h 'HH:mm' o texto relativo 'en 30 minutos'" },
        "category": { "type": "string", "enum": ["medication", "family", "hobby", "health"], "description": "Categoría" }
      },
      "required": ["title", "time_str"]
    }
  },
  {
    "name": "search_nearby_places",
    "description": "Busca lugares de interés cercanos en el mapa del teléfono (farmacias, consultorios, parques, hospitales).",
    "input_schema": {
      "type": "object",
      "properties": {
        "place_type": { "type": "string", "enum": ["farmacia", "centro_salud", "parque", "panaderia", "hospital"], "description": "Tipo de lugar" },
        "query_hint": { "type": "string", "description": "Comuna o referencia adicional" }
      },
      "required": ["place_type"]
    }
  },
  {
    "name": "add_calendar_event",
    "description": "Añade una cita o evento en el calendario del teléfono del usuario.",
    "input_schema": {
      "type": "object",
      "properties": {
        "title": { "type": "string", "description": "Título del evento" },
        "date_str": { "type": "string", "description": "Fecha en formato 'YYYY-MM-DD' o 'hoy', 'mañana'" },
        "time_str": { "type": "string", "description": "Hora de inicio 'HH:mm'" },
        "duration_minutes": { "type": "integer", "description": "Duración en minutos (por defecto 60)" }
      },
      "required": ["title", "time_str"]
    }
  },
  {
    "name": "call_contact",
    "description": "Inicia una llamada telefónica a un familiar o servicio de urgencia.",
    "input_schema": {
      "type": "object",
      "properties": {
        "contact_name_or_role": { "type": "string", "description": "Nombre del contacto (ej: 'hija Carmen', 'SAMU 131')" }
      },
      "required": ["contact_name_or_role"]
    }
  },
  {
    "name": "update_demographics",
    "description": "Actualiza la información demográfica del usuario en la base de datos (nombre, fecha de nacimiento, edad, género, ciudad).",
    "input_schema": {
      "type": "object",
      "properties": {
        "full_name": { "type": "string", "description": "Nombre completo" },
        "birth_date": { "type": "string", "description": "Fecha de cumpleaños completa (ej: '14 de Mayo, 1958')" },
        "birth_year": { "type": "integer", "description": "Año de nacimiento (ej: 1958)" },
        "gender": { "type": "string", "description": "Identidad de género ('Mujer', 'Hombre', etc.)" },
        "city": { "type": "string", "description": "Ciudad o comuna" }
      }
    }
  },
  {
    "name": "add_user_taste",
    "description": "Registra un nuevo pasatiempo o gusto aprendido de la conversación en la base de datos.",
    "input_schema": {
      "type": "object",
      "properties": {
        "taste_name": { "type": "string", "description": "Nombre del gusto (ej: 'Crucigramas', 'Jardinería')" },
        "category": { "type": "string", "description": "Categoría (lectura, musica, aire_libre, manualidades, etc.)" }
      },
      "required": ["taste_name"]
    }
  }
]
```

---

## 5. Guía de Implementación Técnica

### 5.1. Repositorio Reactivo Local (`FifoDataRepository`)
En la aplicación Android, la capa de datos expone flujos de estado (`StateFlow`) que las pantallas de Jetpack Compose observan con `collectAsState()`.

```kotlin
// Inyección en Compose
val userProfile by FifoDataRepository.userProfile.collectAsState()
val tastes by FifoDataRepository.tastes.collectAsState()
val stories by FifoDataRepository.tasteStories.collectAsState()
```

Cuando Fifo ejecuta un Skill (por ejemplo `update_demographics`), llama a:
```kotlin
FifoDataRepository.updateDemographics(
    fullName = "Lucía González",
    birthDate = "14 de Mayo, 1958",
    birthYear = 1958,
    gender = "Mujer",
    city = "Santiago, Chile"
)
```
Esto emite de inmediato el nuevo valor en el `StateFlow`, lo que provoca que `ProfileScreen`, `HomeScreen` y el diálogo de edición se recompongan instantáneamente sin intervención del usuario.

### 5.2. Conexión con Backend Remoto (Firestore / Supabase)
1. **Escucha en tiempo real (Realtime Snapshots):**
   ```kotlin
   firestore.collection("users").document(userId)
       .addSnapshotListener { snapshot, error ->
           if (snapshot != null && snapshot.exists()) {
               val user = snapshot.toObject(UserEntity::class.java)
               FifoDataRepository.updateFromRemote(user)
           }
       }
   ```
2. **Offline-first con persistencia:**
   Habilitar caché local de Firestore o Room SQLite para que el robot y la app funcionen incluso si la conexión a internet es inestable.

---

## 6. Reglas de Privacidad y Manejo de Información Sensible

Para proteger la integridad y dignidad del adulto mayor:
1. **Exclusión de diagnósticos clínicos en historias públicas:**  
   Fifo nunca incluirá enfermedades, medicamentos específicos ni datos médicos privados en las historias de `taste_stories` ni en las publicaciones de `social_posts`. Solo registrará intereses, pasatiempos, recuerdos afectivos y anécdotas positivas.
2. **Confirmación de llamadas y mensajes:**  
   Antes de disparar una llamada o enviar un SMS a un familiar, Fifo confirmará verbalmente:  
   *"¿Desea que llame a su hija Carmen ahora mismo?"*
3. **Control de datos por la familia o tutor:**  
   La familia puede consultar la app para conocer el estado de ánimo y gustos del usuario, fomentando temas de conversación reales y cercanía familiar.
