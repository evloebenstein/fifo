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
7. [Arquitectura de Memoria de Doble Capa: Servidor Completo vs. Celular Compacto](#7-arquitectura-de-memoria-de-doble-capa-servidor-completo-vs-celular-compacto)
8. [Modo Celular, Rastreo del Robot y Navegación GPS (Maps y Waze)](#8-modo-celular-rastreo-del-robot-y-navegación-gps-maps-y-waze)

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
| `is_sensitive` | `Boolean` | Sí | Si contiene salud/familia delicada | `false` (por defecto solo positivo) |
| `explicit_consent` | `Boolean` | Sí | Consentimiento explícito si es sensible | `false` |
| `privacy_level` | `String` | Sí | Visibilidad (`"public_profile"`, `"private"`) | `"public_profile"` |
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
| `category` | `String` | Sí | Categoría (`"Jardinería"`, `"Música"`, `"Bienestar"`) | `"Jardinería"` |
| `content` | `String` | Sí | Contenido del post | `"Hoy mis orquídeas dieron su primera flor blanca..."` |
| `is_sensitive` | `Boolean` | Sí | Si aborda temas de salud o familia delicada | `false` |
| `explicit_consent` | `Boolean` | Sí | Consentimiento informado otorgado por voz | `true` (obligatorio si `is_sensitive=true`) |
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

### 2.8. Colección: `device_locations` (Rastreo del Robot Fifo y Última Conexión GPS)
Registra la última ubicación geográfica conocida y proximidad del robot físico Fifo.

| Campo | Tipo | Requerido | Descripción | Ejemplo |
| :--- | :--- | :---: | :--- | :--- |
| `device_id` | `String` | Sí | Identificador BLE del hardware ESP32 | `"FIFO-S3-ESP32"` |
| `user_id` | `String` | Sí | Referencia a `users.id` | `"usr_lucia_01"` |
| `is_connected` | `Boolean` | Sí | Si está conectado por Bluetooth ahora | `false` |
| `last_connected_time` | `String` | Sí | Hora legible de última conexión | `"Hoy a las 18:30"` |
| `last_known_latitude` | `Double` | Sí | Latitud GPS capturada por el celular | `-33.4255` |
| `last_known_longitude`| `Double` | Sí | Longitud GPS capturada por el celular | `-70.6143` |
| `last_known_address` | `String` | Sí | Dirección de la última desconexión | `"Av. Providencia 1234, Santiago"` |
| `last_known_room` | `String` | Sí | Habitación sugerida en la casa | `"Cerca del Living / Mesa de noche"` |
| `signal_strength_rssi`| `Int` | Sí | Intensidad de señal BLE en dBm | `-64` |

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

#### 🧠 6. `ContextRecallSkill` (Recuperación de Contexto Profundo bajo Demanda)
* **Objetivo:** Permitir a Fifo recordar detalles pasados de nicho (recetas antiguas, nombres de familiares, anécdotas previas) consultando la base de datos completa del servidor, sin penalizar la latencia normal en el celular.
* **Funciones:**
  * `recall_past_context(query, context_hint)`: Busca en la base de datos del servidor las conversaciones completas que coincidan con el tema o la persona solicitada y retorna un resumen sintetizado con los extractos exactos.
* **Respuesta hablada de Fifo:**  
  *"¡Claro que me acuerdo, Lucía! En nuestra charla de hace unos días sobre su cocina, me contó que el secreto de su cazuela de ave era dorar la cebolla con una pizca de comino suave antes de agregar el caldo."*

#### 📍 7. `FindFifoDeviceSkill` (Localizador del Robot Fifo / "Te perdí")
* **Objetivo:** Encontrar el robot Fifo cuando el usuario dice que no lo encuentra o pregunta "¿dónde estás?".
* **Funciones:**
  * `find_fifo_device(action)`: Si está conectado por Bluetooth, emite un tono/melodía alegre por el parlante del robot y enciende la pantalla con "¡AQUÍ ESTOY!". Si está desconectado, consulta la última ubicación GPS y dirección registradas por el celular y abre el mapa.
* **Respuesta hablada de Fifo:**  
  *"¡Aquí estoy, Lucía! Estoy conectado y muy cerca de usted. Estoy haciendo sonar una melodía por mi parlante para que me escuche: ¡bip bip bip! Siga el sonido."*

#### 🧭 8. `CurrentLocationSkill` (Ubicación GPS Celular / "¿Dónde estamos?")
* **Objetivo:** Responder con precisión geográfica cuando el usuario pregunta en qué calle o comuna se encuentra.
* **Funciones:**
  * `get_current_location()`: Lee las coordenadas GPS del celular y realiza geocodificación inversa.
* **Respuesta hablada de Fifo:**  
  *"Estamos en Avenida Providencia 1234, en la comuna de Providencia. ¿Desea que le ayude con indicaciones para llegar a algún lugar?"*

#### 🚗 9. `NavigationDirectionsSkill` (Navegación Paso a Paso con Maps o Waze)
* **Objetivo:** Guiar al usuario paso a paso abriendo Google Maps o Waze hacia su casa, consultorio, farmacia o parque.
* **Funciones:**
  * `open_navigation_directions(destination, navigation_app)`: Lanza la ruta de navegación en la app preferida.
* **Respuesta hablada de Fifo:**  
  *"He iniciado la navegación hacia su casa en Waze. Ya puede seguir las indicaciones paso a paso en la pantalla de su teléfono."*

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
  },
  {
    "name": "recall_past_context",
    "description": "Busca información detallada en conversaciones pasadas del usuario cuando necesitas recordar algo que se habló anteriormente. Usa esta herramienta cuando el usuario pregunte si recuerdas algo que le contó, o cuando necesites más contexto sobre un tema de nicho que no está en los fragmentos recientes.",
    "input_schema": {
      "type": "object",
      "properties": {
        "query": { "type": "string", "description": "Término o tema a buscar en las conversaciones pasadas (ej: 'recetario abuela', 'orquídeas', 'nieto Tomás', 'música Chopin')" },
        "context_hint": { "type": "string", "description": "Pista adicional sobre qué tipo de información buscas (ej: 'detalle de receta', 'nombre de persona', 'fecha de evento')" }
      },
      "required": ["query"]
    }
  },
  {
    "name": "find_fifo_device",
    "description": "Ayuda a encontrar el robot físico Fifo cuando el usuario dice que lo perdió, pregunta 'dónde estás', 'te perdí' o pide que emita un sonido para encontrarlo en la casa o en la calle.",
    "input_schema": {
      "type": "object",
      "properties": {
        "action": { "type": "string", "enum": ["locate", "beep", "last_known_place"], "description": "Acción a realizar" }
      },
      "required": ["action"]
    }
  },
  {
    "name": "get_current_location",
    "description": "Obtiene la ubicación geográfica actual del usuario a través del GPS del celular y le informa con exactitud en qué calle, comuna o ciudad se encuentra cuando pregunta '¿dónde estamos?' o '¿cuál es mi ubicación?'.",
    "input_schema": {
      "type": "object",
      "properties": {
        "detail_level": { "type": "string", "enum": ["street_and_city", "city_only"], "description": "Nivel de detalle" }
      }
    }
  },
  {
    "name": "open_navigation_directions",
    "description": "Abre la navegación paso a paso hacia un destino en el celular utilizando Google Maps o Waze cuando el usuario pide direcciones o pregunta cómo llegar a algún lugar.",
    "input_schema": {
      "type": "object",
      "properties": {
        "destination": { "type": "string", "description": "Destino o dirección (ej: 'mi casa', 'Farmacia Cruz Verde', 'Hospital del Salvador')" },
        "navigation_app": { "type": "string", "enum": ["google_maps", "waze"], "description": "App de navegación ('google_maps' o 'waze')" }
      },
      "required": ["destination"]
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

Para proteger la integridad, intimidad, dignidad y seguridad física/emocional del adulto mayor, Fifo implementa una política estricta de protección de datos personales y sensibles:

### 6.1 Política de Registro Positivo por Defecto
Fifo **únicamente extrae y registra de forma autónoma y proactiva**:
1. **Intereses y pasatiempos**: Actividades recreativas, gustos artísticos, música, cocina, tejido, jardinería, lectura, manualidades o caminatas.
2. **Recuerdos afectivos y biográficos**: Momentos entrañables de vida familiar, anécdotas con nietos, viajes del pasado, recetas de la abuela o mascotas queridas.
3. **Anécdotas positivas de superación o alegría cotidiana**: Pequeños logros del día a día, flores que abrieron en el jardín o encuentros amables con vecinos.

> **Regla de Oro:** Todo contenido que escape a estas tres categorías queda fuera del perfil público, de las historias (`taste_stories`) y del muro comunitario (`social_posts`).

---

### 6.2 Manejo de Información Sensible (Salud, Familia Íntima, Finanzas)
* **Principio de Confidencialidad:** Si el adulto mayor comparte temas médicos (cirugías, dolores, diagnósticos de enfermedades, tratamientos farmacológicos), situaciones familiares complejas o preocupaciones financieras, Fifo brinda contención y escucha empática con calidez, pero **NUNCA los registra como gustos, ni los convierte en historias de perfil ni en publicaciones sociales**.
* **Requisito Obligatorio de Consentimiento Claro e Inequívoco:**
  * Si el usuario manifiesta explícitamente su deseo de compartir algo catalogado como sensible (por ejemplo: *"Fifo, quiero contarle a mis amigos en la app que me dieron el alta médica del hospital y me siento con mucho ánimo"*):
    1. Fifo **NO lo publica de inmediato**.
    2. Fifo solicita confirmación verbal previa e informada:  
       *"Entiendo perfectamente. ¿Está completamente seguro de que desea compartir esta información de salud en el muro de Fifo Amigos para que otros miembros puedan leerlo, o prefiere que lo dejemos solo en nuestra conversación privada?"*
    3. Si el usuario confirma con un *"Sí, deseo publicarlo"*, Fifo efectúa la publicación marcando los campos `is_sensitive = true` y `explicit_consent = true`.
    4. Si el usuario duda o rechaza, la información no se publica y permanece únicamente en la memoria efímera de la sesión.

---

### 6.3 Prohibición Total de Contenido Sexual y Protocolo de Salvaguarda ante Abuso

#### 1. Prohibición Absoluta de Connotación Sexual General
* Queda **TOTALMENTE PROHIBIDO** el registro, almacenamiento o publicación de contenidos, historias, gustos o comentarios con connotación sexual, erótica o de adultos en perfiles, bases de datos o muros comunitarios.
* Cualquier intento de inferencia o publicación de esta naturaleza es filtrado y bloqueado tanto en la capa del LLM (System Prompt) como en los validadores de software locales (`ProfileDatabaseSkill`).

#### 2. Excepción Crítica: Protocolo de Salvaguarda ante Abuso, Violencia o Acoso
* **Caso Especial**: Si el usuario menciona una vivencia o situación de índole sexual en el contexto de **abuso sexual, acoso, vulneración de derechos o violencia contra la persona mayor**:
  * **NUNCA se publica en el muro social ni en historias públicas** (tolerancia cero a la revictimización o exposición pública).
  * **NUNCA se registra como pasatiempo ni interés**.
  * **Protocolo de Protección y Apoyo con Consentimiento del Usuario:**
    1. Fifo responde con máxima empatía, serenidad, contención emocional y respeto absoluto.
    2. Fifo ofrece de forma prudente activar ayuda o contactar a su persona de máxima confianza (familiar tutor / hija) o a un canal oficial especializado de asistencia a personas mayores (ej. SENAMA Fono Mayor 800 400 035 en Chile, SAMU 131 o canal de apoyo a víctimas):  
       *"Lamento profundamente lo que me está contando y su bienestar y seguridad son lo más importante para mí. Quiero que se sienta en un lugar protegido. ¿Me autoriza a contactar a su hija Carmen o a comunicarnos con un servicio de ayuda confidencial para apoyarle?"*
    3. Solo con el **consentimiento claro y voluntario** de la persona, Fifo facilita el contacto telefónico de emergencia mediante `PhoneCommunicationSkill`.

---

### 6.4 Matriz de Permisos de Almacenamiento y Publicación

| Categoría de Información | Registro en Perfil (`tastes` / `stories`) | Publicación Comunitaria (`social_posts`) | Acción Requerida de Fifo |
| :--- | :---: | :---: | :--- |
| **Intereses y pasatiempos** (jardinería, tango) |  Automático |  Bajo solicitud | Registrar directamente y dar retroalimentación cariñosa. |
| **Recuerdos afectivos y anécdotas positivas** |  Automático |  Bajo solicitud | Registrar en historias del perfil (`taste_stories`). |
| **Salud, diagnósticos o medicamentos** | ❌ Excluido por defecto | ⚠️ Requiere Consentimiento Expreso | Preguntar: *"¿Desea compartir esto o mantenerlo privado?"* |
| **Conflictos familiares o finanzas privadas** | ❌ Prohibido | ❌ Prohibido | Escuchar con empatía; nunca registrar ni publicar. |
| **Contenido sexual o erótico general** | 🚫 **TOTALMENTE PROHIBIDO** | 🚫 **TOTALMENTE PROHIBIDO** | Bloqueo estricto por filtro de seguridad y ética. |
| **Revelación de Abuso / Acoso / Violencia** | ❌ NUNCA público | ❌ NUNCA público | **Protocolo de Salvaguarda**: Brindar contención y ofrecer canal de ayuda con consentimiento. |

---

## 7. Arquitectura de Memoria de Doble Capa: Servidor Completo vs. Celular Compacto

### 7.1 Filosofía de Diseño: Latencia Cero y Contexto Infinito

En un asistente de voz para adultos mayores, **cada 100 milisegundos de latencia adicional degradan severamente la experiencia**. Si el robot debe descargar cientos de kilobytes de transcripciones previas antes de formular una respuesta, la pausa se percibe como una desconexión o un fallo del sistema.

Para resolver este desafío, Fifo implementa una **Arquitectura de Memoria de Doble Capa**:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SERVIDOR (Cloud / Supabase)                     │
│  Colección: `conversations_full`                                       │
│  • Guarda la transcripción ÍNTEGRA de cada sesión de voz               │
│  • Cada turno de usuario y de Fifo con audio_duration y timestamps    │
│  • Permite análisis longitudinal, auditoría y respaldo total           │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Extracción offline de
                                    │ entidades, temas y resumen
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        CELULAR (On-Device Cache)                       │
│  Colección / StateFlow: `conversation_fragments`                       │
│  • Solo fragmentos livianos (≤150 palabras cada uno)                   │
│  • Temas clave: ["orquídeas", "riego por inmersión", "balcón"]         │
│  • Personas mencionadas: ["Lucía", "abuela Rosa", "nieto Tomás"]       │
│  • Se inyecta una ventana de los últimos 5 fragmentos en el prompt     │
│  ➜ RESULTADO: Latencia mínima (<600 ms) en el 95% de las charlas       │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    │ Si el usuario pregunta por un
                                    │ detalle de nicho no presente:
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│               RECUPERACIÓN BAJO DEMANDA: recall_past_context           │
│  Fifo invoca la herramienta: recall_past_context(query="cazuela ave")  │
│  • Consulta la BD completa del servidor por palabra clave o vector     │
│  • Retorna extracto exacto del secreto de cocina                       │
│  • Fifo responde con precisión sin haber sobrecargado la sesión diaria │
└────────────────────────────────────────────────────────────────────────┘
```

---

### 7.2 Esquema en Servidor: `conversations_full`

| Campo | Tipo | Requerido | Descripción | Ejemplo |
| :--- | :--- | :---: | :--- | :--- |
| `id` | `String` (UUID) | Sí | ID único de la conversación | `"conv_full_20261003_01"` |
| `user_id` | `String` | Sí | Referencia a `users.id` | `"usr_lucia_01"` |
| `started_at` | `Timestamp` | Sí | Inicio de la sesión de voz | `2026-10-03T10:30:00Z` |
| `ended_at` | `Timestamp` | Sí | Fin de la sesión | `2026-10-03T10:34:00Z` |
| `duration_seconds`| `Int` | Sí | Duración total en segundos | `240` |
| `turns` | `List<Turn>` | Sí | Historial secuencial de turnos de diálogo | Ver JSON abajo |
| `full_transcript` | `String` | Sí | Texto completo unificado | `"Lucía: Hola Fifo... Fifo: Hola Lucía..."` |
| `extracted_topics`| `List<String>` | Sí | Temas detectados por el extractor | `["orquídeas", "riego por inmersión"]` |
| `named_entities` | `List<String>` | Sí | Personas, lugares o fechas | `["Lucía", "Fifo", "Balcón"]` |
| `sentiment_trend` | `String` | Sí | Tendencia anímica de la charla | `"positiva_alegre"` |

```json
{
  "id": "conv_full_20261003_01",
  "user_id": "usr_lucia_01",
  "started_at": "2026-10-03T10:30:00Z",
  "duration_seconds": 240,
  "turns": [
    {
      "speaker": "user",
      "text": "Hola Fifo, hoy estuve regando mis orquídeas en el balcón.",
      "timestamp": "2026-10-03T10:30:05Z"
    },
    {
      "speaker": "assistant",
      "text": "Qué lindo, Lucía. Las orquídeas agradecen mucho el cariño. ¿Cómo están esas flores hoy?",
      "timestamp": "2026-10-03T10:30:09Z"
    }
  ],
  "extracted_topics": ["orquídeas", "riego por inmersión", "balcón"],
  "named_entities": ["Lucía", "balcón"],
  "sentiment_trend": "contenta"
}
```

---

### 7.3 Esquema en Celular: `conversation_fragments` (Local)

En el teléfono se guardan únicamente fragmentos sintetizados representados por la data class `ConversationFragment`:

```kotlin
data class ConversationFragment(
    val id: String,
    val serverConversationId: String,
    val keyTopics: List<String>,
    val namedEntities: List<String>,
    val detectedMood: String = "neutral",
    val compactSummary: String, // ≤150 palabras
    val primaryTag: String,
    val durationSeconds: Int = 0,
    val recordedAt: String = "",
    val containsSensitiveHealth: Boolean = false
)
```

#### Ventana de Contexto Compacto Inyectada en Claude (`FifoDataRepository.buildCompactContextWindow`):
```text
=== CONTEXTO PREVIO DEL USUARIO (fragmentos compactos) ===
Nombre: Lucía González | Edad: 68 | Ciudad: Santiago, Chile
Gustos conocidos: Música clásica, Jardinería, Lectura

--- Charla reciente 1 (Jardinería) ---
Temas: orquídeas, riego por inmersión, balcón, maceteros de greda
Personas mencionadas: Lucía, Fifo
Ánimo: contenta
Resumen: Lucía contó que tiene 4 maceteros de orquídeas en su balcón. Las riega por inmersión los miércoles y limpia las hojas con un paño húmedo.

--- Charla reciente 2 (Música) ---
Temas: Chopin, conciertos de piano, nieto Tomás, visita domingo
Personas mencionadas: Lucía, Tomás, Chopin
Ánimo: ilusionada
Resumen: Conversaron sobre la música clásica y los nocturnos de Chopin. Su nieto Tomás la visitará el domingo.

=== Si necesitas más detalle sobre un tema pasado, usa la herramienta recall_past_context ===
```

---

### 7.4 Flujo de Recuperación Dinámica Bajo Demanda

1. **El usuario menciona un tema de nicho antiguo:**  
   *"Fifo, ¿te acuerdas qué condimento me dijiste que le echara a la masa de las empanadas cuando hablamos el mes pasado?"*
2. **Claude evalúa su contexto actual:**  
   En la ventana de fragmentos compactos no aparece la receta de empanadas del mes pasado (solo están los últimos 5 resúmenes).
3. **Claude invoca `recall_past_context`:**  
   ```json
   {
     "name": "recall_past_context",
     "input": {
       "query": "condimento masa empanadas",
       "context_hint": "receta de cocina del mes pasado"
     }
   }
   ```
4. **`ContextRecallSkill` ejecuta la búsqueda:**  
   El skill consulta la base de datos completa del servidor (`searchDeepContext`), recupera los extractos exactos y los retorna sintetizados.
5. **Fifo responde con total precisión y calidez:**  
   *"¡Por supuesto, Lucía! Me acuerdo que en esa charla me contó que su abuela siempre le ponía manteca tibia con una cucharadita de salmuera y un toquecito de pimentón dulce a la masa para que quedara suave y doradita."*
6. **Balance Óptimo:**  
   - 95% de las charlas: Latencia ultra-baja (payload liviano sin overhead).
   - 5% de las charlas que requieren memoria profunda: Recuperación quirúrgica sin alucinaciones ni pérdidas de contexto.

---

## 8. Modo Celular, Rastreo del Robot y Navegación GPS (Maps y Waze)

### 8.1 Modo Celular Independiente (Sin Robot Físico)
Para permitir que el adulto mayor converse con Fifo en cualquier momento (esté fuera de casa, de paseo, en la consulta médica o con el robot guardado):
- La aplicación móvil permite **hablar directamente desde el celular** usando su micrófono nativo (`PhoneMicRecorder` / `NativeSpeechRecognizer`) y parlante (`AndroidTtsSpeaker`).
- En la pantalla de conexión ("Prende a tu Fifo"), el usuario cuenta con el botón directo: **"Hablar desde el celular"**, que activa inmediatamente la interacción de voz completa sin requerir Bluetooth.
- El estado y la continuidad de memoria son compartidos: todas las historias, gustos y recordatorios se guardan en la misma base de datos reactiva.

---

### 8.2 Rastreo del Robot Perdido ("Te perdí", "¿Dónde estás?")
Dado que el microcontrolador ESP32-S3 no cuenta con un receptor GPS autónomo por razones de costo y autonomía de batería, Fifo implementa una **estrategia de localización híbrida inteligente**:

1. **Rastreo por Proximidad Bluetooth (BLE RSSI) + Beeper en el Robot:**
   - Si el robot está encendido y dentro del alcance del teléfono (~10-15 metros):
     - El usuario pregunta por voz: *"Fifo, te perdí, ¿dónde estás?"* o presiona *"Hacer sonar a Fifo"*.
     - Fifo activa la herramienta `find_fifo_device(action="beep")`.
     - El celular envía por BLE el comando `FIND_ME` a la característica `0000ff12-...` del ESP32.
     - El parlante del ESP32 (MAX98357A) emite una melodía alegre en bucle y su pantalla OLED parpadea con el mensaje: **"¡AQUÍ ESTOY!"**.
     - Fifo avisa por voz: *"¡Aquí estoy, Lucía! Estoy muy cerca. Siga el sonido de mi voz."*

2. **Última Ubicación Conocida vía GPS del Celular (Last Known Location):**
   - Cada vez que el robot se conecta o desconecta por Bluetooth, la app captura instantáneamente las coordenadas GPS del celular (`ACCESS_FINE_LOCATION`), realiza geocodificación inversa y almacena el registro en `device_locations`:
     - Fecha y hora: `"Hoy a las 18:30"`
     - Dirección aproximada: `"Av. Providencia 1234, Providencia, Santiago"`
     - Habitación / Zona: `"Cerca del Living / Mesa de noche"`
   - Si el usuario extravió el robot fuera de casa o en otra habitación lejana:
     - Fifo le informa verbalmente dónde estuvieron juntos por última vez.
     - Abre automáticamente un marcador en Google Maps (`geo:lat,lon?q=...`) para guiarlo visualmente hacia el lugar exacto.

---

### 8.3 Módulo GPS y Navegación Paso a Paso (Google Maps y Waze)
Fifo integra el módulo de geolocalización y navegación nativa del teléfono para responder dudas de orientación espacial del adulto mayor:

1. **"Fifo, ¿dónde estamos?" (`get_current_location`):**
   - Lee el GPS en tiempo real y responde con claridad:  
     *"Estamos en Avenida Providencia 1234, en la comuna de Providencia, Santiago. ¿Desea que le indique cómo llegar a algún lugar?"*
2. **"Fifo, ¿cómo llego a mi casa / farmacia / hospital?" (`open_navigation_directions`):**
   - Si el destino es "mi casa", toma la dirección registrada en el perfil del usuario (`preferredAddress`).
   - **Por defecto: Guía 100% Hablada por Voz (Hands-free):** Fifo calcula la distancia, el tiempo caminando y el rumbo cardinal sin obligar al adulto mayor a sacar el celular de su bolsillo ni mirar una pantalla pequeña:  
     *"Para ir a Farmacia Ahumada camine aproximadamente 220 metros hacia el norte, por la vereda derecha. Le tomará unos 3 minutos a paso tranquilo."*
   - **Navegación Visual Opcional:** Si el usuario solicita explícitamente ver el mapa en pantalla (*"Abre Maps en pantalla"* o *"Abre Waze"*), lanza la aplicación correspondiente en segundo plano:
     - Google Maps: `google.navigation:q=[destino]&mode=w`
     - Waze: `waze://?q=[destino]&navigate=yes`

---

## 9. Arquitectura "Alexa para Celular": Telefonía, Escucha Continua y Control de Hardware

> **Objetivo de Accesibilidad:**  
> Transformar el teléfono móvil en un **asistente invisible de fondo**. El adulto mayor no tiene que desbloquear el teléfono, buscar íconos, deslizar paneles ni lidiar con interfaces táctiles complejas. Fifo gestiona las tareas del teléfono por comando de voz con la misma comodidad y naturalidad que un parlante inteligente tipo Amazon Alexa, pero aprovechando los sensores, antenas y conectividad del celular.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                 ARQUITECTURA DE FONDO: "ALEXA EN TU CELULAR"                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  [1. LLAMADAS TELEFÓNICAS MANOS LIBRES]                                     │
│  • FifoPhoneCallReceiver + FifoCallManager (TelephonyManager API 34)        │
│  • Detección inmediata de RINGING + Lookup de Contactos (ContactsContract) │
│  • Anuncio hablado proactivo: "¡Lucía! Le está llamando Carmen (Hija)..."   │
│  • Comandos de voz directos: "Fifo contesta" (altavoz activado) / "cuelga"  │
│                                                                             │
│  [2. MODO ESCUCHA CONTINUA ("Fifo sigue escuchando")]                      │
│  • Estado reactivo: FifoDataRepository.isContinuousListening                │
│  • Mantiene el micrófono abierto tras cada turno conversacional             │
│  • Sin necesidad de repetir la palabra clave "Fifo" en cada frase           │
│  • Ventana de silencio extendida a 120 segundos para pausas tranquilas     │
│  • Desactivación natural: "Fifo descansa" / "ya no escuches"                │
│                                                                             │
│  [3. GUÍA DE NAVEGACIÓN SPOKEN HANDS-FREE]                                  │
│  • Geocoder Android + Location.distanceBetween + Geodesic Bearing           │
│  • Indicaciones verbales: Metros, minutos a paso senior, dirección cardinal │
│  • Cero necesidad de mirar la pantalla o abrir apps pesadas en la calle     │
│                                                                             │
│  [4. CONTROL DE HARDWARE DEL DISPOSITIVO]                                   │
│  • Linterna: CameraManager.setTorchMode (prender / apagar linterna)         │
│  • Volumen: AudioManager.setStreamVolume (subir, bajar, silenciar)          │
│  • Batería: BatteryManager (nivel porcentual y estado de carga)             │
│  • Hora y Fecha: Formato hablado empático en español ("Son las 4 y cuarto") │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 9.1 Manejo de Llamadas Telefónicas (`manage_phone_call`)

- **Detección y Anuncio Proactivo:**
  Cuando el teléfono recibe una llamada entrante, el `FifoCallManager` intercepta el evento `EXTRA_STATE_RINGING`. Si la agenda telefónica contiene el número, obtiene el nombre del contacto (`"Carmen (Hija)"` o `"Dr. Soto"`). Fifo interrumpe suavemente cualquier actividad y anuncia por voz:  
  *"¡Lucía! Le está llamando Carmen (Hija). ¿Desea que conteste o que cuelgue?"*
- **Acciones Disponibles:**
  1. `answer`: Invoca `TelecomManager.acceptRingingCall()`, activa el modo `AudioManager.MODE_IN_COMMUNICATION` y conmuta a `isSpeakerphoneOn = true`. El usuario puede conversar de inmediato al aire sin acercar el teléfono a su oreja.
  2. `hangup`: Invoca `TelecomManager.endCall()` y confirma verbalmente: *"He colgado la llamada."*
  3. `status`: Informa si hay una llamada en curso o sonando.
  4. `call`: Marca automáticamente al contacto solicitado usando la agenda del teléfono.

### 9.2 Modo de Escucha Continua ("Fifo sigue escuchando")

- **Problema que Resuelve:**
  En los asistentes convencionales, el usuario debe repetir "Oye Siri" o "Alexa" antes de cada frase, lo cual resulta agotador y artificial para un adulto mayor en medio de una conversación extensa o una sesión de preguntas sobre su salud o su familia.
- **Funcionamiento:**
  - El usuario dice: *"Fifo, sigue escuchando"*, *"quédate escuchando"* o *"modo conversación"*.
  - El pipeline activa `isContinuousListening = true`.
  - Fifo confirma: *"De acuerdo, me quedo escuchando atentamente. Puedes hablarme directo sin decir mi nombre. Cuando quieras que descanse, solo dime 'Fifo descansa'."*
  - En este modo:
    - Tras cada respuesta de Fifo, el micrófono se re-arma inmediatamente en estado `LISTENING`.
    - No se requiere pronunciar el wake-word "Fifo" para capturar el siguiente turno.
    - El tiempo de auto-suspensión por silencio se amplía de 25 segundos a 120 segundos.
    - Para finalizar la sesión, el usuario simplemente dice: *"Fifo descansa"*, *"ya no escuches"* o *"buenas noches"*.

### 9.3 Control de Hardware del Dispositivo (`control_device_hardware`)

Permite gestionar funciones clave del celular mediante comandos de voz coloquiales:

| Comando Coloquial | Acción de Skill | Componente Android | Respuesta Hablada de Fifo |
| :--- | :--- | :--- | :--- |
| *"Fifo, prende la linterna"* | `toggle_flashlight(true)` | `CameraManager.setTorchMode` | *"He encendido la linterna para iluminarle el camino."* |
| *"Fifo, apaga la linterna"* | `toggle_flashlight(false)`| `CameraManager.setTorchMode` | *"Linterna apagada."* |
| *"Fifo, ¿cuánta batería le queda al celular?"* | `get_battery_status` | `BatteryManager` | *"Al celular le queda un 78% de batería y no está conectado al cargador."* |
| *"Fifo, sube el volumen"* | `set_volume(up)` | `AudioManager.ADJUST_RAISE` | *"Subí un poco el volumen del teléfono."* |
| *"Fifo, volumen al máximo"* | `set_volume(max)` | `AudioManager.FLAG_SHOW_UI` | *"He puesto el volumen al máximo para que me escuche con total claridad."* |
| *"Fifo, ¿qué hora es?"* | `get_current_time` | `java.time.LocalTime` | *"Son las 4 y veinticinco de la tarde de hoy sábado 3 de octubre."* |

---

## 10. Resumen de Herramientas (Skills) Disponibles para el Cerebro IA

A continuación se resume el catálogo unificado de tools registradas en `FifoSkillRegistry`:

1. `update_user_profile`: Modifica nombre, género, ciudad, cumpleaños, biografía en la base de datos.
2. `manage_user_interests`: Agrega o elimina gustos, pasatiempos e intereses positivos.
3. `manage_reminders`: Crea recordatorios hablados para medicamentos o rutinas del hogar.
4. `manage_calendar_events`: Registra visitas médicas, citas familiares o compromisos.
5. `recall_past_context`: Busca recuerdos profundos en la base de datos completa del servidor.
6. `find_fifo_device`: Rastrear el robot perdido mediante sonido ("beep") o coordenadas GPS previas.
7. `get_current_location`: Informa la calle, comuna y ciudad actual por voz.
8. `open_navigation_directions`: Guía de ruta hablada paso a paso o apertura de Maps/Waze.
9. `manage_phone_call`: Anuncia llamadas, contesta en altavoz manos libres o cuelga.
10. `control_device_hardware`: Linterna, volumen, batería y hora del celular.




