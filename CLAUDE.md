# Generating examples

Each example is a standalone, runnable teaching project. When creating a new one, follow
[`hello-hardwood/`](hello-hardwood) as the reference for setup and structure — copy it and adapt:

- **Maven.** A `pom.xml` that consumes Hardwood as a released dependency via the `hardwood-bom`,
  plus the bundled Maven wrapper. Always run `./mvnw` (never a system `mvn`). Build and run with
  `./mvnw -q compile exec:java`, and apply a 180-second timeout to detect deadlocks early. Examples
  consume released Hardwood; they do not build Hardwood itself.
- **Docker.** A `Dockerfile` and `docker-compose.yaml` that run the example once and clean up —
  `docker compose run --rm --build <service>`, so no stopped container is left behind.
- **Data.** Download any sample data per-example on first run (see `Datasets.java`) into a
  gitignored `data/` folder, and reuse it across runs.
- **README.** The front door for the example, with "What you'll learn", "Run it", "Expected
  output", and "How it works" sections. Keep it in sync with the code: if you change what
  `Main.java` does, those sections must reflect the new behavior. Describe what the code does and
  how to use it, not the development process or rejected alternatives.
- **License headers.** Every source file carries the Apache header from `etc/license.txt`. The
  `license-maven-plugin` checks this during `./mvnw compile` and fails the build on a missing or
  malformed header; run `./mvnw license:format` to apply it. Copying `hello-hardwood/` carries the
  plugin config and header template along.

CI builds every example automatically: the [`build`](.github/workflows/build.yml) workflow
discovers each top-level directory with a `pom.xml`, so a new example needs no workflow change.

# Coding

Correctness is the top priority. Adhere to "fail early": validate inputs, check types, and throw on
misuse rather than silently producing wrong results — silent failures are never an option. Never do
unsafe downcasts with potential value loss (prefer `Math.toIntExact()` where applicable). Avoid
`var`. Avoid fully-qualified class names — always add imports. Keep cyclomatic complexity low. All
JavaDoc must use Markdown `///` syntax (JEP 467), not the legacy `/** */` block style.

Keep each example short, linear, and readable — it exists to teach. Favor clarity over cleverness,
and prefer comments that explain *why* over *what*.

# Commits

Focus commit message bodies on **why**, not **what** — the diff already shows the what. A short
paragraph is usually enough; do not restate the change as a bullet list. Drop ephemeral minutiae:
slips caught and fixed within the same branch, transient process issues, or commentary about how the
change was developed rather than what it does.

Never add Claude (or any Anthropic identity) as a `Co-Authored-By` trailer. Attribute authorship to
the people responsible for the change, not the tooling. Human co-authors are fine.

If a build fails — even for reasons that seem unrelated to your change — still make an effort to fix
it rather than treating it as "not my department."
