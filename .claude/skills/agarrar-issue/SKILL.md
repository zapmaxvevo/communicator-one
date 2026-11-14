---
name: agarrar-issue
description: Lleva un issue del Project «Communicator One» de punta a punta - lo asigna y pasa a In Progress, lo divide en subissues si tiene varias tareas, trabaja en una rama aparte con el test que reproduce primero y abre el PR con «Closes #N». Usalo cuando alguien diga «agarrá el #N», «hacé el issue N», «trabajemos el #N» o pida resolver un issue o subissue, y cuando el coordinador toma la próxima tarea.
---

# Agarrar un issue

El ciclo está en el `CLAUDE.md` («El ciclo de un issue»). Esto es el orden para hacerlo sin saltear pasos. El repo es
`zapmaxvevo/communicator-one`: pasalo siempre con `-R`, porque el remoto usa un alias de host SSH y `gh` no lo deduce.

## 1. Leerlo y decidir si se divide

`gh api repos/zapmaxvevo/communicator-one/issues/N --jq '"\(.title) [\(.state)]\n\(.body)"'` y sus subissues
(`gh api repos/zapmaxvevo/communicator-one/issues/N/sub_issues --jq '.[] | "#\(.number) \(.title) [\(.state)]"'`).
Se lee con `gh api` porque `gh issue view` falla en versiones viejas de `gh` (consulta los Projects clásicos).

- Si tiene subissues, se trabaja uno: preguntá cuál o proponé el primero abierto (en modo autónomo, tomá el primero
  abierto cuyas dependencias estén cerradas o cuya única dependencia pendiente esté en un PR abierto, P-011).
- Si tiene 2 o más tareas sin tildar (o secciones `### 1.`, `### 2.`), dividilo antes de empezar:
  `python3 scripts/issues.py dividir N` muestra las tareas; juntá las que van en el mismo cambio con `--grupos` y,
  con el OK de quien lo pidió (en modo autónomo, si cada parte es un PR revisable por sí solo), `--hacer`. Seguí con
  un subissue.
- Revisá «Depende de»: si alguno sigue abierto, no lo empieces (salvo PR apilado: P-011 y §3, §5 y §6).
- Verificá la premisa contra el árbol antes de construir: el issue puede estar viejo (otro PR lo resolvió, el código
  cambió). Si ya no se sostiene, el entregable es eso, con la evidencia, en un comentario en el issue.

## 2. Tomarlo

`python3 scripts/issues.py tomar N`: queda asignado a quien corre `gh` y en In Progress (y el padre también).

## 3. Rama aparte

No se trabaja en el árbol de `main`: así varias sesiones o agentes no se pisan. Entrá a un worktree con la herramienta
`EnterWorktree` y renombrá la rama: `git branch -m feature/N-<tema-corto>` (o `fix/N-<tema-corto>` si es `bug`).
Si se apila (P-011), después de renombrar llevá la rama a la del PR de la dependencia:
`git fetch origin && git reset --hard origin/<rama-dependencia>` (el worktree recién creado no tiene nada que perder y
la rama renombrada no queda siguiendo a la dependencia).
En `web/`, corré `npm ci` dentro del worktree antes de testear.

## 4. Resolverlo

Lo del `CLAUDE.md` y `.claude/rules/`: primero el test que reproduce o especifica (TDD), cambios mínimos, suite
completa antes de commitear y commits solo de los archivos propios. El commit que lo resuelve lleva `Closes #N` en el
cuerpo. Si corrés como `coordinador`, este paso es el ciclo de agentes con sus revisores.

Si surge una duda no resuelta en `docs/decisiones.md`, elegí lo más simple y anotala en *Provisorias*. Si algo
bloquea de verdad, comentalo en el issue, agregale la etiqueta `blocked` y pasá a otro.

## 5. PR

Antes de pushear, corré el skill `simplify` sobre el cambio de la rama. Lo que arregla va en su propio commit y, si tocó
código, la suite se vuelve a correr. Así `code-review` revisa el código ya limpio.

```
git push -u origin feature/N-<tema-corto>
gh pr create -R zapmaxvevo/communicator-one --title "<qué cambia, en español e imperativo>" --body "<la plantilla completa>"
```

El cuerpo sigue `.github/pull_request_template.md` (con `--body`, `gh` no la carga sola): `Closes #N`, qué cambia, por
qué y cómo se probó, con lo que falló de la suite aunque sea conocido. Pasá el link del PR. Si es apilado, agregá
`--base <rama-dependencia>` y la descripción arranca con «Apilado sobre #N, mergear después».

## 6. Revisión y merge

- Apenas abierto el PR, lanzá vos la revisión: el skill `code-review` con `<número de PR> --comment`. Corre en un agente
  aparte, que no escribió el PR, y deja los comentarios en las líneas del PR.
- Con lo que encuentra, abrí el código de cada hallazgo y decile a quien pidió el issue qué arreglarías en el PR y qué
  no, con el porqué (en modo autónomo, arreglá lo que sea correctitud y dejá el resto anotado en el PR). Lo que se
  arregla va en su propio commit, y se responde en el PR qué se hizo con cada comentario. Lo que queda para después
  pasa a un issue nuevo. Otra ronda de revisión solo si los arreglos fueron grandes.
- El dueño mergea, o pide que se mergee. Nunca se pushea a `main`. Al mergear, el issue pasa a Done y el hook de
  `.claude/settings.json` corre `cerrar-padres` (si se mergea desde la web, correrlo a mano:
  `python3 scripts/issues.py cerrar-padres`).
- PR apilado (P-011):
  - Si la dependencia recibe commits nuevos, rebaseá el apilado sobre ella y pusheá: si no, el verde del CI queda
    viejo.
  - El repo tiene `delete_branch_on_merge` desactivado: después de mergear la dependencia, cambiá a mano la base del
    apilado a `main` antes de mergearlo. Si no, se mergea en la rama muerta, no llega a `main` y `Closes #N` (que
    solo vale contra la rama por defecto) no se aplica. Cambiar la base no dispara `pull_request` (un push nuevo sí):
    el verde sigue siendo el de la corrida contra la rama de la dependencia, así que hacé un push nuevo (por ejemplo,
    rebase sobre `origin/main`) para que el CI corra contra `main` antes de mergear.
  - Si la dependencia se mergeó con squash o rebase, sacá sus commits viejos antes de mergear:
    `git rebase --onto origin/main <último-commit-viejo-de-la-dependencia>` y `git push --force-with-lease`.

## 7. El worktree se borra solo

`python3 scripts/issues.py limpiar-worktrees --hacer` borra los de `.claude/worktrees` cuya rama tiene el PR mergeado
(y su rama local) o que llevan 24 h quietos (la rama queda). Nunca borra uno con cambios sin commitear ni el de la
sesión que lo corre. Lo corren los hooks de `.claude/settings.json` después de `gh pr merge` y al arrancar cada sesión,
que cubre los merges desde la web. Sin `--hacer` solo muestra qué haría.

Si la sesión sigue adentro del worktree después del merge, salí con `ExitWorktree` y `keep`: la próxima limpieza lo
borra.
