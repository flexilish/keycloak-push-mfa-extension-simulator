package de.arbeitsagentur.pushmfasim.controller;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
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
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
    private static final String LOGIN_LOCKOUT_ENDPOINT = "/push-mfa/login/lockout";

    @GetMapping
    public String showInfoPage() {
        return "confirm-page";
    }

    @PostMapping(value = "/challenge")
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

        if (ObjectUtils.isEmpty(iamUrl)) {
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
        logger.debug("Successfully extracted userId: {} from credentialId", userId);

        try {
            // Load JWK keys
            ObjectMapper objectMapper = new ObjectMapper();

            // Versuche zuerst vom Dateisystem zu laden (für K8s-Deployment mit volumeMount)
            Resource jwkResource;

            jwkResource = new FileSystemResource(jwkPath);
            if (!jwkResource.exists()) {
                // Fallback auf Classpath für lokale Entwicklung
                jwkResource = new ClassPathResource("static/keys/rsa-jwk.json");
            }

            logger.debug("Loading JWK from: {}", jwkResource.getURI());
            JsonNode root = objectMapper.readTree(jwkResource.getInputStream());
            JsonNode privateNode = root.get("private");

            Map<String, Object> privateMap = objectMapper.convertValue(privateNode, new TypeReference<>() {});
            RSAKey privateJwk = RSAKey.parse(privateMap);
            logger.debug("JWK loaded successfully with key ID: {}", privateJwk.getKeyID());

            // Create DPoP proof for access token request
            logger.debug("Creating DPoP JWT for token endpoint: {}", iamUrl + TOKEN_ENDPOINT);
            String dPopAccessTokenJwt = createDpopJwt(credentialId, "POST", iamUrl + TOKEN_ENDPOINT, privateJwk);
            logger.debug("DPoP JWT created successfully");

            // Get access token
            logger.info("Requesting access token from Keycloak endpoint: {}", iamUrl + TOKEN_ENDPOINT);
            String accessToken = getAccessToken(iamUrl, dPopAccessTokenJwt);
            if (accessToken == null) {
                logger.warn("Failed to obtain access token from: {}", iamUrl + TOKEN_ENDPOINT);
                return ResponseEntity.status(401).body("Failed to obtain access token");
            }
            logger.info("Access token obtained successfully");
            String basePendingUrl = iamUrl + LOGIN_PENDING_ENDPOINT;

            String pendingUrl = basePendingUrl + "?userId=" + userId;
            logger.debug("Fetching pending challenges for userId: {} (encoded: {})", userId, basePendingUrl);
            // RFC 9449: htu must exclude query and fragment parts (userId)
            String pendingDpop = createDpopJwt(credentialId, "GET", basePendingUrl, privateJwk);
            logger.debug("DPoP JWT created for pending challenges endpoint: {}", basePendingUrl);
            JsonNode pendingJson = getPendingChallenges(pendingUrl, pendingDpop, accessToken);

            if (pendingJson == null || !pendingJson.has("challenges")) {
                logger.warn("Failed to get pending challenges from: {}", pendingUrl);
                return ResponseEntity.status(400).body("Failed to get pending challenges");
            }
            logger.debug(
                    "Retrieved pending challenges array with {} challenges",
                    pendingJson.get("challenges").size());

            // Check if challenge exists in pending list
            JsonNode pendingChallenge = null;
            for (JsonNode challenge : pendingJson.get("challenges")) {
                if (challenge.has("cid") && challenge.get("cid").asString().equals(challengeId)) {
                    pendingChallenge = challenge;
                    break;
                }
            }

            if (pendingChallenge == null) {
                logger.warn("Challenge with ID {} not found in pending challenges", challengeId);
                return ResponseEntity.status(404).body("Challenge not found");
            }
            logger.debug("Challenge {} found in pending challenges", challengeId);

            // Check if user verification is required for approve action
            String pendingUserVerification = pendingChallenge.has("userVerification")
                    ? pendingChallenge.get("userVerification").asString()
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
            logger.debug("Creating DPoP JWT for challenge endpoint: {}", challengeEndpoint);
            String dpopChallengeToken = createDpopJwt(credentialId, "POST", challengeEndpoint, privateJwk);
            String userVerifForChallenge = "approve".equals(effectiveAction) ? effectiveUserVerification : null;
            logger.info(
                    "Posting challenge response - action: {}, challengeId: {}, endpoint: {}",
                    effectiveAction,
                    challengeId,
                    challengeEndpoint);
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

    /*
     * Endpoint that locks out a user account on Keycloak.
     * The device sends a DPoP-authenticated request to disable the user account,
     * acting as a panic button if the account is compromised.
     */
    @PostMapping(value = "/lockout")
    @ResponseBody
    @SuppressWarnings("null")
    public ResponseEntity<String> loginLockout(
            @RequestParam String token,
            @RequestParam(required = false) String context,
            @RequestParam(required = false) String userVerification,
            @RequestParam(required = false) String iamUrl)
            throws Exception {

        logger.info("Starting user lockout process");

        if (iamUrl == null || iamUrl.isEmpty()) {
            iamUrl = defaultIamUrl;
        }
        logger.debug("Using IAM URL: {}", iamUrl);

        // Parse and validate token
        JWT jwt = JWTParser.parse(token);
        JWTClaimsSet claims = jwt.getJWTClaimsSet();

        String credentialId = claims.getClaims().containsKey("credId") ? claims.getStringClaim("credId") : null;

        if (credentialId == null) {
            logger.warn("Invalid token: missing credentialId");
            return ResponseEntity.badRequest().body("Invalid token: missing credentialId");
        }

        // Extract userId from credentialId
        String userId = extractUserIdFromCredentialId(credentialId);
        if (userId == null) {
            logger.warn("Unable to extract user id from credential id");
            return ResponseEntity.badRequest().body("Unable to extract user id from credential id");
        }
        logger.debug("Successfully extracted userId: {} from credentialId", userId);

        try {
            // Load JWK keys
            ObjectMapper objectMapper = new ObjectMapper();

            Resource jwkResource;

            jwkResource = new FileSystemResource(jwkPath);
            if (!jwkResource.exists()) {
                jwkResource = new ClassPathResource("static/keys/rsa-jwk.json");
            }

            JsonNode root = objectMapper.readTree(jwkResource.getInputStream());
            JsonNode privateNode = root.get("private");

            Map<String, Object> privateMap =
                    objectMapper.convertValue(privateNode, new TypeReference<Map<String, Object>>() {});
            RSAKey privateJwk = RSAKey.parse(privateMap);
            logger.debug("Private JWK loaded successfully");

            // Create DPoP JWT for token endpoint
            String tokenEndpointUrl = iamUrl + TOKEN_ENDPOINT;
            String dPopAccessTokenJwt = createDpopJwt(credentialId, "POST", tokenEndpointUrl, privateJwk);
            String accessToken = getAccessToken(iamUrl, dPopAccessTokenJwt);

            if (ObjectUtils.isEmpty(accessToken)) {
                logger.warn("Failed to obtain access token for lockout");
                return ResponseEntity.status(401).body("Failed to obtain access token");
            }
            logger.info("Access token obtained successfully for lockout");

            // Create DPoP JWT for lockout endpoint
            String lockoutEndpoint = iamUrl + LOGIN_LOCKOUT_ENDPOINT;
            logger.debug("Creating DPoP JWT for lockout endpoint: {}", lockoutEndpoint);
            String dpopLockoutToken = createDpopJwt(credentialId, "POST", lockoutEndpoint, privateJwk);

            logger.info("Posting lockout request for userId: {}", userId);
            ResponseEntity<String> lockoutResponse = postLockout(lockoutEndpoint, dpopLockoutToken, accessToken);

            if (!lockoutResponse.getStatusCode().is2xxSuccessful()) {
                logger.warn("Lockout request failed: {}", lockoutResponse.getStatusCode());
                return ResponseEntity.status(lockoutResponse.getStatusCode())
                        .body("Lockout failed: " + lockoutResponse.getStatusCode());
            }

            String responseMsg = String.format(
                    "userId: %s; lockout status: %s; account disabled", userId, lockoutResponse.getStatusCode());

            logger.info("User lockout completed successfully: {}", responseMsg);
            return ResponseEntity.ok(responseMsg);

        } catch (Exception e) {
            logger.error("Error during lockout process", e);
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
        logger.trace("Creating DPoP JWT - method: {}, url: {}", method, url);

        String userId = extractUserIdFromCredentialId(credentialId);
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("htm", method)
                .claim("htu", url)
                .claim("sub", userId)
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
        logger.trace(
                "DPoP JWT created successfully with jti: {}",
                signedJWT.getJWTClaimsSet().getJWTID());

        return signedJWT.serialize();
    }

    private String createChallengeToken(
            String credentialId, String challengeId, String action, String userVerification, RSAKey privateJwk)
            throws Exception {
        logger.trace(
                "Creating challenge token - action: {}, challengeId: {}, userVerification: {}",
                action,
                challengeId,
                userVerification != null && !userVerification.isEmpty());
        long exp = (System.currentTimeMillis() / 1000) + 300;

        JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder()
                .claim("cid", challengeId)
                .claim("credId", credentialId)
                .claim("deviceId", DEVICE_STATIC_ID)
                .claim("action", action)
                .expirationTime(new java.util.Date(exp * 1000));

        if (userVerification != null && !userVerification.trim().isEmpty()) {
            claimsBuilder.claim("userVerification", userVerification);
            logger.trace("User verification added to challenge token");
        }

        JWTClaimsSet claimsSet = claimsBuilder.build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID("DEVICE_KEY_ID")
                .type(new JOSEObjectType("JWT"))
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claimsSet);
        signedJWT.sign(new RSASSASigner(privateJwk));
        logger.trace("Challenge token signed successfully");

        return signedJWT.serialize();
    }

    private String getAccessToken(String iamUrl, String dPopToken) {
        String url = iamUrl + TOKEN_ENDPOINT;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("DPoP", dPopToken);
        logger.debug("Requesting access token with client ID: {} from: {}", clientId, url);

        // Use client credentials grant with device client ID/secret
        String body = "grant_type=client_credentials" + "&client_id=" + clientId + "&client_secret=" + clientSecret;

        HttpEntity<String> request = new HttpEntity<>(body, headers);
        try {
            logger.trace("Sending token request to Keycloak");
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            logger.debug("Token endpoint response status: {}", response.getStatusCode());

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode jsonNode = mapper.readTree(response.getBody());
                if (jsonNode.has("access_token")) {
                    String token = jsonNode.get("access_token").asString();
                    logger.debug("Access token obtained successfully, token length: {}", token.length());
                    return token;
                } else {
                    logger.warn("Access token not found in response");
                }
            } else {
                logger.warn(
                        "Token endpoint returned unsuccessful status: {}, body: {}",
                        response.getStatusCode(),
                        response.getBody());
            }
        } catch (Exception e) {
            logger.error("Failed to get access token from {}", url, e);
        }
        return null;
    }

    @SuppressWarnings("null")
    JsonNode getPendingChallenges(String url, String dPopToken, String accessToken) {
        logger.info("Fetching pending challenges from: {}", url);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        headers.set("DPoP", dPopToken);
        logger.trace("Prepared HTTP headers with Authorization and DPoP for pending challenges request");

        HttpEntity<String> request = new HttpEntity<>(headers);
        long startTime = System.currentTimeMillis();
        try {
            logger.debug("Sending GET request to pending challenges endpoint");
            @SuppressWarnings("null")
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);
            long duration = System.currentTimeMillis() - startTime;
            logger.debug(
                    "Pending challenges endpoint response status: {} (received in {} ms)",
                    response.getStatusCode(),
                    duration);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode result = mapper.readTree(response.getBody());
                logger.trace(
                        "Response body parsed successfully, size: {} bytes",
                        response.getBody().length());

                if (result.has("challenges")) {
                    int challengeCount = result.get("challenges").size();
                    logger.info("Successfully retrieved pending challenges: {} challenge(s) available", challengeCount);
                } else {
                    logger.warn("Response does not contain 'challenges' field");
                }

                return result;
            } else {
                String responseBody = response.getBody();
                logger.warn(
                        "Pending challenges endpoint returned unsuccessful status: {} with body: {}",
                        response.getStatusCode(),
                        responseBody != null
                                ? responseBody.substring(0, Math.min(200, responseBody.length()))
                                : "null");
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error(
                    "Failed to get pending challenges from {} after {} ms. Error: {}",
                    url,
                    duration,
                    e.getMessage(),
                    e);
        }
        return null;
    }

    @SuppressWarnings("null")
    private ResponseEntity<String> postChallengesResponse(
            String url, String dPopToken, String accessToken, String challengeToken) throws Exception {
        logger.debug("Posting challenge response to: {}", url);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + accessToken);
        headers.set("DPoP", dPopToken);

        ChallengeResponseRequest body = new ChallengeResponseRequest(challengeToken);

        HttpEntity<ChallengeResponseRequest> request = new HttpEntity<>(body, headers);

        try {
            logger.trace("Sending POST request with challenge token to Keycloak");
            @SuppressWarnings("null")
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            logger.info("Challenge response posted successfully to {}, status: {}", url, response.getStatusCode());
            logger.debug(
                    "Challenge response body length: {}",
                    response.getBody() != null ? response.getBody().length() : 0);
            return response != null ? response : ResponseEntity.status(500).body("No response from server");
        } catch (Exception e) {
            logger.error("Failed to post challenge response to {}", url, e);
            return ResponseEntity.status(500).body("Failed to post challenge response: " + e.getMessage());
        }
    }

    @SuppressWarnings("null")
    private ResponseEntity<String> postLockout(String url, String dPopToken, String accessToken) throws Exception {
        logger.debug("Posting lockout request to: {}", url);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "DPoP " + accessToken);
        headers.set("DPoP", dPopToken);

        HttpEntity<Void> request = new HttpEntity<>(null, headers);

        try {
            logger.trace("Sending POST request to lockout endpoint");
            @SuppressWarnings("null")
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            logger.info("Lockout request posted successfully to {}, status: {}", url, response.getStatusCode());
            return response != null ? response : ResponseEntity.status(500).body("No response from server");
        } catch (Exception e) {
            logger.error("Failed to post lockout request to {}", url, e);
            return ResponseEntity.status(500).body("Failed to post lockout request: " + e.getMessage());
        }
    }

    public record ChallengeResponseRequest(String token) {}
}
