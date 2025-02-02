package it.davidgreco.metacatalog.functions.common;

import it.davidgreco.metacatalog.service.EntityService;
import it.davidgreco.metacatalog.service.ServiceError;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class ProcedureExecutor {

  @Getter
  private static final Map<String, Class<? extends EntityProcedure>> procedureRegistry =
      new ConcurrentHashMap<>();

  @Getter
  private static final Map<String, Class<? extends EntityFunction>> functionRegistry =
      new ConcurrentHashMap<>();

  private final ApplicationContext applicationContext;

  private final EntityService entityService;

  public void executeProcedure(String procedureName, String entityId) throws ServiceError {

    var entity = entityService.read(entityId);

    if (!procedureRegistry.containsKey(procedureName)) {
      throw new ServiceError("Procedure/Function not found: " + procedureName);
    }
    var provisioningProcedure =
        (EntityProcedure) applicationContext.getBean(procedureRegistry.get(procedureName));

    provisioningProcedure.accept(entity);
  }
}
