# SPDX-License-Identifier: Apache-2.0
"""Bounded dependency traversal over actual upstream Kconfiglib expressions."""

def causality(conf, library, name, max_nodes=512):
    """Traverse upstream expressions; preserve AND/OR/NOT semantics verbatim.

    Relationships describe evaluated configuration, not a proposed repair or
    proof of assignment origin: loading .config loses fragment precedence.
    Cycles are represented by edges, never recursively expanded.
    """
    if max_nodes < 1 or max_nodes > 4096:
        raise ValueError("Kconfig graph limit must be between 1 and 4096")
    def expression_text(expr):
        def display(item):
            if isinstance(item, library.Symbol) and item.is_constant and item.name not in ("y", "m", "n"):
                return '"<redacted>"'
            return library.standard_sc_expr_str(item)
        return library.expr_str(expr, display)
    pending = [name]
    nodes, edges = {}, set()
    truncated = False
    while pending:
        current = pending.pop(0)
        if current in nodes:
            continue
        if len(nodes) >= max_nodes:
            truncated = True
            break
        symbol = conf.syms[current]
        relations = []
        for attribute in ("direct_dep", "rev_dep", "weak_rev_dep"):
            expression = getattr(symbol, attribute)
            relations.append((attribute, expression))
        for index, entry in enumerate(symbol.defaults):
            # Zephyr adds a source-location third field; upstream may have two.
            value, condition = entry[:2]
            relations.extend([(f"default[{index}].value", value),
                              (f"default[{index}].condition", condition)])
        for index, node in enumerate(symbol.nodes):
            if node.prompt:
                relations.append((f"prompt[{index}]", node.prompt[1]))
        choice = symbol.choice
        if choice is not None:
            relations.append(("choice.depends", choice.direct_dep))
        nodes[current] = {
            "symbol": current, "value": symbol.str_value if symbol.type in (library.BOOL, library.TRISTATE) else "<redacted>",
            "user_value": symbol.user_value if symbol.type in (library.BOOL, library.TRISTATE) else "<redacted>",
            "visibility": library.TRI_TO_STR[symbol.visibility],
            "definitions": sorted(f"{n.filename}:{n.linenr}" for n in symbol.nodes),
            "choice": None if choice is None else {
                "name": choice.name, "value": choice.str_value,
                "selection": choice.selection.name if choice.selection else None},
            "expressions": [{"kind": kind, "expression": expression_text(expr),
                             "value": library.TRI_TO_STR[library.expr_value(expr)]}
                            for kind, expr in relations],
        }
        for kind, expression in relations:
            for item in sorted(library.expr_items(expression),
                               key=lambda x: x.name or ""):
                if isinstance(item, library.Symbol) and not item.is_constant and item.nodes:
                    edges.add((current, kind, item.name))
                    if item.name not in nodes and item.name not in pending:
                        pending.append(item.name)
        pending.sort()
    return {"schema": "zew.kconfig-causality/1", "root": name,
            "coverage": "truncated" if truncated else "reachable-expressions",
            "assignment_origin": "unknown: .config does not preserve fragment precedence",
            "nodes": [nodes[key] for key in sorted(nodes)],
            "edges": [{"from": a, "kind": b, "to": c} for a, b, c in sorted(edges)]}
