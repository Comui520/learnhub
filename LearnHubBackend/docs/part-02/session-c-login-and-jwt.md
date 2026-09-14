# Session C：登录与 JWT 鉴权

> 目标：实现 `POST /api/v1/auth/login` 签发 JWT，自定义过滤器解析 token，新增受保护接口 `GET /api/v1/users/me`，未登录返回统一的 401 JSON。
>
> 档位：核心链路 🧑‍🏫 我带；测试 🤝 我们各写一半。
> 预计时间：4～5 小时。

## 0. 今天到底要学会什么

1. 为什么登录之后要发凭证，Session 方案和 Token 方案的区别。
2. JWT 的结构（Header.Payload.Signature）和签名原理。
3. jjwt 库（0.13）的签发与解析 API。
4. `OncePerRequestFilter` 如何把 token 变成 `SecurityContext` 里的认证信息。
5. 401 的 JSON 响应为什么必须自己写（Security 的默认行为不是 JSON）。

---

## 1. 先建立直觉：登录成功之后，怎么证明“你是谁”

注册解决“账号存在”，登录解决“这次请求是你本人发的”。HTTP 是无状态的，每个请求都是独立的，所以登录成功后必须给客户端一个凭证，客户端每个请求都带上它。

两种主流方案：

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| Session | 服务端存会话，返回 sessionId，客户端存 Cookie | 可随时吊销 | 服务端要存状态，多实例要共享会话存储 |
| JWT | 服务端签发一个自包含的 token，客户端存起来，请求时放 Header | 服务端无状态，天然适合多实例 | 签发后难主动失效，token 泄露风险窗口 |

我们选 JWT，因为它和“无状态 API + 多实例部署”最搭。但你要能说出它的代价：**退出登录、封禁用户这类“主动失效”很难做**（Session E/F 会讨论解法）。

### JWT 长什么样

```text
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.xxxxx
├───────────────┘├──────────────┘├───────────┘
   Header(算法)    Payload(数据)    Signature(签名)
```

- Header：声明签名算法，如 HS256。
- Payload：业务数据，我们放 userId、username、过期时间。**Payload 只是 Base64 编码，不加密，所以不能放密码等敏感信息。**
- Signature：用服务端密钥对“Header.Payload”做签名。签名保证“内容没被篡改”，但不能保密。

校验流程：拿到 token → 用密钥重新算签名 → 和 token 里的签名比对 → 一致且没过期，就认为可信。

---

## 2. 先认识关键概念

### 2.1 jjwt 0.13 的 API 形态

```java
// 签发
String token = Jwts.builder()
        .subject("1")
        .claim("username", "learnhub")
        .issuedAt(new Date())
        .expiration(new Date(...))
        .signWith(key)
        .compact();

// 解析
Claims claims = Jwts.parser()
        .verifyWith(key)
        .build()
        .parseSignedClaims(token)
        .getPayload();
```

记住两个重点：`signWith` 用密钥对象（`SecretKey`），解析用 `verifyWith`；HS256 要求密钥至少 32 字节。

### 2.2 OncePerRequestFilter 与 SecurityContext

Security 的过滤器链在请求进入 Controller 之前执行。我们自定义一个过滤器：

```text
请求 -> ... -> JwtAuthenticationFilter（我们的） -> UsernamePasswordAuthenticationFilter -> ... -> Controller
```

我们的过滤器干三件事：

1. 从 `Authorization: Bearer xxx` 头里取出 token。
2. 解析成功 → 构造 `UsernamePasswordAuthenticationToken` 放进 `SecurityContextHolder`。
3. 解析失败 → 清空上下文（不抛异常），让后续过滤器判定“未认证”。

放进 `SecurityContext` 后，Controller 里就能用 `SecurityContextHolder.getContext().getAuthentication()` 拿到当前用户。

### 2.3 AuthenticationEntryPoint：401 的 JSON 由你负责

未认证请求会被 Security 拦截，默认行为是跳转登录页或返回 Basic 认证挑战，**不是 JSON**。我们的 API 需要统一的 `ApiResponse` 格式，所以要自定义 `AuthenticationEntryPoint`，手动写 401 JSON。

### 2.4 jjwt 的三个依赖

jjwt 是 Java 的 JWT 库，分三个构件，POM 里已经预置：

```xml
<!-- 编译期 API：Jwts.builder() 等类型都在这里 -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
</dependency>

<!-- 运行时实现：签名、解析的真正代码 -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- 运行时 JSON 支持：把 Claims 序列化/反序列化 -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <scope>runtime</scope>
</dependency>
```

为什么 `jjwt-impl` 用 `runtime`：我们只在编译期 import `jjwt-api` 里的类，实现类由容器在运行时提供——这也呼应 Session A 讲的 scope 区别。版本 0.13.0 在父 POM 的 `dependencyManagement` 里统一管理，子模块不用写版本号。

---

## 3. 跟着做一遍（🧑‍🏫 我带）

### Step 1：CommonErrorCode 增加 401/403

修改 `learnhub-common/.../exception/CommonErrorCode.java`，在枚举里加两个值：

```java
UNAUTHENTICATED("COMMON_0401", "UNAUTHENTICATED", 401),
FORBIDDEN("COMMON_0403", "FORBIDDEN", 403),
```

`UNAUTHENTICATED` 今天用，`FORBIDDEN` 明天（Session D）用。

### Step 2：JWT 配置（密钥放环境变量）

创建文件：

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/security/JwtProperties.java
```

```java
package com.github.comui520.learnhub.user.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "learnhub.jwt")
public class JwtProperties {

    /** HS256 要求密钥至少 32 字节，生产环境必须通过环境变量覆盖 */
    private String secret;

    /** token 有效期（秒），默认 1 小时 */
    private long expirationSeconds = 3600;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    public void setExpirationSeconds(long expirationSeconds) {
        this.expirationSeconds = expirationSeconds;
    }
}
```

在 `application-dev.yml` 末尾追加：

```yaml
learnhub:
  jwt:
    secret: ${JWT_SECRET:dev-only-jwt-secret-change-me-in-production-0123456789}
    expiration-seconds: 3600
```

读法：优先读环境变量 `JWT_SECRET`，本地没有就用开发默认值。**生产环境必须覆盖**，面试要能说出“密钥不进代码、不进仓库”。

### Step 3：JwtTokenProvider

创建文件：

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/security/JwtTokenProvider.java
```

```java
package com.github.comui520.learnhub.user.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long expirationSeconds;

    public JwtTokenProvider(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = properties.getExpirationSeconds();
    }

    public String createToken(Long userId, String username) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expirationSeconds * 1000);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(key)
                .compact();
    }

    /** 解析 token 并返回用户 ID；token 无效/过期/被篡改会抛 JwtException */
    public Long parseUserId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    public String parseUsername(String token) {
        return parseClaims(token).get("username", String.class);
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
```

设计点：对外只暴露 `parseUserId` / `parseUsername`，把 `Claims` 藏在内部——调用方（过滤器）不需要关心 JWT 细节，测试也更好写。

### Step 4：LoginRequest 和 TokenResponse

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/dto/LoginRequest.java
```

```java
package com.github.comui520.learnhub.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "登录请求")
public record LoginRequest(
        @Schema(description = "登录名", example = "learnhub")
        @NotBlank(message = "Username should not be blank")
        @Size(max = 30, message = "Username should not be longer than 30")
        String username,

        @Schema(description = "密码", example = "password123")
        @NotBlank(message = "Password should not be blank")
        @Size(max = 64, message = "Password should not be longer than 64")
        String password
) {
}
```

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/dto/TokenResponse.java
```

```java
package com.github.comui520.learnhub.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "登录成功返回的令牌")
public record TokenResponse(
        @Schema(description = "访问令牌")
        String accessToken,

        @Schema(description = "令牌类型", example = "Bearer")
        String tokenType,

        @Schema(description = "有效期（秒）")
        long expiresInSeconds
) {
}
```

### Step 5：UserService 与 USER_NOT_FOUND

创建文件：

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/service/UserService.java
```

```java
package com.github.comui520.learnhub.user.service;

import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.user.UserErrorCode;
import com.github.comui520.learnhub.user.dto.UserResponse;
import com.github.comui520.learnhub.user.entity.User;
import com.github.comui520.learnhub.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public UserResponse findById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
        }
        return new UserResponse(user.getId(), user.getUsername());
    }
}
```

在 `UserErrorCode` 里加：

```java
USER_NOT_FOUND("USER_ERROR_0404", "User not found", 404),
```

### Step 6：AuthService 加 login

修改 `AuthService`，构造器增加 `JwtTokenProvider`，并新增方法：

```java
package com.github.comui520.learnhub.user.service;

import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.user.UserErrorCode;
import com.github.comui520.learnhub.user.dto.LoginRequest;
import com.github.comui520.learnhub.user.dto.RegisterRequest;
import com.github.comui520.learnhub.user.dto.TokenResponse;
import com.github.comui520.learnhub.user.dto.UserResponse;
import com.github.comui520.learnhub.user.entity.User;
import com.github.comui520.learnhub.user.mapper.UserMapper;
import com.github.comui520.learnhub.user.security.JwtTokenProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(UserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public UserResponse register(RegisterRequest request) {
        // 和 Session B 一样，不变
    }

    public TokenResponse login(LoginRequest request) {
        User user = userMapper.selectByUsername(request.username());

        // 统一报错：不区分“用户不存在”和“密码错误”，防止枚举用户名
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(UserErrorCode.INVALID_CREDENTIALS);
        }

        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(UserErrorCode.ACCOUNT_DISABLED);
        }

        String token = jwtTokenProvider.createToken(user.getId(), user.getUsername());
        return new TokenResponse(token, "Bearer", jwtTokenProvider.getExpirationSeconds());
    }
}
```

三个重点：

1. **`user == null || !matches(...)` 合并成一个判断**——如果分别返回“用户不存在”和“密码错误”，攻击者就能逐个试探哪些用户名注册过（枚举攻击）。这是面试高频点。
2. `passwordEncoder.matches(明文, 哈希)` 是 BCrypt 验证方式。
3. 登录成功才签发 token，token 里只放 userId 和 username，**绝不放密码**。

### Step 7：AuthController 加 login

在 `AuthController` 里加：

```java
@Operation(summary = "登录", description = "校验用户名密码，签发 JWT")
@ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "登录成功，返回 token"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "参数错误（COMMON_0400）"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "用户名或密码错误（USER_ERROR_0401）")
})
@PostMapping("/login")
public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
    return ApiResponse.success(authService.login(request));
}
```

补 import：`LoginRequest`、`TokenResponse`。

### Step 8：JwtAuthenticationFilter

创建文件：

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/security/JwtAuthenticationFilter.java
```

```java
package com.github.comui520.learnhub.user.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Long userId = jwtTokenProvider.parseUserId(token);
                var authentication = new UsernamePasswordAuthenticationToken(
                        userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                // token 无效：清空上下文，走未认证流程（401），而不是抛 500
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
```

逐行理解：

- `OncePerRequestFilter`：保证每个请求只执行一次（过滤器理论上可能被多次调用）。
- `UsernamePasswordAuthenticationToken(userId, null, authorities)`：三个参数的构造器表示“已认证”。principal 放 userId，`getPrincipal()` 就能拿到。
- 现在角色硬编码 `ROLE_USER`，Session D 改成从数据库查。
- **catch 里只清上下文、不抛异常**——如果这里抛异常，会变成 500；正确做法是让 Security 走“未认证”路径返回 401。

### Step 9：RestAuthenticationEntryPoint（401 JSON）

创建文件：

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/security/RestAuthenticationEntryPoint.java
```

```java
package com.github.comui520.learnhub.user.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.common.exception.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.failure(CommonErrorCode.UNAUTHENTICATED));
    }
}
```

注意：这个类运行在**过滤器链**里，异常根本到不了 `@RestControllerAdvice`，所以必须自己用 `ObjectMapper` 写 JSON。

### Step 10：SecurityConfig 接上过滤器和入口点

修改 `SecurityConfig`：

```java
package com.github.comui520.learnhub.user.config;

import com.github.comui520.learnhub.user.security.JwtAuthenticationFilter;
import com.github.comui520.learnhub.user.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RestAuthenticationEntryPoint restAuthenticationEntryPoint) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.restAuthenticationEntryPoint = restAuthenticationEntryPoint;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/**",
                                "/actuator/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        ).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(restAuthenticationEntryPoint))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

新增的两行：

- `exceptionHandling(...)`：未认证时用我们的入口点返回 401 JSON。
- `addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`：把我们的过滤器插到用户名密码过滤器之前。

### Step 11：受保护接口 GET /api/v1/users/me

创建文件：

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/controller/UserController.java
```

```java
package com.github.comui520.learnhub.user.controller;

import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.user.dto.UserResponse;
import com.github.comui520.learnhub.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User", description = "当前用户")
@RestController
@RequestMapping("/api/v1/users")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "获取当前登录用户")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "返回当前用户"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未登录（COMMON_0401）")
    })
    @GetMapping("/me")
    public ApiResponse<UserResponse> me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Long userId = (Long) authentication.getPrincipal();
        return ApiResponse.success(userService.findById(userId));
    }
}
```

`@SecurityRequirement(name = "bearerAuth")` 是给 Swagger 看的：这个接口需要 Bearer token。

### Step 12：给 Swagger 声明 Bearer 认证

创建文件：

```text
learnhub-application/src/main/java/com/github/comui520/learnhub/web/config/OpenApiSecurityConfig.java
```

```java
package com.github.comui520.learnhub.web.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class OpenApiSecurityConfig {
}
```

启动后打开 Swagger UI，右上角会出现 Authorize 按钮，粘贴 token 后就能直接调用受保护接口。

### Step 13：启动并走完整流程

```powershell
mvn -pl learnhub-application -am spring-boot:run "-Dspring-boot.run.profiles=dev"
```

先注册一个用户（如果 Session B 没删），然后登录：

```powershell
curl.exe -i -X POST "http://localhost:8080/api/v1/auth/login" -H "Content-Type: application/json" -d "{\"username\":\"learnhub\",\"password\":\"password123\"}"
```

期望 200，`data.accessToken` 是一长串 JWT。复制它，然后：

```powershell
curl.exe -i "http://localhost:8080/api/v1/users/me" -H "Authorization: Bearer <粘贴你的token>"
```

期望 200，`data.username=learnhub`。

不带 token：

```powershell
curl.exe -i "http://localhost:8080/api/v1/users/me"
```

期望 401，body 是 `code=COMMON_0401` 的 JSON——注意它**不是**默认的 Spring Security 报错格式，说明我们的入口点生效了。

带一个乱写的 token：

```powershell
curl.exe -i "http://localhost:8080/api/v1/users/me" -H "Authorization: Bearer not-a-real-token"
```

同样期望 401 JSON。

把登录返回的 token 贴到 [jwt.io](https://jwt.io) 里，看 Payload 是不是只有 userId、username、iat、exp——**这就是面试时“JWT 里放了什么、为什么不能放密码”的现场证据**。

---

## 4. 观察结果：成功长什么样

```text
成功证据：
1. 登录成功返回 accessToken/tokenType/expiresInSeconds
2. 带正确 token 访问 /users/me 返回 200
3. 不带 token / 乱写 token 返回 401 + code=COMMON_0401 的 JSON
4. Swagger UI 有 Authorize 按钮
```

---

## 5. 主动制造错误（每个做完恢复）

**错误 A：密钥太短**

把 `application-dev.yml` 里的默认 secret 改成 `short`，启动。签发 token 时（登录）会抛 `WeakKeyException` 之类的错误——HS256 要求至少 32 字节。恢复。

**错误 B：过滤器里抛异常**

把 `JwtAuthenticationFilter` 的 catch 块临时改成 `throw new RuntimeException(e)`，带无效 token 访问 `/users/me`，观察它变成 500 而不是 401。恢复。这让你理解“异常抛出位置决定了是 401 还是 500”。

**错误 C：header 判断写错**

把 `header.startsWith("Bearer ")` 的 `"Bearer "` 里的空格去掉，或把取 token 的 `substring(7)` 改错，观察鉴权失效。恢复。

**错误 D：token 过期**

把 `expiration-seconds` 临时改成 `1`，登录拿 token，等两秒再访问 `/users/me`，观察 401。恢复成 3600。（测试里我们会用更可控的方式测过期。）

---

## 6. 独立练习

### 🤝 我们各写一半：JwtTokenProviderTest

创建文件：

```text
learnhub-user/src/test/java/com/github/comui520/learnhub/user/security/JwtTokenProviderTest.java
```

我已写好 `setUp` 和“过期 token”测试，你来补两个：

```java
package com.github.comui520.learnhub.user.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("test-secret-test-secret-test-secret-test-secret");
        properties.setExpirationSeconds(3600);
        provider = new JwtTokenProvider(properties);
    }

    @Test
    void shouldRejectExpiredToken() {
        JwtProperties expiredProperties = new JwtProperties();
        expiredProperties.setSecret("test-secret-test-secret-test-secret-test-secret");
        expiredProperties.setExpirationSeconds(-10); // 有效期设为负数，签出来就是已过期
        JwtTokenProvider expiredProvider = new JwtTokenProvider(expiredProperties);

        String token = expiredProvider.createToken(1L, "learnhub");

        assertThatThrownBy(() -> expiredProvider.parseUserId(token))
                .isInstanceOf(JwtException.class);
    }

    // TODO 你来写：
    // 1) shouldCreateAndParseToken
    //    createToken(1L, "learnhub") 后，parseUserId 返回 1L，parseUsername 返回 "learnhub"
    // 2) shouldRejectTamperedToken
    //    在正常 token 末尾加一个字符，parseUserId 应抛 JwtException
}
```

提示：`assertThat(provider.parseUserId(token)).isEqualTo(1L)`；篡改就是 `token + "x"`。

### 🏃 你自己做：UserController 安全测试

在 application 模块测试目录新建：

```text
learnhub-application/src/test/java/com/github/comui520/learnhub/user/controller/UserControllerSecurityTest.java
```

要求（这是你第一次写“带真实过滤器链”的切片测试）：

- `@WebMvcTest(UserController.class)`，`@Import` 四个类：`SecurityConfig`、`JwtAuthenticationFilter`、`RestAuthenticationEntryPoint`、`GlobalExceptionHandler`。
- `@MockitoBean JwtTokenProvider`、`@MockitoBean UserService`。
- 测试 1：**不带 token** 访问 `GET /api/v1/users/me` → 401，`code=COMMON_0401`。
- 测试 2：**带有效 token**（stub `parseUserId` 返回 1L、`parseUsername` 返回 `"learnhub"`，`userService.findById(1L)` 返回 `new UserResponse(1L, "learnhub")`）→ 200，`data.username=learnhub`。
- 测试 3（加分）：带一个 `parseUserId` 抛 `JwtException` 的 token → 401。

提示：

- `@MockitoBean` 是 Boot 3.4+ 的注解，替换 Spring 容器里的 Bean。
- 有效 token 测试里，过滤器真的会跑：解析 → 放 SecurityContext → 走到 Controller。

---

## 7. 参考答案

### JwtTokenProviderTest 补全

```java
@Test
void shouldCreateAndParseToken() {
    String token = provider.createToken(1L, "learnhub");

    assertThat(provider.parseUserId(token)).isEqualTo(1L);
    assertThat(provider.parseUsername(token)).isEqualTo("learnhub");
}

@Test
void shouldRejectTamperedToken() {
    String token = provider.createToken(1L, "learnhub");
    String tampered = token + "x";

    assertThatThrownBy(() -> provider.parseUserId(tampered))
            .isInstanceOf(JwtException.class);
}
```

### UserControllerSecurityTest

```java
package com.github.comui520.learnhub.user.controller;

import com.github.comui520.learnhub.user.config.SecurityConfig;
import com.github.comui520.learnhub.user.dto.UserResponse;
import com.github.comui520.learnhub.user.security.JwtAuthenticationFilter;
import com.github.comui520.learnhub.user.security.JwtTokenProvider;
import com.github.comui520.learnhub.user.security.RestAuthenticationEntryPoint;
import com.github.comui520.learnhub.user.service.UserService;
import com.github.comui520.learnhub.web.service.GlobalExceptionHandler;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RestAuthenticationEntryPoint.class, GlobalExceptionHandler.class})
class UserControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserService userService;

    @Test
    void shouldReturn401WhenNoToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_0401"));
    }

    @Test
    void shouldReturnUserWhenTokenIsValid() throws Exception {
        given(jwtTokenProvider.parseUserId("good-token")).willReturn(1L);
        given(jwtTokenProvider.parseUsername("good-token")).willReturn("learnhub");
        given(userService.findById(1L)).willReturn(new UserResponse(1L, "learnhub"));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer good-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("learnhub"));
    }

    @Test
    void shouldReturn401WhenTokenIsInvalid() throws Exception {
        given(jwtTokenProvider.parseUserId("bad-token"))
                .willThrow(new JwtException("invalid token"));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer bad-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_0401"));
    }
}
```

跑一遍：

```powershell
mvn -pl learnhub-application -am test
```

如果 `UserControllerSecurityTest` 报 Bean 相关的错，先读 `Caused by` 最底层：多半是某个 `@Import` 的类缺依赖，对照答案检查 import 列表。

---

## 8. 复盘题

1. JWT 的三段各是什么？Payload 是加密的吗？为什么不能放密码？
2. 为什么 JWT 适合多实例部署，Session 不行？
3. `JwtAuthenticationFilter` 解析失败时为什么不能抛异常？异常抛在过滤器链和抛在 Controller 有什么区别？
4. `AuthenticationEntryPoint` 为什么必须自己写 JSON？默认行为是什么？
5. 登录接口为什么把“用户不存在”和“密码错误”合并成一个错误？
6. `UsernamePasswordAuthenticationToken` 三个参数的构造器表示什么？`getPrincipal()` 返回什么？
7. 测试里“带有效 token”的测试真的验证了过滤器吗？它和 `addFilters = false` 的测试有什么不同？

完成后进入 [Session D](session-d-rbac.md)：RBAC 角色权限。
