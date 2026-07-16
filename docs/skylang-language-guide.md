# SkyLang

*A specification language where you write the contract and the compiler writes the code.*

---

## 1. Why SkyLang exists

We have spent seventy years climbing a ladder of abstraction: machine code → assembly →
C → managed languages like Java. Each rung let us say *more* about *what* we want and *less*
about *how* the machine achieves it.

SkyLang is the next rung. You declare the **shape** of your program — its types, its
signatures, its guarantees — and you specify each behavior as **intent** (plain language),
as **tests** (concrete examples), or as both. A language model synthesizes the
implementation; the compiler proves that implementation against the tests and contracts you
wrote and then emits ordinary, verified code for your chosen target platform. Targets are
supplied by *profiles* (§10): the JVM / Jakarta profile is the reference, with TypeScript /
Node and Python profiles alongside it — the same specification retargets to each.

The model proposes. The compiler disposes. Code that cannot be proven against its
specification never ships.

> **One-line mental model:** The hard layer gives you the guardrails, the LLM writes the body, and your
> tests and contracts are the proof that the body is correct. Lead with prose, lead with
> tests, or both — your choice.

---

## 2. The two layers

Every declaration in SkyLang is made of two layers that sit side by side.

| Layer    | Who writes it | What it contains                                   | Enforced by      |
|----------|---------------|----------------------------------------------------|------------------|
| **Hard** | You           | Types, fields, signatures, contracts, policies     | The compiler     |
| **Soft** | You specify, the LLM realizes | `intent` prose and/or `example`/`spec` tests that say what a body should do | Verified against the hard layer |

The hard layer is deterministic, diffable, and reviewable like any source code. The soft
layer is where prose and tests buy you brevity — but it is never trusted blindly. It is
always checked against the hard layer before it becomes part of your program.

---

## 3. A first program

```sky
module shop

entity Product {
  id     Int       @id
  name   Text(1..120)
  price  Money
  stock  Int       @min(0)
}

service Catalog uses db {

  add(name Text, price Money) -> Product
    intent  "Create a product with zero initial stock and persist it."
    ensures result.stock == 0
            result.name  == name
    example add("Notebook", 9.99eur) -> a Product with stock 0

  restock(id Int, units Int) -> Product
    intent  "Increase the stock of the product by `units`."
    raises  NotFound  when no product has that id
    raises  BadInput  when units <= 0
    ensures result.stock == old(result.stock) + units
}
```

That is a complete, compilable program. You wrote four type lines and two contracts; the
bodies of `add` and `restock` are synthesized and then *verified* against the `ensures`
clauses and the `example`.

---

## 4. The type system

SkyLang's types are familiar types with sharper edges. Every type has fixed semantics and
compiles down to a real type on the target platform, but the compiler enforces invariants
the platform cannot express on its own.

### Primitive and refined types

```sky
Int            // 64-bit signed integer, identical on every target
Int(0..100)    // a refined int: compiler enforces the range at construction
Text           // a Unicode string
Text(1..120)   // Text with a length constraint
Money          // fixed-point decimal + currency, never a float
Email          // Text that must match an email shape
Instant        // a point in UTC time — something that happened
Date           // a calendar day: no time, no zone (course days, deadlines)
DateTime       // a wall-clock date and time with no zone (opening hours, local schedules)
```

A **refined type** is a base type plus a machine-checkable predicate. `Email` is not a naming
convention — it is a `Text` the compiler will not let you construct from `"nonsense"`. The
LLM generates the validation and marshalling code; the *declaration* states the rule.

### Special-behavior types

```sky
Secret<T>      // a value the compiler refuses to log, print, or serialize
Maybe<T>       // explicit absence; there is no null in SkyLang
Bytes          // an immutable sequence of raw bytes
List<T>, Map<K,V>, Set<T>
```

`Secret<Bytes>` for a password hash means any generated code that tries to put it in a log
line or a `toString` is a **compile error**, not a code-review catch.

### Defining your own refined type

```sky
type Slug = Text matching /^[a-z0-9-]+$/
type Percentage = Int(0..100)
```

How each type is represented on a target — its *lowering* — is supplied by the profile
(§10); the semantics above are fixed.

---

## 5. Entities and services

There are two kinds of class.

**`entity`** — data with identity and invariants. The active profile (§10) decides the
concrete class shape it compiles to. Fields use `name Type` order, defaults with `=`,
constraints with `@annotations`.

```sky
entity User {
  id        Int           @id
  email     Email         @unique
  password  Secret<Bytes>
  role      Role          = Role.Member
  createdAt Instant       = now
}
```

`@unique` may take a scope — `email Email @unique(provider)` keeps the email unique **per
value of the sibling `provider` field** instead of globally, so the same address may exist
under different providers. The scope must be a single reference: a field holding an
identified entity or a values entity. A persistence-backed profile enforces the pair as a
composite constraint on the stored table.

Temporal defaults read the pinnable clock: `= now` fills an `Instant` (or a `DateTime`)
at construction, and `= today` fills a `Date`. Neither keyword may appear in contracts —
they would break determinism; read the clock inside the body instead.

A parent may declare an **owned collection** of identified entities by naming the child's
back-reference: `permissions [Permission] @mappedBy(owner)`, where `Permission.owner` is a
plain reference to the parent. The children own the relationship — they are saved
individually and the parent's list is read back from the store, never written through it.
A child's back-reference reached *through* that list carries the parent with its own
collections empty (records are immutable, so a truly cyclic object graph cannot exist).
For many-to-many, an explicit join entity and service queries over `all …` remain the
idiomatic shape.

### Closed value sets

An entity whose body ends in a `values` clause is enum-like: the clause seeds and closes its
instance set. The first field is the sole `@id` **carrier** — a `Text` field the value's own
name fills. Each value becomes a constant (`Role.Member`), and constructing further instances
is rejected.

```sky
entity Role {
  name Text @id
  values Member, Admin
}
```

Data fields may follow the carrier; every value then **pins** each of them, exactly once,
with a constant — a literal or another declared value. The result is a total constant table
the checker verifies like any other typed expression:

```sky
entity Tier {
  name  Text @id
  label Text
  price Money
  values Free with label "Free" and price 0eur,
         Pro  with label "Pro"  and price 399sek
}
```

Pinned fields read like ordinary fields (`tier.price`). Because the set is closed and every
pin is a constant, data fields take no `= default`, and a field needs a constant surface
form to be pinnable: `Int`, `Text`, `Bool`, `Money`, or another values entity. Behaviour
stays in services — a rule that *derives* something from a value belongs in a service method
taking the value as an argument, not on the entity.

**`service`** — behavior. Holds no mutable state of its own; declares the **effects** it is
allowed to use (`uses db, http, clock, …`). Generated code may only touch declared effects —
a service `uses db` that tries to make an HTTP call will not compile.

```sky
service AccountService uses db, clock, mail {
  // methods go here
}
```

---

## 6. Methods: signature is hard, body is driven

A method is a signature you write plus a **body the compiler realizes**. What drives that body
is up to you — a method takes one or more **drivers**, and **at least one is required**:

- **`intent`** — prose describing what the body should do.
- **`example` / `spec`** — concrete tests the body must satisfy (see §6a).
- **native block** — a hand-written body in the profile's language, e.g. `java { … }`
  under the JVM profile (see §9).

`intent` is *not* mandatory. A method whose only driver is tests is a complete, valid method —
SkyLang generates a body until the tests pass. Around any driver you attach contracts
(`requires`/`ensures`/`raises`) that turn "trust me" into "prove it."

Here is the fully-specified, belt-and-suspenders form — intent *and* contracts *and* an example:

```sky
register(email Email, password Password) -> User
  intent  "Create a user. Reject if the email is already taken.
           Hash the password with bcrypt before storing it."
  raises  DuplicateEmail  when email already registered
  ensures result.email == email
          result.password is hashed
  example register("a@b.com", "hunter2longpw")
          -> a User whose email is "a@b.com"
             and whose password is not "hunter2longpw"
```

The clauses (`intent` is the only optional *driver*; pick at least one driver overall):

| Clause     | Role   | Meaning                                                   | Becomes            |
|------------|--------|-----------------------------------------------------------|--------------------|
| `intent`   | driver *(optional)* | Natural-language description of the body     | LLM prompt         |
| `example`  | driver | A concrete input → expected-output case                  | Unit test + few-shot anchor |
| `spec`     | driver | A `given/when/then` test for multi-step behavior (§6a)   | Unit test + few-shot anchor |
| `requires` | contract | Precondition on inputs                                  | Runtime guard + test |
| `ensures`  | contract | Postcondition on the result / state                    | Property test      |
| `raises`   | contract | Which exception is thrown under which condition         | Property test      |

`example`, `spec`, `ensures`, and `raises` all compile to the same tests — the only difference
is whether prose (`intent`) or tests drive the generation. `old(...)` inside `ensures` refers
to a value *before* the method ran, so you can specify state transitions
(`result.stock == old(result.stock) + units`).

When SkyLang synthesizes a body it runs every `example`/`spec` and checks every
`ensures`/`raises`. If any fail, the build does not silently accept bad code — it regenerates,
and if it still cannot satisfy the specification it stops and shows you which clause was
violated.

---

## 6a. Test-driven, when you want it (optional)

Because `intent` is optional, you can write a method as **nothing but its tests** and let the
compiler generate a body that turns them green. This is test-driven development with the LLM
doing the "make it pass" step: write the failing tests, run the build, get a verified body.

```sky
service Catalog uses db {

  // No intent. The tests ARE the specification.
  restock(id Int, units Int) -> Product
    example restock(7, 3) on a Product with stock 5 -> stock 8
    example restock(7, 0)   -> raises BadInput
    example restock(999, 1) -> raises NotFound
}
```

SkyLang generates `restock` until all three examples hold, freezes it, and you never wrote a
loop or a null check. The red → generate → green loop is native (`sky tdd`, §13).

An example result may also be free prose — `example decline(1) -> the payment is declined`.
Prose steers generation like an `intent`, but it verifies nothing beyond the call completing
without an error; prefer the `whose`/`with` forms, which become real assertions.

### Richer cases: the `spec` block

One-line `example`s are the lightweight form. When a behavior needs setup and several
assertions, reach for a `spec` block — a `given / when / then` shape familiar to anyone who
has written BDD tests. It compiles to exactly the same kind of test; it just reads better for
multi-step scenarios.

```sky
transfer(from Account, to Account, amount Money) -> Receipt
  spec "moves money atomically" {
    given from.balance == 100eur and to.balance == 0eur
    when  transfer(from, to, 30eur)
    then  from.balance == 70eur
          to.balance   == 30eur
  }
  spec "rejects overdraft" {
    given from.balance == 10eur
    when  transfer(from, to, 50eur)
    then  raises InsufficientFunds
          from.balance == 10eur        // unchanged
  }
```

Mix freely: a method can be tests-only, intent-only, or both. The **hybrid** form — a short
`intent` to point the generator in the right direction, plus a few `example`/`spec` cases to
pin the behavior down — is the recommended default for anything non-trivial. None of it is
mandatory; all of it is verified.

---

## 7. Behavior that is not a method: `policy`

Some rules are not attached to one method — they are global truths the generator must honor
*everywhere*. A `policy` is a cross-cutting contract.

```sky
policy StrongPasswords {
  whenever a Password is constructed
  require  length >= 12 and contains a symbol
  else     raise WeakPassword
}

policy NoSecretsInLogs {
  whenever a Secret is passed to a logger
  forbid
}
```

Policies are compiled into the verification pass. Every generated body in the module is
checked against every active policy, so a single declaration constrains the entire codebase.
Profiles may register additional policies of their own (§10) — a web profile can, for
example, forbid a `Secret` from ever reaching an HTTP response.

---

## 7a. The interface layer: views, components, and flows

The user interface is soft too. You do not write markup; you *declare* what a screen shows and
what it lets the user do, and the model renders it against the target profile — on the reference
JVM profile, Jakarta Faces/Facelets pages with typed backing beans. As with method bodies, nothing
is trusted on sight: the rendered view is verified against the interface **contracts** you write
(structure and behaviour, checked against the semantic tree rather than pixels), plus a **visual
gate** that freezes each view's look and fails the build if a later render drifts from it. Run
`sky preview` (§13) to see the whole interface live in a browser and watch each contract turn green.

**Pages and views.** A `page` (alias `view`) binds a route, queries a service, projects the result,
and offers actions:

```sky
page ProductList at "/products" {
  shows   Catalog.all() as a sortable table of (name, price, stock)
  action  "Restock" on a row -> Catalog.restock(row.id, prompt Quantity)

  expect  table has columns (name, price, stock)     // must hold in the render
  expect  action "Restock" is a button
  appears action "Restock" in the row's toolbar        // styling / placement intent
  appears columns in order (name, stock, price)
}
```

- `shows <Service.query()> as a [sortable] <table|summary|…> of (fields)` — the data and its shape.
  A `titled "…"` suffix names the section; a page may `shows` several sections. A `Bytes` field in
  a summary renders as an image: the compiler verifies both the markup binding (an
  `h:graphicImage` over the field's data-URI helper) and, at render, that the image element exists.
- `action "Label" on a row | on the order -> Service.method(row.id, ask T | prompt T)` — wires a
  control to a service method. `ask`/`prompt` marks an input the UI must collect (with the right
  converter for `Money`, `Instant`, `Date`, `DateTime`, refined types, …). The `on <subject>`
  clause and the arguments
  are both optional: `action "Sign out" -> Session.signOut()` is a page-level control bound to no
  row. On a page with several `shows`, a named subject (`on the order`) picks the section showing
  that entity.
- `action "Label" -> page <Name>` — navigates to another page of the module instead of calling a
  method. The target must be a declared page (`sky check` rejects an unknown name), navigation is
  page-level (no `on <subject>`), and the render is verified to carry a real navigation control
  whose outcome is the target page.
- `action "Label" -> flow <Name>` — enters a declared flow at its first step. The flow's first
  step must be bound to a page (`step Cart -> page CartPage`), and the render is verified to
  navigate there. Entering a flow from a page promotes the flow's `page <Name>` targets — in
  steps and transitions alike — from prose to checked references: each must name a declared page.
- `param <name> <Type>` declares a request parameter (Bool-, Int-, or Text-based, e.g. a `Slug`
  for the tenant) the page receives in its URL. Params type-check as `shows`/action arguments,
  and `appears <subject> when <condition>` renders a subject conditionally on them — verified by
  requesting the page with and without the parameter and asserting the element appears only when
  driven.
- `expect …` states what must be true of the rendered view — columns present, an action rendered as
  a button, an empty-state message shown — and is verified.
- `appears …` states styling, placement, ordering, and state (`is disabled when status is Shipped`,
  `rows is compact`, `a failed "Cancel" shows the error's message inline`) — it guides the render and
  is checked where checkable.

**Components** are reusable, parameterised pieces of interface:

```sky
component StockBadge(product Product) {
  shows   product.stock as a badge
  appears amber when product.stock <= 10
  appears brick when product.stock == 0
  expect  the badge shows the stock
}
```

A component can then be projected inside a page (`shows Catalog.lowStock(10) as a table of (name,
StockBadge)`). Which component library backs it is **dependency-driven**: with only the standard
library declared you get plain components; declaring PrimeFaces or OmniFaces (§11) lets the
generator render richer ones, without changing the specification.

**Flows** describe multi-step navigation with typed steps and guarded transitions:

```sky
flow Checkout {
  step Cart     -> collect items into a Cart
  step Shipping -> collect address
  step Pay      -> CheckoutService.place(the cart, the customer)

  on success       -> page OrderConfirmed
  on PaymentFailed -> back to step Pay with message

  expect step Pay is reachable only after Shipping
  expect no step follows success
}
```

The flow graph is verified against its `expect` clauses (no step skipped, no navigation after
success), so a generated navigation that violates the declared shape fails the build.

A page enters a flow with `action "Check out" -> flow Checkout`. Binding a step to a page
(`step Cart -> page CartPage`) is formal — the page must exist — and the first step's binding
is the flow's entry: where the entering action navigates to. An unentered flow may keep prose
`page …` transition targets; entering it makes every such target a checked reference.

The interface layer is **profile-supplied** (§10): a profile that does not target a UI simply omits
`page`/`component`/`flow`, and the same domain and behaviour retarget unchanged.

---

## 8. The freeze model: reproducible builds with an LLM in the loop

The obvious objection: *"If a language model writes the code, how is my build reproducible?"*

SkyLang answers with **freezing**.

1. **First build.** For each method, the compiler calls the model, verifies the candidate body
   against its contracts and the active policies, and — once it passes — writes the *accepted
   source* — in the active profile's language — plus a content hash into `sky.lock`.
2. **Every later build.** The compiler reads the frozen, already-verified body from `sky.lock`
   and hands it straight to the profile backend. **No model call. No network. Fully
   deterministic.**
3. **You change an `intent` or a contract.** Only that method's hash breaks, so only that one
   method regenerates. Everything else stays frozen.

```
$ sky build
  Catalog.add       ▸ frozen @ 3f9ac2   ✓ 2 contracts  ✓ 1 example
  Catalog.restock   ▸ regenerated        ✓ 3 contracts          (intent changed)
  AccountService.register ▸ frozen @ a17be0 ✓ 4 contracts ✓ 1 example
```

`sky.lock` is committed to version control. The generated code inside it is **readable and
reviewable** — a pull request shows exactly what changed in the synthesized body, line by
line, just like any other diff. CI builds are offline and identical for everyone.

A build targets exactly one profile, and the profile's id and version are recorded in the
`sky.lock` header, together with the resolved native coordinates of every declared
dependency (§11). Switching profiles invalidates every freeze, so the lock is regenerated
wholesale — a native block in the old profile's language is a hard error under the new one.
Building one module for several profiles at once is out of scope for this sketch, though a
module free of native blocks could in principle freeze per profile.

---

## 9. The escape hatch: hand-written bodies

SkyLang never traps you. Any method can drop to a hand-written body when intent is the wrong
tool — performance-critical inner loops, gnarly bit manipulation, or code you simply want to
own. The construct is the **native block**: every profile registers exactly one keyword
naming its language, and the block's body is written in that language and compiled by the
profile's backend. Under the JVM profile the keyword is `java`:

```sky
hash(input Bytes) -> Bytes
  ensures result.length == 32
  java {
    var md = java.security.MessageDigest.getInstance("SHA-256");
    return md.digest(input);
  }
```

A native block is compiled directly — no model involvement — but it is **still checked
against its contracts**. The verification pillar applies to human code and synthesized code
alike. You can mix intent methods and native-block methods freely in the same service.

The keyword names the language on purpose: a module is portable across profiles exactly
when it contains no native block, and that property is visible at a glance — or a grep.

---

## 10. Target profiles

The language core — everything in §§1–9 — is defined without committing to a concrete
platform. A **profile** is what binds the core to one: it supplies the lowerings, the
frameworks, and the backend, while the semantics stay fixed. A build activates exactly one
profile, declared in the project manifest (§11).

### 10.1 What a profile supplies

| A profile must supply                                                    | SkyLang core guarantees                          |
|--------------------------------------------------------------------------|--------------------------------------------------|
| A **lowering** for every core type, preserving its semantics             | 64-bit `Int`, no null, fixed-point `Money`, …    |
| A validation strategy for **refined types**                              | The predicate itself and where it is enforced    |
| A **binding** for each core effect it supports (`db`, `http`, `clock`, …)| Unsupported effects are a compile error to `use` |
| A **dependency registry** mapping logical names + versions to native packages | The `requires` syntax and resolution rules (§11) |
| Enforcement of **`Secret`** non-leakage and **`Maybe`** null-freedom     | The rules themselves                             |
| Exactly one **native-block keyword** and its language                    | The construct and its contract checking (§9)     |
| The **freeze language** stored in `sky.lock`, canonically formatted      | The freeze model and hashing (§8)                |
| A **project layout and backend toolchain** — how the staged native project (§12) is materialized and built | Nothing unverified reaches the artifact          |
| A **verification harness** that runs contracts, tests, and policies — a test run inside the staged project | What must be verified, and when                  |
| *(optional)* **Framework constructs** — stdlib extensions like `page`    | The two-layer rule applies to them too           |
| *(optional)* **Additional policies**                                     | The policy machinery (§7)                        |

### 10.2 The JVM / Jakarta profile (reference)

SkyLang's first target is enterprise web on the JVM. This is the reference profile — the
one the rest of this guide's examples assume.

Lowerings:

| Core type      | JVM lowering                             |
|----------------|-------------------------------------------|
| `Int`          | `long`                                    |
| `Text`         | `String`                                  |
| `Money`        | `BigDecimal` + `Currency`                 |
| `Instant`      | `java.time.Instant`                       |
| `Date`         | `java.time.LocalDate`                     |
| `DateTime`     | `java.time.LocalDateTime`                 |
| `Bytes`        | `byte[]`                                  |
| `Maybe<T>`     | a null-free wrapper; `null` never escapes |
| `Secret<T>`    | a compiler-tracked wrapper type           |
| refined types  | the base lowering + generated validation  |

The native-block keyword is `java`; `sky.lock` stores frozen bodies as Java source; the
backend stages a standard Maven project under `build/jvm-jakarta/` and delegates to it
(javac under the hood). Effect bindings: `uses db` injects the JPA
persistence layer, `clock` a controllable time source, `mail`/`http` the matching Jakarta
facilities. The dependency registry maps logical names onto Maven artifacts — `bcrypt ^4.0`
resolves to `org.mindrot:jbcrypt` at a pinned version.

The standard library adds declarative constructs that compile onto the Jakarta stack:

```sky
page ProductList at "/products" {
  shows   Catalog.all() as a sortable table of (name, price, stock)
  action  "Restock" on a row -> Catalog.restock(row.id, prompt Int)
}

flow Checkout {
  step Cart     -> collect items
  step Shipping -> collect address
  step Pay      -> charge via Payments
  on success    -> page OrderConfirmed
  on PaymentFailed -> back to step Pay with message
}
```

- `entity` → JPA entity.
- `service` → CDI bean.
- `page` → a Faces/Facelets view plus its backing bean.
- `flow` → a guarded navigation flow with typed steps.
- `auth` → an injected `Auth` handle whose one capability is
  `currentPrincipal() -> Maybe<Principal>` (subject, email, display name). Verification pins a
  **present** principal — generated tests through `TestEffects.auth()`, the render lane through
  a system-property seed — so a page whose controls depend on the signed-in state is verifiable.
  The production binding (e.g. OpenID Connect) replaces only the effect's producer and is not
  yet shipped; one limitation of the present-by-default test principal: a pure-auth method with
  a `-> nothing` example cannot pass, since nothing makes the principal absent per-example.

The same two-layer rule holds: you declare the shape and the guarantees; the generator fills
the wiring; the contracts verify it.

### 10.3 The TypeScript / Node profile

The same core lowered onto modern TypeScript for a Node runtime. Every type, effect, and
contract retargets unchanged; only the framework constructs (`page`/`flow`) are absent,
since those stay optional per profile.

| Core type      | TypeScript lowering                      |
|----------------|-------------------------------------------|
| `Int`          | `bigint`                                  |
| `Text`         | `string`                                  |
| `Money`        | a fixed-point decimal class + currency    |
| `Instant`      | `Temporal.Instant` (not yet lowered)      |
| `Date`         | `Temporal.PlainDate` (not yet lowered)    |
| `DateTime`     | `Temporal.PlainDateTime` (not yet lowered)|
| `Bytes`        | `Uint8Array`                              |
| `Maybe<T>`     | a tagged union; `null`/`undefined` never escape |

The native-block keyword is `ts`:

```sky
hash(input Bytes) -> Bytes
  ensures result.length == 32
  ts {
    return new Uint8Array(crypto.createHash("sha256").update(input).digest());
  }
```

- `sky.lock` stores frozen bodies as TypeScript source.
- The backend stages a `package.json` + `tsconfig` project and delegates to tsc/esbuild
  for a Node artifact.
- The same `requires` block resolves through this profile's registry — `bcrypt` becomes
  the npm `bcrypt` package at a pinned version.
- `uses db` binds to a database client; effects the profile does not bind are a compile
  error to declare.
- The verification harness compiles each `example` and `ensures` clause into a Vitest test
  and runs the suite inside the staged project.
- No `page`/`flow` here — framework constructs are optional per profile.

### 10.4 The Python profile

The same core lowered onto typed Python 3 for a CPython runtime. Like the Node profile it
carries the full type, effect, and contract semantics but no `page`/`flow` constructs.

| Core type      | Python lowering                          |
|----------------|-------------------------------------------|
| `Int`          | `int` (arbitrary precision)               |
| `Text`         | `str`                                     |
| `Money`        | `Decimal` + a currency tag                |
| `Instant`      | timezone-aware `datetime` (UTC)           |
| `Bytes`        | `bytes`                                   |
| `Maybe<T>`     | `T \| None`; a bare `None` never escapes  |
| `Secret<T>`    | a wrapper kept out of `repr` and logs     |

The native-block keyword is `py`:

```sky
hash(input Bytes) -> Bytes
  ensures result.length == 32
  py {
    return hashlib.sha256(input).digest()
  }
```

- `sky.lock` stores frozen bodies as Python source, canonically formatted with black.
- The backend stages a `pyproject.toml` project and delegates to the CPython toolchain;
  the emitted type hints are checked under mypy.
- The dependency registry maps logical names onto PyPI packages — `bcrypt ^4.0` resolves to
  the PyPI `bcrypt` package at a pinned version.
- `uses db` binds to a database client; effects the profile does not bind are a compile
  error to declare.
- The verification harness compiles each `example` and `ensures` clause into a pytest test
  and runs the suite inside the staged project.
- No `page`/`flow` here — framework constructs are optional per profile.

---

## 11. The project manifest: profile and dependencies

A build unit is described by a small manifest, `sky.project`, next to the `.sky` sources.
It names the project, activates exactly one profile (§10), and declares the project's
dependencies.

```
// sky.project
project shop
profile jvm-jakarta

requires {
  bcrypt      ^4.0
  http-client ~2.1
}
```

A dependency is a **logical name** plus a **version constraint** — `^4.0` means any
compatible 4.x, `~2.1` allows patch-level updates, and a bare `2.1.3` pins exactly.
Neither the name nor the version belongs to any native ecosystem: logical names and their
version lines are defined by the profile's **dependency registry**, which maps each
logical name and version onto native coordinates and a pinned native version for that
target. Requiring a name the active profile's registry does not map is a compile error.

Resolution happens at build time, and the resolved native coordinates are recorded in
`sky.lock` alongside the frozen bodies — later builds stay offline and deterministic.
Like effects (§5), dependencies are a declared budget: synthesized bodies and native
blocks alike may only use what `requires` lists, and because resolution is registry-only,
even a native block cannot pull in an arbitrary native package.

Retargeting is the payoff: switch the `profile` line — `jvm-jakarta`, `ts-node`, or
`python` — and the same `requires` block resolves to that profile's packages. Nothing else
in `sky.project` changes.

---

## 12. The compilation pipeline

```
 .sky sources
     │
     ▼
 ┌───────────┐   parse + type-check the HARD layer (no model involved)
 │  Frontend │   refined-type predicates, effect sets, contract well-formedness
 └─────┬─────┘
       │  typed AST + per-method contracts + active policies
       ▼
 ┌───────────┐   for each method body:
 │ Synthesis │     • cache hit in sky.lock?  → reuse frozen body
 │  + Verify │     • else call model, then VERIFY against contracts + policies
 └─────┬─────┘            • pass → freeze (write source + hash to sky.lock)
       │                  • fail → regenerate, then report the violated clause
       ▼
 ┌───────────┐   materialize build/<profile>/ as a conventional native project,
 │  Backend  │   then delegate to the native toolchain
 └─────┬─────┘   (JVM profile: Maven layout + pom.xml → .jar)
       ▼
   target artifact — runs on the profile's standard runtime, no model at runtime
```

### The build directory

The backend does not compile anything itself. It **materializes `build/<profile-id>/` as a
complete, conventional native project** and delegates to the profile's standard toolchain:

- the native build manifest, generated from `sky.project` and the resolved dependency
  coordinates in `sky.lock`;
- the hard layer lowered to native sources, with the frozen bodies from `sky.lock`
  spliced into their methods;
- every `example`/`spec`/`ensures` materialized as ordinary native tests — the
  verification harness (§10.1) is just a test run inside this project.

Under the JVM profile:

```
build/jvm-jakarta/
├── pom.xml                 ← dependencies from sky.lock
├── src/main/java/shop/
│   ├── Product.java        ← hard layer, lowered
│   └── Catalog.java        ← frozen bodies spliced in
└── src/test/java/shop/
    └── CatalogTest.java    ← examples/specs/ensures as tests
```

The directory is a disposable artifact: regenerated deterministically from the `.sky`
sources and `sky.lock`, never hand-edited (edits are overwritten on the next build), safe
to delete, and kept out of version control. Because it is an ordinary project, you can
open it in any IDE to step through generated code — and if you ever leave SkyLang, the
staged project is real code you get to keep.

#### The generated tests

You never write test scaffolding: the suite is derived mechanically from the contracts and
examples. Each `example` becomes one `@Test` that binds the call arguments, invokes the
method, and asserts the expected result; every `ensures` clause is emitted as an assertion
inside that same test — so a body only turns green when it satisfies both the example and the
contract. Given the `Catalog` service:

```sky
service Catalog {
  restock(p Product, units Int) -> Product
    intent   "Increase the product's stock by units."
    requires units > 0
    ensures  result.stock == p.stock + units
    example  restock(Product(1, "Notebook", 5), 3) -> a Product with stock 8
}
```

the backend writes `src/test/java/shop/CatalogTest.java` verbatim:

```java
package shop;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CatalogTest {

    @Test
    void restock_example_1() {
        Catalog svc = new Catalog();
        var p = new Product(1L, "Notebook", 5L);
        var units = 3L;
        var result = svc.restock(p, units);
        assertTrue(((result).stock() == ((p).stock() + units)), "ensures: ((result).stock() == ((p).stock() + units))");
        assertEquals(8L, result.stock(), "example: stock");
    }
}
```

A method with three examples yields `restock_example_1` through `restock_example_3`, each
carrying the same `ensures` assertions. This generated class *is* the verification harness:
`sky build` (and `sky test`) run exactly these tests against every synthesized body, and they
ship inside the emitted project for you to keep and re-run.

Two invariants hold the design together:

- **The model is never on the runtime path.** It runs at build time only; production is a
  plain artifact of the target platform.
- **Nothing reaches the artifact without passing its contracts.** Synthesized or hand-written,
  every body is verified the same way.

---

## 13. Tooling sketch

| Command             | Does                                                                    | Needs a model? |
|---------------------|-------------------------------------------------------------------------|----------------|
| `sky onboard`       | Configure the provider, model, reasoning effort, and key; writes `~/.sky/config` | no |
| `sky check`         | Hard-layer type + contract check only; no synthesis (fast, offline)     | no |
| `sky build`         | Type-check, synthesize/verify unfrozen bodies, emit the target artifact | only on a cache miss |
| `sky run`           | Build, then serve the emitted artifact in a browser (default port 8080) | only on a cache miss |
| `sky preview`       | Serve the module's views live in a browser studio (default port 4599)   | no |
| `sky tdd`           | Watch mode: on a new/edited failing test, regenerate just that method until its tests + contracts are green, then freeze — the red→generate→green loop made native | yes |
| `sky freeze`        | Force-regenerate and re-verify all bodies, rewriting `sky.lock`         | yes |
| `sky clean`         | Delete the build directory; the next build re-materializes it from sources + `sky.lock` | no |
| `sky why <m>`       | Show the intent, contracts, frozen body, and verification report for a method | no |
| `sky test`          | Run all `example`/`spec` cases and `ensures` properties as a test suite | no |

Every command answers `--help` (`-h`). `sky check` is the fast inner-loop command — it validates
everything in the hard layer (types, refinements, effects, contract well-formedness) without
touching the model, so editing signatures stays instant. A model is contacted only when a body
genuinely has to be generated; everything that reads the sources or the frozen lock is offline.

`sky run` and `sky preview` answer different questions. Preview serves the views inside a studio,
where they can be reshaped in natural language. Run brings up the application itself: the artifact
`sky build` emits, deployed to a container and opened at its front door — the module's first
declared view. Saving the source rebuilds and relaunches it; a rebuild that fails leaves the
running application alone. `sky run --skip-build` serves the artifact already in the build
directory, which is fast but may be stale.

The active profile is declared in `sky.project` (§11) and shown in the `sky build` output.

### 13.1 Configuration and credentials

`sky onboard` prompts for the provider (`anthropic`/`openai`), the model (default shown),
the reasoning effort, and the key, validates the key with a small live call, and writes
`~/.sky/config` (mode `600`). Re-running it edits an existing config — the current values come
back as defaults and the key is kept on a blank line — or change one thing in place with
`sky onboard --model NAME` / `--reasoning-effort VALUE`, reusing the stored provider and key.

**Reasoning effort** is a free value, not a fixed set: `low`/`medium`/`high`, OpenAI's `minimal`,
a model-specific tier, or a bare Anthropic thinking budget. It drives OpenAI's `reasoning_effort`
and Anthropic's extended-thinking budget, and the provider decides what it accepts. Onboarding
defaults it to `high`.

**Resolution is per key**: the configuration file wins for any key it defines, and the environment
fills only the gaps (`ANTHROPIC_API_KEY` / `OPENAI_API_KEY`, `SKY_MODEL`, `SKY_REASONING_EFFORT`) —
so removing a key from the file lets its variable take effect. `sky onboard --show` prints each
resolved key with the source it came from, and flags a variable the file silently overrides.

### 13.2 Exit codes

Commands report the kind of failure through the exit code, so a CI job can act on it:

| Exit | Meaning                                                                        |
|------|-------------------------------------------------------------------------------|
| `0`  | success — the command completed and all checks passed                          |
| `1`  | a hard-layer error (type, contract well-formedness, effect, dependency)        |
| `2`  | a verification failure — a body could not satisfy its contracts/examples/policies |
| `3`  | a configuration or provider error — generation could not reach a model         |

---

## 14. Design principles

1. **Types are the contract; prose and tests are the convenience.** Never the other way around.
2. **The compiler trusts nothing it cannot verify** — including its own generated code.
3. **The model lives at build time, never at runtime.** Production is an ordinary artifact
   of the target platform.
4. **Determinism by freezing.** A green build today is byte-identical tomorrow.
5. **Tests can drive generation.** Nothing about *how* you specify a body is mandatory —
   intent, tests, or a hand-written native block — but everything that reaches the artifact
   is verified the same way.
6. **Always an escape hatch.** Any body can become a hand-written native block without
   leaving the language.
7. **No null.** Absence is `Maybe<T>`; invariants live in refined types.
8. **The core is target-independent.** Profiles supply the lowering, the frameworks, and
   the backend — never the semantics.
