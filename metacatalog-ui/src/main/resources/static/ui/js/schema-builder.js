/*
 * JSON Schema editor for trait / entity-type creation.
 *
 * Two synchronized views (see the Builder / Raw JSON tabs):
 *
 *  - Builder: a recursive form of property rows. Each property has a name, a JSON type and a
 *    "required" flag, plus per-type constraints. Objects nest their own property lists and arrays
 *    nest an "items" schema, so arbitrarily deep structures can be described.
 *  - Raw JSON: a textarea for editing the schema document directly.
 *
 * Both views feed the hidden `schema` input and a live preview. The document is validated against
 * the metacatalog rules (object schema with a "properties" map, and none of the disallowed
 * keywords) before submit; the server performs the authoritative validation.
 */
(function () {
  "use strict";

  var TYPES = ["string", "number", "integer", "boolean", "object", "array"];
  var FORMATS = [
    "",
    "date",
    "date-time",
    "time",
    "duration",
    "email",
    "hostname",
    "ipv4",
    "ipv6",
    "uri",
    "uuid",
  ];
  var NOT_ALLOWED = [
    "$schema",
    "$anchor",
    "$ref",
    "$id",
    "if",
    "then",
    "else",
    "dependentRequired",
    "dependentSchemas",
    "allOf",
    "anyOf",
    "oneOf",
    "not",
    "unevaluatedProperties",
  ];

  document.addEventListener("DOMContentLoaded", function () {
    var builder = document.getElementById("schema-builder");
    if (!builder) return;

    var form = document.getElementById("type-form");
    var propRoot = document.getElementById("prop-root");
    var rawTextarea = document.getElementById("raw-schema");
    var preview = document.getElementById("schema-preview");
    var validation = document.getElementById("schema-validation");
    var hidden = form.querySelector('input[name="schema"][type="hidden"]');
    var mode = "builder";

    // --- small DOM helpers -------------------------------------------------

    function el(tag, cls) {
      var e = document.createElement(tag);
      if (cls) e.className = cls;
      return e;
    }

    function child(field, selector) {
      return field.querySelector(":scope > " + selector);
    }

    function labelled(text, input) {
      var wrap = el("label", "constraint");
      wrap.appendChild(document.createTextNode(text));
      wrap.appendChild(input);
      return wrap;
    }

    function numberInput(key, kind, placeholder) {
      var i = el("input");
      i.type = "number";
      if (kind === "int") i.step = "1";
      i.placeholder = placeholder || "";
      i.dataset.key = key;
      i.dataset.kind = kind;
      return i;
    }

    function textInput(key, placeholder) {
      var i = el("input");
      i.type = "text";
      i.placeholder = placeholder || "";
      i.dataset.key = key;
      i.dataset.kind = "text";
      return i;
    }

    function csvInput(key, placeholder) {
      var i = textInput(key, placeholder);
      i.dataset.kind = "csv";
      return i;
    }

    function formatSelect() {
      var s = el("select");
      s.dataset.key = "format";
      s.dataset.kind = "text";
      FORMATS.forEach(function (f) {
        var o = el("option");
        o.value = f;
        o.textContent = f === "" ? "— format —" : f;
        s.appendChild(o);
      });
      return s;
    }

    // --- field creation ----------------------------------------------------

    function createField(withMeta) {
      var field = el("div", "field");

      var head = el("div", "field-head");
      if (withMeta) {
        var name = el("input", "f-name");
        name.type = "text";
        name.placeholder = "propertyName";
        head.appendChild(name);
      }
      var type = el("select", "f-type");
      TYPES.forEach(function (t) {
        var o = el("option");
        o.value = t;
        o.textContent = t;
        type.appendChild(o);
      });
      head.appendChild(type);
      if (withMeta) {
        var rl = el("label", "f-required-wrap");
        var rq = el("input", "f-required");
        rq.type = "checkbox";
        rl.appendChild(rq);
        rl.appendChild(document.createTextNode(" required"));
        head.appendChild(rl);
        var rm = el("button", "link-remove");
        rm.type = "button";
        rm.title = "Remove property";
        rm.textContent = "×";
        head.appendChild(rm);
      }
      field.appendChild(head);
      field.appendChild(el("div", "field-constraints"));
      field.appendChild(el("div", "field-children"));

      renderConstraints(field, "string");
      return field;
    }

    function renderConstraints(field, type) {
      var constraints = child(field, ".field-constraints");
      var children = child(field, ".field-children");
      constraints.innerHTML = "";
      children.innerHTML = "";

      if (type === "string") {
        constraints.appendChild(labelled("min length", numberInput("minLength", "int")));
        constraints.appendChild(labelled("max length", numberInput("maxLength", "int")));
        constraints.appendChild(labelled("pattern", textInput("pattern", "regex")));
        constraints.appendChild(labelled("format", formatSelect()));
        constraints.appendChild(labelled("enum", csvInput("enum", "a, b, c")));
      } else if (type === "number" || type === "integer") {
        var kind = type === "integer" ? "int" : "num";
        constraints.appendChild(labelled("minimum", numberInput("minimum", kind)));
        constraints.appendChild(labelled("maximum", numberInput("maximum", kind)));
        constraints.appendChild(
            labelled("excl. min", numberInput("exclusiveMinimum", kind)));
        constraints.appendChild(
            labelled("excl. max", numberInput("exclusiveMaximum", kind)));
      } else if (type === "array") {
        constraints.appendChild(labelled("min items", numberInput("minItems", "int")));
        constraints.appendChild(labelled("max items", numberInput("maxItems", "int")));
        var itemWrap = el("div", "item-editor");
        var itemLabel = el("div", "item-label");
        itemLabel.textContent = "Items";
        itemWrap.appendChild(itemLabel);
        itemWrap.appendChild(createField(false));
        children.appendChild(itemWrap);
      } else if (type === "object") {
        var list = el("div", "prop-list");
        children.appendChild(list);
        var add = el("button", "btn add-nested");
        add.type = "button";
        add.textContent = "+ Add property";
        children.appendChild(add);
      }
      // boolean: no constraints
    }

    // --- reading the DOM into a schema ------------------------------------

    function readConstraints(field) {
      var out = {};
      child(field, ".field-constraints")
          .querySelectorAll(":scope > .constraint > [data-key]")
          .forEach(function (inp) {
            var raw = (inp.value || "").trim();
            if (raw === "") return;
            var key = inp.dataset.key;
            var kind = inp.dataset.kind;
            if (kind === "int") {
              var n = parseInt(raw, 10);
              if (!isNaN(n)) out[key] = n;
            } else if (kind === "num") {
              var f = parseFloat(raw);
              if (!isNaN(f)) out[key] = f;
            } else if (kind === "csv") {
              var arr = raw
                  .split(",")
                  .map(function (s) {
                    return s.trim();
                  })
                  .filter(Boolean);
              if (arr.length) out[key] = arr;
            } else {
              out[key] = raw;
            }
          });
      return out;
    }

    function readObjectChildren(listEl) {
      var properties = {};
      var required = [];
      listEl.querySelectorAll(":scope > .field").forEach(function (f) {
        var name = child(f, ".field-head").querySelector(".f-name").value.trim();
        if (!name) return;
        properties[name] = readField(f);
        if (child(f, ".field-head").querySelector(".f-required").checked) required.push(name);
      });
      return { properties: properties, required: required };
    }

    function readField(field) {
      var type = child(field, ".field-head").querySelector(".f-type").value;
      var schema = { type: type };
      var constraints = readConstraints(field);
      Object.keys(constraints).forEach(function (k) {
        schema[k] = constraints[k];
      });
      if (type === "object") {
        var list = child(field, ".field-children").querySelector(":scope > .prop-list");
        var result = readObjectChildren(list);
        schema.properties = result.properties;
        schema.required = result.required;
      } else if (type === "array") {
        var itemField = child(field, ".field-children").querySelector(
            ":scope > .item-editor > .field");
        schema.items = itemField ? readField(itemField) : { type: "string" };
      }
      return schema;
    }

    function buildSchema() {
      var result = readObjectChildren(propRoot);
      return { type: "object", properties: result.properties, required: result.required };
    }

    // --- writing a schema back into the DOM (hydration) -------------------

    function normalizeType(t) {
      return TYPES.indexOf(t) !== -1 ? t : "string";
    }

    function applyConstraints(field, def) {
      child(field, ".field-constraints")
          .querySelectorAll(":scope > .constraint > [data-key]")
          .forEach(function (inp) {
            var val = def[inp.dataset.key];
            if (val === undefined || val === null) return;
            inp.value = inp.dataset.kind === "csv" && Array.isArray(val) ? val.join(", ") : val;
          });
    }

    function hydrateField(field, def) {
      var type = normalizeType(def.type);
      child(field, ".field-head").querySelector(".f-type").value = type;
      renderConstraints(field, type);
      applyConstraints(field, def);
      if (type === "object") {
        var list = child(field, ".field-children").querySelector(":scope > .prop-list");
        buildObjectChildren(list, def.properties || {}, def.required || []);
      } else if (type === "array") {
        var itemField = child(field, ".field-children").querySelector(
            ":scope > .item-editor > .field");
        if (itemField) hydrateField(itemField, def.items || { type: "string" });
      }
    }

    function buildObjectChildren(listEl, properties, required) {
      Object.keys(properties).forEach(function (name) {
        var field = createField(true);
        child(field, ".field-head").querySelector(".f-name").value = name;
        child(field, ".field-head").querySelector(".f-required").checked =
            required.indexOf(name) !== -1;
        hydrateField(field, properties[name] || {});
        listEl.appendChild(field);
      });
    }

    function hydrate(schema) {
      propRoot.innerHTML = "";
      buildObjectChildren(propRoot, schema.properties || {}, schema.required || []);
    }

    // --- validation --------------------------------------------------------

    function findNotAllowed(node) {
      if (!node || typeof node !== "object") return false;
      if (Array.isArray(node)) {
        for (var i = 0; i < node.length; i++) {
          var r = findNotAllowed(node[i]);
          if (r) return r;
        }
        return false;
      }
      var keys = Object.keys(node);
      for (var j = 0; j < keys.length; j++) {
        if (NOT_ALLOWED.indexOf(keys[j]) !== -1) return keys[j];
        var nested = findNotAllowed(node[keys[j]]);
        if (nested) return nested;
      }
      return false;
    }

    function validate(str) {
      var parsed;
      try {
        parsed = JSON.parse(str);
      } catch (e) {
        return { ok: false, errors: ["Invalid JSON: " + e.message] };
      }
      var errors = [];
      if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
        errors.push("Schema must be a JSON object.");
      } else {
        if (parsed.type !== "object") errors.push('Top-level "type" must be "object".');
        if (
            typeof parsed.properties !== "object" ||
            parsed.properties === null ||
            Array.isArray(parsed.properties)
        ) {
          errors.push('Schema must have a "properties" object.');
        }
        var bad = findNotAllowed(parsed);
        if (bad) errors.push('Keyword "' + bad + '" is not allowed in a metacatalog schema.');
      }
      return { ok: errors.length === 0, errors: errors, parsed: parsed };
    }

    function showValidation(result) {
      if (!validation) return;
      if (result.ok) {
        validation.className = "validation ok";
        validation.textContent = "✓ Valid schema";
      } else {
        validation.className = "validation err";
        validation.textContent = result.errors.join(" ");
      }
    }

    // --- synchronization / modes ------------------------------------------

    function syncFromBuilder() {
      var schema = buildSchema();
      var pretty = JSON.stringify(schema, null, 2);
      hidden.value = JSON.stringify(schema);
      if (preview) preview.textContent = pretty;
      rawTextarea.value = pretty;
      showValidation(validate(hidden.value));
    }

    function onRawInput() {
      var raw = rawTextarea.value;
      hidden.value = raw;
      var result = validate(raw);
      showValidation(result);
      if (preview) preview.textContent = result.ok ? JSON.stringify(result.parsed, null, 2) : raw;
    }

    function setMode(next) {
      mode = next;
      builder.querySelectorAll(".tab").forEach(function (t) {
        t.classList.toggle("active", t.dataset.tab === next);
      });
      builder.querySelectorAll(".tab-panel").forEach(function (p) {
        p.classList.toggle("hidden", p.dataset.panel !== next);
      });
    }

    function switchTab(next) {
      if (next === mode) return;
      if (next === "raw") {
        syncFromBuilder();
        setMode("raw");
      } else {
        var result = validate(rawTextarea.value);
        if (!result.ok) {
          showValidation(result);
          return; // stay on raw until it is valid
        }
        hydrate(result.parsed);
        setMode("builder");
        syncFromBuilder();
      }
    }

    // --- event wiring (delegated) -----------------------------------------

    builder.addEventListener("click", function (e) {
      var t = e.target;
      if (t.classList.contains("tab")) {
        switchTab(t.dataset.tab);
      } else if (t.id === "add-prop") {
        propRoot.appendChild(createField(true));
        syncFromBuilder();
      } else if (t.classList.contains("add-nested")) {
        t.parentElement.querySelector(":scope > .prop-list").appendChild(createField(true));
        syncFromBuilder();
      } else if (t.classList.contains("link-remove")) {
        t.closest(".field").remove();
        syncFromBuilder();
      }
    });

    builder.addEventListener("input", function (e) {
      if (e.target === rawTextarea) onRawInput();
      else if (mode === "builder") syncFromBuilder();
    });

    builder.addEventListener("change", function (e) {
      if (e.target === rawTextarea) return;
      if (e.target.classList.contains("f-type")) {
        renderConstraints(e.target.closest(".field"), e.target.value);
      }
      if (mode === "builder") syncFromBuilder();
    });

    if (form) {
      form.addEventListener("submit", function (e) {
        if (mode === "raw") {
          hidden.value = rawTextarea.value;
          var result = validate(rawTextarea.value);
          if (!result.ok) {
            e.preventDefault();
            showValidation(result);
          }
        } else {
          syncFromBuilder();
        }
      });
    }

    // --- initial state -----------------------------------------------------

    var initial = builder.getAttribute("data-initial-schema") || (hidden ? hidden.value : "");
    if (initial) {
      var result = validate(initial);
      if (result.ok && result.parsed.properties && Object.keys(result.parsed.properties).length) {
        hydrate(result.parsed);
      } else if (!result.ok) {
        // Keep the user's raw input so they can fix it in the raw editor.
        rawTextarea.value = initial;
        setMode("raw");
        onRawInput();
        return;
      }
    }
    if (mode === "builder" && propRoot.children.length === 0) {
      propRoot.appendChild(createField(true));
    }
    syncFromBuilder();
  });
})();
