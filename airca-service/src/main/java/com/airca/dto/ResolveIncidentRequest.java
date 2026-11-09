package com.airca.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResolveIncidentRequest(
        @NotBlank(message = "verifiedRootCause must not be blank")
        @Size(max = 4000, message = "verifiedRootCause must not exceed 4000 characters")
        String verifiedRootCause,

        @NotBlank(message = "verifiedFixCommandOrPR must not be blank")
        @Size(max = 4000, message = "verifiedFixCommandOrPR must not exceed 4000 characters")
        String verifiedFixCommandOrPR,

        @NotBlank(message = "resolvedBy must not be blank")
        @Size(max = 200, message = "resolvedBy must not exceed 200 characters")
        String resolvedBy
) {
}
