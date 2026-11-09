package com.airca.tenants.common.advice;

import com.airca.tenants.common.config.TenantAircaProperties;
import com.airca.tenants.common.reporter.AircaLogReporter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class TenantGlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TenantGlobalExceptionHandler.class);

    public static final String CORRELATION_HEADER = "X-Correlation-ID";
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    private final AircaLogReporter logReporter;
    private final TenantAircaProperties properties;

    public TenantGlobalExceptionHandler(AircaLogReporter logReporter, TenantAircaProperties properties) {
        this.logReporter = logReporter;
        this.properties = properties;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(
            ResponseStatusException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        // Only ship to Airca if server error (5xx)
        if (status.is5xxServerError()) {
            String stackTrace = serializeStackTrace(ex);
            logReporter.reportError(properties.getServiceName(), stackTrace, correlationId);
        }

        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                ex.getReason() != null ? ex.getReason() : "A server error occurred",
                request != null ? request.getRequestURI() : "",
                correlationId
        );

        return ResponseEntity.status(status)
                .header(CORRELATION_HEADER, correlationId)
                .body(body);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ErrorResponse> handleUnhandledThrowable(
            Throwable ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);

        // Serialize the complete real exception stack trace
        String stackTrace = serializeStackTrace(ex);

        // Report to Airca
        logReporter.reportError(properties.getServiceName(), stackTrace, correlationId);

        // Preserve generic error response
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                "An unexpected internal error occurred. Please contact support referencing correlationId: " + correlationId,
                request != null ? request.getRequestURI() : "",
                correlationId
        );

        return ResponseEntity.status(status)
                .header(CORRELATION_HEADER, correlationId)
                .body(body);
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        if (request != null) {
            String cid = request.getHeader(CORRELATION_HEADER);
            if (cid != null && !cid.isBlank()) {
                return cid.trim();
            }
            String rid = request.getHeader(REQUEST_ID_HEADER);
            if (rid != null && !rid.isBlank()) {
                return rid.trim();
            }
        }
        return UUID.randomUUID().toString();
    }

    private String serializeStackTrace(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }
}
