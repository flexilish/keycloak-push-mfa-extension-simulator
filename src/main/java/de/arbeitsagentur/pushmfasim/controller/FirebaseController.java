package de.arbeitsagentur.pushmfasim.controller;

import de.arbeitsagentur.pushmfasim.model.FcmMessageRequest;
import de.arbeitsagentur.pushmfasim.model.FcmMessageResponse;
import de.arbeitsagentur.pushmfasim.model.FcmTokenResponse;
import de.arbeitsagentur.pushmfasim.services.SseService;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Controller
public class FirebaseController {
    private static final String TOKEN_VALUE_STRING = "keycloak_push_mfa_simulator_valid_assertion";
    private static final Logger LOG = LoggerFactory.getLogger(FirebaseController.class.getName());

    private final SseService sseService;

    public FirebaseController(SseService sseService) {
        this.sseService = sseService;
    }

    @PostMapping(value = "/fcm/token")
    public ResponseEntity<FcmTokenResponse> getToken(@RequestParam("assertion") String assertion) {
        LOG.info("FCM token request received");
        LOG.debug("Assertion parameter provided: {}", assertion != null && !assertion.isEmpty());

        if (assertion == null || assertion.isEmpty()) {
            LOG.warn("FCM token request failed: empty or missing assertion");
            return ResponseEntity.status(HttpStatusCode.valueOf(401)).body(null);
        }

        LOG.debug("Assertion validation successful, generating FCM access token");
        FcmTokenResponse response =
                FcmTokenResponse.builder().accessToken(TOKEN_VALUE_STRING).build();
        LOG.info("FCM access token generated successfully");

        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/fcm/messages:send")
    public ResponseEntity<FcmMessageResponse> sendMessage(
            @RequestHeader("Authorization") String authorization, @RequestBody FcmMessageRequest request) {
        LOG.info("FCM message send request received");
        LOG.trace("Authorization header present: {}", authorization != null);

        if (authorization == null || !authorization.startsWith("Bearer ")) {
            LOG.warn("FCM message request failed: missing or invalid Authorization header");
            return ResponseEntity.status(HttpStatusCode.valueOf(401)).body(null);
        }
        String token = authorization.substring("Bearer ".length());
        LOG.debug("Extracted bearer token, length: {}", token.length());

        if (!TOKEN_VALUE_STRING.equals(token)) {
            LOG.warn("FCM message request failed: invalid bearer token");
            return ResponseEntity.status(HttpStatusCode.valueOf(401)).body(null);
        }
        LOG.debug("Bearer token validation successful");

        if (request == null) {
            LOG.warn("FCM message request failed: null request body");
            return ResponseEntity.status(HttpStatusCode.valueOf(400)).body(null);
        }
        if (request.getMessage() == null) {
            LOG.warn("FCM message request failed: null message in request");
            return ResponseEntity.status(HttpStatusCode.valueOf(400)).body(null);
        }
        if (request.getMessage().getToken() == null) {
            LOG.warn("FCM message request failed: null device token in message");
            return ResponseEntity.status(HttpStatusCode.valueOf(400)).body(null);
        }
        if (request.getMessage().getNotification() == null) {
            LOG.warn("FCM message request failed: null notification in message");
            return ResponseEntity.status(HttpStatusCode.valueOf(400)).body(null);
        }
        if (request.getMessage().getData() == null) {
            LOG.warn("FCM message request failed: null data in message");
            return ResponseEntity.status(HttpStatusCode.valueOf(400)).body(null);
        }
        if (request.getMessage().getData().getToken() == null) {
            LOG.warn("FCM message request failed: null token in message data");
            return ResponseEntity.status(HttpStatusCode.valueOf(400)).body(null);
        }
        LOG.debug(
                "FCM message request validation successful - token: {}, has notification: true",
                request.getMessage().getToken());

        LOG.info("Publishing FCM message to SSE emitters");
        sseService.sendMessageToAllEmitters(request.getMessage());
        LOG.debug("FCM message published successfully");

        FcmMessageResponse response = FcmMessageResponse.builder()
                .name("projects/ba-secure-mock/FcmMessageRequest")
                .build();
        LOG.info("FCM message send request completed successfully");

        return ResponseEntity.ok(response);
    }

    @GetMapping("/fcm/register-sse")
    public ResponseEntity<SseEmitter> sse() {
        LOG.info("SSE emitter registration request received");
        HttpHeaders headers = new HttpHeaders();
        headers.add("Connection", "keep-alive");
        headers.add("Cache-Control", "no-cache");
        headers.add("Content-Type", "text/event-stream");
        LOG.trace("SSE response headers configured");

        LOG.debug("Creating new SSE emitter for client");
        SseEmitter emitter = sseService.createSseEmitter();
        if (emitter == null) {
            LOG.error("Failed to create SSE emitter: SseService returned null");
            return ResponseEntity.status(500).body(null);
        }
        LOG.info("SSE emitter created and registered successfully");
        return ResponseEntity.status(HttpStatus.OK).headers(headers).body(emitter);
    }

    @GetMapping("/fcm/credentials")
    public ResponseEntity<String> getCredentials() {
        LOG.info("Mock Firebase credentials request received");
        LOG.debug("Generating mock service account credentials");

        String privateKey = getPrivateKeyPem();
        if (privateKey == null) {
            LOG.error("Failed to generate mock credentials: private key generation failed");
            return ResponseEntity.status(HttpStatusCode.valueOf(500)).body(null);
        }

        Map<String, String> credentials = Map.of(
                "type", "service_account",
                "project_id", "ba-secure-mock",
                "private_key_id", "some_key_id",
                "private_key", Objects.requireNonNull(getPrivateKeyPem()),
                "client_email", "fcm-mock@test.de",
                "token_uri", "http://localhost:5000/mock/fcm/token");

        LOG.trace("Converting credentials to JSON format");
        try {
            String jsonCredentials =
                    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(credentials);
            LOG.info("Mock Firebase credentials generated successfully, size: {} bytes", jsonCredentials.length());
            return ResponseEntity.ok(jsonCredentials);
        } catch (JacksonException ex) {
            LOG.error("Failed to serialize mock credentials to JSON", ex);
        }
        return ResponseEntity.status(HttpStatusCode.valueOf(500)).body(null);
    }

    private String getPrivateKeyPem() {
        LOG.debug("Generating mock RSA private key (2048-bit)");
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            LOG.trace("RSA key pair generator initialized");

            KeyPair keyPair = generator.generateKeyPair();
            PrivateKey privateKey = keyPair.getPrivate();
            LOG.trace("RSA key pair generated successfully");

            PKCS8EncodedKeySpec pkcs8Spec = new PKCS8EncodedKeySpec(privateKey.getEncoded());
            byte[] pkcs8Bytes = pkcs8Spec.getEncoded();
            LOG.trace("Private key encoded as PKCS8, size: {} bytes", pkcs8Bytes.length);

            String base64Encoded = Base64.getMimeEncoder().encodeToString(pkcs8Bytes);
            LOG.trace("Private key encoded to Base64, size: {} characters", base64Encoded.length());

            StringBuilder pemBuilder = new StringBuilder();
            pemBuilder.append("-----BEGIN PRIVATE KEY-----");
            pemBuilder.append(base64Encoded);
            pemBuilder.append("-----END PRIVATE KEY-----");
            String pemResult = pemBuilder.toString();

            LOG.debug("Mock RSA private key generated successfully, PEM size: {} bytes", pemResult.length());
            return pemResult;
        } catch (NoSuchAlgorithmException ex) {
            LOG.error("Failed to generate mock private key: RSA algorithm not available", ex);
        }
        return null;
    }
}
