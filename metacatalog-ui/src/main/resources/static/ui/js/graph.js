/*
 * Model graph (Administration): every trait and entity type, and what relates them.
 *
 * Reads the graph model embedded in #graph-data — nodes are traits and entity types (and, when
 * asked for, entities and past versions); edges are inheritance, the traits a trait mixes in, the
 * traits a type carries, trait relationships and entity-type mappings — and the roles embedded in
 * #graph-roles, which say which traits are built in and which types are asset types, aggregate
 * roots, provisionable or authorizable.
 *
 * Drawing, layout, panning and selection are graph-core.js's. This script decides what the model
 * looks like on top of it: which band a node belongs to (traits above types above entities), which
 * edges order the rows, how nodes are badged, which edges each filter chip hides, and what the
 * side panel says about the node or edge that is selected.
 */
(function () {
  "use strict";

  // Edge kinds per filter chip. A kind that is listed nowhere is a trait relationship
  // (DEPENDS_ON, HAS_PART and their inverses), which is why "relationships" has no list.
  var EDGE_GROUPS = {
    inheritance: ["extends", "mixes-in"],
    traits: ["has-trait"],
    mappings: ["mapping"],
    versions: ["successor-of", "instance-of"]
  };
  var VERSION_KINDS = ["traitVersion", "entityTypeVersion", "entity", "entityVersion"];

  function groupOf(edgeKind) {
    for (var group in EDGE_GROUPS) {
      if (EDGE_GROUPS[group].indexOf(edgeKind) !== -1) return group;
    }
    return "relationships";
  }

  function el(tag, className, text) {
    var e = document.createElement(tag);
    if (className) e.className = className;
    if (text != null) e.textContent = text;
    return e;
  }

  function parse(id, fallback) {
    var node = document.getElementById(id);
    if (!node) return fallback;
    try {
      return JSON.parse(node.textContent) || fallback;
    } catch (e) {
      return fallback;
    }
  }

  document.addEventListener("DOMContentLoaded", function () {
    var canvas = document.getElementById("graph-canvas");
    if (!canvas || !window.MetacatalogGraphCore) return;

    var roles = parse("graph-roles", {});
    var is = function (list, name) { return (roles[list] || []).indexOf(name) !== -1; };

    var idle = document.getElementById("graph-panel-idle");
    var detail = document.getElementById("graph-panel-detail");
    var empty = document.getElementById("graph-empty");
    var search = document.getElementById("graph-search");
    var inverses = document.getElementById("graph-show-inverses");
    var entities = document.getElementById("graph-show-entities");

    var graph = null;
    var model = { nodes: [], edges: [] };

    // --- model → renderer -----------------------------------------------------------------------

    var KIND_LABEL = {
      trait: "trait", entityType: "entity type", traitVersion: "trait version",
      entityTypeVersion: "type version", entity: "entity", entityVersion: "entity version"
    };

    function bandOf(node) {
      if (node.kind === "trait" || node.kind === "traitVersion") return 0;
      if (node.kind === "entityType" || node.kind === "entityTypeVersion") return 1;
      return 2;
    }

    function toNode(n) {
      var kind = n.kind;
      var badges = [];
      if (kind === "trait" && is("builtInTraits", n.label)) {
        kind = "builtin";
        badges.push({ text: "built-in", tone: "plain", title: "Installed by the platform; cannot be deleted or versioned" });
      }
      if (n.kind === "entityType") {
        if (is("assetTypes", n.label)) {
          kind = "asset";
          badges.push({ text: "asset", tone: "accent", title: "An asset type: an aggregate root carrying the Asset trait" });
        } else if (is("rootTypes", n.label)) {
          badges.push({ text: "root", tone: "plain", title: "An aggregate root type" });
        }
      }
      return {
        id: n.id, title: n.label, subtitle: KIND_LABEL[n.kind] || n.kind, kind: kind,
        badges: badges, data: n, modelKind: n.kind
      };
    }

    function toEdge(e) {
      var plain = e.kind === "extends" || e.kind === "has-trait" || e.kind === "instance-of" || e.kind === "successor-of";
      var kind = e.kind;
      if (groupOf(e.kind) === "relationships") kind = "rel";
      return { source: e.source, target: e.target, kind: kind, label: plain ? "" : e.label, data: e, modelKind: e.kind };
    }

    function ranked(edge) {
      // Only what is a hierarchy orders the rows; a relationship between two traits says nothing
      // about which of them is the more general.
      return ["extends", "mixes-in", "successor-of"].indexOf(edge.modelKind) !== -1;
    }

    function render(data) {
      model = data || { nodes: [], edges: [] };
      var has = model.nodes && model.nodes.length;
      if (empty) empty.hidden = !!has;
      var nodes = (model.nodes || []).map(toNode);
      var edges = (model.edges || []).map(toEdge);
      if (!graph) {
        graph = window.MetacatalogGraphCore.create(canvas, {
          nodes: nodes, edges: edges, ranked: ranked,
          band: function (n) { return bandOf(n.data); },
          label: "Model graph", onSelect: onSelect
        });
        if (empty) canvas.appendChild(empty);
      } else {
        graph.setData(nodes, edges, false);
      }
      applyChips();
      applySearch();
      counts();
      showIdle();
    }

    function counts() {
      var set = function (id, value) {
        var node = document.getElementById(id);
        if (node) node.textContent = String(value);
      };
      var nodes = model.nodes || [], edges = model.edges || [];
      set("count-traits", nodes.filter(function (n) { return n.kind === "trait"; }).length);
      var types = nodes.filter(function (n) { return n.kind === "entityType"; });
      set("count-types", types.length);
      set("count-assets", types.filter(function (n) { return is("assetTypes", n.label); }).length);
      set("count-relationships", edges.filter(function (e) {
        return groupOf(e.kind) === "relationships" && ["DEPENDS_ON", "HAS_PART"].indexOf(e.kind) !== -1;
      }).length);
      set("count-mappings", edges.filter(function (e) { return e.kind === "mapping" && e.label === "MAPPED_TO"; }).length);
    }

    // --- filters --------------------------------------------------------------------------------

    function applyChips() {
      if (!graph) return;
      var hidden = [];
      document.querySelectorAll("[data-edge-group]").forEach(function (chip) {
        if (chip.classList.contains("active")) return;
        var group = chip.getAttribute("data-edge-group");
        hidden = hidden.concat(group === "relationships" ? ["rel"] : EDGE_GROUPS[group]);
      });
      graph.setHiddenEdgeKinds(hidden);
    }

    function applySearch() {
      if (!graph) return;
      var text = search ? search.value.trim().toLowerCase() : "";
      graph.setFilter(text ? function (n) { return n.title.toLowerCase().indexOf(text) !== -1; } : null);
    }

    document.querySelectorAll("[data-edge-group]").forEach(function (chip) {
      chip.setAttribute("aria-pressed", "true");
      chip.addEventListener("click", function () {
        var active = chip.classList.toggle("active");
        chip.setAttribute("aria-pressed", String(active));
        applyChips();
      });
    });
    if (search) search.addEventListener("input", applySearch);

    // --- side panel -----------------------------------------------------------------------------

    function showIdle() {
      if (detail) detail.hidden = true;
      if (idle) idle.hidden = false;
    }

    function row(parent, key, value) {
      var r = el("div", "graph-panel-row");
      r.appendChild(el("span", "graph-panel-k", key));
      var v = el("span", "graph-panel-v");
      if (Array.isArray(value)) {
        value.forEach(function (item) { v.appendChild(el("span", "tag", item)); });
      } else {
        v.textContent = value;
      }
      r.appendChild(v);
      parent.appendChild(r);
    }

    function tree(parent, title, raw, render) {
      parent.appendChild(el("h3", null, title));
      var body = el("div", "sv");
      parent.appendChild(body);
      var view = window.MetacatalogView;
      if (view && raw) {
        try {
          render(view, body, JSON.parse(raw));
          return;
        } catch (e) {
          /* fall through to the placeholder */
        }
      }
      body.appendChild(el("p", "empty", "—"));
    }

    function action(parent, text, href) {
      var a = el("a", "btn-sm", text);
      a.href = href;
      parent.appendChild(a);
    }

    function nodeDetail(n) {
      var d = n.data;
      detail.textContent = "";
      detail.appendChild(el("div", "graph-panel-kind", KIND_LABEL[d.kind] || d.kind));
      detail.appendChild(el("h2", null, d.label));

      var facts = [];
      if (d.kind === "trait" && is("builtInTraits", d.label)) facts.push("built-in");
      if (d.kind === "entityType") {
        if (is("assetTypes", d.label)) facts.push("asset type");
        if (is("rootTypes", d.label)) facts.push("aggregate root");
        if (is("provisionableTypes", d.label)) facts.push("provisionable");
        if (is("authorizableTypes", d.label)) facts.push("authorizable");
      }
      if (facts.length) row(detail, "is", facts);
      if (d.father) row(detail, d.kind === "entity" || d.kind === "entityVersion" ? "instance of" : "inherits from", d.father);
      if (d.traits && d.traits.length) row(detail, d.kind === "trait" ? "mixes in" : "carries", d.traits);

      var name = encodeURIComponent(d.label);
      var actions = el("div", "graph-panel-actions");
      if (d.kind === "trait") {
        action(actions, "Versions", "/ui/admin/traits/" + name + "/versions");
        if (!is("builtInTraits", d.label)) action(actions, "New version", "/ui/admin/traits/" + name + "/versions/new");
      } else if (d.kind === "entityType") {
        action(actions, "Versions", "/ui/admin/entity-types/" + name + "/versions");
        action(actions, "New version", "/ui/admin/entity-types/" + name + "/versions/new");
        action(actions, "Instances", "/ui/admin/instances?type=" + name);
        if (is("assetTypes", d.label)) action(actions, "Assets", "/ui/assets?type=" + name);
      }
      if (actions.childNodes.length) detail.appendChild(actions);

      if (d.kind === "entity" || d.kind === "entityVersion") {
        tree(detail, "Values", d.schema, function (v, c, parsed) { v.mapping(c, parsed, true); });
      } else {
        tree(detail, "Schema", d.schema, function (v, c, parsed) { v.schema(c, parsed, true); });
      }
    }

    var EDGE_TEXT = {
      extends: "inherits from", "mixes-in": "mixes in", "has-trait": "carries the trait",
      "instance-of": "is an instance of", "successor-of": "succeeds", mapping: "is mapped to"
    };

    function edgeDetail(e) {
      var d = e.data;
      var byId = {};
      (model.nodes || []).forEach(function (n) { byId[n.id] = n; });
      var s = byId[d.source], t = byId[d.target];
      detail.textContent = "";
      detail.appendChild(el("div", "graph-panel-kind", d.kind === "mapping" ? "mapping" : groupOf(d.kind) === "relationships" ? "trait relationship" : "edge"));
      detail.appendChild(el("h2", null, (s ? s.label : d.source) + " → " + (t ? t.label : d.target)));
      row(detail, "says", (s ? s.label : "") + " " + (groupOf(d.kind) === "relationships" || d.kind === "mapping" ? d.label : EDGE_TEXT[d.kind] || d.label) + " " + (t ? t.label : ""));

      if (d.kind === "mapping") {
        var actions = el("div", "graph-panel-actions");
        var forward = d.label === "MAPPED_TO";
        action(actions, "Edit mapping", "/ui/admin/mappings/new?source=" +
          encodeURIComponent(forward ? s.label : t.label) + "&target=" +
          encodeURIComponent(forward ? t.label : s.label));
        detail.appendChild(actions);
        tree(detail, "Mapping values", d.mappingValues, function (v, c, parsed) { v.mapping(c, parsed, true); });
        tree(detail, "Path references", d.entityPathReferences, function (v, c, parsed) { v.pathRefs(c, parsed); });
      } else if (groupOf(d.kind) === "relationships") {
        detail.appendChild(el("p", "hint",
          "Entities of types carrying " + (s ? s.label : "the source trait") + " may be linked by " +
          d.label + " to entities of types carrying " + (t ? t.label : "the target trait") + "."));
      }
    }

    function onSelect(selection) {
      if (!selection) {
        showIdle();
        return;
      }
      if (idle) idle.hidden = true;
      detail.hidden = false;
      if (selection.node) nodeDetail(selection.node);
      else edgeDetail(selection.edge);
    }

    // --- toolbar --------------------------------------------------------------------------------

    function bind(id, fn) {
      var b = document.getElementById(id);
      if (b) b.addEventListener("click", function () { if (graph) fn(); });
    }
    bind("graph-fit", function () { graph.fit(); });
    bind("graph-relayout", function () { graph.relayout(); });
    bind("graph-zoom-in", function () { graph.zoomIn(); });
    bind("graph-zoom-out", function () { graph.zoomOut(); });

    function reload() {
      var showInverses = inverses ? inverses.checked : true;
      var showEntities = entities ? entities.checked : false;
      var url = new URL(window.location.href);
      url.searchParams.set("showInverses", String(showInverses));
      url.searchParams.set("showEntities", String(showEntities));
      window.history.replaceState({}, "", url);
      fetch("/ui/admin/graph/data?showInverses=" + showInverses + "&showEntities=" + showEntities)
        .then(function (r) { return r.json(); })
        .then(render)
        .catch(function () { /* keep the current graph on error */ });
    }
    if (inverses) inverses.addEventListener("change", reload);
    if (entities) entities.addEventListener("change", reload);

    // Past versions and instances only exist once something has been versioned or created; with
    // none, the chip that hides them has nothing to do.
    var first = parse("graph-data", { nodes: [], edges: [] });
    render(first);
    var versionChip = document.querySelector("[data-edge-group='versions']");
    if (versionChip) {
      versionChip.hidden = !(first.nodes || []).some(function (n) {
        return VERSION_KINDS.indexOf(n.kind) !== -1;
      }) && !(entities && entities.checked);
      if (entities) entities.addEventListener("change", function () { versionChip.hidden = false; });
    }
  });
})();
