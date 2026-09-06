# Hook Flow Diagrams

Interactive flow charts of the VDI / SyncWeave **hook flow** - the order in
which AssemblyLine and connector hooks fire during a run.

Each diagram is a self-contained, interactive HTML page (pan/zoom, light/dark
theme, focus and trace, PNG/SVG export) generated with
[archify](https://github.com/tt-a1i/archify) from the hook definitions in the
source, not hand-drawn.

## Source of truth

Every hook name, its ordering, and the per-mode structure come from:

- `src/com/ibm/di/util/HookTree.java` - the canonical hook tree, grouped by
  connector mode (`iteratorMode`, `lookupMode`, ...) and phase.
- `HookTree.mainAssemblyLine` - the AssemblyLine-level hook list.

The diagrams reproduce these; they add no hooks that are not in the source.

## Diagrams

Open any `.html` file in a browser.

| Diagram | Connector mode | `HookTree` source |
|---|---|---|
| [assemblyline.html](assemblyline.html) | AssemblyLine lifecycle (wraps every connector) | `mainAssemblyLine` |
| [iterator.html](iterator.html) | Iterator | `iteratorMode` |
| [lookup.html](lookup.html) | Lookup | `lookupMode` |
| [addonly.html](addonly.html) | AddOnly | `addonlyMode` |
| [delete.html](delete.html) | Delete | `deleteMode` |
| [update.html](update.html) | Update (Modify + Add branches) | `updateMode` |
| [delta.html](delta.html) | Delta (Modify + Add + Delete branches) | `deltaMode` |
| [callreply.html](callreply.html) | Call/Reply | `callreplyMode` |
| [function.html](function.html) | Function Component | `functionComponentMode` |
| [reply.html](reply.html) | Reply (server reply side) | `replyLoop` |
| [server.html](server.html) | Server (accept + iterator read + reply) | `serverMode` |
| [reconnect.html](reconnect.html) | Reconnect / Failover (shared sub-flow) | `reconnectHooks` |

`SCRIPT` and `BRANCH` modes are omitted because they define no hooks
(`scriptMode` / `branchComponentMode` are empty in `HookTree`).

## How to read them

- **Swimlanes** group hooks by phase: Prolog/Initialize, the mode's Data Flow
  loop, Epilog/Close, and Errors & Reconnect.
- **Solid green** edges are the success path; **dashed** edges are branches and
  the loop-back to the next cycle; **red** edges lead to `*_fail` hooks.
- Node colours: normal hook, override hook, attribute-map hook, fail/error
  hook, and terminal/no-change hook (see each page's legend).
- The Reconnect sub-flow is shared by the connector modes that support it
  (Iterator, Lookup, AddOnly, Delete, Update, Delta, Call/Reply); those pages
  show a single "Reconnect flow" pointer into [reconnect.html](reconnect.html).

Most diagrams fit a laptop screen. **update** and **delta** are taller because
they encode two and three write branches respectively; use the viewer's
zoom / fit controls, or scroll, to see the whole chart.

## Regenerating

The diagrams are produced from small typed JSON specs under
[`specs/`](specs), which are themselves emitted by
[`specs/gen_specs.py`](specs/gen_specs.py). To rebuild after editing the
generator (requires the `archify` skill and Node.js):

```bash
# 1. regenerate the JSON specs from the hook definitions
python3 docs/hook-flow/specs/gen_specs.py

# 2. render each spec to interactive HTML (repeat per mode)
node <archify>/bin/archify.mjs deliver workflow \
    docs/hook-flow/specs/iterator.workflow.json \
    docs/hook-flow/iterator.html --quality standard
```

If you add or reorder hooks in `HookTree.java`, update the matching spec in
`gen_specs.py` so the diagrams stay in sync with the code.
