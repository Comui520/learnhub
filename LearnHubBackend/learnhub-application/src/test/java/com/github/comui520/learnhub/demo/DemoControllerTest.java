package com.github.comui520.learnhub.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.demo.DemoController;
import com.github.comui520.learnhub.demo.GreetingService;
import com.github.comui520.learnhub.demo.dto.GreetingRequest;
import com.github.comui520.learnhub.demo.dto.GreetingResponse;
import com.github.comui520.learnhub.web.service.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DemoController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
public class DemoControllerTest {
    // mockmvc是用来模拟发送请求的
    @Autowired
    private MockMvc mockMvc;

    // mockitoBean是用来模拟GreetingService的
    @MockitoBean
    private GreetingService greetingService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void shouldReturnGreetingWhenRequestIsValid() throws Exception {
        GreetingRequest request = new GreetingRequest("LearnHub");
        given(greetingService.greet("LearnHub"))
                .willReturn(new GreetingResponse("Hello, LearnHub!"));

        mockMvc.perform(
                        post("/api/v1/demo/greetings")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request))
                )
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("COMMON_0000"))
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value("Hello, LearnHub!"))
                .andExpect(jsonPath("$.timestamp").exists());
        verify(greetingService).greet("LearnHub");
    }

    @Test
    public void shouldReturnBadRequestWhenNameIsBlank() throws Exception {
        GreetingRequest request = new GreetingRequest("");
        mockMvc.perform(
                        post("/api/v1/demo/greetings")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request))
                )
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("COMMON_0400"))
                .andExpect(jsonPath("$.data[0].field").value("name"))
                .andExpect(jsonPath("$.data[0].message").value("Name should not be blank"));
        verifyNoInteractions(greetingService);
    }

    @Test
    void shouldReturnBusinessErrorWhenServiceRejectsName() throws Exception {
        GreetingRequest request = new GreetingRequest("forbidden");
        given(greetingService.greet("forbidden"))
                .willThrow(new BusinessException(DemoErrorCode.NAME_FORBIDDEN));

        mockMvc.perform(
                        post("/api/v1/demo/greetings")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request))
                )
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEMO_ERROR_0422"))
                .andExpect(jsonPath("$.message").value("Name is forbidden"))
                .andExpect(jsonPath("$.data").isEmpty());

        verify(greetingService).greet("forbidden");
    }

    @Test
    void shouldReturnBadRequestWhenJsonIsMalformed() throws Exception {
        mockMvc.perform(post("/api/v1/demo/greetings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bad json\":}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_0400"));

        verifyNoInteractions(greetingService);
    }

    @Test
    void shouldReturnBadRequestWhenNameIsTooLong() throws Exception {
        GreetingRequest request = new GreetingRequest("x".repeat(51));
        mockMvc.perform(
                        post("/api/v1/demo/greetings")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsBytes(request))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_0400"))
                .andExpect(jsonPath("$.data[0].field").value("name"))
                .andExpect(jsonPath("$.data[0].message").value("Name should not be more than 50 characters")
                );
    }

}
