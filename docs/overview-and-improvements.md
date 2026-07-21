# Technical Overview & Improvements

A tour of the Order Router's design, the decisions behind it, the trade-offs
made under a 4-hour take-home budget, and where it could go next — including a
worked sketch of an optimal (ILP-based) routing engine and config-driven
scoring.

For the component-by-component reference and extension points, see
[ARCHITECTURE.md](ARCHITECTURE.md); for run/build instructions see
[../README.md](../README.md).

---

## 1. What it is

A stateless Spring Boot service that routes multi-item medical-equipment orders
to suppliers, balancing four competing concerns — feasibility, shipment
consolidation, supplier quality, and local-vs-mail-order preference. It exposes
one JSON endpoint, `POST /api/route`, plus a Thymeleaf/Bootstrap console for
uploading orders and inspecting results.

## 2. Stack

- **Kotlin 2.1 + Spring Boot 3.4**, Gradle (Kotlin DSL), Java 21 toolchain
- **Thymeleaf + Bootstrap** (bundled locally, no CDN) for the test console
- **Multi-stage Docker** (JDK 21 build → JRE 21 runtime, non-root) + docker-compose
- No database, no external services — reference data loads into memory at startup

## 3. Architecture

Clean layering, each part with one job:

| Layer | Files | Role |
|---|---|---|
| Model | `Product`, `Supplier`, `ZipCoverage`, `dto.kt` | Domain + wire types; normalize messy data once |
| Repository | `ProductCatalog`, `SupplierDirectory` | Load CSVs into in-memory lookups + a category index |
| Service | `RoutingService`, `Csv` | The routing engine + a dependency-free CSV parser |
| Web | `RouteController`, `WebController`, `ApiExceptionHandler` | JSON API, console, always-200 contract |

---

## 4. Key design decisions (and why)

**In-memory data, no DB.** The dataset is ~1,200 products / 1,100 suppliers,
read-only. Loading it into maps at startup keeps the whole thing
dependency-free and sub-millisecond per request. The data locations are
configurable (`router.data.*` as Spring resource locations), so swapping to a
DB later means changing only the two repository classes — nothing downstream
depends on the source.

**Normalize messy data at the boundary, once.** The source is deliberately
messy: a misspelled `suplier_name` header, ZIP coverage as both lists *and*
ranges (`10001-10100`), `"no ratings yet"` scores, and mixed category casing
(`CPAP` vs `cpap`). Rather than special-casing this in the routing logic, each
quirk is absorbed at load time — a shared `normalizeCategory()`,
`ZipCoverage.parse()` that skips bad tokens, typo-tolerant header lookup. The
hot path then does pure comparisons.

**Greedy set-cover for consolidation.** The requirement priority is
*feasibility → fewest shipments → quality → geography*. That is encoded
literally: repeatedly assign the remaining items to the single supplier that
covers the most of them, breaking ties by satisfaction score, then
local-over-mail. The tie-break order lives in one `Comparator`, so the objective
is in exactly one readable place.

**Always HTTP 200.** The spec mandates it, so `feasible` (not the status code)
signals success. `ApiExceptionHandler` upholds this even for malformed JSON or
unexpected errors (which it logs, so failures aren't swallowed silently).

**Test console calls the *real* API.** The page parses orders (JSON or CSV) and
POSTs each to `/api/route` from the browser, so it genuinely exercises the
endpoint rather than a mock — and doubles as living documentation with a
copy-able curl.

---

## 5. Trade-offs (it's a 4-hour take-home)

- **Greedy, not optimal.** Greedy set-cover can miss the provably-minimum
  shipment count in pathological cases. For real orders (a handful of items)
  it's effectively optimal and far more explainable than a solver.
- **`priority` and capacity aren't modeled.** `priority` (rush/standard) is
  accepted but unused — there's no SLA data to act on. Suppliers are assumed to
  have unlimited stock (no inventory data provided).
- **Unrated suppliers rank lowest** (treated as score 0.0). Defensible, but
  arguably an unrated new supplier shouldn't be penalized as hard as a genuinely
  0-rated one.

---

## 6. Rooms for improvement

1. **Optimal routing** — swap greedy for an ILP/branch-and-bound engine if
   minimal shipments must be guaranteed (see §7); the `Comparator` is the single
   seam to change.
2. **Tunable weights** — externalize "how much is a rating point worth vs. a
   local shipment" into config instead of a fixed tie-break order (see §8).
3. **Capacity & partial fulfillment** — split a line item's quantity across
   suppliers when one can't cover it all; needs inventory data.
4. **Type-safety on the wire** — `fulfillment_mode` is a raw `String`; a
   `FulfillmentMode` enum would centralize the two literals (left out
   deliberately as over-engineering at this size — the JS badge still needs the
   strings regardless).
5. **Validation via types** — `route()` re-asserts non-null with `!!` after
   `validate()`; a "parse, don't validate" step returning a proven-valid value
   would remove that implicit coupling.
6. **Observability & hardening** — add Actuator (health/metrics), rate limiting,
   and request/response logging before anything production-facing.
7. **Data refresh without restart** — an admin endpoint or scheduled reload to
   rebuild the in-memory maps.
8. **Richer console** — batch summary stats, downloadable results, side-by-side
   alternatives for a given order.

---

## 7. Deep dive: optimal routing with an ILP

### 7.1 What is an ILP?

**ILP = Integer Linear Program.** It's a way to state an optimization problem in
math so a general-purpose solver can find the *provably best* answer, instead of
hand-writing the logic. Three parts:

1. **Decision variables** — the choices, as numbers; in an *integer* program
   they're whole numbers, often just **0 or 1** ("no/yes"). E.g. `x = 1` means
   "assign this item to this supplier."
2. **Constraints** — rules the answer must obey, written as *linear*
   inequalities (variables added/scaled by constants; no `x·y`, no curves). E.g.
   "every item goes to exactly one supplier."
3. **Objective** — a single linear expression to **minimize or maximize**. E.g.
   "minimize the number of shipments."

You hand those to a **solver** (a library like OR-Tools); it searches all valid
combinations and returns the optimum. You describe *what* a good answer looks
like; the solver figures out *how* to find it. "Linear" keeps it fast to solve;
"integer" makes the decisions discrete (assign or don't), which is what makes it
expressive enough for routing — and why solvers use branch-and-bound search.

**Why it matters here — a case greedy can miss.** Order of items {A, B, C}:

- Supplier 1 handles {A, B}
- Supplier 2 handles {B, C}
- Supplier 3 handles {A, C}

No single supplier covers all three, so the minimum is 2 shipments — but greedy
that grabs "most coverage first" can pick Supplier 1, then need a second
supplier for C, and depending on overlaps may settle for a worse combination
than the true optimum. An ILP evaluates the whole assignment at once and never
misses the minimum.

For this project's data (a few items, a few eligible suppliers) greedy is
effectively optimal and much simpler — which is why it's the right call here.
ILP is the "guaranteed-minimal routing at scale" upgrade.

### 7.2 Formulation

**Sets**

- `I` — line items in the order (each has a category `cat(i)`)
- `S` — *eligible* suppliers only: those handling ≥1 requested category and
  reachable (serve the ZIP, or ship nationally when `mail_order`). This is
  exactly what `SupplierDirectory.handling(category)` already returns, so `S`
  stays tiny (usually < 20, not 1,100).

**Parameters**

- `a[i,s] ∈ {0,1}` — 1 if supplier `s` can fulfill item `i` (`s` handles
  `cat(i)` **and** is reachable)
- `qual[s] ∈ [0,10]` — satisfaction (unrated → `unratedScore`)
- `local[s] ∈ {0,1}` — 1 if `s` serves the customer's ZIP (else mail-order)

**Decision variables**

- `x[i,s] ∈ {0,1}` — item `i` assigned to supplier `s` (only created where
  `a[i,s] = 1` → a sparse model)
- `y[s] ∈ {0,1}` — supplier `s` is used (produces a shipment)

**Constraints**

```
(1) assignment:   Σ_s x[i,s] = 1            ∀ i     # every item placed exactly once
(2) feasibility:  x[i,s] ≤ a[i,s]           ∀ i,s   # only where the supplier can fulfill
(3) linking:      y[s] ≥ x[i,s]             ∀ i,s   # using any item ⇒ supplier is "open"
(4) integrality:  x, y ∈ {0,1}
```

Constraint (1) makes the model **infeasible** exactly when some item has no
eligible supplier — so check `Σ_s a[i,·] = 0` *before* solving, report those
items as unroutable (the existing "partial routing" path), then solve over the
rest.

**Objective** (minimize):

```
minimize   W_ship · Σ_s y[s]                       # fewer shipments
         − W_qual · Σ_{i,s} qual[s] · x[i,s]        # higher quality
         − W_local · Σ_{i,s} local[s] · x[i,s]      # prefer local
```

### 7.3 Strict priority vs. soft trade-off

The current requirement is **strict priority** (shipments always beat quality,
which always beats geography). Two ways to get that from the model:

- **Dominating weights** — choose gaps so a lower tier can never overturn a
  higher one. With `n` items and `qual ≤ 10`:

  ```
  W_ship   > W_qual·10·n + W_local·n     # one fewer shipment beats any quality/local gain
  W_qual·ε > W_local·n                    # quality beats geography
  ```

  Solve once; get the lexicographically-optimal answer.
- **Staged solves** (cleaner, no giant constants): minimize `Σ y[s]`; fix it to
  its optimum `k*` as a constraint; then maximize quality; fix; then maximize
  local. Three tiny solves.

The more interesting option is **not** strict priority — it's letting the
business say "an extra shipment is worth it **only if** it buys ≥ X rating
points." That's just moderate, non-dominating weights, which is the reason to
expose them as config (§8).

### 7.4 Where it plugs in & solver choice

- **Seam:** introduce a `RoutingStrategy` interface with `greedy` (default) and
  `ilp` implementations; `RoutingService.assign()` delegates to it. Both consume
  the weights (§8) and the existing `SupplierDirectory.handling()` index — so
  `S` is pre-narrowed and models stay small.
- **Solver on the JVM:** [OR-Tools](https://developers.google.com/optimization)
  CP-SAT (excellent for this 0/1 model, Apache-2.0, Java bindings), or pure-Java
  **ojAlgo** / **Choco** for zero native deps. Problem size (≤ a few dozen
  suppliers × a few items) solves in microseconds — not a latency concern.
- **Safety net:** wrap the solve with a time limit and fall back to greedy if the
  solver is unavailable or times out; greedy is a good warm-start incumbent
  anyway.

With dominating weights the ILP is provably equivalent to what ships today, so
it can be introduced behind a flag and diffed against greedy on real orders
before flipping the default — the ILP only ever *improves* the shipment count on
the cases greedy gets wrong.

---

## 8. Config-driven weighting

```yaml
# application.yml
router:
  weights:
    shipment: 1000.0    # cost of one extra shipment
    quality: 10.0       # reward per satisfaction point (0–10)
    local: 5.0          # reward per locally-fulfilled item
    unrated-score: 0.0  # score assigned to "no ratings yet" suppliers
```

```kotlin
@ConfigurationProperties("router.weights")
data class RoutingWeights(
    val shipment: Double = 1000.0,
    val quality: Double = 10.0,
    val local: Double = 5.0,
    val unratedScore: Double = 0.0,
)
```

With the defaults above and small orders (`10·n + n` ≈ a few hundred < 1000), the
weights are *dominating*, so this reproduces today's strict-priority behavior
exactly. Lower `shipment` toward `~30` and you get the soft trade-off
("consolidate unless quality is clearly better"). One knob, no code change.

**Feeding the ILP objective** — the weights map straight onto the coefficients:

```kotlin
// per used supplier
objective.setCoefficient(y[s], weights.shipment)
// per feasible (item, supplier) pair
val score = weights.quality * qualityOf(s) + weights.local * (if (s.localForZip) 1.0 else 0.0)
objective.setCoefficient(x[i][s], -score)   // negative: rewards are minimized-away
objective.minimize()
```

**Feeding the current greedy engine** — the same weights turn today's
lexicographic `Comparator` into a scalar score, so greedy and ILP can share one
policy object:

```kotlin
// replaces the compareBy(...) preference in RoutingService
private fun score(c: Candidate): Double =
    weights.quality * (c.eligible.supplier.satisfaction ?: weights.unratedScore) +
    weights.local   * (if (c.local) 1.0 else 0.0)
// pick: coverage-first, then score
```

Honest limitation: greedy's *shipment* minimization is structural (it picks
max-coverage first), so in greedy the weights mainly govern the
quality-vs-local trade-off and whether to accept a lower-coverage-but-better
supplier — it can't truly "spend a shipment for quality." Only the ILP expresses
that fully.
