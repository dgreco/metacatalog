package it.davidgreco.metacatalog.functions.provisioning.tasks;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the provisioning tasks, loaded under the {@code
 * application.config.provisioning} prefix.
 *
 * <p>The {@code tasks} map associates each provisioning task with the entity types it handles:
 *
 * <pre>{@code
 * application:
 *   config:
 *     provisioning:
 *       tasks:
 *         stdout:
 *           entity-types: [S3FolderType]
 *         script:
 *           entity-types: [AthenaTableType]
 *           path: /opt/scripts/provision.sh
 * }</pre>
 *
 * <p>Keys are task names — {@code stdout} for {@link StdoutProvisioningTasks}, {@code script} for
 * {@link ScriptProvisioningTasks} — and deliberately <em>not</em> entity type names: Spring's
 * relaxed binding mangles map keys that are not lowercase alpha-numerics, so a {@code S3FolderType}
 * key would silently bind as {@code s3foldertype} unless bracket-quoted. Keying by task also gives
 * task-specific settings ({@code path}) a natural home. Entity type names rather than traits,
 * because that is the key task factories are looked up by; configuration rather than constants,
 * because entity types are catalogue data created at runtime. Defaults to empty, so adding this
 * module to an application changes nothing until types are named.
 *
 * @param tasks the entity types each provisioning task handles, keyed by task name
 */
@ConfigurationProperties("application.config.provisioning")
public record ProvisioningConfigProperties(Map<String, TaskConfig> tasks) {

  public ProvisioningConfigProperties {
    tasks = tasks == null ? Map.of() : Map.copyOf(tasks);
    // An entity type listed under two tasks would be registered by whichever registrar runs first
    // and silently skipped by the other — refuse the ambiguity at startup instead.
    var seen = new HashSet<String>();
    for (var entry : tasks.entrySet()) {
      for (var entityType : entry.getValue().entityTypes()) {
        if (!seen.add(entityType)) {
          throw new IllegalArgumentException(
              "Entity type "
                  + entityType
                  + " is associated with more than one provisioning task in"
                  + " application.config.provisioning.tasks");
        }
      }
    }
  }

  /** The entity types this registrar handles, or an empty list if the task is not configured. */
  public List<String> entityTypesOf(String taskName) {
    var taskConfig = tasks.get(taskName);
    return taskConfig == null ? List.of() : taskConfig.entityTypes();
  }

  /** The configuration of one provisioning task, or {@code null} if it is not configured. */
  public TaskConfig taskConfig(String taskName) {
    return tasks.get(taskName);
  }

  /**
   * The configuration of one provisioning task.
   *
   * @param entityTypes the entity types the task handles
   * @param path task-specific: the filesystem path of the bash script run by the {@code script}
   *     task; unused by {@code stdout}
   */
  public record TaskConfig(List<String> entityTypes, String path) {

    public TaskConfig {
      entityTypes = entityTypes == null ? List.of() : List.copyOf(entityTypes);
    }
  }
}
