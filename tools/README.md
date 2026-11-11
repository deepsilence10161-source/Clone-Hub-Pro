Local static gate (used before every push).

`python3 tools/precheck.py` checks Java sources in `app/src/main/java` for the failure
classes that actually reached CI in this project: Kotlin-only annotations inside Java
files, unbalanced braces/parentheses after a manual patch, invalid Android XML, and a
component referencing an undeclared permission.

It is intentionally narrow: the sandbox has no Android SDK or JDK, so compile, lint and
tests run in CI and their results are the only authority.
