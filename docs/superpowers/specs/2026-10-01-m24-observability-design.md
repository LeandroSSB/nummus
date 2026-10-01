# M24 — Observability (health probes and Prometheus metrics)

## Problem

nummus has no operational surface: no health endpoint for load balancers or
orchestrators, no liveness/readiness distinction, and no metrics. A payment
core that cannot be probed or scraped cannot be operated — the first
deploy would be blind. The operator's existing Prometheus + Grafana stack has
nothing to point at.

## Goals

- Spring Boot Actuator on a **separate management port** (default `9090`,
  overridable), so the merchant API surface and its auth/rate-limit filters
  never apply to management traffic:
  - `/actuator/health/liveness` and `/actuator/health/readiness` (Kubernetes
    probe groups; readiness includes the datasource check).
  - `/actuator/prometheus` exposing the Prometheus scrape endpoint.
- Domain metrics, Micrometer counters with deliberately tiny tag cardinality
  (tags carry outcomes, never merchant or account ids):
  - `nummus.intents` — `created`, `settled`, `failed`, `expired`
  - `nummus.payouts` — `requested`, `executed`, `failed`, `expired`
  - `nummus.refunds` — `requested`, `settled`, `failed`, `expired`
  - `nummus.webhook_deliveries` — `attempted`, `succeeded`, `failed`
  Incremented at the same service methods that publish lifecycle events and
  drive the outbox — one line per transition, no new transactionality.
- The counters are the truth of state-machine throughput; balances and money
  stay ledger-derived (metrics are observability, never accounting).

## Non-goals

- Histograms/timers per endpoint, tracing, log shipping (backlog threads).
- Per-merchant or per-account metric tags (cardinality hazard).
- Operator dashboards or alert rules (belong to the scraping stack).
- Exposing actuator on the main API port under any path.

## Approaches considered

1. **Separate management port (chosen).** Actuator's own child context on
   `9090`: the auth filter, rate limiter, and request-body cap apply only to
   the main port by construction; no path-prefix allowlist to maintain;
   nginx/compose expose one extra internal port.
2. Actuator on the main port behind a path prefix + filter exemptions.
   Couples management routing with the security filter chain and the rate
   limiter; one misordered rule leaks `/actuator` to merchant traffic.
   Rejected.
3. A hand-rolled `/health` controller and `/metrics` text endpoint. More
   code to own, none of Actuator's probe groups/registry integration.
   Rejected.

## Design

### Dependencies (`pom.xml`)

`spring-boot-starter-actuator` and `micrometer-registry-prometheus` (versions
from the Boot BOM).

### Configuration (`application.yml`)

```yaml
management:
  server:
    port: 9090
  endpoints:
    web:
      exposure:
        include: health,prometheus
  endpoint:
    health:
      probes:
        enabled: true
```

In tests the management port is set to `0` (random) via properties so
parallel contexts never collide.

### Instrumentation

A small `Metrics` facade per module is NOT introduced — counters are created
via an injected `MeterRegistry` where the transition already happens:

- `PaymentsServiceImpl`: create (+created), settle (+settled),
  failure/expiry transitions (+failed/+expired).
- `PayoutsServiceImpl`: create (+requested), execution (+executed),
  failure/expiry resolution (+failed/+expired).
- `RefundsServiceImpl`: create (+requested), settle (+settled).
- The webhook delivery worker: per attempt (+attempted), terminal success
  (+succeeded), terminal failure (+failed).

Each counter: `Counter.builder("nummus.<domain>").tag("outcome", "<state>")`
— one builder invocation per site, registry-cached by Micrometer.

### Health

Default Actuator health with probe groups enabled; the datasource health
indicator rides the starter. No custom indicators.

## Testing

- `@SpringBootTest(webEnvironment = RANDOM_PORT)` integration test with
  `management.server.port=0`: fetch the management port from the environment,
  assert `/actuator/health/liveness` and `/readiness` return `200` with
  `"UP"`, and `/actuator/prometheus` returns `200` text.
- Drive one full lifecycle (intent → settle) through the real services in the
  same test, then scrape `/actuator/prometheus` and assert the
  `nummus_intents_created` and `nummus_intents_settled` series are present
  with value ≥ 1.
- A guard test asserting the MAIN port does not serve `/actuator/health`
  (404 on the API port).

## Migration

None — `V24` remains the head.
