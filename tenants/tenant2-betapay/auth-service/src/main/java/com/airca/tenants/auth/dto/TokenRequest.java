package com.airca.tenants.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TokenRequest {

    private String token;
    private String jwt;
    private String claim;
    private Object claims;
    private List<String> tokens;
    private List<String> claimStrings;
    private Long exp;
    private String sub;

    public TokenRequest() {
    }

    public TokenRequest(String token) {
        this.token = token;
    }

    @JsonCreator
    public static TokenRequest fromJson(JsonNode node) {
        TokenRequest request = new TokenRequest();
        if (node == null || node.isNull()) {
            return request;
        }

        if (node.isTextual()) {
            request.setToken(node.asText());
            return request;
        }

        if (node.isArray()) {
            List<String> list = new ArrayList<>();
            for (JsonNode item : node) {
                if (item != null && !item.isNull()) {
                    list.add(item.isTextual() ? item.asText() : item.toString());
                }
            }
            request.setTokens(list);
            return request;
        }

        if (node.isObject()) {
            if (node.has("token") && !node.get("token").isNull()) {
                request.setToken(node.get("token").asText());
            }
            if (node.has("jwt") && !node.get("jwt").isNull()) {
                request.setJwt(node.get("jwt").asText());
            }
            if (node.has("claim") && !node.get("claim").isNull()) {
                request.setClaim(node.get("claim").asText());
            }
            if (node.has("claims") && !node.get("claims").isNull()) {
                JsonNode cNode = node.get("claims");
                if (cNode.isTextual()) {
                    request.setClaims(cNode.asText());
                } else if (cNode.isArray()) {
                    List<String> list = new ArrayList<>();
                    for (JsonNode item : cNode) {
                        list.add(item.isTextual() ? item.asText() : item.toString());
                    }
                    request.setClaimStrings(list);
                } else {
                    request.setClaims(cNode.toString());
                }
            }
            if (node.has("tokens") && node.get("tokens").isArray()) {
                List<String> list = new ArrayList<>();
                for (JsonNode item : node.get("tokens")) {
                    list.add(item.isTextual() ? item.asText() : item.toString());
                }
                request.setTokens(list);
            }
            if (node.has("claimStrings") && node.get("claimStrings").isArray()) {
                List<String> list = new ArrayList<>();
                for (JsonNode item : node.get("claimStrings")) {
                    list.add(item.isTextual() ? item.asText() : item.toString());
                }
                request.setClaimStrings(list);
            }
            if (node.has("exp") && !node.get("exp").isNull()) {
                if (node.get("exp").isNumber()) {
                    request.setExp(node.get("exp").asLong());
                }
            }
            if (node.has("sub") && !node.get("sub").isNull()) {
                request.setSub(node.get("sub").asText());
            }

            // If the JSON object itself represents raw claims directly (e.g. {"sub": "user", "exp": 1234})
            if (request.getToken() == null && request.getJwt() == null && request.getClaim() == null
                    && request.getClaims() == null && request.getTokens() == null && request.getClaimStrings() == null) {
                if (!node.isEmpty()) {
                    request.setClaims(node.toString());
                }
            }
        }

        return request;
    }

    public List<String> resolveClaimStrings() {
        List<String> list = new ArrayList<>();
        if (tokens != null) {
            for (String t : tokens) {
                if (t != null && !t.isBlank()) {
                    list.add(t.trim());
                }
            }
        }
        if (claimStrings != null) {
            for (String c : claimStrings) {
                if (c != null && !c.isBlank()) {
                    list.add(c.trim());
                }
            }
        }
        if (token != null && !token.isBlank()) {
            list.add(token.trim());
        }
        if (jwt != null && !jwt.isBlank()) {
            list.add(jwt.trim());
        }
        if (claim != null && !claim.isBlank()) {
            list.add(claim.trim());
        }
        if (claims instanceof String s && !s.isBlank()) {
            list.add(s.trim());
        } else if (claims instanceof List<?> l) {
            for (Object item : l) {
                if (item != null) {
                    list.add(item.toString().trim());
                }
            }
        }
        return list;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getJwt() {
        return jwt;
    }

    public void setJwt(String jwt) {
        this.jwt = jwt;
    }

    public String getClaim() {
        return claim;
    }

    public void setClaim(String claim) {
        this.claim = claim;
    }

    public Object getClaims() {
        return claims;
    }

    public void setClaims(Object claims) {
        this.claims = claims;
    }

    public List<String> getTokens() {
        return tokens;
    }

    public void setTokens(List<String> tokens) {
        this.tokens = tokens;
    }

    public List<String> getClaimStrings() {
        return claimStrings;
    }

    public void setClaimStrings(List<String> claimStrings) {
        this.claimStrings = claimStrings;
    }

    public Long getExp() {
        return exp;
    }

    public void setExp(Long exp) {
        this.exp = exp;
    }

    public String getSub() {
        return sub;
    }

    public void setSub(String sub) {
        this.sub = sub;
    }
}
