package com.airca.multitenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TenantFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantFilter.class);

    public static final String TENANT_HEADER = "X-Tenant-ID";
    public static final String API_KEY_HEADER = "X-API-Key";

    private final TenantApiKeyService apiKeyService;

    @Autowired
    public TenantFilter(TenantApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            // Public non-API endpoints (e.g. /health, /actuator/health) and CORS preflights do not require API key
            if (!isSecuredApiRoute(request) || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
                filterChain.doFilter(request, response);
                return;
            }

            // 1. If X-Tenant-ID is supplied, validate canonical UUID format
            String tenantHeader = request.getHeader(TENANT_HEADER);
            UUID requestedTenantId = null;
            if (tenantHeader != null && !tenantHeader.isBlank()) {
                try {
                    requestedTenantId = UUID.fromString(tenantHeader.trim());
                    if (!requestedTenantId.toString().equalsIgnoreCase(tenantHeader.trim())) {
                        throw new IllegalArgumentException("Tenant ID must be a canonical UUID");
                    }
                } catch (IllegalArgumentException exception) {
                    LOGGER.warn("Invalid X-Tenant-ID header received: {}", exception.getMessage());
                    response.sendError(HttpStatus.BAD_REQUEST.value(), "X-Tenant-ID must be a valid UUID");
                    return;
                }
            }

            // 2. Validate X-API-Key (fail closed on missing/invalid credentials)
            String apiKey = request.getHeader(API_KEY_HEADER);
            if (apiKey == null || apiKey.isBlank()) {
                LOGGER.warn("Unauthorized request: missing {} header for route {}", API_KEY_HEADER, request.getRequestURI());
                response.sendError(HttpStatus.UNAUTHORIZED.value(), "Missing or invalid API key");
                return;
            }

            UUID authenticatedTenantId = apiKeyService.resolveTenantId(apiKey).orElse(null);
            if (authenticatedTenantId == null) {
                LOGGER.warn("Unauthorized request: invalid API key for route {}", request.getRequestURI());
                response.sendError(HttpStatus.UNAUTHORIZED.value(), "Missing or invalid API key");
                return;
            }

            // 3. If X-Tenant-ID is supplied, require it to match the key's tenant
            if (requestedTenantId != null && !requestedTenantId.equals(authenticatedTenantId)) {
                LOGGER.warn("Forbidden request: X-Tenant-ID {} does not match authenticated key tenant {}",
                        requestedTenantId, authenticatedTenantId);
                response.sendError(HttpStatus.FORBIDDEN.value(), "X-Tenant-ID does not match authenticated tenant");
                return;
            }

            // 4. Bind TenantContext only from the authenticated key
            TenantContext.setTenantId(authenticatedTenantId);
            filterChain.doFilter(request, response);
        } finally {
            // Always clear TenantContext in finally
            TenantContext.clear();
        }
    }

    private boolean isSecuredApiRoute(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        int semi = path.indexOf(';');
        if (semi != -1) {
            path = path.substring(0, semi);
        }
        return path.equals("/api") || path.startsWith("/api/");
    }
}
