package it.davidgreco.metacatalog.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the form-login flow for the UI when {@code auth-mode = basic}:
 *
 * <ul>
 *   <li>Unauthenticated access to {@code /ui/**} redirects to {@code /login}
 *   <li>{@code POST /login} with valid credentials creates a session and redirects to {@code /ui}
 *   <li>After login, {@code /ui/**} is accessible
 *   <li>{@code POST /logout} clears the session and redirects to {@code /login?logout=true}
 *   <li>The REST API at {@code /metacatalog/v1/**} accepts HTTP Basic credentials (stateless) and
 *       also reuses the UI form-login session, so Swagger "Try it out" XHRs are authenticated
 *       without re-authentication
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "application.config.security.auth-mode=basic",
      "application.config.security.basic.users[0].username=admin",
      "application.config.security.basic.users[0].password={noop}secret",
      "application.config.security.basic.users[0].roles[0]=USER"
    })
class UiFormLoginTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void uiWithoutSessionRedirectsToLogin() throws Exception {
    mockMvc
        .perform(get("/ui/test"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login"));
  }

  @Test
  void loginPageIsAccessible() throws Exception {
    mockMvc.perform(get("/login")).andExpect(status().isOk());
  }

  @Test
  void loginWithValidCredentialsRedirectsToUi() throws Exception {
    mockMvc
        .perform(post("/login").param("username", "admin").param("password", "secret").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/ui"));
  }

  @Test
  void loginWithWrongCredentialsRedirectsBackToLoginWithError() throws Exception {
    mockMvc
        .perform(post("/login").param("username", "admin").param("password", "wrong").with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login?error=true"));
  }

  @Test
  void fullLoginLogoutFlow() throws Exception {
    final var session =
        mockMvc
            .perform(
                post("/login").param("username", "admin").param("password", "secret").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/ui"))
            .andReturn()
            .getRequest()
            .getSession();

    assert session != null;

    mockMvc
        .perform(
            get("/ui/test")
                .sessionAttr(
                    "SPRING_SECURITY_CONTEXT", session.getAttribute("SPRING_SECURITY_CONTEXT")))
        .andExpect(status().isOk())
        .andExpect(content().string("ui-ok"));

    mockMvc
        .perform(
            post("/logout")
                .sessionAttr(
                    "SPRING_SECURITY_CONTEXT", session.getAttribute("SPRING_SECURITY_CONTEXT"))
                .with(csrf())
                .accept("text/html"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login?logout=true"));

    mockMvc
        .perform(
            get("/ui/test")
                .sessionAttr(
                    "SPRING_SECURITY_CONTEXT", session.getAttribute("SPRING_SECURITY_CONTEXT")))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login"));
  }

  @Test
  void apiStillUsesBasicAuth() throws Exception {
    mockMvc.perform(get("/metacatalog/v1/test")).andExpect(status().isUnauthorized());

    mockMvc
        .perform(get("/metacatalog/v1/test").with(httpBasic("admin", "secret")))
        .andExpect(status().isOk())
        .andExpect(content().string("ok"));
  }

  @Test
  void apiReusesUiSessionWithoutReauthentication() throws Exception {
    // Unauthenticated: the API rejects with 401 (Basic entry point, no creds and no session).
    mockMvc.perform(get("/metacatalog/v1/test")).andExpect(status().isUnauthorized());

    // Log in via the UI form-login flow and grab the session.
    final var session =
        mockMvc
            .perform(
                post("/login").param("username", "admin").param("password", "secret").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/ui"))
            .andReturn()
            .getRequest()
            .getSession();
    assert session != null;
    final var securityContext = session.getAttribute("SPRING_SECURITY_CONTEXT");

    // A Swagger "Try it out" XHR to the API rides the same session — no Basic header, no re-auth.
    mockMvc
        .perform(
            get("/metacatalog/v1/test").sessionAttr("SPRING_SECURITY_CONTEXT", securityContext))
        .andExpect(status().isOk())
        .andExpect(content().string("ok"));

    // After logout, the API no longer recognises the session.
    mockMvc
        .perform(
            post("/logout")
                .sessionAttr("SPRING_SECURITY_CONTEXT", securityContext)
                .with(csrf())
                .accept("text/html"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login?logout=true"));
    mockMvc
        .perform(
            get("/metacatalog/v1/test").sessionAttr("SPRING_SECURITY_CONTEXT", securityContext))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void sparqlUiAndQueryReuseUiSessionWithoutReauthentication() throws Exception {
    // Unauthenticated: the SPARQL UI page redirects to /login (form-login chain),
    // the protocol endpoint returns 401 (Basic entry point, no creds).
    mockMvc
        .perform(get("/sparql"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login"));
    mockMvc
        .perform(get("/sparql/query").param("query", "ASK { ?s ?p ?o }"))
        .andExpect(status().isUnauthorized());

    // Log in via the UI form-login flow and grab the session.
    final var session =
        mockMvc
            .perform(
                post("/login").param("username", "admin").param("password", "secret").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/ui"))
            .andReturn()
            .getRequest()
            .getSession();
    assert session != null;
    final var securityContext = session.getAttribute("SPRING_SECURITY_CONTEXT");

    // The SPARQL UI page rides the same session (no re-auth).
    mockMvc
        .perform(get("/sparql").sessionAttr("SPRING_SECURITY_CONTEXT", securityContext))
        .andExpect(
            status().isNotFound()); // no SPARQL controller in this test app → 404 = passed security

    // Yasgui's XHR to the protocol endpoint rides the same session (no re-auth).
    mockMvc
        .perform(
            get("/sparql/query")
                .param("query", "ASK { ?s ?p ?o }")
                .sessionAttr("SPRING_SECURITY_CONTEXT", securityContext))
        .andExpect(status().isNotFound()); // no SPARQL controller here → 404 = passed security
  }
}
