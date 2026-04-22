# lacelang-validator (Kotlin)

Reference Kotlin/JVM validator for [Lace](https://github.com/tracedown/lacelang) --
parser and semantic checks with **100% spec conformance** (v0.9.0). Single
runtime dependency (Gson for JSON serialization).

Parsing and semantic validation only -- no HTTP runtime, no network
dependencies. See `lace-spec.md` section 14 for the validator / executor
package separation rule.

## Build

```bash
./gradlew build
```

## CLI

```bash
# Parse -- check syntax, emit AST
java -jar build/libs/lacelang-kt-validator-0.1.0-all.jar parse script.lace

# Validate -- check syntax + semantic rules
java -jar build/libs/lacelang-kt-validator-0.1.0-all.jar validate script.lace \
    --vars-list vars.json --context context.json
```

Both subcommands support `--pretty` for indented JSON.

## Library

```kotlin
import dev.lacelang.validator.parse
import dev.lacelang.validator.validate

val ast = parse("""get("https://example.com").expect(status: 200)""")
val sink = validate(ast, listOf("base_url"), mapOf("maxRedirects" to 10))
println(sink.errors)
println(sink.warnings)
```

## Responsible use

This software is designed for monitoring endpoints you **own or have
explicit authorization to probe**. See `NOTICE` for the full statement.

## License

Apache License 2.0
