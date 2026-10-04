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

- **Conversación por voz en tiempo real**: Wake-word ("Fifo"), reconocimiento de voz (ASR), motor de diálogo empático con Gemini y síntesis de voz (TTS) fluida en español neutro, depurada de marcadores Markdown o tecnicismos.
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

El adulto mayor interactúa exclusivamente mediante voz con el dispositivo físico Fifo. Fifo opera como agente autónomo con Function Calling (Skills de Claude), ejecutando mutaciones directamente sobre la base de datos (datos demográficos, fecha de nacimiento, gustos, memorias, historias de blog) y disparando herramientas nativas del teléfono (recordatorios de salud, calendario, mapas y llamadas de apoyo). La aplicación Android refleja todos estos cambios en vivo sin que el usuario deba tocar el teléfono.

Para la especificación completa del esquema de base de datos, mapeo de componentes y catálogo de skills, consultar:
👉 **[FIFO_DATABASE_MAP.md](file:///c:/Users/evloe/OneDrive/Escritorio/fifo/FIFO_DATABASE_MAP.md)**

## Strict Privacy, Positive Memory Registry & Safeguarding Policy

- **Registro Positivo por Defecto**: Fifo solo registra de manera autónoma en perfiles e historias: intereses, pasatiempos recreativos, recuerdos afectivos y anécdotas positivas.
- **Información Sensible y Consentimiento Claro**: Temas médicos, diagnósticos, recetas o situaciones familiares delicadas nunca se registran como gustos ni se publican en la comunidad a menos que el usuario exprese un consentimiento explícito, informado y confirmado verbalmente por voz.
- **Tolerancia Cero a Contenidos Sexuales**: Queda terminantemente prohibido el registro, almacenamiento o difusión de cualquier contenido con connotación sexual en perfiles, historias o muros comunitarios.
- **Protocolo de Salvaguarda ante Abuso**: La única excepción a menciones de índole sexual es cuando el usuario exprese haber sufrido o estar sufriendo abuso, violencia o maltrato. En este caso crítico, el contenido jamás se publica públicamente; Fifo brinda contención emocional cálida y, con el consentimiento claro del adulto mayor, facilita la comunicación inmediata con su familiar de confianza o con líneas oficiales de asistencia protegida.

## Dual-Layer Memory Architecture & Zero-Latency Context Recall

- **Capa Servidor (Transcripción Completa)**: La nube almacena la conversación completa con todos los turnos, transcripciones de audio y marcas de tiempo para preservación del patrimonio biográfico del usuario y auditoría.
- **Capa Celular (Fragmentos Livianos On-Device)**: El dispositivo móvil solo mantiene fragmentos compactos (temas clave, entidades nombradas y resúmenes de ≤150 palabras). Esto elimina la latencia de red, garantizando respuestas casi instantáneas en el día a día.
- **Recuperación Dinámica de Nicho (`recall_past_context`)**: Cuando el usuario pregunta por temas específicos pasados o anécdotas antiguas no presentes en la ventana de contexto compacto, Fifo invoca de forma autónoma su skill de búsqueda profunda en la base de datos central para responder con precisión y sin alucinaciones.

## Phone-Only Mode, Lost Device Locator & GPS Navigation

- **Modo Celular Independiente**: Posibilidad de hablar con Fifo directamente a través del micrófono y parlante del teléfono celular sin necesidad de tener el robot físico encendido ni conectado por Bluetooth, manteniendo la misma empatía, memoria y base de datos reactiva.
- **Localizador de Robot Extraviado ("Te perdí", "¿Dónde estás?")**:
  - En rango Bluetooth: Fifo activa una alarma y melodía alegre por el parlante del robot y enciende la pantalla OLED con "¡AQUÍ ESTOY!" para guiar al usuario por el sonido.
  - Fuera de rango: Consulta las últimas coordenadas GPS guardadas por el celular al momento de la desconexión, indica la habitación o dirección aproximada y abre la ubicación exacta en Google Maps.
- **Módulo GPS y Navegación Paso a Paso (Google Maps & Waze)**:
  - Detección de ubicación actual en tiempo real para responder "¿dónde estamos?" con calle y comuna.
  - Navegación guiada por voz paso a paso hacia el domicilio ("mi casa"), farmacias, consultorios, hospitales o parques utilizando Google Maps o Waze.



