package it.davidgreco.metacatalog.functions.provisioning;

import io.vavr.control.Try;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class ProvisioningTaskRunner {

  @Async("threadPoolTaskExecutor")
  public <T, R> CompletableFuture<Try<R>> runTask(Function<T, R> task, T input) {
    return CompletableFuture.completedFuture(Try.of(() -> task.apply(input)));
  }
}
