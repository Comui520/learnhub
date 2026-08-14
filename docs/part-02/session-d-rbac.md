# Session D：RBAC 角色权限

> 目标：引入角色和权限表，注册时默认给 USER 角色，过滤器按角色装配权限，实现“普通用户 403、管理员 200”的用户列表接口。
>
> 档位：建表和角色加载 🧑‍🏫 我带；管理员列表接口 🏃 你自己做。
> 预计时间：3～4 小时。

## 0. 今天到底要学会什么

1. 为什么需要 RBAC，为什么用户和角色之间要中间表。
2. 角色（Role）和权限（Permission）的区别，五张表的经典设计。
3. `@PreAuthorize("hasRole('ADMIN')")` 方法级鉴权。
4. 403 的两种来源，以及为什么都要处理。
5. 为什么注册要变成事务（两步写入，要么都成功要么都失败）。

---

## 1. 先建立直觉：为什么不能直接给用户加个“管理员”字段

如果只有一个管理员，给 `user` 表加个 `is_admin` 字段就够了。但真实系统会有多种角色、多种权限，而且会变：

- 管理员、老师、学生……角色越来越多。
- 某个角色能干什么也会变（比如“老师可以批改作业”）。
- 一个用户可能同时是“老师”又是“课程管理员”。

把角色直接写进用户表，每次需求变化都要改表结构。RBAC（基于角色的访问控制）把问题拆成三层：

```text
用户（谁） ---- 属于多个 --> 角色（身份） ---- 拥有多个 --> 权限（能做什么）
```

用户和角色、角色和权限都是**多对多**关系，多对多就需要中间表：

```text
user            user_role          role            role_permission         permission
id  username    user_id role_id    id  code       role_id permission_id    id  code
```

面试时能画出这张图并解释“为什么需要中间表”，就是标准答案。

## 2. 先认识关键概念

### 2.1 角色 vs 权限

- 角色是“身份的集合”：ADMIN、USER。
- 权限是“动作的集合”：`user:list`（查看用户列表）、`user:manage`（管理用户）。
- 权限控制更细，角色是权限的打包。本项目先用角色控制（`hasRole`），权限表先建好作为设计基础，练习里你可以升级到权限控制（`hasAuthority`）。

### 2.2 `@PreAuthorize`

Spring Security 的方法级鉴权：在方法上加注解，调用前检查。

```java
@PreAuthorize("hasRole('ADMIN')")
public List<UserResponse> listUsers() { ... }
```

前提是配置类上有 `@EnableMethodSecurity`。`hasRole('ADMIN')` 实际上检查 `ROLE_ADMIN` 这个权限标识。

### 2.3 403 的两种来源

1. **过滤器链层面**：URL 规则拦截（比如 `.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")`），由 `AccessDeniedHandler` 处理。
2. **方法层面**：`@PreAuthorize` 在 Controller 调用时抛 `AccessDeniedException`，它**回不到过滤器链**，会落到 `@RestControllerAdvice`。

所以两个地方都要处理：过滤器链配 `AccessDeniedHandler` 返回 403 JSON，全局异常处理器加一个 `AccessDeniedException` 的分支返回 403 JSON。

### 2.4 @Transactional

注册时现在要做两次插入（user + user_role）。如果第二次失败，用户表多了一条没角色的脏数据。`@Transactional` 让两步在一个事务里：任何一个失败，全部回滚。MyBatis-Plus 自带 Spring 事务支持，直接加注解即可。

---

## 3. 跟着做一遍（🧑‍🏫 我带）

### Step 1：V2 迁移——角色与权限五张表

创建文件：

```text
learnhub-user/src/main/resources/db/migration/V2__init_role_tables.sql
```

```sql
CREATE TABLE `role`
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    code       VARCHAR(30)     NOT NULL COMMENT '角色编码，如 ADMIN / USER',
    name       VARCHAR(50)     NOT NULL COMMENT '角色名称',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='角色表';

CREATE TABLE `permission`
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    code       VARCHAR(50)     NOT NULL COMMENT '权限编码，如 user:list',
    name       VARCHAR(50)     NOT NULL COMMENT '权限名称',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='权限表';

CREATE TABLE `user_role`
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    BIGINT UNSIGNED NOT NULL COMMENT '用户 ID',
    role_id    BIGINT UNSIGNED NOT NULL COMMENT '角色 ID',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES `user` (id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES `role` (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='用户角色关联表';

CREATE TABLE `role_permission`
(
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    role_id       BIGINT UNSIGNED NOT NULL COMMENT '角色 ID',
    permission_id BIGINT UNSIGNED NOT NULL COMMENT '权限 ID',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_permission (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES `role` (id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES `permission` (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='角色权限关联表';

INSERT INTO `role` (code, name)
VALUES ('ADMIN', '管理员'),
       ('USER', '普通用户');

INSERT INTO `permission` (code, name)
VALUES ('user:list', '查看用户列表'),
       ('user:manage', '管理用户');

INSERT INTO `role_permission` (role_id, permission_id)
SELECT r.id, p.id
FROM `role` r,
     `permission` p
WHERE r.code = 'ADMIN'
  AND p.code IN ('user:list', 'user:manage');

INSERT INTO `role_permission` (role_id, permission_id)
SELECT r.id, p.id
FROM `role` r,
     `permission` p
WHERE r.code = 'USER'
  AND p.code = 'user:list';
```

看懂几个点：

- `UNIQUE KEY uk_user_role (user_id, role_id)`：同一用户同一角色不能重复绑定。
- 外键 `FOREIGN KEY`：保证引用的 user/role 一定存在，删用户时会约束（我们暂时不做级联删除）。
- `INSERT ... SELECT`：把初始角色和权限绑好，属于“种子数据”，适合放迁移里。

重启应用，观察 `Successfully applied 2 migrations`，然后在 MySQL 里验证：

```powershell
docker compose exec mysql mysql -ulearnhub -pchange-me-mysql learnhub -e "SHOW TABLES; SELECT * FROM role; SELECT * FROM role_permission;"
```

### Step 2：RoleMapper 和 UserRoleMapper

创建两个文件：

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/mapper/RoleMapper.java
```

```java
package com.github.comui520.learnhub.user.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RoleMapper {

    @Select("SELECT id FROM `role` WHERE code = #{code}")
    Long selectIdByCode(String code);
}
```

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/mapper/UserRoleMapper.java
```

```java
package com.github.comui520.learnhub.user.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserRoleMapper {

    @Insert("INSERT INTO `user_role` (user_id, role_id) VALUES (#{userId}, #{roleId})")
    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);

    @Select("""
            SELECT r.code
            FROM `role` r
                     JOIN `user_role` ur ON r.id = ur.role_id
            WHERE ur.user_id = #{userId}
            """)
    List<String> selectRoleCodesByUserId(Long userId);
}
```

注意 `@Select` 里用了 Java 文本块（`"""`）写多行 SQL，可读性好很多。

### Step 3：注册时默认给 USER 角色

修改 `AuthService`：加两个依赖和一个 `@Transactional`：

```java
import com.github.comui520.learnhub.user.mapper.RoleMapper;
import com.github.comui520.learnhub.user.mapper.UserRoleMapper;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;

    public AuthService(UserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       RoleMapper roleMapper,
                       UserRoleMapper userRoleMapper) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userMapper.selectByUsername(request.username()) != null) {
            throw new BusinessException(UserErrorCode.USERNAME_ALREADY_EXISTS);
        }

        User user = new User();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus(1);
        userMapper.insert(user);

        Long userRoleId = roleMapper.selectIdByCode("USER");
        userRoleMapper.insertUserRole(user.getId(), userRoleId);

        return new UserResponse(user.getId(), user.getUsername());
    }

    // login 方法不变
}
```

回答自己：为什么 `register` 现在是事务？如果 `insertUserRole` 失败，会发生什么？(答案：user 记录也会回滚，不会留下“没有角色的用户”)

### Step 4：过滤器按角色装配权限

修改 `JwtAuthenticationFilter`：

```java
package com.github.comui520.learnhub.user.security;

import com.github.comui520.learnhub.user.mapper.UserRoleMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
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
    private final UserRoleMapper userRoleMapper;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   UserRoleMapper userRoleMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userRoleMapper = userRoleMapper;
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
                List<GrantedAuthority> authorities = userRoleMapper.selectRoleCodesByUserId(userId)
                        .stream()
                        .map(code -> new SimpleGrantedAuthority("ROLE_" + code))
                        .toList();
                var authentication = new UsernamePasswordAuthenticationToken(
                        userId, null, authorities
                );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
```

关键变化：`ROLE_USER` 硬编码变成了数据库查询 `selectRoleCodesByUserId(userId)`，每个角色转成 `ROLE_<CODE>` 权限标识。`hasRole('ADMIN')` 检查的正是 `ROLE_ADMIN`。

> 面试点：这样每个请求都要查一次数据库角色，简单但不够快。等到 Part 6 会用 Redis 缓存“用户 → 角色”，这里先埋下伏笔。

### Step 5：RestAccessDeniedHandler（过滤器链层面的 403 JSON）

创建文件：

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/security/RestAccessDeniedHandler.java
```

```java
package com.github.comui520.learnhub.user.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.common.exception.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.failure(CommonErrorCode.FORBIDDEN));
    }
}
```

### Step 6：SecurityConfig 开启方法安全 + 接入 403 处理器

修改 `SecurityConfig`：

```java
package com.github.comui520.learnhub.user.config;

import com.github.comui520.learnhub.user.security.JwtAuthenticationFilter;
import com.github.comui520.learnhub.user.security.RestAccessDeniedHandler;
import com.github.comui520.learnhub.user.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RestAuthenticationEntryPoint restAuthenticationEntryPoint,
                          RestAccessDeniedHandler restAccessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.restAuthenticationEntryPoint = restAuthenticationEntryPoint;
        this.restAccessDeniedHandler = restAccessDeniedHandler;
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
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

新增了两样：类上的 `@EnableMethodSecurity`（`@PreAuthorize` 才能生效），和 `accessDeniedHandler(...)`（过滤器链层面的 403）。

### Step 7：GlobalExceptionHandler 兜住方法级 403

修改 `learnhub-application/.../web/service/GlobalExceptionHandler.java`，加一个分支：

```java
import org.springframework.security.access.AccessDeniedException;

@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException exception) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiResponse.failure(CommonErrorCode.FORBIDDEN));
}
```

为什么必须加：`@PreAuthorize` 的 `AccessDeniedException` 是在 Controller 调用阶段抛的，**不会**经过过滤器链，所以 `RestAccessDeniedHandler` 管不到它。如果不处理，会被你的兜底 `Exception` 处理器转成 500。你现在就有两条 403 路径，面试能讲清楚就是加分项：

```text
URL 级拦截（过滤器链） -> RestAccessDeniedHandler -> 403 JSON
@PreAuthorize（方法级） -> GlobalExceptionHandler -> 403 JSON
```

---

## 4. 🏃 你自己做：管理员用户列表接口

这是你第一次独立完成一个完整接口。要求如下，先自己想，卡住了再看提示，做完对答案。

### 需求

`GET /api/v1/users`（注意没有 `/me`），只允许 `ADMIN` 角色访问，返回全部用户的脱敏列表 `ApiResponse<List<UserResponse>>`。

### 验收标准

- [ ] 普通用户（只有 USER 角色）访问返回 403，`code=COMMON_0403`。
- [ ] 管理员（ADMIN 角色）访问返回 200 和用户列表。
- [ ] 未登录访问返回 401（`anyRequest().authenticated()` 已经保证）。
- [ ] 响应里没有 passwordHash。
- [ ] Swagger 文档标注了 200/401/403 三种响应。
- [ ] 有测试覆盖 403 和 200 两条路径。

### 提示（先别看答案）

1. `UserMapper extends BaseMapper<User>`，查询全部用 `userMapper.selectList(null)`。
2. 实体不能直接返回，用 stream 转成 `UserResponse`：`.stream().map(u -> new UserResponse(u.getId(), u.getUsername())).toList()`。
3. 权限注解加在 Controller 方法上：`@PreAuthorize("hasRole('ADMIN')")`。
4. 控制器路径：`UserController` 已经 `@RequestMapping("/api/v1/users")`，加一个不带路径的 `@GetMapping` 就是 `/api/v1/users`。
5. 测试在 `UserControllerSecurityTest` 里补两个用例：stub `userRoleMapper.selectRoleCodesByUserId(1L)` 返回 `List.of("USER")` → 403；返回 `List.of("ADMIN")` → 200。别忘了给测试类加 `@MockitoBean UserRoleMapper` 和 `@MockitoBean UserService`。
6. ⚠️ `SecurityConfig` 现在构造器多了 `RestAccessDeniedHandler`，所以 `UserControllerSecurityTest` 的 `@Import` 列表**必须加上 `RestAccessDeniedHandler.class`**，并加 `@MockitoBean UserRoleMapper`——否则测试上下文启动失败（过滤器创建不了）。这正是“改配置就要跟着改测试”的连锁反应，值得记进 Bug 记录。

### 需要的代码改动清单

```text
1. UserService 加 listUsers() 方法
2. UserController 加 listUsers() 接口 + @PreAuthorize + @Operation/@ApiResponses
3. UserControllerSecurityTest 加 @MockitoBean UserRoleMapper 和两个测试
```

---

## 5. 参考答案

### UserService

```java
public List<UserResponse> listUsers() {
    return userMapper.selectList(null).stream()
            .map(user -> new UserResponse(user.getId(), user.getUsername()))
            .toList();
}
```

### UserController

```java
@Operation(summary = "用户列表（仅管理员）")
@ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "返回用户列表"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未登录（COMMON_0401）"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "无权限（COMMON_0403）")
})
@GetMapping
@PreAuthorize("hasRole('ADMIN')")
public ApiResponse<List<UserResponse>> listUsers() {
    return ApiResponse.success(userService.listUsers());
}
```

补 import：`java.util.List`、`org.springframework.security.access.prepost.PreAuthorize`。

### UserControllerSecurityTest 新增

```java
@MockitoBean
private UserRoleMapper userRoleMapper;

@Test
void shouldReturn403WhenRoleIsUser() throws Exception {
    given(jwtTokenProvider.parseUserId("user-token")).willReturn(1L);
    given(jwtTokenProvider.parseUsername("user-token")).willReturn("bob");
    given(userRoleMapper.selectRoleCodesByUserId(1L)).willReturn(List.of("USER"));

    mockMvc.perform(get("/api/v1/users")
                    .header("Authorization", "Bearer user-token"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("COMMON_0403"));
}

@Test
void shouldReturnUserListWhenRoleIsAdmin() throws Exception {
    given(jwtTokenProvider.parseUserId("admin-token")).willReturn(1L);
    given(jwtTokenProvider.parseUsername("admin-token")).willReturn("alice");
    given(userRoleMapper.selectRoleCodesByUserId(1L)).willReturn(List.of("ADMIN"));
    given(userService.listUsers()).willReturn(List.of(new UserResponse(1L, "alice")));

    mockMvc.perform(get("/api/v1/users")
                    .header("Authorization", "Bearer admin-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].username").value("alice"));
}
```

跑测试：

```powershell
mvn -pl learnhub-application -am test
```

然后手动验证：注册一个新用户（默认 USER 角色）登录，访问 `/api/v1/users` 应该 403。再把这个用户手动提成管理员：

```powershell
docker compose exec mysql mysql -ulearnhub -pchange-me-mysql learnhub -e "INSERT INTO user_role (user_id, role_id) SELECT u.id, r.id FROM user u, role r WHERE u.username='learnhub' AND r.code='ADMIN';"
```

重新登录（重新签发 token），再访问，应该 200。

---

## 6. 主动制造错误（每个做完恢复）

**错误 A：忘了 @EnableMethodSecurity**

把 `SecurityConfig` 上的 `@EnableMethodSecurity` 注释掉，管理员接口立刻对所有登录用户 403。恢复。这让你理解“注解不是写了就有，前提是开了方法安全”。

**错误 B：不加 GlobalExceptionHandler 的 AccessDeniedException 分支**

把 Step 7 加的分支临时注释掉，普通用户访问管理员接口，观察它变成 **500** 而不是 403。恢复。这是“403 有两种来源”的现场证据。

**错误 C：角色没加载**

把过滤器的角色查询改成 `List.of(new SimpleGrantedAuthority("ROLE_USER"))`，管理员登录也 403。恢复。

---

## 7. 独立练习（升级题）

把管理员列表接口的鉴权从“角色”升级到“权限”：

1. 用 `@PreAuthorize("hasAuthority('user:list')")` 替换 `hasRole('ADMIN')`。
2. 让过滤器把角色对应的**权限编码**（从 `role_permission` 联表查）装进 authorities。
3. 思考并回答：`hasRole` 和 `hasAuthority` 有什么区别？什么时候用哪个？

答案要点：`hasRole('ADMIN')` 实际检查 `ROLE_ADMIN`（自动加前缀）；`hasAuthority('user:list')` 精确匹配。角色适合“身份粗粒度”，权限适合“动作细粒度”；权限更灵活但维护成本高，小项目通常角色够用。

### 具体实现（参考）

**① Mapper 加联表查权限的方法**（放 `UserRoleMapper`）：

```java
@Select("""
        SELECT DISTINCT p.code
        FROM `user_role` ur
                 JOIN `role_permission` rp ON ur.role_id = rp.role_id
                 JOIN `permission` p ON rp.permission_id = p.id
        WHERE ur.user_id = #{userId}
        """)
List<String> selectPermissionCodesByUserId(Long userId);
```

`DISTINCT` 不能省：一个用户可能通过多个角色拿到同一个权限，不去重会导致 authorities 里出现重复项。

**② 过滤器把权限编码装进 authorities**（角色 + 权限都装，这样 `hasRole` 和 `hasAuthority` 都能用）：

```java
List<GrantedAuthority> authorities = new ArrayList<>();
userRoleMapper.selectRoleCodesByUserId(id)
        .forEach(code -> authorities.add(new SimpleGrantedAuthority("ROLE_" + code)));
userRoleMapper.selectPermissionCodesByUserId(id)
        .forEach(code -> authorities.add(new SimpleGrantedAuthority(code))); // 权限不加 ROLE_ 前缀

UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(id, null, authorities);
```

**③ 注解换成权限**：

```java
@PreAuthorize("hasAuthority('user:list')")
```

**④ ⚠️ 数据语义坑：种子数据里 USER 角色也有 `user:list`！**

V3 的种子数据把 `user:list` 同时给了 ADMIN 和 USER。直接换注解后，普通用户也能通过 `hasAuthority('user:list')`，你的 403 测试会变红——这不是代码错了，是**权限数据和接口语义没对齐**（“查看用户列表”本来就不该是普通用户的功能）。

已执行的 V3 **不能改**（Flyway checksum 校验），所以要新增 V4 迁移把 USER 的 `user:list` 删掉：

```sql
-- V4__remove_user_list_permission_from_user_role.sql
DELETE FROM `role_permission`
WHERE role_id = (SELECT id FROM `role` WHERE code = 'USER')
  AND permission_id = (SELECT id FROM `permission` WHERE code = 'user:list');
```

**⑤ 更新测试**：过滤器现在会查两个方法，测试的 stub 也要跟上（Mockito 对 List 返回类型默认给空列表，不 stub 也不会 NPE，但显式写出来更清晰）：

```java
// 403：普通用户有角色但没有 user:list 权限
given(userRoleMapper.selectRoleCodesByUserId(1L)).willReturn(List.of("USER"));
given(userRoleMapper.selectPermissionCodesByUserId(1L)).willReturn(List.of());

// 200：管理员同时有角色和权限
given(userRoleMapper.selectRoleCodesByUserId(1L)).willReturn(List.of("ADMIN"));
given(userRoleMapper.selectPermissionCodesByUserId(1L)).willReturn(List.of("user:list"));
```

---

## 8. 复盘题

1. 为什么用户和角色之间需要中间表？直接加 `role` 字段到 user 表有什么问题？
2. 角色和权限有什么区别？本项目为什么先用角色？
3. `@PreAuthorize` 抛的 403 和过滤器链拦截的 403，处理位置分别在哪？
4. 为什么注册需要 `@Transactional`？去掉会发生什么？
5. 过滤器每个请求都查角色，有什么性能问题？怎么优化？（提示：Redis）
6. `hasRole` 和 `hasAuthority` 有什么区别？

完成后进入 [Session E](session-e-login-attempt-limit-and-audit.md)：登录失败限制与审计日志。
