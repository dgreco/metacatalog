package it.davidgreco.metacatalog.openapi.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Standalone MockMvc tests for {@link GlobalExceptionHandler}. No Spring context or database is
 * needed — the advice is wired by hand against a minimal test controller.
 */
class GlobalExceptionHandlerTest {

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void malformedJsonBodyReturns400ValidationError() throws Exception {
    mvc.perform(post("/test/echo").contentType(MediaType.APPLICATION_JSON).content("{not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0]").exists());
  }

  @Test
  void typeMismatchOnPathVarReturns400ValidationError() throws Exception {
    mvc.perform(get("/test/id/not-a-number"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0]").exists());
  }

  @Test
  void missingRequiredQueryParamReturns400ValidationError() throws Exception {
    mvc.perform(get("/test/required-param"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("Missing")));
  }

  @Test
  void unsupportedHttpMethodReturns405ValidationError() throws Exception {
    mvc.perform(patch("/test/id/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.errors[0]").exists());
  }

  @Test
  void unsupportedMediaTypeReturns415ValidationError() throws Exception {
    mvc.perform(post("/test/echo").contentType(MediaType.TEXT_PLAIN).content("hello"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.errors[0]").exists());
  }

  @Test
  void noResourceFoundReturns404ValidationError() throws Exception {
    mvc.perform(get("/test/missing"))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.errors[0]")
                .value(org.hamcrest.Matchers.containsString("Resource not found")));
  }

  /** Minimal controller exercising the exception paths the advice handles. */
  @Controller
  static class TestController {
    @GetMapping("/test/id/{id}")
    @org.springframework.web.bind.annotation.ResponseBody
    public String getById(@PathVariable int id) {
      return String.valueOf(id);
    }

    @GetMapping("/test/required-param")
    @org.springframework.web.bind.annotation.ResponseBody
    public String requiredParam(@RequestParam String name) {
      return name;
    }

    @PostMapping(value = "/test/echo", consumes = MediaType.APPLICATION_JSON_VALUE)
    @org.springframework.web.bind.annotation.ResponseBody
    public String echo(@RequestBody java.util.Map<String, Object> body) {
      return body.toString();
    }

    @GetMapping("/test/missing")
    @org.springframework.web.bind.annotation.ResponseBody
    public String missing() throws NoResourceFoundException {
      throw new NoResourceFoundException(HttpMethod.GET, "/test/missing", "missing");
    }
  }
}
