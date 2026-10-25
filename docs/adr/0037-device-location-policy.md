# ADR-0037: Transiciones del estado del dispositivo como política pura

**Status:** Accepted

**Date:** 2026-10-24

**Author:** Architecture Review — Device location seam

---

## Context

`FifoDataRepository.updateDeviceConnectionStatus()` y `triggerDeviceBeep()`
mantenían el estado del robot Fifo (`FifoDeviceLocation`) combinando, en el
mismo método, cuatro responsabilidades:

1. leer el reloj y formatear la hora;
2. fusionar los datos nuevos con los últimos conocidos;
3. decidir si el beeper se apaga al desconectarse;
4. persistir en SQLite y sincronizar con el backend.

La fusión y la decisión del beeper no tenían pruebas. Un cambio en el orden de
los `?:` o en la condición `if (!connected)` podía borrar la última ubicación
conocida o dejar el robot sonando tras una desconexión.

---

## Decision

### 1. `DeviceLocationPolicy` es la política pura

Se agrega `data/device/DeviceLocationPolicy.kt`:

```kotlin
internal object DeviceLocationPolicy {
    fun applyConnectionUpdate(
        current: FifoDeviceLocation,
        connected: Boolean,
        nowLabel: String,
        latitude: Double? = null,
        longitude: Double? = null,
        address: String? = null,
        roomHint: String? = null,
        rssi: Int? = null
    ): FifoDeviceLocation

    fun applyBeep(current: FifoDeviceLocation, beeping: Boolean): FifoDeviceLocation
}
```

No conoce SQLite, red, `StateFlow` ni `Date`: la hora entra ya formateada como
etiqueta (`HH:mm`).

### 2. El repositorio conserva reloj, persistencia y red

`FifoDataRepository.updateDeviceConnectionStatus()` sigue formateando la hora,
aplica la transición, actualiza el `StateFlow`, guarda en SQLite y llama a
`patchDeviceLocation`. `triggerDeviceBeep()` aplica la transición y hace la
misma persistencia y sincronización.

### 3. Se preservan las reglas de fusión

- un dato nuevo `null` conserva el último valor conocido (latitud, longitud,
  dirección, habitación y RSSI);
- un RSSI `0` es un valor válido y no se reemplaza por el anterior;
- al conectarse, `lastConnectedTime` pasa a “Conectado ahora”;
- al desconectarse, pasa a “Hoy a las HH:mm” y el beeper se apaga;
- al reconectarse, el beeper conserva el estado que ya tenía;
- activar o apagar el beeper no toca conexión, hora ni ubicación.

La etiqueta “Hoy a las HH:mm” se conserva tal cual, incluso si la desconexión
ocurrió en un día anterior. Cambiarla a una fecha completa es un cambio de
producto aparte.

---

## Files Changed

| File | Change |
|---|---|
| `data/device/DeviceLocationPolicy.kt` | **New** — fusión de conexión/ubicación y transición del beeper |
| `data/FifoDataRepository.kt` | **Updated** — ambos métodos delegan la transición y conservan reloj, SQLite y red |
| `test/.../data/device/DeviceLocationPolicyTest.kt` | **New** — 8 pruebas de conexión, desconexión, beeper, nulos, RSSI cero y estado inicial |
| `data/README.md` | **Updated** — política en el mapa de módulos e invariante |
| `GLOSSARY.md` | **Updated** — estado del dispositivo Fifo |
| `docs/README.md` | **Updated** — índice de ADR |

---

## What Did NOT Change

- No cambia la firma `updateDeviceConnectionStatus(...)` ni
  `triggerDeviceBeep(beeping)`.
- No cambia `FifoDeviceLocation` ni su persistencia en SQLite.
- No cambia la llamada a `patchDeviceLocation` ni sus parámetros: el backend
  sigue recibiendo los valores nuevos (incluidos los `null`), no los fusionados.
- No se toca `FifoDeviceFinderDialog`, `FindFifoDeviceSkill` ni
  `VoicePipelineManager`, que siguen llamando a los mismos métodos.
- No se agregan dependencias.

---

## Consequences

**Positive:**

- La fusión de estado y la regla del beeper tienen pruebas explícitas.
- El repositorio conserva solo la composición: reloj, estado, SQLite y red.
- Un cambio futuro en la política no puede borrar la última ubicación sin que
  una prueba lo detecte.

**Negative / Trade-offs:**

- La etiqueta de hora es textual y no una fecha; el estado no distingue “hoy”
  de días anteriores. Se preserva el comportamiento actual.
- La política duplica el `copy()` de `FifoDeviceLocation`; es deliberado para
  mantener el estado inmutable y testeable.

---

## Related

- [ADR-0002](0002-data-repository-modularization.md) — módulos de dominio.
- [ADR-0031](0031-relational-graph-multi-source.md) — estado y grafo de datos
  locales.
- [ADR-0036](0036-activity-suggestion-policy.md) — otra política pura extraída
  del repositorio.
