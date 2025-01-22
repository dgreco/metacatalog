[![Quality Gate Status](http://192.168.10.182:9000/api/project_badges/measure?project=dgreco_metacatalog_a1aac5ad-232a-476b-a015-53dccc79cc9c&metric=alert_status&token=sqb_c971245e209a2d320ffd919a3c2455b3879e24c2)](http://192.168.10.182:9000/dashboard?id=dgreco_metacatalog_a1aac5ad-232a-476b-a015-53dccc79cc9c)


`mvn versions:display-dependency-updates`

`mvn versions:display-plugin-updates`

`mvn versions:display-plugin-updates`

`mvn spotless:apply`

`mvn dependency:check`

`mvn licensescan:audit`

`../ontop-cli-5/ontop endpoint --db-url "jdbc:postgresql://localhost:5432/metacatalog?loggerLevel=OFF" -m src/main/resources/ontop/mapping.obda -t src/main/resources/ontop/ontology.owl --db-user metacatalog --db-password metacatalog --port 8081`

To bootstrap with ontop-cli: 
`ontop bootstrap --db-url "jdbc:postgresql://localhost:32819/test?loggerLevel=OFF" -m mapping.obda -t ontology.owl -b http://test --db-user test --db-password test`