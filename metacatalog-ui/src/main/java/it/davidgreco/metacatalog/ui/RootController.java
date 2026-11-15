package it.davidgreco.metacatalog.ui;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Sends the addresses people actually open to where the UI now has them: the bare root and {@code
 * /ui} to the asset catalog, and the pages that used to sit directly under {@code /ui} to their
 * place under {@code /ui/admin}.
 *
 * <p>Without a mapping for {@code /}, Spring Boot's welcome-page support finds the {@code index}
 * template and renders it there directly — without the model {@link GraphUiController#index} fills
 * for it — so the page failed with a 500 for anyone who opened the application's address.
 *
 * <p>{@code /ui} is kept as an address of its own rather than folded into the catalog's: it is what
 * a login redirects to and what the documentation and bookmarks point at, and none of those should
 * need to know which half of the UI is the front page.
 */
@Controller
class RootController {

  @GetMapping("/")
  String root() {
    return "redirect:/ui";
  }

  @GetMapping({"/ui", "/ui/"})
  String ui() {
    return "redirect:/ui/assets";
  }

  /**
   * Forwards a page to the address it moved to when the model and the generic tooling were gathered
   * under {@code /ui/admin}. Bookmarks, and links in documentation written against an earlier
   * release, name the old ones; answering them with a 404 would break those for the sake of a
   * prefix. Only reads are forwarded — a form posts to the address its own page was served with.
   */
  @GetMapping({
    "/ui/traits/**",
    "/ui/trait-links/**",
    "/ui/entity-types/**",
    "/ui/mappings/**",
    "/ui/instances/**",
    "/ui/aggregates/**",
    "/ui/graph",
    "/ui/bulk"
  })
  String moved(HttpServletRequest request) {
    var path = request.getRequestURI().substring(request.getContextPath().length());
    var query = request.getQueryString();
    return "redirect:/ui/admin"
        + path.substring("/ui".length())
        + (query == null ? "" : "?" + query);
  }
}
