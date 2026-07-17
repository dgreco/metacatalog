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

      var field = el("div", "field");
      var head = el("div", "field-head");
      head.appendChild(el("span", "f-name", key));
      head.appendChild(badge(type));
      if (requiredList.indexOf(key) !== -1) head.appendChild(badge("required", "req"));
      if (prop.format) head.appendChild(badge(prop.format, "muted"));
      if (Array.isArray(prop.enum)) head.appendChild(badge("enum: " + prop.enum.join(", "), "muted"));
      field.appendChild(head);

      var childSchema = null;
      if (type === "object" && prop.properties) {
        childSchema = prop;
      } else if (type === "array" && prop.items) {
        head.appendChild(badge("items: " + typeLabel(prop.items), "muted"));
        if (prop.items.type === "object" && prop.items.properties) childSchema = prop.items;
      }
      if (childSchema) {
        var children = el("div", "field-children");
        children.appendChild(schemaPropList(childSchema));
        field.appendChild(children);
      }

      list.appendChild(field);
    });

    return list;
  }

  function renderSchema(container, schema) {
    if (!schema || typeof schema !== "object" || !schema.properties || !Object.keys(schema.properties).length) {
      container.appendChild(note("No properties."));
      return;
    }
    container.appendChild(schemaPropList(schema));
  }

  // --- Mapping values ----------------------------------------------------------------------------

  function mappingPropList(obj) {
    var list = el("div", "prop-list");
    Object.keys(obj).forEach(function (key) {
      var value = obj[key];
      var field = el("div", "field");
      var head = el("div", "field-head");
      head.appendChild(el("span", "f-name", key));

      if (value && typeof value === "object" && !Array.isArray(value)) {
        field.appendChild(head);
        var children = el("div", "field-children");
        children.appendChild(mappingPropList(value));
        field.appendChild(children);
      } else {
        head.appendChild(el("code", "sv-expr", typeof value === "string" ? value : JSON.stringify(value)));
        field.appendChild(head);
      }
      list.appendChild(field);
    });
    return list;
  }

  function renderMapping(container, obj) {
    if (!obj || typeof obj !== "object" || Array.isArray(obj) || !Object.keys(obj).length) {
      container.appendChild(note("Empty."));
      return;
    }
    container.appendChild(mappingPropList(obj));
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

  function process(selector, render) {
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
      render(container, parsed);
    });
  }

  document.addEventListener("DOMContentLoaded", function () {
    process(".js-schema", renderSchema);
    process(".js-mapping", renderMapping);
    process(".js-pathrefs", renderPathRefs);
  });
})();
