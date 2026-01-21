package it.davidgreco.metacatalog.functions;

import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.ServiceError;
import it.davidgreco.metacatalog.service.ServiceRuntimeError;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

/**
 * Service for executing registered entity procedures and functions.
 *
 * <p>This executor maintains registries of named procedures and functions that can be invoked on
 * entities. Procedures are obtained from the Spring application context, allowing them to be
 * Spring-managed beans with dependency injection.
 *
 * @see EntityProcedure
 * @see EntityFunction
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ProcedureExecutor {

  /** Registry mapping procedure names to their implementation classes. */
  @Getter
  private static final Map<String, Class<? extends EntityProcedure>> procedureRegistry =
      new ConcurrentHashMap<>();

  /** Registry mapping function names to their implementation classes. */
  @Getter
  private static final Map<String, Class<? extends EntityFunction>> functionRegistry =
      new ConcurrentHashMap<>();

  /** Spring application context for obtaining procedure beans. */
  private final ApplicationContext applicationContext;

  /** Entity service for reading entities by ID. */
  private final EntityService entityService;

  /**
   * Executes a registered procedure on an entity.
   *
   * @param procedureName the name of the procedure to execute
   * @param entityId the ID of the entity to process
   * @throws ServiceError if the procedure is not found, the entity is not found, or execution fails
   */
  public void executeProcedure(String procedureName, String entityId) throws ServiceError {
    try {
      var entity = entityService.read(entityId);
      if (!procedureRegistry.containsKey(procedureName)) {
        throw new ServiceError("Procedure/Function not found: " + procedureName);
      }
      var provisioningProcedure =
          (EntityProcedure) applicationContext.getBean(procedureRegistry.get(procedureName));
      provisioningProcedure.accept(entity);
    } catch (ServiceRuntimeError e) {
      if (e.getCause() instanceof ServiceError se) throw se;
      else throw e;
    }
  }
}
