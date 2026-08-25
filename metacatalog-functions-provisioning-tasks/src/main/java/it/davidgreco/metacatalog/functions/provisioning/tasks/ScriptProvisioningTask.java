package it.davidgreco.metacatalog.functions.provisioning.tasks;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.functions.provisioning.ProvisioningTask;
import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.ServiceError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * A provisioning task that delegates to a bash script, handing it the entity's JSON values on
 * standard input.
 *
 * <p>The script is invoked as {@code bash <path> <operation>} where {@code <operation>} is {@code
 * provision}, {@code unprovision}, {@code authorize} or {@code reject}, so one script serves every
 * direction and can tell them apart. Whatever the script prints is relayed to this process's
 * standard output — like {@link StdoutProvisioningTask}, deliberately not the logger, because the
 * application ships with {@code logging.level.root: ERROR} — and recorded in the entity's {@code
 * provisioningResult} or {@code authorizationResult}, whichever the operation writes.
 *
 * <p>A script wired to a type that is only provisionable never sees the authorization arguments,
 * and vice versa: which operations reach it is decided by the traits its entity type carries, not
 * by the task. One that handles both should branch on the argument rather than assume.
 *
 * <p>A non-zero exit status fails the task (and with it the run), as does a script still running
 * after {@link #TIMEOUT_MINUTES} minutes — provisioning is synchronous, so a hanging script would
 * otherwise hang the whole procedure call.
 */
public class ScriptProvisioningTask extends ProvisioningTask {

  /** How long the script may run before the task gives up and fails. */
  static final long TIMEOUT_MINUTES = 5;

  private final String scriptPath;

  /**
   * @param entity the resource this task acts on
   * @param entityService used to record the outcome on the entity
   * @param scriptPath the filesystem path of the bash script to run
   */
  public ScriptProvisioningTask(Entity entity, EntityService entityService, String scriptPath) {
    super(entity, entityService);
    this.scriptPath = scriptPath;
  }

  @Override
  public String provision() {
    return runScript("provision", "Provisioned");
  }

  @Override
  public String unprovision() {
    return runScript("unprovision", "Unprovisioned");
  }

  @Override
  public String authorize() {
    return runScript("authorize", "Authorized");
  }

  @Override
  public String reject() {
    return runScript("reject", "Rejected");
  }

  private String runScript(String operation, String outcome) {
    var entity = getEntity();
    var resource = entity.getEntityType().getName() + " id=" + entity.getId();
    try {
      var process =
          new ProcessBuilder("bash", scriptPath, operation).redirectErrorStream(true).start();
      try (var stdin = process.getOutputStream()) {
        stdin.write(entity.getValues().toString().getBytes(StandardCharsets.UTF_8));
      }
      // Read before waiting: a script printing more than the pipe buffers would otherwise block
      // forever, with waitFor never returning.
      var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
        process.destroyForcibly();
        throw new ServiceError(
            "Script " + scriptPath + " timed out after " + TIMEOUT_MINUTES + " minutes");
      }
      System.out.print(output);
      if (process.exitValue() != 0) {
        throw new ServiceError(
            "Script " + scriptPath + " exited with status " + process.exitValue() + ": " + output);
      }
      return outcome + " " + resource + " (script task): " + output.strip();
    } catch (IOException e) {
      throw new ServiceError("Failed to run script " + scriptPath + ": " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServiceError("Interrupted while waiting for script " + scriptPath, e);
    }
  }
}
