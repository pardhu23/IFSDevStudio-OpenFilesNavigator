# IFS-Specific Actions and Module Dependencies

## Purpose and Scope

Three components provide IFS Developer Studio–specific functionality that has no meaning outside of an IFS project: `FindApiFileAction`, `FormatApiNameAction`, and `ModuleDependencyTreePanel`. They rely on IFS naming conventions, project property keys, and (in one case) reflection against loaded IFS classes.

## Key Classes / Files

| File | Role |
|------|------|
| `FindApiFileAction.java` | Converts selected `snake_case_api` text to `PascalCase` and opens Quick Search |
| `FormatApiNameAction.java` | Reformats selected text to `Snake_Case_API` in-editor |
| `ModuleDependencyTreePanel.java` | Displays static/dynamic IFS module dependencies via reflection |

---

## FindApiFileAction

### Purpose
In IFS, API files follow the convention `CustomerOrderApi.apv` while database identifiers use `CUSTOMER_ORDER_API`. A developer seeing `customer_order_api` in PL/SQL code can select it, invoke this action, and immediately jump to the file without manual conversion.

### Conversion algorithm
1. Strip a trailing `_api`, `_Api`, or `_API` suffix (case-insensitive).
2. Split the remainder on `_` and capitalise each segment.
3. Append `Api` (no underscore).

Example: `customer_order_api` → `CustomerOrder` + search opens with `CustomerOrder` pre-filled (finds `CustomerOrderApi.apv`, `CustomerOrderApi.plsvc`, etc.).

### Registration
Registered via `@ActionID` / `@ActionReference` at position 320 in the PlSQL editor popup menu (`Editors/text/x-plsql/Popup`). Only appears when text is selected in a PL/SQL editor.

---

## FormatApiNameAction

### Purpose
Formats selected database-style identifiers to the IFS mixed-case convention used in source file content. For example, `CUSTOMER_ORDER_API` → `Customer_Order_API`. This is used when writing IFS view or projection definitions where the mixed-case form is required by the IFS framework parser.

### Conversion algorithm
1. Split on `_`.
2. Capitalise the first character of each segment; lowercase the rest.
3. Re-join with `_`.

Example: `customer_order_api` → `Customer_Order_Api` (segments capitalised independently).

### Registration
Registered at position 322 in the same PlSQL popup menu, immediately after `FindApiFileAction`. Replaces the selected text in-place using the editor's `Document`.

---

## ModuleDependencyTreePanel

### Purpose
IFS applications are composed of modules that depend on each other at two levels: *static* (declared in module definitions) and *dynamic* (resolved at runtime from loaded configuration). This panel reads both levels and provides a tree + path-finder UI for dependency analysis during deploy-order debugging.

### Architecture

#### Reflection-based IFS API access
The panel uses `Class.forName()` and reflection to call:
- `DatabaseContentUtilitiesSqlLibrary.getStaticDependencies(moduleName)`
- `DatabaseContentManager.getDynamicDependecies(moduleName)` *(note: typo in the IFS API is preserved)*

This avoids any compile-time dependency on IFS modules. If the classes are absent (non-IFS IDE), the panel shows an error message instead of crashing.

#### Three tabs

| Tab | Data source | Notes |
|-----|------------|-------|
| Static | `getStaticDependencies()` via reflection | Shows compile-time declared deps |
| Dynamic | `getDynamicDependecies()` via reflection | Shows runtime-resolved deps |
| Static Path Finder | BFS over static deps | Finds all paths between two modules, capped at 20 |

#### BFS path finder
`findAllPaths(from, to, staticDeps)` runs a breadth-first search over the static dependency graph. It tracks visited nodes to avoid cycles and caps results at 20 paths to prevent memory explosions on highly connected graphs. Results are rendered as `A → B → C → D` strings in a `JTextArea`.

#### deploy.ini navigation
Right-clicking a module node shows "Open deploy.ini [MODULE]". The action resolves the file path using:
1. Open projects' root directories + `/workspace/<MODULE>/deploy.ini`
2. Core checkout root (`project.ccs.corefiles` property) + `/<MODULE>/deploy.ini`
Then opens it via `DataObject` / `OpenCookie`.

### Non-Obvious Details

- **Reflection call safety**: All reflection is wrapped in a try-catch. If the IFS class is loaded but the method signature has changed, the panel logs the error to `System.err` and shows the error in the tree rather than propagating a crash.
- **`openWindow()` static method**: Opens the panel in a standalone `JFrame`. This is intentional — the module dependency panel is a utility tool that should not dock into the main window system.
- **Module name normalisation**: The panel passes module names to the IFS API in uppercase. The tree displays them as returned by the API (mixed case in some IFS versions).

## Known Gotchas / Constraints

- **Reflection failures are silent to the user**: If `Class.forName` fails (IFS classes not on classpath), the tabs show empty trees rather than an error. Check `System.err` / IDE log if the panel appears empty.
- **`getDynamicDependecies` is a typo in the IFS API** — it is spelled with one `n`. Do not "fix" it; the reflection call must match the actual method name.
- **BFS cap of 20 paths**: On a large IFS installation with a highly interconnected module graph, BFS can find thousands of paths. The cap prevents UI freezes but means the path-finder may not show the shortest path if shorter paths are found after the 20th result.
- **`FindApiFileAction` strips only `_api` suffix variants**: It does not handle other IFS suffixes like `_tab`, `_seq`, etc. Extend the strip-list if needed for other file types.
