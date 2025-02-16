package it.davidgreco.metacatalog.functions.provisioning;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ProvisioningConfig {

  @Bean(name = "threadPoolProvisioningExecutor")
  @Primary
  public AsyncTaskExecutor threadPoolTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(5); // Minimum number of threads in the pool
    executor.setMaxPoolSize(10); // Maximum number of threads in the pool
    executor.setQueueCapacity(25); // Queue capacity for pending tasks
    executor.setThreadNamePrefix("AsyncProvisioningTaskExecutor-"); // Prefix for thread names
    executor.setWaitForTasksToCompleteOnShutdown(true); // Ensures tasks complete on shutdown
    executor.setAwaitTerminationSeconds(60); // Timeout for waiting for tasks to complete
    executor.initialize(); // Initializes the thread pool
    return executor;
  }

  @Bean()
  public ProvisioningTaskScheduler provisioningTaskScheduler(AsyncTaskExecutor asyncTaskExecutor) {
    return new ProvisioningTaskScheduler(asyncTaskExecutor);
  }
}
