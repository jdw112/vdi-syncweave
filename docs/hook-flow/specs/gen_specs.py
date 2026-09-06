#!/usr/bin/env python3
#
# Copyright contributors to the SyncWeave project
#
# SPDX-License-Identifier: Apache-2.0
"""
Generate Archify workflow specs for the VDI/SyncWeave hook flow.

Source of truth: src/com/ibm/di/util/HookTree.java (hook names, ordering and
per-connector-mode structure) plus the AssemblyLine-level hook list
(HookTree.mainAssemblyLine). This script only lays those hooks out for the
Archify workflow renderer; it invents no hook names.

Node widths are computed from the widest of label/sublabel so the Archify
layout validator (label must fit inside node) passes without hand tuning.
"""
import json
import math
import os

OUT_DIR = os.path.dirname(os.path.abspath(__file__))


def ntype(label):
    if "attribute_map" in label:
        return "database"
    if label.startswith("override"):
        return "messagebus"
    if label.endswith("_fail"):
        return "security"
    if label in ("end_of_data", "no_reply", "modify_nochange", "add_abandon"):
        return "external"
    return "backend"


# Per-diagram width scale. Node width sets the column pitch, which sets the
# diagram's viewBox width; a wider diagram is downscaled to the reader width,
# shrinking rendered height (good for tall diagrams) but also shrinking text.
# Diagrams with short labels + many lanes want the generous default; a diagram
# with long labels + few lanes (iterator) must scale down or its text projects
# below the readable floor at 1440px.
WSCALE = 1.0


def width_for(label, sublabel):
    longest = max(len(label), len(sublabel or ""))
    return max(150, int(math.ceil(longest * 9.5 * WSCALE)) + 40)


def N(id, lane, col, label, sublabel=None, tag=None, type=None):
    n = {
        "id": id,
        "lane": lane,
        "col": col,
        "type": type or ntype(label),
        "label": label,
        "width": width_for(label, sublabel),
    }
    if sublabel:
        n["sublabel"] = sublabel
    if tag:
        n["tag"] = tag
    return n


def E(frm, to, label=None, variant="default", role=None):
    e = {"from": frm, "to": to, "variant": variant}
    if label:
        e["label"] = label
    if role:
        e["role"] = role
    return e


LEGEND = {
    "mode": "auto",
    "entries": {
        "backend": {"label": "Hook"},
        "messagebus": {"label": "Override hook"},
        "database": {"label": "Attribute map"},
        "security": {"label": "Fail / error hook"},
        "external": {"label": "Terminal / no-change"},
    },
}


def build(title, output, lanes, phases, nodes, edges, mainPath, cards):
    # Carry the source provenance in a one-line subtitle instead of tall cards,
    # so the diagram panel stays within a laptop viewport (no vertical scroll).
    subtitle = None
    for c in cards:
        if c.get("title") == "Source" and c.get("items"):
            subtitle = c["items"][0]
            break
    meta = {
        "title": title,
        "quality_profile": "standard",
        "legend": LEGEND,
        "output": output,
    }
    if subtitle:
        meta["subtitle"] = subtitle
    return {
        "schema_version": 2,
        "diagram_type": "workflow",
        "meta": meta,
        "lanes": lanes,
        "phases": phases,
        "mainPath": mainPath,
        "nodes": nodes,
        "edges": edges,
    }


LANE_PROLOG = {"id": "prolog", "label": "Prolog / Initialize"}
LANE_EPILOG = {"id": "epilog", "label": "Epilog / Close"}
LANE_ERRORS = {"id": "errors", "label": "Errors & Reconnect", "variant": "exception"}


def close_nodes():
    return [
        N("before_close", "epilog", 4, "before_close"),
        N("after_close", "epilog", 5, "after_close"),
    ]


def close_edges():
    return [
        E("before_close", "after_close"),
        E("after_close", "close_fail", "on error", "security", "error"),
    ]


def source_card(mode_field):
    return {
        "dot": "cyan",
        "title": "Source",
        "items": [
            "Hooks from HookTree." + mode_field,
            "Solid = success path, red = fail, dashed = branch / loop",
        ],
    }


diagrams = []

# ----------------------------------------------------------------------------
# 1. LOOKUP mode  (initializeHooks + lookupLoop + closeHooks + reconnectHooks)
# ----------------------------------------------------------------------------
nodes = [
    N("before_initialize", "prolog", 0, "before_initialize"),
    N("after_initialize", "prolog", 1, "after_initialize"),

    N("before_execute", "flow", 0, "before_execute", "each cycle"),
    N("override_lookup", "flow", 1, "override_lookup", "override read"),
    N("before_lookup", "flow", 2, "before_lookup"),
    N("lookup_multiple", "flow", 3, "lookup_multiple", "many matches"),
    N("after_lookup", "flow", 4, "after_lookup"),
    N("input_attribute_map", "flow", 5, "input_attribute_map", "map result"),

    N("lookup_ok", "result", 4, "lookup_ok"),
    N("default_ok", "result", 5, "default_ok", "next cycle"),

    N("initialize_fail", "prolog", 2, "initialize_fail"),
    N("lookup_nomatch", "errors", 2, "lookup_nomatch", "no match", type="external"),
    N("lookup_fail", "errors", 3, "lookup_fail"),
    N("default_fail", "errors", 4, "default_fail"),
    N("close_fail", "errors", 5, "close_fail"),
    N("reconnect", "errors", 1, "Reconnect flow", "on conn failure", "shared", "security"),
] + close_nodes()
edges = [
    E("before_initialize", "after_initialize"),
    E("after_initialize", "before_execute", "enter loop", "emphasis"),
    E("before_execute", "override_lookup"),
    E("override_lookup", "before_lookup"),
    E("before_lookup", "lookup_multiple"),
    E("lookup_multiple", "after_lookup"),
    E("after_lookup", "input_attribute_map"),
    E("input_attribute_map", "lookup_ok", "match", "emphasis"),
    E("lookup_ok", "default_ok"),
    E("default_ok", "before_execute", "next cycle", "dashed", "return"),
    E("before_lookup", "lookup_nomatch", "no match", "dashed", "branch"),
    E("lookup_ok", "lookup_fail", "on error", "security", "error"),
    E("lookup_fail", "default_fail", None, "security", "error"),
    E("before_initialize", "initialize_fail", "on error", "security", "error"),
    E("default_ok", "before_close", "on close", "dashed", "branch"),
] + close_edges()
diagrams.append(build(
    "Lookup Mode Hook Flow", "docs/hook-flow/lookup.html",
    [LANE_PROLOG, {"id": "flow", "label": "Data Flow - Lookup"},
     {"id": "result", "label": "Data Flow - Result"}, LANE_EPILOG, LANE_ERRORS],
    [{"id": "init", "label": "Initialize", "fromCol": 0, "toCol": 1},
     {"id": "loop", "label": "Lookup loop", "fromCol": 2, "toCol": 5, "variant": "emphasis"}],
    nodes, edges,
    ["before_execute", "override_lookup", "before_lookup", "lookup_multiple", "after_lookup", "input_attribute_map"],
    [source_card("lookupLoop"),
     {"dot": "rose", "title": "Branches", "items": [
         "lookup_multiple fires when many entries match",
         "lookup_nomatch fires when none match"]}],
))

# ----------------------------------------------------------------------------
# 2. ADD ONLY mode  (initializeHooks + addonlyLoop + closeHooks + reconnect)
# ----------------------------------------------------------------------------
nodes = [
    N("before_initialize", "prolog", 0, "before_initialize"),
    N("after_initialize", "prolog", 1, "after_initialize"),

    N("before_execute", "flow", 0, "before_execute", "each cycle"),
    N("override_add", "flow", 1, "override_add", "override write"),
    N("output_attribute_map", "flow", 2, "output_attribute_map", "map to output"),
    N("before_add", "flow", 3, "before_add"),
    N("after_add", "flow", 4, "after_add"),
    N("addonly_ok", "flow", 5, "addonly_ok"),

    N("default_ok", "result", 5, "default_ok", "next cycle"),

    N("initialize_fail", "prolog", 2, "initialize_fail"),
    N("addonly_fail", "errors", 3, "addonly_fail"),
    N("default_fail", "errors", 4, "default_fail"),
    N("close_fail", "errors", 5, "close_fail"),
    N("reconnect", "errors", 1, "Reconnect flow", "on conn failure", "shared", "security"),
] + close_nodes()
edges = [
    E("before_initialize", "after_initialize"),
    E("after_initialize", "before_execute", "enter loop", "emphasis"),
    E("before_execute", "override_add"),
    E("override_add", "output_attribute_map"),
    E("output_attribute_map", "before_add"),
    E("before_add", "after_add", "write entry", "emphasis"),
    E("after_add", "addonly_ok"),
    E("addonly_ok", "default_ok"),
    E("default_ok", "before_execute", "next cycle", "dashed", "return"),
    E("addonly_ok", "addonly_fail", "on error", "security", "error"),
    E("addonly_fail", "default_fail", None, "security", "error"),
    E("before_initialize", "initialize_fail", "on error", "security", "error"),
    E("default_ok", "before_close", "on close", "dashed", "branch"),
] + close_edges()
diagrams.append(build(
    "Add-Only Mode Hook Flow", "docs/hook-flow/addonly.html",
    [LANE_PROLOG, {"id": "flow", "label": "Data Flow - Add"},
     {"id": "result", "label": "Data Flow - Result"}, LANE_EPILOG, LANE_ERRORS],
    [{"id": "init", "label": "Initialize", "fromCol": 0, "toCol": 1},
     {"id": "loop", "label": "Add-only loop", "fromCol": 2, "toCol": 5, "variant": "emphasis"}],
    nodes, edges,
    ["before_execute", "override_add", "output_attribute_map", "before_add", "after_add", "addonly_ok"],
    [source_card("addonlyLoop"),
     {"dot": "rose", "title": "Notes", "items": [
         "output_attribute_map builds the entry to add",
         "override_add can replace the built-in add"]}],
))

# ----------------------------------------------------------------------------
# 3. DELETE mode  (initializeHooks + deleteLoop + closeHooks + reconnect)
# ----------------------------------------------------------------------------
nodes = [
    N("before_initialize", "prolog", 0, "before_initialize"),
    N("after_initialize", "prolog", 1, "after_initialize"),

    N("before_execute", "flow", 0, "before_execute", "each cycle"),
    N("override_delete", "flow", 1, "override_delete", "override read+del"),
    N("before_lookup", "flow", 2, "before_lookup"),
    N("delete_multiple", "flow", 3, "delete_multiple", "many matches"),
    N("after_lookup", "flow", 4, "after_lookup"),
    N("input_attribute_map", "flow", 5, "input_attribute_map"),

    N("before_delete", "result", 2, "before_delete"),
    N("after_delete", "result", 3, "after_delete"),
    N("delete_ok", "result", 4, "delete_ok"),
    N("default_ok", "result", 5, "default_ok", "next cycle"),

    N("initialize_fail", "prolog", 2, "initialize_fail"),
    N("delete_nomatch", "errors", 1, "delete_nomatch", "no match", type="external"),
    N("delete_fail", "errors", 3, "delete_fail"),
    N("default_fail", "errors", 4, "default_fail"),
    N("close_fail", "errors", 5, "close_fail"),
] + close_nodes()
edges = [
    E("before_initialize", "after_initialize"),
    E("after_initialize", "before_execute", "enter loop", "emphasis"),
    E("before_execute", "override_delete"),
    E("override_delete", "before_lookup"),
    E("before_lookup", "delete_multiple"),
    E("delete_multiple", "after_lookup"),
    E("after_lookup", "input_attribute_map"),
    E("input_attribute_map", "before_delete", "match", "emphasis"),
    E("before_delete", "after_delete", "delete entry", "emphasis"),
    E("after_delete", "delete_ok"),
    E("delete_ok", "default_ok"),
    E("default_ok", "before_execute", "next cycle", "dashed", "return"),
    E("before_lookup", "delete_nomatch", "no match", "dashed", "branch"),
    E("delete_ok", "delete_fail", "on error", "security", "error"),
    E("delete_fail", "default_fail", None, "security", "error"),
    E("before_initialize", "initialize_fail", "on error", "security", "error"),
    E("default_ok", "before_close", "on close", "dashed", "branch"),
] + close_edges()
diagrams.append(build(
    "Delete Mode Hook Flow", "docs/hook-flow/delete.html",
    [LANE_PROLOG, {"id": "flow", "label": "Data Flow - Find"},
     {"id": "result", "label": "Data Flow - Delete"}, LANE_EPILOG, LANE_ERRORS],
    [{"id": "init", "label": "Initialize", "fromCol": 0, "toCol": 1},
     {"id": "loop", "label": "Delete loop", "fromCol": 2, "toCol": 5, "variant": "emphasis"}],
    nodes, edges,
    ["before_execute", "override_delete", "before_lookup", "delete_multiple", "after_lookup", "input_attribute_map"],
    [source_card("deleteLoop"),
     {"dot": "rose", "title": "Notes", "items": [
         "Delete first looks the entry up, then removes it",
         "delete_multiple / delete_nomatch cover match count"]}],
))

# ----------------------------------------------------------------------------
# 4. UPDATE mode  (initializeHooks + updateLoop + closeHooks + reconnect)
#    updateLoop wraps a modify branch and an add branch.
# ----------------------------------------------------------------------------
nodes = [
    N("before_initialize", "prolog", 0, "before_initialize"),
    N("after_initialize", "prolog", 1, "after_initialize"),

    N("before_execute", "flow", 0, "before_execute", "each cycle"),
    N("before_update", "flow", 1, "before_update"),
    N("override_update", "flow", 2, "override_update", "override"),
    N("before_lookup", "flow", 3, "before_lookup"),
    N("after_lookup", "flow", 4, "after_lookup"),
    N("after_update", "flow", 5, "after_update"),

    N("before_modify", "modify", 3, "before_modify", "existing entry"),
    N("modify_apply", "modify", 4, "modify_apply", "compute changes"),
    N("after_modify", "modify", 5, "after_modify"),
    N("before_add", "add", 3, "before_add", "no existing entry"),
    N("after_add", "add", 4, "after_add"),

    N("update_ok", "result", 0, "update_ok"),
    N("default_ok", "result", 1, "default_ok", "next cycle"),

    N("initialize_fail", "prolog", 2, "initialize_fail"),
    N("update_multiple", "errors", 3, "update_multiple", "many matches", type="external"),
    N("modify_nochange", "errors", 4, "modify_nochange", "no delta", type="external"),
    N("add_abandon", "errors", 2, "add_abandon", "skip add", type="external"),
    N("update_fail", "errors", 1, "update_fail"),
    N("default_fail", "errors", 5, "default_fail"),
    N("close_fail", "result", 4, "close_fail"),
] + [N("before_close", "result", 2, "before_close"),
     N("after_close", "result", 3, "after_close")]
edges = [
    E("before_initialize", "after_initialize"),
    E("after_initialize", "before_execute", "enter loop", "emphasis"),
    E("before_execute", "before_update"),
    E("before_update", "override_update"),
    E("override_update", "before_lookup"),
    E("before_lookup", "after_lookup"),
    E("after_lookup", "before_modify", "entry exists", "emphasis", "branch"),
    E("before_modify", "modify_apply"),
    E("modify_apply", "after_modify"),
    E("after_modify", "after_update"),
    E("after_lookup", "before_add", "no entry", "dashed", "branch"),
    E("before_add", "after_add"),
    E("after_add", "after_update"),
    E("after_update", "update_ok"),
    E("update_ok", "default_ok"),
    E("default_ok", "before_execute", "next cycle", "dashed", "return"),
    E("before_lookup", "update_multiple", "many", "dashed", "branch"),
    E("before_modify", "modify_nochange", "no delta", "dashed", "branch"),
    E("before_add", "add_abandon", "abandon", "dashed", "branch"),
    E("update_ok", "update_fail", "on error", "security", "error"),
    E("update_fail", "default_fail", None, "security", "error"),
    E("before_initialize", "initialize_fail", "on error", "security", "error"),
    E("default_ok", "before_close", "on close", "dashed", "branch"),
    E("before_close", "after_close"),
    E("after_close", "close_fail", "on error", "security", "error"),
]
diagrams.append(build(
    "Update Mode Hook Flow", "docs/hook-flow/update.html",
    [LANE_PROLOG, {"id": "flow", "label": "Data Flow - Update"},
     {"id": "modify", "label": "Modify branch"},
     {"id": "add", "label": "Add branch"},
     {"id": "result", "label": "Result / Close"}, LANE_ERRORS],
    [{"id": "init", "label": "Initialize", "fromCol": 0, "toCol": 1},
     {"id": "loop", "label": "Update loop", "fromCol": 2, "toCol": 5, "variant": "emphasis"}],
    nodes, edges,
    ["before_execute", "before_update", "override_update", "before_lookup", "after_lookup"],
    [source_card("updateLoop"),
     {"dot": "rose", "title": "Two branches", "items": [
         "Entry found -> Modify (before_modify / modify_apply / after_modify)",
         "Not found -> Add (before_add / after_add)"]}],
))

# ----------------------------------------------------------------------------
# 5. DELTA mode  (initializeHooks + deltaLoop + closeHooks + reconnect)
#    deltaLoop wraps modify, add and delete branches.
# ----------------------------------------------------------------------------
nodes = [
    N("before_initialize", "prolog", 0, "before_initialize"),
    N("after_initialize", "prolog", 1, "after_initialize"),

    N("before_execute", "flow", 0, "before_execute", "each cycle"),
    N("before_delta", "flow", 1, "before_delta"),
    N("override_delta", "flow", 2, "override_delta", "override"),
    N("before_lookup", "flow", 3, "before_lookup"),
    N("after_lookup", "flow", 4, "after_lookup"),
    N("after_delta", "flow", 5, "after_delta"),

    N("before_modify", "modify", 3, "before_modify", "changed"),
    N("after_modify", "modify", 4, "after_modify"),
    N("before_add", "add", 3, "before_add", "new"),
    N("after_add", "add", 4, "after_add"),
    N("before_delete", "delete", 3, "before_delete", "removed"),
    N("after_delete", "delete", 4, "after_delete"),

    N("delta_ok", "result", 0, "delta_ok"),
    N("default_ok", "result", 1, "default_ok", "next cycle"),

    N("initialize_fail", "prolog", 2, "initialize_fail"),
    N("lookup_multiple", "errors", 2, "lookup_multiple", "many", type="external"),
    N("lookup_nomatch", "errors", 3, "lookup_nomatch", "none", type="external"),
    N("modify_nochange", "errors", 4, "modify_nochange", "no delta", type="external"),
    N("delta_fail", "errors", 1, "delta_fail"),
    N("default_fail", "errors", 5, "default_fail"),
    N("close_fail", "result", 4, "close_fail"),
] + [N("before_close", "result", 2, "before_close"),
     N("after_close", "result", 3, "after_close")]
edges = [
    E("before_initialize", "after_initialize"),
    E("after_initialize", "before_execute", "enter loop", "emphasis"),
    E("before_execute", "before_delta"),
    E("before_delta", "override_delta"),
    E("override_delta", "before_lookup"),
    E("before_lookup", "after_lookup"),
    E("after_lookup", "before_modify", "modify", "emphasis", "branch"),
    E("before_modify", "after_modify"),
    E("after_modify", "after_delta"),
    E("after_lookup", "before_add", "add", "dashed", "branch"),
    E("before_add", "after_add"),
    E("after_add", "after_delta"),
    E("after_lookup", "before_delete", "delete", "dashed", "branch"),
    E("before_delete", "after_delete"),
    E("after_delete", "after_delta"),
    E("after_delta", "delta_ok"),
    E("delta_ok", "default_ok"),
    E("default_ok", "before_execute", "next cycle", "dashed", "return"),
    E("before_lookup", "lookup_multiple", "many", "dashed", "branch"),
    E("before_lookup", "lookup_nomatch", "none", "dashed", "branch"),
    E("before_modify", "modify_nochange", "no delta", "dashed", "branch"),
    E("delta_ok", "delta_fail", "on error", "security", "error"),
    E("delta_fail", "default_fail", None, "security", "error"),
    E("before_initialize", "initialize_fail", "on error", "security", "error"),
    E("default_ok", "before_close", "on close", "dashed", "branch"),
    E("before_close", "after_close"),
    E("after_close", "close_fail", "on error", "security", "error"),
]
diagrams.append(build(
    "Delta Mode Hook Flow", "docs/hook-flow/delta.html",
    [LANE_PROLOG, {"id": "flow", "label": "Data Flow - Delta"},
     {"id": "modify", "label": "Modify branch"},
     {"id": "add", "label": "Add branch"},
     {"id": "delete", "label": "Delete branch"},
     {"id": "result", "label": "Result / Close"}, LANE_ERRORS],
    [{"id": "init", "label": "Initialize", "fromCol": 0, "toCol": 1},
     {"id": "loop", "label": "Delta loop", "fromCol": 2, "toCol": 5, "variant": "emphasis"}],
    nodes, edges,
    ["before_execute", "before_delta", "override_delta", "before_lookup", "after_lookup"],
    [source_card("deltaLoop"),
     {"dot": "rose", "title": "Three branches", "items": [
         "Delta type drives Modify, Add or Delete",
         "Each branch re-joins at after_delta"]}],
))

# ----------------------------------------------------------------------------
# 6. CALL/REPLY mode  (initializeHooks + callreplyLoop + closeHooks + reconnect)
# ----------------------------------------------------------------------------
nodes = [
    N("before_initialize", "prolog", 0, "before_initialize"),
    N("after_initialize", "prolog", 1, "after_initialize"),

    N("before_execute", "flow", 0, "before_execute", "each cycle"),
    N("override_callreply", "flow", 1, "override_callreply", "override"),
    N("output_attribute_map", "flow", 2, "output_attribute_map", "build call"),
    N("before_call", "flow", 3, "before_call", "send request"),
    N("after_reply", "flow", 4, "after_reply", "response in"),
    N("input_attribute_map", "flow", 5, "input_attribute_map", "map reply"),

    N("callreply_ok", "result", 4, "callreply_ok"),
    N("default_ok", "result", 5, "default_ok", "next cycle"),

    N("initialize_fail", "prolog", 2, "initialize_fail"),
    N("no_reply", "errors", 2, "no_reply", "no response"),
    N("callreply_fail", "errors", 3, "callreply_fail"),
    N("default_fail", "errors", 4, "default_fail"),
    N("close_fail", "errors", 5, "close_fail"),
    N("reconnect", "errors", 1, "Reconnect flow", "on conn failure", "shared", "security"),
] + close_nodes()
edges = [
    E("before_initialize", "after_initialize"),
    E("after_initialize", "before_execute", "enter loop", "emphasis"),
    E("before_execute", "override_callreply"),
    E("override_callreply", "output_attribute_map"),
    E("output_attribute_map", "before_call"),
    E("before_call", "after_reply", "call / reply", "emphasis"),
    E("after_reply", "input_attribute_map"),
    E("input_attribute_map", "callreply_ok"),
    E("callreply_ok", "default_ok"),
    E("default_ok", "before_execute", "next cycle", "dashed", "return"),
    E("before_call", "no_reply", "no reply", "dashed", "branch"),
    E("callreply_ok", "callreply_fail", "on error", "security", "error"),
    E("callreply_fail", "default_fail", None, "security", "error"),
    E("before_initialize", "initialize_fail", "on error", "security", "error"),
    E("default_ok", "before_close", "on close", "dashed", "branch"),
] + close_edges()
diagrams.append(build(
    "Call/Reply Mode Hook Flow", "docs/hook-flow/callreply.html",
    [LANE_PROLOG, {"id": "flow", "label": "Data Flow - Call / Reply"},
     {"id": "result", "label": "Data Flow - Result"}, LANE_EPILOG, LANE_ERRORS],
    [{"id": "init", "label": "Initialize", "fromCol": 0, "toCol": 1},
     {"id": "loop", "label": "Call/Reply loop", "fromCol": 2, "toCol": 5, "variant": "emphasis"}],
    nodes, edges,
    ["before_execute", "override_callreply", "output_attribute_map", "before_call", "after_reply", "input_attribute_map"],
    [source_card("callreplyLoop"),
     {"dot": "rose", "title": "Request / response", "items": [
         "output_attribute_map builds the outbound request",
         "no_reply fires when the call returns nothing"]}],
))

# ----------------------------------------------------------------------------
# 7. FUNCTION component mode  (initializeHooks + functionLoop + closeHooks)
#    No reconnect hooks for function components.
# ----------------------------------------------------------------------------
nodes = [
    N("before_initialize", "prolog", 0, "before_initialize"),
    N("after_initialize", "prolog", 1, "after_initialize"),

    N("before_execute", "flow", 0, "before_execute", "each call"),
    N("output_attribute_map", "flow", 1, "output_attribute_map", "build input"),
    N("before_functioncall", "flow", 2, "before_functioncall"),
    N("after_functioncall", "flow", 3, "after_functioncall"),
    N("input_attribute_map", "flow", 4, "input_attribute_map", "map output"),
    N("functioncall_ok", "flow", 5, "functioncall_ok"),

    N("default_ok", "result", 5, "default_ok", "done"),

    N("initialize_fail", "prolog", 2, "initialize_fail"),
    N("no_reply", "errors", 2, "no_reply", "no output"),
    N("functioncall_fail", "errors", 3, "functioncall_fail"),
    N("default_fail", "errors", 4, "default_fail"),
    N("close_fail", "errors", 5, "close_fail"),
] + close_nodes()
edges = [
    E("before_initialize", "after_initialize"),
    E("after_initialize", "before_execute", "call", "emphasis"),
    E("before_execute", "output_attribute_map"),
    E("output_attribute_map", "before_functioncall"),
    E("before_functioncall", "after_functioncall", "invoke", "emphasis"),
    E("after_functioncall", "input_attribute_map"),
    E("input_attribute_map", "functioncall_ok"),
    E("functioncall_ok", "default_ok"),
    E("before_functioncall", "no_reply", "no output", "dashed", "branch"),
    E("functioncall_ok", "functioncall_fail", "on error", "security", "error"),
    E("functioncall_fail", "default_fail", None, "security", "error"),
    E("before_initialize", "initialize_fail", "on error", "security", "error"),
    E("default_ok", "before_close", "on close", "dashed", "branch"),
] + close_edges()
diagrams.append(build(
    "Function Component Hook Flow", "docs/hook-flow/function.html",
    [LANE_PROLOG, {"id": "flow", "label": "Data Flow - Function call"},
     {"id": "result", "label": "Result"}, LANE_EPILOG, LANE_ERRORS],
    [{"id": "init", "label": "Initialize", "fromCol": 0, "toCol": 1},
     {"id": "call", "label": "Function call", "fromCol": 2, "toCol": 5, "variant": "emphasis"}],
    nodes, edges,
    ["before_execute", "output_attribute_map", "before_functioncall", "after_functioncall", "input_attribute_map", "functioncall_ok"],
    [source_card("functionLoop"),
     {"dot": "amber", "title": "No reconnect", "items": [
         "Function components have no reconnect hooks",
         "no_reply fires when the call yields no entry"]}],
))

# ----------------------------------------------------------------------------
# 8. REPLY mode  (replyLoop only)
# ----------------------------------------------------------------------------
nodes = [
    N("before_execute_reply", "flow", 0, "before_execute_reply", "each reply"),
    N("override_reply", "flow", 1, "override_reply", "override"),
    N("output_attribute_map", "flow", 2, "output_attribute_map", "build reply"),
    N("before_reply", "flow", 3, "before_reply"),
    N("after_reply2", "flow", 4, "after_reply2", "sent"),
    N("reply_ok", "flow", 5, "reply_ok"),
    N("reply_fail", "errors", 5, "reply_fail"),
]
edges = [
    E("before_execute_reply", "override_reply"),
    E("override_reply", "output_attribute_map"),
    E("output_attribute_map", "before_reply"),
    E("before_reply", "after_reply2", "send reply", "emphasis"),
    E("after_reply2", "reply_ok"),
    E("reply_ok", "reply_fail", "on error", "security", "error"),
]
diagrams.append(build(
    "Reply Mode Hook Flow", "docs/hook-flow/reply.html",
    [{"id": "flow", "label": "Data Flow - Reply"}, LANE_ERRORS],
    [{"id": "reply", "label": "Reply", "fromCol": 0, "toCol": 5, "variant": "emphasis"}],
    nodes, edges,
    ["before_execute_reply", "override_reply", "output_attribute_map", "before_reply", "after_reply2", "reply_ok"],
    [source_card("replyLoop"),
     {"dot": "rose", "title": "Server reply", "items": [
         "Runs on the reply side of a server connector",
         "override_reply can replace the built-in reply"]}],
))

# ----------------------------------------------------------------------------
# 9. SERVER mode  (initializeHooksIterator + serverLoop + iteratorLoop
#    + replyLoop + closeHooks)
# ----------------------------------------------------------------------------
nodes = [
    N("before_initialize", "prolog", 0, "before_initialize"),
    N("before_selectEntries", "prolog", 1, "before_selectEntries"),
    N("after_selectEntries", "prolog", 2, "after_selectEntries"),
    N("after_initialize", "prolog", 3, "after_initialize"),

    N("before_getnextclient", "accept", 0, "before_getnextclient", "wait client"),
    N("after_getnextclient", "accept", 1, "after_getnextclient", "client in"),

    N("before_execute", "read", 2, "before_execute"),
    N("before_getnext", "read", 3, "before_getnext", "read request"),
    N("after_getnext", "read", 4, "after_getnext"),
    N("get_ok", "read", 5, "get_ok"),

    N("before_reply", "reply", 3, "before_reply", "build reply"),
    N("after_reply2", "reply", 4, "after_reply2"),
    N("reply_ok", "reply", 5, "reply_ok"),

    N("initialize_fail", "errors", 0, "initialize_fail"),
    N("getnextclient_fail", "errors", 1, "getnextclient_fail"),
    N("get_fail", "errors", 5, "get_fail"),
    N("reply_fail", "errors", 4, "reply_fail"),
    N("close_fail", "errors", 3, "close_fail"),
] + [N("before_close", "epilog", 1, "before_close"),
     N("after_close", "epilog", 2, "after_close")]
edges = [
    E("before_initialize", "before_selectEntries"),
    E("before_selectEntries", "after_selectEntries"),
    E("after_selectEntries", "after_initialize"),
    E("after_initialize", "before_getnextclient", "listen", "emphasis"),
    E("before_getnextclient", "after_getnextclient", "accept", "emphasis"),
    E("after_getnextclient", "before_execute"),
    E("before_execute", "before_getnext"),
    E("before_getnext", "after_getnext", "read", "emphasis"),
    E("after_getnext", "get_ok"),
    E("get_ok", "before_reply", "handle", "emphasis"),
    E("before_reply", "after_reply2", "reply", "emphasis"),
    E("after_reply2", "reply_ok"),
    E("reply_ok", "before_getnextclient", "next client", "dashed", "return"),
    E("before_getnextclient", "getnextclient_fail", "on error", "security", "error"),
    E("get_ok", "get_fail", "on error", "security", "error"),
    E("reply_ok", "reply_fail", "on error", "security", "error"),
    E("before_initialize", "initialize_fail", "on error", "security", "error"),
    E("after_getnextclient", "before_close", "shutdown", "dashed", "branch"),
    E("before_close", "after_close"),
    E("after_close", "close_fail", "on error", "security", "error"),
]
diagrams.append(build(
    "Server Mode Hook Flow", "docs/hook-flow/server.html",
    [LANE_PROLOG, {"id": "accept", "label": "Accept client"},
     {"id": "read", "label": "Read request (iterator)"},
     {"id": "reply", "label": "Send reply"}, LANE_EPILOG, LANE_ERRORS],
    [{"id": "init", "label": "Initialize", "fromCol": 0, "toCol": 1},
     {"id": "serve", "label": "Serve clients", "fromCol": 2, "toCol": 5, "variant": "emphasis"}],
    nodes, edges,
    ["before_getnextclient", "after_getnextclient", "before_execute", "before_getnext", "after_getnext", "get_ok"],
    [source_card("serverMode"),
     {"dot": "violet", "title": "Composite", "items": [
         "Server mode combines client-accept, iterator read and reply loops",
         "Read/reply hooks mirror Iterator and Reply modes"]}],
))

# ----------------------------------------------------------------------------
# 10. ASSEMBLY LINE level  (HookTree.mainAssemblyLine)
# ----------------------------------------------------------------------------
nodes = [
    N("prolog_init", "al", 0, "Prolog - Before Init", "al_prolog_init"),
    N("prolog", "al", 1, "Prolog - After Init", "al_prolog"),
    N("startcycle", "al", 2, "Prolog - Startup", "al_startcycle"),
    N("flow", "al", 3, "AL flow", "connectors run / repeats", "per cycle", "database"),
    N("epilog", "al", 4, "Epilog", "al_epilog"),
    N("epilog2", "al", 5, "Epilog - After Flow", "al_epilog2"),

    N("onsuccess", "term", 4, "On Success", "al_onsuccess"),
    N("onfailure", "term", 3, "On Failure", "al_onfailure", type="security"),
    N("shutdown", "term", 5, "Shutdown", "al_shutdown"),
]
edges = [
    E("prolog_init", "prolog"),
    E("prolog", "startcycle"),
    E("startcycle", "flow", "start", "emphasis"),
    E("flow", "epilog", "cycles done", "emphasis"),
    E("epilog", "epilog2"),
    E("epilog2", "onsuccess", "ok", "emphasis", "branch"),
    E("flow", "onfailure", "error", "security", "error"),
    E("onsuccess", "shutdown"),
    E("onfailure", "shutdown", None, "security", "error"),
]
diagrams.append(build(
    "AssemblyLine Hook Flow", "docs/hook-flow/assemblyline.html",
    [{"id": "al", "label": "AssemblyLine lifecycle"},
     {"id": "term", "label": "Result & shutdown", "variant": "exception"}],
    [{"id": "prologp", "label": "Prolog", "fromCol": 0, "toCol": 2},
     {"id": "flowp", "label": "Flow", "fromCol": 3, "toCol": 3, "variant": "emphasis"},
     {"id": "epilogp", "label": "Epilog", "fromCol": 4, "toCol": 5}],
    nodes, edges,
    ["prolog_init", "prolog", "startcycle", "flow", "epilog", "epilog2"],
    [{"dot": "cyan", "title": "Source", "items": [
        "Hooks from HookTree.mainAssemblyLine",
        "Wraps every connector's own hook flow"]},
     {"dot": "rose", "title": "Per-cycle", "items": [
         "The Flow drives connector hook trees each cycle",
         "On Success / On Failure run once at the end"]}],
))

# ----------------------------------------------------------------------------
# 11. RECONNECT sub-flow  (HookTree.reconnectHooks) - shared by connector modes
# ----------------------------------------------------------------------------
nodes = [
    N("connect_init", "conn", 0, "connect_init", "open connection"),
    N("on_connection_failure", "conn", 1, "on_connection_failure", "detected drop", type="security"),

    N("reconnect_ok", "reconnect", 2, "reconnect_ok", "same server"),
    N("reconnect_fail", "reconnect", 3, "reconnect_fail", type="security"),

    N("failover_ok", "failover", 2, "failover_ok", "next server"),
    N("failover_fail", "failover", 3, "failover_fail", type="security"),

    N("failback_ok", "failback", 4, "failback_ok", "primary back"),
    N("failback_fail", "failback", 5, "failback_fail", type="security"),
]
edges = [
    E("connect_init", "on_connection_failure", "connection lost", "security", "error"),
    E("on_connection_failure", "reconnect_ok", "retry same", "emphasis", "branch"),
    E("on_connection_failure", "reconnect_fail", "retry failed", "dashed", "branch"),
    E("reconnect_fail", "failover_ok", "try next", "emphasis", "branch"),
    E("reconnect_fail", "failover_fail", "no server", "security", "error"),
    E("failover_ok", "failback_ok", "primary recovers", "dashed", "branch"),
    E("failback_ok", "connect_init", "resume", "dashed", "return"),
    E("failback_ok", "failback_fail", "failback error", "security", "error"),
]
diagrams.append(build(
    "Reconnect & Failover Hook Flow", "docs/hook-flow/reconnect.html",
    [{"id": "conn", "label": "Connection"},
     {"id": "reconnect", "label": "Reconnect (same host)"},
     {"id": "failover", "label": "Failover (next host)"},
     {"id": "failback", "label": "Failback (primary)"}],
    [{"id": "c", "label": "Connect", "fromCol": 0, "toCol": 1},
     {"id": "r", "label": "Recover", "fromCol": 2, "toCol": 3, "variant": "emphasis"},
     {"id": "b", "label": "Failback", "fromCol": 4, "toCol": 5}],
    nodes, edges,
    ["connect_init", "on_connection_failure", "reconnect_ok"],
    [{"dot": "cyan", "title": "Source", "items": [
        "Hooks from HookTree.reconnectHooks",
        "Shared by Iterator, Lookup, AddOnly, Delete, Update, Delta, Call/Reply"]},
     {"dot": "amber", "title": "Escalation", "items": [
         "reconnect (same) -> failover (next) -> failback (primary)",
         "Each stage has an _ok and _fail hook"]}],
))

# ----------------------------------------------------------------------------
# 12. ITERATOR mode  (initializeHooksIterator + iteratorLoop + closeHooks
#     + reconnectHooks)
# ----------------------------------------------------------------------------
# Iterator's prolog carries four long-labelled init hooks; at the generous
# default width the diagram gets so wide it downscales text below the readable
# floor at 1440px. Scale its node widths down so text stays legible.
WSCALE = 0.78
nodes = [
    N("before_initialize", "prolog", 0, "before_initialize"),
    N("before_selectEntries", "prolog", 1, "before_selectEntries", "iterator only"),
    N("after_selectEntries", "prolog", 2, "after_selectEntries"),
    N("after_initialize", "prolog", 3, "after_initialize"),

    N("before_execute", "flow", 0, "before_execute", "each cycle"),
    N("override_getnext", "flow", 1, "override_getnext", "override read"),
    N("getnext", "flow", 2, "getnext", "before / after_getnext"),
    N("input_attribute_map", "flow", 3, "input_attribute_map", "map entry"),
    N("get_ok", "flow", 4, "get_ok"),
    N("default_ok", "flow", 5, "default_ok", "next cycle"),

    N("before_close", "epilog", 4, "before_close"),
    N("after_close", "epilog", 5, "after_close"),

    N("initialize_fail", "errors", 0, "initialize_fail"),
    N("reconnect", "errors", 1, "Reconnect flow", "on conn failure", "shared", "security"),
    N("end_of_data", "errors", 2, "end_of_data", "no more entries", type="external"),
    N("get_fail", "errors", 3, "get_fail"),
    N("default_fail", "errors", 4, "default_fail"),
    N("close_fail", "errors", 5, "close_fail"),
]
edges = [
    E("before_initialize", "before_selectEntries"),
    E("before_selectEntries", "after_selectEntries"),
    E("after_selectEntries", "after_initialize"),
    E("after_initialize", "before_execute", "enter loop", "emphasis"),
    E("before_execute", "override_getnext"),
    E("override_getnext", "getnext"),
    E("getnext", "input_attribute_map"),
    E("input_attribute_map", "get_ok", "entry", "emphasis"),
    E("get_ok", "default_ok"),
    E("default_ok", "before_execute", "next cycle", "dashed", "return"),
    E("getnext", "end_of_data", "no entry", "dashed", "branch"),
    E("end_of_data", "before_close", "exit loop", "emphasis", "branch"),
    E("get_ok", "get_fail", "on error", "security", "error"),
    E("get_fail", "default_fail", None, "security", "error"),
    E("before_initialize", "initialize_fail", "on error", "security", "error"),
    E("before_close", "after_close"),
    E("after_close", "close_fail", "on error", "security", "error"),
]
diagrams.append(build(
    "Iterator Mode Hook Flow", "docs/hook-flow/iterator.html",
    [LANE_PROLOG, {"id": "flow", "label": "Data Flow - Get Next"},
     LANE_EPILOG, LANE_ERRORS],
    [{"id": "init", "label": "Initialize", "fromCol": 0, "toCol": 1},
     {"id": "loop", "label": "Iterator loop", "fromCol": 2, "toCol": 4, "variant": "emphasis"},
     {"id": "close", "label": "Close", "fromCol": 5, "toCol": 5}],
    nodes, edges,
    ["before_execute", "override_getnext", "getnext", "input_attribute_map", "get_ok", "default_ok"],
    [source_card("iteratorMode"),
     {"dot": "rose", "title": "Loop", "items": [
         "default_ok returns for the next entry",
         "end_of_data exits the loop to Close"]}],
))

# ----------------------------------------------------------------------------
for d in diagrams:
    name = d["meta"]["output"].split("/")[-1].replace(".html", ".workflow.json")
    path = os.path.join(OUT_DIR, name)
    with open(path, "w") as f:
        json.dump(d, f, indent=2)
    print("wrote", name, "-", len(d["nodes"]), "nodes,", len(d["edges"]), "edges")
