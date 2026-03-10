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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Controller
@RequestMapping("/enroll")
public class EnrollController {

    private static final Logger logger = LoggerFactory.getLogger(EnrollController.class);

    private final RestTemplate restTemplate;

    @Value("${app.jwk.path:static/keys/rsa-jwk.json}")
    private String jwkPath;

    @Value("${app.enroll.complete.url:http://localhost:8080/realms/demo/push-mfa/enroll/complete}")
    private String defaultIamUrl;

    public EnrollController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping
    public String showEnrollPage() {
        return "enroll-page";
    }

    @SuppressWarnings("null")
    @PostMapping(value = "/complete")
    @ResponseBody
    public ResponseEntity<String> completeEnrollProcess(
            @RequestParam String token,
            @RequestParam(required = false) String context,
            @RequestParam(required = false) String iamUrl,
            @RequestParam(required = false) String pushProviderType)
            throws Exception {

        logger.info("Starting enrollment completion process");

        if (iamUrl == null || iamUrl.isEmpty()) {
            iamUrl = defaultIamUrl;
        }
        logger.debug("Using IAM URL: {}", iamUrl);
        logger.trace("Parsing enrollment token");
        JWT jwt = JWTParser.parse(token);
        JWTClaimsSet claims = jwt.getJWTClaimsSet();

        // Unpack token
        String enrollmentId =
                claims.getClaims().containsKey("enrollmentId") ? claims.getStringClaim("enrollmentId") : null;
        String nonce = claims.getClaims().containsKey("nonce") ? claims.getStringClaim("nonce") : null;
        String userId = claims.getClaims().containsKey("sub") ? claims.getStringClaim("sub") : null;

        logger.debug(
                "Extracted claims - enrollmentId: {}, userId: {}, nonce present: {}",
                enrollmentId,
                userId,
                nonce != null);

        if (enrollmentId == null || nonce == null || userId == null) {
            logger.warn("Invalid token: missing required claims");
            return ResponseEntity.badRequest().body("Invalid token: missing required claims");
        }

        ObjectMapper objectMapper = new ObjectMapper();

        // Versuche zuerst vom Dateisystem zu laden (für K8s-Deployment mit volumeMount)
        Resource jwkResource;
        try {
            jwkResource = new FileSystemResource(jwkPath);
            if (!jwkResource.exists()) {
                // Fallback auf Classpath für lokale Entwicklung
                logger.debug("JWK file not found at {}, falling back to classpath", jwkPath);
                jwkResource = new ClassPathResource("static/keys/rsa-jwk.json");
            } else {
                logger.debug("Loading JWK from file system: {}", jwkPath);
            }
        } catch (Exception e) {
            // Fallback auf Classpath
            logger.debug("Exception while loading JWK from file system, falling back to classpath: {}", e.getMessage());
            jwkResource = new ClassPathResource("static/keys/rsa-jwk.json");
        }

        logger.trace("Loading JWK from resource: {}", jwkResource);

        JsonNode root = objectMapper.readTree(jwkResource.getInputStream());
        JsonNode publicNode = root.get("public");
        JsonNode privateNode = root.get("private");
        logger.debug("JWK loaded successfully with public and private keys");

        Map<String, Object> publicMap = objectMapper.convertValue(publicNode, new TypeReference<>() {});
        Map<String, Object> privateMap = objectMapper.convertValue(privateNode, new TypeReference<>() {});
        RSAKey publicJwk = RSAKey.parse(publicMap);
        RSAKey privateJwk = RSAKey.parse(privateMap);
        logger.debug("RSA keys parsed successfully");

        Map<String, Object> cnf = Map.of("jwk", publicJwk.toPublicJWK().toJSONObject());

        // Build enrollment JWT
        logger.trace(
                "Building enrollment JWT with claims - enrollmentId: {}, userId: {}, deviceType: ios, pushProviderType: {}",
                enrollmentId,
                userId,
                pushProviderType != null && !pushProviderType.isEmpty() ? pushProviderType : "log");
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("enrollmentId", enrollmentId)
                .claim("nonce", nonce)
                .subject(userId)
                .claim("deviceType", "ios")
                .claim("deviceId", "device-static-id")
                .claim("deviceLabel", "Demo Phone")
                .claim("pushProviderId", "demo-push-provider-token")
                .claim(
                        "pushProviderType",
                        pushProviderType != null && !pushProviderType.isEmpty() ? pushProviderType : "log")
                .claim("credentialId", userId + "-device-alias-" + context)
                .claim("cnf", cnf)
                .build();
        logger.debug("Enrollment JWT claims set created");

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID("DEVICE_KEY_ID")
                .type(new JOSEObjectType("JWT"))
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claimsSet);
        signedJWT.sign(new RSASSASigner(privateJwk));
        String enrollmentToken = signedJWT.serialize();
        logger.debug("Enrollment token generated and signed successfully, token length: {}", enrollmentToken.length());

        Map<String, Object> body = Map.of("token", enrollmentToken);

        String jsonBody = objectMapper.writeValueAsString(body);

        // POST to Keycloak
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        List<MediaType> acceptList = new ArrayList<>();
        acceptList.add(MediaType.APPLICATION_JSON);
        headers.setAccept(acceptList);
        logger.debug("Prepared HTTP headers for enrollment completion request");

        HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);

        Objects.requireNonNull(iamUrl, "iamUrl must not be null");
        Objects.requireNonNull(HttpMethod.POST, "httpMethod must not be null");

        String enrollmentEndpoint = iamUrl + "/push-mfa/enroll/complete";
        logger.info("Sending enrollment completion request to Keycloak endpoint: {}", enrollmentEndpoint);
        logger.trace("Enrollment token being sent, length: {}", enrollmentToken.length());

        ResponseEntity<String> response =
                restTemplate.exchange(enrollmentEndpoint, HttpMethod.POST, entity, String.class);

        logger.info("Enrollment completion response from Keycloak - status: {}", response.getStatusCode());
        logger.debug(
                "Response body length: {}",
                response.getBody() != null ? response.getBody().length() : 0);
        if (!response.getStatusCode().is2xxSuccessful()) {
            logger.warn(
                    "Enrollment completion failed with status: {}, response: {}",
                    response.getStatusCode(),
                    response.getBody());
        } else {
            logger.info("Enrollment completion successful for userId: {}, enrollmentId: {}", userId, enrollmentId);
        }

        return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
    }
}
