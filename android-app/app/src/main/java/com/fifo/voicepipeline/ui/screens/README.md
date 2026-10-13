# 🖥️ Main Screens (`com.fifo.voicepipeline.ui.screens`)

Vistas y pantallas completas de la aplicación Android.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`HomeScreen.kt`](HomeScreen.kt) | **Pantalla Principal (Inicio)**: La pantalla neurálgica de la app. Muestra la carita animada interactiva de Fifo en grande, el estado de conexión del robot (BLE/Teléfono), recordatorios inmediatos, sugerencias de conversación del día y acceso rápido al micrófono. |
| [`ProfileScreen.kt`](ProfileScreen.kt) | **Pantalla de Perfil y Memoria Completa**: Vista extensa (~115 KB) que permite consultar y editar datos personales, biografía redactada por IA, **recuerdos importantes** organizados por categoría, **historias de gustos en detalle**, configuración de claves de API (Claude/Groq/OpenAI), contacto de emergencia y selector de cambio de perfil. |
| [`FriendsScreen.kt`](FriendsScreen.kt) | **Red Social "Fifo Amigos"**: Muestra perfiles de otros adultos mayores compatibles basados en sus `interest_tags`, edad y ciudad, con historias compartidas y sugerencias para romper el hielo. |
| [`ActivitiesScreen.kt`](ActivitiesScreen.kt) | **Pantalla de Actividades y Bienestar**: Sugiere rutinas diarias para personas mayores: caminatas, ejercicios de memoria, adivinanzas, llamadas a seres queridos y ejercicios de respiración guiada. |
| [`OnboardingScreen.kt`](OnboardingScreen.kt) | **Asistente de Configuración Inicial**: Asistente paso a paso para nuevos usuarios que recopila de manera cálida el nombre, fecha de nacimiento, gustos principales, música favorita y contacto de emergencia. |
| [`LoginScreen.kt`](LoginScreen.kt) | **Pantalla de Acceso**: Permite seleccionar perfiles guardados o iniciar sesión con Google. |
| [`WelcomeScreen.kt`](WelcomeScreen.kt) | **Pantalla de Bienvenida**: Saludo visual introductorio para primeras instalaciones. |

---

## 🧭 Reglas de Programación en `ui/screens/`
1. **Consumo de Estados:** Consumir siempre los flujos desde `FifoDataRepository` usando `collectAsState()` para que la pantalla reaccione en tiempo real a mutaciones realizadas por voz o por base de datos.
2. **Scroll Accesible:** Las listas de tarjetas (`LazyColumn`) deben tener espaciados cómodos (`verticalArrangement = Arrangement.spacedBy(16.dp)`) y botones con etiquetas descriptivas.
