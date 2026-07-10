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

The full type system is in. Refined types attach machine-checked predicates to a base type —
inline (`Int(0..100)`, `Text(1..120)`) or as named declarations, each a real nominal type:

```sky
type Iban          = Text matching /^[A-Z]{2}[0-9]{2}[A-Z0-9]{10,30}$/
type Percentage    = Int(0..100)
type PositiveMoney = Money where amount > 0
```

The special types cover the rest of the catalogue: `Bool` (`boolean`), `Money` (an exact
fixed-point amount plus currency — arithmetic requires matching currencies, money times money
does not exist), `Instant` (`java.time.Instant`), `Bytes` (an immutable content-equal wrapper),
`Email` (a `Text` that cannot be constructed from a non-address), `Maybe<T>`
(`java.util.Optional`; there is no null), `Secret<T>` (masked `toString`, no Faces getter,
never renderable in a view), and `List<T>`/`Map<K,V>`/`Set<T>` (`[T]` stays the list
shorthand). Every predicate is enforced where a value is constructed: entity compact
constructors, guarded service-method parameters, and converter/validator-backed view inputs
(`ask Money` renders with a staged `sky.money` Faces converter). See `examples/bank.sky`
for the whole surface in one module.

Entities and services carry their chapter-4 semantics. An `@id` entity compares by its
identity; an entity without one is a value type compared by contents; an enum-like entity
closes its instance set with `values Open, Frozen, Closed` and lowers to constants
(`Status.Open`). Fields take defaults (`active Bool = true`, `status Status = Status.Open`,
`createdAt Instant = now` on the pinnable clock), and constructors may omit the trailing
defaulted fields. `@unique` becomes a schema constraint when the entity is persisted.

Services declare an **effects budget**: `service Accounts uses db, clock { ... }`. Each
declared effect is an injected handle — `db` a typed store per identified entity (backed by
real JPA: a generated mapping class per entity, relations as `@ManyToOne`, component entities
as embeddables, EclipseLink + in-memory H2 as staged), `clock` a `java.time.Clock`, `mail`
and `http` small interfaces with a JDK-client `http` binding. An undeclared effect has no
handle, so no body can reach it, and a linter additionally fails the build if a synthesized
body touches raw platform APIs (`Instant.now()`, `java.net`, JDBC, files, processes) instead
of its handles. Generated tests substitute every effect — a fixed clock, a fresh in-memory
store per test — so verification stays deterministic and offline.

You write no test scaffolding: the example above compiles to `src/test/java/shop/CatalogTest.java`,
where the `example` becomes a `@Test` and the `ensures` rides along as an assertion inside it —
so a synthesized body is only accepted once both hold:

```java
@Test
void restock_example_1() {
    Catalog svc = new Catalog();
    var p = new Product(1L, "Notebook", 5L);
    var units = 3L;
    var result = svc.restock(p, units);
    assertTrue(eq((result).stock(), plus((p).stock(), units)), "ensures: eq((result).stock(), plus((p).stock(), units))");
    assertEquals(8L, result.stock(), "example: stock");
}
```

Contract operators lower through small overloaded helpers (`eq`, `lt`, `plus`, …) staged into
the test class, so the same `ensures` works for `long`, `String`, `Money`, and `Instant`
operands alike — javac's overload resolution picks the semantics.

Methods carry the full chapter-5 contract vocabulary. `raises Error when <condition>`
names failures: errors are entities lowered to exceptions carrying their context fields,
and conditions are formal expressions or the resolvable phrases (`no product has that id`,
`email already registered`) — unresolvable prose is a check error. Each raises with a
derivable witness becomes a generated test (an empty store for existence, a seeded
duplicate for uniqueness, a boundary value for comparisons); `requires` lowers to a guard
plus a boundary test. `ensures` gains `old(...)` (pre-call snapshots, including
`old(result.field)` read back from the store), `not`, `is`/`is not`/`is empty`, aggregates
(`sum of (p.stock for p in all products)`, `count of (... where ...)`, `max`/`min`), and
calls to effect-free helper methods.

Test-driven synthesis is native. A method may be driven by tests alone — `intent` stays the
only optional driver. `spec "title" { given ... when ... then ... }` blocks pin multi-step
scenarios: `given` constructs and seeds witness rows (equality pins; unpinned unique fields
get distinct samples; underivable fields must be pinned — the checker says which), `when`
performs the call, and `then` mixes `raises` with assertions that re-read stored state, so
"unchanged after rejection" works verbatim. Examples grow the same power: `example
restock(7, 3) on a Product with stock 5 -> stock 8` seeds a row and asserts result fields,
and `-> raises BadInput` asserts the failure. `sky tdd` watches the file and reruns the
red-green cycle on save, regenerating only the methods whose specification changed
(`--once` for scripts).

Policies state cross-cutting rules once and enforce them everywhere. `policy StrongPasswords
{ whenever a Password is constructed require length >= 12 and contains a symbol else raise
WeakPassword }` compiles into every construction site of the type — record constructors and
service parameter guards — raising the named error; `policy NoSecretsInLogs { whenever a
Secret is passed to a logger forbid }` joins the synthesis linter, failing any body where a
Secret read meets a logging call. Policies participate in every method's frozen spec, so
adding one re-verifies the module. The profile contributes its own policy in the same
spirit: a `Secret` can never appear in a rendered view (no Faces getter, no column, no
prompt). Unresolvable whenever-prose is a check error naming the two supported shapes.

Not yet implemented (deferred): `page`/`flow`, the dependency `requires` registry, further
whenever-forms (audited deletes, money conservation, layer boundaries), native `java { }`
blocks, property-based `ensures`, prose-form example arguments, a Jakarta Mail binding for
the `mail` effect, and persistence for `Map` fields and lists of identified entities.

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
`sky build --recheck` re-runs the staged verification — the tests, the in-container render
checks, and the visual gate that diffs each view against its frozen look — on a fully frozen
project. It never calls the model, so it runs offline and credential-free; use it in CI to
catch toolchain or rendering drift.

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
