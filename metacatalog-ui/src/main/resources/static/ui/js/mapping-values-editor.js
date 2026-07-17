/*
 * Mapping-values editor for the New Mapping form.
 *
 * When a target entity type is chosen, its (derived) JSON Schema is fetched from
 * GET /metacatalog/v1/entity-type/{name} and turned into a fixed-structure editor: every object
 * property becomes a nested group and every leaf property becomes a single text input for the SpEL
 * expression that maps into it. This mirrors the server's mapping-schema conversion, where only
 * object types stay structured and all other types (scalars and arrays alike) collapse to a string.
 *
 * The user can only fill leaf values; the document structure is derived from the schema and cannot
 * be changed. On submit the tree is serialized into the hidden `mappingValues` field. Required
 * leaves (listed in a schema `required` array) must be filled — browser constraint validation blocks
 * submit otherwise. Optional leaves left blank are omitted from the serialized document.
 */
(function () {
  "use strict";

  document.addEventListener("DOMContentLoaded", function () {
    var form = document.getElementById("mapping-form");
    if (!form) return;

    var targetSelect = document.getElementById("targetEntityType");
    var container = document.getElementById("mapping-values-editor");
    var hidden = document.getElementById("mappingValues");
    if (!targetSelect || !container || !hidden) return;

    // The current editor tree. A node is either an object node
    // ({ kind: "object", children: [{ key, model }] }) or a leaf node
    // ({ kind: "leaf", input, required }). Null while no target is selected or a load failed.
    var model = null;

    function isObjectSchema(node) {
      return (
        node &&
        node.type === "object" &&
        node.properties &&
        typeof node.properties === "object"
      );
    }

    function requiredBadge() {
      var badge = document.createElement("span");
      badge.className = "mv-required";
      badge.textContent = "required";
      return badge;
    }

    function message(text, className) {
      container.innerHTML = "";
      var p = document.createElement("p");
      p.className = className;
      p.textContent = text;
      container.appendChild(p);
    }

    // Recursively render the properties of an object schema into parentEl, returning the object node.
    function renderInto(schemaNode, parentEl) {
      var props = schemaNode.properties || {};
      var requiredList = Array.isArray(schemaNode.required) ? schemaNode.required : [];
      var children = [];

      Object.keys(props).forEach(function (key) {
        var childSchema = props[key] || {};
        var isRequired = requiredList.indexOf(key) !== -1;
        var row = document.createElement("div");

        if (isObjectSchema(childSchema)) {
          row.className = "mv-group";
          var head = document.createElement("div");
          head.className = "mv-key";
          head.textContent = key;
          if (isRequired) head.appendChild(requiredBadge());
          row.appendChild(head);

          var body = document.createElement("div");
          body.className = "mv-children";
          row.appendChild(body);

          children.push({ key: key, model: renderInto(childSchema, body) });
        } else {
          row.className = "mv-leaf";
          var label = document.createElement("label");
          label.className = "mv-leaf-label";
          label.textContent = key;
          if (isRequired) label.appendChild(requiredBadge());

          var input = document.createElement("input");
          input.type = "text";
          input.className = "mv-input";
          input.setAttribute("placeholder", "SpEL expression, e.g. #source." + key);
          if (isRequired) input.required = true;
          label.appendChild(input);
          row.appendChild(label);

          children.push({ key: key, model: { kind: "leaf", input: input, required: isRequired } });
        }

        parentEl.appendChild(row);
      });

      return { kind: "object", children: children };
    }

    // Serialize an object node into a plain JS object, dropping blank (optional) leaves.
    function serialize(node) {
      var obj = {};
      node.children.forEach(function (child) {
        if (child.model.kind === "leaf") {
          var value = child.model.input.value;
          if (value != null && value.trim() !== "") {
            obj[child.key] = value;
          }
        } else {
          obj[child.key] = serialize(child.model);
        }
      });
      return obj;
    }

    // Repopulate leaf inputs from a previously submitted document (error round-trips).
    function fill(node, data) {
      if (!data || typeof data !== "object") return;
      node.children.forEach(function (child) {
        if (!Object.prototype.hasOwnProperty.call(data, child.key)) return;
        var value = data[child.key];
        if (child.model.kind === "leaf") {
          child.model.input.value = typeof value === "string" ? value : JSON.stringify(value);
        } else {
          fill(child.model, value);
        }
      });
    }

    function buildEditor(name, existingValues) {
      model = null;
      if (!name) {
        message("Select a target entity type to build the mapping document.", "empty");
        return;
      }
      message("Loading schema for '" + name + "'…", "empty");

      fetch("/metacatalog/v1/entity-type/" + encodeURIComponent(name), {
        headers: { Accept: "application/json" },
      })
        .then(function (response) {
          if (!response.ok) throw new Error("HTTP " + response.status);
          return response.json();
        })
        .then(function (dto) {
          var schema;
          try {
            schema = JSON.parse(dto.schema);
          } catch (e) {
            throw new Error("the schema is not valid JSON");
          }
          if (!isObjectSchema(schema) || Object.keys(schema.properties || {}).length === 0) {
            model = { kind: "object", children: [] };
            message("Target '" + name + "' has no properties; the mapping document is empty.", "empty");
            return;
          }
          container.innerHTML = "";
          model = renderInto(schema, container);
          if (existingValues) {
            try {
              fill(model, JSON.parse(existingValues));
            } catch (e) {
              /* ignore a malformed previous value */
            }
          }
        })
        .catch(function (err) {
          model = null;
          message("Could not load schema for '" + name + "': " + err.message, "error");
        });
    }

    targetSelect.addEventListener("change", function () {
      buildEditor(targetSelect.value, null);
    });

    // The submit event only fires once native constraint validation passes, so required leaves are
    // already guaranteed non-empty here. Serialize the tree into the hidden field before posting.
    form.addEventListener("submit", function (e) {
      if (model === null) {
        e.preventDefault();
        message(
          "Select a target entity type and fill the mapping values before submitting.",
          "error"
        );
        return;
      }
      hidden.value = JSON.stringify(serialize(model), null, 2);
    });

    // Rebuild on load: after a validation error the form comes back with the target and the
    // previously entered values, which we restore into the freshly built editor.
    buildEditor(targetSelect.value, hidden.value);
  });
})();
