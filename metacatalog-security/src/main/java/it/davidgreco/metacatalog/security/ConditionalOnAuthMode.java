package it.davidgreco.metacatalog.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Activates a bean only when the configured {@link AuthMode} matches the specified value.
 *
 * <p>This is the enum-driven replacement for {@code @ConditionalOnProperty(name =
 * AUTH_MODE_PROPERTY, havingValue = "...")}. Using the {@link AuthMode} enum directly makes the
 * dispatch type-safe: adding a new mode is additive (new enum constant + new config class annotated
 * with {@code @ConditionalOnAuthMode(NEW_MODE)}), and a typo in a mode name is a compile error.
 *
 * <p>When the {@code application.config.security.auth-mode} property is missing, the condition
 * matches {@link AuthMode#NONE} (the default), mirroring the previous {@code matchIfMissing = true}
 * behaviour.
 *
 * @param value the auth mode that must be active for the condition to match
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ConditionalOnAuthMode.AuthModeCondition.class)
public @interface ConditionalOnAuthMode {

  AuthMode value();

  class AuthModeCondition implements org.springframework.context.annotation.Condition {
    @Override
    public boolean matches(
        org.springframework.context.annotation.ConditionContext context,
        org.springframework.core.type.AnnotatedTypeMetadata metadata) {
      var env = context.getEnvironment();
      var modeStr =
          env.getProperty(SecurityConfigProperties.AUTH_MODE_PROPERTY, AuthMode.NONE.name());
      try {
        var configured = AuthMode.valueOf(modeStr.toUpperCase());
        var required =
            (AuthMode)
                metadata
                    .getAnnotationAttributes(ConditionalOnAuthMode.class.getName())
                    .get("value");
        return configured == required;
      } catch (IllegalArgumentException e) {
        return false;
      }
    }
  }
}
