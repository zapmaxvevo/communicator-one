---
name: documentation
description: Escribe documentación visual y lógica de una funcionalidad o flujo. Usar cuando se pregunta cómo funciona un flujo, cuando el usuario pregunta cómo funciona algo, o cuando se pide documentar explícitamente.
---

Al escribir documentación:
1. Primero, un resumen de cómo funciona y cuándo se activa.
2. Dónde están los tests y qué cubren.
3. Diagramas visuales (Mermaid en el Markdown).
4. Documentación extensa del flujo.

# Cómo
- En español, en `docs/` (un archivo por flujo o módulo, enlazado desde `README.md` si es de interés general).
- Documentar el código tal como está, leyéndolo; no copiar del backlog ni del diseño.
- Especificar siempre los tests y cómo cubren la funcionalidad y sus restricciones.
- Incluir ejemplos de datos de entrada y de salida (payloads de webhook, requests y responses de la API, eventos).
- Referencias `archivo:línea` verificadas.
