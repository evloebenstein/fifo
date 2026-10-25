# ADR-0007: GeoIntentSeam — Decoupling the Geo-Conversational Cluster

**Status:** Accepted  
**Date:** 2026-10-20  
**Author:** Architecture Review Pass 3 — Candidate 01

> **Follow-up (2026-10-24):** the deprecated `instance` / `initSingleton` shim
> described below was removed by [ADR-0038](0038-navigation-manager-injection.md).
> `FifoNavigationManager` is now injected everywhere and has no global state.

---

## Context

The geo-conversational cluster — `NavigationDirectionsSkill`, `MapsSkill`, and `FifoNavigationManager` — directly coupled Android hardware (`android.content.Context`, `LocationManager`, `Geocoder`, `Intent`) into business logic that is conceptually pure:

* "Find the closest OXXO to these coordinates"
* "Am I still on route?"
* "Generate a spoken navigation report"

The key problems were:

1. **`FifoNavigationManager` was an `object` singleton** — it held `appContext: Context?` internally, making its state global, mutable, and untestable without an Android emulator.
2. **Both skills took `Context` as a constructor parameter** — any test involving them required Robolectric or a real device.
3. **Map-launch `Intent` construction lived inside `MapsSkill`** — impossible to assert in JVM tests whether an intent was (or wasn't) fired.
4. **Geocoding, POI search, and external navigation calls** were scattered across callers as direct `FifoLocationHelper` calls, leaking the helper's identity into skill logic.

---

## Decision

Introduce a **`LocationAdapter`** interface as the single hardware port for the entire geo-conversational cluster:

```
LocationAdapter (interface)
├── AndroidLocationAdapter   ← production: delegates to FifoLocationHelper
└── InMemoryLocationAdapter  ← test double: pure JVM, zero Android imports
```

Convert `FifoNavigationManager` from a **global `object`** to an **injected `class`**:

```kotlin
class FifoNavigationManager(
    private val locationAdapter: LocationAdapter,
    private val locationManagerProvider: () -> LocationManager? = { null }
)
```

A **deprecated singleton shim** (`FifoNavigationManager.instance` / `initSingleton`) bridges all legacy callers during migration without breaking any existing runtime behaviour.

Refactor `MapsSkill` and `NavigationDirectionsSkill` to receive `LocationAdapter` + `FifoNavigationManager` via constructor, with an `intentLauncher: ((Intent) -> Unit)?` escape hatch in `MapsSkill` to intercept map launches in tests.

Wire everything in `FifoSkillRegistry`:

```kotlin
val locationAdapter = AndroidLocationAdapter(context)
val navigationManager = FifoNavigationManager(
    locationAdapter = locationAdapter,
    locationManagerProvider = { context.getSystemService(LOCATION_SERVICE) as? LocationManager }
)
FifoNavigationManager.initSingleton(navigationManager) // shim for legacy callers
```

---

## Files Changed

| File | Change |
|---|---|
| `location/LocationAdapter.kt` | **New** — hardware port interface |
| `location/AndroidLocationAdapter.kt` | **New** — production implementation |
| `location/InMemoryLocationAdapter.kt` | **New** — pure-JVM test double |
| `location/FifoNavigationManager.kt` | **Refactored** — `object` → `class`, removed `appContext` |
| `skills/MapsSkill.kt` | **Refactored** — `Context` → `LocationAdapter` + `intentLauncher` |
| `skills/NavigationDirectionsSkill.kt` | **Refactored** — `Context` → `LocationAdapter` + `FifoNavigationManager` |
| `skills/FifoSkillRegistry.kt` | **Updated** — builds and wires seam at startup |
| `skills/VoiceIntentRouter.kt` | **Updated** — uses `instance` shim |
| `pipeline/session/SpeechCognitionSeam.kt` | **Updated** — uses `instance` shim |
| `network/CloudApiClient.kt` | **Updated** — uses `instance` shim |
| `pipeline/VoicePipelineManager.kt` | **Updated** — wires `onAutonomousGuidance` via shim |
| `MainActivity.kt` | **Updated** — removed stale `FifoNavigationManager.init(context)` |
| `skills/GeoIntentSeamTest.kt` | **New** — 13 pure-JVM tests |

---

## What Did NOT Change

* **`FifoLocationHelper`** — unchanged; `AndroidLocationAdapter` wraps it.
* **Database / memory layer** (`FifoDataRepository`, backend enrichment, `ConversationContextProvider`) — completely untouched. Location history, past places, and narrative memory continue to flow through the prompt enrichment pipeline as before.
* **`FifoOrientationHelper`** — compass sensor helper remains a standalone singleton; it is read-only and has no `Context` side-effects on the navigation state machine.
* **Runtime behaviour** — no user-facing feature changed. The singleton shim ensures all existing call-sites compile and behave identically.

---

## Consequences

**Positive:**
- Navigation state machine (`FifoNavigationManager`) is fully testable in JVM without emulator or Robolectric.
- `MapsSkill` and `NavigationDirectionsSkill` can be exercised in pure unit tests with any synthetic GPS scenario.
- `LocationAdapter` is now the single authorised boundary for geo-hardware; future callers cannot accidentally bypass it.
- The `intentLauncher` escape hatch documents exactly one place where an `Intent` is fired, making accidental background-launched maps impossible.

**Negative / Trade-offs:**
- The deprecated `instance` shim adds temporary surface area. It must be removed once `VoiceIntentRouter`, `SpeechCognitionSeam`, and `CloudApiClient` are also migrated to injected dependencies.
- `FifoSkillRegistry` now has a hard dependency order: `locationAdapter` and `navigationManager` must be constructed before skills are registered.

---

## Migration Path for Shim Removal

1. Inject `FifoNavigationManager` into `VoiceIntentRouter` constructor.
2. Pass `FifoNavigationManager` through `ConversationContextProvider` to `SpeechCognitionSeam` and `CloudApiClient`.
3. Delete `instance`, `initSingleton`, and the `@Deprecated` annotations from `FifoNavigationManager.Companion`.

**Completed in [ADR-0038](0038-navigation-manager-injection.md) (2026-10-24).**
