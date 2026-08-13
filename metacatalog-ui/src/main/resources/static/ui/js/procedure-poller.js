/*
 * Live status for asynchronously launched provisioning/unprovisioning runs.
 *
 * The provision/unprovision actions call the REST API with async=true; the controller
 * flashes the returned schedule id, which the instances page renders as a
 * #procedure-banner element. This script polls GET /metacatalog/v1/procedure/{id}
 * (an XHR on the same origin — in basic/ldap mode it is authenticated by the existing
 * UI session, like every other API call the UI pages make) until the run leaves
 * RUNNING, then reloads the page so the instance rows show the finished
 * provisioningStatus values. The outcome survives the reload through sessionStorage
 * and is re-injected as a normal flash / error banner.
 */
(function () {
  "use strict";

  var POLL_MS = 1000;
  var RETRY_MS = 2000;
  var OUTCOME_KEY = "metacatalog.procedureOutcome";

  document.addEventListener("DOMContentLoaded", function () {
    injectStoredOutcome();

    var banner = document.getElementById("procedure-banner");
    if (!banner) {
      return;
    }
    var scheduleId = banner.getAttribute("data-schedule-id");
    var label = banner.getAttribute("data-label") || "Procedure";
    if (!scheduleId) {
      return;
    }

    function poll() {
      var xhr = new XMLHttpRequest();
      xhr.open("GET", "/metacatalog/v1/procedure/" + encodeURIComponent(scheduleId), true);
      xhr.setRequestHeader("Accept", "application/json");
      xhr.onload = function () {
        if (xhr.status !== 200) {
          finish(false, label + " status unavailable (HTTP " + xhr.status + ").");
          return;
        }
        var body;
        try {
          body = JSON.parse(xhr.responseText);
        } catch (e) {
          finish(false, label + " status could not be read.");
          return;
        }
        if (body.status === "RUNNING") {
          window.setTimeout(poll, POLL_MS);
        } else if (body.status === "SUCCEEDED") {
          finish(true, label + " completed.");
        } else {
          finish(false, label + " failed: " + (body.error || "unknown error"));
        }
      };
      // Transient network errors: keep polling, the run is still going server-side.
      xhr.onerror = function () {
        window.setTimeout(poll, RETRY_MS);
      };
      xhr.send();
    }

    function finish(ok, text) {
      try {
        window.sessionStorage.setItem(OUTCOME_KEY, JSON.stringify({ ok: ok, text: text }));
      } catch (e) {
        /* storage unavailable: the reload still shows the refreshed statuses */
      }
      window.location.reload();
    }

    poll();
  });

  function injectStoredOutcome() {
    var raw;
    try {
      raw = window.sessionStorage.getItem(OUTCOME_KEY);
      if (raw) {
        window.sessionStorage.removeItem(OUTCOME_KEY);
      }
    } catch (e) {
      return;
    }
    if (!raw) {
      return;
    }
    var outcome;
    try {
      outcome = JSON.parse(raw);
    } catch (e) {
      return;
    }
    var el = document.createElement("div");
    el.className = outcome.ok ? "flash" : "error";
    el.textContent = outcome.text;
    var main = document.querySelector("main");
    if (main) {
      main.insertBefore(el, main.firstChild);
    }
  }
})();
