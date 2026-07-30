# 🎫 Event Ticketing Platform

![Status](https://img.shields.io/badge/status-active_development-orange)
![Java](https://img.shields.io/badge/Java-25-blue)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-brightgreen)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-4-FF6600)

Backend portfolio project for event management, seat inventory and order creation. The current implementation focuses on correctness under concurrent requests and reliable asynchronous processing with PostgreSQL and RabbitMQ.

> **Project status:** active development. The Sales API and the event-to-seat messaging flow are implemented and tested. Stripe payments, ticket fulfillment/PDF generation, the application Docker image, CI and OpenAPI documentation are not implemented yet.

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
| Payments | Planned — orders currently remain `PENDING` |
| Fulfillment worker | Spring Boot scaffold only |
| PDF and email delivery | Planned |
| CI and application image | Planned |

## Technical focus

The project is primarily used to explore practical backend engineering problems rather than only CRUD functionality:

- preventing overbooking when orders are created concurrently,
- coordinating database transactions with message publishing,
- handling at-least-once message delivery and duplicates,
- recovering from publishing and consumer failures,
- validating behavior against real PostgreSQL and RabbitMQ instances.

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
├── sales-api/             # implemented application
├── fulfillment-worker/    # scaffold for future ticket fulfillment
├── compose.yaml           # PostgreSQL and RabbitMQ for local development
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
- idempotent RabbitMQ consumer,
- bounded consumer retries and dead-letter handling,
- order creation with concurrent seat protection,
- batch inserts for seat generation,
- Flyway database migrations,
- RFC 7807-style error responses through Spring `ProblemDetail`,
- Bean Validation and JPA auditing,
- separate `dev`, `test` and `prod` configuration profiles,
- Docker Compose infrastructure for PostgreSQL and RabbitMQ.

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
- Docker and Docker Compose
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

Fill `.env` with local PostgreSQL, RabbitMQ and JWT values. The current `dev` profile expects the PostgreSQL database name `event-ticketing-platform-db`.

The default RabbitMQ settings used by the `dev` profile are:

| Setting | Default value |
|---|---|
| Host | `localhost` |
| AMQP port | `5672` |
| Management UI port | `15672` |
| Username | `admin` |
| Password | `admin` |

The management UI is available at `http://localhost:15672` after RabbitMQ starts. These credentials are intended only for local development and must not be used in production. All values can be overridden with environment variables.

For a standard local RabbitMQ setup, `rabbitmq` should define the same ports as `.env`, for example:

```properties
listeners.tcp.default=5672
management.tcp.port=15672
```

Docker Compose reads the root `.env` file automatically. With the `dev` profile, the Sales API can use the default RabbitMQ credentials listed above and the development-only JWT fallback configured in `application-dev.yaml`. Environment variables still override those defaults. When using shell environment variables, use `JWT_SECRET_KEY` for the Spring property `jwt-secret-key`.

### 3. Start PostgreSQL and RabbitMQ

```bash
docker compose up -d
```

The compose file starts:

- PostgreSQL,
- RabbitMQ with the management plugin.

### 4. Start the Sales API

```bash
cd sales-api
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The `dev` profile seeds local accounts for development purposes only:

| Role | Email | Password |
|---|---|---|
| `USER` | `user@gmail.com` | `User123` |
| `ADMIN` | `admin@gmail.com` | `Admin123` |

### 5. Stop local infrastructure

```bash
docker compose down
```

Use `docker compose down -v` only when the local database and RabbitMQ volumes should also be removed.

## Roadmap

1. GitLab CI pipeline for build and automated tests.
2. Sales API Docker image and production-profile cleanup.
3. Stripe integration, including webhook verification and idempotent payment handling.
4. Fulfillment worker communicating through RabbitMQ after payment confirmation and generating ticket PDFs.
5. Final production-hardening review, bug fixing and documentation cleanup.
6. OpenAPI/Swagger documentation (optional, but planned as a presentation improvement).

## Project status and intent

This is an actively developed portfolio project, not a production deployment or a finished commercial ticketing product.

Its purpose is to build and document a realistic backend workflow involving transactions, concurrency, asynchronous messaging and failure handling. Planned features are kept separate from implemented functionality so that the repository reflects the actual state of the code.
