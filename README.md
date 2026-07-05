# SkyLang compiler

A specification-language compiler: you declare types, signatures, and contracts (the **hard
layer**); a language model synthesizes each method body at **build time**; the compiler
**verifies** that body against your contracts and examples, then **freezes** the accepted
source into `sky.lock` so later builds are offline and deterministic. The model is never on
the runtime path — production is an ordinary artifact of the target platform.

See [`docs/skylang-language-guide.md`](docs/skylang-language-guide.md) for the language design.

This repository currently implements an **end-to-end thin slice**: a small grammar taken
through the whole pipeline (parse → type-check → synthesize → verify → freeze → emit), built
as a native `sky` binary with GraalVM. It is the skeleton the full language grows into.

## Pipeline

```
 .sky source
   │  ANTLR4 grammar (src/main/antlr4/.../SkyLang.g4) → AstBuilder
   ▼
 Frontend      parse + type-check the hard layer (no model)        → `sky check` stops here
   │
   ▼
 Synthesis     per method: reuse a frozen body if its spec hash matches,
   + Verify     else call the configured LLM (OpenAI or Anthropic, via LangChain4j),
                then stage build/<profile>/ and run its tests
   │            (green → freeze into sky.lock; red → regenerate, then report)
   ▼
 Backend       build/jvm-jakarta/ — a conventional Maven project you can open and keep
```

## What the slice accepts

```sky
module shop

entity Product {
  id    Int
  name  Text
  stock Int @min(0)
}

service Catalog {
  restock(p Product, units Int) -> Product
    intent   "Return a copy of the product with its stock increased by units."
    requires units > 0
    ensures  result.stock == p.stock + units
    example  restock(Product(1, "Notebook", 5), 3) -> a Product with stock 8
}
```

`Int` lowers to `long`, `Text` to `String`, and each `entity` to an immutable Java `record`
(with `@min` enforced in the compact constructor). `ensures` and `example` become JUnit tests
in the staged project; `intent` drives the model.

Not yet implemented (deferred): effects/`uses db` + JPA, `Secret`/`Maybe`/`policy`,
`page`/`flow`, the dependency `requires` registry, `spec` blocks, `raises`, `Money`, and
property-based `ensures`.

## Build & run

Requires **GraalVM for JDK 22** (for the native image) and Maven. On this machine GraalVM is
at `/home/chaozer/graalvm/graalvm-jdk-22.0.2+9.1`; point **both** `JAVA_HOME` and
`GRAALVM_HOME` at it (the native-maven-plugin prefers `GRAALVM_HOME`, so a stale one pointing
at an older GraalVM must be overridden).

```sh
# Unit tests (offline: uses a stub model + stub verifier)
JAVA_HOME=<graalvm> mvn test

# Runnable jar
JAVA_HOME=<graalvm> mvn -DskipTests package
<graalvm>/bin/java -jar target/compiler.jar onboard        # configure provider + key (once)
<graalvm>/bin/java -jar target/compiler.jar check examples/shop.sky

# Native binary → target/sky
GRAALVM_HOME=<graalvm> JAVA_HOME=<graalvm> mvn -Pnative -DskipTests package
./target/sky check examples/shop.sky
./target/sky build examples/shop.sky
```

`sky build` needs a JDK + Maven on PATH (to compile and test the staged project) and Claude
credentials + network for any method that is not already frozen. `sky check` needs neither.

## Credentials (build-time only)

The model connection goes through **LangChain4j**, so synthesis can run against **OpenAI or
Anthropic**. Configure it once with `sky onboard`:

```sh
sky onboard --provider anthropic --api-key sk-ant-...   # or: --provider openai --api-key sk-...
# or just `sky onboard` and answer the prompts
```

This validates the key with a small live call and writes `~/.sky/config` (mode `600`):

```
provider=anthropic
model=claude-opus-4-8
api_key=sk-ant-...
```

`sky build` resolves credentials in this order:

1. `~/.sky/config` (written by `sky onboard`) — provider + model + key.
2. Otherwise the environment: `ANTHROPIC_API_KEY` **or** `OPENAI_API_KEY` (setting both is an
   error — onboard or unset one); `SKY_MODEL` overrides the default model.

Both routes use pay-as-you-go API billing for the chosen provider. A Claude **Pro/Max
subscription does not cover** these calls — that billing is reserved for Anthropic's first-party
apps — so an Anthropic key needs API credits. Credentials are resolved lazily, so a fully-frozen
build needs none.

## Layout

| Path | What |
|------|------|
| `src/main/antlr4/.../SkyLang.g4` | the grammar (source of truth for the frontend) |
| `.../front/` | `AstBuilder`, parsing entry point, the AST |
| `.../types/` | the hard-layer type + contract checker (`sky check`) |
| `.../config/` | provider + `~/.sky/config` store (`sky onboard` writes it) |
| `.../synth/` | provider-neutral `Llm` + LangChain4j impl (OpenAI/Anthropic) + prompt builder |
| `.../verify/` | staged-project verifier (runs `mvn test`) |
| `.../freeze/` | `sky.lock` read/write + spec hashing |
| `.../backend/` | JVM profile: type/expression lowering + project stager |
| `.../Pipeline.java` | orchestrates synthesize → stage → verify → freeze |
| `.../cli/` | picocli commands (`check`, `build`) |
