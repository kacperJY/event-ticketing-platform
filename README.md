# 🎫 Event Ticketing Platform

![Status](https://img.shields.io/badge/status-core_project_complete-brightgreen)
![Java](https://img.shields.io/badge/Java-25-blue)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-brightgreen)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-4-FF6600)

Backend portfolio project for event management, seat inventory, orders, payments and refunds. The project focuses on concurrency, transaction boundaries, reliable messaging and integration with an external payment provider.

> **Status:** the planned end-to-end flow is complete: checkout, payment, refunds, reliable publication of paid orders and ticket PDF delivery by email. Further work is maintenance and optional improvements (see [Future improvements](#future-improvements)).

## Highlights

- **Reliable messaging:** transactional outbox with broker confirms and scheduled recovery on the producer side; database-backed deduplication, bounded retries and a DLQ on the consumer side.
- **Payments done carefully:** Stripe calls run outside database transactions with deterministic idempotency keys; signed webhooks go through a transactional inbox.
- **Concurrency:** pessimistic seat locking with `SKIP LOCKED`, order locks and an expiration scheduler that cooperates with late payments (automatic refunds).
- **Two services, two databases:** the Sales API and the fulfillment worker communicate only through RabbitMQ and never share a database.
- **Verified:** unit tests plus PostgreSQL/RabbitMQ integration tests (Testcontainers), run in CI for both services.

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
```

Design details, per-flow guarantees and known limitations: **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

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

## Stack

Java 25, Spring Boot 4.1.0, Spring MVC, Security, Data JPA/Hibernate, AMQP, Bean Validation, Flyway, Stripe Java SDK 33.4.0, JJWT, PostgreSQL and RabbitMQ. The application enables virtual threads. Tests use JUnit, Mockito, AssertJ, MockMvc and Testcontainers.

## Quick start

Requirements: Java 25, Docker with Compose, a Stripe sandbox and the [Stripe CLI](https://docs.stripe.com/stripe-cli). SMTP credentials are needed only for the worker's email delivery. Maven is supplied by the wrapper.

```bash
git clone https://github.com/kacperJY/event-ticketing-platform.git
cd event-ticketing-platform
cp .env.example .env
cp sales_api.env.example sales_api.env
cp fulfillment_worker.env.example fulfillment_worker.env
cp rabbitmq.example rabbitmq
# fill in the infrastructure credentials in .env (DEV: admin/admin, see docs/LOCAL_DEVELOPMENT.md)

docker compose --profile dev up -d        # PostgreSQL x2 + RabbitMQ
```

Create `sales-api/local.secrets.properties` (JWT secret, Stripe test key, webhook secret from `stripe listen`) and start the Sales API:

```bash
cd sales-api
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Secrets, the Stripe CLI listener, the worker's SMTP settings and the fully containerized `prod` profile are described step by step in **[docs/LOCAL_DEVELOPMENT.md](docs/LOCAL_DEVELOPMENT.md)**.

### Try it

The DEV profile seeds `admin@gmail.com` / `Admin123` and `user@gmail.com` / `User123`. The login endpoint returns the ready-to-use `Authorization` header value.

```bash
# log in as the seeded admin
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@gmail.com","password":"Admin123"}')

# create an event (any future date); seats are generated asynchronously via RabbitMQ
curl -X POST http://localhost:8080/api/v1/admin/event \
  -H "Authorization: $TOKEN" -H 'Content-Type: application/json' \
  -d '{
        "name": "Demo concert",
        "description": "Local demo event",
        "eventCategory": "CONCERT",
        "location": {"country": "Poland", "city": "Warsaw", "street": "Main St", "no": "1", "postalCode": "00-001"},
        "seatPrice": 100,
        "eventDate": "2027-06-01T18:00:00Z",
        "placesNumber": 50
      }'

# public catalog and event details (with available-seat count)
curl 'http://localhost:8080/api/v1/event?city=Warsaw&page=1'
curl http://localhost:8080/api/v1/event/1
```

Ordering and paying for tickets (`POST /api/v1/order`, then `/payment-intent`, then confirming the PaymentIntent with the Stripe CLI) is walked through in [Manual payment and refund checks](docs/LOCAL_DEVELOPMENT.md#manual-payment-and-refund-checks).

## Tests

Integration tests need a running Docker daemon (Testcontainers starts isolated PostgreSQL and RabbitMQ).

```bash
cd sales-api          && ./mvnw clean verify
cd fulfillment-worker && ./mvnw clean verify
```

What is covered, and how CI runs it: [docs/LOCAL_DEVELOPMENT.md#automated-tests](docs/LOCAL_DEVELOPMENT.md#automated-tests).

## Documentation

| Document | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Flows (events, checkout, payments, webhooks, fulfillment, refunds), reliability boundaries, known limitations, migration policy, ticket delivery |
| [docs/LOCAL_DEVELOPMENT.md](docs/LOCAL_DEVELOPMENT.md) | Full local setup, Stripe CLI, manual payment/refund checks, Docker `prod` profile, automated tests |

## Future improvements

- Ongoing maintenance and focused refactoring of naming, configuration and implementation details.
- OpenAPI/Swagger documentation for the Sales API.
- Query and index optimization based on measured workload.
- Moving RabbitMQ queue, exchange and binding declarations into shared external broker configuration.
- Event-specific seat-number prefixes instead of the current fixed `SEAT - [number]` pattern.
- Reconciliation and operator recovery for payments, refunds and older fulfillment requests.
- A separate cleanup scheduler that scans the PDF directory and removes files no longer used by any active delivery attempt.

This is a learning and portfolio project. Its planned scope now includes payment, refunds, reliable fulfillment publication, idempotent worker intake, PDF generation and email delivery. The project is considered complete at this stage.
