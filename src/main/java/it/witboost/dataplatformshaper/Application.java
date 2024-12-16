package it.witboost.dataplatformshaper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@ComponentScan(
        basePackages = {
            "com.example.petstore.controller",
            "it.agilelab.datamesh.glossaryplugin.service.impl",
            "it.agilelab.datamesh.glossaryplugin.repository",
            "it.agilelab.datamesh.glossaryplugin.entity"
        })
@SpringBootApplication
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
