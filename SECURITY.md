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

## Datos Biométricos de Voz (ADR-0048)

La identidad de locutor de Fifo atribuye un turno a una voz para **personalizar
el trato**, nunca para autenticar. Aun así, un embedding de voz permite
reconocer a una persona y se trata como **dato biométrico sensible**:

- **Consentimiento explícito y específico.** El enrolamiento solo se ofrece tras
  explicar qué se guarda (un embedding, no audio), para qué se usa, que no se
  sincroniza y cómo se borra. Sin consentimiento no se crea ningún perfil.
- **Solo embeddings, nunca audio crudo.** `SpeakerEnrollment` guarda el centroide
  de 3–5 muestras y descarta las grabaciones. Un `VoiceProfile` no contiene PCM.
- **Almacenamiento cifrado en reposo.** `EncryptedVoiceProfileStore` guarda un
  blob versionado cifrado y autenticado; `AndroidKeystoreProfileCipher` usa
  AES-256-GCM con una clave del Android Keystore que no sale del dispositivo. El
  archivo se reemplaza de forma atómica y `delete`/`clear` lo eliminan.
- **Consentimiento como puerta, no como aviso.** `ConsentAwareVoiceProfileStore`
  no lee perfiles ni permite enrolar sin consentimiento vigente. Retirarlo
  bloquea la lectura y el enrolamiento, pero **nunca** impide borrar.
- **Sin sincronización con el backend.** Los embeddings no se suben. El backend
  actual no tiene autenticación real; enviar biometría sería peor que no tener
  la función.
- **Versionado por modelo.** Cada perfil lleva `modelId`; al cambiar de extractor
  los perfiles viejos se ignoran en vez de comparar vectores incompatibles.
- **La voz no es una llave.** Ninguna llamada, pago, cambio de configuración ni
  dato privado se autoriza solo porque la voz coincida. Esa garantía es una
  invariante de [`AGENTS.md`](AGENTS.md), no una opción de configuración.
- **Nada de biometría sin modelo validado.** `NoopSpeakerIdentityProvider` es el
  valor por defecto. Un proveedor real exige validación con grabaciones, medición
  de EER/FRR/FAR por condición, presupuesto de latencia y un almacén cifrado.

Si encuentras una fuga de perfiles de voz, una sincronización no autorizada de
embeddings o un almacén sin cifrar, repórtalo por el canal privado de arriba.
