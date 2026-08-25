/*
 * Site-wide popup for asynchronously launched aggregate runs — provisioning and unprovisioning,
 * authorization and rejection alike.
 *
 * Nothing here knows which of the four ran: the controller flashes a label ("Provisioning",
 * "Authorizing", …) and this reads it, so a new procedure needs no change on this side.
 *
 * The provision/unprovision/authorize/reject actions call the REST API with async=true; the controller
 * flashes the returned schedule id, which the instances page renders as a hidden
 * #procedure-banner seed element. This script (included on every page) moves the run
 * into localStorage and shows a fixed-position popup that keeps polling
 * GET /metacatalog/v1/procedure/{id} — so the "in progress" indicator survives
 * navigating to other pages and is visible from every open tab of the UI.
 *
 * The XHR is same-origin: in basic/ldap mode it is authenticated by the existing UI
 * session, like every other API call the pages make. When the run leaves RUNNING, the
 * popup flips to a success/failure state; on the instances page the outcome is stashed
 * in localStorage and the page reloads so the rows show the finished status
 * values, with the outcome popup re-injected after the reload.
 */
(function () {
  "use strict";

  var POLL_MS = 1000;
  var RETRY_MS = 2000;
  var RUN_KEY = "metacatalog.procedureRun";
  var OUTCOME_KEY = "metacatalog.procedureOutcome";
  var OUTCOME_TTL_MS = 15000;
  var SUCCESS_DISMISS_MS = 6000;
  var FAILURE_DISMISS_MS = 15000;

  var popup = null;
  var activeScheduleId = null;

  document.addEventListener("DOMContentLoaded", function () {
    // Seed: the redirect after launching a run renders the schedule id into the page.
    var banner = document.getElementById("procedure-banner");
    if (banner) {
      var scheduleId = banner.getAttribute("data-schedule-id");
      var label = banner.getAttribute("data-label") || "Procedure";
      var entityId = banner.getAttribute("data-entity-id") || "";
      var entityType = banner.getAttribute("data-entity-type") || "";
      banner.parentNode.removeChild(banner);
      if (scheduleId) {
        write(RUN_KEY, {
          scheduleId: scheduleId,
          label: label,
          entityId: entityId,
          entityType: entityType
        });
      }
    }

    var outcome = take(OUTCOME_KEY);
    if (outcome && Date.now() - (outcome.at || 0) < OUTCOME_TTL_MS) {
      showOutcome(outcome);
    }

    var run = read(RUN_KEY);
    if (run && run.scheduleId) {
      track(run);
    }

    // A run launched from another tab after this page loaded.
    window.addEventListener("storage", function (event) {
      if (event.key !== RUN_KEY || !event.newValue) {
        return;
      }
      var newRun = read(RUN_KEY);
      if (newRun && newRun.scheduleId && newRun.scheduleId !== activeScheduleId) {
        track(newRun);
      }
    });
  });

  function track(run) {
    activeScheduleId = run.scheduleId;
    showRunning(run);
    poll(run);
  }

  /** "Provisioning DataProductType 0849185c-…" — what the run acts on, for every popup state. */
  function describe(run) {
    var parts = [run.label || "Procedure"];
    if (run.entityType) {
      parts.push(run.entityType);
    }
    if (run.entityId) {
      parts.push(run.entityId);
    }
    return parts.join(" ");
  }

  function poll(run) {
    var xhr = new XMLHttpRequest();
    xhr.open("GET", "/metacatalog/v1/procedure/" + encodeURIComponent(run.scheduleId), true);
    xhr.setRequestHeader("Accept", "application/json");
    xhr.onload = function () {
      if (xhr.status === 404) {
        finish(run, false, describe(run) + ": status is unknown or expired.");
        return;
      }
      if (xhr.status !== 200) {
        window.setTimeout(function () {
          poll(run);
        }, RETRY_MS);
        return;
      }
      var body;
      try {
        body = JSON.parse(xhr.responseText);
      } catch (e) {
        finish(run, false, describe(run) + ": status could not be read.");
        return;
      }
      if (body.status === "RUNNING") {
        window.setTimeout(function () {
          poll(run);
        }, POLL_MS);
      } else if (body.status === "SUCCEEDED") {
        finish(run, true, describe(run) + " completed.");
      } else {
        finish(run, false, describe(run) + " failed: " + (body.error || "unknown error"));
      }
    };
    // Transient network errors: keep polling, the run is still going server-side.
    xhr.onerror = function () {
      window.setTimeout(function () {
        poll(run);
      }, RETRY_MS);
    };
    xhr.send();
  }

  function finish(run, ok, text) {
    if (activeScheduleId === run.scheduleId) {
      activeScheduleId = null;
    }
    remove(RUN_KEY);
    // On the instances page the rows reflect the run's outcome: reload so they are
    // fresh, carrying the outcome popup across the reload.
    if (window.location.pathname.indexOf("/ui/instances") === 0) {
      write(OUTCOME_KEY, { ok: ok, text: text, at: Date.now() });
      window.location.reload();
      return;
    }
    showOutcome({ ok: ok, text: text });
  }

  function showRunning(run) {
    var el = ensurePopup();
    el.className = "procedure-popup running";
    el.innerHTML = "";
    var spinner = document.createElement("span");
    spinner.className = "procedure-spinner";
    el.appendChild(spinner);
    el.appendChild(document.createTextNode(describe(run) + " in progress…"));
  }

  function showOutcome(outcome) {
    var el = ensurePopup();
    el.className = "procedure-popup " + (outcome.ok ? "ok" : "failed");
    el.innerHTML = "";
    el.appendChild(document.createTextNode(outcome.text));
    var close = document.createElement("button");
    close.type = "button";
    close.className = "procedure-popup-close";
    close.setAttribute("aria-label", "Dismiss");
    close.textContent = "×";
    close.addEventListener("click", dismiss);
    el.appendChild(close);
    // Completed runs dismiss themselves; failures linger a little longer so the error
    // is readable, but the outcome also stays available in the entity's own result field.
    window.setTimeout(
        function () {
          if (popup === el) {
            dismiss();
          }
        },
        outcome.ok ? SUCCESS_DISMISS_MS : FAILURE_DISMISS_MS);
  }

  function dismiss() {
    if (popup && popup.parentNode) {
      popup.parentNode.removeChild(popup);
    }
    popup = null;
  }

  function ensurePopup() {
    if (!popup) {
      popup = document.createElement("div");
      popup.id = "procedure-popup";
      popup.setAttribute("role", "status");
      document.body.appendChild(popup);
    }
    return popup;
  }

  function read(key) {
    try {
      var raw = window.localStorage.getItem(key);
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      return null;
    }
  }

  function take(key) {
    var value = read(key);
    try {
      window.localStorage.removeItem(key);
    } catch (e) {
      /* storage unavailable */
    }
    return value;
  }

  function write(key, value) {
    try {
      window.localStorage.setItem(key, JSON.stringify(value));
    } catch (e) {
      /* storage unavailable: the popup still works within this page */
    }
  }

  function remove(key) {
    try {
      window.localStorage.removeItem(key);
    } catch (e) {
      /* storage unavailable */
    }
  }
})();
