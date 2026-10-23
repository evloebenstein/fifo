# Política de Seguridad de Fifo 🛡️

En el proyecto **Fifo**, la seguridad y la privacidad de nuestros usuarios —especialmente personas mayores y sus familias— son de máxima prioridad.

## Versiones Soportadas

Actualmente mantenemos activamente la rama principal y las siguientes versiones:

| Versión | Soportada          |
| ------- | ------------------ |
| `main`  | :white_check_mark: |
| v1.x    | :white_check_mark: |
| < v1.0  | :x:                |

## Reporte de Vulnerabilidades

Si descubres una posible vulnerabilidad de seguridad o un problema relacionado con:
- Filtración de claves de API (Groq, OpenAI, etc.).
- Privacidad en la captura y transmisión de audio PCM o transcripciones.
- Conexión BLE no autorizada o secuestro de la comunicación ESP32-Android.
- Datos personales almacenados en la base de datos local del dispositivo (`database/`).

**Por favor NO abras un Issue público.**

En su lugar, sigue este procedimiento:

1. **GitHub Security Advisory**: Ve a la pestaña **Security** del repositorio en GitHub y selecciona **"Report a vulnerability"** para crear un reporte privado.
2. Alternativamente, contacta directamente al mantenedor principal del repositorio a través de su perfil de GitHub.

### Información a Incluir en el Reporte

Para acelerar la evaluación y resolución, por favor incluye:
- Descripción clara del fallo o vector de ataque.
- Pasos detallados para reproducir la vulnerabilidad o código de prueba de concepto (PoC).
- Módulos afectados (`android-app`, `esp32-firmware`, `backend` o scripts de base de datos).
- Posible impacto sobre la privacidad del usuario o el hardware.

### Nuestro Compromiso

- Acusaremos recibo de tu reporte en un plazo máximo de **48 horas**.
- Proporcionaremos una estimación de tiempo para el parche y mantendremos la comunicación confidencial hasta que la corrección sea liberada públicamente.
- Daremos el debido crédito al investigador en las notas de la versión (si así lo desea).
