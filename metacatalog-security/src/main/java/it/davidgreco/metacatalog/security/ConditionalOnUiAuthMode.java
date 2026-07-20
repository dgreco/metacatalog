package it.davidgreco.metacatalog.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/**
 * Meta-annotation activating a bean only in the auth modes that have a username/password to check
 * against a browser session: {@code basic} and {@code ldap}.
 *
 * <p>Centralises the {@code auth-mode == basic || ldap} condition that the UI security chain, the
 * SPARQL Protocol chain and the login controller all share, so the expression is declared once.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnExpression(
    "'${application.config.security.auth-mode:}' eq 'basic'"
        + " or '${application.config.security.auth-mode:}' eq 'ldap'")
public @interface ConditionalOnUiAuthMode {}
