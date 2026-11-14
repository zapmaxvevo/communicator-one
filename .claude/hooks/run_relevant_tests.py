#!/usr/bin/env python3
"""Hook de tests acotado: baseline ANTES de editar y corrida final al TERMINAR.

Funciona en dos momentos y solo sobre lo afectado:

- PreToolUse (Write|Edit): la PRIMERA vez que se edita un paquete Java (o un archivo de la web) en la sesion,
  corre sus tests como *baseline* (codigo aun sin modificar) y guarda si pasaban.
- Stop: corre una sola vez los tests de cada objetivo tocado y los compara contra el baseline:
    * pasan                          -> OK
    * fallan y ya fallaban antes     -> preexistente
    * fallan y antes pasaban         -> introducido por estos cambios (exit 2)

Mapeo archivo -> objetivo:
- `backend/src/(main|test)/java/<paquete>/X.java`: sube desde el paquete del archivo hasta el primer paquete que tenga
  tests en `src/test/java`, y corre `./mvnw test -Dtest='<paquete>/**/*'`.
- `web/src/**/*.ts(x)`: `npx vitest related --run <archivo>`.

Si no existe `backend/pom.xml` o `web/package.json` (antes del scaffold), no hace nada.
El estado se guarda en un archivo temporal por session_id y se borra cuando todo queda en verde.
"""
import json
import os
import subprocess
import sys
import tempfile

TIMEOUT_SECONDS = 300
TEST_SUFFIXES = ('Test.java', 'Tests.java', 'IT.java')


def state_path(data):
    session_id = data.get('session_id') or 'default'
    return os.path.join(tempfile.gettempdir(), f'claude_tests_communicator_one_{session_id}.json')


def load_state(path):
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return {'targets': {}}


def save_state(path, state):
    try:
        with open(path, 'w') as f:
            json.dump(state, f)
    except Exception:
        pass


def edited_path(data):
    return (
        data.get('tool_response', {}).get('filePath')
        or data.get('tool_input', {}).get('file_path')
        or ''
    )


def find_upwards(start_dir, marker):
    current = start_dir
    while True:
        if os.path.isfile(os.path.join(current, marker)):
            return current
        parent = os.path.dirname(current)
        if parent == current:
            return None
        current = parent


def has_tests(directory):
    for _root, _dirs, files in os.walk(directory):
        if any(name.endswith(TEST_SUFFIXES) for name in files):
            return True
    return False


def java_target(file_path):
    for source_set in ('/src/main/java/', '/src/test/java/'):
        if source_set in file_path:
            module_root, package_path = file_path.split(source_set, 1)
            break
    else:
        return None
    if not os.path.isfile(os.path.join(module_root, 'pom.xml')):
        return None
    test_root = os.path.join(module_root, 'src', 'test', 'java')
    package_dir = os.path.dirname(package_path)
    while package_dir:
        if has_tests(os.path.join(test_root, package_dir)):
            return {
                'kind': 'maven',
                'root': module_root,
                'selector': f'{package_dir}/**/*',
            }
        package_dir = os.path.dirname(package_dir)
    return None


def web_target(file_path):
    if not file_path.endswith(('.ts', '.tsx')) or '/src/' not in file_path:
        return None
    web_root = find_upwards(os.path.dirname(file_path), 'package.json')
    if not web_root or os.path.basename(web_root) != 'web':
        return None
    return {
        'kind': 'vitest',
        'root': web_root,
        'selector': os.path.relpath(file_path, web_root),
    }


def resolve_target(file_path):
    if not file_path:
        return None
    file_path = os.path.abspath(file_path)
    if file_path.endswith('.java'):
        return java_target(file_path)
    return web_target(file_path)


def target_key(target):
    return f"{target['kind']}:{target['root']}:{target['selector']}"


def maven_command(root, selector):
    wrapper = os.path.join(root, 'mvnw')
    executable = wrapper if os.access(wrapper, os.X_OK) else 'mvn'
    return [
        executable, '-q', '-B', 'test',
        f'-Dtest={selector}',
        '-Dsurefire.failIfNoSpecifiedTests=false',
    ]


def vitest_command(selector):
    return ['npx', '--no-install', 'vitest', 'related', '--run', '--passWithNoTests', selector]


def run_tests(target):
    """Devuelve (paso, salida). paso es None si la herramienta no esta disponible."""
    if target['kind'] == 'maven':
        command = maven_command(target['root'], target['selector'])
    else:
        command = vitest_command(target['selector'])
    try:
        result = subprocess.run(
            command, cwd=target['root'], capture_output=True, text=True, timeout=TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        return False, f'(timeout > {TIMEOUT_SECONDS}s)'
    except FileNotFoundError:
        return None, f'(no se encontro `{command[0]}`)'
    return result.returncode == 0, (result.stdout or '') + (result.stderr or '')


def describe(target):
    if target['kind'] == 'maven':
        return f"backend {target['selector']}"
    return f"web {target['selector']}"


def handle_pre(data):
    target = resolve_target(edited_path(data))
    if not target:
        return 0
    path = state_path(data)
    state = load_state(path)
    key = target_key(target)
    if key in state['targets']:
        return 0

    passed, _output = run_tests(target)
    if passed is None:
        return 0
    target['baseline'] = 'passed' if passed else 'failed'
    state['targets'][key] = target
    save_state(path, state)
    print(
        f'[tests-hook] baseline `{describe(target)}`: '
        f'{"verde" if passed else "ya fallaba antes de tus cambios"}.'
    )
    return 0


def handle_stop(data):
    path = state_path(data)
    state = load_state(path)
    targets = state.get('targets', {})
    if not targets:
        return 0

    introduced = []
    preexisting = []
    ok = []
    details = []
    for target in targets.values():
        passed, output = run_tests(target)
        if passed is None:
            continue
        name = describe(target)
        if passed:
            ok.append(name)
        elif target.get('baseline') == 'failed':
            preexisting.append(name)
        else:
            introduced.append(name)
            details.append(f'### `{name}`\n{output[-4000:]}')

    summary = []
    if ok:
        summary.append(f'OK: {", ".join(ok)}')
    if preexisting:
        summary.append(f'fallan (ya fallaban antes): {", ".join(preexisting)}')
    if introduced:
        summary.append(f'ROTOS por estos cambios: {", ".join(introduced)}')
    line = '[tests-hook] ' + ' | '.join(summary) if summary else ''

    if not introduced:
        try:
            os.remove(path)
        except OSError:
            pass
        if line:
            print(line)
        return 0

    if data.get('stop_hook_active'):
        print(line + '\n(quedan tests rojos; reintento del hook agotado)')
        return 0

    print(line + '\n\n' + '\n\n'.join(details), file=sys.stderr)
    return 2


def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        return 0
    event = data.get('hook_event_name', '')
    if event == 'Stop':
        return handle_stop(data)
    return handle_pre(data)


if __name__ == '__main__':
    sys.exit(main())
