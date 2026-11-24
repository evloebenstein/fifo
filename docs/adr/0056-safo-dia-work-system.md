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
| SAFO | `%USERPROFILE%\tools\scrum-around-and-find-out` | main `75cabb7fcea2713a8039f95732eea67541babfd2` |
| DIA | `%USERPROFILE%\tools\department-of-internal-arguments` | v0.4.0 `a9d434794869aaf167df8adc3f351ce27c12532d` |

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
- El `gh` de WSL es un shim (`$HOME/tools/bin/gh`) que exec
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
  protección efectiva de `main` se aplicó y verificó mediante el PR #4; la
  [issue #2](https://github.com/evloebenstein/fifo/issues/2) conserva el
  registro de verificación continua y restante.

### 5. Protección verificada y trabajo gated por aprobación

El GitHub Project Fifo (número 4) ya fue creado con aprobación explícita del
mantenedor; `project.number` quedó registrado en `board.yaml` y se corrieron
`reconcile` y `audit`. La creación de futuros proyectos y cualquier autorización
nueva de scope `project` siguen requiriendo aprobación explícita y fresca del
mantenedor: `gh auth refresh -s project` (interactivo, navegador) y
`safo bootstrap` escriben en GitHub.

El PR #3 mergeó el workflow de CI y su check `CI green`. El PR #4 mergeó
en `main`, en el commit
`0ef98143caed42f1d0ae05ca9fd79138cb976811`, la protección clásica que
exige ese check. La evidencia live observada por el lead mediante REST y
GraphQL, fechada `2026-11-24T02:06:44Z`, confirmó:

- check requerido `CI green`, publicado por la app GitHub Actions (`15368`);
- `strict=true`, pull request obligatorio y `0` aprobaciones obligatorias;
- administradores incluidos (`enforce_admins=true`) y conversaciones
  resueltas exigidas;
- force-push y borrado de rama desactivados, sin actores de bypass;
- cero rulesets en conflicto.

`safo audit` terminó clean y conservó su nota informativa `NO-GATE`: esa
nota no significa que la protección básica sea inexistente. La limitación
residual aceptada es que la protección clásica identifica el check por
nombre y app, no por el archivo exacto del workflow. La
[issue #2](https://github.com/evloebenstein/fifo/issues/2) sigue como
registro de la verificación continua y del trabajo restante, sin presentar
la protección ya aplicada como un pendiente inicial.

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
  SAFO. Con `gate_require_admin_enforced: true`, la puerta se aplica
  también a administradores y no acepta bypass administrativo. El workflow
  tiene cobertura universal para pushes y PRs a `main`: su único job ejecuta
  siempre el gate Android completo con JDK 17, sin filtros de rutas ni
  resultados condicionales. La protección básica de `main` quedó aplicada y
  verificada mediante el PR #4 (commit
  `0ef98143caed42f1d0ae05ca9fd79138cb976811`); la
  [issue #2](https://github.com/evloebenstein/fifo/issues/2) conserva la
  verificación continua y el trabajo restante. La sección 5 registra la
  evidencia live y la limitación residual del identificador del check.
- El GitHub Project Fifo ya está creado (número 4), con aprobación registrada,
  `project.number` en `board.yaml` y `reconcile`/`audit` ejecutados. Los futuros
  proyectos y nuevas autorizaciones de scope requieren aprobación fresca.
