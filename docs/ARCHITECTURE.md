# Architecture

This document describes the Order Router system: its components, how they
interact, and how to enhance or extend each one.

---

## 1. Overview

The Order Router is a stateless Spring Boot service. On startup it loads two
reference CSVs (products and suppliers) into memory; at request time it routes
each incoming order to one or more suppliers using a deterministic rules engine.
There is no database — the dataset is small and read-only, so in-memory
structures keep the design simple and fast.

```
                        ┌──────────────────────────────────────────┐
                        │              OrderRouter (JVM)            │
  Browser               │                                          │
  ┌──────────────┐      │  ┌────────────┐      ┌────────────────┐  │
  │ Test console │─GET /│─▶│WebController│      │  RoutingService │  │
  │ (Thymeleaf + │      │  └────────────┘      │  (rules engine) │  │
  │  app.js)     │      │                       └───────▲────────┘  │
  │              │POST  │  ┌────────────┐               │           │
  │              │/api/ │─▶│RouteController├────────────┘           │
  └──────────────┘route │  └────────────┘        reads              │
                        │        ▲            ┌──────────┴────────┐  │
  curl / other ─────────┼────────┘            │ ProductCatalog    │  │
  clients               │                     │ SupplierDirectory │  │
                        │   startup load ────▶│ (in-memory)       │  │
                        │                     └──────────▲────────┘  │
                        │                                │ CSV       │
                        │                     ┌──────────┴────────┐  │
                        │                     │ products.csv       │ │
                        │                     │ suppliers.csv      │ │
                        │                     └───────────────────┘  │
                        └──────────────────────────────────────────┘
```

---

## 2. Components

### 2.1 Data model (`model/`)

| Type | Responsibility |
|------|----------------|
| `Product` | A catalog product; exposes a normalized `categoryKey` (lower-cased) for case-insensitive matching. |
| `Supplier` | A supplier with `ZipCoverage`, normalized `categoryKeys`, nullable `satisfaction`, and `canMailOrder`. Helper predicates `handlesCategory` / `servesZip`. |
| `ZipCoverage` | Parses `service_zips` cells into numeric ranges (handles explicit lists, `10001-10100` ranges, mixed cells, leading zeros) and answers `covers(zip)`. |
| `dto.kt` | API payloads: `RouteRequest` / `OrderItem` in, `RouteResponse` / `SupplierShipment` / `RoutedItem` out. |

**Design note:** normalization (casing, ZIP → int) happens once at load time, so
the hot path (`route`) is pure comparisons.

**How to extend:** add a field (e.g. supplier capacity or SLA) to `Supplier`,
populate it in `SupplierDirectory`, and reference it in `RoutingService`. Adding
a response field is a non-breaking change because responses omit nulls.

### 2.2 Data access (`repository/`)

`ProductCatalog` and `SupplierDirectory` load their CSVs once at construction
(via Spring's `ResourceLoader`) and expose read-only lookups. `ProductCatalog`
is a `Map<code, Product>`; `SupplierDirectory` is a `List<Supplier>`.

They absorb the data's messiness:
- misspelled `suplier_name` header (fallback header lookup),
- `"no ratings yet"` → `null` satisfaction,
- `can_mail_order?` `y`/`n` → boolean,
- comma-separated categories and ZIP specs.

**How to extend:**
- **Swap the data source** — the file locations are bound from
  `router.data.products` / `router.data.suppliers`, so point them at
  `file:/data/...` or a mounted volume without code changes.
- **Move to a database** — replace the CSV load in these two classes with a
  repository query; nothing else in the system needs to change, since the rest
  of the code depends only on `all()` / `findByCode()`.
- **Reload without restart** — add a scheduled refresh or an admin endpoint that
  rebuilds the in-memory maps.

### 2.3 CSV parsing (`service/Csv.kt`)

A small, dependency-free CSV reader that honors double-quoted fields (ZIP and
category cells contain commas) and escaped quotes. `Csv.Table` provides
typo-tolerant, header-name-based column lookup.

**How to extend:** for larger or more irregular data, swap this for a library
(e.g. Apache Commons CSV or Jackson CSV) behind the same `Csv.Table` shape.

### 2.4 Routing engine (`service/RoutingService.kt`)

The core. `route(request)` performs:

1. **Validation** — structural checks producing human-readable errors
   (missing/empty items, ZIP format, quantity `1..MAX_QUANTITY`).
2. **Resolution** — map each `product_code` to a known `Product`; unknown codes
   become errors.
3. **Assignment** — a **greedy set-cover**: repeatedly pick the single supplier
   that fulfills the most still-unassigned items, then remove those items and
   repeat until all items are placed or no supplier can help.
4. **Response assembly** — feasible when every item was placed; otherwise
   `feasible=false` with per-item errors, still returning any partial routing.

**Candidate ranking** (the `preference` comparator, "greatest wins"):
`coveredCount` → `effectiveScore` (unrated = 0.0) → `localCount`. This encodes
the requirement priority order: consolidation, then quality, then geography.
Suppliers are pre-sorted by id so ties resolve deterministically.

**Eligibility** per (supplier, item): supplier handles the category **and**
either serves the ZIP (`local`) or, when `mail_order` is true and the supplier
ships nationally (`mail_order`).

**Complexity:** `O(shipments × suppliers × items)`. With ~1,100 suppliers and a
handful of items this is sub-millisecond; orders ship in 1–few passes.

**How to extend / evolve:**
- **Exact optimization** — replace greedy with an ILP / branch-and-bound solver
  if provably minimal shipment counts are required. The comparator is the single
  place where the objective lives.
- **Tunable weights** — externalize the tie-break weighting (e.g. how much a
  rating point is "worth" versus a local shipment) into `@ConfigurationProperties`.
- **Capacity / inventory** — filter candidates by stock, or split quantities
  across suppliers, by extending `candidateFor`.
- **Priority/SLA** — `RouteRequest.priority` is currently informational; use it
  to weight faster suppliers once SLA data exists.

### 2.5 Web layer (`web/`)

| Class | Responsibility |
|-------|----------------|
| `RouteController` | `POST /api/route` → delegates to `RoutingService`. |
| `ApiExceptionHandler` | Maps unparseable JSON bodies to a 200 `feasible=false` response, preserving the "always 200" contract. |
| `WebController` | `GET /` → renders the Thymeleaf console with dataset sizes, a sample order, and a curl snippet. |

JSON uses snake_case globally (Jackson `SNAKE_CASE` strategy) and omits nulls, so
DTOs stay idiomatic Kotlin while the wire format matches the spec.

**How to extend:**
- **Batch endpoint** — add `POST /api/route/batch` accepting an array; the
  console currently loops client-side instead.
- **Versioning** — introduce `/api/v2/route` and keep `RoutingService` behind an
  interface if the contract must change.
- **AuthN/Z, rate limiting, metrics** — add Spring Security and Actuator; the
  service is stateless so horizontal scaling is trivial.

### 2.6 Front end (`resources/templates`, `resources/static`)

A single Thymeleaf page driven by vanilla `app.js`. It parses orders (JSON or
CSV), calls the real API once per order, and renders shipments/errors as
Bootstrap cards. Bootstrap is bundled locally (no CDN), so the console works
offline / air-gapped.

**How to extend:** the page is intentionally framework-free for portability;
replace with a SPA (React/Vue) if richer interaction is needed — the API is
already CORS-free same-origin JSON.

---

## 3. Request lifecycle

```
POST /api/route
  → RouteController.route(request)
    → RoutingService.route(request)
        1. validate(request)                → [errors] ⇒ 200 feasible=false
        2. resolve product codes            → unknown codes collected as errors
        3. assign(resolved, request)        → greedy set-cover over SupplierDirectory
        4. build RouteResponse              → feasible=true | false(+partial routing)
  ← 200 OK (always), JSON body
```

---

## 4. Configuration

| Key | Default | Purpose |
|-----|---------|---------|
| `router.data.products` | `classpath:data/products.csv` | Product catalog location. |
| `router.data.suppliers` | `classpath:data/suppliers.csv` | Supplier directory location. |
| `server.port` | `8080` | HTTP port. |
| `spring.servlet.multipart.max-file-size` | `5MB` | Upload guard. |

`RoutingService.MAX_QUANTITY` (`1000`) bounds per-line quantity.

---

## 5. Testing strategy

- **Unit tests** run the engine against a small, hand-crafted dataset
  (`src/test/resources/testdata/`) so every outcome — which supplier, which mode,
  feasible or not — is deterministic and asserted precisely.
- **Boundary/error tests** cover bad input (empty items, malformed ZIP, unknown
  product, quantity `0`/negative/over-max) and parser edge cases (quoted fields,
  inverted ranges, typo headers).
- **Full-stack tests** (`@SpringBootTest` + `MockMvc`) verify the HTTP contract
  against the real bundled data: always-200, `feasible` semantics, snake_case
  JSON shape, malformed-body handling, and that the console page renders.

See [README.md](../README.md#testing) to run them.

---

## 6. Deployment

Multi-stage `Dockerfile` (JDK 21 build → JRE 21 runtime, non-root user).
`docker-compose.yml` builds and runs the single self-contained service with a
health check. Because the service is stateless and holds only read-only data in
memory, scaling out is just running more replicas behind a load balancer.
