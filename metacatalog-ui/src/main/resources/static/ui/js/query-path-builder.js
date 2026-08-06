/*
 * Guided query-path builder for the instances search bar.
 *
 * When a specific entity type is selected, the type's (derived) JSON Schema — embedded in the
 * page as window.__TYPE_SCHEMAS__ — is walked to enumerate the queryable leaf fields. The user
 * picks a field, an operator suited to the field's type, and a value, and "Add to query" writes
 * the corresponding PostgreSQL jsonpath predicate into the query input, e.g.
 *
 *   $ ? (@.owner == "sales" && @.size > 100)
 *
 * Conditions always take the root-predicate form `$ ? (...)` so that adding another one merges
 * into the existing filter — with `&&` or `||` as chosen, and optionally negated with `!(...)`.
 * Merging groups left to right: when an `&&` is added to a filter carrying a top-level `||`, the
 * existing filter is parenthesized first, so `a && b || c` followed by "and d" becomes
 * `(a && b || c) && d` rather than silently binding d to c. The query input stays editable: an
 * expression the builder does not recognize is simply replaced when a condition is added. With no
 * type selected (or a schema the builder cannot walk) the builder stays hidden and only the raw
 * input is offered.
 *
 * Schema constructs the builder cannot enumerate (oneOf/allOf/anyOf/$ref, additionalProperties)
 * are skipped rather than failing the whole builder.
 */
(function () {
  "use strict";

  document.addEventListener("DOMContentLoaded", function () {
    var typeSelect = document.getElementById("type");
    var queryInput = document.getElementById("query");
    var builder = document.getElementById("query-builder");
    var joinSelect = document.getElementById("qp-join");
    var notCheckbox = document.getElementById("qp-not");
    var fieldSelect = document.getElementById("qp-field");
    var opSelect = document.getElementById("qp-op");
    var valueSlot = document.getElementById("qp-value-slot");
    var addButton = document.getElementById("qp-add");
    if (!typeSelect || !queryInput || !builder || !joinSelect || !notCheckbox || !fieldSelect || !opSelect || !valueSlot || !addButton) return;

    var schemas = window.__TYPE_SCHEMAS__ || {};
    var MAX_DEPTH = 6;
    var leaves = []; // current type's queryable fields

    function isIdentifier(name) {
      return /^[A-Za-z_][A-Za-z0-9_]*$/.test(name);
    }

    // jsonpath accessor for a chain of property/array segments, rooted at `@`.
    function accessor(segments) {
      var path = "@";
      segments.forEach(function (seg) {
        if (seg === "[*]") path += "[*]";
        else if (isIdentifier(seg)) path += "." + seg;
        else path += "." + JSON.stringify(seg);
      });
      return path;
    }

    function label(segments) {
      var text = "";
      segments.forEach(function (seg) {
        if (seg === "[*]") text += "[*]";
        else text += (text ? "." : "") + seg;
      });
      return text;
    }

    // Recursively collects leaf fields (scalars, and scalar array elements) from a schema node.
    function collectLeaves(node, segments, depth, out) {
      if (!node || typeof node !== "object" || depth > MAX_DEPTH) return;
      if (node.$ref || node.oneOf || node.allOf || node.anyOf) return;
      var type = node.type;
      if (type === "object") {
        var props = node.properties;
        if (!props || typeof props !== "object") return;
        Object.keys(props).forEach(function (name) {
          collectLeaves(props[name], segments.concat([name]), depth + 1, out);
        });
      } else if (type === "array") {
        collectLeaves(node.items, segments.concat(["[*]"]), depth + 1, out);
      } else if (segments.length > 0) {
        out.push({
          segments: segments,
          type: Array.isArray(node.enum) ? "enum" : type || "string",
          enumValues: Array.isArray(node.enum) ? node.enum : null,
        });
      }
    }

    // Operators per field type. `value: false` marks the operators that take no right-hand side.
    var OPERATORS = {
      common: [
        { op: "==", label: "equals" },
        { op: "!=", label: "not equals" },
        { op: "exists", label: "exists", value: false },
      ],
      string: [
        { op: "starts with", label: "starts with" },
        { op: "like_regex", label: "matches regex" },
      ],
      numeric: [
        { op: ">", label: ">" },
        { op: ">=", label: ">=" },
        { op: "<", label: "<" },
        { op: "<=", label: "<=" },
      ],
    };

    function operatorsFor(fieldType) {
      var ops = OPERATORS.common.slice();
      if (fieldType === "string" || fieldType === "enum") ops = ops.concat(OPERATORS.string);
      if (fieldType === "number" || fieldType === "integer") ops = ops.concat(OPERATORS.numeric);
      return ops;
    }

    function currentField() {
      return leaves[parseInt(fieldSelect.value, 10)] || null;
    }

    function currentOperator() {
      var field = currentField();
      if (!field) return null;
      return operatorsFor(field.type)[parseInt(opSelect.value, 10)] || null;
    }

    // Rebuilds the operator select for the chosen field's type.
    function renderOperators() {
      var field = currentField();
      opSelect.innerHTML = "";
      if (!field) return;
      operatorsFor(field.type).forEach(function (entry, i) {
        var opt = document.createElement("option");
        opt.value = String(i);
        opt.textContent = entry.label;
        opSelect.appendChild(opt);
      });
    }

    // Rebuilds the value control: enum -> its values, boolean -> true/false,
    // number/integer -> number input, otherwise text. Hidden for value-less operators.
    function renderValue() {
      var field = currentField();
      var operator = currentOperator();
      valueSlot.innerHTML = "";
      if (!field || !operator || operator.value === false) return;
      var input;
      if (field.type === "enum" && operator.op !== "like_regex" && operator.op !== "starts with") {
        input = document.createElement("select");
        field.enumValues.forEach(function (val) {
          var opt = document.createElement("option");
          opt.value = String(val);
          opt.textContent = String(val);
          input.appendChild(opt);
        });
      } else if (field.type === "boolean") {
        input = document.createElement("select");
        ["true", "false"].forEach(function (val) {
          var opt = document.createElement("option");
          opt.value = val;
          opt.textContent = val;
          input.appendChild(opt);
        });
      } else if (
        (field.type === "number" || field.type === "integer") &&
        operator.op !== "like_regex" &&
        operator.op !== "starts with"
      ) {
        input = document.createElement("input");
        input.type = "number";
        if (field.type === "integer") input.step = "1";
      } else {
        input = document.createElement("input");
        input.type = "text";
      }
      input.id = "qp-value";
      input.className = "qp-value";
      valueSlot.appendChild(input);
    }

    // jsonpath literal for the value, following the field's declared type.
    function literal(field, operator, raw) {
      if (operator.op === "like_regex" || operator.op === "starts with") return JSON.stringify(raw);
      if (field.type === "boolean") return raw === "true" ? "true" : "false";
      if (field.type === "number" || field.type === "integer") {
        var n = parseFloat(raw);
        return isNaN(n) ? null : String(n);
      }
      if (field.type === "enum" && field.enumValues.indexOf(raw) === -1) {
        // enum of numbers: the select stringified them
        var e = parseFloat(raw);
        if (!isNaN(e) && field.enumValues.indexOf(e) !== -1) return String(e);
      }
      return JSON.stringify(raw);
    }

    function buildCondition() {
      var field = currentField();
      var operator = currentOperator();
      if (!field || !operator) return null;
      var path = accessor(field.segments);
      var condition;
      if (operator.value === false) {
        condition = "exists(" + path + ")";
      } else {
        var valueInput = document.getElementById("qp-value");
        var raw = valueInput ? valueInput.value : "";
        if (raw === "") return null;
        var lit = literal(field, operator, raw);
        if (lit === null) return null;
        condition = path + " " + operator.op + " " + lit;
      }
      return notCheckbox.checked ? "!(" + condition + ")" : condition;
    }

    // Whether the filter body carries an `||` outside every parenthesis, bracket and string
    // literal — the case where gluing an `&&` onto it flat would rebind its last branch.
    function hasTopLevelOr(body) {
      var depth = 0;
      var inString = false;
      for (var i = 0; i < body.length; i++) {
        var c = body.charAt(i);
        if (inString) {
          if (c === "\\") i++;
          else if (c === '"') inString = false;
        } else if (c === '"') {
          inString = true;
        } else if (c === "(" || c === "[") {
          depth++;
        } else if (c === ")" || c === "]") {
          depth--;
        } else if (c === "|" && depth === 0 && body.charAt(i + 1) === "|") {
          return true;
        }
      }
      return false;
    }

    // Merges the condition into an existing `$ ? (...)` filter with the chosen connector, or
    // starts a fresh one. Anything else in the input (a hand-written expression the builder does
    // not understand) is replaced. Grouping is left to right: `&&` onto a top-level `||`
    // parenthesizes the existing filter first; the added condition itself is atomic.
    function addCondition() {
      var condition = buildCondition();
      if (!condition) return;
      var existing = queryInput.value.trim();
      var match = existing.match(/^\$\s*\?\s*\((.*)\)$/);
      if (match) {
        var body = match[1];
        var joiner = joinSelect.value === "||" ? "||" : "&&";
        if (joiner === "&&" && hasTopLevelOr(body)) body = "(" + body + ")";
        queryInput.value = "$ ? (" + body + " " + joiner + " " + condition + ")";
      } else {
        queryInput.value = "$ ? (" + condition + ")";
      }
      notCheckbox.checked = false;
      refreshJoinState();
      renderValue(); // fresh value control, ready for the next condition
    }

    // The connector only means something once there is a recognized filter to join onto.
    function refreshJoinState() {
      joinSelect.disabled = !/^\$\s*\?\s*\(.*\)$/.test(queryInput.value.trim());
    }

    function initForType(typeName) {
      leaves = [];
      builder.hidden = true;
      if (!typeName || !schemas[typeName]) return;
      var schema;
      try {
        schema = JSON.parse(schemas[typeName]);
      } catch (e) {
        return;
      }
      collectLeaves(schema, [], 0, leaves);
      if (leaves.length === 0) return;
      fieldSelect.innerHTML = "";
      leaves.forEach(function (leaf, i) {
        var opt = document.createElement("option");
        opt.value = String(i);
        opt.textContent = label(leaf.segments);
        fieldSelect.appendChild(opt);
      });
      renderOperators();
      renderValue();
      builder.hidden = false;
    }

    fieldSelect.addEventListener("change", function () {
      renderOperators();
      renderValue();
    });
    opSelect.addEventListener("change", renderValue);
    addButton.addEventListener("click", addCondition);
    queryInput.addEventListener("input", refreshJoinState);
    queryInput.addEventListener("change", refreshJoinState);

    initForType(typeSelect.value);
    refreshJoinState();
  });
})();
