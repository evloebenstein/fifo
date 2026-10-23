# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Jetpack Compose (Kotlin), Android Material 3, ESP-IDF / Arduino C++ (ESP32-S3 BLE Audio UCC, SSD1306 OLED, I2S INMP441 + MAX98357A)

## Users

Adultos mayores (personas de la tercera edad, 65+ años) en Chile y Latinoamérica que viven solas o buscan mantenerse activas, saludables y acompañadas. Usuarios con diversos niveles de visión (presbicia), motricidad (temblores leves o artritis) y familiaridad tecnológica.

## Product Purpose

Fifo es un robot compañero físico y aplicación móvil de bienestar y envejecimiento activo. Su propósito es acompañar con calidez, combatir el aislamiento no deseado y ayudar al adulto mayor a redescubrir, adaptar o iniciar pasatiempos y rutinas saludables a través de una interacción por voz empática, paciente y conversacional guiada por "Las 4 Preguntas Británicas" para el bienestar activo.

## Positioning

A diferencia de los asistentes virtuales fríos y utilitarios (Alexa, Siri, Google Assistant) o las aplicaciones complejas de productividad, Fifo es una presencia física tangible y afectuosa con un rostro animado expresivo y amigable. No juzga, no apura, habla de "usted" con cariño y respeto, no utiliza términos técnicos en inglés ni jerga confusa, y se adapta al ritmo de vida pausado del usuario.

## Operating Context

El hogar: la mesa de noche, el velador, la cocina o el comedor. El usuario puede interactuar directamente hablando con el robot físico Fifo colocado cerca de él o mediante la aplicación complementaria Android en su teléfono. La interfaz debe ser visible a distancia de brazo, sin sobrecarga visual, con elementos táctiles grandes y estables.

## Capabilities and Constraints

- **Conversación por voz en tiempo real**: Wake-word ("Fifo"), reconocimiento de voz (ASR), motor de diálogo empático sobre Groq LPU (`qwen/qwen3.8-27b`) y síntesis de voz (TTS) fluida en español neutro, depurada de marcadores Markdown o tecnicismos.
- **Un solo pipeline activo:** `VoicePipelineModeSelector` elige `SESSION` o `LEGACY` según `VOICE_SESSION_ENABLED` y la disponibilidad de STT de nube; nunca se construyen ambos. En release, `VoiceSessionPipeline` compone la sesión, la captura, la telefonía y la memoria durable.
- **Rostro robot reactivo**: Expresión facial vectorial sincronizada en pantalla OLED (ESP32) y en la app Android (FifoFace) con parpadeo orgánico, apertura de boca modulada por volumen RMS y estados claros (escuchando, pensando, hablando, durmiendo).
- **Control de privacidad visible e intuitivo**: Botón de silenciamiento de micrófono de acceso directo con señalética inequívoca (rojo suave y texto explicativo) tanto en la app como en el robot.
- **Conectividad BLE Audio UCC**: Transmisión de voz bidireccional optimizada sobre Bluetooth Low Energy entre el ESP32-S3 y la app Android.
- **Herramientas de bienestar**: Ejercicios de respiración guiada ("Baja el ritmo"), recordatorios diarios y registro amigable de conversaciones.

## Brand Commitments

- **Identidad**: "Fifo" / "Tu Amigo Fifo".
- **Voz y Tono**: Empático, paciente, motivador, siempre tratando de "usted", celebrando pequeños logros cotidianos y transmitiendo que nunca es tarde para aprender o disfrutar.
- **Lenguaje**: Cero jerga tecnológica. En lugar de "Buffer overflow" o "BLE Packet loss", comunicar con serenidad "Reconectando con Fifo..." o "Habla cuando quieras".
- **Estética Visual**: Paleta cálida y reconfortante basada en Azul Marino profundo (`#141A2D`), Celeste Suave de confianza (`#DCEBFA`, `#C7E4F9`), Fondos limpios y descansados (`#F6F8FB`), y acentos celestes y esmeralda.

## Evidence on Hand

- Diseños oficiales de Figma para todas las pantallas principales (`HomeScreen`, `VoiceInteractionSheet`, `ActivitiesScreen`, `FriendsScreen`, `ProfileScreen`, `OnboardingScreen`).
- Firmware C++ para ESP32-S3 en `esp32-firmware/` con soporte de pantalla OLED SSD1306 I2C y audio I2S.
- Pipeline de voz completo en `android-app/app/src/main/java/com/fifo/voicepipeline/`.
- Sanitizador de texto natural `TextSanitizer.kt` para garantizar pronunciación limpia y humana.

## Product Principles

1. **Dignidad, respeto y paciencia ante todo**: El adulto mayor es el protagonista. Nunca interrumpir bruscamente ni apresurar sus pausas al hablar; escuchar con paciencia y responder siempre con cortesía y afecto.
2. **Accesibilidad sin concesiones**: Cada elemento interactivo debe tener un área táctil mínima de 48×48 dp (56 dp para acciones primarias), tipografía en unidades `sp` legibles con excelente contraste (WCAG AAA para textos clave), y espaciados generosos que eviten toques accidentales.
3. **Cero sobrecarga cognitiva**: Interfaces limpias y directas donde una acción principal destaque claramente sobre las secundarias. Jerarquía visual nítida sin adornos superfluos ni texto sobrecargado.
4. **Calma, previsibilidad y calidez**: Animaciones suaves, transiciones fluidas a ritmos naturales y retroalimentación clara y tranquilizadora en cada interacción.
5. **Privacidad visible y tangible**: La persona usuaria siempre debe saber con certeza si Fifo la está escuchando o si el micrófono está apagado, con controles de un solo toque para silenciar.

## Accessibility & Inclusion

- Objetivos táctiles mínimos de 48×48 dp y 56 dp para botones de voz y acciones principales.
- Relación de contraste de texto superior a 4.5:1 para cuerpo y 3:1 para titulares grandes contra cualquier fondo.
- Compatibilidad plena con configuraciones de accesibilidad del sistema operativo: tamaño de fuente aumentado, contraste alto y lectores de pantalla (TalkBack con `contentDescription` descriptivos en español).
- Alternativas sensoriales: información crítica transmitida visualmente (color, icono, texto explícito) y por voz de forma complementaria.

## Autonomous Operation & Real-Time Sync (Cero Intervención Manual)

El adulto mayor interactúa exclusivamente mediante voz con el dispositivo físico Fifo. Fifo opera como agente autónomo con Function Calling sobre Groq LPU (`qwen/qwen3.8-27b`), ejecutando mutaciones directamente sobre la base de datos (datos demográficos, fecha de nacimiento, gustos, memorias, historias de blog) y disparando herramientas nativas del teléfono (recordatorios de salud, calendario, mapas y llamadas de apoyo). La aplicación Android refleja todos estos cambios en vivo sin que el usuario deba tocar el teléfono.

Para la especificación completa del esquema de base de datos, mapeo de componentes y catálogo de skills, consultar:
👉 **[FIFO_DATABASE_MAP.md](FIFO_DATABASE_MAP.md)**

## Strict Privacy, Positive Memory Registry & Safeguarding Policy

- **Registro Positivo por Defecto**: Fifo solo registra de manera autónoma en perfiles e historias: intereses, pasatiempos recreativos, recuerdos afectivos y anécdotas positivas.
- **Información Sensible y Consentimiento Claro**: Temas médicos, diagnósticos, recetas o situaciones familiares delicadas nunca se registran como gustos ni se publican en la comunidad a menos que el usuario exprese un consentimiento explícito, informado y confirmado verbalmente por voz.
- **Tolerancia Cero a Contenidos Sexuales**: Queda terminantemente prohibido el registro, almacenamiento o difusión de cualquier contenido con connotación sexual en perfiles, historias o muros comunitarios.
- **Protocolo de Salvaguarda ante Abuso**: La única excepción a menciones de índole sexual es cuando el usuario exprese haber sufrido o estar sufriendo abuso, violencia o maltrato. En este caso crítico, el contenido jamás se publica públicamente; Fifo brinda contención emocional cálida y, con el consentimiento claro del adulto mayor, facilita la comunicación inmediata con su familiar de confianza o con líneas oficiales de asistencia protegida.

## Durable Memory, Zero-Latency Context & Conversational Repair

- **Diario durable antes de hablar:** cada turno (usuario + Fifo) se guarda en `conversation_journal` (SQLite v7) antes de la locución. Si la app se cierra después de que Fifo habló, el turno no se pierde.
- **Cola de consolidación persistente:** si la consolidación falla, el turno queda `PENDING` en disco y se reintenta con backoff exponencial (30 s a 15 min), incluso tras reiniciar la app. Solo pasa a `CONSOLIDATED` cuando el consolidador devuelve `true`.
- **Capa Servidor (Transcripción Completa y Motor de Patrones):** la nube conserva las conversaciones consolidadas y usa el endpoint `analyze-and-consolidate` para conectar personas mencionadas, sintetizar biografías (`bio_ai`) y categorizar `interest_tags`.
- **Capa Celular (Memoria Viva On-Device):** tags de intereses, fragmentos cortos de gustos, recuerdos activos, recordatorios pendientes y los últimos turnos restaurados desde el diario (4 turnos / 8 mensajes) para responder sin latencia de red.
- **Recuperación Dinámica de Nicho (`recall_past_context`):** cuando el usuario pregunta por un tema antiguo fuera de la memoria viva, Fifo consulta la base de datos central bajo demanda.
- **Reparación conversacional ±1 turno:** una corrección explícita (“te equivocaste”, “eso está mal”, “no era…”) se reconoce sin wake word y se locuta si el turno anterior tiene ~10 s o menos. Si llega tarde, se guarda en diario/OLED sin interrumpir. Un “No” aislado solo cuenta como corrección si el turno anterior quedó marcado `needsRepair`.
- **La corrección se aprende:** si un turno fue reparado, la consolidación usa la respuesta corregida (`repairText`), no la versión errónea que alcanzó a escucharse.
- **Mutación Autónoma en Tiempo Real:** Fifo puede modificar recordatorios directamente en la app (programar, completar, eliminar o listar), añadir historias de gustos en detalle (`add_taste_story`) y registrar recuerdos afectivos al instante.

## Fifo Amigos: Red Comunitaria & Matchmaking por Afinidad de Tags

- **Conexión Intergeneracional y entre Pares**: Con el consentimiento del usuario, sus `interest_tags` (obtenidos orgánicamente de sus charlas) y su biografía enriquecida se utilizan para calcular afinidad con otros miembros de la comunidad (`GET /users/{id}/fifo-friends/recommendations`).
- **Sugerencias de Diálogo Cálidas**: Para reducir la timidez y fomentar la conversación, el sistema propone temas compartidos ("Podrían charlar sobre el cultivo de orquídeas o recetas tradicionales") y recomienda publicaciones afines en el muro social.

## Phone-Only Mode, Lost Device Locator & GPS Navigation

- **Modo Celular Independiente**: Posibilidad de hablar con Fifo directamente a través del micrófono y parlante del teléfono celular sin necesidad de tener el robot físico encendido ni conectado por Bluetooth, manteniendo la misma empatía, memoria y base de datos reactiva.
- **Localizador de Robot Extraviado ("Te perdí", "¿Dónde estás?")**:
  - En rango Bluetooth: Fifo activa una alarma y melodía alegre por el parlante del robot y enciende la pantalla OLED con "¡AQUÍ ESTOY!" para guiar al usuario por el sonido.
  - Fuera de rango: Consulta las últimas coordenadas GPS guardadas por el celular al momento de la desconexión, indica la habitación o dirección aproximada y abre la ubicación exacta en Google Maps.
- **Módulo GPS y Navegación Paso a Paso (Google Maps & Waze)**:
  - Detección de ubicación actual en tiempo real para responder "¿dónde estamos?" con calle y comuna.
  - Navegación guiada paso a paso hacia el domicilio ("mi casa"), farmacias, consultorios, hospitales o parques utilizando Google Maps o Waze.

## Alexa-Style Invisible Phone Architecture: Hands-Free Telephony & Hardware Control

- **El Celular como Cerebro Invisible**: El adulto mayor nunca necesita desbloquear la pantalla, navegar menús ni abrir aplicaciones complejas. Fifo gestiona las tareas del teléfono por voz como un parlante inteligente Alexa pero con la movilidad y sensores del celular.
- **Gestión Proactiva de Llamadas Telefónicas y Captura Sellada**:
  - Fifo anuncia en voz alta y clara quién llama consultando primero el nombre que ya conoce el teléfono (`CallerContextProvider.local()`) y enriqueciendo en paralelo contra el backend (~800 ms).
  - El anuncio usa nombre y parentesco; no lee temas recientes ni notas contextuales. Un caller ID con nombre pero sin registro sigue siendo desconocido y no se le inventa parentesco.
  - `CaptureArbiter` decide la fuente de captura: durante una llamada la ruta es `PHONE_CALL`, nunca `SpeechRecognizer`. Timbrado y llamada activa no reconfiguran el micrófono.
  - Comandos completos de voz: *“Fifo contesta”* durante el timbrado y *“Fifo cuelga”* durante la llamada activa. El escudo de eco se evalúa antes de la acción, así que el propio anuncio no puede contestar ni colgar.
- **Modo de Escucha Continua ("Fifo sigue escuchando")**:
  - Permite encadenar preguntas o conversar libremente sin tener que repetir el comando de activación "Fifo" en cada frase.
  - Margen de silencio extendido a 120 segundos para respetar las pausas naturales al hablar del adulto mayor.
  - Finalización con comandos amables: *"Fifo, descansa"* o *"ya no escuches"*.
- **Guía de Navegación 100% Hablada (Copiloto Peatonal en Tiempo Real)**:
  - Instrucciones verbales de orientación espacial y conciencia ambiental (*"¿Dónde estoy?", "¿Qué calle es esta?"*).
  - Acompañamiento en tiempo real con sensor giroscópico y brújula magnética integrada (`FifoOrientationHelper`), alertando sobre giros relativos (*"gire a su izquierda"*, *"continúe hacia el norte"*, *"le faltan 2 cuadras"*).
  - Búsqueda directa y autónoma de locales esenciales cercanos (Oxxo, farmacias, supermercados, panaderías) con cálculo de distancia y apertura inteligente de mapas si se solicita.
- **Control de Hardware del Teléfono por Voz**:
  - Linterna para emergencias y noche (*"Fifo, prende la linterna"* / *"apaga la linterna"*).
  - Consulta de batería (*"Fifo, ¿cuánta batería le queda al celular?"*).
  - Ajuste de volumen y consulta de hora en lenguaje natural.

## Conversational Voice Interaction & Barge-In Policy

- **Activación Estricta por Wake Word ("Fifo")**:
  - Salvo que Fifo formule una pregunta directa al usuario (`expectFollowUpQuestion`), que esté activo el modo de escucha continua, o que se use el botón push-to-talk, Fifo requiere estrictamente la palabra clave *"Fifo"* para procesar y responder.
  - Esto evita que Fifo responda a conversaciones ajenas en la habitación, ruido ambiente o llamadas telefónicas en curso donde el usuario habla con un tercero.
- **Barge-In Inteligente Basado en Contenido (Cero Cortes Accidentales)**:
  - Fifo no corta su locución ante ruidos ambientales fuertes (RMS), murmullos ni personas hablando en segundo plano.
  - La voz de Fifo solo se detiene si:
    1. El usuario dice una orden explícita de silencio (*"Fifo silencio"*, *"Fifo cállate"*, *"Fifo para"*, *"silencio"*, etc.).
    2. El usuario interrumpe con una nueva pregunta o instrucción directa que comience con *"Fifo ..."*.

## Multi-Profile Ecosystem, Google Sign-In & Zero-Friction Onboarding

- **Inicio de Sesión con Google ("Un Toque")**:
  - Diseñado específicamente para que adultos mayores o familiares ingresen sin recordar contraseñas alfanuméricas complejas.
  - Botón accesible de alto contraste con icono oficial de Google y tipografía grande (18 sp, área táctil de 56 dp) presente en la pantalla de bienvenida (`WelcomeScreen`), inicio de sesión (`LoginScreen`) y configuración inicial (`OnboardingScreen`).
  - Creación y aprovisionamiento automático en la base de datos relacional MySQL (`fifo_db`) con biografía inicial generada por IA, gustos iniciales y memoria de bienvenida.
- **Creación Dinámica de Perfiles en Base de Datos**:
  - Capacidad de crear múltiples cuentas y perfiles en la base de datos MySQL mediante el endpoint `POST /users` o el asistente guiado por voz/formulario de Onboarding.
  - Cada perfil cuenta con su propio historial de conversaciones, memorias afectivas, lista de gustos detallados y recordatorios independientes.
- **Selector y Conmutación de Perfiles en Vivo**:
  - `LoginScreen` incluye un carrusel táctil horizontal que consulta en tiempo real los perfiles existentes en la base de datos (`GET /users`).
  - Permite cambiar de perfil con un solo toque (`switchUserProfile`), sincronizando instantáneamente la memoria local de SQLite y el contexto del cerebro de Fifo con los datos del nuevo usuario.
