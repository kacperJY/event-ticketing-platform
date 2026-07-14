# 🎫 Event Ticketing System

![Status](https://img.shields.io/badge/Status-Work_in_Progress-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-brightgreen)
![Java](https://img.shields.io/badge/Java-25-blue)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Ready-blue)

> **Note:** This project is currently under active development. The documentation describes both the implemented features and the planned architecture.

---

# 📖 About

Backend application for managing event ticket reservations and sales.

The project is being developed as a **modular monolith**, with some components (such as PDF generation) planned to be extracted into asynchronous workers in the future.

The main goal of the project is to explore topics such as concurrent seat reservations, authentication, event-driven communication and integration with external services.

---

# 🏗️ Architecture

Current architecture consists of:

- **Sales API** – user authentication, event management, reservations and order processing.
- **PostgreSQL** – application database.
- **Docker Compose** – local development environment.

Planned components:

- **Ticket PDF Worker** – asynchronous ticket generation using RabbitMQ.
- **Stripe Integration** – payment processing via webhooks.

---

# ✨ Implemented Features

- JWT authentication and authorization
- Role-based access control
- User registration and login
- Global exception handling using **ProblemDetail (RFC 7807)**
- JPA Auditing
- Bean Validation
- Environment-based configuration (`.env`)
- Docker Compose support
- Unit testing with JUnit 5 and Mockito

---

# 💻 Tech Stack

- Java 25
- Spring Boot 3
- Spring Web
- Spring Data JPA
- Spring Security
- Bean Validation
- PostgreSQL
- Docker & Docker Compose
- Maven
- JUnit 5
- Mockito
- Testcontainers *(in progress)*

Planned:

- RabbitMQ
- Stripe API

---

# 🗺️ Roadmap

## Completed

- ✅ Project initialization
- ✅ Docker environment
- ✅ Domain model
- ✅ JWT authentication
- ✅ Role-based authorization
- ✅ Global exception handling
- ✅ JPA Auditing

## In Progress

- 🚧 Integration tests with Testcontainers
- 🚧 Seat reservation logic
- 🚧 Shopping cart & checkout

## Planned

- ⏳ Stripe integration
- ⏳ RabbitMQ integration
- ⏳ PDF ticket generation worker

---

# 🚀 Running locally

## Requirements

- Java 25
- Docker
- Maven

## 🛠️ How to run locally (Development Mode)
> **Note:** Full containerization (Application Dockerfile, RabbitMQ, Mail Server) is planned for the upcoming phases. Currently, only the PostgreSQL database is containerized for local development.

1. Clone the repository.
2. Copy the `.env.example` file and rename it to `.env`, then fill in your local variables (e.g., JWT secret, DB credentials).
3. Start the PostgreSQL database container:
   
   ```bash
   docker compose up -d

---

# 📌 Planned Improvements

- Optimistic/Pessimistic locking for seat reservations
- Asynchronous PDF generation
- Payment processing with Stripe
- Event-driven communication with RabbitMQ
- Additional integration tests

