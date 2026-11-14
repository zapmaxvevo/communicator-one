---
description: Estándares de React + TypeScript para web/
paths:
  - "web/**/*.ts"
  - "web/**/*.tsx"
  - "web/**/*.css"
---

# Web (React + TypeScript)

React + Vite + TypeScript estricto. `eslint --fix` corre solo al guardar (hook PostToolUse); también `npm run lint`
desde `web/`. Esta regla manda sobre la skill global `react-frontend-architecture` cuando haya conflicto.

## Principio
- **Toda la lógica de negocio vive en el backend.** La web muestra lo que devuelve la API y manda acciones. Reglas como
  la ventana de canal, el reparto o los permisos no se recalculan en el cliente: se muestran como las informa el
  backend.

## Estructura
- **Por feature:** `features/<nombre>/{components,hooks,types,utils,assets}`, más `shared/` (`ui/`, `hooks/`,
  `utils/`, `styles/` con `global.css`), `app/` (shell, rutas, configuración) y `api/` (cliente generado desde el
  OpenAPI del backend). Los `.module.css` van al lado de su `.tsx`, no en una carpeta aparte (P-013).
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
- **CSS Modules**: `Componente.module.css` al lado de `Componente.tsx`, importado como `styles` (P-013).
- Color, tipografía, espaciado y radio **solo con los tokens definidos en `shared/styles/global.css`**, nunca con
  valores sueltos; si falta uno, se agrega ahí.
- `global.css` (tokens y reset mínimo) se importa una sola vez, en `main.tsx`; nada de otros estilos globales.

## Tests
- Vitest + Testing Library, en `<Componente>.test.tsx` al lado del componente (P-014).
- `globals: true` en `web/vite.config.ts` está para que Testing Library haga el cleanup automático entre tests: **no se
  saca**. Igual, `describe`, `it` y `expect` se importan de `vitest`, porque `tsc` lo exige.
- Nombre en inglés como frase de usuario, en presente: `it("shows ... when ...")`. `describe` solo cuando hay varios
  escenarios del mismo componente.
- Given/When/Then con bloques separados por una línea en blanco, **sin comentarios**.
- Consultas por rol y nombre accesible (`getByRole("heading", { name: ... })`) antes que por texto o `testid`.
