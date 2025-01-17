package it.davidgreco.metacatalog;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("application.config")
@Getter
@Setter
@RequiredArgsConstructor
public class ApplicationConfigProperties {

    private final boolean automaticEntitiesMapping;
}
