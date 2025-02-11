package it.davidgreco.metacatalog.functions.provisioning;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class ProvisioningConfig {

  @Bean(name = "threadPoolTaskExecutor")
  public TaskExecutor threadPoolTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(5); // Minimum number of threads in the pool
    executor.setMaxPoolSize(10); // Maximum number of threads in the pool
    executor.setQueueCapacity(25); // Queue capacity for pending tasks
    executor.setThreadNamePrefix("AsyncExecutor-"); // Prefix for thread names
    executor.setWaitForTasksToCompleteOnShutdown(true); // Ensures tasks complete on shutdown
    executor.setAwaitTerminationSeconds(60); // Timeout for waiting for tasks to complete
    executor.initialize(); // Initializes the thread pool
    return executor;
  }
}
