/*
 * Catalog graph: a self-contained force-directed renderer (no external libraries).
 *
 * Reads the graph model embedded in #graph-data (nodes = traits + entity types; edges = inheritance,
 * trait membership, trait relationships, and entity-type mappings) and lays it out with a small
 * Fruchterman-Reingold simulation drawn to SVG. Nodes are draggable, the background pans, and the
 * wheel zooms.
 */
(function () {
  "use strict";

  var SVG_NS = "http://www.w3.org/2000/svg";

  var EDGE_STYLE = {
    extends: { color: "#94a3b8", dash: null, label: false },
    "has-trait": { color: "#94a3b8", dash: "4 3", label: false },
    "instance-of": { color: "#94a3b8", dash: "2 4", label: false },
    mapping: { color: "#2563eb", dash: null, label: true },
    // trait relationships (DEPENDS_ON / HAS_PART / MAPPED_TO between traits)
    _rel: { color: "#7c3aed", dash: null, label: true },
  };

  function edgeStyle(kind) {
    return EDGE_STYLE[kind] || EDGE_STYLE._rel;
  }

  function svg(tag, attrs) {
    var node = document.createElementNS(SVG_NS, tag);
    if (attrs) Object.keys(attrs).forEach(function (k) { node.setAttribute(k, attrs[k]); });
    return node;
  }

  // --- Hover popups: full node / mapping detail on mouse-over ------------------------------------

  var popup = null;
  var hideTimer = null;

  function hel(tag, className, text) {
    var e = document.createElement(tag);
    if (className) e.className = className;
    if (text != null) e.textContent = text;
    return e;
  }

  function view() {
    return window.MetacatalogView || null; // read-only tree renderers from schema-view.js
  }

  function ensurePopup() {
    if (popup) return popup;
    popup = hel("div", "sv sv-popup graph-popup");
    popup.style.display = "none";
    popup.addEventListener("mouseenter", cancelHide);
    popup.addEventListener("mouseleave", scheduleHide);
    document.body.appendChild(popup);
    return popup;
  }

  function cancelHide() {
    if (hideTimer) { clearTimeout(hideTimer); hideTimer = null; }
  }

  function scheduleHide() {
    cancelHide();
    hideTimer = setTimeout(function () { if (popup) popup.style.display = "none"; }, 150);
  }

  function hidePopup() {
    cancelHide();
    if (popup) popup.style.display = "none";
  }

  function positionAt(x, y) {
    var p = popup, m = 8, pw = p.offsetWidth || 320, ph = p.offsetHeight || 240;
    var left = x + 14;
    if (left + pw > window.innerWidth - m) left = x - pw - 14;
    if (left < m) left = m;
    var top = y + 14;
    if (top + ph > window.innerHeight - m) top = Math.max(m, window.innerHeight - ph - m);
    p.style.left = left + "px";
    p.style.top = top + "px";
  }

  function section(parent, title) {
    parent.appendChild(hel("div", "graph-popup-h", title));
    var body = hel("div", "sv");
    parent.appendChild(body);
    return body;
  }

  function tree(container, fn, raw, expanded) {
    var v = view();
    if (v && raw) {
      try {
        fn(v, container, JSON.parse(raw), expanded);
        return;
      } catch (e) {
        /* fall through to placeholder */
      }
    }
    container.appendChild(hel("p", "empty", "—"));
  }

  function showNodePopup(n, ev) {
    var p = ensurePopup();
    p.textContent = "";
    if (n.kind === "entity") {
      p.appendChild(hel("div", "sv-popup-title", "Entity"));
      p.appendChild(hel("div", "graph-popup-name", n.label));
      if (n.father) {
        var f = hel("div", "graph-popup-row");
        f.appendChild(hel("span", "graph-popup-k", "instance of"));
        f.appendChild(hel("span", null, n.father));
        p.appendChild(f);
      }
      tree(section(p, "Values"), function (v, c, d, x) { v.mapping(c, d, x); }, n.schema, true);
      p.style.display = "block";
      positionAt(ev.clientX, ev.clientY);
      return;
    }
    p.appendChild(hel("div", "sv-popup-title", n.kind === "entityType" ? "Entity type" : "Trait"));
    p.appendChild(hel("div", "graph-popup-name", n.label));
    if (n.father) {
      var f = hel("div", "graph-popup-row");
      f.appendChild(hel("span", "graph-popup-k", "inherits from"));
      f.appendChild(hel("span", null, n.father));
      p.appendChild(f);
    }
    if (n.traits && n.traits.length) {
      var tr = hel("div", "graph-popup-row");
      tr.appendChild(hel("span", "graph-popup-k", "traits"));
      var tags = hel("span");
      n.traits.forEach(function (t) { tags.appendChild(hel("span", "tag", t)); });
      tr.appendChild(tags);
      p.appendChild(tr);
    }
    tree(section(p, "Schema"), function (v, c, d, x) { v.schema(c, d, x); }, n.schema, true);
    p.style.display = "block";
    positionAt(ev.clientX, ev.clientY);
  }

  function showMappingPopup(e, ev) {
    var p = ensurePopup();
    p.textContent = "";
    p.appendChild(hel("div", "sv-popup-title", "Mapping"));
    p.appendChild(hel("div", "graph-popup-name", e.s.label + " → " + e.t.label));
    tree(section(p, "Mapping values"), function (v, c, d, x) { v.mapping(c, d, x); }, e.mappingValues, true);
    tree(section(p, "Path references"), function (v, c, d) { v.pathRefs(c, d); }, e.entityPathReferences, false);
    p.style.display = "block";
    positionAt(ev.clientX, ev.clientY);
  }

  function run(data, canvas) {
    // Clear any previous render so re-renders (e.g. toggling inverses) start clean.
    canvas.innerHTML = "";

    var nodes = data.nodes.map(function (n) {
      return {
        id: n.id, label: n.label, kind: n.kind,
        father: n.father, traits: n.traits, schema: n.schema,
        x: 0, y: 0, dx: 0, dy: 0, fixed: false,
      };
    });
    var byId = {};
    nodes.forEach(function (n) { byId[n.id] = n; });
    var edges = data.edges
      .filter(function (e) { return byId[e.source] && byId[e.target]; })
      .map(function (e) {
        return {
          s: byId[e.source], t: byId[e.target], kind: e.kind, label: e.label,
          mappingValues: e.mappingValues, entityPathReferences: e.entityPathReferences,
        };
      });

    var W = canvas.clientWidth || 800;
    var H = canvas.clientHeight || 600;
    var cx = W / 2;
    var cy = H / 2;

    // Seed positions on a circle (deterministic, avoids overlap at t=0).
    // `rotate` shifts the starting angle so each re-layout produces a fresh arrangement.
    var seedR = Math.min(W, H) / 2.5;
    function seedPositions(rotate) {
      nodes.forEach(function (n, i) {
        var a = rotate + (2 * Math.PI * i) / Math.max(1, nodes.length);
        n.x = cx + seedR * Math.cos(a);
        n.y = cy + seedR * Math.sin(a);
      });
    }
    seedPositions(0);

    var area = W * H;
    // A larger ideal edge length spreads nodes out, which reduces label and edge overlap.
    var k = 1.05 * Math.sqrt(area / Math.max(1, nodes.length));
    var temp = W / 10;

    // --- SVG scaffold ---
    var root = svg("svg", { class: "graph-svg", width: "100%", height: "100%" });
    var defs = svg("defs");
    var marker = svg("marker", {
      id: "graph-arrow", viewBox: "0 0 12 12", refX: "10", refY: "6",
      markerWidth: "9", markerHeight: "9", orient: "auto-start-reverse",
    });
    marker.appendChild(svg("path", { d: "M 0 0 L 12 6 L 0 12 L 3 6 z", fill: "context-stroke" }));
    defs.appendChild(marker);
    root.appendChild(defs);

    var viewport = svg("g", {});
    root.appendChild(viewport);
    var edgeLayer = svg("g", {});
    var labelLayer = svg("g", {});
    var nodeLayer = svg("g", {});
    viewport.appendChild(edgeLayer);
    viewport.appendChild(labelLayer);
    viewport.appendChild(nodeLayer);
    canvas.appendChild(root);

    // --- Edge geometry: curved quadratic Bezier paths.
    // Curving every edge to the left of its travel direction separates forward / inverse
    // pairs (which travel in opposite directions and so curve to opposite sides), so both
    // arrows stay visible instead of overlapping into one ambiguous bidirectional line.
    var NODE_R = 9;
    var ARROW_GAP = 4;
    var CURVE = 26;
    function edgeGeom(s, t) {
      var dx = t.x - s.x, dy = t.y - s.y;
      var len = Math.sqrt(dx * dx + dy * dy) || 0.01;
      var mx = (s.x + t.x) / 2, my = (s.y + t.y) / 2;
      // Perpendicular unit vector, 90° CCW from the travel direction.
      var px = -dy / len, py = dx / len;
      var cx = mx + px * CURVE, cy = my + py * CURVE;
      // Trim the endpoints to the node boundary + arrow gap, along the curve tangent.
      var trim = NODE_R + ARROW_GAP;
      var sax = s.x - cx, say = s.y - cy;
      var sal = Math.sqrt(sax * sax + say * say) || 0.01;
      var s2x = s.x + (sax / sal) * trim, s2y = s.y + (say / sal) * trim;
      var tax = t.x - cx, tay = t.y - cy;
      var tal = Math.sqrt(tax * tax + tay * tay) || 0.01;
      var t2x = t.x - (tax / tal) * trim, t2y = t.y - (tay / tal) * trim;
      // Label sits at the curve midpoint (t = 0.5 on the quadratic Bezier).
      var lx = 0.25 * s.x + 0.5 * cx + 0.25 * t.x;
      var ly = 0.25 * s.y + 0.5 * cy + 0.25 * t.y;
      return {
        path: "M " + s2x + " " + s2y + " Q " + cx + " " + cy + " " + t2x + " " + t2y,
        lx: lx, ly: ly,
      };
    }

    // Edge elements
    edges.forEach(function (e) {
      var st = edgeStyle(e.kind);
      e.line = svg("path", { class: "graph-edge", stroke: st.color, fill: "none", "marker-end": "url(#graph-arrow)" });
      if (st.dash) e.line.setAttribute("stroke-dasharray", st.dash);
      var title = svg("title");
      title.textContent = e.s.label + " " + e.label + " " + e.t.label;
      e.line.appendChild(title);
      edgeLayer.appendChild(e.line);
      if (st.label) {
        e.text = svg("text", { class: "graph-edge-label", "text-anchor": "middle" });
        e.text.textContent = e.label;
        labelLayer.appendChild(e.text);
      }
      // Mapping edges get a wide invisible hit path and a hover popup with the full mapping.
      if (e.kind === "mapping") {
        e.hit = svg("path", { class: "graph-edge-hit", fill: "none" });
        edgeLayer.appendChild(e.hit);
        e.hit.addEventListener("mouseenter", function (ev) { cancelHide(); showMappingPopup(e, ev); });
        e.hit.addEventListener("mouseleave", scheduleHide);
      }
    });

    // Node elements
    nodes.forEach(function (n) {
      n.g = svg("g", { class: "graph-node graph-node-" + n.kind });
      n.circle = svg("circle", { r: "9", class: "graph-node-dot" });
      n.text = svg("text", { class: "graph-node-label", x: "0", y: "22" });
      n.text.textContent = n.label;
      n.g.appendChild(n.circle);
      n.g.appendChild(n.text);
      nodeLayer.appendChild(n.g);
      n.g.addEventListener("mouseenter", function (ev) { cancelHide(); showNodePopup(n, ev); });
      n.g.addEventListener("mouseleave", scheduleHide);
    });

    // --- Simulation ---
    function step() {
      nodes.forEach(function (n) { n.dx = 0; n.dy = 0; });

      // Repulsion between all pairs.
      for (var i = 0; i < nodes.length; i++) {
        for (var j = i + 1; j < nodes.length; j++) {
          var a = nodes[i], b = nodes[j];
          var dx = a.x - b.x, dy = a.y - b.y;
          var dist = Math.sqrt(dx * dx + dy * dy) || 0.01;
          var rep = (k * k) / dist;
          var ux = dx / dist, uy = dy / dist;
          a.dx += ux * rep; a.dy += uy * rep;
          b.dx -= ux * rep; b.dy -= uy * rep;
        }
      }
      // Attraction along edges.
      edges.forEach(function (e) {
        var dx = e.s.x - e.t.x, dy = e.s.y - e.t.y;
        var dist = Math.sqrt(dx * dx + dy * dy) || 0.01;
        var attr = (dist * dist) / k;
        var ux = dx / dist, uy = dy / dist;
        e.s.dx -= ux * attr; e.s.dy -= uy * attr;
        e.t.dx += ux * attr; e.t.dy += uy * attr;
      });
      // Gentle gravity toward the centre.
      nodes.forEach(function (n) {
        n.dx += (cx - n.x) * 0.02;
        n.dy += (cy - n.y) * 0.02;
      });
      // Integrate, capped by temperature.
      nodes.forEach(function (n) {
        if (n.fixed) return;
        var len = Math.sqrt(n.dx * n.dx + n.dy * n.dy) || 0.01;
        n.x += (n.dx / len) * Math.min(len, temp);
        n.y += (n.dy / len) * Math.min(len, temp);
      });
      if (temp > 1.2) temp *= 0.99;
    }

    function draw() {
      edges.forEach(function (e) {
        var g = edgeGeom(e.s, e.t);
        e.line.setAttribute("d", g.path);
        if (e.hit) e.hit.setAttribute("d", g.path);
        if (e.text) {
          e.text.setAttribute("x", g.lx);
          e.text.setAttribute("y", g.ly);
        }
      });
      nodes.forEach(function (n) {
        n.g.setAttribute("transform", "translate(" + n.x + "," + n.y + ")");
      });
    }

    var frames = 0;
    var raf;
    var MAX_FRAMES = 3000;
    function loop() {
      // Two simulation steps per frame for faster, smoother convergence.
      step();
      step();
      draw();
      frames++;
      if (temp > 1.2 && frames < MAX_FRAMES) {
        raf = requestAnimationFrame(loop);
      }
    }
    loop();

    // Re-run the layout from a fresh seed to minimise edge/node overlap.
    // Unpins every node so the simulation is free to settle into a new arrangement.
    function relayout() {
      nodes.forEach(function (n) { n.fixed = false; });
      seedPositions(Math.random() * 2 * Math.PI);
      temp = W / 10;
      frames = 0;
      cancelAnimationFrame(raf);
      loop();
    }
    window.MetacatalogGraph = { relayout: relayout };

    // --- Pan / zoom ---
    var view = { x: 0, y: 0, s: 1 };
    function applyView() {
      viewport.setAttribute("transform", "translate(" + view.x + "," + view.y + ") scale(" + view.s + ")");
    }
    root.addEventListener("wheel", function (ev) {
      ev.preventDefault();
      var rect = root.getBoundingClientRect();
      var mx = ev.clientX - rect.left, my = ev.clientY - rect.top;
      var factor = ev.deltaY < 0 ? 1.1 : 1 / 1.1;
      var ns = Math.max(0.2, Math.min(4, view.s * factor));
      // Zoom around the pointer.
      view.x = mx - (mx - view.x) * (ns / view.s);
      view.y = my - (my - view.y) * (ns / view.s);
      view.s = ns;
      applyView();
    }, { passive: false });

    // --- Dragging (nodes) and panning (background) ---
    var drag = null;
    function graphPoint(ev) {
      var rect = root.getBoundingClientRect();
      return {
        x: (ev.clientX - rect.left - view.x) / view.s,
        y: (ev.clientY - rect.top - view.y) / view.s,
      };
    }
    root.addEventListener("mousedown", function (ev) {
      hidePopup();
      var target = ev.target.closest ? ev.target.closest(".graph-node") : null;
      if (target) {
        var n = nodes.find(function (x) { return x.g === target; });
        if (n) { drag = { node: n }; n.fixed = true; ev.preventDefault(); }
      } else {
        drag = { pan: true, x0: ev.clientX - view.x, y0: ev.clientY - view.y };
      }
    });
    function onMouseMove(ev) {
      if (!drag) return;
      if (drag.node) {
        var p = graphPoint(ev);
        drag.node.x = p.x;
        drag.node.y = p.y;
        // Just redraw; do not reheat. Reheating on every move made the whole
        // graph jitter while dragging, which is what made nodes hard to place.
        draw();
      } else if (drag.pan) {
        view.x = ev.clientX - drag.x0;
        view.y = ev.clientY - drag.y0;
        applyView();
      }
    }
    function onMouseUp() {
      // Leave the dragged node pinned so it stays exactly where it was dropped.
      // "Re-layout" is the way to unpin everything and recompute positions.
      drag = null;
    }
    window.addEventListener("mousemove", onMouseMove);
    window.addEventListener("mouseup", onMouseUp);

    // Tear down this render: cancel the animation and remove the window-level
    // listeners so a subsequent re-render (toggling inverses) doesn't leak them.
    // The root-level listeners die with the SVG, which canvas.innerHTML = "" removes.
    return function destroy() {
      cancelAnimationFrame(raf);
      window.removeEventListener("mousemove", onMouseMove);
      window.removeEventListener("mouseup", onMouseUp);
      hidePopup();
      canvas.innerHTML = "";
    };
  }

  document.addEventListener("DOMContentLoaded", function () {
    var canvas = document.getElementById("graph-canvas");
    var dataEl = document.getElementById("graph-data");
    var relayoutBtn = document.getElementById("graph-relayout");
    var inversesToggle = document.getElementById("graph-show-inverses");
    var entitiesToggle = document.getElementById("graph-show-entities");

    var currentDestroy = null;

    function render(data) {
      var empty = document.getElementById("graph-empty");
      if (!data.nodes || !data.nodes.length) {
        if (currentDestroy) { currentDestroy(); currentDestroy = null; }
        if (empty) empty.hidden = false;
        return;
      }
      if (empty) empty.hidden = true;
      if (currentDestroy) currentDestroy();
      currentDestroy = run(data, canvas);
    }

    function loadAndRender(showInverses, showEntities) {
      fetch("/ui/graph/data?showInverses=" + showInverses + "&showEntities=" + showEntities)
        .then(function (r) { return r.json(); })
        .then(render)
        .catch(function () { /* keep the current graph on error */ });
    }

    function refreshFromToggles() {
      var showInverses = inversesToggle ? inversesToggle.checked : true;
      var showEntities = entitiesToggle ? entitiesToggle.checked : false;
      var url = new URL(window.location.href);
      url.searchParams.set("showInverses", String(showInverses));
      url.searchParams.set("showEntities", String(showEntities));
      window.history.replaceState({}, "", url);
      loadAndRender(showInverses, showEntities);
    }

    if (relayoutBtn) {
      relayoutBtn.addEventListener("click", function () {
        if (window.MetacatalogGraph && window.MetacatalogGraph.relayout) {
          window.MetacatalogGraph.relayout();
        }
      });
    }

    if (inversesToggle) {
      inversesToggle.addEventListener("change", refreshFromToggles);
    }
    if (entitiesToggle) {
      entitiesToggle.addEventListener("change", refreshFromToggles);
    }

    if (!canvas || !dataEl) return;
    var data;
    try {
      data = JSON.parse(dataEl.textContent);
    } catch (e) {
      data = { nodes: [], edges: [] };
    }
    render(data);
  });
})();
