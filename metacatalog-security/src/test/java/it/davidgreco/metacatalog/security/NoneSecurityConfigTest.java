package it.davidgreco.metacatalog.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies that with {@code auth-mode = none} every request is permitted, including the
 * otherwise-protected API path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "application.config.security.auth-mode=none")
class NoneSecurityConfigTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void apiEndpointIsAccessibleWithoutCredentials() throws Exception {
    mockMvc
        .perform(get("/metacatalog/v1/test"))
        .andExpect(status().isOk())
        .andExpect(content().string("ok"));
  }
}
