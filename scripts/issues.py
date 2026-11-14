"""Issues, subissues y estados del Project de GitHub, con gh.

  python3 scripts/issues.py tomar N             asigna a quien corre y pasa a In Progress (y al padre)
  python3 scripts/issues.py dividir N [--grupos "1,2;3"] [--hacer]
                                                cada tarea sin tildar pasa a un subissue; sin --hacer solo muestra
  python3 scripts/issues.py cerrar-padres       cierra los padres con todos sus subissues cerrados y sin tareas en
                                                el cuerpo (avisa los que quedan abiertos por eso)
  python3 scripts/issues.py limpiar-worktrees [--hacer]
                                                borra los de .claude/worktrees con PR mergeado o 24 h quietos;
                                                sin --hacer solo muestra
  python3 scripts/issues.py prs-abiertos [--hook]
                                                lista los PRs abiertos; con --hook, para el arranque de sesion
  python3 scripts/issues.py plantilla NOMBRE    el cuerpo de un issue del formulario .github/ISSUE_TEMPLATE/NOMBRE.yml,
                                                para crearlo por comando igual que desde la web

El repo sale de GITHUB_REPO o del remoto `origin`; el Project, de GITHUB_PROJECT (titulo, por defecto
«Communicator One»). Lo demas lo hace el tablero solo: un issue cerrado pasa a Done, un subissue se suma al tablero,
y un PR o commit en main con «Closes #N» cierra el issue.
"""
import argparse
import functools
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

PROJECT_TITLE = os.environ.get("GITHUB_PROJECT", "Communicator One")
IN_PROGRESS = "in progress"
SUBISSUES_NOTICE = "Las tareas pendientes están en los subissues: un PR o commit por subissue."
INACTIVE_SECONDS = 24 * 3600
ISSUE_FORMS = ".github/ISSUE_TEMPLATE"


def gh(*args, stdin=None, attempts=3):
    """Reintenta los cortes de red; un POST que crea se llama con attempts=1 para no duplicar."""
    for i in range(attempts):
        try:
            r = subprocess.run(["gh", *args], input=stdin, capture_output=True, text=True, encoding="utf-8")
        except FileNotFoundError:
            sys.exit("No está instalado `gh` (GitHub CLI). Instalalo y corré `gh auth login -s project`.")
        if r.returncode == 0:
            return r.stdout
        if i + 1 < attempts and re.search(r"connect|timeout|connection", r.stderr, re.I):
            time.sleep(3)
            continue
        sys.exit(f"gh {' '.join(args[:3])}: {r.stderr.strip()}\nVolvé a correr el mismo comando: retoma sin duplicar.")


def graphql(query, **variables):
    args = ["api", "graphql", "-f", f"query={query}"]
    for k, v in variables.items():
        args += ["-F" if isinstance(v, int) else "-f", f"{k}={v}"]
    return json.loads(gh(*args))["data"]


@functools.lru_cache(maxsize=None)
def repo():
    configured = os.environ.get("GITHUB_REPO")
    if configured:
        return configured
    url = _git("remote", "get-url", "origin").stdout.strip()
    m = re.search(r"[:/]([^/:]+/[^/]+?)(?:\.git)?$", url)
    if not m:
        sys.exit("No pude sacar el repo del remoto `origin`: definí GITHUB_REPO=dueño/repo")
    return m.group(1)


def owner_and_name():
    owner, name = repo().split("/", 1)
    return owner, name


@functools.lru_cache(maxsize=None)
def project():
    """(project_id, status_field_id, in_progress_option_id) del Project con titulo PROJECT_TITLE."""
    owner, _ = owner_and_name()
    projects_fragment = (
        "projectsV2(first:50){nodes{id title fields(first:50){nodes{"
        "... on ProjectV2SingleSelectField{id name options{id name}}}}}}"
    )
    data = graphql(
        "query($o:String!){repositoryOwner(login:$o){"
        f"... on User{{{projects_fragment}}} ... on Organization{{{projects_fragment}}}}}}}",
        o=owner)
    nodes = (data["repositoryOwner"] or {}).get("projectsV2", {}).get("nodes", [])
    found = next((p for p in nodes if p["title"] == PROJECT_TITLE), None)
    if not found:
        sys.exit(f"No encontré el Project «{PROJECT_TITLE}» de {owner} (definí GITHUB_PROJECT con el título).")
    status = next((f for f in found["fields"]["nodes"] if f and f.get("name") == "Status"), None)
    option = status and next((o for o in status["options"] if o["name"].lower() == IN_PROGRESS), None)
    if not option:
        sys.exit(f"El Project «{PROJECT_TITLE}» no tiene el campo Status con la opción «In Progress».")
    return found["id"], status["id"], option["id"]


def issue(n):
    return json.loads(gh("api", f"repos/{repo()}/issues/{n}"))


def parent_of(n):
    owner, name = owner_and_name()
    d = graphql(
        "query($o:String!,$r:String!,$n:Int!){repository(owner:$o,name:$r){issue(number:$n){parent{number}}}}",
        o=owner, r=name, n=n)
    p = d["repository"]["issue"]["parent"]
    return p and p["number"]


def move_to_in_progress(n):
    project_id, status_field, in_progress = project()
    node_id = issue(n)["node_id"]
    item = graphql(
        "mutation($p:ID!,$c:ID!){addProjectV2ItemById(input:{projectId:$p,contentId:$c}){item{id}}}",
        p=project_id, c=node_id)["addProjectV2ItemById"]["item"]["id"]
    graphql(
        "mutation($p:ID!,$i:ID!,$f:ID!,$o:String!){updateProjectV2ItemFieldValue(input:"
        "{projectId:$p,itemId:$i,fieldId:$f,value:{singleSelectOptionId:$o}}){projectV2Item{id}}}",
        p=project_id, i=item, f=status_field, o=in_progress)


def take(n):
    gh("issue", "edit", str(n), "-R", repo(), "--add-assignee", "@me")
    move_to_in_progress(n)
    print(f"#{n}: asignado y en In Progress")
    p = parent_of(n)
    if p:
        move_to_in_progress(p)
        print(f"#{p} (padre): en In Progress")


def sections(lines):
    """Tareas como secciones `### N. título`, hasta el próximo título."""
    out, i = [], 0
    while i < len(lines):
        m = re.match(r"###\s+\d+[.)]\s+(.*)", lines[i])
        if not m:
            i += 1
            continue
        j = i + 1
        while j < len(lines) and not re.match(r"#{1,3}\s", lines[j]):
            j += 1
        following = j
        while j > i + 1 and not lines[j - 1].strip():
            j -= 1
        out.append(([(i, j)], m.group(1).replace("**", "").strip(), "\n".join(lines[i + 1:j]).strip()))
        i = following
    return out


def tasks(body):
    """Tareas `- [ ] ...` con su detalle: [(rangos de líneas, título, detalle)]. Sin ninguna casilla (ni tildada),
    las secciones `### N. título`, también las que `dividir --grupos` dejó en el padre; con casillas, un título así
    es texto."""
    lines = body.splitlines()
    if not any(re.match(r"- \[[ xX]\] ", line) for line in lines):
        return sections(lines)
    out, i = [], 0
    while i < len(lines):
        m = re.match(r"- \[ \] (.*)", lines[i])
        if not m:
            i += 1
            continue
        j = i + 1
        while j < len(lines) and (lines[j].startswith("  ") or not lines[j].strip()):
            j += 1
        while j > i + 1 and not lines[j - 1].strip():
            j -= 1
        detail = "\n".join(line[2:] if line.startswith("  ") else line for line in lines[i + 1:j])
        out.append([[(i, j)], m.group(1).replace("**", "").strip(), detail])
        i = j
    k = 0
    while k < len(lines):
        if not lines[k].startswith("<details"):
            k += 1
            continue
        end, open_tags = k, 0
        while end < len(lines):
            open_tags += len(re.findall(r"<details", lines[end])) - lines[end].count("</details>")
            end += 1
            if open_tags <= 0:
                break
        text = "\n".join(lines[k:end])
        summary = re.match(r"<details[^>]*>\s*<summary>(.*?)</summary>", text)
        summary = summary if summary and _key(summary.group(1)) else None
        owner = None
        if summary:
            wanted = _key(summary.group(1))
            owner = next((t for t in out if _key(t[1]) == wanted), None) or next(
                (t for t in out if re.match(re.escape(wanted) + r"\b", _key(t[1]))), None)
        if owner is None and not summary:
            previous = [t for t in out if t[0][0][1] <= k]
            if previous and not any(line.strip() for line in lines[previous[-1][0][0][1]:k]):
                owner = previous[-1]
        if owner:
            owner[0].append((k, end))
            owner[2] += "\n\n" + text
        k = end
    for t in out:
        t[2] = re.sub(r"</?details[^>]*>|<summary>.*?</summary>", "", t[2]).strip()
    return [tuple(t) for t in out]


def _key(text):
    return re.sub(r"\s+", " ", re.sub(r"[*`«»]", "", text)).strip(" .:").lower()


def body_without_tasks(body, used):
    """El cuerpo sin las tareas que pasaron a subissues, con el aviso donde estaba la primera."""
    lines = body.splitlines()
    ranges = sorted((r for t in used for r in t[0]), reverse=True)
    for start, end in ranges:
        del lines[start:end]
    lines.insert(ranges[-1][0], SUBISSUES_NOTICE)
    return re.sub(r"\n{3,}", "\n\n", "\n".join(lines))


def split(n, groups, apply):
    it = issue(n)
    body = it["body"] or ""
    found = tasks(body)
    if not found:
        sys.exit(f"#{n} no tiene tareas sin tildar")
    if groups:
        indexes = [[int(x) - 1 for x in g.split(",")] for g in groups.split(";")]
    else:
        indexes = [[k] for k in range(len(found))]
    labels = [label["name"] for label in it["labels"]]
    titles = [found[g[0]][1] + (f" (y {len(g) - 1} más)" if len(g) > 1 else "") for g in indexes]
    for g, title in zip(indexes, titles):
        print(f"- {title}  <- tareas {', '.join(str(k + 1) for k in g)}")
    if not apply:
        print(f"\n{len(indexes)} subissues de #{n}. Para crearlos: --hacer")
        return
    linked = {h["title"] for h in json.loads(gh("api", f"repos/{repo()}/issues/{n}/sub_issues?per_page=100"))}
    loose = {h["title"]: h for h in json.loads(gh(
        "issue", "list", "-R", repo(), "--author", "@me", "--state", "open", "--limit", "100",
        "--json", "number,title,body,id"))
        if (h["body"] or "").startswith(f"Parte de #{n}.") and h["title"] not in linked}
    for g, title in zip(indexes, titles):
        if title in linked:
            print(f"ya estaba: {title}")
            continue
        if title in loose:
            number = loose[title]["number"]
        else:
            parts = [f"Parte de #{n}."]
            for k in g:
                parts.append(f"### {found[k][1]}\n\n{found[k][2]}" if len(g) > 1 else found[k][2])
            number = json.loads(gh("api", f"repos/{repo()}/issues", "--input", "-", attempts=1, stdin=json.dumps({
                "title": title, "body": "\n\n".join(parts), "labels": labels,
            })))["number"]
        child_id = issue(number)["id"]
        gh("api", "-X", "POST", f"repos/{repo()}/issues/{n}/sub_issues", "-F", f"sub_issue_id={child_id}", attempts=1)
        print(f"#{number} vinculado")
    new_body = body_without_tasks(body, [found[k] for g in indexes for k in g])
    gh("api", "-X", "PATCH", f"repos/{repo()}/issues/{n}", "--input", "-", stdin=json.dumps({"body": new_body}))
    print(f"#{n}: tareas movidas a los subissues")


def with_closed_subissues(nodes):
    """Los padres con todos sus subissues cerrados: los candidatos a cerrarse, con su cuerpo."""
    return [(x["number"], x.get("body")) for x in nodes if x["subIssuesSummary"]["total"]
            and x["subIssuesSummary"]["completed"] == x["subIssuesSummary"]["total"]]


def has_pending_tasks(body):
    """Si el cuerpo de un padre todavía tiene tareas: `dividir --grupos` deja ahí las que no fueron a un subissue."""
    return bool(tasks(body or ""))


def close_parents():
    owner, name = owner_and_name()
    candidates, cursor = [], ""
    while True:
        d = graphql(
            "query($o:String!,$r:String!,$c:String){repository(owner:$o,name:$r)"
            "{issues(first:100,states:OPEN,after:$c){pageInfo{hasNextPage endCursor}"
            "nodes{number body subIssuesSummary{total completed}}}}}",
            o=owner, r=name, **({"c": cursor} if cursor else {}))
        page = d["repository"]["issues"]
        candidates += with_closed_subissues(page["nodes"])
        if not page["pageInfo"]["hasNextPage"]:
            break
        cursor = page["pageInfo"]["endCursor"]
    closed, kept_open = 0, 0
    for n, body in candidates:
        if has_pending_tasks(body):
            print(f"#{n}: todos sus subissues están cerrados, pero le quedan tareas en el cuerpo; queda abierto. "
                  f"Dividilas (issues.py dividir {n}) o, si ya están hechas, tildalas o sacalas del cuerpo")
            kept_open += 1
            continue
        gh("issue", "close", str(n), "-R", repo(), "-c", "Todos los subissues están cerrados y no quedan tareas.")
        print(f"#{n} cerrado")
        closed += 1
    if not closed:
        print("ningún padre para cerrar" + (f" ({kept_open} quedan abiertos por sus tareas)" if kept_open else ""))


def worktree_action(wt, merged, now, in_use=()):
    """(borrar, borrar_la_rama, motivo) para un worktree de `.claude/worktrees`.

    No se borra el que tiene cambios sin commitear ni el que usa la sesión. Se borra el de una rama con PR mergeado
    (y la rama local, que ya está en main) o el que lleva 24 h sin actividad, que deja su rama."""
    path = os.path.normcase(os.path.abspath(wt["path"]))
    used = [os.path.normcase(os.path.abspath(u)) for u in in_use if u]
    if any(u == path or u.startswith(path + os.sep) for u in used):
        return False, False, "es el de esta sesión"
    if wt["uncommitted"]:
        return False, False, "tiene cambios sin commitear: " + ", ".join(wt["uncommitted"][:5])
    if wt["branch"] in merged:
        return True, True, "su PR está mergeado"
    if now - wt["last_activity"] > INACTIVE_SECONDS:
        return True, False, "lleva más de 24 h sin actividad (la rama queda)"
    return False, False, "activo"


def _git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, encoding="utf-8")


def _worktrees():
    root = os.path.join(_git("rev-parse", "--show-toplevel").stdout.strip(), ".claude", "worktrees")
    found, current = [], {}
    for line in _git("worktree", "list", "--porcelain").stdout.splitlines() + [""]:
        if line.startswith("worktree "):
            current = {"path": line[9:]}
        elif line.startswith("branch "):
            current["branch"] = line[7:].removeprefix("refs/heads/")
        elif not line and current:
            if os.path.normcase(os.path.abspath(current["path"])).startswith(os.path.normcase(root) + os.sep):
                found.append(current)
            current = {}
    for wt in found:
        wt.setdefault("branch", "")
        gitdir = _git("-C", wt["path"], "rev-parse", "--absolute-git-dir").stdout.strip()
        times = [os.path.getmtime(p) for p in (os.path.join(gitdir, "HEAD"), os.path.join(gitdir, "logs", "HEAD"))
                 if os.path.exists(p)]
        wt["last_activity"] = max(times, default=time.time())
        wt["uncommitted"] = [line[3:].strip() for line in
                             _git("-C", wt["path"], "status", "--porcelain").stdout.splitlines()]
    return found


def clean_worktrees(apply, in_use=()):
    wts = _worktrees()
    if not wts:
        print("ningún worktree en .claude/worktrees")
        return
    branches = json.loads(gh("pr", "list", "-R", repo(), "--state", "merged", "--limit", "200",
                             "--json", "headRefName") or "[]")
    merged = {b["headRefName"] for b in branches}
    for wt in wts:
        delete, delete_branch, reason = worktree_action(wt, merged, time.time(), in_use)
        name = os.path.basename(wt["path"])
        if not delete or not apply:
            print(f"{name}: {'se borraría' if delete else 'queda'} ({reason})")
            continue
        r = _git("worktree", "remove", "--force", wt["path"])
        if os.path.exists(wt["path"]):
            shutil.rmtree(wt["path"], ignore_errors=True)
        if delete_branch and wt["branch"]:
            _git("branch", "-D", wt["branch"])
        print(f"{name}: borrado ({reason})" if not os.path.exists(wt["path"])
              else f"{name}: no se pudo borrar ({r.stderr.strip()})")
    _git("worktree", "prune")


def open_prs(hook):
    try:
        prs = json.loads(gh("pr", "list", "-R", repo(), "--state", "open", "--json",
                            "number,title,author,isDraft", attempts=1))
    except SystemExit:
        if hook:
            return
        raise
    lines = [f"#{p['number']} {p['title']} ({p['author']['login']}{', borrador' if p['isDraft'] else ''})"
             for p in prs]
    text = (f"PRs abiertos ({len(prs)}):\n" + "\n".join(lines)) if prs else "No hay PRs abiertos."
    if not hook:
        print(text)
    elif prs:
        print(json.dumps({"systemMessage": text, "hookSpecificOutput": {
            "hookEventName": "SessionStart", "additionalContext": text}}, ensure_ascii=False))


def issue_form_body(root, name):
    """El cuerpo que arma GitHub con el formulario `name`: cada campo como `### título`, en orden, con su descripción
    como comentario para completar. El formulario es la única definición de los campos."""
    import yaml

    data = yaml.safe_load((root / ISSUE_FORMS / f"{name}.yml").read_text(encoding="utf-8"))
    fields = [c["attributes"] for c in data["body"] if c["type"] != "markdown"]
    parts = [f"### {a['label']}\n\n<!-- {a.get('description', '').replace('--', '—')} -->\n" for a in fields]
    return "\n".join(parts)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("tomar").add_argument("n", type=int)
    d = sub.add_parser("dividir")
    d.add_argument("n", type=int)
    d.add_argument("--grupos", help='qué tareas van juntas, por número: "1,2;3;4,5"')
    d.add_argument("--hacer", action="store_true")
    sub.add_parser("cerrar-padres")
    w = sub.add_parser("limpiar-worktrees")
    w.add_argument("--hacer", action="store_true")
    w.add_argument("--hook", action="store_true", help="lee del hook el cwd de la sesión, para no borrarlo")
    sub.add_parser("prs-abiertos").add_argument("--hook", action="store_true")
    sub.add_parser("plantilla").add_argument("nombre", help="el formulario, sin .yml (ej.: tarea)")
    a = ap.parse_args()
    if a.cmd == "tomar":
        take(a.n)
    elif a.cmd == "dividir":
        split(a.n, a.grupos, a.hacer)
    elif a.cmd == "limpiar-worktrees":
        in_use = [os.getcwd()]
        if a.hook:
            try:
                in_use.append(json.loads(sys.stdin.read() or "{}").get("cwd", ""))
            except ValueError:
                pass
        clean_worktrees(a.hacer, in_use)
    elif a.cmd == "prs-abiertos":
        open_prs(a.hook)
    elif a.cmd == "plantilla":
        print(issue_form_body(Path(__file__).resolve().parents[1], a.nombre))
    else:
        close_parents()


if __name__ == "__main__":
    main()
