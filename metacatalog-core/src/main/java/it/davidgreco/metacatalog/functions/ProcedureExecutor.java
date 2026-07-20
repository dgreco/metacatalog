package it.davidgreco.metacatalog.functions;

import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.ServiceRuntimeError;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for executing registered entity procedures.
 *
 * <p>Procedures are discovered from the Spring application context: every {@link EntityProcedure}
 * bean is registered under its {@link EntityProcedure#name()}. This removes the previous reliance
 * on a process-global static registry populated from a class-loading-dependent static initializer,
 * which could silently miss lazily-created procedure beans and leaked state across tests.
 *
 * @see EntityProcedure
 */
@Slf4j
@Service
public class ProcedureExecutor {

  /** Registry mapping procedure names to their (Spring-managed) implementations. */
  private final Map<String, EntityProcedure> procedureRegistry;

  /** Entity service for reading entities by ID. */
  private final EntityService entityService;

  /**
   * Builds the procedure registry from all {@link EntityProcedure} beans in the context.
   *
   * @param procedures every procedure bean, injected by Spring
   * @param entityService entity service for reading entities by ID
   */
  public ProcedureExecutor(List<EntityProcedure> procedures, EntityService entityService) {
    this.procedureRegistry =
        procedures.stream()
            .collect(Collectors.toUnmodifiableMap(EntityProcedure::name, Function.identity()));
    this.entityService = entityService;
  }

  /**
   * Executes a registered procedure on an entity.
   *
   * @param procedureName the name of the procedure to execute
   * @param entityId the ID of the entity to process
   * @throws ServiceError if the procedure is not found, the entity is not found, or execution fails
   *     <p>Runs in a transaction so the procedure body can traverse lazily-fetched associations of
   *     the entity graph (entity type, traits, mapping rules, relationship endpoints) while
   *     building its plan. This is the non-web execution path — there is no open-session-in-view
   *     here — so without an ambient session those lazy loads would throw {@link
   *     org.hibernate.LazyInitializationException}. Only the synchronous plan-building runs in this
   *     transaction; the tasks it schedules execute on their own threads and transactions.
   */
  @Transactional
  public void executeProcedure(String procedureName, String entityId) throws ServiceError {
    try {
      var entity = entityService.read(entityId);
      var procedure = procedureRegistry.get(procedureName);
      if (procedure == null) {
        throw new ServiceError("Procedure not found: " + procedureName);
      }
      procedure.accept(entity);
    } catch (ServiceRuntimeError e) {
      if (e.getCause() instanceof ServiceError se) throw se;
      else throw e;
    }
  }
}
