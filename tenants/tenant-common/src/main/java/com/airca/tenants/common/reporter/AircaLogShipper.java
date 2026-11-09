package com.airca.tenants.common.reporter;

import com.airca.tenants.common.config.TenantAircaProperties;
import org.springframework.web.client.RestClient;

/**
 * Backward compatibility subclass for AircaLogReporter.
 */
public class AircaLogShipper extends AircaLogReporter {

    public AircaLogShipper(TenantAircaProperties properties, RestClient restClient) {
        super(properties, restClient);
    }

    public AircaLogShipper(TenantAircaProperties properties, RestClient.Builder restClientBuilder) {
        super(properties, restClientBuilder);
    }
}
