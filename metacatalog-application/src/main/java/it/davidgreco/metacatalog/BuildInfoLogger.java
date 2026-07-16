package it.davidgreco.metacatalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs the running build's identity once the application is ready, so it is easy to confirm which
 * version is actually running (for example after a {@code docker compose up --build}).
 *
 * <p>The same information is served by {@code /actuator/info}. Build and git properties are
 * optional — they are only present when {@code build-info.properties} / {@code git.properties} were
 * generated at build time — hence the {@link ObjectProvider} lookups.
 */
@Component
public class BuildInfoLogger {

  private static final Logger log = LoggerFactory.getLogger(BuildInfoLogger.class);

  private final ObjectProvider<BuildProperties> buildProperties;
  private final ObjectProvider<GitProperties> gitProperties;

  public BuildInfoLogger(
      ObjectProvider<BuildProperties> buildProperties,
      ObjectProvider<GitProperties> gitProperties) {
    this.buildProperties = buildProperties;
    this.gitProperties = gitProperties;
  }

  /** Emits a single line summarising version, git commit and build time. */
  @EventListener(ApplicationReadyEvent.class)
  public void logBuildInfo() {
    var build = buildProperties.getIfAvailable();
    var git = gitProperties.getIfAvailable();

    var version = build != null ? build.getVersion() : "unknown";
    var builtAt = build != null && build.getTime() != null ? build.getTime().toString() : "unknown";
    var commit = git != null ? git.getShortCommitId() : "unknown";
    var dirty = git != null && "true".equals(git.get("dirty")) ? " (dirty)" : "";

    log.info("Meta Catalog {} started (git {}{}, built {})", version, commit, dirty, builtAt);
  }
}
