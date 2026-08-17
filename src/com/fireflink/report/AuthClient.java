package com.fireflink.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/**
 * Handles POST /appmanagement/optimize/v1/public/user/signin and extracts
 * the bearer token from the response.
 *
 * NOTE: the exact response field name has not been confirmed yet (only the
 * request was shared). This tries a shortlist of common names in order:
 * token, accessToken, access_token, jwt, bearerToken - all searched both at
 * the top level and inside a nested "responseObject" (matching the envelope
 * shape every other endpoint uses). Once you run this and see the real
 * response, tell me the actual field name/path and I'll pin it down exactly
 * instead of guessing.
 */
public class AuthClient {

    private static final List<String> CANDIDATE_TOKEN_FIELDS = List.of(
            "token", "accessToken", "access_token", "jwt", "bearerToken", "idToken"
    );

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Config config;

    public AuthClient(HttpClient http, Config config) {
        this.http = http;
        this.config = config;
    }

    public String signIn() throws Exception {
        String body = mapper.writeValueAsString(new SignInRequest(config.email, config.password));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl + "/appmanagement/optimize/v1/public/user/signin"))
                .header("Content-Type", "application/json")
                .header("Accept", "*/*")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Sign-in failed: HTTP " + response.statusCode() + " - " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        String token = findToken(root);
        if (token == null) {
            throw new RuntimeException("Sign-in succeeded but no recognizable token field was found. " +
                    "Raw response: " + response.body() +
                    "\nUpdate AuthClient.CANDIDATE_TOKEN_FIELDS with the real field name.");
        }
        return token;
    }

    private String findToken(JsonNode root) {
        for (String field : CANDIDATE_TOKEN_FIELDS) {
            if (root.hasNonNull(field)) {
                return root.get(field).asText();
            }
        }
        JsonNode nested = root.get("responseObject");
        if (nested != null) {
            for (String field : CANDIDATE_TOKEN_FIELDS) {
                if (nested.hasNonNull(field)) {
                    return nested.get(field).asText();
                }
            }
        }
        return null;
    }

    private static class SignInRequest {
        public String emailId;
        public String password;
        SignInRequest(String emailId, String password) {
            this.emailId = emailId;
            this.password = password;
        }
    }
}
