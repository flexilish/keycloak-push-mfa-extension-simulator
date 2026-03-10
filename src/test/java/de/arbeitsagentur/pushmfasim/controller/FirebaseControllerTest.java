package de.arbeitsagentur.pushmfasim.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.arbeitsagentur.pushmfasim.model.FcmMessageData;
import de.arbeitsagentur.pushmfasim.model.FcmMessageNotification;
import de.arbeitsagentur.pushmfasim.model.FcmMessageRequest;
import de.arbeitsagentur.pushmfasim.model.FcmMessageRequestMessage;
import de.arbeitsagentur.pushmfasim.model.FcmMessageResponse;
import de.arbeitsagentur.pushmfasim.model.FcmTokenResponse;
import de.arbeitsagentur.pushmfasim.services.SseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = FirebaseController.class)
class FirebaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SseService sseService;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        when(sseService.createSseEmitter()).thenReturn(new SseEmitter());
    }

    @SuppressWarnings("null")
    @Test
    void testGetTokenWithValidAssertion() throws Exception {
        mockMvc.perform(post("/fcm/token").param("assertion", "valid_assertion"))
                .andExpect(status().isOk())
                .andExpect(content()
                        .json(objectMapper.writeValueAsString(FcmTokenResponse.builder()
                                .accessToken("keycloak_push_mfa_simulator_valid_assertion")
                                .build())));
    }

    @Test
    void testGetTokenWithInvalidAssertion() throws Exception {
        mockMvc.perform(post("/fcm/token").param("assertion", "")).andExpect(status().isUnauthorized());
    }

    @SuppressWarnings("null")
    @Test
    void testSendMessageWithValidAuthorization() throws Exception {
        FcmMessageRequestMessage requestMessage = FcmMessageRequestMessage.builder()
                .token("valid_token")
                .notification(FcmMessageNotification.builder()
                        .title("Test")
                        .body("This is a test")
                        .build())
                .data(FcmMessageData.builder().token("valid_data_token").build())
                .build();
        FcmMessageRequest request =
                FcmMessageRequest.builder().message(requestMessage).build();

        mockMvc.perform(post("/fcm/messages:send")
                        .header("Authorization", "Bearer keycloak_push_mfa_simulator_valid_assertion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content()
                        .json(objectMapper.writeValueAsString(FcmMessageResponse.builder()
                                .name("projects/ba-secure-mock/FcmMessageRequest")
                                .build())));
    }

    @SuppressWarnings("null")
    @Test
    void testSendMessageWithInvalidAuthorization() throws Exception {
        FcmMessageRequestMessage requestMessage = FcmMessageRequestMessage.builder()
                .token("valid_token")
                .notification(FcmMessageNotification.builder()
                        .title("Test")
                        .body("This is a test")
                        .build())
                .data(FcmMessageData.builder().token("valid_data_token").build())
                .build();
        FcmMessageRequest request =
                FcmMessageRequest.builder().message(requestMessage).build();

        mockMvc.perform(post("/fcm/messages:send")
                        .header("Authorization", "Bearer invalid_token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @SuppressWarnings("null")
    @Test
    void testSendMessageWithInvalidRequestBody() throws Exception {
        mockMvc.perform(post("/fcm/messages:send")
                        .header("Authorization", "Bearer keycloak_push_mfa_simulator_valid_assertion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testGetCredentials() throws Exception {
        MvcResult result = mockMvc.perform(get("/fcm/credentials"))
                .andExpect(status().isOk())
                .andReturn();

        String responseContent = result.getResponse().getContentAsString().replaceAll("\" : \"", "\":\"");
        assertTrue(responseContent.contains("\"type\":\"service_account\""));
        assertTrue(responseContent.contains("\"project_id\":\"ba-secure-mock\""));
        assertTrue(responseContent.contains("\"client_email\":\"fcm-mock@test.de\""));
        assertTrue(responseContent.contains("\"token_uri\":\"http://localhost:5000/mock/fcm/token\""));
        assertTrue(responseContent.contains("\"private_key\":\"-----BEGIN PRIVATE KEY-----"));
    }

    @Test
    void testSseEndpoint() throws Exception {
        mockMvc.perform(get("/fcm/register-sse")).andExpect(status().isOk());
    }

    @Test
    void testSseEndpointError() throws Exception {
        when(sseService.createSseEmitter()).thenReturn(null);
        mockMvc.perform(get("/fcm/register-sse")).andExpect(status().is(500));
    }
}
