# ADR-0056: Sistema de trabajo SAFO + DIA

**Status:** Accepted

**Date:** 2026-11-23

**Author:** Sesión Qoder — adopción pedida por el mantenedor

---

## Context

El trabajo de Fifo lo hacen varias sesiones de agentes (Qoder, OpenCode,
Claude Code, Codex) más el mantenedor. Sin un sistema común aparecieron los
mismos fallos una y otra vez: trabajo empezado sin registro (créditos agotados
a mitad de tarea sin punto de continuación claro), cambios sin revisión
independiente y decisiones que cada sesión tomaba por su cuenta.

El mantenedor pidió adoptar dos herramientas upstream:

- **SAFO** (`scrum-around-and-find-out`): el GitHub Project como código
  (`board.yaml`), enrutado de tareas por forma (`agents.yaml` + `safo route`)
  y la regla de *no hidden work*: toda unidad de trabajo tiene issue y tarjeta
  antes de empezar.
- **DIA** (`department-of-internal-arguments`): roles de agente con
  responsabilidad separada (implementer, reviewer, adversarial-reviewer,
  transcriber, researcher, cloud-engineer, devops-engineer), packs de
  conocimiento por dominio y un bloque *lead* que gobierna la sesión que
  despacha.

Ambas son POSIX-only (`fcntl`, `O_NOFOLLOW`, permisos de confianza) y el
entorno del mantenedor es Windows con WSL (kali) disponible.

## Decision

### 1. Clones pinned fuera del repo

| Herramienta | Ruta | Versión |
| :--- | :--- | :--- |
| SAFO | `C:\Users\evloe\tools\scrum-around-and-find-out` | main `75cabb7fcea2713a8039f95732eea67541babfd2` |
| DIA | `C:\Users\evloe\tools\department-of-internal-arguments` | v0.4.0 `a9d434794869aaf167df8adc3f351ce27c12532d` |

No se forkean ni se modifican: actualizar es `git fetch` + checkout de un tag
o commit nuevo, y re-ejecutar el instalador de DIA. El clon de DIA lleva
`core.autocrlf false`: con CRLF el instalador copia bytes que rompen sus
marcadores de propiedad y toda la instalación aparece como deriva.

### 2. DIA instalado para claude y codex, lead en AGENTS.md

`install.py --target fifo --adapter claude --pack python-untrusted-input
--pack threat-model --pack gha-security-dast --pack asset-production --lead
AGENTS.md --apply`, y lo mismo con `--adapter codex` (sin `--lead`). Packs
elegidos por el dominio real del repo: Python que carga YAML/JSON y llama
APIs (SAFO, tools, backend), threat model del pipeline de voz, CI de GitHub
Actions (ya mergeadas) y assets shipped (renders, STL, iconos).

Los 22 archivos gestionados viven en `.claude/`, `.codex/` y `.agents/skills/`;
Qoder y OpenCode cargan los packs desde `.agents/skills`. El bloque lead
(`<!-- doia-lead:begin … -->`) se añadió a `AGENTS.md` y solo se actualiza con
el instalador. Las plantillas que el lead referencia se copiaron al repo:
`templates/brief.md`, `templates/handoff.md`, `evals/log-schema.md`.

### 3. SAFO corre en WSL, sin pip y sin duplicar secretos

- `python3 -m safo` con `PYTHONPATH` al `src` del clon (PyYAML ya está en la
  kali). El intento de pip install en Windows se revirtió: `guardlog.py`
  importa `fcntl`.
- El `gh` de WSL es un shim (`/home/evl/tools/bin/gh`) que exec
  `gh.exe` de Windows: reutiliza el keyring y no copia tokens.
- `safo route` exige un archivo de confianza POSIX y rechaza `/mnt/c`
  (group-writable). La copia viva es `~/.config/safo/agents.yaml` (0600),
  sincronizada desde el repo tras cada edición.

### 4. board.yaml y agents.yaml de Fifo

En la raíz del repo. Tablero de un solo repo (`evloebenstein/fifo`) con
áreas App / Hardware / Docs / Herramientas, campos Status, Kind, Epic,
Effort, Agent y Sprint, y las reglas de estado por defecto. Equipo:
`qoder`, `opencode`, `claude-code`, `codex`, `human`; formas:
`implementacion`, `hardware`, `android`, `revision`, `investigacion`,
`seguridad`. Todos los perfiles nacen `unmeasured`: ningún rasgo se afirma
sin evidencia medida.

Dos particularidades honestas:

- `kind` solo admite la taxonomía upstream; Qoder y OpenCode se declaran
  `claude` (familia de protocolo) con nota. La etiqueta que sugiere `route`
  para la tarjeta es la del kind; el lead la mapea a la opción real del
  campo Agent.
- `gate_check: "CI green"` y `gate_require_admin_enforced: true`: el check
  de CI ya existe y quedó mergeado mediante el PR #3. La regla de merge de
  SAFO exige que la protección clásica aplique la puerta también a los
  administradores; no se configura `gate_admin_exempt_repos` ni se acepta
  bypass administrativo. El workflow se ejecuta para todo push y pull
  request a `main`, sin filtros de rutas. Su único job, `CI green`, ejecuta
  siempre el gate Android completo con JDK 17:
  `./gradlew compileDebugKotlin testDebugUnitTest assembleDebug`. Cualquier
  fallo de compilación, pruebas o empaquetado deja el job rojo. La
  protección efectiva de `main` se gestiona en la
  [issue #2](https://github.com/evloebenstein/fifo/issues/2).

### 5. Lo que queda gated por aprobación

El GitHub Project no se crea sin aprobación explícita del mantenedor:
`gh auth refresh -s project` (interactivo, navegador) y `safo bootstrap`
escriben en GitHub. Tras bootstrap, `project.number` entra en `board.yaml`
y se corren `reconcile` y `audit`.

El PR #3 ya mergeó el workflow de CI y su check `CI green`; ese hecho no
autoriza aplicar permisos. Activar la protección de `main` cambia estado
compartido y exige aprobación fresca del mantenedor en el momento de
ejecutarla. La política aprobada es estricta: el check debe exigirse también
a administradores y no se admite bypass administrativo. La
[issue #2](https://github.com/evloebenstein/fifo/issues/2) registra ese
trabajo; este ADR y `board.yaml` solo documentan y configuran la regla, sin
sustituir la verificación de los permisos aplicados.

## Alternatives considered

- **pip install de safo en Windows**: descartado; `fcntl` es POSIX-only y
  parchear upstream viola el principio de no forkear.
- **Ejecutar todo en Git Bash**: descartado; no hay `fcntl` ni permisos
  POSIX reales, y la conversión de rutas `/mnt/c` rompe los comandos.
- **Copiar tokens a WSL (`GH_TOKEN`)**: descartado; duplica un secreto y
  las variables ambientales de gh reemplazan el login almacenado. El shim
  reutiliza el keyring sin copiar nada.
- **Instalar DIA solo para claude**: descartado; Codex y Qoder/OpenCode
  también trabajan el repo y el layout codex comparte los mismos packs.

## Consequences

- Cada tarea nace con issue + tarjeta; cada PR enlaza su issue; cada
  despacho lleva brief y cada tarea cerrada una línea en el pilot log.
- El que implementa no revisa; el adversarial va después del reviewer.
- Las sesiones no editan los archivos gestionados por DIA ni el bloque
  lead; actualizar es re-ejecutar el instalador.
- `safo validate` es la puerta offline de `board.yaml`/`agents.yaml`.
- El check `CI green` ya está mergeado (PR #3) y `gate_check` lo exige en
  SAFO. Con `gate_require_admin_enforced: true`, la puerta debe aplicarse
  también a administradores y no acepta bypass administrativo. El workflow
  tiene cobertura universal para pushes y PRs a `main`: su único job ejecuta
  siempre el gate Android completo con JDK 17, sin filtros de rutas ni
  resultados condicionales. Verificar y aplicar la protección de rama sigue
  sujeto a la aprobación fresca descrita en la
  [issue #2](https://github.com/evloebenstein/fifo/issues/2); declarar la
  regla o pasar `CI green` no demuestra que los permisos de GitHub estén
  activos.
- Pendiente con issue propio: bootstrap del Project (aprobación +
  scope `project`).
