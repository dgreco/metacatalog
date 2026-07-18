/*
 * Light / dark theme switch for the Meta Catalog UI.
 *
 * Loaded from <head> as a blocking script (no defer) so the chosen theme is
 * applied to <html data-theme> *before* the body paints — this prevents a flash
 * of the wrong (default light) theme for dark-mode users. The toggle button is
 * injected into the topbar and wired up on DOMContentLoaded.
 *
 * Resolution order: an explicit user choice in localStorage wins; otherwise the
 * OS preference (prefers-color-scheme) is honoured; otherwise light. localStorage
 * access is guarded so the page still works in private mode / when storage is
 * blocked.
 */
(function () {
  "use strict";

  var STORAGE_KEY = "metacatalog-theme";

  function storedTheme() {
    try {
      var v = localStorage.getItem(STORAGE_KEY);
      return v === "light" || v === "dark" ? v : null;
    } catch (e) {
      return null;
    }
  }

  function systemTheme() {
    return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches
      ? "dark"
      : "light";
  }

  function currentTheme() {
    return document.documentElement.dataset.theme === "dark" ? "dark" : "light";
  }

  // Icon shows the theme you'll switch *to*; title spells out the action.
  function renderToggle(btn) {
    var dark = currentTheme() === "dark";
    btn.setAttribute("aria-pressed", String(dark));
    btn.setAttribute("title", dark ? "Switch to light theme" : "Switch to dark theme");
    btn.setAttribute("aria-label", "Toggle light / dark theme");
    btn.textContent = dark ? "\u2600" : "\u263E";
  }

  // Run synchronously while <head> is being parsed: set the theme before paint.
  document.documentElement.dataset.theme = storedTheme() || systemTheme();

  function ensureToggle() {
    var nav = document.querySelector(".topbar nav");
    if (!nav) return null;
    var btn = nav.querySelector(".theme-toggle");
    if (btn) return btn;
    btn = document.createElement("button");
    btn.type = "button";
    btn.className = "btn theme-toggle";
    nav.appendChild(btn);
    return btn;
  }

  document.addEventListener("DOMContentLoaded", function () {
    var btn = ensureToggle();
    if (!btn) return;
    renderToggle(btn);
    btn.addEventListener("click", function () {
      var next = currentTheme() === "dark" ? "light" : "dark";
      document.documentElement.dataset.theme = next;
      try {
        localStorage.setItem(STORAGE_KEY, next);
      } catch (e) {
        /* private mode / storage blocked: keep the session-only choice */
      }
      renderToggle(btn);
    });

    // If the user hasn't chosen explicitly, follow the OS as it changes.
    var mq = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;
    if (mq && mq.addEventListener) {
      mq.addEventListener("change", function (e) {
        if (!storedTheme()) {
          document.documentElement.dataset.theme = e.matches ? "dark" : "light";
          renderToggle(btn);
        }
      });
    }
  });
})();
