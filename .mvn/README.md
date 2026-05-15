The `mvnw` and `mvnw.cmd` scripts are generated automatically by Maven.
Run this once after cloning to install the wrapper:

    mvn wrapper:wrapper -Dmaven=3.9.9

Or, if you have Maven globally, just use `mvn` directly throughout.
The `Makefile` targets call `./mvnw` which falls back to system Maven when
the wrapper is absent.
