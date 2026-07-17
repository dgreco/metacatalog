/*
 * Read-only structured viewers for the dashboard.
 *
 * Replaces the raw-JSON <pre> blocks with a tree representation styled like the schema editor:
 *
 *  - .js-schema    : a JSON Schema -> nested property rows (name, type, required, format, enum),
 *                    recursing into object properties and array item schemas.
 *  - .js-mapping   : a mapping-values document -> the same object structure, each leaf showing the
 *                    SpEL expression that maps into it.
 *  - .js-pathrefs  : the entity path references array -> "alias -> reference path" rows.
 *
 * Each element carries its source JSON as text content (rendered server-side), so the page still
 * shows the raw document if scripting is unavailable. Parsing happens client-side.
 */
(function () {
  "use strict";

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  function badge(text, extraClass) {
    return el("span", "tag" + (extraClass ? " " + extraClass : ""), text);
  }

  function note(text) {
    return el("p", "empty sv-note", text);
  }

  function typeLabel(schema) {
    if (!schema || typeof schema !== "object") return "any";
    if (Array.isArray(schema.type)) return schema.type.join(" | ");
    if (schema.type) return schema.type;
    if (schema.enum) return "enum";
    return "any";
  }

  // --- JSON Schema -------------------------------------------------------------------------------

  function schemaPropList(schema) {
    var props = (schema && schema.properties) || {};
    var requiredList = Array.isArray(schema && schema.required) ? schema.required : [];
    var list = el("div", "prop-list");

    Object.keys(props).forEach(function (key) {
      var prop = props[key] || {};
      var type = typeLabel(prop);

      var childSchema = null;
      if (type === "object" && prop.properties) {
        childSchema = prop;
      } else if (type === "array" && prop.items && prop.items.type === "object" && prop.items.properties) {
        childSchema = prop.items;
      }

      // The badges shown for the property, shared by leaf and foldable rows.
      function fillHead(head) {
        head.appendChild(el("span", "f-name", key));
        head.appendChild(badge(type));
        if (requiredList.indexOf(key) !== -1) head.appendChild(badge("required", "req"));
        if (prop.format) head.appendChild(badge(prop.format, "muted"));
        if (Array.isArray(prop.enum)) head.appendChild(badge("enum: " + prop.enum.join(", "), "muted"));
        if (type === "array" && prop.items) head.appendChild(badge("items: " + typeLabel(prop.items), "muted"));
      }

      if (childSchema) {
        // Foldable branch: a <details> whose summary is the property head. Open by default so the
        // structure is visible once the parent schema is expanded; the user can collapse branches.
        var details = el("details", "field sv-node");
        details.open = true;
        var summary = el("summary", "field-head");
        fillHead(summary);
        details.appendChild(summary);
        var children = el("div", "field-children");
        children.appendChild(schemaPropList(childSchema));
        details.appendChild(children);
        list.appendChild(details);
      } else {
        var field = el("div", "field");
        var head = el("div", "field-head");
        fillHead(head);
        field.appendChild(head);
        list.appendChild(field);
      }
    });

    return list;
  }

  function renderSchema(container, schema, expanded) {
    var count =
      schema && typeof schema === "object" && schema.properties
        ? Object.keys(schema.properties).length
        : 0;
    if (!count) {
      container.appendChild(note("No properties."));
      return;
    }
    // Collapse the whole schema by default to keep the dashboard uncluttered.
    var details = el("details", "sv-fold");
    if (expanded) details.open = true;
    var summary = el("summary", "sv-summary");
    summary.appendChild(el("span", null, count + (count === 1 ? " property" : " properties")));
    details.appendChild(summary);
    details.appendChild(schemaPropList(schema));
    container.appendChild(details);
  }

  // --- Mapping values ----------------------------------------------------------------------------

  function mappingPropList(obj) {
    var list = el("div", "prop-list");
    Object.keys(obj).forEach(function (key) {
      var value = obj[key];

      if (value && typeof value === "object" && !Array.isArray(value)) {
        // Foldable branch for a nested object, open by default.
        var details = el("details", "field sv-node");
        details.open = true;
        var summary = el("summary", "field-head");
        summary.appendChild(el("span", "f-name", key));
        details.appendChild(summary);
        var children = el("div", "field-children");
        children.appendChild(mappingPropList(value));
        details.appendChild(children);
        list.appendChild(details);
      } else {
        var field = el("div", "field");
        var head = el("div", "field-head");
        head.appendChild(el("span", "f-name", key));
        head.appendChild(el("code", "sv-expr", typeof value === "string" ? value : JSON.stringify(value)));
        field.appendChild(head);
        list.appendChild(field);
      }
    });
    return list;
  }

  function renderMapping(container, obj, expanded) {
    if (!obj || typeof obj !== "object" || Array.isArray(obj) || !Object.keys(obj).length) {
      container.appendChild(note("Empty."));
      return;
    }
    var count = Object.keys(obj).length;
    // Collapse the whole document by default to keep the mappings table uncluttered.
    var details = el("details", "sv-fold");
    if (expanded) details.open = true;
    var summary = el("summary", "sv-summary");
    summary.appendChild(el("span", null, count + (count === 1 ? " field" : " fields")));
    details.appendChild(summary);
    details.appendChild(mappingPropList(obj));
    container.appendChild(details);
  }

  // --- Path references ---------------------------------------------------------------------------

  function renderPathRefs(container, arr) {
    if (!Array.isArray(arr) || !arr.length) {
      container.appendChild(note("None."));
      return;
    }
    var list = el("div", "prop-list");
    arr.forEach(function (ref) {
      var field = el("div", "field");
      var head = el("div", "field-head");
      head.appendChild(el("span", "f-name", ref.alias));
      head.appendChild(badge("→"));
      head.appendChild(el("code", "sv-expr", ref.referencePath));
      field.appendChild(head);
      list.appendChild(field);
    });
    container.appendChild(list);
  }

  // --- Wiring ------------------------------------------------------------------------------------

  // --- Hover popup: full, fully-expanded content on mouse-over --------------------------------

  var popup = null;
  var hideTimer = null;

  function ensurePopup() {
    if (popup) return popup;
    popup = el("div", "sv sv-popup");
    popup.style.display = "none";
    popup.addEventListener("mouseenter", cancelHide);
    popup.addEventListener("mouseleave", scheduleHide);
    document.body.appendChild(popup);
    return popup;
  }

  function cancelHide() {
    if (hideTimer) {
      clearTimeout(hideTimer);
      hideTimer = null;
    }
  }

  function scheduleHide() {
    cancelHide();
    hideTimer = setTimeout(function () {
      if (popup) popup.style.display = "none";
    }, 150);
  }

  function positionPopup(p, container) {
    var rect = container.getBoundingClientRect();
    var margin = 8;
    var pw = p.offsetWidth || 360;
    var ph = p.offsetHeight || 240;
    var left = rect.right + margin;
    if (left + pw > window.innerWidth - margin) left = rect.left - pw - margin;
    if (left < margin) left = margin;
    var top = rect.top;
    if (top + ph > window.innerHeight - margin) top = Math.max(margin, window.innerHeight - ph - margin);
    p.style.left = left + "px";
    p.style.top = top + "px";
  }

  function showPopup(container, render, data, label) {
    var p = ensurePopup();
    p.textContent = "";
    p.appendChild(el("div", "sv-popup-title", label));
    var body = el("div", "sv");
    render(body, data, true); // expanded: show everything
    p.appendChild(body);
    p.style.display = "block";
    positionPopup(p, container);
  }

  function attachHover(container, render, data, label) {
    container.classList.add("sv-hoverable");
    container.addEventListener("mouseenter", function () {
      cancelHide();
      showPopup(container, render, data, label);
    });
    container.addEventListener("mouseleave", scheduleHide);
  }

  // --- Wiring ------------------------------------------------------------------------------------

  function process(selector, render, hoverLabel) {
    document.querySelectorAll(selector).forEach(function (container) {
      var raw = container.textContent.trim();
      container.textContent = "";
      container.classList.add("sv");
      if (!raw) {
        container.appendChild(note("—"));
        return;
      }
      var parsed;
      try {
        parsed = JSON.parse(raw);
      } catch (e) {
        // Not valid JSON: fall back to showing the original text.
        container.appendChild(el("pre", "schema", raw));
        return;
      }
      render(container, parsed, false);
      if (hoverLabel) attachHover(container, render, parsed, hoverLabel);
    });
  }

  document.addEventListener("DOMContentLoaded", function () {
    process(".js-schema", renderSchema, "Schema");
    process(".js-mapping", renderMapping, "Mapping values");
    process(".js-pathrefs", renderPathRefs, null);
  });

  // Public API so other pages (e.g. the graph) can render the same read-only trees.
  if (typeof window !== "undefined") {
    window.MetacatalogView = {
      schema: renderSchema, // (container, schemaObject, expanded)
      mapping: renderMapping, // (container, valuesObject, expanded)
      pathRefs: renderPathRefs, // (container, referencesArray)
      note: note, // (text) -> muted <p>
    };
  }
})();
