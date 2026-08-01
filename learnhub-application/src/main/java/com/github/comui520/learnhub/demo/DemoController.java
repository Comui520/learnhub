package com.github.comui520.learnhub.demo;


import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.demo.dto.GreetingRequest;
import com.github.comui520.learnhub.demo.dto.GreetingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Demo", description = "仅仅是测试接口")
@RestController
@RequestMapping("/api/v1/demo")
public class DemoController {
    private final GreetingService greetingService;
    public DemoController(GreetingService greetingService){
        this.greetingService = greetingService;
    }

    @Operation(summary = "生成问候语", description = "用于验证 JSON、参数校验和统一异常响应")
    @PostMapping("/greetings")
    public ApiResponse<String> greet(
            @Valid @RequestBody GreetingRequest request
    ){
        GreetingResponse response = greetingService.greet(request.name());
        return ApiResponse.success(response.greeting());
    }
}
