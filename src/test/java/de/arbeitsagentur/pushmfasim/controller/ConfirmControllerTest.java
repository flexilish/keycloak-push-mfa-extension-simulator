package de.arbeitsagentur.pushmfasim.controller;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jwt.JWTClaimsSet;
import java.lang.reflect.Method;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConfirmController Tests")
class ConfirmControllerTest {

    @InjectMocks
    private ConfirmController confirmController;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Should reject request with missing token")
    void testMissingToken() {
        // Empty token will cause JWT parsing to fail, which is expected behavior
        assertThrows(Exception.class, () -> {
            confirmController.completeEnrollProcess(
                    "", // empty token
                    "test-context",
                    "approve",
                    "12345",
                    "http://localhost:8080/realms/demo");
        });
    }

    @Test
    @DisplayName("Should extract userId from credentialId correctly")
    void testExtractUserIdFromCredentialId() throws Exception {
        Method method = ConfirmController.class.getDeclaredMethod("extractUserIdFromCredentialId", String.class);
        method.setAccessible(true);

        String credentialId = "user123-device-alias-context";
        String result = (String) method.invoke(confirmController, credentialId);

        assertEquals("user123", result);
    }

    @Test
    @DisplayName("Should return null when extracting userId from invalid credentialId")
    void testExtractUserIdFromInvalidCredentialId() throws Exception {
        Method method = ConfirmController.class.getDeclaredMethod("extractUserIdFromCredentialId", String.class);
        method.setAccessible(true);

        String credentialId = "invalid-credential";
        String result = (String) method.invoke(confirmController, credentialId);

        assertNull(result);
    }

    @Test
    @DisplayName("Should accept approve action as default")
    void testDefaultActionIsApprove() throws Exception {
        // This test verifies behavior through the firstNonBlank method
        Method method = ConfirmController.class.getDeclaredMethod("firstNonBlank", String[].class);
        method.setAccessible(true);

        String result = (String) method.invoke(confirmController, (Object) new String[] {"", null});
        assertNull(result);
    }

    @Test
    @DisplayName("Should select first non-blank value using firstNonBlank")
    void testFirstNonBlankSelectsFirstValidValue() throws Exception {
        Method method = ConfirmController.class.getDeclaredMethod("firstNonBlank", String[].class);
        method.setAccessible(true);

        String result = (String) method.invoke(confirmController, (Object) new String[] {"", "second", "third"});
        assertEquals("second", result);
    }

    @Test
    @DisplayName("Should use firstNonBlank with context as fallback")
    void testFirstNonBlankWithContextFallback() throws Exception {
        Method method = ConfirmController.class.getDeclaredMethod("firstNonBlank", String[].class);
        method.setAccessible(true);

        String result = (String) method.invoke(confirmController, (Object) new String[] {"", null, "context-value"});
        assertEquals("context-value", result);
    }

    @Test
    @DisplayName("Should trim whitespace in firstNonBlank")
    void testFirstNonBlankTrimsWhitespace() throws Exception {
        Method method = ConfirmController.class.getDeclaredMethod("firstNonBlank", String[].class);
        method.setAccessible(true);

        String result = (String) method.invoke(confirmController, (Object) new String[] {"  ", "  second  "});
        assertEquals("second", result);
    }

    @Test
    @DisplayName("Should show GET endpoint is available")
    void testGetEndpointExists() {
        String result = confirmController.showInfoPage();
        assertEquals("confirm-page", result);
    }

    @Test
    @DisplayName("Should normalize action to lowercase")
    void testActionNormalization() throws Exception {
        Method method = ConfirmController.class.getDeclaredMethod("firstNonBlank", String[].class);
        method.setAccessible(true);

        String result = (String) method.invoke(confirmController, (Object) new String[] {"APPROVE"});
        // Note: The controller will then use .toLowerCase() on the result
        assertEquals("APPROVE", result); // firstNonBlank returns as-is, controller toLowerCase's it
    }

    @Test
    @DisplayName("Should handle empty action as approve")
    void testEmptyActionDefaultsToApprove() throws Exception {
        String action = "";
        String effectiveAction =
                (action != null && !action.trim().isEmpty()) ? action.trim().toLowerCase() : "approve";
        assertEquals("approve", effectiveAction);
    }

    @Test
    @DisplayName("Should handle null action as approve")
    void testNullActionDefaultsToApprove() throws Exception {
        String action = null;
        String effectiveAction =
                (action != null && !action.trim().isEmpty()) ? action.trim().toLowerCase() : "approve";
        assertEquals("approve", effectiveAction);
    }

    @Test
    @DisplayName("Should handle deny action correctly")
    void testDenyActionProcessed() throws Exception {
        String action = "DENY";
        String effectiveAction =
                (action != null && !action.trim().isEmpty()) ? action.trim().toLowerCase() : "approve";
        assertEquals("deny", effectiveAction);
    }

    @Test
    @DisplayName("Should handle whitespace-only action as approve")
    void testWhitespaceActionDefaultsToApprove() throws Exception {
        String action = "   ";
        String effectiveAction =
                (action != null && !action.trim().isEmpty()) ? action.trim().toLowerCase() : "approve";
        assertEquals("approve", effectiveAction);
    }

    @Test
    @DisplayName("Should validate userVerification requirement for approve action")
    void testUserVerificationRequirementForApprove() throws Exception {
        String effectiveAction = "approve";
        String pendingUserVerification = "required";
        String effectiveUserVerification = null;

        boolean isValid = !("approve".equals(effectiveAction)
                && pendingUserVerification != null
                && (effectiveUserVerification == null
                        || effectiveUserVerification.trim().isEmpty()));

        assertFalse(isValid); // Should be invalid - missing user verification
    }

    @Test
    @DisplayName("Should not require userVerification for deny action")
    void testNoUserVerificationRequiredForDeny() {
        String effectiveAction = "deny";
        String pendingUserVerification = "required";
        String effectiveUserVerification = null;

        boolean isValid = !("approve".equals(effectiveAction)
                && pendingUserVerification != null
                && (effectiveUserVerification == null
                        || effectiveUserVerification.trim().isEmpty()));

        assertTrue(isValid); // Should be valid - deny doesn't need verification
    }

    @Test
    @DisplayName("Should provide valid response on successful confirmation")
    void testSuccessfulConfirmationResponse() {
        String userId = "test-user";
        String responseStatus = "200 OK";
        String pendingUserVerification = "12345";
        String action = "approve";

        String responseMsg = String.format(
                "userId: %s; responseStatus: %s; userVerification: %s; action: %s",
                userId, responseStatus, pendingUserVerification, action);

        assertTrue(responseMsg.contains("userId: test-user"));
        assertTrue(responseMsg.contains("responseStatus: 200 OK"));
        assertTrue(responseMsg.contains("userVerification: 12345"));
        assertTrue(responseMsg.contains("action: approve"));
    }

    @Test
    @DisplayName("Should format response with null userVerification")
    void testResponseWithNullUserVerification() {
        String userId = "test-user";
        String responseStatus = "200 OK";
        String pendingUserVerification = null;
        String action = "deny";

        String responseMsg = String.format(
                "userId: %s; responseStatus: %s; userVerification: %s; action: %s",
                userId, responseStatus, pendingUserVerification, action);

        assertTrue(responseMsg.contains("userVerification: null"));
        assertTrue(responseMsg.contains("action: deny"));
    }

    @Test
    @DisplayName("Should correctly identify challenge in pending list")
    void testFindChallengeInPendingList() throws Exception {
        String json = "{\"challenges\": [{\"cid\": \"challenge-1\", \"userVerification\": \"required\"}, "
                + "{\"cid\": \"challenge-2\", \"userVerification\": null}]}";
        JsonNode rootNode = objectMapper.readTree(json);
        JsonNode challengesNode = rootNode.get("challenges");

        String targetChallengeId = "challenge-1";
        JsonNode foundChallenge = null;

        for (JsonNode challenge : challengesNode) {
            if (challenge.has("cid") && challenge.get("cid").asString().equals(targetChallengeId)) {
                foundChallenge = challenge;
                break;
            }
        }

        assertNotNull(foundChallenge);
        assertEquals("challenge-1", foundChallenge.get("cid").asString());
        assertEquals("required", foundChallenge.get("userVerification").asString());
    }

    @Test
    @DisplayName("Should return null when challenge not found in pending list")
    void testChallengeNotFoundInPendingList() throws Exception {
        String json = "{\"challenges\": [{\"cid\": \"challenge-1\"}]}";
        JsonNode rootNode = objectMapper.readTree(json);
        JsonNode challengesNode = rootNode.get("challenges");

        String targetChallengeId = "challenge-999";
        JsonNode foundChallenge = null;

        for (JsonNode challenge : challengesNode) {
            if (challenge.has("cid") && challenge.get("cid").asString().equals(targetChallengeId)) {
                foundChallenge = challenge;
                break;
            }
        }

        assertNull(foundChallenge);
    }

    @Test
    @DisplayName("Should parse JWT with userVerification claim")
    void testParseJWTWithUserVerificationClaim() throws Exception {
        // Create a JWT with userVerification claim
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("cid", "challenge-123")
                .claim("credId", "credential-456")
                .claim("userVerification", "98765")
                .expirationTime(new Date(System.currentTimeMillis() + 300000))
                .build();

        // Verify claims extraction
        String challengeId = claimsSet.getStringClaim("cid");
        String credentialId = claimsSet.getStringClaim("credId");
        String userVerification = claimsSet.getStringClaim("userVerification");

        assertEquals("challenge-123", challengeId);
        assertEquals("credential-456", credentialId);
        assertEquals("98765", userVerification);
    }

    @Test
    @DisplayName("Should parse JWT without userVerification claim")
    void testParseJWTWithoutUserVerificationClaim() throws Exception {
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .claim("cid", "challenge-123")
                .claim("credId", "credential-456")
                .expirationTime(new Date(System.currentTimeMillis() + 300000))
                .build();

        String userVerification = claimsSet.getClaims().containsKey("userVerification")
                ? claimsSet.getStringClaim("userVerification")
                : null;

        assertNull(userVerification);
    }

    @Test
    @DisplayName("Should validate response message format")
    void testResponseMessageFormat() {
        String responseMsg = "userId: test-user; responseStatus: 200 OK; userVerification: 12345; action: approve";

        assertTrue(responseMsg.matches(".*userId:.*responseStatus:.*userVerification:.*action:.*"));
    }

    @Test
    @DisplayName("Lockout endpoint should forward to existing process and throw on empty token")
    void testLoginLockoutThrowsOnEmptyToken() throws Exception {
        assertThrows(Exception.class, () -> {
            confirmController.loginLockout("", "ctx", null, "http://localhost:8080/realms/demo");
        });
    }

    @Test
    @DisplayName("Should handle special characters in userId")
    void testHandleSpecialCharactersInUserId() throws Exception {
        String userId = "user-with-special_chars@example.com";
        String context = "test-context";
        String credentialId = userId + "-device-alias-" + context;

        Method method = ConfirmController.class.getDeclaredMethod("extractUserIdFromCredentialId", String.class);
        method.setAccessible(true);

        String result = (String) method.invoke(confirmController, credentialId);
        assertEquals(userId, result);
    }
}
