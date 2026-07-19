package it.davidgreco.metacatalog.security;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal controller used by security tests to have a concrete endpoint behind the protected {@code
 * /metacatalog/v1/**} path. Lives in {@code src/test/java} so it never ships in the published jar.
 */
@RestController
@RequestMapping("/metacatalog/v1")
class TestController {

  @GetMapping("/test")
  String test() {
    return "ok";
  }
}
