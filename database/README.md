# Database

The schema, owned by Flyway.

## Why the migrations live here

They used to sit in `common/src/main/resources/db/migration`, which put them
inside a dependency jar nested in each service's Spring Boot executable jar.
Flyway locates migrations by scanning the classpath, and scanning *inside* a
nested jar is exactly the case that has historically been unreliable.

Keeping them here, as plain files copied into each service that owns a
datasource, means they land at `BOOT-INF/classes/db/migration` — the top level
of that service's own classpath, which Flyway reads without any scanning
subtlety. One source of truth in the repository, one copy per service jar, and
no dependency on how a build tool packages a transitive resource.

The copy is done by `maven-resources-plugin` in `fraud-engine/pom.xml` and
`fraud-api/pom.xml`. Editing a file here changes both services.

## Adding a migration

Add `V<n>__description.sql`. Migrations are immutable once applied — Flyway
checksums them, and editing one that has already run fails validation on the
next startup, deliberately.
