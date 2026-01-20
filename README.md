# Meta Catalog

[![Quality Gate Status](http://192.168.10.182:9000/api/project_badges/measure?project=dgreco_metacatalog_a1aac5ad-232a-476b-a015-53dccc79cc9c&metric=alert_status&token=sqb_c971245e209a2d320ffd919a3c2455b3879e24c2)](http://192.168.10.182:9000/dashboard?id=dgreco_metacatalog_a1aac5ad-232a-476b-a015-53dccc79cc9c)

A comprehensive metadata management system built with Spring Boot for managing entities, entity types, traits, and their relationships with integrated ontology support.

## Features

- **Entity Management**: CRUD operations for business entities with JSON-based attributes
- **Entity Types**: Define and manage entity type hierarchies
- **Traits**: Reusable characteristics applicable to entities
- **Relationship Management**: Complex relationship mapping between entities
- **JSON Schema Validation**: Validate entity attributes against schemas
- **Graph Operations**: Advanced relationship traversal using JGraphT
- **Ontology Integration**: Semantic web support via Ontop
- **Bulk Operations**: Efficient bulk loading and updates
- **Audit Trail**: Entity lifecycle event tracking
- **REST API**: Comprehensive OpenAPI-documented REST endpoints

## Technology Stack

- **Java 25**
- **Spring Boot 4.0.1**
- **PostgreSQL 42.7.5**
- **Maven 3.9.9+**
- **Ontop CLI v5**

## Prerequisites

- Java 25
- Maven 3.9.9 or higher
- PostgreSQL 14+
- Docker (for testing with Testcontainers)
- Ontop CLI v5 (optional, for ontology integration)

## Quick Start

### 1. Clone the Repository

```bash
git clone <repository-url>
cd metacatalog
```

### 2. Setup Database

```bash
# Create PostgreSQL database
createdb metacatalog

# Create user
psql -c "CREATE USER metacatalog WITH PASSWORD 'metacatalog';"
psql -c "GRANT ALL PRIVILEGES ON DATABASE metacatalog TO metacatalog;"
```

### 3. Build the Project

```bash
mvn clean install
```

### 4. Run the Application

```bash
mvn spring-boot:run -pl metacatalog-application
```

The application will start on the default Spring Boot port (8080).

## Project Structure

```
metacatalog/
├── metacatalog-core/           # Core domain, repositories, and services
├── metacatalog-functions/      # Custom PostgreSQL functions
├── metacatalog-openapi/        # OpenAPI specs and generated code
└── metacatalog-application/    # Spring Boot application
```

## Development

### Code Formatting

This project uses Google Java Format via Spotless. Always format your code before committing:

```bash
mvn spotless:apply
```

Check formatting:

```bash
mvn spotless:check
```

### Running Tests

```bash
# Unit tests
mvn test

# All tests including integration tests
mvn verify
```

### Dependency Management

Check for dependency updates:

```bash
mvn versions:display-dependency-updates
```

Check for plugin updates:

```bash
mvn versions:display-plugin-updates
```

Analyze dependencies:

```bash
mvn dependency:check
```

### Security & License Scanning

Scan for license violations:

```bash
mvn licensescan:audit
```

OWASP dependency vulnerability check:

```bash
mvn dependency-check:check
```

### Code Quality

Run SonarQube analysis:

```bash
mvn verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar
```

View the [SonarQube Dashboard](http://192.168.10.182:9000/dashboard?id=dgreco_metacatalog_a1aac5ad-232a-476b-a015-53dccc79cc9c)

## Ontop Integration

Meta Catalog integrates with Ontop for ontology-based data access.

### Running Ontop Endpoint

```bash
../ontop-cli-5/ontop endpoint \
  --db-url "jdbc:postgresql://localhost:5432/metacatalog?loggerLevel=OFF" \
  -m src/main/resources/ontop/mapping.obda \
  -t src/main/resources/ontop/ontology.owl \
  --db-user metacatalog \
  --db-password metacatalog \
  --port 8081
```

### Bootstrap Ontop Mappings

To generate initial mappings from your database schema:

```bash
ontop bootstrap \
  --db-url "jdbc:postgresql://localhost:32819/test?loggerLevel=OFF" \
  -m mapping.obda \
  -t ontology.owl \
  -b http://test \
  --db-user test \
  --db-password test
```

## API Documentation

When the application is running, access the interactive API documentation:

- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **OpenAPI Spec**: http://localhost:8080/v3/api-docs

## Key Libraries & Frameworks

- **Spring Boot**: Web, JPA, Cache, Actuator, Validation
- **Vavr**: Functional programming utilities
- **JGraphT**: Graph algorithms for relationship traversal
- **JSON Schema Validator**: Schema validation for entity attributes
- **Hypersistence Utils**: Advanced Hibernate features
- **Flyway**: Database migration management
- **Testcontainers**: Integration testing with PostgreSQL

## Configuration

Application configuration is managed through:
- `application.properties` / `application.yml`
- `CoreConfigProperties` class for custom properties

## Quality Gates

The build will fail if:
- Code is not formatted (Spotless check)
- Critical vulnerabilities (CVSS ≥ 9) are found
- GPL v2.0 licensed dependencies are detected
- SonarQube quality gate fails
- Tests fail

## Database Migrations

Database schema is managed by Flyway. Migration scripts are located in:
```
src/main/resources/db/migration/
```

Migrations run automatically on application startup.

## Monitoring & Management

Spring Boot Actuator endpoints are enabled for monitoring:
- `/actuator/health` - Health check
- `/actuator/info` - Application info
- `/actuator/metrics` - Metrics

## Contributing

1. Create a feature branch from `main`
2. Make your changes
3. Run `mvn spotless:apply` to format code
4. Run `mvn verify` to ensure tests pass
5. Commit and push your changes
6. Create a pull request

## License

[Add your license information here]

## Support

For issues and questions, please use the project's issue tracker.

## Additional Documentation

For detailed project information, see [CLAUDE.MD](CLAUDE.MD) which contains comprehensive documentation for developers and AI assistants.
