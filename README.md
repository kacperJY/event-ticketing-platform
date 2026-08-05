# 🎫 Event Ticketing Platform

![Status](https://img.shields.io/badge/status-active_development-orange)
![Java](https://img.shields.io/badge/Java-25-blue)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-brightgreen)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-4-FF6600)

Backend portfolio project for event management, seat inventory and order creation. The current implementation focuses on correctness under concurrent requests and reliable asynchronous processing with PostgreSQL and RabbitMQ.

> **Project status:** active development. The Sales API, event-to-seat messaging flow, automated verification pipeline and containerized runtime are implemented and tested. Stripe payments, ticket fulfillment/PDF generation and OpenAPI documentation are not implemented yet.

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
| Automated verification | Implemented with GitHub Actions and `mvn verify` |
| Sales API Docker image | Implemented |
| Docker Compose runtime profiles | Implemented for `dev` and `prod` |
| Payments | Planned — orders currently remain `PENDING` |
| Fulfillment worker | Spring Boot scaffold only |
| PDF and email delivery | Planned |

## Technical focus

The project is primarily used to explore practical backend engineering problems rather than only CRUD functionality:

- preventing overbooking when orders are created concurrently,
- coordinating database transactions with message publishing,
- handling at-least-once message delivery and duplicates,
- recovering from publishing and consumer failures,
- coordinating concurrent outbox publishers,
- validating behavior against real PostgreSQL and RabbitMQ instances,
- building and running the application in a reproducible containerized environment.

## Architecture

```text
Client
  |
  v
Sales API (Spring Boot)
  |
  |-- PostgreSQL
  |     |-- users, events, seats, orders, tickets
  |     |-- outbox_messages
  |     `-- processed_messages
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
  `-- scaffold only; post-payment ticket delivery is planned
```

The repository is organized as a small monorepo:

```text
event-ticketing-platform/
├── sales-api/             # implemented application and Docker image
├── fulfillment-worker/    # scaffold for future ticket fulfillment
├── compose.yaml           # development infrastructure and containerized runtime
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

Concurrent publishers claim pending outbox records with PostgreSQL pessimistic locking and `NOWAIT`. If another publisher has already locked or processed the message, the immediate publishing attempt is skipped and processing remains with the publisher that successfully claimed it.

Publisher confirms and returned-message handling update the outbox lifecycle:

```text
PENDING → PROCESSING → SENT
                 `----→ PENDING (retry)
                 `----→ FAILED
```

The consumer validates message metadata and payload, uses a database-backed idempotency key, and creates the processed marker together with all seats in one transaction.

### Order creation

Authenticated users can create an order for one or more events. The current flow:

- verifies that all requested events exist,
- sorts event requests to acquire locks in a deterministic order,
- selects available seats using pessimistic database locking,
- changes selected seats to `LOCKED_FOR_CHECKOUT`,
- creates ticket records and a `PENDING` order.

Payment capture and the transition to a completed purchase are intentionally not implemented yet.

## Reliability and concurrency decisions

| Problem | Current approach |
|---|---|
| Database write and broker publish cannot be one atomic transaction | Transactional Outbox Pattern |
| Broker accepts a message but the producer crashes before marking it as sent | At-least-once publishing with idempotent consumers |
| Duplicate or concurrent delivery | Atomic insert into `processed_messages` with a composite primary key |
| Temporary publisher failure | Outbox retry schedule and `PENDING` requeue |
| Publisher process stops while a message is `PROCESSING` | Recovery of stale processing records |
| Multiple publishers attempt to claim the same outbox message | PostgreSQL pessimistic locking with `NOWAIT` and graceful claim-loss handling |
| Unroutable message | Publisher returns and `FAILED` outbox status |
| Invalid message contract or exhausted consumer retries | Dead-letter exchange and dedicated DLQ |
| Concurrent orders compete for the same seats | PostgreSQL pessimistic locking with `SKIP LOCKED` |
| Multi-event orders acquire locks in a different order | Deterministic event ordering before seat selection |

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
- concurrent outbox claim handling,
- idempotent RabbitMQ consumer,
- bounded consumer retries and dead-letter handling,
- order creation with concurrent seat protection,
- batch inserts for seat generation,
- Flyway database migrations,
- RFC 7807-style error responses through Spring `ProblemDetail`,
- Bean Validation and JPA auditing,
- separate `dev`, `test` and `prod` configuration profiles,
- multi-stage Sales API Docker image running as a non-root user,
- Docker Compose profiles for development infrastructure and the full containerized runtime,
- GitHub Actions verification pipeline.

## API overview

| Method | Endpoint | Access | Purpose |
|---|---|---|---|
| `POST` | `/auth/register` | Public | Register a user |
| `POST` | `/auth/login` | Public | Receive a JWT bearer token |
| `GET` | `/api/v1/event` | Public | List events; supports `city` and `page` parameters |
| `GET` | `/api/v1/event/{eventID}` | Public | Get event details and available-seat count |
| `POST` | `/api/v1/admin/event` | `ADMIN` | Create an event and trigger asynchronous seat generation |
| `POST` | `/api/v1/order` | Authenticated | Create a pending order and lock seats for checkout |

## Testing

The project contains unit tests and integration tests.

Unit tests use JUnit 5, Mockito and AssertJ. Integration tests run the Spring context against real PostgreSQL and RabbitMQ containers provided by Testcontainers.

Covered integration scenarios include:

- concurrent orders cannot overbook available seats,
- multi-event orders acquire locks consistently,
- sequential and concurrent duplicate message delivery is idempotent,
- concurrent outbox publishers cannot claim the same pending message,
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

The same verification command is executed automatically by GitHub Actions for pull requests targeting `main` and for pushes to `main`.

## Tech stack

### Application

- Java 25
- Spring Boot 4.1
- Spring Web MVC
- Spring Data JPA / Hibernate
- Spring Security
- Spring AMQP
- Bean Validation
- JJWT
- virtual threads

### Data and infrastructure

- PostgreSQL 17 for local development
- RabbitMQ 4
- Flyway
- multi-stage Docker image for the Sales API
- Docker Compose `dev` and `prod` profiles
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

A global Maven installation is not required because both applications include Maven Wrapper.

### 1. Clone the repository

```bash
git clone https://github.com/kacperJY/event-ticketing-platform.git
cd event-ticketing-platform
```

### 2. Prepare environment files

Copy the examples:

```bash
cp .env.example .env
cp rabbitmq.example rabbitmq
```

The `rabbitmq` file is intentionally not tracked and must be created locally from `rabbitmq.example` before starting Docker Compose.

The copied RabbitMQ configuration already contains the standard ports:

```properties
listeners.tcp.default=5672
management.tcp.port=15672
```

Fill `.env` with local PostgreSQL, RabbitMQ and JWT values.

For the standard local setup, use:

```text
POSTGRES_DB=event-ticketing-platform-db
POSTGRES_USER=admin
POSTGRES_PASSWORD=admin

DB_USER=admin
DB_PASSWORD=admin

RABBITMQ_DEFAULT_USER=admin
RABBITMQ_DEFAULT_PASS=admin
```

The `dev` Spring profile connects to the local PostgreSQL instance using the `admin` / `admin` credentials.

For the containerized `prod` profile:

- `DB_USER` must identify the PostgreSQL user created through `POSTGRES_USER`,
- `DB_PASSWORD` must match `POSTGRES_PASSWORD`,
- `JWT_SECRET_KEY` must contain a valid Base64-encoded signing key,
- RabbitMQ credentials must match the values used to initialize the broker.

The standard local RabbitMQ configuration is:

| Setting | Default value |
|---|---|
| Host used by the local application | `localhost` |
| Host used inside Compose | `event-ticketing-platform-message-broker` |
| AMQP port | `5672` |
| Management UI port | `15672` |
| Username | `admin` |
| Password | `admin` |

The management UI is available at `http://localhost:15672` after RabbitMQ starts. These credentials are intended only for local development and must not be used in a real production environment.

Docker Compose reads the root `.env` file automatically.

### 3. Start development infrastructure

```bash
docker compose --profile dev up -d
```

The `dev` Compose profile starts:

- PostgreSQL,
- RabbitMQ with the management plugin.

### 4. Start the Sales API locally

```bash
cd sales-api
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The `dev` profile seeds local accounts for development purposes only:

| Role | Email | Password |
|---|---|---|
| `USER` | `user@gmail.com` | `User123` |
| `ADMIN` | `admin@gmail.com` | `Admin123` |

### 5. Stop development infrastructure

From the repository root:

```bash
docker compose --profile dev down
```

Use the `-v` option only when the local PostgreSQL and RabbitMQ volumes should also be removed.

## Running the containerized application

The `prod` Compose profile builds the Sales API image and starts the complete environment:

```bash
docker compose --profile prod up --build
```

This profile starts:

- PostgreSQL,
- RabbitMQ,
- the Sales API using the `prod` Spring profile.

The Sales API receives its database, RabbitMQ, JWT and server configuration through environment variables defined in `.env`.

The `prod` Spring profile is not tied to Docker. The packaged application can also be started as a regular JVM process when all required environment variables are provided externally.

Stop the containerized environment with:

```bash
docker compose --profile prod down
```

## Roadmap

1. Stripe integration, including webhook verification and idempotent payment handling.
2. Fulfillment worker communicating through RabbitMQ after payment confirmation and generating ticket PDFs.
3. Final production-hardening review, bug fixing and documentation cleanup.
4. OpenAPI/Swagger documentation (optional, but planned as a presentation improvement).

## Project status and intent

This is an actively developed portfolio project, not a production deployment or a finished commercial ticketing product.

Its purpose is to build and document a realistic backend workflow involving transactions, concurrency, asynchronous messaging, failure handling and containerized application delivery. Planned features are kept separate from implemented functionality so that the repository reflects the actual state of the code.