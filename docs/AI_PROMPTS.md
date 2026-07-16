# AI Prompts

This project was built with AI assistance (Claude). Below are the significant
prompts and instructions that drove the work, in the order they were used. The
first is the verbatim task prompt; the rest are the derived working prompts /
decisions used to implement each phase.

---

### 1. Original task prompt

> This is a tech assessment project that should be built using AI. Create a git
> repo for this and review the instructions and sample data provided. The main
> readme file should contain instructions on how to run the project when
> finished. The project should also contain a generated architecture document
> that describes the system and all of the components built, how they interact,
> how to enhance and expand each component. Unit tests should be built to
> exercise happy paths and error conditions (bad input data, invalid API usage,
> parameter size expectations, etc.).
>
> Incremental git commits should be made on relevant steps. Relevant AI prompts
> should be recorded in a document as per the instructions.
>
> The final project should include a service endpoint test web page to allow the
> user to upload an order CSV like the sample provided and display the results of
> calling the API endpoint on the page. The API should be exposed and sample
> CURL instructions should also be shown on the web page.
>
> For the tech stack, build a Spring Boot project with Kotlin. The frontend
> should be Thymeleaf with Bootstrap. A Docker compose file should be created to
> allow bringing up the entire stack on a new machine.

The take-home instructions themselves (`Take Home Instructions.pdf`) specified a
**Multi-Supplier Order Router**: `POST /api/route` that reads `suppliers.csv` and
`products.csv`, routes multi-item orders by feasibility → consolidation → quality
→ geography, always returns HTTP 200 with a `feasible` flag, and ships
containerized with `README.md` and `AI_PROMPTS.md`.

---

### 2. Understand the data

> Review products.csv, suppliers.csv and sample_orders.json. Identify data
> quirks that the loaders must tolerate (header typos, ZIP ranges vs lists,
> "no ratings yet", category casing) and confirm which product categories map to
> supplier capabilities.

Findings that shaped the code: the `suplier_name` header typo, `service_zips`
given as both explicit lists and `NNNNN-NNNNN` ranges, `customer_satisfaction_score`
sometimes `"no ratings yet"`, and category casing inconsistency (`CPAP` vs
`cpap`) — all handled at load time.

---

### 3. Scaffold the project

> Set up a Spring Boot 3 + Kotlin Gradle project. Pin a Java 21 toolchain so
> local builds match the container. Add web, Thymeleaf, validation, and Jackson
> Kotlin dependencies. Configure Jackson for snake_case JSON and non-null
> inclusion. Bundle the reference CSVs in resources with configurable locations.

---

### 4. Domain model and data layer

> Implement Product, Supplier, and a ZipCoverage type that parses mixed
> list/range ZIP specs and compares numerically. Write a dependency-free CSV
> parser that respects quoted fields and tolerates the header typo. Load
> products and suppliers into in-memory repositories at startup, normalizing
> category casing and parsing the messy satisfaction / mail-order fields.

---

### 5. Routing engine and validation

> Implement RoutingService.route() that optimizes in strict priority order:
> feasibility, then fewest shipments (greedy set-cover), then supplier quality,
> then local-over-mail-order on ties. Treat mail_order=true as making national
> suppliers eligible regardless of ZIP. Validate line items, 5-digit ZIP, and
> quantity bounds (1..1000); report unknown product codes and unroutable items
> as errors. Always return a 200-friendly RouteResponse with a feasible flag.

---

### 6. API and Thymeleaf test console

> Expose POST /api/route (always HTTP 200; add an exception handler so malformed
> JSON still returns feasible=false). Build a Thymeleaf + Bootstrap page that
> lets the user paste or upload orders (JSON array or flat CSV), calls the API
> per order from the browser, renders shipments/errors, and shows a copy-able
> curl command. Bundle Bootstrap locally so it works offline.

---

### 7. Tests

> Write unit tests against a small controlled dataset so routing outcomes are
> deterministic: consolidation, quality and geo tie-breaks, mail-order
> eligibility, and infeasibility. Add boundary/error tests (empty items,
> malformed ZIP, unknown product, quantity 0/negative/over-max) and parser edge
> cases. Add full-stack MockMvc tests for the HTTP contract (always 200, feasible
> semantics, malformed body, root page renders).

---

### 8. Containerization and documentation

> Write a multi-stage Dockerfile (JDK 21 build, JRE 21 runtime, non-root) and a
> docker-compose.yml that brings up the whole stack with `docker compose up
> --build` and a health check. Write README.md (run/build/test + API reference),
> ARCHITECTURE.md (components, interactions, how to extend each), and this
> AI_PROMPTS.md. Verify the image builds and serves a real route before finishing.

---

### 9. Docs reorganization and simplify pass

> Move ARCHITECTURE.md and AI_PROMPTS.md into docs/ (fixing cross-references),
> then run a simplify pass over the entire code base: fan out four independent
> review agents (reuse, simplification, efficiency, altitude), each returning
> concrete findings; dedup them and apply the quality fixes (no behavior
> changes), keeping the test suite green. Commit the updates.

This was a quality-only cleanup — reuse, simplification, efficiency, and
altitude — deliberately excluding correctness-bug hunting.

---

### Notes on process

- Work was committed incrementally after each phase (scaffold → data layer →
  routing/API/UI → tests → docker/docs).
- Each phase was verified before moving on: the app was run locally and every
  sample order plus error case was exercised via `curl`, the browser console was
  driven end-to-end, all 32 tests were run green, and the Docker image was built
  and hit with a live request.
