package it.witboost.dataplatformshaper;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import it.unibz.inf.ontop.injection.OntopSQLOWLAPIConfiguration;
import it.unibz.inf.ontop.rdf4j.repository.OntopRepository;
import it.witboost.dataplatformshaper.entity.Address;
import it.witboost.dataplatformshaper.entity.Customer;
import it.witboost.dataplatformshaper.repository.AddressRepository;
import it.witboost.dataplatformshaper.repository.CustomerRepository;
import java.io.InputStreamReader;
import java.util.Objects;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
class ApplicationTests {

    static final int POSTGRESQL_PORT = 5433;

    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withExposedPorts(5432)
            .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(new HostConfig()
                    .withPortBindings(
                            new PortBinding(Ports.Binding.bindPort(POSTGRESQL_PORT), new ExposedPort(5432)))));
    ;

    @BeforeAll
    static void beforeAll() {
        postgres.start();

        var flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    @AfterAll
    static void afterAll() throws InterruptedException {
        // Thread.sleep(200000);
        postgres.stop();
    }

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AddressRepository addressRepository;

    @Test
    void testDatabase() {

        var customer1 = new Customer();
        customer1.setFirstName("David");
        customer1.setFamilyName("Greco");
        customer1.setAge(64);
        customerRepository.save(customer1);

        var address1 = new Address();
        address1.setAddressLine1("Via Tempio 43");
        address1.setCity("Cagliari");
        address1.setState("Cagliari");
        address1.setCountry("Italy");
        address1.setZipcode("09127");
        address1.setCustomer(customer1);
        addressRepository.save(address1);

        var address2 = new Address();
        address2.setAddressLine1("Via Delle Benedettine 47");
        address2.setCity("Roma");
        address2.setState("Roma");
        address2.setCountry("Italy");
        address2.setZipcode("00135");
        address2.setCustomer(customer1);
        addressRepository.save(address2);

        var retrievedCustomer = customerRepository.findById(customer1.getId()).get();
        System.out.println(retrievedCustomer);

        OntopSQLOWLAPIConfiguration configuration = OntopSQLOWLAPIConfiguration.defaultBuilder()
                .ontologyReader(new InputStreamReader(Objects.requireNonNull(
                        Thread.currentThread().getContextClassLoader().getResourceAsStream("ontop/ontology.owl"))))
                .nativeOntopMappingReader(new InputStreamReader(Objects.requireNonNull(
                        Thread.currentThread().getContextClassLoader().getResourceAsStream("ontop/mapping.obda"))))
                .jdbcUrl(postgres.getJdbcUrl())
                .jdbcUser("test")
                .jdbcPassword("test")
                .enableTestMode()
                .build();

        try (var repo = OntopRepository.defaultRepository(configuration)) {
            repo.init();

            try (var conn = repo.getConnection();
                    TupleQueryResult result = conn.prepareTupleQuery(
                                    QueryLanguage.SPARQL,
                                    """
                                SELECT ?s ?p ?o WHERE {?s ?p ?o}
                        """)
                            .evaluate()) {
                while (result.hasNext()) {
                    BindingSet bindingSet = result.next();
                    System.out.println(bindingSet);
                }
            }

            try (var conn = repo.getConnection();
                    TupleQueryResult result = conn.prepareTupleQuery(
                                    QueryLanguage.SPARQL,
                                    """
                                PREFIX ns: <http://witboost/>
                                PREFIX cust: <http://witboost/customer#>
                                PREFIX addr: <http://witboost/address#>

                                SELECT ?n ?c WHERE {
                                  ?s ns:hasAddress ?o .
                                  ?o addr:city ?c .
                                  ?s cust:first_name ?n .
                                }
                        """)
                            .evaluate()) {
                while (result.hasNext()) {
                    BindingSet bindingSet = result.next();
                    System.out.println(bindingSet);
                }
            }
        }
    }
}
