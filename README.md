# 🎫 Event Ticketing Platform

![Status](https://img.shields.io/badge/status-sales_api_complete-brightgreen)
![Java](https://img.shields.io/badge/Java-25-blue)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-brightgreen)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-4-FF6600)

Backend portfolio project for event management, seat inventory, orders, payments and refunds. The project focuses on concurrency, transaction boundaries, reliable messaging and integration with an external payment provider.

> **Current milestone:** the Sales API implements checkout, order expiration, Stripe PaymentIntent initialization, signed webhooks, automatic refunds for late payments and reliable publication of successfully paid orders for fulfillment. The worker is currently a scaffold; it will consume the fulfillment contract, generate tickets and store them in its own database.

## Current scope

| Area | Status |
|---|---|
| Authentication and authorization | JWT and role-based access |
| Event catalog and administrator event creation | Implemented |
| Asynchronous seat generation | RabbitMQ consumer |
| Reliable publishing | Transactional outbox, confirms, returns and scheduled recovery |
| Consumer reliability | Database deduplication, bounded retries and DLQ |
| Order creation | Concurrent seat locking and deterministic event lock ordering |
| Order expiration | Scheduled cleanup and immediate checks during payment initialization |
| Stripe PaymentIntent initialization | Create/retrieve with deterministic idempotency keys |
| Stripe webhooks | Signature verification, transactional inbox and state validation |
| Refund initiation | Scheduled TX1 → Stripe CREATE → TX2 flow |
| Refund outcomes | `refund.created`, `refund.updated` and `refund.failed` |
| Automated verification | Unit tests and PostgreSQL/RabbitMQ integration tests |
| Runtime and CI | Sales API Docker image, Compose profiles and GitHub Actions |
| Paid-order publication for fulfillment | Transactional outbox with one versioned message per completed order |
| Fulfillment worker | Spring Boot scaffold only |
| Ticket ownership and deduplication | Planned in the worker's own database |
| Ticket generation, PDF and email | Planned |
| OpenAPI documentation | Planned |

## Architecture

```text
Client ──HTTP/JWT──> Sales API
                       |
                       |── PostgreSQL
                       |     |── users, events, seats, orders, order_items
                       |     |── outbox_messages       (outgoing AMQP messages)
                       |     |── processed_messages    (AMQP consumer deduplication)
                       |     └── stripe_events         (Stripe event inbox)
                       |
                       |── Stripe API: PaymentIntent create/retrieve, Refund create
                       |<─ Signed Stripe webhooks
                       |
                       └── RabbitMQ
                             |── CreateEventMessageConsumer → batch seat generation
                             └── completed-order queue → future Fulfillment Worker
                                                           └── separate worker database
                                                                 └── Ticket records + deduplication

Successful payment transaction:
order + seats + Stripe inbox + fulfillment outbox → commit
  → existing outbox publisher with retry → completed-order queue
```

```text
event-ticketing-platform/
├── sales-api/             # implemented application
├── fulfillment-worker/    # future ticket fulfillment
├── .github/workflows/     # Sales API verification
├── compose.yaml           # dev infrastructure and prod-style runtime
├── .env.example
└── rabbitmq.example
```

The Sales API groups code by domain. Orchestration services coordinate external calls; separate Spring beans define transaction boundaries. Webhook dispatch selects the event handler, while handlers and validators enforce local state transitions. Seat lifecycle operations are shared by expiration and payment handling.

## Implemented flows

### Event creation and seat generation

The administrator creates an event through `POST /api/v1/admin/event`. The event and an outbox message are saved in one transaction. After commit, the application attempts publication; a scheduled scanner provides fallback delivery and recovery of stale processing records.

```text
Event + Outbox(PENDING) → commit → publish → broker confirm/return
                                     |
                                     └── RabbitMQ → consumer transaction
                                                     |── processed_messages claim
                                                     └── seats
```

Outbox records progress through `PENDING → PROCESSING → SENT`, with retry or `FAILED` outcomes. Publishing is at least once. The consumer uses an atomic insert into `processed_messages` to avoid applying the same message twice. Invalid contracts and exhausted consumer retries go to a DLQ.

### Checkout and order lifecycle

An authenticated user creates an order containing event IDs and requested quantities. The application sorts event requests, locks available seats using `SKIP LOCKED`, creates order items and calculates the total amount from seat prices.

```text
New order: PENDING / NOT_INITIALIZED
Seats: AVAILABLE → LOCKED_FOR_CHECKOUT
```

The checkout lifetime is configured by `order.time-to-expired` (currently 10 minutes). Every 15 seconds, the expiration scanner processes up to five batches of 50 orders. Payment initialization also performs an immediate expiration check.

```text
Expiration:   PENDING → EXPIRED     seats → AVAILABLE
Cancellation: PENDING → CANCELED    seats → AVAILABLE
Payment:      PENDING → COMPLETED   seats → SOLD
```

Expiration preserves the payment status. The payment webhook acts on the persisted order state under an order lock: a successful payment for an already `EXPIRED` order requires a refund. The success handler does not independently compare `expiresAt` with the current time.

Tickets are not created inside the Sales API. A valid payment completion creates a fulfillment request for the future worker.

### PaymentIntent initialization

```text
POST /api/v1/order/{order_id}/payment-intent

TX1: ownership, expiration and local-state checks
  → Stripe PaymentIntent CREATE outside the database transaction
  → TX2: expiration check, NOWAIT order lock and local-state revalidation
  → persist PaymentIntent ID, PENDING payment status and initialization time
  → return client secret
```

The key `payment-intent:order:<orderId>` identifies the creation request. Stripe `idempotency_key_in_use` conflicts have bounded retries. If a pending PaymentIntent is already linked, the service retrieves it and validates the local order again before returning the response.

The client secret is returned only after the local update succeeds. Database transactions are not held open during Stripe network calls.

### Signed webhooks and transactional inbox

The endpoint is `POST /api/v1/webhooks/stripe`. It accepts the raw request body and validates the `Stripe-Signature` header with the configured webhook signing secret.

Supported events:

| Event | Local behavior |
|---|---|
| `payment_intent.succeeded` | Complete a pending order and sell its seats, or mark an expired order `REFUND_REQUIRED` |
| `payment_intent.canceled` | Cancel a pending order and release seats; preserve `EXPIRED` for expired orders |
| `payment_intent.payment_failed` | Validate PaymentIntent identity and record a no-op; one failed attempt does not cancel the order |
| `refund.created` | Handle the refund object's status through the refund handler |
| `refund.updated` | Handle the refund object's status through the refund handler |
| `refund.failed` | Mark the refund failed, including a previously successful refund |

```text
Verify signature → parse supported event and orderId metadata
  → transaction: INSERT stripe_events ON CONFLICT DO NOTHING
  → dispatcher → handler → NOWAIT order lock → validator → state change/no-op
  → commit inbox record and business changes together → HTTP 200
```

Duplicate event IDs are no-ops. A missing local PaymentIntent/refund ID during initialization or an order lock conflict rolls back the inbox claim and returns a retryable server error. The same event can be processed after the local transaction succeeds.

Handlers return `APPLIED`, `IDEMPOTENT_NO_OP` or `ACKNOWLEDGED_INCONSISTENT`; all three results commit the inbox entry. Unsupported events and some permanently invalid contracts are acknowledged before processing. Permanent identity/state exceptions are acknowledged after the transaction rolls back. Therefore, `stripe_events` is a processing inbox, not a complete audit log of every incoming request.

### Paid-order fulfillment publication

A `payment_intent.succeeded` event creates a fulfillment request only for the valid local transition from `OrderStatus.PENDING` and `PaymentStatus.PENDING` to `COMPLETED / SUCCEEDED`. The same transaction:

- claims the Stripe event in the inbox,
- completes the order and marks its seats as sold,
- saves one `ORDER / PAID / V1` outbox message for the whole order.

The payload contains the order ID, purchaser email, payment completion time and one item per order item. Every item carries a stable order-item ID together with the event name, location, date, category and seat number. The worker therefore will not need read access to the Sales API database.

```text
PENDING / PENDING + payment_intent.succeeded
  → transaction: COMPLETED / SUCCEEDED + seats SOLD + Outbox(PENDING)
  → existing publisher and retry recovery
  → RabbitMQ completed-order queue
```

A late successful payment for an `EXPIRED` order enters the refund flow and does not create a fulfillment request. Re-delivery of the same Stripe event is stopped by the transactional inbox. A different success event for an already completed order reaches the existing idempotent no-op transition and does not create another outbox message.

RabbitMQ confirmation proves that the broker accepted the message. It does not prove that the future worker generated or delivered the tickets, so worker processing must remain idempotent.

### Automatic refunds

Every 15 seconds, the refund scheduler selects up to 50 orders meeting all these conditions:

- order status `EXPIRED` and payment status `REFUND_REQUIRED`,
- a linked PaymentIntent,
- no refund ID, request timestamp or completion timestamp.

```text
TX1: select candidates with SKIP LOCKED → commit, release locks
  → for each candidate:
      Stripe Refund CREATE using refund:order:<orderId>
      → TX2: NOWAIT lock + revalidate + bind refund ID
      → REFUND_PENDING + refundRequestedAt
  → webhook resolves the refund outcome
```

TX1 does not reserve an order for the entire loop. Concurrent scheduler runs can select the same order after TX1 commits. Stable request parameters and Stripe idempotency protect creation; TX2 accepts an already-linked matching refund as a no-op without overwriting its outcome.

The refund request includes the PaymentIntent, amount and `orderId` metadata. It does **not** send `currency`; the refund uses the original payment's currency.

TX2 establishes the local link even when Stripe CREATE returns `succeeded`. Final status processing remains in the webhook handler. A `created` or `updated` event with `pending` preserves the local pending state after validation.

```text
REFUND_REQUIRED → REFUND_PENDING → REFUNDED
                                └→ REFUND_FAILED

refund.failed can also change REFUNDED → REFUND_FAILED and clear refundedAt.
```

`requires_action` does not trigger customer interaction. `REFUND_FAILED` has no automatic new-refund workflow. See the limitations below.

## Reliability boundaries

| Concern | Current approach |
|---|---|
| Concurrent checkout | Pessimistic seat locks and deterministic event ordering |
| Expiration versus payment outcome | Order lock, persisted order state and shared seat lifecycle |
| External payment calls | Separate database transactions before and after the call |
| Duplicate webhook | Event-ID primary key and atomic inbox claim |
| Webhook arriving before local initialization completes | Rollback and retryable HTTP error |
| Repeated refund creation | Stable idempotency key and parameters; idempotent TX2 |
| Duplicate AMQP message | Transactional processed marker |
| Publisher failure | Outbox retries and stale-processing recovery |
| Order completion versus fulfillment publication | Order transition, Stripe inbox and fulfillment outbox commit together |

Stripe may deliver events out of order and retry failed deliveries. Delivery and idempotency retention are finite; a deterministic key is not an indefinite recovery mechanism. See [Stripe webhooks](https://docs.stripe.com/webhooks) and [idempotent requests](https://docs.stripe.com/api/idempotent_requests).

Known limitations and follow-up work:

- No periodic reconciliation of local payment/refund state with Stripe after lost or exhausted webhook deliveries.
- `charge_already_refunded` is logged; there is no suspended-refund state, automatic refund discovery or operator recovery flow yet.
- Refund initiation repeatedly selects the first eligible page without retry backoff or quarantine. Persistently failing candidates can consume the batch and delay others.
- `requires_action`, manually created refunds and partial/multiple-refund workflows are outside the current application contract.
- A late failed refund is handled through `refund.failed`; subscribe to all six supported events. `created`/`updated` with `failed` do not themselves reverse local `REFUNDED`.
- Automated coordinator coverage currently verifies successful TX1 → CREATE → TX2 orchestration. A failed TX2 followed by a coordinator rerun remains a useful additional recovery test.
- The Sales API publishes the fulfillment request, but ticket generation, persistence, PDF creation and delivery are not implemented yet. The worker will own Ticket data in a separate database.
- There is no reconciliation or backfill flow for orders completed before fulfillment publication was introduced.

## API overview

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| POST | `/auth/register` | Public | Register |
| POST | `/auth/login` | Public | Obtain JWT |
| GET | `/api/v1/event` | Public | Catalog with `city` and `page` filters |
| GET | `/api/v1/event/{eventID}` | Public | Event details and available-seat count |
| POST | `/api/v1/admin/event` | ADMIN | Create event and schedule seat generation |
| POST | `/api/v1/order` | Authenticated | Create order |
| POST | `/api/v1/order/{order_id}/payment-intent` | Order owner | Initialize/retrieve payment |
| POST | `/api/v1/webhooks/stripe` | Stripe signature | Process supported Stripe events |

## Stack and automated verification

Java 25, Spring Boot 4.1.0, Spring MVC, Security, Data JPA/Hibernate, AMQP, Bean Validation, Flyway, Stripe Java SDK 33.4.0, JJWT, PostgreSQL and RabbitMQ. The application enables virtual threads. Tests use JUnit, Mockito, AssertJ, MockMvc and Testcontainers.

From `sales-api/`:

```bash
./mvnw test          # unit tests
./mvnw clean verify  # unit + integration tests, packaging
```

Integration tests require a running Docker daemon and start isolated PostgreSQL/RabbitMQ containers. The `test` profile disables scheduled scanners. Stripe API calls are mocked in automated payment/refund orchestration tests; webhook HTTP tests construct signed payloads.

Coverage includes state matrices, inbox commit/rollback, duplicate and concurrent event delivery, missing local IDs and subsequent retry, order locking, refund candidate selection, refund TX2 persistence and lock failure, successful refund orchestration with request-argument assertions, and the atomic successful-payment path that persists a complete fulfillment payload in the outbox.

Testcontainers and Compose both pin PostgreSQL to `postgres:17.10` and RabbitMQ to `rabbitmq:4.3.2-management`.

GitHub Actions runs Sales API verification on pushes to `main` and pull requests targeting `main`. Worker behavior is not covered by this CI job yet.

## Local development

Requirements: Java 25, Docker with Compose, a Stripe sandbox and the [Stripe CLI](https://docs.stripe.com/stripe-cli). Maven is supplied by the wrapper.

```bash
git clone https://github.com/kacperJY/event-ticketing-platform.git
cd event-ticketing-platform
cp .env.example .env
cp rabbitmq.example rabbitmq
```

For DEV, fill the infrastructure credentials in `.env` to match `application-dev.yaml`:

```properties
POSTGRES_DB=event-ticketing-platform-db
POSTGRES_USER=admin
POSTGRES_PASSWORD=admin
RABBITMQ_DEFAULT_USER=admin
RABBITMQ_DEFAULT_PASS=admin
```

Keep the example RabbitMQ host ports at `5672` and `15672`, and `RABBITMQ_CONFIG_FILE=/config/rabbitmq`. The copied `rabbitmq` file configures these same ports.

Start the infrastructure from the repository root:

```bash
docker compose --profile dev up -d
```

The Sales API runs on the host. PostgreSQL is available at `localhost:5432`, RabbitMQ at `localhost:5672`, and its management UI at `http://localhost:15672`.

### Stripe CLI and signing secret

Log in to the **same sandbox** used by the application's test API key, then keep this listener running:

```bash
stripe login
stripe listen \
  --events payment_intent.succeeded,payment_intent.canceled,payment_intent.payment_failed,refund.created,refund.updated,refund.failed \
  --forward-to http://localhost:8080/api/v1/webhooks/stripe
```

Create the ignored file `sales-api/local.secrets.properties`:

```properties
local.secret.jwt-secret-key=<base64-encoded-local-jwt-secret>
local.secret.stripe.secret-key=<sk_test_...>
local.stripe.webhook.secret=<whsec_... printed by stripe listen>
```

The webhook property name intentionally matches the existing DEV configuration. The CLI signing secret is distinct from the API key and from a Dashboard endpoint's signing secret. Restart the application after changing this file.

The DEV JVM does not automatically load the root `.env`. It reads this properties file and `application-dev.yaml` instead. The file is excluded from Git and the Docker build context.

Start the application from `sales-api/`:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The DEV profile seeds these local accounts:

| Role | Email | Password |
|---|---|---|
| USER | `user@gmail.com` | `User123` |
| ADMIN | `admin@gmail.com` | `Admin123` |

### Manual payment and refund checks

Create an event/order through the application and initialize its PaymentIntent. Use the returned **PaymentIntent ID** (`pi_...`), not the client secret, in CLI commands.

Confirm an application-created PaymentIntent:

```bash
stripe post /v1/payment_intents/pi_REPLACE_ME/confirm \
  -d payment_method=pm_card_visa \
  -d return_url=http://localhost:8080/payment-return
```

`return_url` is the browser return destination for redirect-capable payment methods. The URL above is a placeholder; the application has no frontend return page. The test Visa scenario does not require visiting it.

Cancel a **different, still cancelable** PaymentIntent:

```bash
stripe post /v1/payment_intents/pi_REPLACE_ME/cancel
```

A succeeded PaymentIntent cannot be canceled. For the automatic refund scenario, initialize a fresh order's PaymentIntent, wait until the order is actually `EXPIRED` locally, then confirm that PaymentIntent. The success webhook should mark it `REFUND_REQUIRED`; the scheduler creates and links the refund, and refund webhooks resolve its status. Observe application logs and persisted state; webhook processing is asynchronous.

Generic `stripe trigger` fixtures create independent test objects and do not automatically target your order. Creating a refund manually also bypasses the scheduler's local linking transaction, so it is not equivalent to testing the complete application refund flow.

## Production-style local Docker runtime

The Compose `prod` profile runs the API inside Docker. It can still use Stripe **sandbox** credentials; a Spring profile does not select Stripe live mode.

Fill `.env` with:

- `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`,
- `DB_URL`, `DB_USER`, `DB_PASSWORD` matching that database,
- `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS`, `RABBITMQ_HOST`, `RABBITMQ_NODE_PORT`,
- `JWT_SECRET_KEY`, `STRIPE_SECRET_KEY`, **`STRIPE_WEBHOOK_SECRET`**,
- `SERVER_PORT` (default example: `8080`).

The example database and broker hostnames refer to Compose services. Use the listener's `whsec_...` for local forwarding and match its target port to `SERVER_PORT`.

```bash
docker compose --profile prod up --build -d
```

Compose passes `STRIPE_WEBHOOK_SECRET` to the API container. After changing `.env`, recreate the API container to refresh its environment; a simple container restart retains the old environment.

```bash
docker compose --profile prod up -d --force-recreate event-ticketing-platform-sales-api
```

Use one runtime profile at a time because both share service names, ports and volumes. Stop the selected stack with `docker compose --profile dev down` or `docker compose --profile prod down`. Adding `-v` removes its persistent data.

This setup is a local production-style runtime, not a complete public deployment. A public Stripe destination needs HTTPS and its own configured signing secret.

## Database migration policy

Flyway applies migrations and Hibernate validates the resulting schema. V4 adds the Stripe inbox, refund fields and updated payment-status constraint. V5 aligns the outbox aggregate and operation constraints with the implemented `ORDER / PAID` fulfillment contract.

**V1–V5 are the established migration history after this milestone. Do not edit or replace them.** Future schema or data changes belong in V6 and subsequent migrations. An existing database must have a compatible migration history; the automated suite verifies migration of a fresh database, not every historical development database.

## Next stages: fulfillment worker

Development will continue in separate, focused work blocks. The Sales API producer boundary is complete: a valid paid-order transition persists the versioned fulfillment contract and the existing publisher delivers it through RabbitMQ. The remaining end-to-end work belongs to the worker.

### Starting point: implemented producer boundary

For a fully paid, valid order (`OrderStatus.COMPLETED` and `PaymentStatus.SUCCEEDED`), the Sales API persists one fulfillment message in its existing transactional outbox. The order transition, Stripe inbox claim, seat update and outbox insert commit together. A late payment for an expired order follows the refund flow and does not request ticket generation.

The publisher delivers the message using the existing retry and stale-processing recovery mechanisms. Duplicate payment delivery does not create another fulfillment request. Broker confirmation means the broker accepted the message; it does not mean the worker generated the tickets.

The `ORDER / PAID / V1` contract contains all data required to generate tickets, including stable order and order-item identifiers. The worker will not require access to the Sales API database. Backfilling orders completed before this producer existed remains a separate recovery concern.

### 1. Worker-owned tickets and idempotency

The worker will consume the fulfillment message, generate tickets for the paid order and persist the `Ticket` records in a **separate database owned by the worker**.

The intended design uses persisted Ticket records as the idempotency record for ticket creation. This requires stable source identifiers and database uniqueness, so redelivery of the same logical request cannot generate additional tickets. An order can contain multiple tickets; an order ID alone cannot be a unique identifier for each Ticket. The worker's exact uniqueness key and local transaction boundary will be defined before its implementation.

If all tickets for one message are created in a single local transaction, the committed Ticket set can establish that ticket creation has already completed. A failure must leave no partial set that is mistaken for completed processing, and message acknowledgement must follow the database commit. Any additional message-processing inbox needed for more complex workflows remains a design decision for that stage.

Ticket deduplication covers ticket creation. PDF generation and email delivery will need their own explicit progress, retry and failure behavior; the presence of a Ticket does not prove that delivery succeeded.

### 2. Subsequent work

- Add worker integration tests for broker retry, redelivery and recovery after a committed Ticket transaction.
- Implement PDF generation and email delivery.
- Address reconciliation/operator recovery and the known limitations above in separate changes.
- Add OpenAPI documentation when useful for presenting the project.

This is a learning and portfolio project. The documented Sales API scope now includes payment, refund and reliable fulfillment publication; end-to-end ticket generation and delivery remain unfinished.
