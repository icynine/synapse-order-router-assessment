# Multi-Supplier Order Router

A Spring Boot (Kotlin) service that routes multi-item medical-equipment orders to
the supplier(s) best able to fulfill them, balancing feasibility, shipment
consolidation, supplier quality, and local-vs-mail-order preference.

It ships with a **browser test console** (Thymeleaf + Bootstrap) for uploading
orders and inspecting routing results, and exposes a single JSON API:
`POST /api/route`.

---

## Quick start (Docker — recommended)

No JDK required on the host. From the project root:

```bash
docker compose up --build
```

Then open **http://localhost:8080/** for the test console, or call the API
directly:

```bash
curl -s -X POST http://localhost:8080/api/route \
  -H 'Content-Type: application/json' \
  -d '{
    "order_id": "ORD-001",
    "customer_zip": "10015",
    "mail_order": false,
    "items": [
      { "product_code": "WC-STD-001", "quantity": 1 },
      { "product_code": "OX-PORT-024", "quantity": 1 }
    ]
  }'
```

Stop with `Ctrl-C`, or `docker compose down`.

---

## Running locally (without Docker)

Requires **JDK 21**. The Gradle build pins a Java 21 toolchain, so run the
Gradle daemon on a JDK 21 (Kotlin 2.1 cannot host the compiler on newer JDKs).

```bash
# Point Gradle at a JDK 21 if it is not your default:
export JAVA_HOME=/path/to/jdk-21

./gradlew bootRun        # start the service on http://localhost:8080
./gradlew test           # run the test suite
./gradlew build          # compile, test, and produce build/libs/order-router-1.0.0.jar
```

Run the packaged jar:

```bash
java -jar build/libs/order-router-1.0.0.jar
```

---

## The test console

Open **http://localhost:8080/**. You can:

1. **Paste JSON** — a single order object or an array of orders.
2. **Upload a file** — `.json` (like the provided `sample_orders.json`) or a
   flat `.csv` (see format below). Each order is sent to `POST /api/route` from
   the browser and the shipments / errors are rendered per order.
3. **Copy a ready-to-run `curl`** command for the API.

CSV upload format (one row per line item, grouped by `order_id`):

```csv
order_id,customer_zip,mail_order,product_code,quantity
ORD-001,10015,false,WC-STD-001,1
ORD-001,10015,false,OX-PORT-024,1
```

---

## API reference

### `POST /api/route`

Always returns **HTTP 200**. The `feasible` field indicates whether the order
was fully routable.

**Request**

| Field          | Type    | Notes                                             |
|----------------|---------|---------------------------------------------------|
| `order_id`     | string  | Optional; echoed back in the response.            |
| `customer_zip` | string  | Required; 5-digit ZIP.                             |
| `mail_order`   | boolean | When `true`, national mail-order suppliers become eligible regardless of ZIP. |
| `items[]`      | array   | Required, non-empty.                              |
| `items[].product_code` | string | Required; must exist in the catalog.       |
| `items[].quantity`     | int    | Required; `1..1000`.                       |

**Successful response**

```json
{
  "feasible": true,
  "order_id": "ORD-001",
  "routing": [
    {
      "supplier_id": "SUP-0636",
      "supplier_name": "Care Supply Corp #636",
      "satisfaction_score": 6.8,
      "items": [
        { "product_code": "WC-STD-001", "quantity": 1, "category": "wheelchair", "fulfillment_mode": "local" },
        { "product_code": "OX-PORT-024", "quantity": 1, "category": "oxygen", "fulfillment_mode": "local" }
      ]
    }
  ]
}
```

**Unsuccessful response** (validation failure or unroutable items)

```json
{
  "feasible": false,
  "order_id": "ORD-BAD",
  "errors": [
    "Order must include at least one line item.",
    "Order must include a valid customer_zip."
  ]
}
```

`fulfillment_mode` is `local` when the supplier serves the customer's ZIP, or
`mail_order` when fulfilled via a national shipper.

---

## Routing logic

Optimized in strict priority order (see
[ARCHITECTURE.md](ARCHITECTURE.md) for the full design):

1. **Feasibility** — only suppliers that both handle the product category *and*
   are geographically eligible (serve the ZIP, or ship nationally when
   `mail_order` is `true`).
2. **Consolidation** — fewest shipments, via a greedy set-cover that repeatedly
   assigns remaining items to the single supplier covering the most of them.
3. **Quality** — higher `customer_satisfaction_score` wins ties (unrated
   suppliers rank lowest).
4. **Geography** — local fulfillment preferred over mail order on remaining ties.

The service tolerates the messy source data: mixed ZIP lists/ranges, a
misspelled `suplier_name` header, `"no ratings yet"` scores, and inconsistent
category casing (`CPAP` vs `cpap`).

---

## Project layout

```
src/main/kotlin/com/synapse/orderrouter/
  config/      RouterDataProperties (configurable data locations)
  model/       Product, Supplier, ZipCoverage, request/response DTOs
  repository/  ProductCatalog, SupplierDirectory (in-memory, CSV-backed)
  service/     Csv parser, RoutingService (the routing engine)
  web/         RouteController (API), WebController (console), exception handler
src/main/resources/
  data/        products.csv, suppliers.csv  (bundled reference data)
  templates/   index.html (Thymeleaf)
  static/      Bootstrap + app.js (bundled locally; no CDN needed)
src/test/       unit + full-stack tests, with a controlled testdata/ dataset
```

Reference-data locations are configurable via `router.data.products` /
`router.data.suppliers` (Spring resource locations, e.g. `file:/data/...`) so
alternate datasets can be supplied without a rebuild.

---

## Testing

```bash
./gradlew test
```

32 tests cover ZIP parsing, CSV parsing, the routing engine (consolidation,
quality/geo tie-breaks, mail-order eligibility, infeasibility), all validation
errors, and the full HTTP API contract. See
[ARCHITECTURE.md](ARCHITECTURE.md#testing-strategy) for the strategy.

---

## Documentation

- **[ARCHITECTURE.md](ARCHITECTURE.md)** — components, interactions, and how to
  extend each part.
- **[AI_PROMPTS.md](AI_PROMPTS.md)** — the significant AI prompts used to build
  this project, in order.
