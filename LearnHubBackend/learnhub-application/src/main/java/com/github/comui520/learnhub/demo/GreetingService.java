package com.github.comui520.learnhub.demo;

import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.demo.dto.GreetingResponse;
import org.springframework.stereotype.Service;

@Service
public class GreetingService {
    public GreetingResponse greet(String name){
        String actualName = name.trim();
        if ("forbidden".equalsIgnoreCase(actualName)){
            throw new BusinessException(DemoErrorCode.NAME_FORBIDDEN);
        }

        return new GreetingResponse("Hello, " + actualName + "!");
    }
}
