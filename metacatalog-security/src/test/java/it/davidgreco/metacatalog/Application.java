package it.davidgreco.metacatalog;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal {@link SpringBootApplication} anchor for tests in this module.
 *
 * <p>It lives in the {@code it.davidgreco.metacatalog} package so that {@code @WebMvcTest} tests in
 * {@code it.davidgreco.metacatalog.security} find a {@code @SpringBootConfiguration} by searching
 * packages upwards. The actual security configuration classes under test are pulled in via
 * {@code @Import} on each test class.
 */
@SpringBootApplication
public class Application {}
