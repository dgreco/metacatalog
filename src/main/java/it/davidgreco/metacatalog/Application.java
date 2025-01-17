package it.davidgreco.metacatalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
@EnableConfigurationProperties(ApplicationConfigProperties.class)
@EnableAsync
@EnableScheduling
public class Application {

    protected Application() {}

    /**
     *
     * @param args
     */
    public static void main(final String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
