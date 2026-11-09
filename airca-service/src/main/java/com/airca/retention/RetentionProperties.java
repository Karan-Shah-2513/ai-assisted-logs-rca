package com.airca.retention;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.retention")
public class RetentionProperties {

    private int incidentRetentionDays = 30;
    private int runbookChunkRetentionDays = 180;
    private String cleanupCron = "0 0 3 * * *";
    private boolean enabled = true;
    private long redisDedupTtlMinutes = 60;

    public int getIncidentRetentionDays() {
        return incidentRetentionDays;
    }

    public void setIncidentRetentionDays(int incidentRetentionDays) {
        this.incidentRetentionDays = incidentRetentionDays;
    }

    public int getRunbookChunkRetentionDays() {
        return runbookChunkRetentionDays;
    }

    public void setRunbookChunkRetentionDays(int runbookChunkRetentionDays) {
        this.runbookChunkRetentionDays = runbookChunkRetentionDays;
    }

    public String getCleanupCron() {
        return cleanupCron;
    }

    public void setCleanupCron(String cleanupCron) {
        this.cleanupCron = cleanupCron;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getRedisDedupTtlMinutes() {
        return redisDedupTtlMinutes;
    }

    public void setRedisDedupTtlMinutes(long redisDedupTtlMinutes) {
        this.redisDedupTtlMinutes = redisDedupTtlMinutes;
    }

    public Duration getIncidentRetentionDuration() {
        return Duration.ofDays(incidentRetentionDays);
    }

    public Duration getRunbookChunkRetentionDuration() {
        return Duration.ofDays(runbookChunkRetentionDays);
    }
}
