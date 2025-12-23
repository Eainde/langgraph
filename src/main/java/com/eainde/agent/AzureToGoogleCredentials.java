package com.eainde.agent;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.OAuth2Credentials;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

public class AzureToGoogleCredentials extends OAuth2Credentials {

    private final TokenFetchService azureTokenService;
    private final RestTemplate restTemplate;

    // Configuration Variables
    private final String projectNumber;       // e.g., "123456789" (Must be numeric)
    private final String poolId;              // e.g., "my-pool"
    private final String providerId;          // e.g., "my-provider"
    private final String serviceAccountEmail; // e.g., "my-sa@my-project.iam.gserviceaccount.com"

    public AzureToGoogleCredentials(TokenFetchService azureTokenService,
                                    String projectNumber,
                                    String poolId,
                                    String providerId,
                                    String serviceAccountEmail) {
        this.azureTokenService = azureTokenService;
        this.restTemplate = new RestTemplate();
        this.projectNumber = projectNumber;
        this.poolId = poolId;
        this.providerId = providerId;
        this.serviceAccountEmail = serviceAccountEmail;
    }

    /**
     * This override is critical. Spring AI/LangChain will call this automatically
     * whenever the token is expired or missing.
     */
    @Override
    public AccessToken refreshAccessToken() throws IOException {
        try {
            // 1. Get Azure Token (using your existing logic)
            String azureToken = azureTokenService.getTokenFromAzure();

            // 2. Exchange Azure Token for Google STS (Federated) Token
            String federatedToken = exchangeAzureForFederatedToken(azureToken);

            // 3. Exchange STS Token for actual Service Account Access Token
            return exchangeFederatedForAccessToken(federatedToken);

        } catch (Exception e) {
            // Must wrap in IOException for the Google Library signature
            throw new IOException("Failed to perform Workload Identity Federation exchange", e);
        }
    }

    // -------------------------------------------------------------------------
    // STEP 1: Exchange Azure Token -> Google STS Token
    // -------------------------------------------------------------------------
    private String exchangeAzureForFederatedToken(String azureToken) {
        String stsUrl = "https://sts.googleapis.com/v1/token";

        // Build the Audience String: //iam.googleapis.com/projects/{PROJECT_NUMBER}/...
        String audience = String.format(
                "//iam.googleapis.com/projects/%s/locations/global/workloadIdentityPools/%s/providers/%s",
                projectNumber, poolId, providerId
        );

        // Prepare Headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        // Prepare Body (Form Data)
        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
        map.add("requested_token_type", "urn:ietf:params:oauth:token-type:access_token");
        map.add("subject_token_type", "urn:ietf:params:oauth:token-type:jwt");
        map.add("subject_token", azureToken); // Inject your Azure Token
        map.add("audience", audience);
        map.add("scope", "https://www.googleapis.com/auth/cloud-platform");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);

        // Execute POST Request
        ResponseEntity<Map> response = restTemplate.postForEntity(stsUrl, request, Map.class);

        // Extract Token
        Map<String, Object> body = response.getBody();
        if (body == null || !body.containsKey("access_token")) {
            throw new IllegalStateException("STS response did not contain access_token");
        }

        return (String) body.get("access_token");
    }

    // -------------------------------------------------------------------------
    // STEP 2: Exchange STS Token -> Service Account Access Token
    // -------------------------------------------------------------------------
    private AccessToken exchangeFederatedForAccessToken(String federatedToken) {
        String impersonationUrl = String.format(
                "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/%s:generateAccessToken",
                serviceAccountEmail
        );

        // Prepare Headers (Bearer Auth using the STS token)
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(federatedToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Prepare Body (JSON)
        // Requesting a token valid for 3600 seconds (1 hour)
        Map<String, Object> requestBody = Map.of(
                "scope", Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"),
                "lifetime", "3600s"
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        // Execute POST Request
        ResponseEntity<Map> response = restTemplate.postForEntity(impersonationUrl, request, Map.class);
        Map<String, Object> responseBody = response.getBody();

        if (responseBody == null || !responseBody.containsKey("accessToken")) {
            throw new IllegalStateException("Service Account impersonation failed: missing accessToken");
        }

        String finalToken = (String) responseBody.get("accessToken");

        // Handle Expiration:
        // We set it to 55 minutes to ensure we refresh slightly before the 60 min server expiry.
        Date expirationDate = Date.from(Instant.now().plus(55, ChronoUnit.MINUTES));

        return new AccessToken(finalToken, expirationDate);
    }
}