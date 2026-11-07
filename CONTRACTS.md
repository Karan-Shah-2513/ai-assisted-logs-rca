# System Architecture & Technical Contracts

## 1. Directory & Layer Ownership
- **Sub-Agent 1 (`Infra-DB-Backend-Core`)**:
  - `/docker-compose.yml`
  - `/backend/pom.xml`
  - `/backend/src/main/resources/application.yml`
  - `/backend/src/main/resources/db/changelog/**` (Liquibase migrations & seed data)
  - Base packages:
    - `com.airca.config` (Kafka, Redis, WebMvc, DataSource, Security/CORS)
    - `com.airca.multitenancy` (TenantContext, TenantFilter, DataSourceTenantAspect)
    - `com.airca.ingestion` (IngestionController, KafkaProducer, KafkaConsumer, LogSanitizer)
    - `com.airca.deduplication` (RedisDeduplicatorService)
    - `com.airca.model` (Tenant, Incident, RunbookChunk entities & IncidentStatus enum)
    - `com.airca.repository` (TenantRepository, IncidentRepository, RunbookChunkRepository)
    - `com.airca.dto` (LogIngestRequest, IngestResponse, IncidentDto, TelemetryDto, AiRcaReport)

- **Sub-Agent 2 (`AI-RAG-Chaos-Engine`)**:
  - Packages:
    - `com.airca.ai` (Spring AI / Gemini service, MockAiFallbackService, Prompt engineering, AiRcaReport generator)
    - `com.airca.rag` (VectorRetrieverService, GitDiffService, PgVector similarity search)
    - `com.airca.websocket` (WebSocketConfig, StompBroadcasterService)
    - `com.airca.chaos` (ChaosSimulatorService, ChaosController)
    - `com.airca.controller` (IncidentController: GET /api/v1/incidents, GET /api/v1/incidents/{id}, POST /api/v1/incidents/{id}/resolve, GET /api/v1/telemetry)

- **Sub-Agent 3 (`Frontend-UI`)**:
  - `/frontend`
    - React 18+, TypeScript, Vite, TailwindCSS, AG-Grid (`ag-grid-react`, `ag-grid-community`), `@stomp/stompjs`, `sockjs-client`, `lucide-react`, `clsx`, `tailwind-merge`
    - Components: TenantSwitcher, KpiCards, ChaosModal, IncidentGrid, RcaDrawer, DiffViewer

---

## 2. Shared Data Models & REST / WebSocket Contracts

### DTOs
- `LogIngestRequest`:
  ```json
  {
    "serviceName": "payment-service",
    "stackTrace": "java.sql.SQLException: Connection pool exhausted at com.airca.payment...",
    "correlationId": "req-98234-abcd"
  }
  ```
- `AiRcaReport` (JSON stored in `incidents.ai_rca_json`):
  ```json
  {
    "rootCauseSummary": "Connection pool deadlock due to unclosed prepared statements in batch refund loop",
    "suspectedCommit": "commit a9f120e - 'refactor: batch refunds' by alice@alphafin.com\nDiff:\n--- a/RefundService.java\n+++ b/RefundService.java\n@@ -42,3 +42,4 @@ connection.setAutoCommit(false);",
    "matchedRunbook": "Runbook: Payment Gateway Timeout & DB Deadlock Runbook (RB-PAY-01)",
    "confidenceScore": 94,
    "severity": "P0",
    "suggestedRemediation": "kubectl rollout undo deployment/payment-service -n alpha-prod && redis-cli flushdb"
  }
  ```
- `IncidentDto`:
  ```json
  {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "tenantId": "11111111-1111-1111-1111-111111111111",
    "fingerprintHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "serviceName": "payment-service",
    "rawStackTrace": "...",
    "sanitizedStackTrace": "...",
    "occurrenceCount": 501,
    "status": "OPEN",
    "aiRcaJson": { ... },
    "firstSeen": "2026-11-07T12:00:00Z",
    "lastSeen": "2026-11-07T12:05:00Z"
  }
  ```
- `TelemetryDto`:
  ```json
  {
    "tenantId": "11111111-1111-1111-1111-111111111111",
    "totalLogs": 5000,
    "deduplicatedLogs": 4980,
    "uniqueIncidents": 20,
    "deduplicationRatio": 99.6,
    "estimatedCostSavedUsd": 74.70
  }
  ```
- `SimulateOutageRequest`:
  ```json
  {
    "scenario": "PAYMENT_DB_DEADLOCK",
    "tenantId": "11111111-1111-1111-1111-111111111111",
    "logCount": 500
  }
  ```

### REST Endpoints
- `POST /api/v1/logs` (Header: `X-Tenant-ID`) -> `{ "status": "QUEUED", "fingerprint": "...", "correlationId": "..." }`
- `GET /api/v1/incidents` (Header: `X-Tenant-ID`) -> List of `IncidentDto`
- `GET /api/v1/incidents/{id}` (Header: `X-Tenant-ID`) -> `IncidentDto`
- `POST /api/v1/incidents/{id}/resolve` (Header: `X-Tenant-ID`) -> `IncidentDto` (status = `RESOLVED`, updates knowledge base)
- `GET /api/v1/telemetry` (Header: `X-Tenant-ID`) -> `TelemetryDto`
- `POST /api/v1/simulate-outage` -> `{ "message": "Chaos simulation triggered", "scenario": "PAYMENT_DB_DEADLOCK", "logsQueued": 500 }`
- `GET /api/v1/tenants` -> `[ { "id": "11111111-1111-1111-1111-111111111111", "name": "AlphaFin Corp" }, ... ]`

### WebSockets (STOMP over SockJS at `/ws`)
- `/topic/tenant/{tenantId}/incidents`: Incident creation or count update
- `/topic/tenant/{tenantId}/telemetry`: Real-time telemetry counter ticks
