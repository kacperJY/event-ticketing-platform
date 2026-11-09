# 🎫 Event Ticketing Platform

![Status](https://img.shields.io/badge/status-core_project_complete-brightgreen)
![Java](https://img.shields.io/badge/Java-25-blue)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-brightgreen)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-4-FF6600)

Backend portfolio project for event management, seat inventory, orders, payments and refunds. The project focuses on concurrency, transaction boundaries, reliable messaging and integration with an external payment provider.

> **Project status:** the planned end-to-end flow is complete. The Sales API handles checkout, payment, refunds and reliable publication of paid orders. The worker consumes each completed order, persists its own tickets, then schedules PDF generation and email delivery with bounded retries. Further work is maintenance and optional improvements.

## Current scope

| Area | Status |
|---|---|
| Authentication and authorization | JWT and role-based access |
| Event catalog and administrator event creation | Implemented |
| Asynchronous seat generation | RabbitMQ consumer |
| Reliable publishing | Transactional outbox, confirms, returns and scheduled recovery |
| Consumer reliability | Database deduplication, bounded retries and DLQ |
| Order creation | One event per order, multiple seats and concurrent seat locking |
| Order expiration | Scheduled cleanup and immediate checks during payment initialization |
| Stripe PaymentIntent initialization | Create/retrieve with deterministic idempotency keys |
| Stripe webhooks | Signature verification, transactional inbox and state validation |
| Refund initiation | Scheduled TX1 → Stripe CREATE → TX2 flow |
| Refund outcomes | `refund.created`, `refund.updated` and `refund.failed` |
| Automated verification | Unit tests and PostgreSQL/RabbitMQ integration tests |
| Runtime and CI | Sales API and worker Docker images, isolated Compose networks and GitHub Actions verification of both services |
| Paid-order publication for fulfillment | Transactional outbox with one versioned message per completed order |
| Fulfillment worker | RabbitMQ consumer, contract validation, transactional inbox and owned persistence |
| Ticket ownership and deduplication | One worker-owned ticket per source order item, protected by inbox and database constraints |
| Ticket PDF generation | One PDF per order, one page per ticket, generated for each delivery attempt |
| Email delivery and fulfillment scheduler | Implemented with bounded concurrency, retry and orphan recovery |
| OpenAPI documentation | Future improvement |

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
                             └── completed-order queue → Fulfillment Worker
                                                           |── contract and payload validation
                                                           |── retry → DLQ for permanent/exhausted failures
                                                           |── separate PostgreSQL: atomic consumer transaction
                                                           |     |── processed_messages claim
                                                           |     |── fulfillment_orders
                                                           |     └── tickets
                                                           └── fulfillment scheduler → PDF → SMTP email

Successful payment transaction:
order + seats + Stripe inbox + fulfillment outbox → commit
  → existing outbox publisher with retry → completed-order queue
  → worker inbox + fulfillment order + complete ticket set → commit → ACK
  → scheduled claim → PDF per attempt → email attachment → SENT or retry/FAILED
```

```text
event-ticketing-platform/
├── sales-api/                       # implemented API and fulfillment producer
├── fulfillment-worker/              # fulfillment consumer and worker-owned ticket persistence
├── .github/workflows/               # verification of both services
├── compose.yaml                     # shared infrastructure and application runtime
├── .env.example                     # Compose and infrastructure variables
├── sales_api.env.example            # Sales API container variables
├── fulfillment_worker.env.example   # worker container variables
└── rabbitmq.example
```

The Sales API groups code by domain. Orchestration services coordinate external calls; separate Spring beans define transaction boundaries. Webhook dispatch selects the event handler, while handlers and validators enforce local state transitions. Seat lifecycle operations are shared by expiration and payment handling.

## Implemented flows

### Event creation and seat generation

The administrator creates an event through `POST /api/v1/admin/event`. The event and an outbox message are saved in one transaction. After commit, the application attempts publication; a scheduled scanner provides fallback delivery and recovery of stale processing records. New seats currently receive the fixed prefix `SEAT` (`SEAT - 1`, `SEAT - 2`, and so on); event-specific prefixes are a possible future improvement.

```text
Event + Outbox(PENDING) → commit → publish → broker confirm/return
                                     |
                                     └── RabbitMQ → consumer transaction
                                                     |── processed_messages claim
                                                     └── seats
```

Outbox records progress through `PENDING → PROCESSING → SENT`, with retry or `FAILED` outcomes. Publishing is at least once. The consumer uses an atomic insert into `processed_messages` to avoid applying the same message twice. Invalid contracts and exhausted consumer retries go to a DLQ.

### Checkout and order lifecycle

An authenticated user creates an order for exactly one event. The request to `POST /api/v1/order` contains a required `eventId` and a `quantity` between 1 and 1000:

```json
{
  "eventId": 1,
  "quantity": 3
}
```

This replaces the previous `tickets` array. Multiple tickets for the same event are supported; tickets for different events require separate orders. API clients must use the new request shape.

The application verifies that the event exists, then selects available seats for that event in ascending `seatId` order using pessimistic locking with `SKIP LOCKED`. It creates one order item per selected seat and calculates the total amount from their prices. Order creation and seat reservation run in one database transaction.

A missing event raises `NoSuchDbRecordException` before seats are queried. If fewer unlocked, available seats can be selected than requested, `NoSuchQuantityException` rejects the order without persisting an order or changing seat statuses.

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

Tickets are not created inside the Sales API. A valid payment completion creates a fulfillment request; the worker consumes it and persists one local ticket record for every order item.

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

The payload contains the order ID, purchaser email, payment completion time and one item per order item. Every item carries a stable order-item ID together with the event name, location, date, category and seat number. The worker therefore does not need read access to the Sales API database.

```text
PENDING / PENDING + payment_intent.succeeded
  → transaction: COMPLETED / SUCCEEDED + seats SOLD + Outbox(PENDING)
  → existing publisher and retry recovery
  → RabbitMQ completed-order queue
  → worker resolver + payload validator
  → transaction: inbox claim + FulfillmentOrder(PENDING) + complete Ticket set
  → commit → automatic acknowledgement
```

A late successful payment for an `EXPIRED` order enters the refund flow and does not create a fulfillment request. Re-delivery of the same Stripe event is stopped by the transactional inbox. A different success event for an already completed order reaches the existing idempotent no-op transition and does not create another outbox message.

The worker first validates the message identifier, contract headers, content type, aggregate ID and `ORDER / PAID / V1` routing contract. Bean Validation then checks the payload fields and rejects duplicated source order-item IDs. Invalid contracts are rejected without retry; unexpected processing failures use bounded stateless retry. Permanent failures and exhausted retries are rejected to the worker-owned DLQ.

The consumer transaction inserts `(consumerName, messageId)` into `processed_messages` using `ON CONFLICT DO NOTHING`. A new claim creates the fulfillment order and all tickets in the same transaction. Redelivery after a committed database transaction becomes a no-op, while any persistence failure rolls back the inbox claim and remains retryable. `AUTO` acknowledgement occurs only after the listener returns successfully.

Both applications declare the same durable main exchange, dead-letter exchange, completed-order queue and binding with identical arguments. This lets either application start first. The worker alone declares and binds its completed-order DLQ.

RabbitMQ confirmation proves that the broker accepted the message. The committed worker consumer transaction proves that its fulfillment order and tickets were persisted. A separate scheduler claims persisted orders and performs PDF generation and SMTP delivery; `SENT` records successful completion of that delivery step. Broker acknowledgement alone does not imply that the email was sent.

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
| Concurrent checkout | Pessimistic seat locks with `SKIP LOCKED`, scoped to one event and ordered by `seatId` |
| Expiration versus payment outcome | Order lock, persisted order state and shared seat lifecycle |
| External payment calls | Separate database transactions before and after the call |
| Duplicate webhook | Event-ID primary key and atomic inbox claim |
| Webhook arriving before local initialization completes | Rollback and retryable HTTP error |
| Repeated refund creation | Stable idempotency key and parameters; idempotent TX2 |
| Duplicate AMQP message | Atomic inbox claim in the same transaction as the worker-owned order and tickets |
| Invalid or repeatedly failing fulfillment message | Permanent contract failures and exhausted retries are rejected to the worker DLQ |
| Publisher failure | Outbox retries and stale-processing recovery |
| Order completion versus fulfillment publication | Order transition, Stripe inbox and fulfillment outbox commit together |
| Ticket delivery | Bounded scheduler concurrency, database claims with `SKIP LOCKED`, separate state-update transactions and retry after failures |
| Interrupted delivery | Stale `PROCESSING` records become eligible for another attempt after the orphan timeout |

Stripe may deliver events out of order and retry failed deliveries. Delivery and idempotency retention are finite; a deterministic key is not an indefinite recovery mechanism. See [Stripe webhooks](https://docs.stripe.com/webhooks) and [idempotent requests](https://docs.stripe.com/api/idempotent_requests).

Known limitations and follow-up work:

- No periodic reconciliation of local payment/refund state with Stripe after lost or exhausted webhook deliveries.
- `charge_already_refunded` is logged; there is no suspended-refund state, automatic refund discovery or operator recovery flow yet.
- Refund initiation repeatedly selects the first eligible page without retry backoff or quarantine. Persistently failing candidates can consume the batch and delay others.
- `requires_action`, manually created refunds and partial/multiple-refund workflows are outside the current application contract.
- A late failed refund is handled through `refund.failed`; subscribe to all six supported events. `created`/`updated` with `failed` do not themselves reverse local `REFUNDED`.
- Automated coordinator coverage currently verifies successful TX1 → CREATE → TX2 orchestration. A failed TX2 followed by a coordinator rerun remains a useful additional recovery test.
- SMTP acceptance and a committed `SENT` status do not prove delivery to the recipient's inbox. If sending succeeds but the status update fails, a later retry may send a duplicate email.
- A sudden process/container exit or failed file deletion can leave a generated PDF on disk; there is no periodic cleanup of such stale files.
- There is no reconciliation or backfill flow for orders completed before fulfillment publication was introduced.

## API overview

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| POST | `/auth/register` | Public | Register |
| POST | `/auth/login` | Public | Obtain JWT |
| GET | `/api/v1/event` | Public | Catalog with `city` and `page` filters |
| GET | `/api/v1/event/{eventID}` | Public | Event details and available-seat count |
| POST | `/api/v1/admin/event` | ADMIN | Create event and schedule seat generation |
| POST | `/api/v1/order` | Authenticated | Create order for one event using `eventId` and `quantity` |
| POST | `/api/v1/order/{order_id}/payment-intent` | Order owner | Initialize/retrieve payment |
| POST | `/api/v1/webhooks/stripe` | Stripe signature | Process supported Stripe events |

## Stack and automated verification

Java 25, Spring Boot 4.1.0, Spring MVC, Security, Data JPA/Hibernate, AMQP, Bean Validation, Flyway, Stripe Java SDK 33.4.0, JJWT, PostgreSQL and RabbitMQ. The application enables virtual threads. Tests use JUnit, Mockito, AssertJ, MockMvc and Testcontainers.

From `sales-api/`:

```bash
./mvnw test          # unit tests
./mvnw clean verify  # unit + integration tests, packaging
```

From `fulfillment-worker/`:

```bash
./mvnw clean verify  # unit + PostgreSQL/RabbitMQ integration tests, packaging
```

Integration tests require a running Docker daemon and start isolated PostgreSQL/RabbitMQ containers. The `test` profile disables scheduled scanners. Stripe API calls are mocked in automated payment/refund orchestration tests; webhook HTTP tests construct signed payloads.

Sales API coverage includes single-event checkout, request validation, missing events, insufficient seats, concurrent checkout for the same and different events, state matrices, inbox commit/rollback, duplicate and concurrent event delivery, missing local IDs and subsequent retry, order locking, refund candidate selection, refund TX2 persistence and lock failure, successful refund orchestration with request-argument assertions, and the atomic successful-payment path that persists a complete fulfillment payload in the outbox.

Worker coverage includes message-contract resolution, payload validation, duplicate order-item detection, atomic inbox/order/ticket persistence, sequential and concurrent redelivery, successful AMQP consumption, retry followed by recovery, immediate rejection of permanent routing failures, dead-lettering after exhausted retries, PDF generation with one page and the correct order and ticket IDs per item, fulfillment state transitions, successful delivery with a PDF attachment, mail/PDF failures and recovery of stale processing after a failed status update. Automated mail tests use a mocked sender; the local end-to-end flow was also checked with the configured SMTP service.

Testcontainers and Compose both pin PostgreSQL to `postgres:17.10` and RabbitMQ to `rabbitmq:4.3.2-management`.

GitHub Actions runs `./mvnw verify` for both Sales API and fulfillment worker on pushes to `main`, pull requests targeting `main`, and manual `workflow_dispatch` runs.

## Local development

Requirements: Java 25, Docker with Compose, a Stripe sandbox and the [Stripe CLI](https://docs.stripe.com/stripe-cli). Maven is supplied by the wrapper.

```bash
git clone https://github.com/kacperJY/event-ticketing-platform.git
cd event-ticketing-platform
cp .env.example .env
cp sales_api.env.example sales_api.env
cp fulfillment_worker.env.example fulfillment_worker.env
cp rabbitmq.example rabbitmq
```

The files have separate responsibilities:

- `.env` is read by Docker Compose for variable interpolation and configures ports, database containers and RabbitMQ initialization.
- `sales_api.env` contains variables injected into the Sales API container in the `prod` profile.
- `fulfillment_worker.env` contains variables injected into the worker container in the `prod` profile.
- `rabbitmq` is mounted into the broker container as its configuration file.
- `fulfillment-worker/local.environments.properties` contains the worker's local SMTP settings for the `dev` profile.

For DEV, fill the infrastructure credentials in `.env` to match both applications' `application-dev.yaml` files:

```properties
SALES_API_POSTGRES_USER=admin
SALES_API_POSTGRES_PASSWORD=admin
FULFILLMENT_WORKER_POSTGRES_USER=admin
FULFILLMENT_WORKER_POSTGRES_PASSWORD=admin
RABBITMQ_DEFAULT_USER=admin
RABBITMQ_DEFAULT_PASS=admin
```

The database names are already present in the example. Keep the Sales API database host port at `5432`, the worker database host port at `5433`, the RabbitMQ host ports at `5672` and `15672`, and `RABBITMQ_CONFIG_FILE=/config/rabbitmq`. The copied `rabbitmq` file configures the same broker ports.

Start the infrastructure from the repository root:

```bash
docker compose --profile dev up -d
```

The DEV profile starts infrastructure only. The Sales API and worker run directly on the host:

| Service | Local address |
|---|---|
| Sales API PostgreSQL | `localhost:5432` |
| Fulfillment worker PostgreSQL | `localhost:5433` |
| RabbitMQ AMQP | `localhost:5672` |
| RabbitMQ management UI | `http://localhost:15672` |

The two databases are separate containers with separate persistent volumes. The applications share RabbitMQ but do not share database access.

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

The DEV JVM does not automatically load `.env`, `sales_api.env` or `fulfillment_worker.env`. The Sales API reads `local.secrets.properties` and `application-dev.yaml`. The worker's `application-dev.yaml` imports `local.environments.properties` from the `fulfillment-worker/` working directory. Create it from the example and fill in the SMTP host, port, username, app password and sender address before starting the worker:

```bash
cp fulfillment-worker/local.environments.example.properties fulfillment-worker/local.environments.properties
```

These local credentials are excluded from Git and the Docker build contexts. For a Gmail account using an app password, use its authenticated SMTP endpoint with STARTTLS on port 587. The sender address should match an address authorized by the SMTP account.

Start the application from `sales-api/`:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

To validate the worker locally, start it separately from `fulfillment-worker/`:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The worker is a long-running RabbitMQ consumer and fulfillment scheduler. On startup it migrates and validates its database, declares the shared completed-order topology and its own DLQ, prepares the PDF directory, then consumes paid-order messages and scans persisted fulfillment orders for delivery.

The completed-order queue now has dead-letter exchange arguments. If a local broker volume contains an older queue created without these arguments, RabbitMQ rejects the incompatible redeclaration with `PRECONDITION_FAILED`. Delete that queue once through the management UI or recreate the local broker volume before starting the updated applications.

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

For the fulfillment flow, confirm an application-created PaymentIntent belonging to a non-expired order. After the `payment_intent.succeeded` webhook completes, the Sales API should mark the order `COMPLETED / SUCCEEDED`, save and publish one outbox record, and the worker should persist one inbox claim, one fulfillment order and one ticket for every source order item. The worker scheduler should then move the fulfillment order to `SENT` and send one email with a PDF attachment containing a separate page for each ticket. The completed-order queue and worker DLQ should both be empty after successful processing.

## Production-style local Docker runtime

The Compose `prod` profile builds and runs the Sales API and fulfillment worker inside Docker together with their separate PostgreSQL databases and the shared RabbitMQ broker. The Sales API can still use Stripe **sandbox** credentials; a Spring profile does not select Stripe live mode.

Fill the copied files as follows:

### `.env`

This file defines the Compose model and initializes infrastructure containers:

- `SALES_API_HOST_PORT`,
- `SALES_API_POSTGRES_DB`, `SALES_API_POSTGRES_USER`, `SALES_API_POSTGRES_PASSWORD`,
- `SALES_API_DB_HOST_PORT`, `SALES_API_DB_HOST_PORT_LOCAL`,
- `FULFILLMENT_WORKER_POSTGRES_DB`, `FULFILLMENT_WORKER_POSTGRES_USER`, `FULFILLMENT_WORKER_POSTGRES_PASSWORD`,
- `FULFILLMENT_WORKER_DB_HOST_PORT`, `FULFILLMENT_WORKER_DB_HOST_PORT_LOCAL`,
- `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS`,
- `RABBITMQ_NODE_PORT`, `RABBITMQ_NODE_PORT_LOCAL`,
- `RABBITMQ_MANAGEMENT_TCP_PORT`, `RABBITMQ_MANAGEMENT_TCP_PORT_LOCAL`,
- `RABBITMQ_CONFIG_FILE`.

`.env` is used for Compose interpolation. Its values are not automatically exposed to every container. Compose explicitly passes the application port to the Sales API as `HOST_PORT` and the broker port to both applications as `RABBITMQ_NODE_PORT`.

### `sales_api.env`

This file is injected only into the Sales API container:

- `DB_URL`, `DB_USER`, `DB_PASSWORD`,
- `JWT_SECRET_KEY`, `STRIPE_SECRET_KEY`, **`STRIPE_WEBHOOK_SECRET`**,
- `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS`, `RABBITMQ_HOST`.

### `fulfillment_worker.env`

This file is injected only into the worker container:

- `DB_URL`, `DB_USER`, `DB_PASSWORD`,
- `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS`, `RABBITMQ_HOST`,
- `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `MAIL_FROM`.

For each application, `DB_USER` and `DB_PASSWORD` must match the corresponding PostgreSQL initialization credentials from `.env`. RabbitMQ credentials must match in all three files. The example JDBC URLs and broker hostnames use Compose service names and should remain unchanged for the container runtime.

Use the Stripe CLI listener's `whsec_...` as `STRIPE_WEBHOOK_SECRET` for local forwarding and match its target port to `SALES_API_HOST_PORT`.

```bash
docker compose --profile prod up --build -d
```

The runtime uses three networks:

- `sales-api-db-network` connects only the Sales API and its database,
- `fulfillment-worker-db-network` connects only the worker and its database,
- `broker-network` connects both applications to RabbitMQ.

Neither application can reach the other application's database through the Compose networks. Both applications communicate asynchronously through the shared broker.

After changing `.env`, `sales_api.env` or `fulfillment_worker.env`, recreate the affected containers to refresh their environment; a simple container restart retains the old environment.

```bash
docker compose --profile prod up -d --force-recreate event-ticketing-platform-sales-api
docker compose --profile prod up -d --force-recreate event-ticketing-platform-fulfillment-worker
```

The worker container remains active as the completed-order consumer and delivery scheduler. It can start before or after the Sales API because both applications declare the same shared exchange, source queue and binding. The worker additionally owns the DLQ and its separate PostgreSQL schema. It writes PDFs to `/opt/ticketing/pdfs/` inside the container and normally deletes each file after its delivery attempt. Compose does not mount this directory as a persistent volume.

Use one runtime profile at a time because both share service names, ports and volumes. Stop the selected stack with `docker compose --profile dev down` or `docker compose --profile prod down`. Adding `-v` removes its persistent data.

This setup is a local production-style runtime, not a complete public deployment. A public Stripe destination needs HTTPS and its own configured signing secret.

## Database migration policy

Flyway applies migrations and Hibernate validates each application's schema. In the Sales API, V4 adds the Stripe inbox, refund fields and updated payment-status constraint. V5 aligns the outbox aggregate and operation constraints with the implemented `ORDER / PAID` fulfillment contract.

**V1–V5 are the established migration history after this milestone. Do not edit or replace them.** Future schema or data changes belong in V6 and subsequent migrations. An existing database must have a compatible migration history; the automated suite verifies migration of a fresh database, not every historical development database.

The worker owns an independent Flyway history in its own database. Its V1 creates `processed_messages`, `fulfillment_orders` and `tickets`, including lifecycle checks, source-order relationships and source-order-item uniqueness. V2 updates the `fulfillment_orders` lifecycle constraint for retryable `PENDING` and in-progress `PROCESSING` records. Future worker schema changes belong in V3 and subsequent migrations; the worker migration numbering is independent of the Sales API history.

## Ticket delivery and future updates

The planned project flow is complete: a successful payment produces a durable fulfillment request, and the worker generates and emails the corresponding tickets. The following details describe the implemented delivery boundary and the optional work left for later.

### Implemented fulfillment boundary

For a fully paid, valid order (`OrderStatus.COMPLETED` and `PaymentStatus.SUCCEEDED`), the Sales API persists one `ORDER / PAID / V1` message in its transactional outbox. The order transition, Stripe inbox claim, seat update and outbox insert commit together. A late payment for an expired order follows the refund flow and does not request ticket generation.

The existing publisher delivers the message using broker confirms, returns, retry and stale-processing recovery. The worker validates the contract and payload, then atomically persists:

- `processed_messages`, keyed by consumer name and source message ID for inbox deduplication,
- `fulfillment_orders`, keyed by the source order ID and carrying delivery state, retry scheduling and optimistic-lock version,
- `tickets`, with a generated worker ticket ID, a source-order relationship and a unique source order-item ID.

The contract contains all event and seat data required by the worker, so it never reads the Sales API database. Re-delivery of the same source message is a no-op after the first committed consumer transaction. Backfilling orders completed before this producer existed remains a separate recovery concern.

The `PDFGenerator` accepts a completed-order ticket payload and a full output-file path. For each delivery attempt, it produces one PDF for the order with an A5 page per ticket, including the worker-generated ticket ID and source order ID. Tests verify the page count and these IDs on every page.

### Fulfillment delivery pipeline

The fulfillment lifecycle is `PENDING → PROCESSING → SENT`, with `PROCESSING → PENDING` after a retryable failure and `PROCESSING → FAILED` when attempts are exhausted. A new record starts in `PENDING` with zero attempts. TX1 claims eligible records using `SKIP LOCKED`, increments `attemptCount`, clears the previous retry fields and moves them to `PROCESSING`. The scheduler limits simultaneous work with a semaphore and runs PDF generation and SMTP delivery outside the database transaction. Each attempt creates its own PDF; the sender attaches it to one email for the entire order.

TX2 records success as `SENT` with `deliveredAt`, or records the error and either schedules another `PENDING` attempt through `nextAttemptAt` or marks the order `FAILED`. A `PROCESSING` record left behind by an interrupted attempt becomes eligible again after the stale-processing timeout. TX1 can also mark a recovered record `FAILED` when its retry budget has already been exhausted. The PDF is deleted after a handled success or failure, independently of whether the failure-state update itself succeeded. Database constraints protect the compatible state combinations.

Ticket persistence proves that the fulfillment request was consumed; only the later `SENT` state records that the worker completed its SMTP send call. External email delivery cannot be committed atomically with the worker database transaction, so a retry after an ambiguous send outcome can produce a duplicate email.

### Future updates

- Ongoing maintenance and focused refactoring of naming, configuration and implementation details.
- OpenAPI/Swagger documentation for the Sales API.
- Query and index optimization based on measured workload.
- Moving RabbitMQ queue, exchange and binding declarations into shared external broker configuration.
- Event-specific seat-number prefixes instead of the current fixed `SEAT - [number]` pattern.
- Reconciliation and operator recovery for payments, refunds and older fulfillment requests.
- A separate cleanup scheduler that scans the PDF directory and removes files no longer used by any active delivery attempt.

This is a learning and portfolio project. Its planned scope now includes payment, refunds, reliable fulfillment publication, idempotent worker intake, PDF generation and email delivery. The project is considered complete at this stage.
