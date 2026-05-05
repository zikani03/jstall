# Contributing to JStall

## Building and Testing

```bash
mvn clean package
```

### Test Configuration

JStall uses `RunCommandUtil` to execute commands during testing. The utility supports several configuration options via Maven system properties:

**Configuration Options:**

- `test.externalJar=<path/to/external.jar>`: Run tests against an external JAR file instead of the classpath. This is useful for testing the built artifact with all test cases.
  - Example: `mvn test -Dtest.externalJar=target/jstall.jar`

- `test.forceRunWithShell=[true|false]`: Force running commands with shell wrapping (`-s LOCAL_SHELL` option) to test remote execution paths. This exercises the shell-based execution code path.
  - Example: `mvn test -Dtest.forceRunWithShell=true`

**CI Testing Strategy:**

To ensure comprehensive test coverage across different execution modes, run the test suite multiple times:

1. **Standard tests** (direct classpath execution):
   ```bash
   mvn clean test
   ```

2. **External JAR tests** (built artifact):
   ```bash
   mvn clean package
   mvn test -Dtest.externalJar=target/jstall.jar
   ```

3. **Shell execution tests** (remote execution simulation):
   ```bash
   mvn clean test -Dtest.forceRunWithShell=true
   ```

### Release Process

[release.py](./release.py) is a helper script to create new releases. It supports testing with minimal/optimized builds,
using [femtojar](https://github.com/parttimenerd/femtojar).

```bash
./release.py build minimal
```

This creates a minimal JAR and runs the tests against it for validation:

```bash
mvn test -Dtest.externalJar=target/jstall-minimal.jar
```

[bin/sync-documentation.py](bin/sync-documentation.py) is used to synchronize the CLI help messages into README.md and [docs/COMMANDS.md](docs/COMMANDS.md). It looks for `<!-- BEGIN help_<command> -->` / `<!-- END help_<command> -->` markers in both files.

## Extending

Extend this tool by adding new analyzers. You can do this by implementing an analysis, creating a new command,
and adding it to the main CLI class (and adding the analysis optionally to the status command).
Please also update the README accordingly.
