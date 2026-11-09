package com.airca.dto;

public record IngestResponse(String status, String fingerprint, String correlationId) {
}
