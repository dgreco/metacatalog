/*
 * Schema-driven values editor for the unified instance manager (New / Edit instance).
 *
 * When an entity type is chosen, its (derived) JSON Schema is fetched from
 * GET /metacatalog/v1/entity-type/{name} and turned into a typed data-entry form: every object
 * property becomes a nested group and every leaf property becomes a typed input (text, number,
 * checkbox, or enum select). Arrays become a repeatable list of the items schema with add/remove
 * buttons. On submit the tree is serialized into the hidden `values` field. Required leaves (listed
 * in a schema `required` array) must be filled — browser constraint validation blocks submit
 * otherwise.
 *
 * A "Raw JSON" tab lets the user edit the document directly; switching back to the Builder tab
 * re-parses and re-fills the form. In edit mode the editor is seeded from the existing values.
 *
 * Anything the editor cannot render (e.g. oneOf/allOf/$ref) falls back to the Raw JSON tab only.
 */
(function () {
  "use strict";

  document.addEventListener("DOMContentLoaded", function () {
    var form = document.getElementById("instance-form");
    if (!form) return;
    var READ_ONLY = window.__READ_ONLY__ === true;

    var typeSelect = document.getElementById("entityType");
    var container = document.getElementById("instance-values-editor");
    var hidden = document.getElementById("values");
    var rawTextarea = document.getElementById("instance-raw-values");
    var validation = document.getElementById("instance-validation");
    if (!typeSelect || !container || !hidden) return;

    var model = null; // current builder tree; null while no schema is loaded
    var currentSchema = null; // last fetched schema object (for Raw->Builder rebuild)
    var mode = "builder";

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

    function readOnlyBadge() {
      var badge = document.createElement("span");
      badge.className = "mv-readonly";
      badge.textContent = "read-only";
      return badge;
    }

    function message(text, className) {
      container.innerHTML = "";
      var p = document.createElement("p");
      p.className = className || "empty";
      p.textContent = text;
      container.appendChild(p);
    }

    function showValidation(text, ok) {
      if (!validation) return;
      validation.textContent = text || "";
      validation.className = "validation" + (ok ? " ok" : " err");
    }

    function parseLeaf(input, type) {
      if (type === "boolean") return input.checked;
      var v = input.value;
      if (v === null || v.trim() === "") return undefined;
      if (type === "integer") {
        var n = parseInt(v, 10);
        return isNaN(n) ? v : n;
      }
      if (type === "number") {
        var f = parseFloat(v);
        return isNaN(f) ? v : f;
      }
      return v;
    }

    // Builds a typed input for a scalar/enum leaf schema, returning a leaf model node.
    function renderLeaf(schema, isRequired) {
      var type = schema.type || "string";
      var isReadOnly = schema.readOnly === true;
      var input;
      if (Array.isArray(schema.enum)) {
        input = document.createElement("select");
        var placeholder = document.createElement("option");
        placeholder.value = "";
        placeholder.textContent = "— Select —";
        input.appendChild(placeholder);
        schema.enum.forEach(function (val) {
          var opt = document.createElement("option");
          opt.value = val;
          opt.textContent = val;
          input.appendChild(opt);
        });
      } else if (type === "boolean") {
        input = document.createElement("input");
        input.type = "checkbox";
      } else if (type === "integer" || type === "number") {
        input = document.createElement("input");
        input.type = "number";
        if (type === "integer") input.step = "1";
        if (schema.minimum != null) input.min = schema.minimum;
        if (schema.maximum != null) input.max = schema.maximum;
      } else {
        input = document.createElement("input");
        input.type = "text";
      }
      input.className = "mv-input";
      if (isReadOnly || READ_ONLY) input.disabled = true;
      if (isRequired && type !== "boolean" && !isReadOnly && !READ_ONLY) input.required = true;
      return { kind: "leaf", input: input, type: type, required: isRequired, readOnly: isReadOnly };
    }

    // Renders the properties of an object schema into parentEl, returning an object model node.
    function renderObject(schemaNode, parentEl) {
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

          children.push({ key: key, model: renderObject(childSchema, body) });
          parentEl.appendChild(row);
        } else if (childSchema.type === "array") {
          row.className = "mv-group";
          var ahead = document.createElement("div");
          ahead.className = "mv-key";
          ahead.textContent = key;
          if (isRequired) ahead.appendChild(requiredBadge());
          row.appendChild(ahead);

          var abody = document.createElement("div");
          abody.className = "mv-children";
          row.appendChild(abody);

          children.push({ key: key, model: renderArray(childSchema, abody) });
          parentEl.appendChild(row);
        } else {
          row.className = "mv-leaf";
          var label = document.createElement("label");
          label.className = "mv-leaf-label";
          label.textContent = key;
          if (isRequired) label.appendChild(requiredBadge());
          if (childSchema.readOnly === true) label.appendChild(readOnlyBadge());

          var leafModel = renderLeaf(childSchema, isRequired);
          label.appendChild(leafModel.input);
          row.appendChild(label);

          children.push({ key: key, model: leafModel });
          parentEl.appendChild(row);
        }
      });

      return { kind: "object", children: children };
    }

    // Renders a single array item's schema into parentEl, returning the item model.
    function renderItem(schema, parentEl) {
      if (isObjectSchema(schema)) return renderObject(schema, parentEl);
      var label = document.createElement("label");
      label.className = "mv-leaf-label";
      var m = renderLeaf(schema, false);
      label.appendChild(m.input);
      parentEl.appendChild(label);
      return m;
    }

    // Renders an array as a repeatable list of the items schema, returning an array model node.
    function renderArray(schema, parentEl) {
      var itemsSchema = schema.items || { type: "string" };
      var itemNodes = [];

      var listEl = document.createElement("div");
      listEl.className = "iv-array-list";
      parentEl.appendChild(listEl);

      function addItem(value) {
        var itemRow = document.createElement("div");
        itemRow.className = "iv-array-item";
        var itemModel = renderItem(itemsSchema, itemRow);
        if (value !== undefined) setModelValue(itemModel, value);

        if (!READ_ONLY) {
          var removeBtn = document.createElement("button");
          removeBtn.type = "button";
          removeBtn.className = "link-remove";
          removeBtn.title = "Remove";
          removeBtn.textContent = "×";
          removeBtn.addEventListener("click", function () {
            itemRow.remove();
            var idx = itemNodes.indexOf(itemModel);
            if (idx !== -1) itemNodes.splice(idx, 1);
          });
          itemRow.appendChild(removeBtn);
        }

        listEl.appendChild(itemRow);
        itemNodes.push(itemModel);
      }

      if (!READ_ONLY) {
        var addBtn = document.createElement("button");
        addBtn.type = "button";
        addBtn.className = "btn";
        addBtn.textContent = "+ Add item";
        addBtn.addEventListener("click", function () {
          addItem();
        });
        parentEl.appendChild(addBtn);
      }

      return { kind: "array", items: itemNodes, addItem: addItem };
    }

    // Serializes a model node into a plain JS value, dropping blank optional leaves.
    function serialize(node) {
      if (node.kind === "leaf") {
        return parseLeaf(node.input, node.type);
      }
      if (node.kind === "array") {
        return node.items
          .map(function (itemModel) {
            return serialize(itemModel);
          })
          .filter(function (v) {
            return v !== undefined;
          });
      }
      // object
      var obj = {};
      node.children.forEach(function (child) {
        var sv = serialize(child.model);
        if (sv !== undefined) obj[child.key] = sv;
      });
      return obj;
    }

    // Populates a model node from a plain JS value (used to seed the editor in edit mode and on
    // Raw -> Builder switches).
    function setModelValue(node, value) {
      if (value === null || value === undefined) return;
      if (node.kind === "leaf") {
        if (node.type === "boolean") node.input.checked = !!value;
        else node.input.value = String(value);
      } else if (node.kind === "object") {
        node.children.forEach(function (child) {
          if (value && Object.prototype.hasOwnProperty.call(value, child.key)) {
            setModelValue(child.model, value[child.key]);
          }
        });
      } else if (node.kind === "array") {
        if (Array.isArray(value)) value.forEach(function (v) { node.addItem(v); });
      }
    }

    // Clears the container and renders the editor from the given schema, optionally seeded with
    // existing values. Falls back to a Raw-only message when the schema is not a renderable object.
    function renderEditor(schema, existingValues) {
      model = null;
      container.innerHTML = "";
      showValidation("", true);

      if (!isObjectSchema(schema) || Object.keys(schema.properties || {}).length === 0) {
        message(
          "This type has no structured properties — use the Raw JSON tab to enter values.",
          "empty"
        );
        return;
      }
      model = renderObject(schema, container);
      if (existingValues) {
        try {
          setModelValue(model, JSON.parse(existingValues));
        } catch (e) {
          /* ignore malformed previous value */
        }
      }
    }

    function buildEditor(name, existingValues) {
      currentSchema = null;
      model = null;
      if (!name) {
        message("Select an entity type to build the values.", "empty");
        return;
      }
      var schemas = window.__TYPE_SCHEMAS__ || {};
      var schemaStr = schemas[name];
      if (!schemaStr) {
        model = null;
        message("Could not load schema for '" + name + "': not found", "error");
        return;
      }
      try {
        var schema = JSON.parse(schemaStr);
        currentSchema = schema;
        renderEditor(schema, existingValues);
        if (rawTextarea && !rawTextarea.value && existingValues) {
          rawTextarea.value = existingValues;
        }
      } catch (err) {
        model = null;
        message("Could not load schema for '" + name + "': " + err.message, "error");
      }
    }

    function setMode(next) {
      mode = next;
      form.querySelectorAll(".tab").forEach(function (t) {
        t.classList.toggle("active", t.dataset.tab === next);
      });
      form.querySelectorAll(".tab-panel").forEach(function (p) {
        p.classList.toggle("hidden", p.dataset.panel !== next);
      });
    }

    function switchTab(next) {
      if (next === mode) return;
      if (next === "raw") {
        if (model) rawTextarea.value = JSON.stringify(serialize(model), null, 2);
        setMode("raw");
      } else {
        try {
          var parsed = rawTextarea.value.trim() ? JSON.parse(rawTextarea.value) : {};
          if (currentSchema) renderEditor(currentSchema, JSON.stringify(parsed));
          setMode("builder");
        } catch (e) {
          showValidation("Raw JSON is invalid: " + e.message, false);
        }
      }
    }

    // Builder/raw switching is an edit-mode concern; in read-only mode the page has its own
    // Builder / JSON / YAML tabs with their own (inline) toggle, so binding here would fight it.
    if (!READ_ONLY) {
      form.querySelectorAll(".tab").forEach(function (t) {
        t.addEventListener("click", function () {
          switchTab(t.dataset.tab);
        });
      });
    }

    typeSelect.addEventListener("change", function () {
      buildEditor(typeSelect.value, null);
    });

    form.addEventListener("submit", function (e) {
      if (mode === "raw") {
        try {
          JSON.parse(rawTextarea.value);
          hidden.value = rawTextarea.value;
        } catch (err) {
          e.preventDefault();
          showValidation("Raw JSON is invalid: " + err.message, false);
        }
        return;
      }
      if (model === null) {
        e.preventDefault();
        message("Select an entity type and fill the values before submitting.", "error");
        return;
      }
      hidden.value = JSON.stringify(serialize(model), null, 2);
    });

    // Rebuild on load: in edit mode the type is pre-selected and the hidden field carries the
    // existing values; after a validation error the form comes back with the same values.
    buildEditor(typeSelect.value, hidden.value);
  });
})();
