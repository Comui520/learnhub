package com.github.comui520.learnhub.demo;

import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.demo.dto.GreetingRequest;
import com.github.comui520.learnhub.demo.dto.GreetingResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.catchThrowableOfType;

public class GreetingServiceTest {

    private final GreetingService greetingService = new GreetingService();

    @Test
    void shouldReturnNormalizedGreetingWhenNameIsValid(){
        GreetingResponse response = greetingService.greet("     LearnHub     ");
        assertThat(response.greeting()).isEqualTo("Hello, LearnHub!");
    }

    @Test
    void shouldThrowBusinessExceptionWhenNameIsForbidden() {
        BusinessException exception = catchThrowableOfType(
                () -> greetingService.greet("FORBIDDEN"),
                BusinessException.class
        );

        assertThat(exception.getErrorCode()).isEqualTo(DemoErrorCode.NAME_FORBIDDEN);
        assertThat(exception.getMessage()).isEqualTo("Name is forbidden");
    }
}
