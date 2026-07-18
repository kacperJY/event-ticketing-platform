# 🎫 Event Ticketing Platform

![Status](https://img.shields.io/badge/Status-Work_in_Progress-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-brightgreen)
![Java](https://img.shields.io/badge/Java-25-blue)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-blue)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-4-orange)

> **Note:** This project is currently under active development. The README reflects the current implementation and separates implemented features from planned functionality.

---

# 📖 About

Backend platform for managing events, ticket reservations and order processing.

The project is being developed as a portfolio project with a focus on backend engineering concepts such as:

- concurrent seat reservation,
- transaction management,
- authentication and authorization,
- asynchronous messaging,
- database migrations,
- and integration with external services.

The system currently consists primarily of the **Sales API**, while additional responsibilities such as ticket fulfillment are planned to be handled by separate asynchronous workers.

---

# 🏗️ Architecture

The project currently consists of:

### Sales API

Main Spring Boot application responsible for:

- user registration and authentication,
- JWT-based authorization,
- role-based access control,
- event creation and browsing,
- asynchronous seat generation,
- seat availability management,
- order creation and checkout.

### PostgreSQL

Primary relational database used for storing:

- users,
- events,
- seats,
- orders,
- tickets.

Database schema changes are managed using **Flyway migrations**.

### RabbitMQ

Used for asynchronous communication inside the platform.

Currently, event creation publishes a message that triggers asynchronous seat generation.

### Fulfillment Worker

Separate Spring Boot application scaffold intended to handle post-purchase operations such as:

- ticket PDF generation,
- email delivery.

This component is currently under development.

### Docker Compose

Provides local infrastructure for:

- PostgreSQL,
- RabbitMQ.

---

# ✨ Implemented Features

- JWT authentication
- Role-based authorization (`USER`, `ADMIN`)
- User registration and login
- Development-only default account seeding
- Event creation restricted to administrators
- Event catalog with pagination
- Event detail and seat availability lookup
- Order creation
- Pessimistic database locking for seat selection
- Asynchronous seat generation using RabbitMQ
- Batch seat insertion
- Flyway database migrations
- Global exception handling using `ProblemDetail`
- Bean Validation
- JPA Auditing
- Environment-based configuration
- Docker Compose development environment
- Unit tests with JUnit 5 and Mockito

---

# 💻 Tech Stack

- Java 25
- Spring Boot 4.1
- Spring Web MVC
- Spring Data JPA
- Spring Security
- Spring AMQP
- Bean Validation
- PostgreSQL 17
- RabbitMQ 4
- Flyway
- Docker & Docker Compose
- Maven
- JUnit 5
- Mockito

Planned / in progress:

- Testcontainers
- Stripe API
- PDF ticket generation
- Email ticket delivery
- GitHub Actions CI

---

# 🗺️ Roadmap

## Completed

- ✅ Project initialization
- ✅ Docker development infrastructure
- ✅ Domain model
- ✅ Flyway database migrations
- ✅ JWT authentication
- ✅ Role-based authorization
- ✅ Global exception handling
- ✅ Event creation
- ✅ Event catalog
- ✅ RabbitMQ integration
- ✅ Asynchronous seat generation
- ✅ Basic order and checkout flow
- ✅ Pessimistic locking for seat selection

## In Progress

- 🚧 Concurrency integration tests with PostgreSQL and Testcontainers
- 🚧 Improving concurrent order processing
- 🚧 Reliable transactional event publishing
- 🚧 Idempotent RabbitMQ consumers
- 🚧 Request validation improvements

## Planned

- ⏳ Stripe payment integration
- ⏳ Transactional Outbox Pattern
- ⏳ Ticket PDF generation
- ⏳ Email ticket delivery
- ⏳ Fulfillment worker implementation
- ⏳ GitHub Actions CI pipeline
- ⏳ Additional integration tests

---

# 🚀 Running Locally

## Requirements

- Java 25
- Docker
- Maven

## Development Mode

1. Clone the repository.

2. Copy `.env.example` to `.env` and configure the required application environment variables, including database, RabbitMQ and JWT settings.

3. Create the local RabbitMQ configuration file `rabbitmq` based on `rabbitmq.example`, using the filename expected by Docker Compose.

The RabbitMQ configuration currently defines the ports used by the local RabbitMQ container. RabbitMQ connection settings used by the application are configured through environment variables.

4. Start the infrastructure:

```bash
docker compose up -d
```

This starts:

- PostgreSQL
- RabbitMQ

5. Start the `sales-api` application using Maven or your IDE with the `dev` profile.

The development profile creates local test accounts for development purposes only.

---

# 🔐 Concurrency

One of the main technical goals of the project is preventing ticket overbooking when multiple users attempt to purchase seats for the same event concurrently.

The current implementation uses pessimistic database locking while selecting available seats.

The concurrency model is currently being validated and improved using integration tests running against a real PostgreSQL database.

---

# 📌 Current Technical Improvements

The current development phase focuses on improving reliability and production-readiness before adding additional business features.

Current priorities include:

- concurrency integration testing,
- deterministic database locking,
- transaction-safe RabbitMQ publishing,
- idempotent message processing,
- stronger request validation,
- CI automation,
- documentation and code cleanup.

---

# 📄 Project Status

This project is actively developed as a backend portfolio project.

The goal is not only to implement the business functionality of a ticketing platform, but also to explore real-world backend engineering challenges involving concurrency, transactions, asynchronous messaging and system reliability.
