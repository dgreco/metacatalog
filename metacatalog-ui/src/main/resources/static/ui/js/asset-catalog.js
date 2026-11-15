/*
 * Asset catalog: the list of assets and the graph of their dependencies, two views of one model.
 *
 * The page embeds the catalog as JSON in #catalog-data: the assets, each with what it depends on
 * and the assets depending on it; the targets — aggregates and plain entities an asset may depend
 * on without their being assets; the dependency rules, saying which asset type may depend on which
 * type and the trait relationships allowing it; and the types a new node can be created from. Both
 * views are rendered from that, by this script, so that a dependency added in one is in the other
 * without a second source of truth.
 *
 * The list is of assets and nothing else. The graph also draws the targets: a dependency has to end
 * somewhere, and to make one its other end has to be there to drop on.
 *
 * Each asset carries its mapped entities — what mappings derived from its members, which is what
 * a provisioning or authorization run acts on. The list names them in a column; the graph hangs
 * them below their asset, and can leave them out.
 *
 * Each asset also carries its parts, and the graph draws the asset's inside with them: every part
 * below what contains it, and the dependencies between parts as edges — the same relation as a
 * dependency between assets, one level down. A mapped entity then hangs from the part it was
 * derived from rather than from the asset. The inside is looked at here, not edited: parts and
 * their dependencies are the asset's own content and change on its page, as one version of it.
 *
 * A dependency is created by dragging an asset onto something: a row onto a row in the list, a node
 * onto a node in the graph (graph-core.js). While an asset is being dragged the nodes it may be
 * dropped on are marked, and whether one may is decided here before the server is asked, from the
 * same rules the server applies:
 *
 *   - only an asset depends on something;
 *   - the model must allow it: some rule from the dragged asset's type to the target's type;
 *   - it must not exist already;
 *   - it must not close a cycle.
 *
 * The server still decides — the drop is a POST, and whatever it refuses is shown — but a drop that
 * cannot succeed is refused where the user is looking, with the reason, instead of after the fact.
 *
 * A right-click on the graph's board offers to create a new node there and then: an asset, or
 * something for assets to depend on. The dialog asks for the scalar values the type declares and
 * creates the root alone; an asset's parts are added from its own page.
 *
 * A right-click on an asset also offers its four runs — provision, unprovision, authorize, reject
 * — which are launched as from the asset's page and bring the browser back to the graph, where
 * the progress popup follows the run and the nodes show what it left.
 *
 * A right-click on a node offers to open it and, for what stands on the board in its own right —
 * an asset, an aggregate, an entity — to delete it. An asset or an aggregate goes with everything
 * inside it. What something still depends on is refused here, with what depends on it; the server
 * is asked for the rest, and decides.
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
    function normalize(c) {
      c = c || {};
      c.assets = c.assets || [];
      c.targets = c.targets || [];
      c.rules = c.rules || [];
      c.types = c.types || [];
      c.creatable = c.creatable || [];
      c.procedures = c.procedures || [];
      // One shape for everything on the board: an asset is its own kind of node.
      c.assets.forEach(function (a) {
        a.kind = "asset";
        a.href = "/ui/assets/" + encodeURIComponent(a.id);
        a.mapped = a.mapped || [];
        a.mapped.forEach(function (m) { m.kind = "mapped"; m.asset = a; m.dependsOn = []; });
        a.parts = a.parts || [];
        // `dependsOn` is what an asset's dependencies are walked through when looking for a
        // cycle; a part's own are kept apart, in `dependencies`, and never enter that walk.
        a.parts.forEach(function (p) { p.kind = "part"; p.asset = a; p.dependsOn = []; });
      });
      c.targets.forEach(function (t) { t.dependsOn = []; });
      return c;
    }
    catalog = normalize(catalog);

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

    // Anything on the board, by id: an asset or one of the other targets.
    function byId(id) {
      var i;
      for (i = 0; i < catalog.assets.length; i++) {
        if (catalog.assets[i].id === id) return catalog.assets[i];
      }
      for (i = 0; i < catalog.targets.length; i++) {
        if (catalog.targets[i].id === id) return catalog.targets[i];
      }
      for (i = 0; i < catalog.assets.length; i++) {
        var inside = catalog.assets[i].parts.concat(catalog.assets[i].mapped);
        for (var j = 0; j < inside.length; j++) {
          if (inside[j].id === id) return inside[j];
        }
      }
      return null;
    }

    function isAsset(node) {
      return node.kind === "asset";
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

    // The assets depending on a node, whatever the node is. An asset's row already says; for an
    // aggregate or an entity it is read off the assets.
    function dependents(node) {
      if (isAsset(node)) return node.requiredBy;
      return catalog.assets
        .filter(function (a) { return dependsOn(a, node); })
        .map(function (a) { return { id: a.id, name: a.name, entityType: a.entityType, kind: "asset", href: a.href }; });
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
      if (!isAsset(source)) {
        // A part does depend on things, but not from here: its dependencies are content of its
        // asset, changed with the asset so that the change is one version of it.
        if (source.kind === "part") {
          return "«" + source.name + "» is a part of «" + source.asset.name + "»: what a part depends on is edited on the asset's page";
        }
        return "Only an asset depends on something: «" + source.name + "» is " +
          (source.kind === "aggregate" ? "an aggregate" : source.kind === "mapped" ? "a mapped entity" : "an entity");
      }
      if (source.id === target.id) return "An asset cannot depend on itself";
      // A part never stands on its own: what can be depended on is the asset it belongs to.
      if (target.kind === "part") return "«" + target.name + "» is a part of «" + target.asset.name + "»; depend on «" + target.asset.name + "» instead";
      // A mapped entity is the consequence of an asset, not a thing of its own to depend on.
      if (target.kind === "mapped") return "«" + target.name + "» is derived by a mapping; depend on «" + target.asset.name + "» instead";
      if (dependsOn(source, target)) return "«" + source.name + "» already depends on «" + target.name + "»";
      if (!rule(source.entityType, target.entityType)) {
        return "A " + source.entityType + " asset cannot depend on a " + target.entityType + " " + target.kind;
      }
      if (isAsset(target) && reaches(target, source)) {
        return "«" + target.name + "» already depends on «" + source.name + "»: this would close a cycle";
      }
      return null;
    }

    // The type filter is about assets; what they depend on stays lit under it, or filtering by a
    // type would hide the very things that type's assets point at.
    function matches(node) {
      // A part and a mapped entity go with the asset they belong to.
      if (node.kind === "mapped" || node.kind === "part") return matches(node.asset);
      var type = typeSelect ? typeSelect.value : "";
      var text = search ? search.value.trim().toLowerCase() : "";
      if (type && isAsset(node) && node.entityType !== type) return false;
      return !text || node.name.toLowerCase().indexOf(text) !== -1;
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

    // Posts a change and, when it is accepted, redraws from the catalog the server answers with.
    // `failed` receives the reason otherwise; without one the reason is shown as a notice.
    function send(path, params, done, failed) {
      var headers = { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" };
      // With security enabled the UI chain enforces CSRF; Thymeleaf injects the token into the
      // hidden form as a _csrf field, and this request must forward it as the header.
      var csrf = form ? form.querySelector("input[name='_csrf']") : null;
      if (csrf) headers["X-CSRF-TOKEN"] = csrf.value;
      var report = failed || function (why) { notify(why, false); };
      fetch(path, {
        method: "POST",
        headers: headers,
        body: Object.keys(params)
          .map(function (k) { return encodeURIComponent(k) + "=" + encodeURIComponent(params[k]); })
          .join("&")
      })
        .then(function (res) {
          return res.json().then(function (body) { return { ok: res.ok, body: body }; });
        })
        .then(function (answer) {
          if (!answer.ok || !answer.body.catalog) {
            report(answer.body.error || "The change was refused.");
            return;
          }
          catalog = normalize(answer.body.catalog);
          // Laid out afresh rather than in place: a dependency decides which row an asset sits
          // in, so keeping the old positions would leave an arrow pointing the wrong way.
          render();
          done();
        })
        .catch(function () {
          report("The server could not be reached; nothing was changed.");
        });
    }

    function post(path, source, target, done) {
      send(path, { source: source.id, target: target.id }, done);
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

    // Launches a run on an asset the way its page does — a form posted to the same address — so
    // that the answer is a page carrying the seed of the progress popup. It says where it was
    // launched from, and the server sends the browser back here instead of to the asset's page.
    function run(asset, procedure) {
      if (!window.confirm("«" + asset.name + "» — " + procedure.confirm)) return;
      var launch = el("form");
      launch.method = "post";
      launch.action = asset.href + "/procedure/" + encodeURIComponent(procedure.path);
      launch.hidden = true;
      function field(name, value) {
        var input = el("input");
        input.type = "hidden";
        input.name = name;
        input.value = value;
        launch.appendChild(input);
      }
      field("from", "graph");
      // With security enabled the UI chain enforces CSRF; the page's own form holds the token.
      var csrf = form ? form.querySelector("input[name='_csrf']") : null;
      if (csrf) field("_csrf", csrf.value);
      document.body.appendChild(launch);
      launch.submit();
    }

    // Why `node` may not be deleted, or null when the server may be asked.
    function deletionRefusal(node) {
      var needing = isAsset(node) ? node.requiredBy : dependents(node);
      if (!needing.length) return null;
      return "«" + node.name + "» cannot be deleted: " +
        needing.map(function (a) { return "«" + a.name + "»"; }).join(", ") +
        (needing.length === 1 ? " depends" : " depend") + " on it. Remove the " +
        (needing.length === 1 ? "dependency" : "dependencies") + " first";
    }

    function remove(node) {
      var why = deletionRefusal(node);
      if (why) {
        notify(why + ".", false);
        return;
      }
      var inside = node.kind === "entity" ? "" :
        " Everything inside it is deleted with it" +
        (isAsset(node) && node.mapped.length ? ", and so are the " + node.mapped.length + " entities derived from it" : "") + ".";
      if (!window.confirm("Delete the " + node.kind + " «" + node.name + "»?" + inside + " This cannot be undone.")) return;
      send("/ui/assets/entities/delete", { id: node.id }, function () {
        notify("«" + node.name + "» was deleted.", true);
      });
    }

    // --- the list -------------------------------------------------------------------------------

    // How a mapped entity stands: failed if either run failed, up if something is in place.
    function statusClass(m) {
      if (m.provisioningStatus === "FAILED" || m.authorizationStatus === "FAILED") return "is-failed";
      if (m.provisioningStatus === "PROVISIONED" || m.authorizationStatus === "AUTHORIZED") return "is-ok";
      if (m.provisioningStatus === "UNPROVISIONED" || m.authorizationStatus === "REJECTED") return "is-down";
      return "";
    }

    function statusText(m) {
      var parts = [m.provisioningStatus, m.authorizationStatus].filter(Boolean);
      return parts.length ? " — " + parts.join(", ").toLowerCase() : "";
    }

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
        a.href = ref.href;
        a.title = ref.kind === "asset" ? ref.entityType : ref.entityType + " (" + ref.kind + ")";
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

        var mapped = el("td", "col-refs");
        if (!asset.mapped.length) mapped.appendChild(el("span", "muted-dash", "—"));
        asset.mapped.forEach(function (m) {
          var chip = el("a", "mapped-chip " + statusClass(m), m.name);
          chip.href = m.href;
          chip.draggable = false;
          chip.title = m.entityType + ", derived from " + m.sourceName + statusText(m);
          mapped.appendChild(chip);
        });
        tr.appendChild(mapped);

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

    // An aggregate or an entity an asset may depend on: on the board, but told apart from the
    // assets by its outline and a badge saying what it is.
    function toTargetNode(target) {
      return {
        id: target.id, title: target.name, subtitle: target.entityType, kind: target.kind,
        badges: [{ text: target.kind, tone: "plain" }], data: target
      };
    }

    // An entity a mapping derived from one of an asset's members.
    function toMappedNode(m) {
      var badges = [];
      var p = m.provisioningStatus ? PROVISIONING[m.provisioningStatus] : null;
      var a = m.authorizationStatus ? AUTHORIZATION[m.authorizationStatus] : null;
      if (p) badges.push({ text: p.text, tone: p.tone });
      if (a) badges.push({ text: a.text, tone: a.tone });
      return { id: m.id, title: m.name, subtitle: "from " + m.sourceName, kind: "mapped", badges: badges, data: m };
    }

    // An entity inside an asset, in its asset's color so that the parts of two assets side by
    // side are told apart.
    function toPartNode(p) {
      return {
        id: p.id, title: p.name, subtitle: p.entityType, kind: "part",
        color: colorOf(p.asset.entityType), data: p
      };
    }

    function showsMapped() {
      var toggle = document.getElementById("asset-show-mapped");
      return !toggle || toggle.checked;
    }

    function showsParts() {
      var toggle = document.getElementById("asset-show-parts");
      return !toggle || toggle.checked;
    }

    function graphData() {
      var nodes = catalog.assets.map(toNode).concat(catalog.targets.map(toTargetNode));
      var edges = [];
      var onBoard = {};
      function add(node) {
        nodes.push(node);
        onBoard[node.id] = true;
      }
      nodes.forEach(function (n) { onBoard[n.id] = true; });
      if (showsParts()) {
        catalog.assets.forEach(function (asset) {
          asset.parts.forEach(function (p) {
            add(toPartNode(p));
            edges.push({ source: p.parentId, target: p.id, kind: "part", label: "", data: p });
          });
        });
      }
      if (showsMapped()) {
        catalog.assets.forEach(function (asset) {
          asset.mapped.forEach(function (m) { add(toMappedNode(m)); });
        });
        catalog.assets.forEach(function (asset) {
          asset.mapped.forEach(function (m) {
            // From what it was derived from — a part, or in a chain of mappings another mapped
            // entity — when that is drawn; from the asset when its inside is left out.
            edges.push({ source: onBoard[m.sourceId] ? m.sourceId : asset.id, target: m.id, kind: "mapping", label: "", data: m });
          });
        });
      }
      catalog.assets.forEach(function (asset) {
        asset.dependsOn.forEach(function (d) {
          edges.push({ source: asset.id, target: d.id, kind: "dependency", label: "", data: { source: asset.id, target: d.id } });
        });
      });
      if (showsParts()) {
        catalog.assets.forEach(function (asset) {
          asset.parts.forEach(function (p) {
            p.dependencies.forEach(function (d) {
              // Almost always another part of the same asset. One that leaves the asset is drawn
              // when its other end is on the board, and has nowhere to go when it is not.
              if (onBoard[d]) edges.push({ source: p.id, target: d, kind: "internal", label: "", data: { source: p.id, target: d } });
            });
          });
        });
      }
      return { nodes: nodes, edges: edges };
    }

    function renderGraph() {
      if (!canvas || !window.MetacatalogGraphCore) return;
      var data = graphData();
      if (!graph) {
        graph = window.MetacatalogGraphCore.create(canvas, {
          nodes: data.nodes, edges: data.edges, label: "Asset dependency graph",
          // A dependency points up, at what is depended on — between assets and between parts
          // alike; containment and a mapping point down, at what an asset holds and at what
          // follows from it.
          ranked: function (edge) { return edge.kind === "mapping" || edge.kind === "part" ? "reverse" : true; },
          onSelect: onSelect,
          onContextMenu: openMenu,
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
        a.href = ref.href;
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
          li.appendChild(el("strong", null, r.target + (r.targetKind === "asset" ? " assets" : " (" + r.targetKind + ")")));
          li.appendChild(el("span", "rule-why", r.sanctionedBy.join("; ")));
          list.appendChild(li);
        });
        detail.appendChild(list);
        detail.appendChild(el("p", "hint", "Drag this asset onto one of those to add the dependency, or right-click the board to create one."));
      } else {
        detail.appendChild(el("p", "empty", "The model allows a " + asset.entityType + " asset no dependency."));
      }
    }

    function targetDetail(target) {
      detail.textContent = "";
      detail.appendChild(el("div", "graph-panel-kind", target.kind));
      detail.appendChild(el("h2", null, target.name));
      panelRow(detail, "type", function (v) { v.textContent = target.entityType; });
      panelRow(detail, "required by", function (v) { assetLinks(v, dependents(target)); });
      var actions = el("div", "graph-panel-actions");
      button(actions, "Open", target.href);
      detail.appendChild(actions);
      detail.appendChild(el("p", "hint",
        (target.kind === "aggregate" ? "An aggregate" : "An entity") +
        " that is not an asset: it is not provisioned or authorized from here, but assets may depend on it. " +
        "Drag an asset onto it to add the dependency."));
    }

    function mappedDetail(m) {
      detail.textContent = "";
      detail.appendChild(el("div", "graph-panel-kind", "mapped entity"));
      detail.appendChild(el("h2", null, m.name));
      panelRow(detail, "type", function (v) { v.textContent = m.entityType; });
      panelRow(detail, "asset", function (v) { assetLinks(v, [m.asset]); });
      panelRow(detail, "derived from", function (v) { v.textContent = m.sourceName + " (" + m.sourceEntityType + ")"; });
      panelRow(detail, "provisioning", function (v) { v.appendChild(statusPill(m.provisioningStatus, PROVISIONING)); });
      panelRow(detail, "authorization", function (v) { v.appendChild(statusPill(m.authorizationStatus, AUTHORIZATION)); });
      var actions = el("div", "graph-panel-actions");
      button(actions, "Open", m.href);
      button(actions, "Edit mapping", m.mappingHref);
      detail.appendChild(actions);
      detail.appendChild(el("p", "hint",
        "Derived by the mapping " + m.sourceEntityType + " → " + m.entityType +
        ". Nobody writes it: it follows the asset, and a provisioning or authorization run of «" +
        m.asset.name + "» records its outcome here."));
    }

    // The parts of the same asset depending on the given part.
    function partDependents(p) {
      return p.asset.parts.filter(function (other) { return other.dependencies.indexOf(p.id) !== -1; });
    }

    function partDetail(p) {
      detail.textContent = "";
      detail.appendChild(el("div", "graph-panel-kind", "part"));
      detail.appendChild(el("h2", null, p.name));
      panelRow(detail, "type", function (v) { v.textContent = p.entityType; });
      panelRow(detail, "asset", function (v) { assetLinks(v, [p.asset]); });
      var parent = byId(p.parentId);
      if (parent && parent.kind === "part") panelRow(detail, "inside", function (v) { assetLinks(v, [parent]); });
      panelRow(detail, "depends on", function (v) {
        assetLinks(v, p.dependencies.map(byId).filter(function (d) { return d; }));
      });
      panelRow(detail, "required by", function (v) { assetLinks(v, partDependents(p)); });
      panelRow(detail, "mapped to", function (v) {
        assetLinks(v, p.asset.mapped.filter(function (m) { return m.sourceId === p.id; }));
      });
      var actions = el("div", "graph-panel-actions");
      button(actions, "Open", p.href);
      button(actions, "Edit «" + p.asset.name + "»", p.asset.href + "/edit");
      detail.appendChild(actions);
      detail.appendChild(el("p", "hint",
        "A part of «" + p.asset.name + "». It is created, changed and removed with the asset, and what it " +
        "depends on is part of the asset's content: edit the asset to change either."));
    }

    // A dependency of a part: shown, with the way to where it is changed.
    function internalDetail(edge) {
      var source = byId(edge.source), target = byId(edge.target);
      if (!source || !target) {
        showIdle();
        return;
      }
      detail.textContent = "";
      detail.appendChild(el("div", "graph-panel-kind", "internal dependency"));
      detail.appendChild(el("h2", null, source.name + " → " + target.name));
      panelRow(detail, "asset", function (v) { assetLinks(v, [source.asset]); });
      var outside = target.kind !== "part" || target.asset !== source.asset;
      detail.appendChild(el("p", "hint",
        "«" + source.name + "», a part of «" + source.asset.name + "», depends on «" + target.name + "»" +
        (outside ? ", which is outside it" : ", another of its parts") +
        ". Mappings read through this: what is derived from «" + source.name + "» can take values from «" +
        target.name + "» and from what was derived from it. It is part of the asset's content and is " +
        "changed by editing the asset."));
      var actions = el("div", "graph-panel-actions");
      button(actions, "Edit «" + source.asset.name + "»", source.asset.href + "/edit");
      button(actions, "Mappings of " + source.asset.name, source.asset.href + "#mappings");
      detail.appendChild(actions);
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
      button(actions, "Mappings of " + source.name, source.href + "#mappings");
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
      if (selection.node) {
        if (isAsset(selection.node.data)) assetDetail(selection.node.data);
        else if (selection.node.data.kind === "mapped") mappedDetail(selection.node.data);
        else if (selection.node.data.kind === "part") partDetail(selection.node.data);
        else targetDetail(selection.node.data);
      } else if (selection.edge.kind === "mapping") {
        mappedDetail(selection.edge.data);
      } else if (selection.edge.kind === "part") {
        partDetail(selection.edge.data);
      } else if (selection.edge.kind === "internal") {
        internalDetail(selection.edge.data);
      } else {
        dependencyDetail(selection.edge.data);
      }
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
        [["aggregate", "lg-aggregate", "Aggregates"], ["entity", "lg-plain", "Entities"]].forEach(function (k) {
          var n = catalog.targets.filter(function (t) { return t.kind === k[0]; }).length;
          if (!n) return;
          var li = el("li");
          li.appendChild(el("span", "lg-card " + k[1]));
          li.appendChild(document.createTextNode(k[2] + " assets may depend on (" + n + ")"));
          legend.appendChild(li);
        });
        var inside = catalog.assets.reduce(function (sum, a) { return sum + a.parts.length; }, 0);
        if (inside) {
          var partItem = el("li");
          partItem.appendChild(el("span", "lg-card lg-part"));
          partItem.appendChild(document.createTextNode("Parts of assets (" + inside + ")"));
          legend.appendChild(partItem);
          var internal = el("li");
          internal.appendChild(el("span", "lg-line sw-internal"));
          internal.appendChild(document.createTextNode(" a part depends on another"));
          legend.appendChild(internal);
        }
        var derived = catalog.assets.reduce(function (sum, a) { return sum + a.mapped.length; }, 0);
        if (derived) {
          var item = el("li");
          item.appendChild(el("span", "lg-card lg-mapped"));
          item.appendChild(document.createTextNode("Mapped entities (" + derived + ")"));
          legend.appendChild(item);
        }
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
          li.appendChild(el("span", "rule-why",
            (r.targetKind === "asset" ? "" : (r.targetKind === "aggregate" ? "an aggregate" : "a plain entity") + " · ") +
            r.sanctionedBy.join("; ")));
          rules.appendChild(li);
        });
      }
    }

    // --- the board's menu and its dialog --------------------------------------------------------

    var menu = document.getElementById("board-menu");
    var dialog = document.getElementById("board-dialog");

    function closeMenu() {
      if (menu) menu.hidden = true;
    }

    function menuItem(text, onClick) {
      var b = el("button", null, text);
      b.type = "button";
      b.setAttribute("role", "menuitem");
      b.addEventListener("click", function () {
        closeMenu();
        onClick();
      });
      menu.appendChild(b);
      return b;
    }

    // A right-click on a node offers what can be done to that node; on the board, what can be
    // put on it.
    function openMenu(at) {
      if (!menu) return;
      menu.textContent = "";
      if (at.node) {
        var node = at.node.data;
        menu.appendChild(el("div", "board-menu-head", node.name));
        menuItem("Open", function () { window.location.href = node.href; });
        if (isAsset(node)) {
          menuItem("Edit", function () { window.location.href = node.href + "/edit"; });
          // The asset as a whole is what a run acts on; its parts and resources have no run of
          // their own.
          if (catalog.procedures.length) menu.appendChild(el("div", "board-menu-head", "Run"));
          catalog.procedures.forEach(function (p) {
            menuItem(p.label, function () { run(node, p); });
          });
          menu.appendChild(el("div", "board-menu-rule"));
        }
        // Not for a part or a mapped entity: neither is on the board in its own right, and
        // deleting one would change its asset without the asset knowing.
        if (isAsset(node) || node.kind === "aggregate" || node.kind === "entity") {
          menuItem("Delete", function () { remove(node); }).className = "danger";
        }
      } else {
        var groups = [["asset", "New asset"], ["aggregate", "New aggregate"], ["entity", "New entity"]];
        groups.forEach(function (group) {
          var types = catalog.creatable.filter(function (c) { return c.kind === group[0]; });
          if (!types.length) return;
          menu.appendChild(el("div", "board-menu-head", group[1]));
          types.forEach(function (c) {
            menuItem(c.type, function () { openDialog(c); });
          });
        });
        if (!menu.childNodes.length) {
          menu.appendChild(el("p", "empty", "Nothing can be created here yet."));
        }
      }
      menu.hidden = false;
      // Kept inside the window: a menu opened near the right or bottom edge would be cut off. It
      // is placed in page coordinates, so it stays where it was opened when the page scrolls —
      // closing it on scroll instead would lose it to any scroll that merely happens to follow
      // the click.
      var w = menu.offsetWidth, h = menu.offsetHeight;
      var x = Math.max(8, Math.min(at.clientX, window.innerWidth - w - 8));
      var y = Math.max(8, Math.min(at.clientY, window.innerHeight - h - 8));
      menu.style.left = (x + window.scrollX) + "px";
      menu.style.top = (y + window.scrollY) + "px";
      var first = menu.querySelector("button");
      if (first) first.focus();
    }

    document.addEventListener("mousedown", function (ev) {
      if (menu && !menu.hidden && !menu.contains(ev.target)) closeMenu();
    });
    document.addEventListener("keydown", function (ev) {
      if (ev.key === "Escape") closeMenu();
    });

    // The fields the dialog asks for: the scalar properties the type's schema declares, minus the
    // ones a run writes. Anything structured — an access policy, say — is left to the full editor.
    function dialogFields(schema) {
      var props = (schema && schema.properties) || {};
      var required = Array.isArray(schema && schema.required) ? schema.required : [];
      return Object.keys(props)
        .filter(function (key) {
          var p = props[key] || {};
          if (p.readOnly === true) return false;
          return Array.isArray(p.enum) || ["string", "number", "integer", "boolean"].indexOf(p.type || "string") !== -1;
        })
        .map(function (key) {
          return { key: key, schema: props[key] || {}, required: required.indexOf(key) !== -1 };
        });
    }

    function openDialog(creatable) {
      if (!dialog) return;
      var schema;
      try {
        schema = JSON.parse(creatable.schema || "{}");
      } catch (e) {
        schema = {};
      }
      document.getElementById("board-dialog-title").textContent = "New " + creatable.type;
      document.getElementById("board-dialog-hint").textContent =
        creatable.kind === "entity"
          ? "A plain entity: assets may depend on it, and it is not provisioned."
          : "Creates the " + creatable.kind + " itself. Its parts, and anything structured, are added from its own page afterwards.";
      var fieldsEl = document.getElementById("board-dialog-fields");
      var errorEl = document.getElementById("board-dialog-error");
      fieldsEl.textContent = "";
      errorEl.hidden = true;
      var inputs = dialogFields(schema).map(function (field) {
        var label = el("label", "board-dialog-field", field.key + (field.required ? " *" : ""));
        var type = field.schema.type || "string";
        var input;
        if (Array.isArray(field.schema.enum)) {
          input = document.createElement("select");
          input.appendChild(new Option("—", ""));
          field.schema.enum.forEach(function (v) { input.appendChild(new Option(v, v)); });
        } else {
          input = document.createElement("input");
          input.type = type === "boolean" ? "checkbox" : type === "string" ? "text" : "number";
          if (type === "integer") input.step = "1";
        }
        input.name = field.key;
        if (field.required && type !== "boolean") input.required = true;
        label.appendChild(input);
        fieldsEl.appendChild(label);
        return { field: field, input: input, type: type };
      });
      if (!inputs.length) {
        fieldsEl.appendChild(el("p", "empty", "This type declares no value to fill in here."));
      }

      document.getElementById("board-dialog-form").onsubmit = function (ev) {
        ev.preventDefault();
        var values = {};
        inputs.forEach(function (i) {
          if (i.type === "boolean") {
            if (i.input.checked) values[i.field.key] = true;
          } else if (i.input.value !== "") {
            values[i.field.key] = i.type === "string" || Array.isArray(i.field.schema.enum)
              ? i.input.value
              : Number(i.input.value);
          }
        });
        send("/ui/assets/entities", { type: creatable.type, values: JSON.stringify(values) },
          function () {
            dialog.close();
            notify("Created " + (values.name ? "«" + values.name + "»" : "a new " + creatable.type) + ".", true);
          },
          function (why) {
            // Shown in the dialog, which is still open over the page's own notices.
            errorEl.textContent = why;
            errorEl.hidden = false;
          });
      };
      document.getElementById("board-dialog-cancel").onclick = function () { dialog.close(); };
      dialog.showModal();
      if (inputs.length) inputs[0].input.focus();
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
    ["asset-show-mapped", "asset-show-parts"].forEach(function (id) {
      var toggle = document.getElementById(id);
      if (toggle) toggle.addEventListener("change", function () { if (graph) renderGraph(); });
    });
    bind("asset-fit", function () { graph.fit(); });
    bind("asset-relayout", function () { graph.relayout(); });
    bind("asset-zoom-in", function () { graph.zoomIn(); });
    bind("asset-zoom-out", function () { graph.zoomOut(); });

    render();
    setView(view);
  });
})();
