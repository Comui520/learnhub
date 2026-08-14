# Part 2 重点知识总结：用户、登录与权限

> 给完成 Session A ~ E 的你。这篇把零散的知识点和踩过的坑整理成一张“知识网”，既能复习，也是面试前的速查卡。
>
> 用法：先完整读一遍；然后只看第 7 节“面试速答卡”能否自己复述；最后做第 8 节自测清单。

## 1. 全流程串讲：认证与授权的一天

```mermaid
flowchart LR
    A[客户端发请求] --> B[Tomcat]
    B --> C[Security 过滤器链]
    C --> D[JwtAuthenticationFilter<br/>解析 Bearer token]
    D --> E{token 有效?}
    E -- 否 --> F[清空 SecurityContext]
    E -- 是 --> G[放 Authentication 进 SecurityContext]
    F --> H[AuthorizationFilter]
    G --> H
    H --> I{已认证?}
    I -- 否 --> J[AuthenticationEntryPoint<br/>401 JSON]
    I -- 是 --> K{有权限?]
    K -- 否 --> L[403：方法级→GlobalExceptionHandler<br/>过滤器级→AccessDeniedHandler]
    K -- 是 --> M[DispatcherServlet → Controller]
```

一句话版本：**请求进过滤器链 → 过滤器读 token 发“名片”（SecurityContext）→ 授权过滤器检查“能不能进” → 能进就到 Controller，不能进就 401/403。**

---

## 2. Spring Security 核心（重点中的重点）

### 2.1 过滤器链

- 一个 HTTP 请求在到达 Controller 前，会经过一串 Servlet Filter；Spring Security 用 `FilterChainProxy` 把自己的过滤器打包成一串插进去。
- 我们自定义的 `JwtAuthenticationFilter extends OncePerRequestFilter`，通过 `addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)` 插到指定位置。
- **黄金规则**：过滤器要么调用 `filterChain.doFilter(request, response)` 放行，要么自己写响应——**绝不能什么都不做就返回**（Session C 的“200 空响应”就是忘了放行）。

### 2.2 认证：SecurityContext 与 Authentication

- 认证结果是一个 `Authentication` 对象（principal=谁、authorities=权限、authenticated=是否已认证）。
- 它放在 `SecurityContext` 里，`SecurityContext` 放在 `SecurityContextHolder`（ThreadLocal，每请求一个，请求结束自动清）。
- 过滤器负责“放名片”：`SecurityContextHolder.getContext().setAuthentication(...)`。
- Controller 负责“取名片”：`SecurityContextHolder.getContext().getAuthentication().getPrincipal()`。
- `UsernamePasswordAuthenticationToken(principal, null, authorities)` 三参构造 = 已认证。

### 2.3 授权：authorities、ROLE_ 前缀、hasRole vs hasAuthority

- 权限标识分两类：**角色**（带 `ROLE_` 前缀，如 `ROLE_ADMIN`）和**权限编码**（原样，如 `user:list`）。
- `hasRole('ADMIN')` 实际检查 `ROLE_ADMIN`（自动加前缀）。
- `hasAuthority('user:list')` 精确匹配 `user:list`（**不要加 ROLE_ 前缀**）。
- 角色 = 粗粒度身份；权限 = 细粒度动作。权限更灵活但维护成本高。

### 2.4 401 与 403：四条路径要分清

```text
未认证（没 token / token 无效）
  -> AuthenticationEntryPoint（过滤器链里）-> 401 JSON

已认证但过滤器级授权失败（URL 规则）
  -> AccessDeniedHandler（过滤器链里）-> 403 JSON

已认证但方法级授权失败（@PreAuthorize）
  -> AccessDeniedException 抛在 Controller 层
  -> @RestControllerAdvice 的 ExceptionHandler -> 403 JSON

未知异常
  -> @ExceptionHandler(Exception.class) -> 500 JSON
```

关键理解：**过滤器链里的异常到不了 `@RestControllerAdvice`**（它们在 DispatcherServlet 之前），所以 401/403 的过滤器层处理必须自己写 JSON；而 `@PreAuthorize` 的异常发生在 Controller 调用阶段，必须由全局异常处理器兜住——两个地方都要有代码。

### 2.5 JwtAuthenticationFilter 标准骨架（含易错点）

```java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenTool jwtTool;
    // 构造器注入

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);          // "Bearer " 正好 7 个字符
            try {
                Long id = jwtTool.parseId(token);
                List<GrantedAuthority> authorities = /* 查角色/权限 */;
                var authentication = new UsernamePasswordAuthenticationToken(id, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();    // 解析失败 = 没有名片，走 401，不要抛 500
            }
        }
        filterChain.doFilter(request, response);          // ← 必须在 if 外面，无条件放行
    }
}
```

易错点三个：

1. `doFilter` 写进 `if` 里 → 不带 token 的请求被吞掉 → 全站 200 空响应。
2. catch 里抛异常 → 500；正确做法是清空上下文走 401。
3. 权限编码拼了 `ROLE_` 前缀 → `hasAuthority` 永远匹配不上。

### 2.6 SecurityConfig 逐行速查

```java
@Configuration
@EnableMethodSecurity                       // 不写这行，@PreAuthorize 是摆设
public class SecurityConfig {
    // 构造器注入：JwtAuthenticationFilter、RestAuthenticationEntryPoint、RestAccessDeniedHandler

    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
            .csrf(AbstractHttpConfigurer::disable)          // 无状态 API 不用 Cookie 认证，关闭 CSRF
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**", "/actuator/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated())              // anyRequest 必须最后
            .exceptionHandling(e -> e
                .authenticationEntryPoint(entryPoint)       // 401 JSON
                .accessDeniedHandler(accessDeniedHandler))  // 过滤器层 403 JSON
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

### 2.7 为什么关 CSRF、用 STATELESS

- CSRF 防的是“浏览器自动带 Cookie 导致伪造请求”。我们用 `Authorization` 头带 token，浏览器不会自动带，主要攻击路径消失，所以关掉。**如果你以后用 Cookie 存 token，CSRF 会回来。**
- STATELESS = 服务端不建 Session、不存登录状态，每个请求靠 token 自证身份。好处是多实例部署不用共享会话。

---

## 3. 异常与统一响应

`GlobalExceptionHandler` 应该有的分支：

| 异常 | 状态码 | 说明 |
|---|---|---|
| `BusinessException` | 由 errorCode 决定 | 业务规则拒绝 |
| `MethodArgumentNotValidException` | 400 | 参数校验失败 |
| `HttpMessageNotReadableException` | 400 | JSON 解析失败 |
| `AccessDeniedException`（security 包） | 403 | `@PreAuthorize` 权限不足 |
| `Exception` | 500 | 兜底，记日志不泄漏堆栈 |

⚠️ import 用 `org.springframework.security.access.AccessDeniedException`，不是 `java.nio.file.AccessDeniedException`（同名类陷阱，Session D 踩过）。

---

## 4. 测试套路总结

| 场景 | 用什么 | 关键点 |
|---|---|---|
| 纯 Service 无依赖 | 直接 new | week-01 的 GreetingServiceTest |
| Service 有依赖 | `@ExtendWith(MockitoExtension.class)` + `@Mock` + `@InjectMocks` | given/verify/ArgumentCaptor |
| Controller 切片（不测安全） | `@WebMvcTest` + `@AutoConfigureMockMvc(addFilters = false)` + `@MockitoBean` | 只测映射/校验/异常 |
| Controller 切片（测安全） | `@WebMvcTest` + `@Import`（SecurityConfig、过滤器、EntryPoint、GlobalExceptionHandler）+ `@MockitoBean` | 过滤器真的会跑 |

- 测试放哪个模块：**跟着依赖走**。要 `@Import(GlobalExceptionHandler)` 的测试放 application 模块（依赖方向不回头）。
- `@Mock`（纯 Mockito，不进容器）vs `@MockitoBean`（Spring 容器替换 Bean）：别混。
- 断言异常：`assertThatThrownBy`（链式）优先；要拿异常对象反复用就 `catchThrowableOfType`。

---

## 5. 数据库与 Flyway 约定

- 迁移文件放**拥有这些表的模块**的 `src/main/resources/db/migration/`。
- 版本号**全局连续**（所有模块共用一条 `flyway_schema_history`），新模块从当前最大版本的下一个数字开始。
- 已执行的迁移文件**永远不许改**（checksum 校验）；要改就新建更高版本迁移。
- 密码字段存哈希；实体绝不直接返回给前端；脱敏用 DTO。

---

## 6. 高频坑速查表（症状 → 根因）

| 症状 | 根因 | 解法 |
|---|---|---|
| 全站 200 空响应、Swagger 白屏 | `doFilter` 写进了 `if` | 移到 if 外，无条件放行 |
| 403 变成 500 | `AccessDeniedException` import 错（`java.nio.file`） | 换 `org.springframework.security.access` |
| 401 测试断言失败 | 错误码写错（`COMMON_401` vs `COMMON_0401`） | 查 `CommonErrorCode` 实际值 |
| 带 token 仍 401/403 | stub 参数带了 `Bearer ` 前缀 | 过滤器已 substring(7)，stub 用纯 token |
| `hasAuthority` 永远不通过 | 权限编码拼了 `ROLE_` 前缀 | 权限原样放入 authorities |
| `@PreAuthorize` 不生效 | 忘了 `@EnableMethodSecurity` | 加上 |
| token 一签发就过期 | `expiration` 单位是毫秒，配置当成秒 | 统一单位（毫秒） |
| Maven 报“referencing itself” | application POM 的 artifactId 被改成 user 的 | 恢复 artifactId |
| Access denied 但密码明明对 | Java 进程没读到环境变量（IDEA 配置没生效） | 检查实际运行的配置 |

---

## 7. 面试速答卡（先自己复述，再看答案）

1. **一个登录请求经过哪些关键步骤？** → 过滤器链 → 认证（查密码/签发 token）→ 后续请求带 token → `JwtAuthenticationFilter` 解析 → 放 SecurityContext → 授权检查 → Controller。
2. **JWT 为什么无状态？代价？** → 服务端不存会话，token 自包含可验证，多实例友好；代价是难主动失效，需要黑名单/短有效期。
3. **退出登录怎么做？** → 前端删 token + 服务端短期黑名单（Redis），或 Refresh Token 吊销。
4. **为什么密码要加盐哈希？** → 哈希不可逆，盐防彩虹表、同密码不同哈希；BCrypt 的盐内嵌在哈希串里。
5. **RBAC 为什么需要中间表？** → 用户/角色/权限是多对多，中间表存关联，加角色改权限不动用户表。
6. **401 和 403？** → 未认证 401（EntryPoint），已认证无权限 403（过滤器层 AccessDeniedHandler / 方法层 Advice 各管一路）。
7. **hasRole 和 hasAuthority？** → 前者查 `ROLE_` 前缀，后者精确匹配；角色粗粒度、权限细粒度。
8. **登录失败限制为什么最终要 Redis？** → 内存版重启丢失、多实例不共享；Redis 共享+过期+原子。
9. **为什么“用户不存在”和“密码错误”统一？** → 防止用户名枚举。
10. **过滤器异常为什么到不了 @RestControllerAdvice？** → 过滤器在 DispatcherServlet 之前，异常不经过 MVC 异常处理器。

---

## 8. 自测清单

- [ ] 能画出“请求 → 过滤器链 → SecurityContext → Controller → 异常处理”的完整图。
- [ ] 能默写 `JwtAuthenticationFilter` 骨架，并说出三个易错点。
- [ ] 能说出 401/403 四条路径分别由谁处理。
- [ ] 能解释 `@MockitoBean` 和 `@Mock` 的区别，以及测试放哪个模块的原则。
- [ ] 能解释为什么无状态 API 关 CSRF、用 STATELESS。
- [ ] 能说出 Flyway 版本号全局连续的规则和“已执行不能改”的原因。
- [ ] 第 7 节 10 道题能脱稿回答。

完成 Session F 验收后，进入 [Part 3：知识库与文件管理](../part-03/README.md)。
