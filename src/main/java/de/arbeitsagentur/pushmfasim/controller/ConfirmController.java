package de.arbeitsagentur.pushmfasim.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestTemplate;

@Controller
@RequestMapping("/confirm")
public class ConfirmController {

    private static final Logger logger = LoggerFactory.getLogger(ConfirmController.class);

    private final RestTemplate restTemplate;

    @Value("${app.jwk.path:static/keys/rsa-jwk.json}")
    private String jwkPath;

    @Value("classpath:static/keys/rsa-jwk.json")
    private Resource jwkResource;

    @Value("${app.defaultIamUrl:http://localhost:8080/realms/demo}")
    private String defaultIamUrl;

    @Value("${app.clientId:push-device-client}")
    private String clientId;

    @Value("${app.clientSecret:device-client-secret}")
    private String clientSecret;

    public ConfirmController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    private static final String DEVICE_ALIAS = "-device-alias-";
    private static final String DEVICE_STATIC_ID = "device-static-id";
    private static final String TOKEN_ENDPOINT = "/protocol/openid-connect/token";
    private static final String LOGIN_PENDING_ENDPOINT = "/push-mfa/login/pending";

    @GetMapping
    public String showInfoPage() {
        return "confirm-page";
    }

    @PostMapping(path = "/login")
    @ResponseBody
    @SuppressWarnings("null")
    public ResponseEntity<String> completeEnrollProcess(
            @RequestParam String token,
            @RequestParam(required = false) String context,
            @RequestParam(required = false, defaultValue = "approve") String action,
            @RequestParam(required = false) String userVerification,
            @RequestParam(required = false) String iamUrl)
            throws Exception {

        logger.info("Starting confirm login process");

        if (iamUrl == null || iamUrl.isEmpty()) {
            iamUrl = defaultIamUrl;
        }
        logger.debug("Using IAM URL: {}", iamUrl);

        // Parse and validate token
        JWT jwt = JWTParser.parse(token);
        JWTClaimsSet claims = jwt.getJWTClaimsSet();

        String challengeId = claims.getClaims().containsKey("cid") ? claims.getStringClaim("cid") : null;
        String credentialId = claims.getClaims().containsKey("credId") ? claims.getStringClaim("credId") : null;
        String tokenUserVerification =
                claims.getClaims().containsKey("userVerification") ? claims.getStringClaim("userVerification") : null;

        if (challengeId == null || credentialId == null) {
            logger.warn("Invalid token: missing required claims");
            return ResponseEntity.badRequest().body("Invalid token: missing required claims");
        }

        String effectiveAction =
                (action != null && !action.trim().isEmpty()) ? action.trim().toLowerCase() : "approve";
        String effectiveUserVerification = firstNonBlank(userVerification, tokenUserVerification, context);

        logger.debug(
                "Extracted claims - challengeId: {}, credentialId: {}, action: {}, userVerification: {}",
                challengeId,
                credentialId,
                effectiveAction,
                effectiveUserVerification);

        // Extract userId from credentialId
        String userId = extractUserIdFromCredentialId(credentialId);
        if (userId == null) {
            logger.warn("Unable to extract user id from credential id");
            return ResponseEntity.badRequest().body("Unable to extract user id from credential id");
        }

        try {
            // Load JWK keys
            ObjectMapper objectMapper = new ObjectMapper();

            // Versuche zuerst vom Dateisystem zu laden (für K8s-Deployment mit volumeMount)
            Resource jwkResource;
            try {
                jwkResource = new FileSystemResource(jwkPath);
                if (!jwkResource.exists()) {
                    // Fallback auf Classpath für lokale Entwicklung
                    jwkResource = new ClassPathResource("static/keys/rsa-jwk.json");
                }
            } catch (Exception e) {
                // Fallback auf Classpath
                jwkResource = new ClassPathResource("static/keys/rsa-jwk.json");
            }

            JsonNode root = objectMapper.readTree(jwkResource.getInputStream());
            JsonNode privateNode = root.get("private");

            Map<String, Object> privateMap =
                    objectMapper.convertValue(privateNode, new TypeReference<Map<String, Object>>() {});
            RSAKey privateJwk = RSAKey.parse(privateMap);

            // Create DPoP proof for access token request
            String dPopAccessTokenJwt = createDpopJwt(credentialId, "POST", iamUrl + TOKEN_ENDPOINT, privateJwk);

            // Get access token
            String accessToken = getAccessToken(iamUrl, dPopAccessTokenJwt);
            if (accessToken == null) {
                logger.warn("Failed to obtain access token");
                return ResponseEntity.status(401).body("Failed to obtain access token");
            }

            // Get pending challenges
            String pendingUrl =
                    iamUrl + LOGIN_PENDING_ENDPOINT + "?userId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8);
            String pendingDpop = createDpopJwt(credentialId, "GET", pendingUrl, privateJwk);
            JsonNode pendingJson = getPendingChallenges(pendingUrl, pendingDpop, accessToken);

            if (pendingJson == null || !pendingJson.has("challenges")) {
                logger.warn("Failed to get pending challenges");
                return ResponseEntity.status(400).body("Failed to get pending challenges");
            }

            // Check if challenge exists in pending list
            JsonNode pendingChallenge = null;
            for (JsonNode challenge : pendingJson.get("challenges")) {
                if (challenge.has("cid") && challenge.get("cid").asText().equals(challengeId)) {
                    pendingChallenge = challenge;
                    break;
                }
            }

            if (pendingChallenge == null) {
                logger.warn("Challenge not found in pending challenges");
                return ResponseEntity.status(404).body("Challenge not found");
            }

            // Check if user verification is required for approve action
            String pendingUserVerification = pendingChallenge.has("userVerification")
                    ? pendingChallenge.get("userVerification").asText()
                    : null;

            if ("approve".equals(effectiveAction)
                    && pendingUserVerification != null
                    && (effectiveUserVerification == null
                            || effectiveUserVerification.trim().isEmpty())) {
                logger.warn("User verification required but not provided");
                return ResponseEntity.badRequest().body("userVerification required");
            }

            // Post challenge response
            String challengeEndpoint = iamUrl + "/push-mfa/login/challenges/" + challengeId + "/respond";
            String dpopChallengeToken = createDpopJwt(credentialId, "POST", challengeEndpoint, privateJwk);
            String userVerifForChallenge = "approve".equals(effectiveAction) ? effectiveUserVerification : null;
            String challengeToken =
                    createChallengeToken(credentialId, challengeId, effectiveAction, userVerifForChallenge, privateJwk);

            ResponseEntity<String> challengeResponse =
                    postChallengesResponse(challengeEndpoint, dpopChallengeToken, accessToken, challengeToken);

            if (!challengeResponse.getStatusCode().is2xxSuccessful()) {
                logger.warn("Challenge response failed: {}", challengeResponse.getStatusCode());
                return ResponseEntity.status(challengeResponse.getStatusCode()).body(challengeResponse.getBody());
            }

            String responseMsg = String.format(
                    "userId: %s; responseStatus: %s; userVerification: %s; action: %s",
                    userId, challengeResponse.getStatusCode(), pendingUserVerification, effectiveAction);

            logger.info("Confirm login completed successfully: {}", responseMsg);
            return ResponseEntity.ok(responseMsg);

        } catch (Exception e) {
            logger.error("Error during confirm login process", e);
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private String extractUserIdFromCredentialId(String credentialId) {
        if (credentialId == null || credentialId.isBlank()) {
            return null;
        }

        int aliasIndex = credentialId.indexOf(DEVICE_ALIAS);
        if (aliasIndex < 0) {
            return null;
        }
        String userId = credentialId.substring(0, aliasIndex);
        return userId.isBlank() ? null : userId;
    }

    private String createDpopJwt(String credentialId, String method, String url, RSAKey privateJwk) throws Exception {

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("htm", method)
                .claim("htu", url)
                .claim("sub", extractUserIdFromCredentialId(credentialId))
                .claim("deviceId", DEVICE_STATIC_ID)
                .issueTime(java.util.Date.from(java.time.Instant.now()))
                .jwtID(UUID.randomUUID().toString())
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(new JOSEObjectType("dpop+jwt"))
                .jwk(privateJwk.toPublicJWK())
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claimsSet);
        signedJWT.sign(new RSASSASigner(privateJwk));

        return signedJWT.serialize();
    }

    private String createChallengeToken(
            String credentialId, String challengeId, String action, String userVerification, RSAKey privateJwk)
            throws Exception {
        long exp = (System.currentTimeMillis() / 1000) + 300;

        JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                .claim("cid", challengeId)
                .claim("credId", credentialId)
                .claim("deviceId", DEVICE_STATIC_ID)
                .claim("action", action)
                .expirationTime(new java.util.Date(exp * 1000));

        if (userVerification != null && !userVerification.trim().isEmpty()) {
            claimsBuilder.claim("userVerification", userVerification);
        }

        JWTClaimsSet claimsSet = claimsBuilder.build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID("DEVICE_KEY_ID")
                .type(new JOSEObjectType("JWT"))
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claimsSet);
        signedJWT.sign(new RSASSASigner(privateJwk));

        return signedJWT.serialize();
    }

    private String getAccessToken(String iamUrl, String dPopToken) throws Exception {
        String url = iamUrl + TOKEN_ENDPOINT;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("DPoP", dPopToken);
        logger.info("Requesting access token with client credentials {} and {}", clientId, clientSecret);

        // Use client credentials grant with device client ID/secret
        String body = "grant_type=client_credentials" + "&client_id=" + clientId + "&client_secret=" + clientSecret;

        HttpEntity<String> request = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode jsonNode = mapper.readTree(response.getBody());
                if (jsonNode.has("access_token")) {
                    return jsonNode.get("access_token").asText();
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get access token", e);
        }
        return null;
    }

    private JsonNode getPendingChallenges(String url, String dPopToken, String accessToken) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        headers.set("DPoP", dPopToken);

        HttpEntity<String> request = new HttpEntity<>(headers);
        try {
            @SuppressWarnings("null")
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                ObjectMapper mapper = new ObjectMapper();
                return mapper.readTree(response.getBody());
            }
        } catch (Exception e) {
            logger.error("Failed to get pending challenges", e);
        }
        return null;
    }

    private ResponseEntity<String> postChallengesResponse(
            String url, String dPopToken, String accessToken, String challengeToken) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + accessToken);
        headers.set("DPoP", dPopToken);

        ChallengeResponseRequest body = new ChallengeResponseRequest(challengeToken);

        HttpEntity<ChallengeResponseRequest> request = new HttpEntity<>(body, headers);

        try {
            @SuppressWarnings("null")
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            return response != null ? response : ResponseEntity.status(500).body("No response from server");
        } catch (Exception e) {
            logger.error("Failed to post challenge response", e);
            return ResponseEntity.status(500).body("Failed to post challenge response: " + e.getMessage());
        }
    }

    public record ChallengeResponseRequest(String token) {}
}
