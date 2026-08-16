# 🎫 Event Ticketing Platform

![Status](https://img.shields.io/badge/status-active_development-orange)
![Java](https://img.shields.io/badge/Java-25-blue)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-brightgreen)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-4-FF6600)

Backend portfolio project for event management, seat inventory, order processing and payment initialization. The project focuses on correctness under concurrent requests, transaction boundaries, reliable asynchronous messaging and failure handling with PostgreSQL, RabbitMQ and Stripe.

> **Project status:** active development. The Sales API, event-to-seat messaging flow, order expiration lifecycle, Stripe PaymentIntent initialization, Docker runtime and CI are implemented. Stripe webhook handling and post-payment fulfillment are the main remaining areas.

## Current scope

| Area | Current state |
|---|---|
| Sales API | Implemented |
| Authentication and authorization | Implemented with JWT and role-based access |
| Event catalog and event creation | Implemented |
| Asynchronous seat generation | Implemented with RabbitMQ |
| Transactional outbox | Implemented |
| Idempotent consumer, retries and DLQ | Implemented |
| Order creation and concurrent seat locking | Implemented |
| Order expiration and seat release | Implemented |
| Stripe PaymentIntent initialization | Implemented |
| Stripe webhooks and final payment state transitions | Planned / next development stage |
| Fulfillment worker | Spring Boot scaffold only |
| PDF and email delivery | Planned |
| Sales API Docker image | Implemented |
| GitHub Actions CI | Implemented |
| OpenAPI documentation | Planned |

## Technical focus

The project is primarily used to explore practical backend engineering problems rather than only CRUD functionality:

- preventing overbooking when orders are created concurrently,
- coordinating database transactions with message publishing,
- handling at-least-once message delivery and duplicates,
- recovering from publishing and consumer failures,
- designing transaction boundaries around an external payment provider,
- handling order expiration concurrently with payment initialization,
- using pessimistic locking, `NOWAIT` and `SKIP LOCKED` for explicit concurrency control,
- validating behavior against real PostgreSQL and RabbitMQ instances.

## Architecture

```text
Client
  |
  v
Sales API (Spring Boot)
  |
  |-- PostgreSQL
  |     |-- users, events, seats, orders, order_items
  |     |-- outbox_messages
  |     `-- processed_messages
  |
  |-- Stripe
  |     `-- PaymentIntent create / retrieve
  |
  `-- RabbitMQ
        |
        `-- create-event queue
              |
              v
        CreateEventMessageConsumer
              |
              `-- batch seat generation

Fulfillment Worker
  `-- scaffold only; post-payment ticket generation and delivery are planned
```

The repository is organized as a small monorepo:

```text
event-ticketing-platform/
├── sales-api/             # main application
├── fulfillment-worker/    # scaffold for future ticket fulfillment
├── .github/workflows/     # Sales API CI
├── compose.yaml           # dev infrastructure and prod runtime
├── .env.example
└── rabbitmq.example
```

## Implemented flows

### Event creation and asynchronous seat generation

```text
POST /api/v1/admin/event
        |
        v
Database transaction
  |-- Event
  `-- OutboxMessage(PENDING)
        |
        v
Commit
        |
        |-- immediate publish attempt
        `-- scheduled fallback scan
                |
                v
RabbitMQ publisher confirm / return
                |
                v
Create-event consumer
        |
        v
Database transaction
  |-- ProcessedMessage claim
  `-- Seats created in batches
```

The event and its outbox record are persisted in the same transaction. After commit, the publisher attempts immediate delivery, while a scheduled scanner provides a fallback for pending or stale messages.

Publisher confirms and returned-message handling update the outbox lifecycle:

```text
PENDING → PROCESSING → SENT
                 `----→ PENDING (retry)
                 `----→ FAILED
```

The consumer validates message metadata and payload, uses a database-backed idempotency key, and creates the processed marker together with all seats in one transaction.

### Order creation

Authenticated users can create an order for one or more events. The flow:

- verifies that all requested events exist,
- sorts event requests to acquire locks in a deterministic order,
- selects available seats using pessimistic database locking with `SKIP LOCKED`,
- changes selected seats to `LOCKED_FOR_CHECKOUT`,
- creates `OrderItem` records associated with the selected seats and events,
- creates a `PENDING` order with `PaymentStatus.NOT_INITIALIZED`,
- assigns an expiration time to the order.

Tickets are intentionally not created during checkout. Ticket creation belongs to the future post-payment fulfillment stage.

### Order expiration

Pending orders have a limited checkout lifetime. Expiration is handled in two ways:

- a scheduled batch scanner finds expired orders and uses `SKIP LOCKED` so one busy order does not block cleanup of other orders,
- payment initialization performs an immediate expiration check using fail-fast locking before communicating with Stripe.

When an eligible order expires:

```text
OrderStatus.PENDING → OrderStatus.EXPIRED
Seats: LOCKED_FOR_CHECKOUT → AVAILABLE
```

Expiration currently does not change `PaymentStatus`. Final payment-state reconciliation is intentionally deferred to the webhook/payment-lifecycle stage.

### Stripe PaymentIntent initialization

The Sales API currently supports creating or retrieving a Stripe PaymentIntent for an authenticated user's order:

```text
POST /api/v1/order/{order_id}/payment-intent
```

The create path is deliberately split around the external Stripe call:

```text
TX1
  |
  |-- validate ownership
  |-- validate Order / Payment state
  `-- check expiration
        |
        v
Commit
        |
        v
Stripe PaymentIntent CREATE
(same deterministic idempotency key for the same Order)
        |
        v
TX2
  |
  |-- re-check expiration
  |-- acquire Order lock with NOWAIT
  |-- revalidate local state
  `-- persist PaymentIntent ID, initialization time and PENDING payment state
        |
        v
Commit
        |
        v
Return client secret
```

No database transaction is kept open while waiting for Stripe. The client secret is returned only after the local TX2 update succeeds.

If the Order already has an initialized pending PaymentIntent, the API retrieves the existing Stripe object instead of creating another one and revalidates the local Order after the external call.

Stripe `idempotency_key_in_use` conflicts are retried a bounded number of times using the same idempotency key. Other Stripe communication failures are translated into controlled external-service errors.

Webhook verification and the final mapping of Stripe payment outcomes to local `PaymentStatus` / `OrderStatus` transitions are intentionally left for the next development stage.

## Reliability and concurrency decisions

| Problem | Current approach |
|---|---|
| Database write and broker publish cannot be one atomic transaction | Transactional Outbox Pattern |
| Broker accepts a message but the producer crashes before marking it as sent | At-least-once publishing with idempotent consumers |
| Duplicate or concurrent delivery | Atomic insert into `processed_messages` with a composite primary key |
| Temporary publisher failure | Outbox retry schedule and `PENDING` requeue |
| Publisher process stops while a message is `PROCESSING` | Recovery of stale processing records |
| Unroutable message | Publisher returns and `FAILED` outbox status |
| Invalid message contract or exhausted consumer retries | Dead-letter exchange and dedicated DLQ |
| Concurrent orders compete for the same seats | PostgreSQL pessimistic locking with `SKIP LOCKED` |
| Multi-event orders acquire locks in a different order | Deterministic event ordering before seat selection |
| Expired orders must release seats without blocking the whole cleanup batch | Scheduled cleanup with `SKIP LOCKED` |
| Payment flow races with another process working on the same Order | Immediate fail-fast locking with `NOWAIT` |
| Stripe call must not run inside a long database transaction | Separate TX1 → external Stripe call → TX2 flow |
| Stripe PaymentIntent creation may be retried | Deterministic Stripe idempotency key based on Order ID |
| Stripe succeeds but local TX2 cannot safely persist the result | Client secret is not returned; the request fails and can be retried |

## Implemented features

- user registration and login,
- JWT authentication,
- role-based authorization (`USER`, `ADMIN`),
- development-only account seeding,
- public event catalog with pagination and optional city filtering,
- event details with available-seat count,
- administrator-only event creation,
- asynchronous seat generation,
- transactional outbox with publisher confirms and returns,
- immediate publishing with scheduled fallback and stale-message recovery,
- idempotent RabbitMQ consumer,
- bounded consumer retries and dead-letter handling,
- order creation with concurrent seat protection,
- deterministic lock ordering for multi-event orders,
- `OrderItem` model for pre-payment order contents,
- order expiration with automatic seat release,
- scheduled expiration cleanup with `SKIP LOCKED`,
- fail-fast order claims with `NOWAIT`,
- Stripe PaymentIntent create/retrieve flow,
- deterministic Stripe idempotency keys and bounded conflict retries,
- separate local transactions before and after the Stripe call,
- batch inserts for seat generation,
- Flyway database migrations,
- RFC 7807-style error responses through Spring `ProblemDetail`,
- Bean Validation and JPA auditing,
- separate `dev`, `test` and `prod` configuration profiles,
- multi-stage non-root Docker image for the Sales API,
- Docker Compose `dev` and `prod` profiles,
- GitHub Actions CI running the Sales API verification suite.

## API overview

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `POST` | `/auth/register` | Public | Register a user |
| `POST` | `/auth/login` | Public | Receive a JWT bearer token |
| `GET` | `/api/v1/event` | Public | List events; supports `city` and `page` parameters |
| `GET` | `/api/v1/event/{eventID}` | Public | Get event details and available-seat count |
| `POST` | `/api/v1/admin/event` | `ADMIN` | Create an event and trigger asynchronous seat generation |
| `POST` | `/api/v1/order` | Authenticated | Create a pending order and lock seats for checkout |
| `POST` | `/api/v1/order/{order_id}/payment-intent` | Authenticated | Create or retrieve a Stripe PaymentIntent for an Order |

## Testing

The project contains unit tests and integration tests.

Unit tests use JUnit 5, Mockito and AssertJ. Integration tests run the Spring context against real PostgreSQL and RabbitMQ containers provided by Testcontainers. Stripe SDK behavior is isolated in automated tests; real Stripe sandbox calls are used only for manual verification.

Covered scenarios include:

- concurrent orders cannot overbook available seats,
- multi-event orders acquire seat locks consistently,
- non-expired orders remain valid for checkout,
- expired orders transition to `EXPIRED` and release their seats,
- payment-state updates fail fast when another transaction holds the Order lock,
- immediate expiration checks fail safely when the Order cannot be claimed,
- scheduled expiration cleanup skips locked orders and processes other expired orders,
- successful payment initialization state is committed to PostgreSQL,
- Stripe PaymentIntent create and retrieve orchestration,
- bounded retry behavior for Stripe idempotency conflicts,
- Stripe provider failures do not update local payment state,
- sequential and concurrent duplicate message delivery is idempotent,
- successful RabbitMQ publishing and consumption,
- invalid message type and payload are dead-lettered,
- retry exhaustion rolls back database work and moves the message to the DLQ,
- full HTTP → Event → Outbox → RabbitMQ → Consumer → Seats flow.

Run the Sales API test suite with:

```bash
cd sales-api
./mvnw verify
```

Docker must be running because the integration tests start PostgreSQL and RabbitMQ containers.

### Continuous integration

GitHub Actions runs the Sales API verification suite for pushes to `main` and pull requests targeting `main`:

```text
checkout
→ Java 25
→ Maven dependency cache
→ ./mvnw verify
```

## Tech stack

### Application

- Java 25
- Spring Boot 4.1
- Spring Web MVC
- Spring Data JPA / Hibernate
- Spring Security
- Spring AMQP
- Bean Validation
- Stripe Java SDK
- JJWT
- virtual threads

### Data and infrastructure

- PostgreSQL 17
- RabbitMQ 4
- Flyway
- Docker and Docker Compose
- GitHub Actions
- Maven Wrapper

### Testing

- JUnit 5
- Mockito
- AssertJ
- Testcontainers
- Spring Boot Test
- MockMvc

## Running locally

### Requirements

- Java 25
- Docker with Docker Compose
- Stripe sandbox account / test secret key for the payment-initialization endpoint

A global Maven installation is not required because both applications include Maven Wrapper.

### 1. Clone the repository

```bash
git clone https://github.com/kacperJY/event-ticketing-platform.git
cd event-ticketing-platform
```

### 2. Prepare local infrastructure configuration

Copy the root configuration examples:

```bash
cp .env.example .env
cp rabbitmq.example rabbitmq
```

The `dev` profile runs the Sales API directly on the host and connects to PostgreSQL and RabbitMQ through `localhost`.

For the default development configuration, set the local infrastructure credentials in `.env` to match `application-dev.yaml`:

```properties
POSTGRES_DB=event-ticketing-platform-db
POSTGRES_USER=admin
POSTGRES_PASSWORD=admin

RABBITMQ_DEFAULT_USER=admin
RABBITMQ_DEFAULT_PASS=admin
RABBITMQ_NODE_PORT=5672
RABBITMQ_MANAGEMENT_TCP_PORT=15672
RABBITMQ_CONFIG_FILE=/config/rabbitmq
```

Other production-oriented values present in `.env.example` are used by the `prod` Compose profile and are not required by the locally running DEV JVM.

For RabbitMQ, the `rabbitmq` file should expose the same ports, for example:

```properties
listeners.tcp.default=5672
management.tcp.port=15672
```

### 3. Prepare local application secrets

Create the following ignored file inside `sales-api/`:

```text
sales-api/local.secrets.properties
```

Example structure:

```properties
local.secret.jwt-secret-key=<base64-encoded-local-jwt-secret>
local.secret.stripe.secret-key=<stripe-test-secret-key>
```

The DEV profile imports this file from the filesystem. It is intentionally stored outside `src/main/resources` so local secrets are not packaged into the application JAR or Docker image.

Do not commit this file.

### 4. Start PostgreSQL and RabbitMQ for development

From the repository root:

```bash
docker compose --profile dev up -d
```

This starts PostgreSQL and RabbitMQ only. The Sales API itself remains outside Docker for faster local development.

Default local endpoints:

| Service | Address |
|---|---|
| PostgreSQL | `localhost:5432` |
| RabbitMQ AMQP | `localhost:5672` |
| RabbitMQ Management UI | `http://localhost:15672` |

### 5. Start the Sales API

```bash
cd sales-api
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The `dev` profile seeds local accounts for development purposes only:

| Role | Email | Password |
|---|---|---|
| `USER` | `user@gmail.com` | `User123` |
| `ADMIN` | `admin@gmail.com` | `Admin123` |

### 6. Stop local infrastructure

From the repository root:

```bash
docker compose --profile dev down
```

Use `docker compose --profile dev down -v` only when the local PostgreSQL and RabbitMQ volumes should also be removed.

## Running the production-style Docker stack

The `prod` Compose profile builds the Sales API image and runs the application together with PostgreSQL and RabbitMQ.

Fill the production-oriented values in `.env`, including:

- `DB_URL`, `DB_USER`, `DB_PASSWORD`,
- `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`,
- `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS`,
- `RABBITMQ_HOST`, `RABBITMQ_NODE_PORT`,
- `JWT_SECRET_KEY`,
- `STRIPE_SECRET_KEY`,
- `SERVER_PORT`.

Then run:

```bash
docker compose --profile prod up --build -d
```

The Sales API container starts with the `prod` Spring profile and receives runtime configuration through environment variables.

To stop the stack:

```bash
docker compose --profile prod down
```

## Roadmap

1. Stripe webhook verification, event idempotency and final payment/order state transitions.
2. Fulfillment worker consuming confirmed payments through RabbitMQ and generating tickets.
3. PDF ticket generation and email delivery.
4. Final production-hardening review, remaining bug fixes and documentation cleanup.
5. OpenAPI/Swagger documentation as an optional presentation improvement.

## Project status and intent

This is an actively developed portfolio project, not a production deployment or a finished commercial ticketing product.

Its purpose is to build and document a realistic backend workflow involving transactions, concurrency, asynchronous messaging, external payment integration and failure handling. Planned features are kept separate from implemented functionality so that the repository reflects the actual state of the code.