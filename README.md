# ATM simulator

Java Swing ATM registration and login application.

## Project layout

```text
src/main/java/atm/
  App.java                 Application entry point
  db/Conn.java             JDBC connection configuration
  ui/                      Swing screens and shared UI constants
src/main/resources/icons/  Application images
database/schema.sql        Existing database schema
```

## Configuration

Set these environment variables in the shell or IDE run configuration before
starting the application:

| Variable | Required value |
| --- | --- |
| `DB_URL` | JDBC URL for the database created from `database/schema.sql` |
| `DB_USERNAME` | Database username |
| `DB_PASSWORD` | Database password |

All three values are required and must be non-blank. Keep credentials outside
source files. The application reports missing configuration by variable name.

## Compile and run

Use JDK 21 and the local MySQL connector JAR. From the project root on macOS/Linux:

```sh
JDBC_JAR="../../mysql-connector-j-9.7.0/mysql-connector-j-9.7.0.jar"
mkdir -p out
javac -cp "$JDBC_JAR" -d out src/main/java/atm/App.java src/main/java/atm/db/*.java src/main/java/atm/ui/*.java
cp -R src/main/resources/. out/
java -cp "out:$JDBC_JAR" atm.App
```

Adjust `JDBC_JAR` if the connector is installed elsewhere. In IntelliJ, the module
marks `src/main/java` as sources and `src/main/resources` as resources. Run
`atm.App` with the same environment variables and the connector on its classpath.
