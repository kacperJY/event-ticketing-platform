# Local development

Full setup, manual checks and runtime options for the [Event Ticketing Platform](../README.md). The README contains a short quick start; this document has the complete procedure.

**Contents**

- [Development profile](#development-profile-dev)
- [Stripe CLI and signing secret](#stripe-cli-and-signing-secret)
- [Manual payment and refund checks](#manual-payment-and-refund-checks)
- [Production-style local Docker runtime](#production-style-local-docker-runtime)
- [Automated tests](#automated-tests)

See also: [Architecture and design notes](ARCHITECTURE.md).

## Development profile (`dev`)

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


## Automated tests

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
