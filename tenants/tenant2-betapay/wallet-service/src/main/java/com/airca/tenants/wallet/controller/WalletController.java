package com.airca.tenants.wallet.controller;

import com.airca.tenants.common.config.TenantAircaProperties;
import com.airca.tenants.wallet.dto.FxQuoteRequest;
import com.airca.tenants.wallet.dto.WithdrawRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/wallet", "/api/v1/wallet"})
public class WalletController {

    public static final BigDecimal DEFAULT_LIQUIDITY_THRESHOLD = new BigDecimal("10000.00");

    private static final Map<String, BigDecimal> KNOWN_RATES = Map.ofEntries(
            Map.entry("EUR/USD", new BigDecimal("1.0850")),
            Map.entry("USD/EUR", new BigDecimal("0.9215")),
            Map.entry("GBP/USD", new BigDecimal("1.2650")),
            Map.entry("USD/GBP", new BigDecimal("0.7905")),
            Map.entry("USD/JPY", new BigDecimal("155.20")),
            Map.entry("JPY/USD", new BigDecimal("0.00644")),
            Map.entry("EUR/GBP", new BigDecimal("0.8575")),
            Map.entry("GBP/EUR", new BigDecimal("1.1660")),
            Map.entry("USD/CAD", new BigDecimal("1.3650")),
            Map.entry("CAD/USD", new BigDecimal("0.7326")),
            Map.entry("AUD/USD", new BigDecimal("0.6550")),
            Map.entry("USD/AUD", new BigDecimal("1.5267")),
            Map.entry("USD/CHF", new BigDecimal("0.9050")),
            Map.entry("CHF/USD", new BigDecimal("1.1050")),
            Map.entry("EUR/JPY", new BigDecimal("168.40")),
            Map.entry("JPY/EUR", new BigDecimal("0.00594")),
            Map.entry("GBP/JPY", new BigDecimal("196.30")),
            Map.entry("JPY/GBP", new BigDecimal("0.00509")),
            // Aliases without slashes
            Map.entry("EURUSD", new BigDecimal("1.0850")),
            Map.entry("USDEUR", new BigDecimal("0.9215")),
            Map.entry("GBPUSD", new BigDecimal("1.2650")),
            Map.entry("USDGBP", new BigDecimal("0.7905")),
            Map.entry("USDJPY", new BigDecimal("155.20")),
            Map.entry("JPYUSD", new BigDecimal("0.00644"))
    );

    private static final Map<String, BigDecimal> BASE_USD_RATES = Map.ofEntries(
            Map.entry("USD", new BigDecimal("1.0000")),
            Map.entry("EUR", new BigDecimal("1.0850")),
            Map.entry("GBP", new BigDecimal("1.2650")),
            Map.entry("JPY", new BigDecimal("0.00644")),
            Map.entry("CAD", new BigDecimal("0.7326")),
            Map.entry("AUD", new BigDecimal("0.6550")),
            Map.entry("CHF", new BigDecimal("1.1050")),
            Map.entry("NZD", new BigDecimal("0.6020")),
            Map.entry("SGD", new BigDecimal("0.7420")),
            Map.entry("HKD", new BigDecimal("0.1280")),
            Map.entry("INR", new BigDecimal("0.0118"))
    );

    private final TenantAircaProperties properties;

    public WalletController(TenantAircaProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "service", properties.getServiceName(),
                "tenantId", properties.getTenantId(),
                "status", "UP"
        );
    }

    /**
     * Intentional defect: withdrawal amount exceeding liquidity threshold triggers an unhandled
     * IllegalStateException representing a connection timeout/liquidity-lock timeout.
     * Within-threshold withdrawals succeed.
     */
    @PostMapping("/withdraw")
    public ResponseEntity<Map<String, Object>> withdraw(@RequestBody(required = false) WithdrawRequest request) {
        BigDecimal amount = (request != null && request.getAmount() != null)
                ? request.getAmount()
                : BigDecimal.valueOf(50.0);

        BigDecimal threshold = (request != null && request.resolveThreshold() != null)
                ? request.resolveThreshold()
                : DEFAULT_LIQUIDITY_THRESHOLD;

        // Intentional defect: exceeding liquidity threshold triggers unhandled IllegalStateException
        if (amount.compareTo(threshold) > 0) {
            throw new IllegalStateException(
                    "Liquidity lock timeout: withdrawal amount " + amount
                    + " exceeds available liquidity threshold " + threshold
                    + " - connection timeout waiting for liquidity reservation lock"
            );
        }

        String withdrawalId = (request != null && request.getWithdrawalId() != null)
                ? request.getWithdrawalId()
                : UUID.randomUUID().toString();

        String walletId = (request != null) ? request.resolveWalletId() : "WALLET-DEFAULT";
        String currency = (request != null && request.getCurrency() != null) ? request.getCurrency() : "USD";

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put("withdrawalId", withdrawalId);
        response.put("walletId", walletId);
        response.put("amount", amount);
        response.put("currency", currency);
        response.put("service", properties.getServiceName());

        return ResponseEntity.ok(response);
    }

    /**
     * Intentional defect: malformed/unvalidated currency-pair input causes a real NumberFormatException
     * through numeric parsing (not hand-thrown). Valid quote inputs succeed.
     */
    @PostMapping("/fx-quote")
    public ResponseEntity<Map<String, Object>> fxQuote(@RequestBody(required = false) FxQuoteRequest request) {
        String pairInput = (request != null) ? request.resolvePair() : null;

        BigDecimal rate = parseOrLookupRate(pairInput);

        BigDecimal amount = (request != null && request.getAmount() != null)
                ? request.getAmount()
                : BigDecimal.valueOf(100.0);

        BigDecimal convertedAmount = amount.multiply(rate).setScale(4, RoundingMode.HALF_UP);

        String quoteId = (request != null && request.getQuoteId() != null)
                ? request.getQuoteId()
                : UUID.randomUUID().toString();

        String[] currencies = extractCurrencies(pairInput);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put("quoteId", quoteId);
        response.put("currencyPair", pairInput != null ? pairInput : "EUR/USD");
        response.put("fromCurrency", (request != null && request.getFromCurrency() != null) ? request.getFromCurrency() : currencies[0]);
        response.put("toCurrency", (request != null && request.getToCurrency() != null) ? request.getToCurrency() : currencies[1]);
        response.put("rate", rate);
        response.put("amount", amount);
        response.put("convertedAmount", convertedAmount);
        response.put("service", properties.getServiceName());

        return ResponseEntity.ok(response);
    }

    private BigDecimal parseOrLookupRate(String pairInput) {
        if (pairInput == null || pairInput.isBlank()) {
            // Intentional unvalidated numeric parse: empty/null input causes real NumberFormatException
            double val = Double.parseDouble(String.valueOf(pairInput));
            return BigDecimal.valueOf(val);
        }

        String trimmed = pairInput.trim();
        String upper = trimmed.toUpperCase();

        // Custom rate specified via ':' or '@' separator (e.g. "EUR/USD:1.085" or "EUR/USD:invalid")
        if (trimmed.contains(":") || trimmed.contains("@")) {
            String[] segments = trimmed.split("[:@]");
            // Real numeric parsing of rate segment - unvalidated input throws real NumberFormatException
            double parsedRate = Double.parseDouble(segments[1].trim());
            return BigDecimal.valueOf(parsedRate);
        }

        // Custom rate specified via 3 segments (e.g. "EUR/USD/1.085" or "EUR/USD/invalid")
        if (trimmed.contains("/")) {
            String[] segments = trimmed.split("/");
            if (segments.length == 3) {
                // Real numeric parsing of rate segment - unvalidated input throws real NumberFormatException
                double parsedRate = Double.parseDouble(segments[2].trim());
                return BigDecimal.valueOf(parsedRate);
            }
            if (segments.length == 2) {
                String base = segments[0].trim().toUpperCase();
                String quote = segments[1].trim().toUpperCase();
                String key = base + "/" + quote;
                if (KNOWN_RATES.containsKey(key)) {
                    return KNOWN_RATES.get(key);
                }
                if (BASE_USD_RATES.containsKey(base) && BASE_USD_RATES.containsKey(quote)) {
                    BigDecimal baseUsd = BASE_USD_RATES.get(base);
                    BigDecimal quoteUsd = BASE_USD_RATES.get(quote);
                    return baseUsd.divide(quoteUsd, 4, RoundingMode.HALF_UP);
                }
                // Unvalidated currency pair input: attempt numeric parsing of non-matching pair segment
                double parsed = Double.parseDouble(segments[1].trim());
                return BigDecimal.valueOf(parsed);
            }
        }

        if (trimmed.contains("-")) {
            String[] segments = trimmed.split("-");
            if (segments.length == 3) {
                double parsedRate = Double.parseDouble(segments[2].trim());
                return BigDecimal.valueOf(parsedRate);
            }
            if (segments.length == 2) {
                String base = segments[0].trim().toUpperCase();
                String quote = segments[1].trim().toUpperCase();
                String key = base + "/" + quote;
                if (KNOWN_RATES.containsKey(key)) {
                    return KNOWN_RATES.get(key);
                }
                if (BASE_USD_RATES.containsKey(base) && BASE_USD_RATES.containsKey(quote)) {
                    BigDecimal baseUsd = BASE_USD_RATES.get(base);
                    BigDecimal quoteUsd = BASE_USD_RATES.get(quote);
                    return baseUsd.divide(quoteUsd, 4, RoundingMode.HALF_UP);
                }
                double parsed = Double.parseDouble(segments[1].trim());
                return BigDecimal.valueOf(parsed);
            }
        }

        // Standard unslashed key lookup (e.g. "EURUSD")
        if (KNOWN_RATES.containsKey(upper)) {
            return KNOWN_RATES.get(upper);
        }

        // Malformed/unvalidated currency-pair input (e.g. "INVALID", "malformed-quote", "foo"):
        // Real numeric parsing directly on the unvalidated currency-pair input string triggers real NumberFormatException
        double parsed = Double.parseDouble(trimmed);
        return BigDecimal.valueOf(parsed);
    }

    private String[] extractCurrencies(String pairInput) {
        if (pairInput == null || pairInput.isBlank()) {
            return new String[]{"EUR", "USD"};
        }
        String clean = pairInput.split("[:@]")[0].trim().toUpperCase();
        if (clean.contains("/")) {
            String[] parts = clean.split("/");
            if (parts.length >= 2) {
                return new String[]{parts[0], parts[1]};
            }
        }
        if (clean.contains("-")) {
            String[] parts = clean.split("-");
            if (parts.length >= 2) {
                return new String[]{parts[0], parts[1]};
            }
        }
        if (clean.length() == 6) {
            return new String[]{clean.substring(0, 3), clean.substring(3, 6)};
        }
        return new String[]{"EUR", "USD"};
    }
}
