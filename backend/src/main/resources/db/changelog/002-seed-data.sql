INSERT INTO tenants (id, name)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'AlphaFin Corp'),
    ('22222222-2222-2222-2222-222222222222', 'BetaPay Systems')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;

INSERT INTO runbook_chunks (tenant_id, runbook_name, chunk_text, source_path, git_diff)
VALUES
    (
        '11111111-1111-1111-1111-111111111111',
        'Payment Gateway Timeout & DB Deadlock Runbook (RB-PAY-01)',
        'For payment gateway timeouts accompanied by connection pool exhaustion, inspect transaction boundaries in batch refund processing. Check for unclosed prepared statements and long-running locks. Mitigate by rolling back the latest payment-service deployment, then verify pool utilization and database lock queues.',
        'runbooks/RB-PAY-01.md',
        E'--- a/RefundService.java\n+++ b/RefundService.java\n@@ -42,3 +42,4 @@\n+connection.setAutoCommit(false);'
    ),
    (
        '22222222-2222-2222-2222-222222222222',
        'Wallet Ledger Consistency Runbook (RB-WAL-02)',
        'For wallet ledger lag, verify consumer group health, inspect retry and dead-letter queues, and compare committed offsets with the ledger checkpoint. Restore consumers only after confirming idempotent event handling and no duplicate balance postings.',
        'runbooks/RB-WAL-02.md',
        E'--- a/LedgerConsumer.java\n+++ b/LedgerConsumer.java\n@@ -88,2 +88,3 @@\n+if (event.isAlreadyApplied()) return;'
    );
