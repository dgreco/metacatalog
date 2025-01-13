
`mvn versions:display-dependency-updates`

`mvn versions:display-plugin-updates`

`mvn versions:display-plugin-updates`

`mvn spotless:apply`

`mvn dependency:check`

`mvn licensescan:audit`

`../ontop-cli-5/ontop endpoint --db-url "jdbc:postgresql://localhost:5432/metacatalog?loggerLevel=OFF" -m src/main/resources/ontop/mapping.obda -t src/main/resources/ontop/ontology.owl --db-user metacatalog --db-password metacatalog --port 8081`

To bootstrap with ontop-cli: 
`ontop bootstrap --db-url "jdbc:postgresql://localhost:32819/test?loggerLevel=OFF" -m mapping.obda -t ontology.owl -b http://test --db-user test --db-password test`