/*
 * Auto-dismiss for flash messages ("Entity created.", "Aggregate provisioned.", ...).
 *
 * Success flashes (.flash) confirm an action that already succeeded, so they fade
 * out on their own after a few seconds instead of sitting in the page until the
 * next navigation. Error banners (.error) are deliberately left alone: they carry
 * information the user still has to act on.
 *
 * The fade uses the .flash-dismissed CSS transition; removal waits for
 * transitionend with a timer fallback so the element is also removed when
 * transitions are disabled (prefers-reduced-motion).
 */
(function () {
  "use strict";

  var VISIBLE_MS = 4000;
  var FALLBACK_REMOVE_MS = 1000;

  document.addEventListener("DOMContentLoaded", function () {
    var flashes = document.querySelectorAll(".flash");
    Array.prototype.forEach.call(flashes, function (el) {
      setTimeout(function () {
        var removed = false;
        function remove() {
          if (!removed && el.parentNode) {
            removed = true;
            el.parentNode.removeChild(el);
          }
        }
        el.addEventListener("transitionend", remove);
        setTimeout(remove, FALLBACK_REMOVE_MS);
        el.classList.add("flash-dismissed");
      }, VISIBLE_MS);
    });
  });
})();
