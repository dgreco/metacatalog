package it.davidgreco.metacatalog.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Sends the bare root to the dashboard.
 *
 * <p>Without a mapping for {@code /}, Spring Boot's welcome-page support finds the {@code index}
 * template and renders it there directly — without the model {@link GraphUiController#index} fills
 * for it — so the page failed with a 500 for anyone who opened the application's address.
 */
@Controller
class RootController {

  @GetMapping("/")
  String root() {
    return "redirect:/ui";
  }
}
