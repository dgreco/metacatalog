
`mvn versions:display-dependency-updates`

`mvn versions:display-plugin-updates`

`mvn spotless:apply`

`mvn dependency:check`

`mvn licensescan:audit`

To bootstrap with ontop-cli: 
`ontop bootstrap --db-url "jdbc:postgresql://localhost:32819/test?loggerLevel=OFF" -m mapping.obda -t ontology.owl -b http://test --db-user test --db-password test`