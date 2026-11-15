/*
 * Layered graph renderer shared by the model graph (Administration) and the asset dependency graph
 * (Assets). Self-contained: no external libraries, drawn to SVG.
 *
 * Both of those graphs are hierarchies with a few cross links — a type under the traits it carries,
 * an asset under the assets it depends on — so nodes are laid out in rows rather than left to a
 * force simulation: an edge the caller marks as `ranked` puts its target in a row above its source.
 * The result is the same on every load, which a simulation never was, and reads top-down.
 *
 * A node is a card (title, subtitle, badges) rather than a dot with a label, an edge is a curve
 * between card borders, and the canvas pans, zooms and fits itself to the graph. Hovering a node
 * dims everything it is not connected to. Clicking a node or an edge selects it and tells the
 * caller, which is how each page fills its own detail panel.
 *
 * Dropping one node onto another is offered to the caller as a `drop` hook: while a node is being
 * dragged, the nodes it may be dropped on are marked, the one under the pointer is highlighted as
 * accepting or refusing, and a release over it calls back instead of moving the node. A node
 * dropped on empty canvas is simply moved there. The asset graph uses this to create a dependency;
 * the model graph does not pass the hook and only moves nodes.
 *
 *   var graph = MetacatalogGraphCore.create(canvasElement, {
 *     nodes: [{ id, title, subtitle, kind, color, badges: [{ text, tone }], data }],
 *     edges: [{ source, target, kind, label, data }],
 *     ranked: function (edge) { return true; },   // does this edge place its target above?
 *     band: function (node) { return 0; },        // coarse top-to-bottom grouping, optional
 *     onSelect: function (selection) {},          // { node } | { edge } | null
 *     onContextMenu: function (at) {},            // { clientX, clientY, node } on a right-click
 *     drop: { allowed(source, target), hint(source, target), onDrop(source, target) }
 *   });
 *   graph.setData(nodes, edges); graph.fit(); graph.relayout(); graph.setFilter(fn);
 *   graph.setHiddenEdgeKinds(set); graph.select(nodeId); graph.destroy();
 */
(function () {
  "use strict";

  var SVG_NS = "http://www.w3.org/2000/svg";

  var NODE_H = 46;
  var NODE_MIN_W = 120;
  var NODE_MAX_W = 300;
  var PAD_X = 14;
  var ACCENT_W = 5;
  var COL_GAP = 34;
  var ROW_GAP = 86;
  var MAX_ROW_W = 1500;
  var BADGE_H = 16;
  var DRAG_THRESHOLD = 4;

  function svg(tag, attrs) {
    var node = document.createElementNS(SVG_NS, tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        node.setAttribute(k, attrs[k]);
      });
    }
    return node;
  }

  // Text is measured on a canvas, not in the SVG: an SVG inside a hidden tab reports every length
  // as zero, and the catalog renders its graph while the list view is the one showing.
  var measureCtx = null;
  function textWidth(text, font) {
    if (!text) return 0;
    if (!measureCtx) {
      var c = document.createElement("canvas");
      measureCtx = c.getContext ? c.getContext("2d") : null;
    }
    if (!measureCtx) return text.length * 7;
    measureCtx.font = font;
    return measureCtx.measureText(text).width;
  }

  var TITLE_FONT = "600 13px system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif";
  var SUB_FONT = "11px system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif";
  var BADGE_FONT = "600 10px system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif";

  function ellipsize(text, font, max) {
    if (textWidth(text, font) <= max) return text;
    var t = text;
    while (t.length > 1 && textWidth(t + "…", font) > max) t = t.slice(0, -1);
    return t + "…";
  }

  function create(canvas, options) {
    var opts = options || {};
    var ranked = opts.ranked || function () { return true; };
    var band = opts.band || function () { return 0; };

    var nodes = [];
    var edges = [];
    var byId = {};
    var view = { x: 0, y: 0, s: 1 };
    var selection = null;
    var hiddenEdgeKinds = {};
    var filter = null;
    var destroyed = false;

    canvas.innerHTML = "";
    var root = svg("svg", { class: "gc-svg", width: "100%", height: "100%", role: "img" });
    root.setAttribute("aria-label", opts.label || "Graph");
    var defs = svg("defs");
    var markerId = "gc-arrow-" + Math.random().toString(36).slice(2, 8);
    var marker = svg("marker", {
      id: markerId, viewBox: "0 0 10 10", refX: "9", refY: "5",
      markerWidth: "7", markerHeight: "7", orient: "auto-start-reverse"
    });
    marker.appendChild(svg("path", { d: "M 0 0 L 10 5 L 0 10 z", fill: "context-stroke" }));
    defs.appendChild(marker);
    root.appendChild(defs);
    var viewport = svg("g", {});
    var edgeLayer = svg("g", { class: "gc-edges" });
    var labelLayer = svg("g", { class: "gc-edge-labels" });
    var nodeLayer = svg("g", { class: "gc-nodes" });
    viewport.appendChild(edgeLayer);
    viewport.appendChild(labelLayer);
    viewport.appendChild(nodeLayer);
    root.appendChild(viewport);
    canvas.appendChild(root);

    var hint = document.createElement("div");
    hint.className = "gc-hint";
    hint.hidden = true;
    canvas.appendChild(hint);

    // --- model ---------------------------------------------------------------------------------

    function buildNode(n, previous) {
      var title = n.title == null ? n.id : String(n.title);
      var subtitle = n.subtitle == null ? "" : String(n.subtitle);
      var badges = (n.badges || []).filter(function (b) { return b && b.text; });
      var badgesW = 0;
      badges.forEach(function (b) {
        b.w = Math.ceil(textWidth(b.text, BADGE_FONT)) + 12;
        badgesW += b.w + 4;
      });
      var textMax = NODE_MAX_W - 2 * PAD_X - ACCENT_W - badgesW;
      var shownTitle = ellipsize(title, TITLE_FONT, textMax);
      var shownSub = ellipsize(subtitle, SUB_FONT, textMax);
      var textW = Math.max(textWidth(shownTitle, TITLE_FONT), textWidth(shownSub, SUB_FONT));
      var w = Math.max(NODE_MIN_W, Math.ceil(textW + badgesW + 2 * PAD_X + ACCENT_W));
      return {
        id: n.id, title: title, subtitle: subtitle, shownTitle: shownTitle, shownSub: shownSub,
        kind: n.kind || "node", badges: badges, data: n.data, raw: n,
        w: w, h: NODE_H,
        x: previous ? previous.x : 0, y: previous ? previous.y : 0,
        placed: !!previous
      };
    }

    function setData(newNodes, newEdges, keepPositions) {
      var previous = keepPositions ? byId : {};
      nodes = (newNodes || []).map(function (n) { return buildNode(n, previous[n.id]); });
      byId = {};
      nodes.forEach(function (n) { byId[n.id] = n; });
      // Parallel edges between the same two nodes — a relationship and its inverse, say — are
      // fanned out, so that each keeps its own arrow instead of overlapping into one line.
      var pairCount = {};
      edges = (newEdges || [])
        .filter(function (e) { return byId[e.source] && byId[e.target]; })
        .map(function (e, i) {
          var s = byId[e.source], t = byId[e.target];
          var key = s.id < t.id ? s.id + "\u0000" + t.id : t.id + "\u0000" + s.id;
          var index = pairCount[key] || 0;
          pairCount[key] = index + 1;
          return {
            id: "e" + i, s: s, t: t, kind: e.kind || "edge", label: e.label || "",
            data: e.data, raw: e, pairKey: key, pairIndex: index
          };
        });
      edges.forEach(function (e) { e.pairSize = pairCount[e.pairKey]; });
      selection = null;
      var allPlaced = nodes.length > 0 && nodes.every(function (n) { return n.placed; });
      if (!allPlaced) layout();
      render();
    }

    // --- layout --------------------------------------------------------------------------------

    function layout() {
      // Row within a band: 0 for a node no ranked edge leaves, otherwise one below the lowest of
      // the nodes it points at. Only edges inside a band rank; an edge crossing bands is already
      // ordered by the bands themselves. The `visiting` guard makes a cycle harmless.
      var bandOf = {};
      nodes.forEach(function (n) { bandOf[n.id] = band(n.raw) || 0; });
      var up = {};
      nodes.forEach(function (n) { up[n.id] = []; });
      edges.forEach(function (e) {
        if (e.s === e.t || bandOf[e.s.id] !== bandOf[e.t.id] || !ranked(e.raw)) return;
        up[e.s.id].push(e.t.id);
      });
      var rank = {}, visiting = {};
      function rankOf(id) {
        if (rank[id] != null) return rank[id];
        if (visiting[id]) return 0;
        visiting[id] = true;
        var highest = -1;
        up[id].forEach(function (t) { highest = Math.max(highest, rankOf(t)); });
        visiting[id] = false;
        rank[id] = highest + 1;
        return rank[id];
      }
      nodes.forEach(function (n) { rankOf(n.id); });

      var bands = {};
      nodes.forEach(function (n) {
        var b = bandOf[n.id];
        bands[b] = bands[b] || [];
        var r = rank[n.id];
        bands[b][r] = bands[b][r] || [];
        bands[b][r].push(n);
      });
      var rows = [];
      Object.keys(bands)
        .map(Number)
        .sort(function (a, b) { return a - b; })
        .forEach(function (b) {
          bands[b].forEach(function (row) { if (row && row.length) rows.push(row); });
        });

      // Order each row by where its neighbours in the rows already ordered sit, which is what
      // keeps edges from crossing more than they must; one pass down, one back up.
      var neighbours = {};
      nodes.forEach(function (n) { neighbours[n.id] = []; });
      edges.forEach(function (e) {
        if (e.s === e.t) return;
        neighbours[e.s.id].push(e.t);
        neighbours[e.t.id].push(e.s);
      });
      var slot = {};
      function assignSlots(row) {
        row.forEach(function (n, i) { slot[n.id] = (i + 0.5) / row.length; });
      }
      function byName(a, b) {
        return a.kind === b.kind ? a.title.localeCompare(b.title) : a.kind.localeCompare(b.kind);
      }
      function reorder(row) {
        row.forEach(function (n) {
          // Neighbours in other rows only: a node's own row is being ordered, so where its
          // row-mates currently sit says nothing about where it should go.
          var placed = neighbours[n.id].filter(function (m) {
            return slot[m.id] != null && row.indexOf(m) === -1;
          });
          // A node with no neighbour to follow stays where it is, rather than being pushed to
          // one end: sending the unconnected to the right dragged every connected node left,
          // away from the node it is connected to.
          n.bary = placed.length
            ? placed.reduce(function (sum, m) { return sum + slot[m.id]; }, 0) / placed.length
            : slot[n.id];
        });
        row.sort(function (a, b) { return a.bary - b.bary || byName(a, b); });
        assignSlots(row);
      }
      rows.forEach(function (row) {
        row.sort(byName);
        assignSlots(row);
      });
      rows.forEach(reorder);
      for (var i = rows.length - 2; i >= 0; i--) reorder(rows[i]);

      // A row wider than the canvas could sensibly show is folded onto further lines.
      var y = 0;
      rows.forEach(function (row) {
        var lines = [[]];
        var width = 0;
        row.forEach(function (n) {
          if (width > 0 && width + n.w > MAX_ROW_W) {
            lines.push([]);
            width = 0;
          }
          lines[lines.length - 1].push(n);
          width += n.w + COL_GAP;
        });
        lines.forEach(function (line, lineIndex) {
          var total = line.reduce(function (sum, n) { return sum + n.w; }, 0) +
            COL_GAP * (line.length - 1);
          var x = -total / 2;
          line.forEach(function (n) {
            n.x = x + n.w / 2;
            n.y = y;
            n.placed = true;
            x += n.w + COL_GAP;
          });
          y += NODE_H + (lineIndex === lines.length - 1 ? ROW_GAP : 22);
        });
      });
      fit();
    }

    // --- geometry ------------------------------------------------------------------------------

    function edgeGeometry(e) {
      var s = e.s, t = e.t;
      // Parallel edges are spread around the centre line.
      var spread = (e.pairIndex - (e.pairSize - 1) / 2) * 22;
      if (s === t) {
        var lx = s.x + s.w / 2, ly = s.y;
        var r = 22 + e.pairIndex * 10;
        return {
          path: "M " + lx + " " + (ly - 8) + " C " + (lx + r * 2) + " " + (ly - r * 1.6) + ", " +
            (lx + r * 2) + " " + (ly + r * 1.6) + ", " + (lx + 2) + " " + (ly + 8),
          lx: lx + r * 1.55, ly: ly
        };
      }
      var dy = t.y - s.y;
      if (Math.abs(dy) > (s.h + t.h) / 2 + 6) {
        // Different rows: leave through the top or bottom border and arrive the same way.
        var dir = dy < 0 ? -1 : 1;
        var x1 = s.x + spread, y1 = s.y + dir * s.h / 2;
        var x2 = t.x + spread, y2 = t.y - dir * (t.h / 2 + 2);
        var bend = Math.max(26, Math.abs(y2 - y1) / 2);
        var c1y = y1 + dir * bend, c2y = y2 - dir * bend;
        return {
          path: "M " + x1 + " " + y1 + " C " + x1 + " " + c1y + ", " + x2 + " " + c2y + ", " +
            x2 + " " + y2,
          lx: (x1 + x2) / 2,
          // The labels of parallel edges are stacked, or a relationship and its inverse would
          // print over each other.
          ly: 0.125 * y1 + 0.375 * c1y + 0.375 * c2y + 0.125 * y2 + spread * 0.55
        };
      }
      // Same row: side to side, arching over whatever sits between the two.
      var side = t.x < s.x ? -1 : 1;
      var ax = s.x + side * s.w / 2, ay = s.y + spread * 0.4;
      var bx = t.x - side * (t.w / 2 + 2), by = t.y + spread * 0.4;
      // Each parallel edge arches higher than the one before, which also keeps their labels apart.
      var lift = 30 + Math.abs(bx - ax) * 0.12 + e.pairIndex * 18;
      var cy = Math.min(ay, by) - lift;
      return {
        path: "M " + ax + " " + ay + " C " + ax + " " + cy + ", " + bx + " " + cy + ", " +
          bx + " " + by,
        lx: (ax + bx) / 2,
        ly: 0.25 * ay + 0.75 * cy + 4
      };
    }

    // --- rendering -----------------------------------------------------------------------------

    function render() {
      edgeLayer.textContent = "";
      labelLayer.textContent = "";
      nodeLayer.textContent = "";

      edges.forEach(function (e) {
        e.line = svg("path", {
          class: "gc-edge gc-edge-" + e.kind, fill: "none", "marker-end": "url(#" + markerId + ")"
        });
        e.hit = svg("path", { class: "gc-edge-hit", fill: "none" });
        var title = svg("title");
        title.textContent = e.s.title + " " + (e.label || e.kind) + " " + e.t.title;
        e.hit.appendChild(title);
        edgeLayer.appendChild(e.line);
        edgeLayer.appendChild(e.hit);
        if (e.label) {
          e.text = svg("text", { class: "gc-edge-label gc-edge-label-" + e.kind, "text-anchor": "middle" });
          e.text.textContent = e.label;
          labelLayer.appendChild(e.text);
        }
        e.hit.addEventListener("click", function (ev) {
          ev.stopPropagation();
          select({ edge: e });
        });
        e.hit.addEventListener("mouseenter", function () { hover(null, e); });
        e.hit.addEventListener("mouseleave", function () { hover(null, null); });
      });

      nodes.forEach(function (n) {
        n.g = svg("g", { class: "gc-node gc-node-" + n.kind, tabindex: "0" });
        n.g.setAttribute("data-node-id", n.id);
        n.g.setAttribute("role", "button");
        n.g.setAttribute("aria-label", n.title + (n.subtitle ? ", " + n.subtitle : ""));
        var left = -n.w / 2, top = -n.h / 2;
        n.g.appendChild(svg("rect", {
          class: "gc-node-card", x: left, y: top, width: n.w, height: n.h, rx: 10, ry: 10
        }));
        var accent = svg("rect", {
          class: "gc-node-accent", x: left, y: top, width: ACCENT_W + 6, height: n.h, rx: 10, ry: 10
        });
        // A node may bring its own accent — the asset graph colors by asset type, which no
        // stylesheet can know in advance; otherwise the kind's color applies.
        if (n.raw.color) accent.style.fill = n.raw.color;
        n.g.appendChild(accent);
        // Squares off the accent's right side, which the rounded rect above leaves rounded.
        n.g.appendChild(svg("rect", {
          class: "gc-node-card gc-node-accent-mask", x: left + ACCENT_W, y: top + 1,
          width: 8, height: n.h - 2
        }));
        var textX = left + ACCENT_W + PAD_X - 2;
        var titleEl = svg("text", {
          class: "gc-node-title", x: textX, y: n.shownSub ? -3 : 4
        });
        titleEl.textContent = n.shownTitle;
        n.g.appendChild(titleEl);
        if (n.shownSub) {
          var subEl = svg("text", { class: "gc-node-sub", x: textX, y: 13 });
          subEl.textContent = n.shownSub;
          n.g.appendChild(subEl);
        }
        var bx = n.w / 2 - PAD_X + 4;
        n.badges.slice().reverse().forEach(function (b) {
          bx -= b.w;
          var badge = svg("g", { class: "gc-badge gc-badge-" + (b.tone || "plain") });
          badge.appendChild(svg("rect", {
            x: bx, y: -BADGE_H / 2, width: b.w, height: BADGE_H, rx: 8, ry: 8
          }));
          var bt = svg("text", { x: bx + b.w / 2, y: 3.5, "text-anchor": "middle" });
          bt.textContent = b.text;
          badge.appendChild(bt);
          if (b.title) {
            var btitle = svg("title");
            btitle.textContent = b.title;
            badge.appendChild(btitle);
          }
          n.g.appendChild(badge);
          bx -= 4;
        });
        var full = svg("title");
        full.textContent = n.title + (n.subtitle ? " — " + n.subtitle : "");
        n.g.appendChild(full);
        nodeLayer.appendChild(n.g);
        n.g.addEventListener("mouseenter", function () { if (!drag) hover(n, null); });
        n.g.addEventListener("mouseleave", function () { if (!drag) hover(null, null); });
        n.g.addEventListener("keydown", function (ev) {
          if (ev.key === "Enter" || ev.key === " ") {
            ev.preventDefault();
            select({ node: n });
          }
        });
      });
      draw();
      applyState();
    }

    function draw() {
      edges.forEach(function (e) {
        var g = edgeGeometry(e);
        e.line.setAttribute("d", g.path);
        e.hit.setAttribute("d", g.path);
        if (e.text) {
          e.text.setAttribute("x", g.lx);
          e.text.setAttribute("y", g.ly);
        }
      });
      nodes.forEach(function (n) {
        n.g.setAttribute("transform", "translate(" + n.x + "," + n.y + ")");
      });
    }

    function applyView() {
      viewport.setAttribute(
        "transform", "translate(" + view.x + "," + view.y + ") scale(" + view.s + ")");
    }

    function fit() {
      if (!nodes.length) return;
      var minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
      nodes.forEach(function (n) {
        minX = Math.min(minX, n.x - n.w / 2);
        maxX = Math.max(maxX, n.x + n.w / 2);
        minY = Math.min(minY, n.y - n.h / 2);
        maxY = Math.max(maxY, n.y + n.h / 2);
      });
      var W = canvas.clientWidth || 900, H = canvas.clientHeight || 600;
      var pad = 48;
      var s = Math.min((W - 2 * pad) / Math.max(1, maxX - minX), (H - 2 * pad) / Math.max(1, maxY - minY));
      // Never magnified beyond life size: a three-node graph should not fill the screen.
      s = Math.max(0.15, Math.min(1, s));
      view.s = s;
      view.x = W / 2 - s * (minX + maxX) / 2;
      view.y = H / 2 - s * (minY + maxY) / 2;
      applyView();
    }

    // --- state: selection, hover, filter -------------------------------------------------------

    function edgeVisible(e) {
      return !hiddenEdgeKinds[e.kind];
    }

    function applyState(hoverNode, hoverEdge) {
      var lit = null;
      if (hoverNode) {
        lit = {};
        lit[hoverNode.id] = true;
        edges.forEach(function (e) {
          if (!edgeVisible(e)) return;
          if (e.s === hoverNode) lit[e.t.id] = true;
          if (e.t === hoverNode) lit[e.s.id] = true;
        });
      } else if (hoverEdge) {
        lit = {};
        lit[hoverEdge.s.id] = true;
        lit[hoverEdge.t.id] = true;
      }
      nodes.forEach(function (n) {
        var matches = !filter || filter(n.raw);
        n.g.classList.toggle("gc-dim", (!!lit && !lit[n.id]) || !matches);
        n.g.classList.toggle("gc-selected", !!selection && selection.node === n);
      });
      edges.forEach(function (e) {
        var visible = edgeVisible(e);
        var related = hoverNode ? e.s === hoverNode || e.t === hoverNode : hoverEdge ? e === hoverEdge : true;
        var endsMatch = !filter || (filter(e.s.raw) && filter(e.t.raw));
        var selected = !!selection && selection.edge === e;
        [e.line, e.hit, e.text].forEach(function (el) {
          if (!el) return;
          el.style.display = visible ? "" : "none";
        });
        e.line.classList.toggle("gc-dim", !related || !endsMatch);
        e.line.classList.toggle("gc-lit", (!!lit && related) || selected);
        e.line.classList.toggle("gc-selected", selected);
        if (e.text) {
          e.text.classList.toggle("gc-dim", !related || !endsMatch);
          e.text.classList.toggle("gc-selected", selected);
        }
      });
    }

    function hover(node, edge) {
      applyState(node, edge);
    }

    function select(next, silent) {
      selection = next;
      applyState();
      if (!silent && opts.onSelect) {
        opts.onSelect(
          !next ? null : next.node ? { node: next.node.raw } : { edge: next.edge.raw });
      }
    }

    // --- pointer: pan, zoom, move, drop ---------------------------------------------------------

    var drag = null;

    function graphPoint(ev) {
      var rect = root.getBoundingClientRect();
      return {
        x: (ev.clientX - rect.left - view.x) / view.s,
        y: (ev.clientY - rect.top - view.y) / view.s
      };
    }

    function nodeAt(point, except) {
      for (var i = nodes.length - 1; i >= 0; i--) {
        var n = nodes[i];
        if (n === except) continue;
        if (Math.abs(point.x - n.x) <= n.w / 2 && Math.abs(point.y - n.y) <= n.h / 2) return n;
      }
      return null;
    }

    function showHint(text, tone, ev) {
      if (!text) {
        hint.hidden = true;
        return;
      }
      var rect = canvas.getBoundingClientRect();
      hint.textContent = text;
      hint.className = "gc-hint gc-hint-" + tone;
      hint.hidden = false;
      hint.style.left = Math.min(rect.width - 20, ev.clientX - rect.left + 16) + "px";
      hint.style.top = (ev.clientY - rect.top + 18) + "px";
    }

    function clearDropMarks() {
      nodes.forEach(function (n) {
        n.g.classList.remove("gc-can-drop", "gc-no-drop", "gc-drop-ok", "gc-drop-refused", "gc-dragging");
      });
      root.classList.remove("gc-dropping");
      hint.hidden = true;
    }

    function onPointerDown(ev) {
      if (ev.button != null && ev.button !== 0) return;
      var el = ev.target.closest ? ev.target.closest(".gc-node") : null;
      if (el) {
        var n = byId[el.getAttribute("data-node-id")];
        if (!n) return;
        drag = {
          node: n, x0: ev.clientX, y0: ev.clientY, ox: n.x, oy: n.y,
          grab: null, moved: false, target: null
        };
        var p = graphPoint(ev);
        drag.grab = { dx: n.x - p.x, dy: n.y - p.y };
        ev.preventDefault();
      } else if (!(ev.target.closest && ev.target.closest(".gc-edge-hit"))) {
        drag = { pan: true, x0: ev.clientX - view.x, y0: ev.clientY - view.y, sx: ev.clientX, sy: ev.clientY, moved: false };
      }
    }

    function onPointerMove(ev) {
      if (!drag) return;
      if (drag.pan) {
        if (Math.abs(ev.clientX - drag.sx) + Math.abs(ev.clientY - drag.sy) > DRAG_THRESHOLD) drag.moved = true;
        view.x = ev.clientX - drag.x0;
        view.y = ev.clientY - drag.y0;
        applyView();
        return;
      }
      var n = drag.node;
      if (!drag.moved) {
        if (Math.abs(ev.clientX - drag.x0) + Math.abs(ev.clientY - drag.y0) < DRAG_THRESHOLD) return;
        drag.moved = true;
        hover(null, null);
        n.g.classList.add("gc-dragging");
        // Raised above the others, so the card being carried is never hidden by one it passes.
        nodeLayer.appendChild(n.g);
        if (opts.drop) {
          root.classList.add("gc-dropping");
          nodes.forEach(function (m) {
            if (m === n) return;
            m.g.classList.add(opts.drop.allowed(n.raw, m.raw) ? "gc-can-drop" : "gc-no-drop");
          });
        }
      }
      var p = graphPoint(ev);
      n.x = p.x + drag.grab.dx;
      n.y = p.y + drag.grab.dy;
      draw();
      if (!opts.drop) return;
      var over = nodeAt(p, n);
      if (over !== drag.target) {
        if (drag.target) drag.target.g.classList.remove("gc-drop-ok", "gc-drop-refused");
        drag.target = over;
      }
      if (over) {
        var ok = opts.drop.allowed(n.raw, over.raw);
        over.g.classList.add(ok ? "gc-drop-ok" : "gc-drop-refused");
        showHint(opts.drop.hint ? opts.drop.hint(n.raw, over.raw) : "", ok ? "ok" : "refused", ev);
      } else {
        showHint("", "", ev);
      }
    }

    function onPointerUp(ev) {
      if (!drag) return;
      var d = drag;
      drag = null;
      if (d.pan) {
        if (!d.moved) select(null);
        return;
      }
      var n = d.node;
      if (!d.moved) {
        select({ node: n });
        return;
      }
      var target = d.target;
      clearDropMarks();
      if (opts.drop && target) {
        // A drop on a node is a statement about the two nodes, not a move: the card goes back.
        n.x = d.ox;
        n.y = d.oy;
        draw();
        if (opts.drop.allowed(n.raw, target.raw)) opts.drop.onDrop(n.raw, target.raw);
        else if (opts.drop.onRefused) opts.drop.onRefused(n.raw, target.raw);
      }
      if (ev) hover(null, null);
    }

    function onWheel(ev) {
      ev.preventDefault();
      var rect = root.getBoundingClientRect();
      var mx = ev.clientX - rect.left, my = ev.clientY - rect.top;
      var factor = ev.deltaY < 0 ? 1.1 : 1 / 1.1;
      var ns = Math.max(0.15, Math.min(3, view.s * factor));
      view.x = mx - (mx - view.x) * (ns / view.s);
      view.y = my - (my - view.y) * (ns / view.s);
      view.s = ns;
      applyView();
    }

    // A right-click is handed to the caller, which decides what to offer there — on the board or
    // on a node. Without a hook the browser's own menu is left alone.
    function onContextMenu(ev) {
      if (!opts.onContextMenu) return;
      ev.preventDefault();
      var el = ev.target.closest ? ev.target.closest(".gc-node") : null;
      var n = el ? byId[el.getAttribute("data-node-id")] : null;
      opts.onContextMenu({ clientX: ev.clientX, clientY: ev.clientY, node: n ? n.raw : null });
    }
    root.addEventListener("contextmenu", onContextMenu);
    root.addEventListener("mousedown", onPointerDown);
    window.addEventListener("mousemove", onPointerMove);
    window.addEventListener("mouseup", onPointerUp);
    root.addEventListener("wheel", onWheel, { passive: false });

    function zoomBy(factor) {
      var W = canvas.clientWidth || 900, H = canvas.clientHeight || 600;
      var ns = Math.max(0.15, Math.min(3, view.s * factor));
      view.x = W / 2 - (W / 2 - view.x) * (ns / view.s);
      view.y = H / 2 - (H / 2 - view.y) * (ns / view.s);
      view.s = ns;
      applyView();
    }

    setData(opts.nodes, opts.edges, false);

    return {
      setData: function (n, e, keepPositions) { if (!destroyed) setData(n, e, keepPositions); },
      relayout: function () {
        nodes.forEach(function (n) { n.placed = false; });
        layout();
        draw();
      },
      fit: fit,
      zoomIn: function () { zoomBy(1.2); },
      zoomOut: function () { zoomBy(1 / 1.2); },
      setFilter: function (fn) {
        filter = fn || null;
        applyState();
      },
      setHiddenEdgeKinds: function (kinds) {
        hiddenEdgeKinds = {};
        (kinds || []).forEach(function (k) { hiddenEdgeKinds[k] = true; });
        if (selection && selection.edge && hiddenEdgeKinds[selection.edge.kind]) select(null);
        applyState();
      },
      select: function (nodeId) {
        var n = byId[nodeId];
        select(n ? { node: n } : null);
      },
      clearSelection: function () { select(null, true); },
      nodeCount: function () { return nodes.length; },
      destroy: function () {
        destroyed = true;
        window.removeEventListener("mousemove", onPointerMove);
        window.removeEventListener("mouseup", onPointerUp);
        canvas.innerHTML = "";
      }
    };
  }

  window.MetacatalogGraphCore = { create: create };
})();
