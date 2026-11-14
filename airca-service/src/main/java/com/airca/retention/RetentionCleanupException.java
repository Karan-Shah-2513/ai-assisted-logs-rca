package com.airca.retention;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public class RetentionCleanupException extends RuntimeException {

    private final Map<UUID, String> tenantErrors;

    public RetentionCleanupException(String message) {
        super(message);
        this.tenantErrors = Collections.emptyMap();
    }

    public RetentionCleanupException(String message, Throwable cause) {
        super(message, cause);
        this.tenantErrors = Collections.emptyMap();
    }

    public RetentionCleanupException(String message, Map<UUID, String> tenantErrors) {
        super(message);
        this.tenantErrors = tenantErrors != null ? Collections.unmodifiableMap(tenantErrors) : Collections.emptyMap();
    }

    public Map<UUID, String> getTenantErrors() {
        return tenantErrors;
    }
}
