# Session B：注册与密码安全（BCrypt）

> 目标：实现 `POST /api/v1/auth/register`，密码以 BCrypt 哈希入库，用户名重复返回统一的业务错误。
>
> 档位：核心流程 🧑‍🏫 我带；测试部分 🤝 我们各写一半。
> 预计时间：3～4 小时。

## 0. 今天到底要学会什么

1. 为什么密码必须哈希存储，哈希和加密的区别。
2. BCrypt 为什么自带盐，为什么不能自己发明哈希方案。
3. Spring Security 的 `SecurityFilterChain` 怎么配置“哪些路径放行、哪些要登录”。
4. MyBatis-Plus 的实体 + Mapper 怎么用，为什么实体不能直接返回给前端。
5. 注册接口的测试怎么写：成功、参数错误、业务错误三条路径。

---

## 1. 先建立直觉：注册功能到底要解决什么

注册接口的流程看起来很简单：

```text
收到用户名密码 -> 检查用户名是否被占用 -> 存起来 -> 返回成功
```

但有两个问题一旦做错，面试直接完蛋：

### 1.1 密码不能存明文

如果数据库被拖库（这一定会发生，只是时间问题），明文密码等于直接送给攻击者，而且用户往往在多个网站用同一个密码。所以数据库里永远只存“密码的哈希”。

**哈希 ≠ 加密**：

- 加密：可逆，能解密回原文（需要密钥）。适合“要读回来的数据”。
- 哈希：不可逆，单向。适合“只验证不读取”的场景，密码正是这种。

### 1.2 哈希也不能随便存

直接存 `SHA-256(password)` 也危险，因为攻击者可以用彩虹表（预先算好一堆常见密码的哈希）反查。解决办法是**加盐**：每个用户生成一个随机盐，哈希 `salt + password`，这样同一个密码在不同用户那里的哈希值不同。

BCrypt 把“生成盐 → 哈希”做成了内置功能，还通过工作因子让计算故意变慢（几十到几百毫秒），拖慢暴力破解。**结论：用 Spring Security 提供的 `BCryptPasswordEncoder`，永远不要自己写哈希算法。**

---

## 2. 先认识几个关键概念

### 2.1 `PasswordEncoder`

Spring Security 定义的一个接口，核心两个方法：

- `encode(原始密码)`：加密/哈希，得到存储值。
- `matches(原始密码, 存储值)`：验证。登录时用。

`BCryptPasswordEncoder` 是它的标准实现。我们会把它注册成 Bean，Service 里构造器注入。

### 2.2 `SecurityFilterChain`

Spring Security 6 之后，安全配置就是“声明一个返回 `SecurityFilterChain` 的 Bean”，用 `HttpSecurity` 一步步描述规则。Session A 你已经写了最小版，今天把它收紧：

```text
放行：/api/v1/auth/**（注册、登录）、/actuator/**、Swagger 相关
其余：必须登录
```

> ⚠️ 如果你是第一次接触 Spring Security，上面这三行对你来说可能是“天书”。**先读 [前置教学：Spring Security 零基础入门](primer-spring-security.md)**（10 分钟），把过滤器链、SecurityContext、认证/授权、401/403 搞清楚再回来写代码。它会把 Step 8 的 `SecurityConfig` 逐行翻译给你看。

### 2.3 MyBatis-Plus 的实体与 Mapper

- **实体**：一个 Java 类对应一张表，字段用 `@TableName`、`@TableId` 标注。
- **Mapper**：继承 `BaseMapper<T>` 就自带增删改查；重要 SQL 用 `@Select` 手写。
- Mapper 需要 `@Mapper` 注解，MyBatis-Spring 启动时自动扫描注册。

依赖已经在 POM 里（版本由 Boot BOM 统一管理）：

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
</dependency>
```

它内部自带 `spring-boot-starter-jdbc` 和 HikariCP 连接池，所以数据源、事务这些基础能力不用再单独加依赖。

### 2.4 为什么实体不能直接返回给前端

`User` 实体里有 `passwordHash`。如果 Controller 直接 `return user`，JSON 里就会带出密码哈希。所以必须有一个脱敏的响应对象 `UserResponse`（只含 id、username）。**这是数据安全的基本功，面试必问。**

---

## 3. 跟着做一遍（🧑‍🏫 我带）

### Step 1：User 实体

创建文件：

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/entity/User.java
```

```java
package com.github.comui520.learnhub.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("`user`")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String passwordHash;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
```

注意两个设计点：

1. **用 `@Getter @Setter` 而不是 `@Data`**。`@Data` 会生成 `toString()`，把 `passwordHash` 也打出来——万一有人打印 `User`，密码哈希就进日志了。这是真实项目踩过的坑。
2. `@TableName("`user`")` 带反引号，和迁移文件保持一致，规避关键字。

顺带认识 Lombok：它是**编译期**的样板代码生成器，`@Getter`/`@Setter` 在编译时生成对应方法，编译后的 class 和手写效果一样。依赖是 `org.projectlombok:lombok`（`optional` scope，不传递给下游模块），版本由 Boot BOM 管理。

### Step 2：UserMapper

创建文件：

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/mapper/UserMapper.java
```

```java
package com.github.comui520.learnhub.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.comui520.learnhub.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /** 按用户名查询。这是核心查询，手写 SQL，面试能解释索引命中（uk_username）。 */
    @Select("SELECT * FROM `user` WHERE username = #{username}")
    User selectByUsername(String username);
}
```

`BaseMapper<User>` 自带 `insert`、`selectById`、`selectList` 等方法，`selectByUsername` 是我们手写的。

### Step 3：UserErrorCode

创建文件：

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/UserErrorCode.java
```

```java
package com.github.comui520.learnhub.user;

import com.github.comui520.learnhub.common.exception.ErrorCode;

public enum UserErrorCode implements ErrorCode {

    USERNAME_ALREADY_EXISTS("USER_ERROR_0409", "Username already exists", 409),
    INVALID_CREDENTIALS("USER_ERROR_0401", "Invalid username or password", 401),
    ACCOUNT_DISABLED("USER_ERROR_0403", "Account is disabled", 403),
    ;

    private final String code;
    private final String message;
    private final Integer httpStatus;

    UserErrorCode(String code, String message, Integer httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public Integer httpStatus() {
        return httpStatus;
    }
}
```

风格和 `DemoErrorCode`、`CommonErrorCode` 完全一致：code 用于业务识别，httpStatus 决定 HTTP 状态码。`INVALID_CREDENTIALS` 和 `ACCOUNT_DISABLED` 这节用不到，Session C 登录时用，先定义好。

### Step 4：RegisterRequest DTO

创建文件：

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/dto/RegisterRequest.java
```

```java
package com.github.comui520.learnhub.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "注册请求")
public record RegisterRequest(

        @Schema(description = "登录名", example = "learnhub")
        @NotBlank(message = "Username should not be blank")
        @Size(min = 3, max = 30, message = "Username length should be between 3 and 30")
        @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "Username should only contain letters, digits or underscore")
        String username,

        @Schema(description = "密码", example = "password123")
        @NotBlank(message = "Password should not be blank")
        @Size(min = 8, max = 64, message = "Password length should be between 8 and 64")
        String password
) {
}
```

三个校验注解的含义：`@NotBlank` 非空、`@Size` 长度范围、`@Pattern` 正则约束用户名只能由字母数字下划线组成。这些注解属于 Validation，失败时会由 `GlobalExceptionHandler` 转成 `COMMON_0400`。

### Step 5：UserResponse（脱敏响应）

创建文件：

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/dto/UserResponse.java
```

```java
package com.github.comui520.learnhub.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "用户信息（脱敏）")
public record UserResponse(
        @Schema(description = "用户 ID")
        Long id,

        @Schema(description = "登录名", example = "learnhub")
        String username
) {
}
```

注意：**没有 passwordHash 字段**。这是刻意的。

### Step 6：AuthService

创建文件：

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/service/AuthService.java
```

```java
package com.github.comui520.learnhub.user.service;

import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.user.UserErrorCode;
import com.github.comui520.learnhub.user.dto.RegisterRequest;
import com.github.comui520.learnhub.user.dto.UserResponse;
import com.github.comui520.learnhub.user.entity.User;
import com.github.comui520.learnhub.user.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse register(RegisterRequest request) {
        if (userMapper.selectByUsername(request.username()) != null) {
            throw new BusinessException(UserErrorCode.USERNAME_ALREADY_EXISTS);
        }

        User user = new User();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus(1);
        userMapper.insert(user);

        return new UserResponse(user.getId(), user.getUsername());
    }
}
```

逐行想清楚：

- 先查重，重复就抛 `BusinessException`，全局处理器转成 409。
- `passwordEncoder.encode(...)` 才是真正的密码处理——这里永远不出现 `request.password()` 直接入库。
- `userMapper.insert(user)` 之后，MyBatis-Plus 会把自增主键回填到 `user.getId()`。
- 返回的是脱敏的 `UserResponse`，不是 `User`。

> 面试点：查重 + 唯一索引的关系。Service 查重是为了“友好报错”，数据库 `uk_username` 唯一索引是“并发下的最后防线”。两个都要有，Session B 的错误练习会让你亲眼看到去掉唯一索引会发生什么。

### Step 7：AuthController

创建文件：

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/controller/AuthController.java
```

```java
package com.github.comui520.learnhub.user.controller;

import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.user.dto.RegisterRequest;
import com.github.comui520.learnhub.user.dto.UserResponse;
import com.github.comui520.learnhub.user.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "注册与登录")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "注册", description = "创建新账号，用户名全局唯一")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "注册成功，返回用户信息"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "参数错误（COMMON_0400）"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "用户名已存在（USER_ERROR_0409）")
    })
    @PostMapping("/register")
    public ApiResponse<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }
}
```

注意 `@ApiResponse` 用了全限定名 `@io.swagger.v3.oas.annotations.responses.ApiResponse`——因为和你自己的 `ApiResponse` 类同名冲突（Part 1 回顾文档 8.5 讲过这个坑）。以后每个 Controller 都照这个写法。

### Step 8：收紧 SecurityConfig

修改 `learnhub-user/.../config/SecurityConfig.java`：

```java
package com.github.comui520.learnhub.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

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
                        .anyRequest().authenticated());
        return http.build();
    }
}
```

从 Session A 的“全部放行”改成了“白名单放行，其余认证”。逐行理解：

- `requestMatchers(...)`：匹配的路径 `permitAll()`，不要求登录。
- `anyRequest().authenticated()`：**除白名单外所有请求都要认证**——这也是为什么你现在的 demo 接口访问会 401。
- `passwordEncoder()`：注册成 Bean，`AuthService` 构造器注入的就是它。

### Step 9：启动并验证

```powershell
mvn -pl learnhub-application -am spring-boot:run "-Dspring-boot.run.profiles=dev"
```

另开 PowerShell 依次执行：

```powershell
curl.exe -i -X POST "http://localhost:8080/api/v1/auth/register" -H "Content-Type: application/json" -d "{\"username\":\"learnhub\",\"password\":\"password123\"}"
```

期望：HTTP 200，body 里 `code=COMMON_0000`，`data.id` 和 `data.username` 正常，**没有 password 字段**。

再执行一次同样命令：

期望：HTTP 409，`code=USER_ERROR_0409`。

参数错误：

```powershell
curl.exe -i -X POST "http://localhost:8080/api/v1/auth/register" -H "Content-Type: application/json" -d "{\"username\":\"\",\"password\":\"password123\"}"
```

期望：HTTP 400，`code=COMMON_0400`，`data[0].field=username`。

最后去数据库验证密码：

```powershell
docker compose exec mysql mysql -ulearnhub -pchange-me-mysql learnhub -e "SELECT id, username, LEFT(password_hash, 10) AS hash_prefix FROM `user`;"
```

期望 `hash_prefix` 是 `$2a$...` 或 `$2b$...`（BCrypt 特征前缀），不是明文。注册两个不同用户再查，会发现相同密码的哈希也完全不同（因为盐不同）。

---

## 4. 观察结果：成功长什么样

```text
成功证据：
1. 注册成功返回 200 + data 无密码字段
2. 重复注册返回 409 USER_ERROR_0409
3. 空用户名返回 400 COMMON_0400，data[0].field=username
4. MySQL 里 password_hash 以 $2a$/$2b$ 开头
```

---

## 5. 主动制造错误（每个做完恢复）

**错误 A：明文存密码**

把 `user.setPasswordHash(passwordEncoder.encode(request.password()))` 改成 `user.setPasswordHash(request.password())`，重新注册一个用户，去 MySQL 看——明文躺在数据库里。这是最直观的“为什么必须哈希”。恢复并删掉这个测试用户。

**错误 B：注释掉查重**

把 `if (userMapper.selectByUsername(...) != null)` 那几行注释掉，连续注册两个相同用户名。第一次成功，第二次报 500（`Duplicate entry`），因为撞了唯一索引。亲眼看到后恢复。这能让你讲清“唯一索引兜底”和“Service 查重给友好提示”的分工。

**错误 C：忘了放行注册路径**

把 `requestMatchers` 里的 `/api/v1/auth/**` 删掉，注册接口立刻变成 401/403（因为 `anyRequest().authenticated()`）。恢复。

**错误 D：正则写错**

把 `@Pattern` 的正则改成 `^[a-z]+$`，用 `"LearnHub"` 注册，观察 400 和错误消息。恢复。

---

## 6. 独立练习

### 🤝 我们各写一半：AuthControllerTest

这个测试放在 **application 模块**的测试目录：

```text
learnhub-application/src/test/java/com/github/comui520/learnhub/user/controller/AuthControllerTest.java
```

为什么放这里？因为测试要 `@Import(GlobalExceptionHandler.class)`，而全局异常处理器在 application 模块。依赖方向是 application → user，所以“组合后的行为”在 application 侧测试——这正是模块化单体里 application 负责组合的体现。

我已经写好类骨架和第一个成功测试，你来补两个测试：

```java
package com.github.comui520.learnhub.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.comui520.learnhub.user.dto.RegisterRequest;
import com.github.comui520.learnhub.user.dto.UserResponse;
import com.github.comui520.learnhub.user.service.AuthService;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
public class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @Test
    void shouldReturnUserWhenRegisterSucceeds() throws Exception {
        RegisterRequest request = new RegisterRequest("learnhub", "password123");
        given(authService.register(request))
                .willReturn(new UserResponse(1L, "learnhub"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON_0000"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.username").value("learnhub"));
    }

    // TODO 你来写：
    // 1) shouldReturnBadRequestWhenUsernameIsBlank
    //    请求体 {"username":"","password":"password123"}
    //    断言 400、code=COMMON_0400、data[0].field=username、authService 没被调用
    // 2) shouldReturnConflictWhenUsernameAlreadyExists
    //    given(authService.register(any())).willThrow(new BusinessException(UserErrorCode.USERNAME_ALREADY_EXISTS))
    //    断言 409、code=USER_ERROR_0409
}
```

需要的提示：

- 第二个测试要 `import static org.mockito.ArgumentMatchers.any;`
- 第一个测试用 `verifyNoInteractions(authService)` 验证“请求根本没进 Service”。

### 🏃 你自己做：AuthServiceTest

在 user 模块测试目录新建：

```text
learnhub-user/src/test/java/com/github/comui520/learnhub/user/service/AuthServiceTest.java
```

> ⚠️ 这是你第一次写 `@Mock` / `@InjectMocks`。先读 [前置教学：Mockito 与 Service 测试](primer-mockito-and-service-testing.md)，里面把 `Mockito`、`@Mock`、`@MockitoBean` 的区别、每行注解的意思、真实开发怎么测 Service 都讲清楚了。

要求：

- 用 `@ExtendWith(MockitoExtension.class)` + `@Mock UserMapper` + `@Mock PasswordEncoder` + `@InjectMocks AuthService`。
- 测试 1：用户名未被占用时注册成功——断言返回的 `UserResponse.username` 正确，并 `verify(userMapper).insert(any(User.class))`。
- 测试 2：用户名已存在时抛 `BusinessException`，消息是 `UserErrorCode.USERNAME_ALREADY_EXISTS.message()`。
- 不启动 Spring，纯单元测试。

---

## 7. 参考答案

### AuthControllerTest 的两个测试

```java
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@Test
void shouldReturnBadRequestWhenUsernameIsBlank() throws Exception {
    RegisterRequest request = new RegisterRequest("", "password123");

    mockMvc.perform(post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COMMON_0400"))
            .andExpect(jsonPath("$.data[0].field").value("username"));

    verifyNoInteractions(authService);
}

@Test
void shouldReturnConflictWhenUsernameAlreadyExists() throws Exception {
    RegisterRequest request = new RegisterRequest("learnhub", "password123");
    given(authService.register(any()))
            .willThrow(new BusinessException(UserErrorCode.USERNAME_ALREADY_EXISTS));

    mockMvc.perform(post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(request)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("USER_ERROR_0409"));
}
```

别忘了补 `import com.github.comui520.learnhub.common.exception.BusinessException;` 和 `import com.github.comui520.learnhub.user.UserErrorCode;`。

### AuthServiceTest

```java
package com.github.comui520.learnhub.user.service;

import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.user.UserErrorCode;
import com.github.comui520.learnhub.user.dto.RegisterRequest;
import com.github.comui520.learnhub.user.dto.UserResponse;
import com.github.comui520.learnhub.user.entity.User;
import com.github.comui520.learnhub.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    @Test
    void shouldEncodePasswordAndInsertUserWhenUsernameIsFree() {
        given(userMapper.selectByUsername("learnhub")).willReturn(null);
        given(passwordEncoder.encode("password123")).willReturn("$2a$10$hashed-value");

        UserResponse response = authService.register(new RegisterRequest("learnhub", "password123"));

        assertThat(response.username()).isEqualTo("learnhub");
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void shouldThrowWhenUsernameAlreadyExists() {
        given(userMapper.selectByUsername("learnhub")).willReturn(new User());

        assertThatThrownBy(() -> authService.register(new RegisterRequest("learnhub", "password123")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(UserErrorCode.USERNAME_ALREADY_EXISTS.message());
    }
}
```

跑一遍：

```powershell
mvn -pl learnhub-application -am test
```

全绿后，把复盘题答案发给老师。

---

## 8. 复盘题

1. 哈希和加密的区别是什么？为什么密码用哈希？
2. BCrypt 的“盐”解决了什么问题？为什么两个相同密码的哈希不同？
3. `SecurityFilterChain` 里 `permitAll` 和 `authenticated` 分别什么意思？为什么 demo 接口现在访问 401 了？
4. 为什么 `User` 实体不能直接返回给前端？`UserResponse` 少了什么字段？
5. Service 查重和数据库唯一索引分别解决什么问题？去掉任何一个会发生什么？
6. `@WebMvcTest` 的测试为什么放在 application 模块？`addFilters = false` 是绕过安全吗？

完成后进入 [Session C](session-c-login-and-jwt.md)：登录与 JWT。
