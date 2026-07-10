# 🎫 Event Ticketing System (Sales API)

![Status](https://img.shields.io/badge/Status-Work_in_Progress-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-brightgreen)
![Java](https://img.shields.io/badge/Java-25-blue)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Ready-blue)

> **Note:** This project is currently in active development. The documentation reflects both the implemented features and the target architecture.

## 📖 About The Project
This project is a scalable backend system for event ticket reservations and sales. It is designed with modern architectural patterns to handle concurrent booking requests and ensure data consistency. The core domain focuses on isolating resource state (seats/tickets) from the transaction process, providing a robust foundation for a high-traffic e-commerce environment.

## 🏗️ Target Architecture
The system is being built with a microservices-oriented mindset, currently starting as a modular monolith with plans to extract workers.
* **Sales API (Core):** Handles user authentication, domain logic, seat reservations, and payment processing.
* **Ticket PDF Generator (Planned):** An asynchronous worker responsible for generating PDF tickets, communicating with the main API via RabbitMQ to offload the main thread.
* **Payment Gateway:** Integration with Stripe for secure transaction handling via webhooks.

## 🚀 Current Status & Implemented Features
At this stage, the foundational infrastructure, core domain model, and security layer have been established and merged into the main branch:
* **Domain Modeling:** Core entities designed with DDD principles, separating resource availability from business transactions.
* **Data Integrity:** Configured JPA Auditing to track entity lifecycles (creation/modification timestamps).
* **Stateless Security:** Fully implemented JWT (JSON Web Token) authentication infrastructure.
  * Secured endpoints with role-based access control.
  * Exposed public endpoints for webhooks and authentication.
  * Extracted JWT secrets to environment variables (`.env`).
* **Authentication Flow:** Ready-to-use `/auth/register` and `/auth/login` endpoints with secure BCrypt password hashing.
* **Global Exception Handling:** Centralized error management using Spring Boot 3 `ProblemDetail` (RFC 7807 standard) for clear, structured API error responses (including validation and business logic constraints).
* **Testing Strategy:** 
  * Strict business logic and DTO validation unit testing using Mockito, `ArgumentCaptor`, and `ValidatorFactory`.
  * Configured integration testing environment leveraging **Testcontainers** for isolated, real-database interactions.
* **Containerization:** Environment setup using Docker and `docker compose` for PostgreSQL database initialization.

## 💻 Tech Stack
* **Language:** Java 25
* **Framework:** Spring Boot 3 (Spring Web, Spring Data JPA, Spring Security, Bean Validation)
* **Database:** PostgreSQL
* **Messaging:** RabbitMQ (Planned)
* **External APIs:** Stripe (Planned)
* **Security:** JWT (jjwt library), BCrypt
* **Infrastructure:** Maven, Docker, Docker Compose
* **Testing:** JUnit 5, Mockito, AssertJ, Testcontainers (Planned)

## 🗺️ Roadmap
- [x] Project initialization and Docker environment setup
- [x] Core Domain Modeling & JPA Auditing
- [x] Stateless Security Infrastructure (JWT Filters & Config)
- [x] User Authentication Flow & Global Exception Handling
- [ ] Integration Testing setup with Testcontainers
- [ ] Concurrency Control (Pessimistic Locking for seat reservations)
- [ ] Shopping Cart & Order Checkout logic
- [ ] Stripe Payment integration (Webhook handling)
- [ ] RabbitMQ integration for async tasks
- [ ] Ticket PDF Generation worker

## 🛠️ How to run locally (Development Mode)
> **Note:** Full containerization (Application Dockerfile, RabbitMQ, Mail Server) is planned for the upcoming phases. Currently, only the PostgreSQL database is containerized for local development.

1. Clone the repository.
2. Copy the `.env.example` file and rename it to `.env`, then fill in your local variables (e.g., JWT secret, DB credentials).
3. Start the PostgreSQL database container:
   
   ```bash
   docker compose up -d
