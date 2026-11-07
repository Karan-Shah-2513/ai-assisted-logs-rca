package com.airca.rag;

import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class MockGitDiffService {

    public String findRelevantDiff(String serviceName, String stackTrace) {
        String service = serviceName == null ? "" : serviceName.toLowerCase(Locale.ROOT);
        String error = stackTrace == null ? "" : stackTrace.toLowerCase(Locale.ROOT);

        if (service.contains("payment") || error.contains("deadlock")
                || error.contains("connection pool") || error.contains("sql")) {
            return """
                    Commit a9f120e - "refactor: batch refunds" by alice@alphafin.com
                    --- a/payment-service/src/main/java/com/airca/payment/RefundService.java
                    +++ b/payment-service/src/main/java/com/airca/payment/RefundService.java
                    @@ -42,8 +42,12 @@ public void refundBatch(List<Refund> refunds) {
                    +  connection.setAutoCommit(false);
                    +  for (Refund refund : refunds) {
                    +    statement.executeUpdate(refundSql(refund));
                    +  }
                    +  connection.commit();
                    +  // Previous implementation closed each statement per refund.
                    """;
        }
        if (service.contains("auth") || error.contains("nullpointer")
                || error.contains("null pointer")) {
            return """
                    Commit 7c41bd2 - "optimize token refresh path" by maya@alphafin.com
                    --- a/auth-service/src/main/java/com/airca/auth/TokenRefreshHandler.java
                    +++ b/auth-service/src/main/java/com/airca/auth/TokenRefreshHandler.java
                    @@ -56,6 +56,9 @@ public Token refresh(Session session) {
                    -  return session.getUser().getToken();
                    +  User user = session.getUser();
                    +  return user.getToken();
                    +  // Regression: anonymous refresh sessions may not have a user.
                    """;
        }
        if (service.contains("cache") || service.contains("redis") || error.contains("oom")) {
            return """
                    Commit d02ea91 - "cache serialized responses" by leo@alphafin.com
                    --- a/cache-service/src/main/java/com/airca/cache/ResponseCache.java
                    +++ b/cache-service/src/main/java/com/airca/cache/ResponseCache.java
                    @@ -18,4 +18,8 @@ public void put(String key, byte[] value) {
                    +  cache.set(key, value);
                    +  // Regression: unbounded payloads and no expiry increased Redis memory use.
                    +  metrics.recordCacheWrite(value.length);
                    """;
        }

        return """
                Commit f19ac84 - "add service retry handling" by dev@alphafin.com
                --- a/service/src/main/java/com/airca/service/RetryHandler.java
                +++ b/service/src/main/java/com/airca/service/RetryHandler.java
                @@ -21,3 +21,5 @@ public Result call(Request request) {
                +  return retryTemplate.execute(context -> client.send(request));
                +  // Review retry bounds and timeout behavior for the affected dependency.
                """;
    }
}
