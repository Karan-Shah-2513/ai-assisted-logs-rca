# AI-Assisted Multi-Tenant Log Root-Cause Analyzer (Airca) — End-to-End Demo Guide

This guide provides the complete, authoritative manual for starting, operating, demonstrating, and troubleshooting the **AI-Assisted Multi-Tenant Log Root-Cause Analyzer (`ai-assisted-logs-rca`)** system.

---

## 1. Architecture & Demo Overview

The system provides multi-tenant incident triage and automated Root-Cause Analysis (RCA) across distributed microservices. It connects:

1. **Four tenant microservices** (`order-service`, `ledger-service`, `auth-service`, `wallet-service`) containing deliberate defect endpoints.
2. **Shared error-log shipper** (`TenantGlobalExceptionHandler` + `AircaLogReporter`) that captures 5xx exceptions and posts real stack traces to Airca.
3. **Central Airca ingestion pipeline**:
   - HTTP gateway with API key authentication and tenant isolation (`TenantFilter`).
   - Apache Kafka topic (`raw-error-logs`) buffer.
   - Deterministic regex sanitizer and SHA-256 fingerprint deduplication with Redis 7.
   - Retrieval-Augmented Generation (RAG) using PostgreSQL 16 + `pgvector` (768-dimensional cosine similarity), with vectors generated through the official Google GenAI Java SDK and used to match historical incidents, Git commits, and operational runbooks.
   - Hosted Gemma 4 root-cause analysis via the official Google GenAI Java SDK (`client.models.generateContent`), reusing the same `GEMINI_API_KEY` with configurable model ID (default `gemma-4-26b-a4b-it`, also supporting `gemma-4-31b-it`), authoritative RAG runbook match preservation (guaranteeing that top qualifying vector runbook matches are preserved directly in reports, independent of model output), and a deterministic fallback when no API key is configured or a generation request fails.
   - STOMP over SockJS WebSocket broker with tenant-scoped destination authorization (`/ws`).
   - PostgreSQL Row-Level Security (RLS) with dedicated unprivileged `airca_app` role (`NOBYPASSRLS`).
4. **Dark-mode Vite/React Observability Dashboard**:
   - Tenant switcher, session-scoped API key management, real-time KPI telemetry cards, fault injection and burst controls, AG-Grid incident table, and slide-out RCA Drawer with verified resolution feedback loop.

---

## 2. Prerequisites & Environment Setup

| Component | Minimum Version | Requirement Context |
| :--- | :--- | :--- |
| **Docker Engine & Docker Compose** | Docker 24+ / Compose v2+ | **Mandatory** for running infrastructure and containerized demo services. |
| **Node.js & npm** | Node.js 18+ (20+ LTS recommended), npm 9+ | **Mandatory** for running and building the Vite frontend. |
| **Java Development Kit (JDK)** | Java 21 (Temurin / OpenJDK) | Only needed if compiling or testing Spring Boot services natively outside Docker. |
| **Apache Maven** | Maven 3.8+ | Only needed if executing Maven builds or test suites outside Docker. |
| **curl & jq** | Standard Unix tools | Useful for running CLI verification and defect trigger snippets. |

---

## 3. Tenant Directory & Credentials (Demo-Only)

> [!WARNING]
> **Demo-Only Credentials Notice:**
> The API keys and database credentials listed below are hardcoded defaults configured strictly for local Docker Compose demonstration and integration testing.
> - **Never** reuse these keys in production environments.
> - **Never** commit real production credentials to Git.
> - In production architectures, transmitting long-lived tenant API keys directly from browser web applications is **not** safe. Browser clients in production must use backend session proxies (BFF), short-lived OAuth/OIDC tokens, or secure HTTP-only cookies. In this local demo, keys are entered into a session-scoped input stored in `sessionStorage` with a Vite `import.meta.env.DEV` helper button.

### Tenant Mapping & Port Matrix

| Tenant Entity | Tenant UUID (`X-Tenant-ID`) | Local Demo API Key (`X-API-Key`) | Microservices & Ports |
| :--- | :--- | :--- | :--- |
| **Tenant A: AlphaFin Corp** | `11111111-1111-1111-1111-111111111111` | `alphafin-secret-key-1111` | • `order-service` (`http://localhost:8081`)<br/>• `ledger-service` (`http://localhost:8082`) |
| **Tenant B: BetaPay Systems** | `22222222-2222-2222-2222-222222222222` | `betapay-secret-key-2222` | • `auth-service` (`http://localhost:8083`)<br/>• `wallet-service` (`http://localhost:8084`) |
| **Central Platform** | N/A (Multi-Tenant) | Accepts both keys; validates tenant context | • `airca-service` (`http://localhost:8080`)<br/>• Frontend Dashboard (`http://localhost:5173`) |

### Database Roles & Credentials (Local Compose)

- **Liquibase / Migration Role (Superuser):** `airca` / `airca` (Database: `airca`, Port: `5432`).
- **Application Runtime Role (`NOBYPASSRLS`):** `airca_app` / `airca_app` (Created automatically by `init-app-role.sh` and Liquibase changeset `006-enforce-app-role-rls.sql`).

---

## 4. End-to-End Startup via Docker Compose

### Step 1: Start All Services

From the repository root, start all infrastructure and application containers in the background:

```bash
# Powers both first-occurrence log vectorization and hosted Gemma 4 RCA generation:
export GEMINI_API_KEY="<Google AI Studio API key>"

# Optional: override hosted Gemma 4 model (default: gemma-4-26b-a4b-it, supports gemma-4-31b-it)
# export AIRCA_AI_CHAT_MODEL="gemma-4-26b-a4b-it"

docker compose up --build -d
```

*(If images are already built, you can omit `--build`: `docker compose up -d`)*
All tenant microservices (`order-service`, `ledger-service`, `auth-service`, `wallet-service`) and central `airca-service` build directly via multi-stage Docker builds from the Maven reactor; no manual pre-building of `target/*.jar` on the host machine is required.
The service uses `GEMINI_API_KEY` for both vector embeddings (via `gemini-embedding-2`) and hosted Gemma 4 RCA generation (defaulting to `gemma-4-26b-a4b-it`, configurable via `AIRCA_AI_CHAT_MODEL`). Do not commit real keys to the repository. Without `GEMINI_API_KEY`, first-occurrence log ingestion fails during embedding generation, while RCA falls back to high-fidelity deterministic local analysis.

### Step 2: Verify Readiness & Container Health

Docker Compose includes integrated health checks for the infrastructure layer (`postgres`, `redis`, `kafka`). Check container status:

```bash
docker compose ps
```

Expected output should show all 8 containers in healthy or running state:

```text
NAME                                STATUS
ai-assisted-logs-rca-postgres-1       Up (healthy)  0.0.0.0:5432->5432/tcp
ai-assisted-logs-rca-redis-1          Up (healthy)  0.0.0.0:6379->6379/tcp
ai-assisted-logs-rca-kafka-1          Up (healthy)  0.0.0.0:9092->9092/tcp
ai-assisted-logs-rca-airca-service-1  Up            0.0.0.0:8080->8080/tcp
ai-assisted-logs-rca-order-service-1   Up            0.0.0.0:8081->8081/tcp
ai-assisted-logs-rca-ledger-service-1  Up            0.0.0.0:8082->8082/tcp
ai-assisted-logs-rca-auth-service-1    Up            0.0.0.0:8083->8083/tcp
ai-assisted-logs-rca-wallet-service-1  Up            0.0.0.0:8084->8084/tcp
```

### Step 3: Run Service Health Checks via HTTP

Verify that all service HTTP endpoints are responding:

```bash
# Central Airca Platform (port 8080)
curl -s http://localhost:8080/health
# Expected: {"status":"UP"}

# AlphaFin Order Service (port 8081)
curl -s http://localhost:8081/api/orders/health
# Expected: {"service":"order-service","tenantId":"11111111-1111-1111-1111-111111111111","status":"UP"}

# AlphaFin Ledger Service (port 8082)
curl -s http://localhost:8082/api/ledger/health
# Expected: {"service":"ledger-service","tenantId":"11111111-1111-1111-1111-111111111111","status":"UP"}

# BetaPay Auth Service (port 8083)
curl -s http://localhost:8083/api/auth/health
# Expected: {"service":"auth-service","tenantId":"22222222-2222-2222-2222-222222222222","status":"UP"}

# BetaPay Wallet Service (port 8084)
curl -s http://localhost:8084/api/wallet/health
# Expected: {"service":"wallet-service","tenantId":"22222222-2222-2222-2222-222222222222","status":"UP"}
```

---

## 5. Starting the Frontend Observability Dashboard

### Step 1: Install Dependencies and Start Vite Dev Server

Open a new terminal window:

```bash
cd frontend
npm install
npm run dev
```

The Vite dev server starts at **`http://localhost:5173`**.

### Step 2: Open Dashboard & Configure Session API Keys

1. Navigate to **`http://localhost:5173`** in your browser.
2. In the top navigation bar, observe the **Tenant Switcher** (defaults to **AlphaFin Corp**) and the **API Key (X-API-Key)** widget.
3. Because the frontend runs in Vite dev mode (`import.meta.env.DEV`), a **"Use dev key"** quick-fill button is available in the API key bar. Clicking it populates the local Compose demo key (`alphafin-secret-key-1111` for AlphaFin or `betapay-secret-key-2222` for BetaPay).
4. Alternatively, you can type the key manually into the password field and click **"Save"**.
5. Once saved, the key is held in `sessionStorage` (`airca_api_key_<tenantId>`) and automatically attached as the `X-API-Key` header on all outbound REST calls and STOMP WebSocket `CONNECT` frames.
6. The connection indicator will turn green: **`Stream Live`** (connected to STOMP topic `/topic/tenant/<tenantId>/incidents`).

### How Vite Routes Requests During Development

The browser sends API and WebSocket requests to the Vite origin (`http://localhost:5173`). The development server proxies `/api/v1/orders` and `/api/orders` to `http://localhost:8081`, ledger paths to `8082`, auth paths to `8083`, and wallet paths to `8084`. Other `/api` requests go to central Airca at `8080`; `/ws` is also proxied to Airca with WebSocket support enabled.

---

## 6. Authentication & Tenant Authorization Rules

The backend enforces strict fail-closed multi-tenancy rules implemented across `TenantFilter.java` (HTTP) and `StompTenantAuthChannelInterceptor.java` (WebSockets):

1. **Secured Routes:** All `/api/**` endpoints require an `X-API-Key` header.
2. **Missing or Invalid Key:** Responds with `401 Unauthorized` (`Missing or invalid API key`). Constant-time comparison (`MessageDigest.isEqual`) prevents timing leaks.
3. **Tenant Context Binding:** The tenant ID is derived directly from the authenticated API key in `TenantApiKeyService`.
4. **Header Mismatch Validation:** If an explicit `X-Tenant-ID` header is also provided in the request, it MUST match the tenant ID bound to the `X-API-Key`. A mismatch returns `403 Forbidden` (`X-Tenant-ID does not match authenticated tenant`).
5. **WebSocket Authorization:**
   - STOMP `CONNECT` frame must provide `X-API-Key` (and optional matching `X-Tenant-ID`).
   - STOMP `SUBSCRIBE` frame is validated against `/topic/tenant/{tenantId}/(incidents|telemetry)`. Cross-tenant subscriptions and wildcard destinations (`*`, `#`, `/topic/**`) are blocked immediately with `StompAuthorizationException`.
   - Client `SEND` frames are prohibited.

---

## 7. Intentional Defect Endpoints & Source Code Locations

The tenant microservices contain deliberate, reproducible bugs designed to exercise real error shipping and automated RCA.

### Summary of Intentional Defect Locations

| Service | Endpoint | Java Class & Line References | Defect Type & Exception | Trigger Condition |
| :--- | :--- | :--- | :--- | :--- |
| **`order-service`** (AlphaFin) | `POST /api/v1/orders/checkout` | [`OrderController.java`](file:///home/karan/projects/ai-assisted-logs-rca/tenants/tenant1-alphafin/order-service/src/main/java/com/airca/tenants/order/controller/OrderController.java#L43-L48) | `java.lang.NullPointerException` | Omitting `discountCode` or passing `{}` executes unguarded `.toUpperCase()` on `null`. |
| **`order-service`** (AlphaFin) | `POST /api/v1/orders/bulk` | [`OrderController.java`](file:///home/karan/projects/ai-assisted-logs-rca/tenants/tenant1-alphafin/order-service/src/main/java/com/airca/tenants/order/controller/OrderController.java#L65-L82) | `java.lang.IndexOutOfBoundsException` | Passing `items` array with > 5 items triggers off-by-one loop `i <= items.size()`. |
| **`ledger-service`** (AlphaFin) | `POST /api/v1/ledger/transfer` | [`LedgerController.java`](file:///home/karan/projects/ai-assisted-logs-rca/tenants/tenant1-alphafin/ledger-service/src/main/java/com/airca/tenants/ledger/controller/LedgerController.java#L42-L50) | `java.lang.ArithmeticException: / by zero` | Passing `exchangeRateSpread: 0` (or omitting spread) causes integer division `10000L / 0L`. |
| **`ledger-service`** (AlphaFin) | `POST /api/v1/ledger/reconcile` | [`LedgerController.java`](file:///home/karan/projects/ai-assisted-logs-rca/tenants/tenant1-alphafin/ledger-service/src/main/java/com/airca/tenants/ledger/controller/LedgerController.java#L78-L87) | `java.lang.IllegalStateException` | Unbalanced totals (`totalDebits != totalCredits`) simulates ledger partition lock contention. |
| **`auth-service`** (BetaPay) | `POST /api/v1/auth/token` | [`AuthController.java`](file:///home/karan/projects/ai-assisted-logs-rca/tenants/tenant2-betapay/auth-service/src/main/java/com/airca/tenants/auth/controller/AuthController.java#L52-L66) | `java.lang.IllegalArgumentException` | Malformed JWT claims string or expired token (`exp < now`) triggers validation exception. |
| **`auth-service`** (BetaPay) | `POST /api/v1/auth/mfa` | [`AuthController.java`](file:///home/karan/projects/ai-assisted-logs-rca/tenants/tenant2-betapay/auth-service/src/main/java/com/airca/tenants/auth/controller/AuthController.java#L89-L96) | `java.lang.NullPointerException` | Omitting `deviceMetadata.fingerprint` triggers unguarded `fingerprint.trim()`. |
| **`wallet-service`** (BetaPay) | `POST /api/v1/wallet/withdraw` | [`WalletController.java`](file:///home/karan/projects/ai-assisted-logs-rca/tenants/tenant2-betapay/wallet-service/src/main/java/com/airca/tenants/wallet/controller/WalletController.java#L87-L103) | `java.lang.IllegalStateException` | Withdrawal `amount` > `threshold` (default 10,000.00) triggers simulated liquidity lock timeout. |
| **`wallet-service`** (BetaPay) | `POST /api/v1/wallet/fx-quote` | [`WalletController.java`](file:///home/karan/projects/ai-assisted-logs-rca/tenants/tenant2-betapay/wallet-service/src/main/java/com/airca/tenants/wallet/controller/WalletController.java#L128-L157) | `java.lang.NumberFormatException` | Passing non-numeric pair string (`pair: "MALFORMED"`) triggers unvalidated `Double.parseDouble`. |

---

## 8. Expected HTTP 500 Behavior & Asynchronous Pipeline Pipeline Caveat

### How Error Reporting Works
When an intentional defect endpoint is called:
1. The unhandled exception is caught by `TenantGlobalExceptionHandler` ([`TenantGlobalExceptionHandler.java`](file:///home/karan/projects/ai-assisted-logs-rca/tenants/tenant-common/src/main/java/com/airca/tenants/common/advice/TenantGlobalExceptionHandler.java#L63-L88)).
2. `TenantGlobalExceptionHandler` extracts the complete Java stack trace.
3. `AircaLogReporter` immediately sends an HTTP POST request to central Airca (`http://airca-service:8080/api/v1/logs`) containing `serviceName`, `stackTrace`, and `correlationId`, along with `X-Tenant-ID` and `X-API-Key` headers.
4. The tenant service returns **`HTTP 500 Internal Server Error`** to the calling client, with body:
   ```json
   {
     "timestamp": "2026-11-10T13:30:00Z",
     "status": 500,
     "error": "Internal Server Error",
     "message": "An unexpected internal error occurred. Please contact support referencing correlationId: ...",
     "path": "/api/v1/orders/checkout",
     "correlationId": "550e8400-e29b-41d4-a716-446655440000"
   }
   ```

> [!IMPORTANT]
> **Understanding the Asynchronous Processing Pipeline:**
> - **HTTP 500 is the expected defect response from the microservice.** It confirms that the defect fired and log forwarding was attempted; it is not proof that Airca accepted or processed the log.
> - **Ingestion into Airca is asynchronous:**
>   1. Airca acknowledges the incoming log with `HTTP 202 Accepted` (`status: "QUEUED"`).
>   2. The log message is published to Kafka topic `raw-error-logs`.
>   3. The Kafka consumer processes the log, normalizes dynamic values (UUIDs, timestamps, hex pointers), and checks Redis for deduplication.
>   4. If it is the **first occurrence**, Airca requires `GEMINI_API_KEY` to generate 768-dimensional embeddings via `gemini-embedding-2` through the official Google GenAI SDK, then queries `pgvector` for similar runbooks (cosine threshold 0.72) and inspects recent Git diffs. The top qualifying runbook match from vector retrieval is locked in as the authoritative runbook for the incident. Without the Gemini key, embedding generation throws and the incident is not fully processed; configure the key before generating demo logs, and check Airca logs if a trigger does not appear on the dashboard.
>   5. RCA invokes hosted Gemma 4 generation (`gemma-4-26b-a4b-it` or `gemma-4-31b-it`) via the official Google GenAI SDK using `GEMINI_API_KEY` (temperature 0.1, structured JSON output mode). If the key is missing or model generation fails/times out, it automatically falls back to deterministic local analysis. The authoritative RAG runbook resolved in Step 4 is preserved on the final `AiRcaReport` and displayed in the UI, independent of model generation.
>   6. If it is a **subsequent occurrence**, Redis increments the occurrence counter in PostgreSQL without invoking the chat model, saving over 99% in token costs.
>   7. The frontend receives the event via STOMP WebSocket and updates the UI live.
> - **Caveat:** There is a brief propagation window (typically 100–500ms) between the microservice returning HTTP 500 and the incident appearing or updating on the frontend AG-Grid.

---

## 9. Step-by-Step Demo Walkthrough

### Scenario A: Trigger AlphaFin Direct Tenant Defects

#### 1. Trigger Order Checkout Defect (`NullPointerException`)
```bash
# Triggers HTTP 500 (NPE on null discountCode)
curl -i -X POST http://localhost:8081/api/v1/orders/checkout \
  -H "Content-Type: application/json" \
  -d '{}'
```
*Contrast with non-defective request:*
```bash
# Returns HTTP 200 SUCCESS
curl -i -X POST http://localhost:8081/api/v1/orders/checkout \
  -H "Content-Type: application/json" \
  -d '{"discountCode": "SAVE20", "orderId": "ORD-1001"}'
```

#### 2. Trigger Order Bulk Processing Defect (`IndexOutOfBoundsException`)
```bash
# Triggers HTTP 500 (Off-by-one loop when items > 5)
curl -i -X POST http://localhost:8081/api/v1/orders/bulk \
  -H "Content-Type: application/json" \
  -d '{
    "items": [
      {"orderId":"item-1"}, {"orderId":"item-2"}, {"orderId":"item-3"},
      {"orderId":"item-4"}, {"orderId":"item-5"}, {"orderId":"item-6"}
    ]
  }'
```

#### 3. Trigger Ledger Transfer Division by Zero (`ArithmeticException`)
```bash
# Triggers HTTP 500 (10000 / 0L when spread is 0)
curl -i -X POST http://localhost:8082/api/v1/ledger/transfer \
  -H "Content-Type: application/json" \
  -d '{"exchangeRateSpread": 0}'
```

#### 4. Trigger Ledger Partition Contention (`IllegalStateException`)
```bash
# Triggers HTTP 500 (Unbalanced debits/credits)
curl -i -X POST http://localhost:8082/api/v1/ledger/reconcile \
  -H "Content-Type: application/json" \
  -d '{"totalDebits": 5000.00, "totalCredits": 2500.00}'
```

---

### Scenario B: Trigger BetaPay Direct Tenant Defects

#### 1. Trigger Auth Token Malformed / Expired Defect (`IllegalArgumentException`)
```bash
# Triggers HTTP 500 (Malformed JWT segment)
curl -i -X POST http://localhost:8083/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"claims": ["eyJhbGciOiJIUzI1NiJ9.invalid-payload.sig"]}'
```

#### 2. Trigger Auth MFA Missing Fingerprint Defect (`NullPointerException`)
```bash
# Triggers HTTP 500 (Missing fingerprint in deviceMetadata)
curl -i -X POST http://localhost:8083/api/v1/auth/mfa \
  -H "Content-Type: application/json" \
  -d '{"userId": "usr-betapay-demo"}'
```

#### 3. Trigger Wallet Liquidity Lock Timeout (`IllegalStateException`)
```bash
# Triggers HTTP 500 (Withdrawal amount > threshold)
curl -i -X POST http://localhost:8084/api/v1/wallet/withdraw \
  -H "Content-Type: application/json" \
  -d '{"amount": 50000.00, "threshold": 10000.00}'
```

#### 4. Trigger Wallet FX Quote Parse Failure (`NumberFormatException`)
```bash
# Triggers HTTP 500 (Non-numeric currency pair)
curl -i -X POST http://localhost:8084/api/v1/wallet/fx-quote \
  -H "Content-Type: application/json" \
  -d '{"pair": "INVALID_PAIR"}'
```

---

### Scenario C: High-Volume Chaos Burst & Deduplication Demonstration

`POST /api/v1/simulate-outage` accepts `PAYMENT_DB_DEADLOCK` (`payment-service`), `REDIS_OOM` (`cache-service`), or `AUTH_NPE` (`auth-service`). To demonstrate how repeated error logs collapse into a triage ticket via Redis fingerprint deduplication:

```bash
# Blast 500 logs of PAYMENT_DB_DEADLOCK for AlphaFin
curl -i -X POST http://localhost:8080/api/v1/simulate-outage \
  -H "Content-Type: application/json" \
  -H "X-API-Key: alphafin-secret-key-1111" \
  -d '{
    "scenario": "PAYMENT_DB_DEADLOCK",
    "tenantId": "11111111-1111-1111-1111-111111111111",
    "logCount": 500
  }'
```

**Observation on Dashboard:**
1. AG-Grid shows **1 unique incident** for `payment-service`.
2. The occurrence counter ticks up to **500** rapidly.
3. The KPI cards display:
   - **Total Logs Ingested:** 500+
   - **Deduplication Ratio:** 99.8%
   - **Estimated Cost Saved:** ~$7.50+ (calculated based on avoided LLM token invocations).

---

### Scenario D: Inspecting Root Cause Analysis (RCA) in Frontend

1. In the frontend dashboard, click on any incident row in the AG-Grid table.
2. The **RCA Inspector Drawer** slides out from the right:
   - **Section 01 (Root Cause Analysis):** Displays the root cause diagnosis and a confidence score progress bar. RCA uses the configured Gemma 4 model or deterministic fallback, with retrieved evidence from pgvector.
   - **Section 02 (Suspected Introducing Commit):** Highlights the suspect Git commit SHA, author, and interactive syntax-highlighted diff (rendered by `DiffViewer.tsx`).
   - **Section 03 (Matched Operational Runbook):** Shows the pgvector-retrieved runbook playbook chunk (authoritatively resolved from vector similarity search and preserved independent of LLM output).
   - **Section 04 (Suggested Remediation):** Provides the actionable remediation command with a single-click copy button (e.g. `kubectl rollout undo deployment/...`).
   - **Section 05 (Failure Signature):** Displays the sanitized exception stack trace.

---

### Scenario E: Human-Verified Resolution & Semantic Retrieval Feedback Loop

Once an engineer verifies the fix, they resolve the incident. This indexes the verified root cause and fix command into `pgvector` so future similar incidents retrieve it automatically:

#### Resolving via Frontend UI
1. Scroll to **Section 06 (Human-Verified Resolution)** in the open RCA Drawer.
2. Click **"Use AI Summary"** or **"Use AI Remediation"** to pre-fill the form, or type custom verified details.
3. Fill in:
   - **Verified Root Cause:** e.g., `Connection pool exhaustion caused by unclosed statement in batch loop.` (required; maximum 4,000 characters)
   - **Verified Fix Command or PR Link:** e.g., `PR #108: Wrap batch statement in try-with-resources and set pool max 50.` (required; maximum 4,000 characters)
   - **Resolved By:** e.g., `alice@alphafin.com` (required; maximum 200 characters)
4. Click **"Verify & Resolve Incident"**.
5. The incident status updates to `RESOLVED` on the grid, and a notification confirms it has been indexed into the vector store.

#### Resolving via curl
```bash
# Replace <INCIDENT_UUID> with actual incident ID from GET /api/v1/incidents
curl -i -X POST http://localhost:8080/api/v1/incidents/<INCIDENT_UUID>/resolve \
  -H "Content-Type: application/json" \
  -H "X-API-Key: alphafin-secret-key-1111" \
  -H "X-Tenant-ID: 11111111-1111-1111-1111-111111111111" \
  -d '{
    "verifiedRootCause": "Connection pool exhaustion caused by unclosed statement in batch loop",
    "verifiedFixCommandOrPR": "PR #108: Wrap statement in try-with-resources",
    "resolvedBy": "alice@alphafin.com"
  }'
```
The backend rejects blank values, root-cause or fix text over 4,000 characters, and `resolvedBy` values over 200 characters.

---

### Scenario F: Incident & Runbook Retention Cleanup

#### Scheduled Retention
- Configured via `app.retention` properties in [`application.yml`](file:///home/karan/projects/ai-assisted-logs-rca/airca-service/src/main/resources/application.yml#L83-L89):
  - `incident-retention-days`: `30`
  - `runbook-chunk-retention-days`: `180`
  - `cleanup-cron`: `0 0 3 * * *` (Daily at 03:00 AM)
- Automatically managed by [`RetentionScheduler.java`](file:///home/karan/projects/ai-assisted-logs-rca/airca-service/src/main/java/com/airca/retention/RetentionScheduler.java), which loops through each tenant and sets `TenantContext` to respect RLS boundaries during deletion.

#### Manual Retention Cleanup via curl
Administrators can trigger immediate retention cleanup with custom retention day cutoffs:

```bash
curl -i -X POST http://localhost:8080/api/v1/admin/retention/cleanup \
  -H "Content-Type: application/json" \
  -H "X-API-Key: alphafin-secret-key-1111" \
  -H "X-Tenant-ID: 11111111-1111-1111-1111-111111111111" \
  -d '{
    "incidentRetentionDays": 7,
    "runbookChunkRetentionDays": 30
  }'
```

Expected Response:
```json
{
  "tenantId": "11111111-1111-1111-1111-111111111111",
  "deletedIncidents": 0,
  "deletedRunbookChunks": 0,
  "incidentCutoff": "2026-11-03T07:55:00Z",
  "runbookChunkCutoff": "2026-10-11T07:55:00Z"
}
```

---

## 10. Complete Working curl Cheat Sheet

Use these snippets to test every major endpoint directly from your terminal.

```bash
# -------------------------------------------------------------
# 1. Platform & Tenant Health Checks (Public, no auth needed)
# -------------------------------------------------------------
curl -s http://localhost:8080/health
curl -s http://localhost:8081/api/orders/health
curl -s http://localhost:8082/api/ledger/health
curl -s http://localhost:8083/api/auth/health
curl -s http://localhost:8084/api/wallet/health

# -------------------------------------------------------------
# 2. Ingest Error Log Directly into Airca (AlphaFin)
# -------------------------------------------------------------
curl -i -X POST http://localhost:8080/api/v1/logs \
  -H "Content-Type: application/json" \
  -H "X-API-Key: alphafin-secret-key-1111" \
  -H "X-Tenant-ID: 11111111-1111-1111-1111-111111111111" \
  -d '{
    "serviceName": "payment-service",
    "stackTrace": "java.sql.SQLException: Connection pool exhausted at com.alphafin.payment.RefundBatch.process(RefundBatch.java:42)",
    "correlationId": "corr-alpha-001"
  }'

# -------------------------------------------------------------
# 3. List Incidents for AlphaFin
# -------------------------------------------------------------
curl -s -X GET http://localhost:8080/api/v1/incidents \
  -H "X-API-Key: alphafin-secret-key-1111" \
  -H "X-Tenant-ID: 11111111-1111-1111-1111-111111111111" | jq .

# -------------------------------------------------------------
# 4. Fetch Real-Time Telemetry & Token Cost Savings
# -------------------------------------------------------------
curl -s -X GET http://localhost:8080/api/v1/telemetry \
  -H "X-API-Key: alphafin-secret-key-1111" \
  -H "X-Tenant-ID: 11111111-1111-1111-1111-111111111111" | jq .

# -------------------------------------------------------------
# 5. List Tenant Summaries (requires X-API-Key)
# -------------------------------------------------------------
curl -s -X GET http://localhost:8080/api/v1/tenants \
  -H "X-API-Key: alphafin-secret-key-1111" | jq .

# -------------------------------------------------------------
# 6. Verify Auth Failures (Expected 401 and 403)
# -------------------------------------------------------------
# Missing key -> Returns 401 Unauthorized
curl -i -X GET http://localhost:8080/api/v1/incidents

# Key / Tenant Mismatch -> Returns 403 Forbidden
curl -i -X GET http://localhost:8080/api/v1/incidents \
  -H "X-API-Key: alphafin-secret-key-1111" \
  -H "X-Tenant-ID: 22222222-2222-2222-2222-222222222222"
```

---

## 11. Database Row-Level Security (RLS) & Testcontainers

### Dedicated Unprivileged Runtime Role (`airca_app`)
- PostgreSQL superusers bypass RLS policies by default.
- To guarantee true database-enforced multi-tenant isolation, the application connects as `airca_app`, which is explicitly provisioned with:
  ```sql
  CREATE ROLE airca_app WITH LOGIN PASSWORD 'airca_app'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
  ```
- RLS is forced on both `incidents` and `runbook_chunks`:
  ```sql
  ALTER TABLE incidents ENABLE ROW LEVEL SECURITY;
  ALTER TABLE incidents FORCE ROW LEVEL SECURITY;

  CREATE POLICY incidents_tenant_isolation ON incidents
    USING (tenant_id::text = NULLIF(current_setting('app.current_tenant', true), ''))
    WITH CHECK (tenant_id::text = NULLIF(current_setting('app.current_tenant', true), ''));
  ```
- Before any query is executed, Spring Boot sets the session setting:
  `SET LOCAL app.current_tenant = '<authenticated_tenant_uuid>'`.
- Any query run without this setting set sees **0 rows**, even if issuing `SELECT * FROM incidents;`.

### Testcontainers Integration Test Architecture
- Backend integration tests extend [`AbstractPostgresIntegrationTest`](file:///home/karan/projects/ai-assisted-logs-rca/airca-service/src/test/java/com/airca/integration/AbstractPostgresIntegrationTest.java).
- Configured with `@Testcontainers(disabledWithoutDocker = true)`.
- When Docker is present:
  1. Testcontainers pulls and starts `pgvector/pgvector:pg16`.
  2. Runs Liquibase migrations using superuser `airca`.
  3. Creates `airca_app` with `NOBYPASSRLS`.
  4. Injects dynamic database URLs and credentials into the Spring test context.
- When Docker is absent: Tests cleanly skip without failing the build.

---

## 12. Troubleshooting & FAQ

### 1. Port Conflicts on Host Machine
If any service fails to bind its port during `docker compose up`:
- Port `5432`: Stop local PostgreSQL (`sudo systemctl stop postgresql` or change `ports: ["5433:5432"]` in `docker-compose.yml`).
- Port `6379`: Stop local Redis (`sudo systemctl stop redis` or `killall redis-server`).
- Port `9092`: Stop local Kafka.
- Ports `8080`–`8084`: Check for stale Java processes (`lsof -i :8080` and `kill -9 <PID>`).
- Port `5173`: Check for stale Node processes (`lsof -i :5173`).

### 2. Browser WebSocket Connection Shows "Stream Offline"
- Ensure you have configured a valid API key in the top bar (`alphafin-secret-key-1111` or `betapay-secret-key-2222`).
- Check browser developer console (F12) for STOMP connection logs.
- If running frontend on a custom host or port, ensure `CORS_ALLOWED_ORIGINS` in `airca-service` allows your frontend origin.

### 3. AI Configuration: Google GenAI SDK, Embeddings & Hosted Gemma 4 RCA
- **Unified API Key:** A single `GEMINI_API_KEY` powers both vector embeddings (via `gemini-embedding-2`) and hosted Gemma 4 root-cause analysis (via `gemma-4-26b-a4b-it` or `gemma-4-31b-it`) using the official Google GenAI Java SDK (`com.google.genai:google-genai`). No separate OpenAI key is needed.
- **Vector Embeddings (`TextEmbeddingService`):**
  - Model: `gemini-embedding-2` (`app.ai.embedding.model`).
  - Dimensions: `768` (`app.ai.embedding.dimensions: 768`).
  - Strict validation: verifies that exactly 768 finite float values are returned before persisting to `pgvector` (`vector(768)`).
  - Required for first-occurrence log ingestion: without `GEMINI_API_KEY`, embedding generation throws and the incident is not fully triaged.
- **Hosted Gemma 4 RCA (`AiRcaService`):**
  - Default Model: `gemma-4-26b-a4b-it` (`airca.ai.chat-model: ${AIRCA_AI_CHAT_MODEL:gemma-4-26b-a4b-it}`).
  - Supported Alternative: `gemma-4-31b-it`.
  - Configured with `temperature(0.1f)` and `responseMimeType("application/json")` for deterministic, structured JSON output.
  - Robust Payload Parsing: `extractJsonPayload` handles potential markdown code fence wrapping (```json ... ```), and `normalizeEvidence` gracefully converts string variations like `"null"`, `"none"`, or `"n/a"` to standard platform fallbacks (`"No matching commit identified"`, `"No directly matching runbook found"`).
- **Authoritative RAG Runbook Preservation (Task 27):**
  - The top qualifying runbook chunk retrieved from PostgreSQL `pgvector` cosine similarity search (threshold: 0.72) is preserved as the authoritative runbook value in the final `AiRcaReport` (`report.withMatchedRunbook(authoritativeRunbook)`), independent of whether the LLM mentions or omits the runbook.
- **Resilient Deterministic Local Analysis Fallback:**
  - If `GEMINI_API_KEY` is not configured or if the Gemma 4 API call fails (network timeout, rate limit, quota exhaustion), `AiRcaService` automatically falls back to local deterministic rule-based analysis, guaranteeing 100% platform availability without throwing HTTP 500s.
- **Environment Setup:**
  ```bash
  # Required: powers both gemini-embedding-2 and hosted Gemma 4 RCA
  export GEMINI_API_KEY="<Google AI Studio API key>"

  # Optional: override default Gemma 4 model (default: gemma-4-26b-a4b-it)
  # export AIRCA_AI_CHAT_MODEL="gemma-4-31b-it"

  docker compose up -d
  ```
- **Run Unit & Integration Tests:**
  ```bash
  cd airca-service
  mvn test -Dtest=AiRcaServiceTest,TextEmbeddingServiceTest,RetrievalResultTest,VectorRetrieverServiceTest
  ```

### 4. Database Liquibase Changelog Lock
If the backend crashes mid-migration and logs `Waiting for changelog lock`:
```bash
docker compose exec postgres psql -U airca -d airca -c "UPDATE databasechangeloglock SET locked = false, lockedby = null, lockgranted = null WHERE id = 1;"
```

### 5. Cleaning Up Demo Containers
To stop and remove demo containers without losing persistent database data:
```bash
docker compose down
```
To stop containers and reset database data back to seed state:
```bash
docker compose down -v
```
