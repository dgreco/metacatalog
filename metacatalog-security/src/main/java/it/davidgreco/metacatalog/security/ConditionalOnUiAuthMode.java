package it.davidgreco.metacatalog.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Meta-annotation activating a bean only in auth modes whose {@link AuthMode#isUiMode()} returns
 * true — i.e. {@code basic} and {@code ldap}.
 *
 * <p>The set of UI-capable modes is defined by {@link AuthMode#isUiMode()}, not by a hardcoded SpEL
 * string, so adding a new UI-capable mode is a one-line change to the enum.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ConditionalOnUiAuthMode.UiAuthModeCondition.class)
public @interface ConditionalOnUiAuthMode {

  /**
   * Spring condition that resolves the active {@code auth-mode} property and delegates to {@link
   * AuthMode#isUiMode()}.
   */
  class UiAuthModeCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      var env = context.getEnvironment();
      var modeStr =
          env.getProperty(SecurityConfigProperties.AUTH_MODE_PROPERTY, AuthMode.NONE.name());
      try {
        return AuthMode.valueOf(modeStr.toUpperCase()).isUiMode();
      } catch (IllegalArgumentException e) {
        return false;
      }
    }
  }
}
