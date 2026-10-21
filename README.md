# ATM simulator

Java Swing ATM registration and login application.

## Project layout

```text
src/main/java/atm/
  App.java                 Application entry point
  db/Conn.java             JDBC connection configuration
  dao/                     Registration and authentication SQL
  model/                   Plain Java data models
  ui/                      Swing screens and shared UI constants
src/test/java/atm/dao/      Headless DAO regression tests
src/main/resources/icons/  Application images
database/schema.sql        Existing database schema
database/migrations/       Changes for existing databases
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

## Database setup and upgrades

For a fresh installation, use `database/schema.sql`. For an existing installation
that lacks `signuptwo.isSenior`, apply
`database/migrations/001_add_is_senior.sql` once. Both define the same nullable
`VARCHAR(3)` column for the application's `Yes`/`No` values. Existing rows retain
their data and receive `NULL` for the previously unrecorded senior status.

For local MySQL, with `DB_USERNAME` configured, run the appropriate command from
the project root and enter the database password when prompted:

```sh
# Fresh installation
mysql -u "$DB_USERNAME" -p < database/schema.sql

# Existing installation missing isSenior (run once)
mysql -u "$DB_USERNAME" -p < database/migrations/001_add_is_senior.sql
```

Add your MySQL host and port options if using a remote server. Running the fresh
schema against existing tables does not upgrade them; use the migration instead.
Skip this migration if `isSenior` already exists, including on fresh installs.

## Compile and run

Use JDK 21 and the local MySQL connector JAR. From the project root on macOS/Linux:

```sh
JDBC_JAR="../../mysql-connector-j-9.7.0/mysql-connector-j-9.7.0.jar"
mkdir -p out
javac -cp "$JDBC_JAR" -d out src/main/java/atm/App.java src/main/java/atm/db/*.java src/main/java/atm/dao/*.java src/main/java/atm/model/*.java src/main/java/atm/ui/*.java
cp -R src/main/resources/. out/
java -cp "out:$JDBC_JAR" atm.App
```

Adjust `JDBC_JAR` if the connector is installed elsewhere. In IntelliJ, the module
marks `src/main/java` as sources and `src/main/resources` as resources. Run
`atm.App` with the same environment variables and the connector on its classpath.

## DAO tests

After compiling the application, run the headless checks below. They use a test
JDBC driver and dummy credentials; they do not connect to MySQL or open windows.

```sh
javac -cp out -d out src/test/java/atm/dao/DaoTest.java
DB_URL=jdbc:atm-dao-test DB_USERNAME=test-only-user DB_PASSWORD=test-only-password java -Djava.awt.headless=true -cp out atm.dao.DaoTest
```

`App` creates the two concrete DAOs and passes them through screen constructors.
DAOs own JDBC resources and return plain data; validation, dialogs and navigation
remain in the Swing screens.
