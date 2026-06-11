# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Permissions

- **Run anything freely** — compile, run, read, edit, create, or delete files within this repo without asking for permission.
- **Never commit** — do not run `git commit` under any circumstances. The user reviews all changes and commits manually, which allows rollback of any unwanted changes.

## Build & Run

```bash
# Build
mvn clean install

# Run the application
mvn javafx:run
```

No test framework or linting tools are currently configured in pom.xml.

## Architecture

This is a **JavaFX desktop application** that parses Java code, visualizes it as a flowchart, and simulates step-by-step execution with live variable editing.

**Pipeline (left to right):**

```
User types Java code
  → CodeParser       — AST parsing via JavaParser, emits FlowNode/FlowEdge lists
  → ExecutionEngine  — walks the graph step-by-step, evaluates conditions, mutates variable state
  → FlowChartView    — renders nodes/edges on a JavaFX Canvas with zoom/pan support
  → VariablePanel    — editable variable grid; pushes changes back into ExecutionEngine
  → DrawIoExporter   — serializes the current flowchart as draw.io XML
```

**Key packages:**

| Package | Role |
|---|---|
| `com.codeflow` | `MainApp` — JavaFX entry point, 1200×800 window |
| `com.codeflow.parser` | `CodeParser` — wraps bare code in `class Temp { void temp(){…} }` before parsing; emits `ParseResult` |
| `com.codeflow.engine` | `ExecutionEngine` — two-phase step: first fires `StepEvent.node()` (highlight), then `StepEvent.edge()` (execute & advance) |
| `com.codeflow.model` | `FlowNode`, `FlowEdge`, `StepEvent`, `ExecutionContext` — plain data classes |
| `com.codeflow.enums` | `NodeType` — `START`, `PROCESS`, `DECISION`, `END`, `JOIN` |
| `com.codeflow.ui` | `MainView` (root container), `FlowChartView` (canvas renderer), `VariablePanel` (variable grid) |
| `com.codeflow.export` | `DrawIoExporter` — currently unused; draw.io export logic lives in `FlowChartView.generateDrawIOXML()` |

## Parser Scope & Limitations

`CodeParser` only handles a **single flat method body**. Supported constructs:
- `if/else` → DECISION nodes with `true`/`false` edges
- Variable declarations and assignments
- `return` → END node
- Simple arithmetic (`+`, `-`), `++`, `--`

**Not supported:** loops (`for`, `while`), nested if beyond one level, method calls, complex expressions.

## Execution Engine Details

`ExecutionEngine` uses a two-phase step model driven by `StepEvent`:
- **`SHOW_NODE`** phase: highlights the current node in the UI, no state change
- **`SHOW_EDGE`** phase: executes the node's statement (updates variables), advances to next node

Condition evaluation supports: `>`, `<`, `>=`, `<=`, `==`, `!=`. Variable type inference handles `int`, `long`, `double`, `float`, `boolean`, `String`, and `null`.

## FlowChartView Rendering

Draws directly onto a JavaFX `Canvas`. Node shapes:
- Rectangle → `START`, `PROCESS`, `END`
- Diamond → `DECISION`

Supports scroll-wheel zoom (0.3×–3.0×), click-drag pan, and fit-to-screen. The draw.io XML export (`generateDrawIOXML()`) replicates all nodes and edges with orthogonal routing.

## Tech Stack

- **Java 21**, **JavaFX 21**, **Maven**
- **JavaParser 3.25.8** — AST parsing
- **JGraphX 4.2.2** — imported but not actively used
- **SLF4J 2.0.9 + Logback 1.4.11** — logging (no `logback.xml`; uses defaults)
