---
description: Estándares de React + TypeScript para web/
paths:
  - "web/**/*.ts"
  - "web/**/*.tsx"
---

# Web (React + TypeScript)

React + Vite + TypeScript estricto. `eslint --fix` corre solo al guardar (hook PostToolUse); también `npm run lint`
desde `web/`. Esta regla manda sobre la skill global `react-frontend-architecture` cuando haya conflicto.

## Principio
- **Toda la lógica de negocio vive en el backend.** La web muestra lo que devuelve la API y manda acciones. Reglas como
  la ventana de canal, el reparto o los permisos no se recalculan en el cliente: se muestran como las informa el
  backend.

## Estructura
- **Por feature:** `features/<nombre>/{components,hooks,types,utils,styles,assets}`, más `shared/` (`ui/`, `hooks/`,
  `utils/`, `styles/`), `app/` (shell, rutas, configuración) y `api/` (cliente generado desde el OpenAPI del backend).
- Features aisladas: nada de imports entre features; lo compartido va a `shared/`.
- **El cliente generado en `api/` no se edita a mano**: se regenera con el script documentado en `CLAUDE.md`.

## Componentes y exports
- **Solo componentes de función y hooks.**
- Componentes con **export default**, archivos en **PascalCase** (`OpportunityList.tsx`).
- Hooks y utilidades con **exports nombrados**; los hooks empiezan con `use` y van en `hooks/` (`useTimeline`).
- Props tipadas con una `interface` con sufijo **`Props`**, desestructuradas en la firma.

## TypeScript
- `strict: true`. **Uniones discriminadas** para el estado (`type LoadState = "idle" | "loading" | "loaded" | "error"`).
- **`import type`** para imports solo de tipos.
- **Comillas dobles** y **punto y coma**.
- **PROHIBIDO ESCRIBIR COMENTARIOS salvo que se pidan explícitamente.**

## Tiempo real
- Las suscripciones a WebSocket viven en un hook con cleanup; nada de suscripciones sueltas en componentes. Tienen que
  soportar el doble montaje de StrictMode y la reconexión sin duplicar mensajes.

## Estilos
- Se definen en el issue #3 (scaffold de la web) y se anotan como provisoria en `docs/decisiones.md`; después se agregan a
  esta regla.
