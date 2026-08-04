/*
 * Schema-driven aggregate editor.
 *
 * The page is handed the combined aggregate schema produced by AggregateSchemaService: a JSON
 * Schema whose $defs hold one entry per entity type reachable from the aggregate root, with
 * containment expressed as $ref (so a cyclic composition graph still yields a finite document).
 *
 * This editor walks that schema and renders the aggregate as a tree of nodes. Each node shows the
 * entity type it stands for, a typed values form built from that type's own schema, the optional
 * `ref` / `dependsOn` bookkeeping fields the aggregate loader understands, and — when the type
 * composes others — an "add part" control per allowed part type. Parts are created lazily, which is
 * what stops a recursive schema from expanding forever.
 *
 * On submit the tree is serialized into the hidden `document` field. A "Raw JSON" tab lets the
 * document be edited directly; switching back to the Builder tab re-parses it and rebuilds the tree.
 */
(function () {
  "use strict";

  document.addEventListener("DOMContentLoaded", function () {
    var form = document.getElementById("aggregate-form");
    if (!form) return;

    var rootSelect = document.getElementById("rootType");
    var container = document.getElementById("aggregate-editor");
    var hidden = document.getElementById("document");
    var rawTextarea = document.getElementById("aggregate-raw");
    var validation = document.getElementById("aggregate-validation");
    if (!rootSelect || !container || !hidden) return;

    var schema = readEmbeddedSchema();
    var rootNode = null; // the built tree; null while no schema is loaded
    var mode = "builder";

    function readEmbeddedSchema() {
      var el = document.getElementById("aggregate-schema");
      if (!el) return null;
      try {
        return JSON.parse(el.textContent || "null");
      } catch (e) {
        return null;
      }
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

    // --- schema helpers ------------------------------------------------------

    // Resolves a local "#/$defs/Name" pointer against the combined schema.
    function resolveRef(ref) {
      if (!schema || typeof ref !== "string") return null;
      var prefix = "#/$defs/";
      if (ref.indexOf(prefix) !== 0) return null;
      var defs = schema.$defs || {};
      return defs[ref.slice(prefix.length)] || null;
    }

    // The node schemas a `parts` array accepts, as [{name, schema}].
    function allowedPartTypes(nodeSchema) {
      var props = nodeSchema.properties || {};
      var parts = props.parts;
      if (!parts || !parts.items) return [];
      var items = parts.items;
      var refs = items.anyOf ? items.anyOf : [items];
      return refs
        .map(function (entry) {
          var ref = entry.$ref;
          var resolved = resolveRef(ref);
          if (!resolved) return null;
          return { name: ref.slice("#/$defs/".length), schema: resolved };
        })
        .filter(Boolean);
    }

    // The default entity type name for a node: the first value its `entityType` enum allows.
    function defaultEntityType(nodeSchema, fallback) {
      var prop = (nodeSchema.properties || {}).entityType || {};
      if (Array.isArray(prop.enum) && prop.enum.length) return prop.enum[0];
      return fallback;
    }

    function isObjectSchema(node) {
      return (
        node &&
        node.type === "object" &&
        node.properties &&
        typeof node.properties === "object"
      );
    }

    // --- values sub-form (the entity's own schema) ---------------------------

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

    function requiredBadge() {
      var badge = document.createElement("span");
      badge.className = "mv-required";
      badge.textContent = "required";
      return badge;
    }

    function renderLeaf(leafSchema, isRequired) {
      var type = leafSchema.type || "string";
      var input;
      if (Array.isArray(leafSchema.enum)) {
        input = document.createElement("select");
        var placeholder = document.createElement("option");
        placeholder.value = "";
        placeholder.textContent = "— Select —";
        input.appendChild(placeholder);
        leafSchema.enum.forEach(function (val) {
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
        if (leafSchema.minimum != null) input.min = leafSchema.minimum;
        if (leafSchema.maximum != null) input.max = leafSchema.maximum;
      } else {
        input = document.createElement("input");
        input.type = "text";
      }
      input.className = "mv-input";
      if (isRequired && type !== "boolean") input.required = true;
      return { kind: "leaf", input: input, type: type };
    }

    function renderValues(valuesSchema, parentEl) {
      var props = valuesSchema.properties || {};
      var requiredList = Array.isArray(valuesSchema.required) ? valuesSchema.required : [];
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
          children.push({ key: key, model: renderValues(childSchema, body) });
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
          children.push({ key: key, model: renderValuesArray(childSchema, abody) });
        } else {
          row.className = "mv-leaf";
          var label = document.createElement("label");
          label.className = "mv-leaf-label";
          label.textContent = key;
          if (isRequired) label.appendChild(requiredBadge());
          var leafModel = renderLeaf(childSchema, isRequired);
          label.appendChild(leafModel.input);
          row.appendChild(label);
          children.push({ key: key, model: leafModel });
        }
        parentEl.appendChild(row);
      });

      return { kind: "object", children: children };
    }

    function renderValuesArray(arraySchema, parentEl) {
      var itemsSchema = arraySchema.items || { type: "string" };
      var itemNodes = [];

      var listEl = document.createElement("div");
      listEl.className = "iv-array-list";
      parentEl.appendChild(listEl);

      function addItem(value) {
        var itemRow = document.createElement("div");
        itemRow.className = "iv-array-item";
        var itemModel;
        if (isObjectSchema(itemsSchema)) {
          itemModel = renderValues(itemsSchema, itemRow);
        } else {
          var label = document.createElement("label");
          label.className = "mv-leaf-label";
          itemModel = renderLeaf(itemsSchema, false);
          label.appendChild(itemModel.input);
          itemRow.appendChild(label);
        }
        if (value !== undefined) setValues(itemModel, value);

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

        listEl.appendChild(itemRow);
        itemNodes.push(itemModel);
      }

      var addBtn = document.createElement("button");
      addBtn.type = "button";
      addBtn.className = "btn";
      addBtn.textContent = "+ Add item";
      addBtn.addEventListener("click", function () {
        addItem();
      });
      parentEl.appendChild(addBtn);

      return { kind: "array", items: itemNodes, addItem: addItem };
    }

    function serializeValues(node) {
      if (node.kind === "leaf") return parseLeaf(node.input, node.type);
      if (node.kind === "array") {
        return node.items
          .map(serializeValues)
          .filter(function (v) {
            return v !== undefined;
          });
      }
      var obj = {};
      node.children.forEach(function (child) {
        var sv = serializeValues(child.model);
        if (sv !== undefined) obj[child.key] = sv;
      });
      return obj;
    }

    function setValues(node, value) {
      if (value === null || value === undefined) return;
      if (node.kind === "leaf") {
        if (node.type === "boolean") node.input.checked = !!value;
        else node.input.value = String(value);
      } else if (node.kind === "object") {
        node.children.forEach(function (child) {
          if (value && Object.prototype.hasOwnProperty.call(value, child.key)) {
            setValues(child.model, value[child.key]);
          }
        });
      } else if (node.kind === "array") {
        if (Array.isArray(value)) {
          value.forEach(function (v) {
            node.addItem(v);
          });
        }
      }
    }

    // --- aggregate node tree -------------------------------------------------

    /*
     * Renders one aggregate node and returns its model. `onRemove` is null for the root, which
     * cannot be removed. Child parts are only rendered when the user adds them, so a self-composing
     * type does not recurse indefinitely.
     */
    function renderNode(typeName, nodeSchema, parentEl, onRemove, existing) {
      var wrapper = document.createElement("div");
      wrapper.className = "mv-group";

      var head = document.createElement("div");
      head.className = "mv-key";

      var entityTypeSelect = document.createElement("select");
      entityTypeSelect.className = "mv-input";
      var enumValues = ((nodeSchema.properties || {}).entityType || {}).enum || [typeName];
      enumValues.forEach(function (name) {
        var opt = document.createElement("option");
        opt.value = name;
        opt.textContent = name;
        entityTypeSelect.appendChild(opt);
      });
      entityTypeSelect.value =
        (existing && existing.entityType) || defaultEntityType(nodeSchema, typeName);

      var title = document.createElement("span");
      title.textContent = typeName + " ";
      head.appendChild(title);
      head.appendChild(entityTypeSelect);

      if (onRemove) {
        var removeBtn = document.createElement("button");
        removeBtn.type = "button";
        removeBtn.className = "link-remove";
        removeBtn.title = "Remove this part";
        removeBtn.textContent = "×";
        removeBtn.addEventListener("click", function () {
          wrapper.remove();
          onRemove();
        });
        head.appendChild(removeBtn);
      }
      wrapper.appendChild(head);

      var body = document.createElement("div");
      body.className = "mv-children";
      wrapper.appendChild(body);

      // ref / dependsOn: the loader uses these to wire DEPENDS_ON links across the tree.
      var refLabel = document.createElement("label");
      refLabel.className = "mv-leaf-label";
      refLabel.textContent = "ref";
      var refInput = document.createElement("input");
      refInput.type = "text";
      refInput.className = "mv-input";
      refInput.placeholder = "optional local id";
      refLabel.appendChild(refInput);
      body.appendChild(refLabel);
      if (existing && existing.ref) refInput.value = existing.ref;

      var dependsLabel = document.createElement("label");
      dependsLabel.className = "mv-leaf-label";
      dependsLabel.textContent = "dependsOn";
      var dependsInput = document.createElement("input");
      dependsInput.type = "text";
      dependsInput.className = "mv-input";
      dependsInput.placeholder = "comma-separated refs";
      dependsLabel.appendChild(dependsInput);
      body.appendChild(dependsLabel);
      if (existing && Array.isArray(existing.dependsOn)) {
        dependsInput.value = existing.dependsOn.join(", ");
      }

      // values, built from this entity type's own schema
      var valuesSchema = (nodeSchema.properties || {}).values || { type: "object", properties: {} };
      var valuesGroup = document.createElement("div");
      valuesGroup.className = "mv-group";
      var valuesHead = document.createElement("div");
      valuesHead.className = "mv-key";
      valuesHead.textContent = "values";
      valuesGroup.appendChild(valuesHead);
      var valuesBody = document.createElement("div");
      valuesBody.className = "mv-children";
      valuesGroup.appendChild(valuesBody);
      body.appendChild(valuesGroup);

      var valuesModel = renderValues(valuesSchema, valuesBody);
      if (existing && existing.values) setValues(valuesModel, existing.values);
      if (!Object.keys(valuesSchema.properties || {}).length) {
        var none = document.createElement("p");
        none.className = "empty";
        none.textContent = "This type declares no properties.";
        valuesBody.appendChild(none);
      }

      // parts
      var partModels = [];
      var partTypes = allowedPartTypes(nodeSchema);
      if (partTypes.length) {
        var partsGroup = document.createElement("div");
        partsGroup.className = "mv-group";
        var partsHead = document.createElement("div");
        partsHead.className = "mv-key";
        partsHead.textContent = "parts";
        partsGroup.appendChild(partsHead);
        var partsBody = document.createElement("div");
        partsBody.className = "mv-children";
        partsGroup.appendChild(partsBody);

        var addPart = function (partType, existingPart) {
          var model = null;
          var remove = function () {
            var idx = partModels.indexOf(model);
            if (idx !== -1) partModels.splice(idx, 1);
          };
          model = renderNode(partType.name, partType.schema, partsBody, remove, existingPart);
          partModels.push(model);
          return model;
        };

        partTypes.forEach(function (partType) {
          var addBtn = document.createElement("button");
          addBtn.type = "button";
          addBtn.className = "btn";
          addBtn.textContent = "+ Add " + partType.name;
          addBtn.addEventListener("click", function () {
            addPart(partType);
          });
          partsGroup.appendChild(addBtn);
        });

        body.appendChild(partsGroup);

        if (existing && Array.isArray(existing.parts)) {
          existing.parts.forEach(function (part) {
            // Re-attach a saved part to the part type whose enum accepts its entityType, so a
            // round-trip through the Raw JSON tab keeps the tree intact.
            var match =
              partTypes.filter(function (pt) {
                var allowed = ((pt.schema.properties || {}).entityType || {}).enum || [pt.name];
                return allowed.indexOf(part.entityType) !== -1;
              })[0] || partTypes[0];
            addPart(match, part);
          });
        }
      }

      parentEl.appendChild(wrapper);

      return {
        entityTypeSelect: entityTypeSelect,
        refInput: refInput,
        dependsInput: dependsInput,
        valuesModel: valuesModel,
        parts: partModels
      };
    }

    function serializeNode(node) {
      var doc = {
        entityType: node.entityTypeSelect.value,
        values: serializeValues(node.valuesModel)
      };
      var ref = node.refInput.value.trim();
      if (ref) doc.ref = ref;
      var depends = node.dependsInput.value
        .split(",")
        .map(function (s) {
          return s.trim();
        })
        .filter(Boolean);
      if (depends.length) doc.dependsOn = depends;
      if (node.parts.length) doc.parts = node.parts.map(serializeNode);
      return doc;
    }

    // --- wiring --------------------------------------------------------------

    function buildTree(existing) {
      container.innerHTML = "";
      rootNode = null;
      showValidation("", true);

      if (!schema || !schema.properties) {
        message("Select an aggregate root type to start building.", "empty");
        return;
      }
      var rootName = defaultEntityType(schema, rootSelect.value);
      rootNode = renderNode(rootName, schema, container, null, existing);
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
        if (rootNode) rawTextarea.value = JSON.stringify(serializeNode(rootNode), null, 2);
        setMode("raw");
      } else {
        try {
          var parsed = rawTextarea.value.trim() ? JSON.parse(rawTextarea.value) : null;
          buildTree(parsed);
          setMode("builder");
        } catch (e) {
          showValidation("Raw JSON is invalid: " + e.message, false);
        }
      }
    }

    form.querySelectorAll(".tab").forEach(function (t) {
      t.addEventListener("click", function () {
        switchTab(t.dataset.tab);
      });
    });

    // Switching root type reloads the combined schema for that root.
    rootSelect.addEventListener("change", function () {
      var name = rootSelect.value;
      if (!name) {
        schema = null;
        buildTree(null);
        return;
      }
      fetch("schema?rootType=" + encodeURIComponent(name), { headers: { Accept: "application/json" } })
        .then(function (res) {
          if (!res.ok) throw new Error("could not load the schema for '" + name + "'");
          return res.json();
        })
        .then(function (loaded) {
          schema = loaded;
          buildTree(null);
        })
        .catch(function (err) {
          schema = null;
          message(err.message, "error");
        });
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
      if (rootNode === null) {
        e.preventDefault();
        message("Select an aggregate root type and fill the values before submitting.", "error");
        return;
      }
      hidden.value = JSON.stringify(serializeNode(rootNode), null, 2);
    });

    buildTree(hidden.value ? safeParse(hidden.value) : null);

    function safeParse(text) {
      try {
        return JSON.parse(text);
      } catch (e) {
        return null;
      }
    }
  });
})();
