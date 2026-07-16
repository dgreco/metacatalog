/*
 * JSON Schema builder for trait / entity-type creation.
 *
 * Renders a set of "property" rows (name, JSON type, required flag) and assembles a flat
 * object JSON Schema of the shape expected by the metacatalog services:
 *
 *   { "type": "object", "properties": { <name>: { "type": <t> }, ... }, "required": [ ... ] }
 *
 * The assembled schema is kept in sync with the hidden `schema` input and a live preview, and is
 * (re)serialized on form submit. On server-side validation errors the form is re-rendered with the
 * previously posted schema, which is hydrated back into rows so the user does not lose their work.
 */
(function () {
  "use strict";

  var TYPES = ["string", "number", "integer", "boolean"];

  document.addEventListener("DOMContentLoaded", function () {
    var builder = document.getElementById("schema-builder");
    if (!builder) return;

    var form = document.getElementById("type-form");
    var rows = document.getElementById("prop-rows");
    var addButton = document.getElementById("add-prop");
    var preview = document.getElementById("schema-preview");
    var hidden = form.querySelector('input[name="schema"][type="hidden"]');

    function makeCell(child) {
      var td = document.createElement("td");
      td.appendChild(child);
      return td;
    }

    function addRow(name, type, required) {
      var tr = document.createElement("tr");

      var nameInput = document.createElement("input");
      nameInput.type = "text";
      nameInput.className = "prop-name";
      nameInput.placeholder = "propertyName";
      nameInput.value = name || "";

      var typeSelect = document.createElement("select");
      typeSelect.className = "prop-type";
      var options = TYPES.slice();
      if (type && options.indexOf(type) === -1) options.push(type); // preserve unknown types
      options.forEach(function (t) {
        var opt = document.createElement("option");
        opt.value = t;
        opt.textContent = t;
        if (t === type) opt.selected = true;
        typeSelect.appendChild(opt);
      });

      var requiredInput = document.createElement("input");
      requiredInput.type = "checkbox";
      requiredInput.className = "prop-required";
      requiredInput.checked = !!required;

      var removeButton = document.createElement("button");
      removeButton.type = "button";
      removeButton.className = "link-remove";
      removeButton.title = "Remove property";
      removeButton.textContent = "×";
      removeButton.addEventListener("click", function () {
        tr.remove();
        sync();
      });

      tr.appendChild(makeCell(nameInput));
      tr.appendChild(makeCell(typeSelect));
      tr.appendChild(makeCell(requiredInput));
      tr.appendChild(makeCell(removeButton));
      rows.appendChild(tr);

      nameInput.addEventListener("input", sync);
      typeSelect.addEventListener("change", sync);
      requiredInput.addEventListener("change", sync);
    }

    function buildSchema() {
      var properties = {};
      var required = [];
      rows.querySelectorAll("tr").forEach(function (tr) {
        var name = tr.querySelector(".prop-name").value.trim();
        if (!name) return;
        var type = tr.querySelector(".prop-type").value;
        properties[name] = { type: type };
        if (tr.querySelector(".prop-required").checked) required.push(name);
      });
      return { type: "object", properties: properties, required: required };
    }

    function sync() {
      var schema = buildSchema();
      var json = JSON.stringify(schema);
      if (hidden) hidden.value = json;
      if (preview) preview.textContent = JSON.stringify(schema, null, 2);
    }

    function hydrate() {
      var initial = builder.getAttribute("data-initial-schema");
      if (!initial && hidden) initial = hidden.value;
      if (!initial) return false;
      try {
        var parsed = JSON.parse(initial);
        var props = parsed.properties || {};
        var required = Array.isArray(parsed.required) ? parsed.required : [];
        var names = Object.keys(props);
        if (names.length === 0) return false;
        names.forEach(function (name) {
          var def = props[name] || {};
          var type = typeof def.type === "string" ? def.type : "string";
          addRow(name, type, required.indexOf(name) !== -1);
        });
        return true;
      } catch (e) {
        return false;
      }
    }

    addButton.addEventListener("click", function () {
      addRow("", "string", false);
      sync();
    });

    if (form) {
      form.addEventListener("submit", sync);
    }

    // Start from the previously posted schema (error redisplay) or a single empty row.
    if (!hydrate()) {
      addRow("", "string", false);
    }
    sync();
  });
})();
