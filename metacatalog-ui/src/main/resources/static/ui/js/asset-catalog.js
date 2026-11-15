/*
 * Asset catalog: the list of assets and the graph of their dependencies, two views of one model.
 *
 * The page embeds the catalog as JSON in #catalog-data — the assets, each with the assets it
 * depends on and the ones depending on it, and the dependency rules: which asset type may depend on
 * which, and the trait relationships allowing it. Both views are rendered from that, by this
 * script, so that a dependency added in one is in the other without a second source of truth.
 *
 * A dependency is created by dragging an asset onto another, in either view: a row onto a row in
 * the list, a node onto a node in the graph (graph-core.js). While something is being dragged the
 * assets that may be dropped on are marked, and whether one may is decided here before the server
 * is asked, from the same rules the server applies:
 *
 *   - the model must allow it: some rule from the dragged asset's type to the target's type;
 *   - it must not exist already;
 *   - it must not close a cycle.
 *
 * The server still decides — the drop is a POST, and whatever it refuses is shown — but a drop that
 * cannot succeed is refused where the user is looking, with the reason, instead of after the fact.
 */
(function () {
  "use strict";

  // One accent per asset type, the same in the list, the graph and the legend. Picked to stay
  // apart from each other in both themes; a catalog with more types than colors reuses them.
  var TYPE_COLORS = ["#c026d3", "#2563eb", "#0d9488", "#d97706", "#dc2626", "#7c3aed", "#0891b2", "#65a30d"];

  var PROVISIONING = {
    PROVISIONED: { text: "provisioned", tone: "ok" },
    UNPROVISIONED: { text: "unprovisioned", tone: "plain" },
    FAILED: { text: "provisioning failed", tone: "err" }
  };
  var AUTHORIZATION = {
    AUTHORIZED: { text: "authorized", tone: "ok" },
    REJECTED: { text: "rejected", tone: "plain" },
    FAILED: { text: "authorization failed", tone: "err" }
  };

  function el(tag, className, text) {
    var e = document.createElement(tag);
    if (className) e.className = className;
    if (text != null) e.textContent = text;
    return e;
  }

  document.addEventListener("DOMContentLoaded", function () {
    var dataEl = document.getElementById("catalog-data");
    var rowsEl = document.getElementById("asset-rows");
    if (!dataEl || !rowsEl) return;

    var catalog;
    try {
      catalog = JSON.parse(dataEl.textContent) || {};
    } catch (e) {
      catalog = {};
    }
    catalog.assets = catalog.assets || [];
    catalog.rules = catalog.rules || [];
    catalog.types = catalog.types || [];

    var listSection = document.getElementById("catalog-list");
    var graphSection = document.getElementById("catalog-graph");
    var graphTools = document.getElementById("catalog-graph-tools");
    var typeSelect = document.getElementById("catalog-type");
    var search = document.getElementById("catalog-search");
    var count = document.getElementById("catalog-count");
    var emptyEl = document.getElementById("catalog-empty");
    var table = document.getElementById("asset-table");
    var notices = document.getElementById("catalog-notices");
    var canvas = document.getElementById("asset-graph");
    var idle = document.getElementById("asset-panel-idle");
    var detail = document.getElementById("asset-panel-detail");
    var form = document.getElementById("dependency-form");

    var graph = null;
    var view = graphSection && !graphSection.hidden ? "graph" : "list";

    // --- the model ------------------------------------------------------------------------------

    function byId(id) {
      for (var i = 0; i < catalog.assets.length; i++) {
        if (catalog.assets[i].id === id) return catalog.assets[i];
      }
      return null;
    }

    function colorOf(type) {
      var index = catalog.types.indexOf(type);
      return TYPE_COLORS[(index < 0 ? 0 : index) % TYPE_COLORS.length];
    }

    function rule(sourceType, targetType) {
      for (var i = 0; i < catalog.rules.length; i++) {
        var r = catalog.rules[i];
        if (r.source === sourceType && r.target === targetType) return r;
      }
      return null;
    }

    function dependsOn(asset, target) {
      return asset.dependsOn.some(function (d) { return d.id === target.id; });
    }

    // Whether `from` already reaches `to` through dependencies: a new dependency of `to` on `from`
    // would then close a cycle.
    function reaches(from, to, seen) {
      if (from.id === to.id) return true;
      seen = seen || {};
      if (seen[from.id]) return false;
      seen[from.id] = true;
      return from.dependsOn.some(function (d) {
        var next = byId(d.id);
        return next ? reaches(next, to, seen) : false;
      });
    }

    // Why `source` may not be made to depend on `target`, or null when it may.
    function refusal(source, target) {
      if (source.id === target.id) return "An asset cannot depend on itself";
      if (dependsOn(source, target)) return "«" + source.name + "» already depends on «" + target.name + "»";
      if (!rule(source.entityType, target.entityType)) {
        return "A " + source.entityType + " asset cannot depend on a " + target.entityType + " asset";
      }
      if (reaches(target, source)) {
        return "«" + target.name + "» already depends on «" + source.name + "»: this would close a cycle";
      }
      return null;
    }

    function matches(asset) {
      var type = typeSelect ? typeSelect.value : "";
      var text = search ? search.value.trim().toLowerCase() : "";
      if (type && asset.entityType !== type) return false;
      return !text || asset.name.toLowerCase().indexOf(text) !== -1;
    }

    // --- notices --------------------------------------------------------------------------------

    function notify(text, ok) {
      if (!notices) return;
      notices.textContent = "";
      var box = el("div", ok ? "flash" : "error", text);
      notices.appendChild(box);
      if (ok) {
        window.setTimeout(function () {
          if (box.parentNode) box.parentNode.removeChild(box);
        }, 4000);
      }
    }

    // --- talking to the server ------------------------------------------------------------------

    function post(path, source, target, done) {
      var headers = { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" };
      // With security enabled the UI chain enforces CSRF; Thymeleaf injects the token into the
      // hidden form as a _csrf field, and this request must forward it as the header.
      var csrf = form ? form.querySelector("input[name='_csrf']") : null;
      if (csrf) headers["X-CSRF-TOKEN"] = csrf.value;
      fetch(path, {
        method: "POST",
        headers: headers,
        body: "source=" + encodeURIComponent(source.id) + "&target=" + encodeURIComponent(target.id)
      })
        .then(function (res) {
          return res.json().then(function (body) { return { ok: res.ok, body: body }; });
        })
        .then(function (answer) {
          if (!answer.ok || !answer.body.catalog) {
            notify(answer.body.error || "The dependency could not be changed.", false);
            return;
          }
          catalog = answer.body.catalog;
          // Laid out afresh rather than in place: a dependency decides which row an asset sits
          // in, so keeping the old positions would leave an arrow pointing the wrong way.
          render();
          done();
        })
        .catch(function () {
          notify("The server could not be reached; the dependency was not changed.", false);
        });
    }

    function link(source, target) {
      var why = refusal(source, target);
      if (why) {
        notify(why + ".", false);
        return;
      }
      post("/ui/assets/dependencies", source, target, function () {
        notify("«" + source.name + "» now depends on «" + target.name + "».", true);
      });
    }

    function unlink(source, target) {
      if (!window.confirm("Remove the dependency of «" + source.name + "» on «" + target.name + "»?")) return;
      post("/ui/assets/dependencies/delete", source, target, function () {
        notify("«" + source.name + "» no longer depends on «" + target.name + "».", true);
      });
    }

    // --- the list -------------------------------------------------------------------------------

    function statusPill(value, labels) {
      var known = value ? labels[value] : null;
      var pill = el("span", "status status-" + (value || "none"), known ? known.text : value || "never run");
      return pill;
    }

    function refChips(cell, refs, owner) {
      if (!refs.length) {
        cell.appendChild(el("span", "muted-dash", "—"));
        return;
      }
      refs.forEach(function (ref) {
        var chip = el("span", "ref-chip");
        var a = el("a", null, ref.name);
        a.href = "/ui/assets/" + encodeURIComponent(ref.id);
        a.title = ref.entityType;
        chip.appendChild(a);
        if (owner) {
          var remove = el("button", "ref-chip-remove", "×");
          remove.type = "button";
          remove.title = "Remove this dependency";
          remove.setAttribute("aria-label", "Remove the dependency on " + ref.name);
          remove.addEventListener("click", function () {
            var target = byId(ref.id);
            if (target) unlink(owner, target);
          });
          chip.appendChild(remove);
        }
        cell.appendChild(chip);
      });
    }

    var dragged = null;

    function clearRowMarks() {
      rowsEl.querySelectorAll("tr").forEach(function (tr) {
        tr.classList.remove("row-dragging", "row-can-drop", "row-no-drop", "row-drop-over");
      });
    }

    function renderList() {
      rowsEl.textContent = "";
      var shown = catalog.assets.filter(matches);
      shown.forEach(function (asset) {
        var tr = el("tr", "asset-row");
        tr.setAttribute("data-asset-id", asset.id);
        tr.draggable = true;

        var grip = el("td", "col-grip", "⠿");
        grip.title = "Drag onto another asset to depend on it";
        tr.appendChild(grip);

        var name = el("td", "col-name");
        var a = el("a", "asset-link", asset.name);
        a.href = "/ui/assets/" + encodeURIComponent(asset.id);
        a.draggable = false;
        name.appendChild(a);
        if (asset.version != null) name.appendChild(el("span", "tag muted", "v" + asset.version));
        tr.appendChild(name);

        var type = el("td");
        var tag = el("span", "type-tag", asset.entityType);
        tag.style.setProperty("--type-color", colorOf(asset.entityType));
        type.appendChild(tag);
        tr.appendChild(type);

        var prov = el("td");
        prov.appendChild(statusPill(asset.provisioningStatus, PROVISIONING));
        tr.appendChild(prov);
        var auth = el("td");
        auth.appendChild(statusPill(asset.authorizationStatus, AUTHORIZATION));
        tr.appendChild(auth);

        var deps = el("td", "col-refs");
        refChips(deps, asset.dependsOn, asset);
        tr.appendChild(deps);
        var req = el("td", "col-refs");
        refChips(req, asset.requiredBy, null);
        tr.appendChild(req);

        var actions = el("td", "col-actions");
        [["Open", ""], ["Edit", "/edit"], ["YAML", "/yaml"]].forEach(function (pair) {
          var b = el("a", "btn-sm", pair[0]);
          b.href = "/ui/assets/" + encodeURIComponent(asset.id) + pair[1];
          b.draggable = false;
          actions.appendChild(b);
        });
        tr.appendChild(actions);

        tr.addEventListener("dragstart", function (ev) {
          dragged = asset;
          ev.dataTransfer.effectAllowed = "link";
          ev.dataTransfer.setData("text/plain", asset.id);
          tr.classList.add("row-dragging");
          rowsEl.querySelectorAll("tr").forEach(function (other) {
            if (other === tr) return;
            var target = byId(other.getAttribute("data-asset-id"));
            other.classList.add(target && !refusal(asset, target) ? "row-can-drop" : "row-no-drop");
          });
        });
        tr.addEventListener("dragend", function () {
          dragged = null;
          clearRowMarks();
        });
        tr.addEventListener("dragover", function (ev) {
          if (!dragged || dragged.id === asset.id) return;
          // Only an allowed target calls preventDefault, which is what makes it a drop target at
          // all: over anything else the browser shows its own "not allowed" cursor.
          if (refusal(dragged, asset)) return;
          ev.preventDefault();
          ev.dataTransfer.dropEffect = "link";
          tr.classList.add("row-drop-over");
        });
        tr.addEventListener("dragleave", function () {
          tr.classList.remove("row-drop-over");
        });
        tr.addEventListener("drop", function (ev) {
          ev.preventDefault();
          var source = dragged;
          dragged = null;
          clearRowMarks();
          if (source) link(source, asset);
        });

        rowsEl.appendChild(tr);
      });

      if (table) table.hidden = shown.length === 0;
      if (emptyEl) {
        emptyEl.hidden = shown.length !== 0;
        emptyEl.textContent = catalog.assets.length
          ? "No asset matches the filter."
          : "No assets yet — create one, or upload a YAML file.";
      }
      if (count) {
        count.textContent = shown.length === catalog.assets.length
          ? catalog.assets.length + (catalog.assets.length === 1 ? " asset" : " assets")
          : shown.length + " of " + catalog.assets.length + " assets";
      }
    }

    // --- the graph ------------------------------------------------------------------------------

    function toNode(asset) {
      var badges = [];
      var p = asset.provisioningStatus ? PROVISIONING[asset.provisioningStatus] : null;
      var a = asset.authorizationStatus ? AUTHORIZATION[asset.authorizationStatus] : null;
      if (p) badges.push({ text: p.text, tone: p.tone });
      if (a) badges.push({ text: a.text, tone: a.tone });
      return {
        id: asset.id, title: asset.name, subtitle: asset.entityType, kind: "asset",
        color: colorOf(asset.entityType), badges: badges, data: asset
      };
    }

    function graphData() {
      var nodes = catalog.assets.map(toNode);
      var edges = [];
      catalog.assets.forEach(function (asset) {
        asset.dependsOn.forEach(function (d) {
          edges.push({ source: asset.id, target: d.id, kind: "dependency", label: "", data: { source: asset.id, target: d.id } });
        });
      });
      return { nodes: nodes, edges: edges };
    }

    function renderGraph() {
      if (!canvas || !window.MetacatalogGraphCore) return;
      var data = graphData();
      if (!graph) {
        graph = window.MetacatalogGraphCore.create(canvas, {
          nodes: data.nodes, edges: data.edges, label: "Asset dependency graph",
          onSelect: onSelect,
          drop: {
            allowed: function (source, target) { return !refusal(source.data, target.data); },
            hint: function (source, target) {
              var why = refusal(source.data, target.data);
              return why || "Make «" + source.data.name + "» depend on «" + target.data.name + "»";
            },
            onDrop: function (source, target) { link(source.data, target.data); },
            onRefused: function (source, target) { notify(refusal(source.data, target.data) + ".", false); }
          }
        });
      } else {
        graph.setData(data.nodes, data.edges, false);
      }
      graph.setFilter(function (node) { return matches(node.data); });
      showIdle();
    }

    function showIdle() {
      if (detail) detail.hidden = true;
      if (idle) idle.hidden = false;
    }

    function panelRow(parent, key, build) {
      var r = el("div", "graph-panel-row");
      r.appendChild(el("span", "graph-panel-k", key));
      var v = el("span", "graph-panel-v");
      build(v);
      r.appendChild(v);
      parent.appendChild(r);
    }

    function assetLinks(parent, refs) {
      if (!refs.length) {
        parent.textContent = "—";
        return;
      }
      refs.forEach(function (ref) {
        var a = el("a", "tag", ref.name);
        a.href = "/ui/assets/" + encodeURIComponent(ref.id);
        parent.appendChild(a);
      });
    }

    function button(parent, text, href) {
      var a = el("a", "btn-sm", text);
      a.href = href;
      parent.appendChild(a);
      return a;
    }

    function assetDetail(asset) {
      detail.textContent = "";
      detail.appendChild(el("div", "graph-panel-kind", "asset"));
      detail.appendChild(el("h2", null, asset.name));
      panelRow(detail, "type", function (v) { v.textContent = asset.entityType; });
      if (asset.version != null) panelRow(detail, "version", function (v) { v.textContent = "v" + asset.version; });
      panelRow(detail, "provisioning", function (v) { v.appendChild(statusPill(asset.provisioningStatus, PROVISIONING)); });
      panelRow(detail, "authorization", function (v) { v.appendChild(statusPill(asset.authorizationStatus, AUTHORIZATION)); });
      panelRow(detail, "depends on", function (v) { assetLinks(v, asset.dependsOn); });
      panelRow(detail, "required by", function (v) { assetLinks(v, asset.requiredBy); });

      var actions = el("div", "graph-panel-actions");
      var base = "/ui/assets/" + encodeURIComponent(asset.id);
      button(actions, "Open", base);
      button(actions, "Edit", base + "/edit");
      button(actions, "Download YAML", base + "/yaml");
      detail.appendChild(actions);

      var targets = catalog.rules.filter(function (r) { return r.source === asset.entityType; });
      detail.appendChild(el("h3", null, "May depend on"));
      if (targets.length) {
        var list = el("ul", "rule-list");
        targets.forEach(function (r) {
          var li = el("li");
          li.appendChild(el("strong", null, r.target + " assets"));
          li.appendChild(el("span", "rule-why", r.sanctionedBy.join("; ")));
          list.appendChild(li);
        });
        detail.appendChild(list);
        detail.appendChild(el("p", "hint", "Drag this asset onto one of those to add the dependency."));
      } else {
        detail.appendChild(el("p", "empty", "The model allows a " + asset.entityType + " asset no dependency."));
      }
    }

    function dependencyDetail(edge) {
      var source = byId(edge.source), target = byId(edge.target);
      if (!source || !target) {
        showIdle();
        return;
      }
      detail.textContent = "";
      detail.appendChild(el("div", "graph-panel-kind", "dependency"));
      detail.appendChild(el("h2", null, source.name + " → " + target.name));
      // Says only what a dependency between assets does: it is recorded, and mappings can read
      // through it. Each asset is still provisioned and authorized on its own.
      detail.appendChild(el("p", "hint",
        "«" + source.name + "» depends on «" + target.name + "». The dependency is part of every version of «" +
        source.name + "», and its mappings can read values from «" + target.name + "» through it."));

      var r = rule(source.entityType, target.entityType);
      detail.appendChild(el("h3", null, "Allowed by"));
      if (r) {
        var list = el("ul", "rule-list");
        r.sanctionedBy.forEach(function (text) { list.appendChild(el("li", null, text)); });
        detail.appendChild(list);
      } else {
        detail.appendChild(el("p", "error",
          "No trait relationship allows this dependency any more; it could not be created again."));
      }

      var actions = el("div", "graph-panel-actions");
      var remove = el("button", "btn-sm danger", "Remove dependency");
      remove.type = "button";
      remove.addEventListener("click", function () { unlink(source, target); });
      actions.appendChild(remove);
      button(actions, "Mappings of " + source.name, "/ui/assets/" + encodeURIComponent(source.id) + "#mappings");
      button(actions, "Edit the model", "/ui/admin");
      detail.appendChild(actions);
    }

    function onSelect(selection) {
      if (!selection) {
        showIdle();
        return;
      }
      if (idle) idle.hidden = true;
      detail.hidden = false;
      if (selection.node) assetDetail(selection.node.data);
      else dependencyDetail(selection.edge.data);
    }

    function renderLegend() {
      var legend = document.getElementById("asset-type-legend");
      if (legend) {
        legend.textContent = "";
        catalog.types.forEach(function (type) {
          var li = el("li");
          var swatch = el("span", "lg-card");
          swatch.style.borderLeftColor = colorOf(type);
          li.appendChild(swatch);
          var n = catalog.assets.filter(function (a) { return a.entityType === type; }).length;
          li.appendChild(document.createTextNode(type + " (" + n + ")"));
          legend.appendChild(li);
        });
      }
      var rules = document.getElementById("asset-rule-list");
      if (rules) {
        rules.textContent = "";
        if (!catalog.rules.length) {
          rules.appendChild(el("li", "empty",
            "None yet. Relate two traits by DEPENDS_ON under Administration to allow the asset types carrying them to depend on each other."));
        }
        catalog.rules.forEach(function (r) {
          var li = el("li");
          li.appendChild(el("strong", null, r.source + " → " + r.target));
          li.appendChild(el("span", "rule-why", r.sanctionedBy.join("; ")));
          rules.appendChild(li);
        });
      }
    }

    // --- views ----------------------------------------------------------------------------------

    function render() {
      renderList();
      renderLegend();
      // The graph is only built once its section is visible: a canvas inside a hidden section has
      // no size, and the first layout would fit the graph to nothing.
      if (view === "graph" || graph) renderGraph();
    }

    function setView(next) {
      view = next;
      if (listSection) listSection.hidden = next !== "list";
      if (graphSection) graphSection.hidden = next !== "graph";
      if (graphTools) graphTools.hidden = next !== "graph";
      document.querySelectorAll(".view-switch [data-view]").forEach(function (b) {
        var active = b.getAttribute("data-view") === next;
        b.classList.toggle("active", active);
        b.setAttribute("aria-pressed", String(active));
      });
      // The application bar's two catalog links are this same switch; they follow it.
      document.querySelectorAll(".subnav a").forEach(function (a) {
        var href = a.getAttribute("href") || "";
        if (href.indexOf("/ui/assets") !== 0 || /\/(new|upload)/.test(href)) return;
        a.classList.toggle("active", (href.indexOf("view=graph") !== -1) === (next === "graph"));
      });
      var url = new URL(window.location.href);
      url.searchParams.set("view", next);
      window.history.replaceState({}, "", url);
      if (next === "graph") {
        if (!graph) renderGraph();
        else graph.fit();
      }
    }

    document.querySelectorAll(".view-switch [data-view]").forEach(function (b) {
      b.addEventListener("click", function () { setView(b.getAttribute("data-view")); });
    });

    function refilter() {
      renderList();
      if (graph) graph.setFilter(function (node) { return matches(node.data); });
      if (typeSelect) {
        var url = new URL(window.location.href);
        if (typeSelect.value) url.searchParams.set("type", typeSelect.value);
        else url.searchParams.delete("type");
        window.history.replaceState({}, "", url);
      }
    }
    if (typeSelect) typeSelect.addEventListener("change", refilter);
    if (search) search.addEventListener("input", refilter);

    function bind(id, fn) {
      var b = document.getElementById(id);
      if (b) b.addEventListener("click", function () { if (graph) fn(); });
    }
    bind("asset-fit", function () { graph.fit(); });
    bind("asset-relayout", function () { graph.relayout(); });
    bind("asset-zoom-in", function () { graph.zoomIn(); });
    bind("asset-zoom-out", function () { graph.zoomOut(); });

    render();
    setView(view);
  });
})();
