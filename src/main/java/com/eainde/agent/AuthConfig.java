package com.eainde.agent;

import com.eainde.agent.auth.TokenFetchService; // Your existing Azure service
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdentityPoolCredentials;
import com.google.auth.oauth2.ImpersonatedCredentials;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;

@Configuration
public class AuthConfig {

    @Bean
    public GoogleCredentials vertexAiCredentials(
            TokenFetchService azureTokenService,
            @Value("${gcp.project-number}") String projectNumber,
            @Value("${wif.pool-id}") String poolId,
            @Value("${wif.provider-id}") String providerId,
            @Value("${wif.service-account}") String serviceAccountEmail) {

        // 1. Define the Audience String (Resource Name)
        // Format: //iam.googleapis.com/projects/{PROJECT_NUMBER}/locations/global/workloadIdentityPools/{POOL}/providers/{PROVIDER}
        String audience = String.format(
                "//iam.googleapis.com/projects/%s/locations/global/workloadIdentityPools/%s/providers/%s",
                projectNumber, poolId, providerId
        );

        // 2. Create the Identity Pool Credential (Azure -> Federated Token)
        // This replaces your manual REST call to sts.googleapis.com
        IdentityPoolCredentials federatedCreds = IdentityPoolCredentials.newBuilder()
                .setAudience(audience)
                .setSubjectTokenType("urn:ietf:params:oauth:token-type:jwt") // Crucial: Tell Google it's a generic JWT
                .setSubjectTokenSupplier(azureTokenService::getTokenFromAzure) // Your existing method ref
                .build();

        // 3. Chain it: Use Federated Token -> Impersonate Service Account
        // This replaces your manual REST call to iamcredentials.googleapis.com
        return ImpersonatedCredentials.create(
                federatedCreds,         // The source (Federated Identity)
                serviceAccountEmail,    // The target (Service Account)
                Collections.emptyList(), // No delegates
                List.of("https://www.googleapis.com/auth/cloud-platform"), // Scope
                3600                     // Lifetime (seconds)
        );
    }
}