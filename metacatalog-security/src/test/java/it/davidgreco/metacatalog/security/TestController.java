package it.davidgreco.metacatalog.security;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal controller used by security tests to have concrete endpoints behind the protected {@code
 * /metacatalog/v1/**} (API) and {@code /ui/**} (UI) paths. Lives in {@code src/test/java} so it
 * never ships in the published jar.
 */
@RestController
class TestController {

  @GetMapping("/metacatalog/v1/test")
  String test() {
    return "ok";
  }

  @GetMapping("/ui/test")
  String uiTest() {
    return "ui-ok";
  }
}
