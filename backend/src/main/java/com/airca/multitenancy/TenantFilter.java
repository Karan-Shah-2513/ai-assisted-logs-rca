package com.airca.multitenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TenantFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-ID";
    private final UUID defaultTenantId;

    public TenantFilter(@Value("${airca.multitenancy.default-tenant-id}") UUID defaultTenantId) {
        this.defaultTenantId = defaultTenantId;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(TENANT_HEADER);
        UUID tenantId;
        try {
            tenantId = header == null || header.isBlank()
                    ? defaultTenantId
                    : UUID.fromString(header.trim());
            if (header != null && !header.isBlank()
                    && !tenantId.toString().equalsIgnoreCase(header.trim())) {
                throw new IllegalArgumentException("Tenant ID must be a canonical UUID");
            }
        } catch (IllegalArgumentException exception) {
            response.sendError(HttpStatus.BAD_REQUEST.value(), "X-Tenant-ID must be a valid UUID");
            return;
        }

        TenantContext.setTenantId(tenantId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
