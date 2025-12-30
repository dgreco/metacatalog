# Meta Catalog - Project Guide

## Project Overview

Meta Catalog is a Spring Boot-based metadata management system that provides a comprehensive catalog for managing entities, entity types, traits, and their relationships. The project uses PostgreSQL for persistence and includes ontology integration via Ontop.

**Group ID**: `it.davidgreco`
**Artifact ID**: `metacatalog`
**Version**: 0.0.1-SNAPSHOT

## Technology Stack

### Core Framework
- **Spring Boot**: 4.0.1
- **Java Version**: 23
- **Maven**: 3.9.9+ required
- **Lombok**: 1.18.36

### Known Issues
- **Current Build Status**: Project currently has compilation issues with Spring Boot 4.0.1, Java 23, and Lombok 1.18.36 combination
- Lombok annotation processing is not working correctly, causing missing generated code (@Slf4j, @RequiredArgsConstructor, @Getter, @Setter)
- Recommendation: Consider downgrading to Spring Boot 3.x or Java 21 LTS until compatibility issues are resolved

### Database & Persistence
- **PostgreSQL**: 42.7.5
- **Spring Data JPA**: Hibernate 6.3 compatible
- **Flyway**: 11.2.0 (database migrations)
- **Hypersistence Utils**: 3.9.0
- **Ontop**: CLI v5 for ontology-based data access

### Key Libraries
- **Vavr**: 0.10.5 (functional programming)
- **JGraphT**: 1.5.2 (graph algorithms)
- **JSON Schema Validator**: 1.5.5
- **JSON Path**: 2.9.0
- **JSQLParser**: 5.1

### API & Documentation
- **Swagger/OpenAPI**: 2.2.28
- **Swagger Parser**: 2.1.25
- **SpringDoc**: 2.8.3
- **OpenAPI Generator**: 7.10.0

### Testing
- **Testcontainers**: 1.20.4 (PostgreSQL integration tests)
- **JaCoCo**: 0.8.12 (code coverage)

### Code Quality & Security
- **Spotless**: 3.0.0 (Google Java Format 1.25.2)
- **OWASP Dependency Check**: 12.0.0 (CVEs > 9 fail build)
- **License Scanner**: 3.2 (GPL v2 forbidden)
- **SpotBugs Annotations**: 4.9.0
- **SonarQube**: Integrated with quality gate

## Project Structure

```
metacatalog/
├── metacatalog-core/           # Core domain entities, repositories, and services
│   ├── entity/                 # JPA entities (Entity, EntityType, Trait, Relationships)
│   ├── repository/             # Spring Data repositories
│   ├── service/                # Business logic services
│   ├── functions/              # Custom database procedures and functions
│   └── common/                 # Common utilities (JSON handling, etc.)
├── metacatalog-functions/      # Custom PostgreSQL functions
├── metacatalog-openapi/        # OpenAPI specifications and generated code
└── metacatalog-application/    # Spring Boot application entry point
```

## Core Domain Model

### Primary Entities
- **Entity**: Core business entities with JSON-based attributes
- **EntityType**: Type definitions for entities
- **Trait**: Reusable characteristics that can be applied to entities
- **EntityRelationship**: Relationships between entities
- **EntityLifeCycleEvent**: Audit trail of entity changes

### Relationship Types
- **MappingEntityRelationship**: Maps entity relationships
- **MappingEntityTypeRelationship**: Maps entity type relationships
- **TraitRelationship**: Trait-to-entity relationships
- **CommonRelationship**: Base relationship abstraction

### Services
- **EntityService**: Entity CRUD and lifecycle management
- **EntityTypeService**: Entity type management
- **TraitService**: Trait management
- **MappingService**: Relationship mapping logic
- **AggregateService**: Aggregation operations
- **BulkLoaderService**: Bulk data loading
- **MappingUpdaterService**: Update mapping relationships
- **TaskManager/TaskFactory**: Asynchronous task management

## Development Commands

### Build & Verify
```bash
mvn clean verify
```

### Code Formatting
```bash
mvn spotless:apply      # Auto-format code
mvn spotless:check      # Check formatting
```

### Dependency Management
```bash
mvn versions:display-dependency-updates    # Check for dependency updates
mvn versions:display-plugin-updates        # Check for plugin updates
mvn dependency:check                        # Analyze dependencies
```

### Security & License Scanning
```bash
mvn licensescan:audit                      # Check licenses (fails on GPL v2)
mvn dependency-check:check                 # OWASP vulnerability scan
```

### Code Quality
```bash
mvn verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar
```

### Testing
```bash
mvn test                                   # Run unit tests
mvn verify                                 # Run all tests including integration
```

## Running the Application

### Standard Run
```bash
mvn spring-boot:run -pl metacatalog-application
```

### With Ontop Integration
The project integrates with Ontop for ontology-based data access. Run Ontop endpoint separately:

```bash
../ontop-cli-5/ontop endpoint \
  --db-url "jdbc:postgresql://localhost:5432/metacatalog?loggerLevel=OFF" \
  -m src/main/resources/ontop/mapping.obda \
  -t src/main/resources/ontop/ontology.owl \
  --db-user metacatalog \
  --db-password metacatalog \
  --port 8081
```

### Ontop Bootstrapping
To bootstrap Ontop mappings from database:
```bash
ontop bootstrap \
  --db-url "jdbc:postgresql://localhost:32819/test?loggerLevel=OFF" \
  -m mapping.obda \
  -t ontology.owl \
  -b http://test \
  --db-user test \
  --db-password test
```

## Database Setup

### PostgreSQL
- **Default Database**: metacatalog
- **Default Credentials**: metacatalog/metacatalog
- **Port**: 5432 (local), 32819 (test container)
- **Migrations**: Managed by Flyway

### Docker PostgreSQL for Testing
The project uses Testcontainers for integration tests, which automatically spins up PostgreSQL containers.

## Code Style & Conventions

### Formatting
- **Google Java Format** (v1.25.2) is enforced via Spotless
- Run `mvn spotless:apply` before committing
- Spotless check runs during `validate` phase

### Annotations
- **Lombok** is used extensively for boilerplate reduction
- **SpotBugs annotations** for static analysis hints

### Package Structure
- Each major package has a `package-info.java` for documentation
- Clear separation between entity, repository, and service layers

## Quality Gates

### Build Fails On
1. **Spotless check failure** (code not formatted)
2. **CVSS score ≥ 9** (critical security vulnerabilities)
3. **GPL v2.0 licenses** in dependencies
4. **SonarQube quality gate failure**
5. **Maven version < 3.9.9**

### Test Coverage
- JaCoCo generates coverage reports in `target/site/jacoco/`
- Coverage metrics sent to SonarQube

## CI/CD

### GitLab CI
Configuration in `.gitlab-ci.yml`

### SonarQube Integration
- **Project Key**: `dgreco_metacatalog_c3b4a273-3848-4c86-9b30-1ce0d66b807a`
- **Quality Gate**: Must pass before merge
- **Dashboard**: http://192.168.10.182:9000/dashboard?id=dgreco_metacatalog_...

## Configuration

### Application Properties
- Configuration properties defined in `CoreConfigProperties`
- Enabled via `@EnableConfigurationProperties`

### Spring Features Enabled
- Transaction Management (`@EnableTransactionManagement`)
- Scheduling (`@EnableScheduling`)
- Caching (Spring Cache abstraction)
- Retry (Spring Retry)
- Actuator (monitoring and management)

## API Documentation

### OpenAPI/Swagger
- Swagger UI available when application runs
- SpringDoc integration for live API documentation
- Generated API models in `metacatalog-openapi` module

### Generated Documentation
Auto-generated docs in:
```
metacatalog-openapi/target/generated-sources/openapi/docs/
```

## Advanced Features

### Custom Database Functions
The `metacatalog-functions` module contains custom PostgreSQL procedures:
- **AbstractEntityProcedure**: Base for custom procedures
- **EntityProcedure**: Entity-specific procedures
- **ProcedureExecutor**: Executes custom procedures

### JSON Support
- **WrappedJsonNode**: Wrapper for Jackson JsonNode
- **JsonUtils**: JSON manipulation utilities
- **JSON Schema validation** for entity attributes
- **JSONPath** querying capabilities

### Graph Operations
- Uses **JGraphT** for relationship graph analysis
- Supports complex entity relationship traversal

### Advisory Locks
- **AdvisoryLockManager**: PostgreSQL advisory lock management
- Prevents concurrent modification conflicts

## Common Issues & Solutions

### Build Issues
1. **Java version mismatch**: Ensure Java 24 is installed
2. **Maven version**: Must be ≥ 3.9.9
3. **Formatting failures**: Run `mvn spotless:apply`

### Database Issues
1. **Connection failures**: Check PostgreSQL is running on port 5432
2. **Migration failures**: Check Flyway migration scripts
3. **Test failures**: Ensure Docker is running (for Testcontainers)

### Dependency Issues
1. **Vulnerability warnings**: Review OWASP report in `target/dependency-check-report.html`
2. **License violations**: Check `suppressions/suppression.xml` for exemptions

## Development Workflow

1. **Feature branches**: Create from `main`
2. **Format code**: `mvn spotless:apply`
3. **Run tests**: `mvn verify`
4. **Check quality**: Review SonarQube dashboard
5. **Create PR**: Ensure CI passes

## Additional Resources

### Ontop Documentation
- Ontop CLI v5 required for ontology integration
- Mapping files: `src/main/resources/ontop/mapping.obda`
- Ontology: `src/main/resources/ontop/ontology.owl`

### Monitoring
- Spring Boot Actuator endpoints available
- Health checks, metrics, and info endpoints

## Notes for AI Assistants

- This is a **multi-module Maven project** with 4 modules
- Use **Java 23 syntax** when writing code
- Always run **spotless:apply** before committing
- **PostgreSQL-specific features** are used (advisory locks, JSONB)
- **Ontop integration** for semantic web/ontology-based data access
- Entity attributes stored as **JSON with schema validation**
- Heavy use of **Spring Data JPA** and **Spring Boot conventions**
- **Testcontainers** for integration testing - don't write manual DB setup
- **Vavr** functional programming - prefer functional patterns where used
- **Transaction management** is critical - respect `@Transactional` boundaries
