# Part 2 前置教学：Spring Security 零基础入门

> 写给完全没用过 Spring Security 的你。这篇不讲怎么写完整个登录系统，只讲清楚一件事：**Spring Security 到底是什么、它凭什么拦截请求、你写的那几行配置分别是什么意思**。
>
> 建议在 Session B 的 Step 8（SecurityConfig）之前读完，之后 Session C/D 还会用到这里的概念。

## 0. 你需要先知道的背景

你已经会：Spring Boot 启动一个 Web 应用，浏览器/curl 发 HTTP 请求，Controller 接收并返回 JSON。

现在补两个背景知识：

1. 一个 HTTP 请求到达你的 Controller 之前，会先经过 **Tomcat（内嵌服务器）** 里一串 **过滤器（Filter）**。
2. Spring Security 就是靠“往这串过滤器里插入自己的过滤器”来拦截请求的——它**不是**魔法，是插队。

记住这句话，后面所有内容都是它的展开。

---

## 1. Security 解决两个问题：认证 + 授权

用小区门禁类比：

- **认证（Authentication）**：你是谁？进大门要刷卡，证明“你是业主”。
- **授权（Authorization）**：你能进哪栋楼？业主能进小区，但地下车库可能要单独权限。

对应到系统：

- 认证：登录成功，证明“我是 learnhub”。
- 授权：你是普通用户还是管理员，能调哪些接口。

两个词别混：**先认证，后授权**。认证失败返回 401，认证成功但没权限返回 403（后面细讲）。

---

## 2. 它怎么“突然出现”的：starter 依赖 + 自动配置

你在 Session A 亲历过：`learnhub-user` 一被 application 依赖，`DemoControllerTest` 就挂了，接口也访问不了了。为什么？

```text
pom 里加了 spring-boot-starter-security
  -> classpath 上出现 Spring Security 的类
  -> Spring Boot 自动配置（AutoConfiguration）检测到它们
  -> 自动创建一条“默认过滤器链”：所有请求都要认证
```

**自动配置**是 Spring Boot 的核心机制：依赖在不在 classpath 上，决定哪些配置自动生效。你什么都没写，Security 就已经在工作了——这既是好事（开箱即用）也是惊吓（“我没配过啊”）。

你之后写的 `SecurityConfig`，本质是**替换默认规则**，告诉它“哪些放行、哪些要认证、用什么方式认证”。

---

## 3. 过滤器链：请求的完整旅行路线

把 Tomcat 里发生的事展开：

```text
浏览器 / curl 请求
  |
  v
Tomcat（内嵌服务器）收到请求
  |
  v
[过滤器1] -> [过滤器2] -> ... -> [Spring Security 的过滤器链] -> ... -> [最后一个过滤器]
  |                                                              |
  +--------------------------------------------------------------+
  v
DispatcherServlet（Spring MVC 的入口，你 Part 1 学过）
  |
  v
你的 Controller 方法
```

Spring Security 不是“一个过滤器”，而是一串：它用 `FilterChainProxy` 把自己的一堆过滤器（登录过滤器、认证过滤器、异常处理过滤器……）打包成一串，插进 Servlet 过滤器链里。

这串里有一个位置很关键：`UsernamePasswordAuthenticationFilter`（用户名密码登录过滤器）。我们 Session C 的 `JwtAuthenticationFilter` 就是用 `addFilterBefore(...)` 插在它**前面**。

### 过滤器链的三大作用

1. **认证**：检查“这个请求带没带身份凭证”，没带就交给 `AuthenticationEntryPoint` 返回 401。
2. **授权**：已认证的请求，检查“你有没有权限访问这个地址/方法”，没有就 403。
3. **放行**：通过检查后，把请求继续往下传，最终到达 Controller。

> 关键理解：**Security 的工作发生在你的 Controller 之前**。所以过滤器里抛的异常到不了 `@RestControllerAdvice`——那是给 Controller 层的异常准备的。这就是为什么 401/403 的 JSON 要专门处理（Session C/D 会写）。

---

## 4. 核心概念逐个讲

### 4.1 Authentication：认证结果的“名片”

认证成功后，Security 会造一个 `Authentication` 对象，里面装着：

| 字段 | 含义 | 类比 |
|---|---|---|
| `principal` | 主体，是谁 | 你的身份证 |
| `credentials` | 凭证，通常登录后清空 | 你手里的钥匙（验证完就不该留着） |
| `authorities` | 权限列表 | 你能进哪些门 |
| `authenticated` | 是否已认证 | 保安有没有放行 |

Session C 里我们这样“发名片”：

```java
new UsernamePasswordAuthenticationToken(userId, null, authorities)
```

三个参数的构造器意味着 `authenticated = true`（已认证）。第一个参数 `userId` 就是 `principal`，之后 Controller 用 `authentication.getPrincipal()` 拿回 userId。

### 4.2 SecurityContext 与 SecurityContextHolder：名片放哪

`Authentication` 不会乱飘，它被放进 `SecurityContext`，而 `SecurityContext` 放在 `SecurityContextHolder` 里。

```text
SecurityContextHolder（一个“储物柜”，按线程分格）
  -> SecurityContext（这一格的抽屉）
    -> Authentication（抽屉里的名片）
      -> principal / authorities
```

实现上是 **ThreadLocal**：每个请求一个线程，请求开始时是空的，过滤器放进名片，Controller 里能取到，请求结束自动清空。所以：

- 过滤器里：`SecurityContextHolder.getContext().setAuthentication(authentication)` —— 放名片。
- Controller 里：`SecurityContextHolder.getContext().getAuthentication()` —— 取名片。

### 4.3 授权：authorities 与 ROLE_ 前缀

`authorities` 是权限标识的集合，每条就是一个字符串：

```text
ROLE_ADMIN
ROLE_USER
user:list        （这种叫权限编码，更细粒度）
```

带 `ROLE_` 前缀的叫“角色”。`hasRole('ADMIN')` 实际检查的是 `ROLE_ADMIN`；`hasAuthority('user:list')` 精确匹配。Session D 会用到。

### 4.4 PasswordEncoder：密码不是明文

`PasswordEncoder` 是 Security 定义的一个接口：

- `encode(明文)`：加密，得到存储值。
- `matches(明文, 存储值)`：验证。

为什么必须自己声明 Bean？因为 Spring Boot **不会替你选**用哪种加密算法（BCrypt、Argon2、PBKDF2……这是安全决策，不该由框架默认）。所以我们写：

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

BCrypt 是哈希（不可逆）+ 自带随机盐 + 故意慢。你 Session A 在数据库里看到的 `$2a$10$...` 就是它的产物，盐藏在哈希串里。

### 4.5 HttpSecurity DSL：逐行读懂你的 SecurityConfig

这是你 Session B Step 8 会写出来的完整配置，我逐行翻译：

```java
@Configuration                                          // 这是一个配置类，Spring 会处理里面的 @Bean
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {          // 声明密码编码器 Bean，Service 注入它
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // ① 关闭 CSRF 防护
                .csrf(AbstractHttpConfigurer::disable)
                // ② 不创建 Session，保持无状态
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // ③ 路径规则：白名单放行，其余必须认证
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/**",     // 注册、登录接口：匿名可用
                                "/actuator/**",        // 健康检查
                                "/swagger-ui.html",    // Swagger 页面
                                "/swagger-ui/**",      // Swagger 资源
                                "/v3/api-docs/**"      // OpenAPI JSON
                        ).permitAll()                  // 这些路径不需要登录
                        .anyRequest().authenticated()) // 除了上面，其他都要登录
                // ④ 未认证时怎么响应（Session C 换成我们自己的 JSON 401）
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(restAuthenticationEntryPoint))
                // ⑤ 把 JWT 过滤器插到用户名密码过滤器之前（Session C 加）
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();                           // 把规则“编译”成真正的过滤器链
    }
}
```

逐条解释：

**① `.csrf(AbstractHttpConfigurer::disable)`** —— 注释里那句“我们做无状态 JWT API，不需要 CSRF”什么意思？

CSRF（跨站请求伪造）防的是：浏览器会自动带上你的 Cookie，攻击者诱导你访问一个恶意页面，那个页面偷偷向你的网站发请求，Cookie 一戴，服务器以为是你。防法是要求请求里带一个随机 token。

但我们不用 Cookie 存登录态，token 放在 `Authorization` 头里，**浏览器不会自动带**，所以 CSRF 的主要攻击路径没了。无状态 API 关闭 CSRF 是常规操作。反过来想：如果你将来用 Cookie 存 JWT，CSRF 又要回来。

**② `SessionCreationPolicy.STATELESS`** —— 让 Security **不创建、不读取 HttpSession**。服务端不存“谁登录了”，每个请求都是独立的，全靠请求里带的 token 证明身份。好处：多实例部署时不用共享会话存储，随便加机器。这就是“无状态”的含义。

**③ `authorizeHttpRequests`** —— 规则从上往下匹配：先列出的白名单 `permitAll()`（放行），最后 `anyRequest().authenticated()`（兜底：剩下全部要认证）。注意顺序：`anyRequest` 必须放最后。

**④ `exceptionHandling`** —— 定义“认证失败时怎么办”。默认是跳登录页/弹 Basic 认证框，我们的 API 要返回 JSON，所以 Session C 换成了自己写的 `RestAuthenticationEntryPoint`。

**⑤ `addFilterBefore`** —— 把我们的 JWT 过滤器插入过滤器链的指定位置。等 Session C 有真实代码后再回来看这句，会豁然开朗。

### 4.6 401 与 403：两种“失败”

```text
没登录/凭证无效      -> 认证失败 -> 401 Unauthorized
已登录但没权限       -> 授权失败 -> 403 Forbidden
```

对应两个处理点：

- 401：`AuthenticationEntryPoint`（过滤器链里，认证失败时调用）。
- 403：两条路——过滤器链层面的 `AccessDeniedHandler`；以及方法级 `@PreAuthorize` 抛出的 `AccessDeniedException`，它发生在 Controller 调用阶段，由 `@RestControllerAdvice` 兜住（Session D 我们会两个都写）。

### 4.7 @EnableMethodSecurity 与 @PreAuthorize

```java
@Configuration
@EnableMethodSecurity   // 打开“方法级安全”开关
public class SecurityConfig { ... }
```

不写这行，`@PreAuthorize` 注解就是普通注释，不生效。加上后：

```java
@PreAuthorize("hasRole('ADMIN')")
public List<UserResponse> listUsers() { ... }
```

每次调用这个方法前，Security 用 SpEL 表达式检查当前认证信息有没有 `ROLE_ADMIN`，没有就抛 `AccessDeniedException`。

### 4.8 OncePerRequestFilter：JWT 过滤器的“血统”

Session C 的 `JwtAuthenticationFilter` 会继承 `OncePerRequestFilter`。它的名字说明一切：**每个请求只执行一次**。

我们让它做三件事：

```java
1. 从 Header 里取 token（Authorization: Bearer xxx）
2. 解析 token -> 造 Authentication -> 放进 SecurityContextHolder（发名片）
3. 解析失败 -> 清空上下文，不抛异常（让后面的逻辑判定为未认证 -> 401）
```

注意第 3 点：**不抛异常**。如果抛了，会变成 500；正确做法是当作“没带名片”，走 401 流程。

---

## 5. 名词速查表

| 名词 | 人话 | 本项目对应代码 |
|---|---|---|
| 认证 Authentication | 证明你是谁 | 登录接口、JWT 解析 |
| 授权 Authorization | 你能干什么 | `@PreAuthorize`、路径规则 |
| SecurityFilterChain | 一串安全过滤器的配置 | `SecurityConfig.securityFilterChain()` |
| FilterChainProxy | 装这串过滤器的总入口 | Spring 内部，不用写 |
| OncePerRequestFilter | 每个请求只跑一次的过滤器 | `JwtAuthenticationFilter`（Session C） |
| SecurityContextHolder | 存当前请求身份的储物柜 | 过滤器放、Controller 取 |
| Authentication | 名片：principal/authorities/是否认证 | `UsernamePasswordAuthenticationToken` |
| principal | 名片上的“谁” | 我们放 userId |
| authorities / ROLE_ | 权限标识 | `ROLE_USER`、`ROLE_ADMIN` |
| PasswordEncoder | 密码哈希接口 | `BCryptPasswordEncoder` Bean |
| AuthenticationEntryPoint | 认证失败的出口（401） | `RestAuthenticationEntryPoint`（Session C） |
| AccessDeniedHandler | 过滤器层授权失败的出口（403） | `RestAccessDeniedHandler`（Session D） |
| @EnableMethodSecurity | 方法级安全开关 | SecurityConfig 类上 |
| @PreAuthorize | 方法调用前检查权限 | 管理员接口（Session D） |
| CSRF | 借 Cookie 伪造请求的攻击 | 无状态 API 关闭 |
| STATELESS | 不用 HttpSession，无登录状态 | `SessionCreationPolicy.STATELESS` |

---

## 6. 常见疑问 FAQ

**Q1：我什么都没配，为什么日志里出现 “Using generated security password”？**

Spring Boot 检测到 Security 在 classpath 上，但没有自定义的 `UserDetailsService`，就临时造了一个内存用户（用户名 `user`，密码随机打印在日志里）。**它只影响默认的登录方式，我们不用它**，等我们完全用自己的 JWT 认证后它就名存实亡。无害，不用管。

**Q2：为什么 @WebMvcTest 里 Security 也生效，把测试搞挂了？**

切片测试会加载部分自动配置，Security 自动配置在其中。Session A 用 `addFilters = false` 让 MockMvc 不执行过滤器，是“切片测试只测 Controller”的取舍，不是绕过安全（安全行为有专门测试）。

**Q3：SecurityConfig 放在 learnhub-user 模块，application 怎么找到的？**

`@SpringBootApplication` 会扫描 `com.github.comui520.learnhub` 包及其子包，`...learnhub.user.config.SecurityConfig` 在这个范围内，且 `application` 依赖 `user` 模块（类在 classpath 上），所以能被发现并生效。

**Q4：过滤器里抛异常为什么到不了 @RestControllerAdvice？**

`@RestControllerAdvice` 处理的是 DispatcherServlet 处理请求过程中抛出的异常。过滤器在 DispatcherServlet **之前**运行，异常直接冒到 Tomcat 层，不会经过 Controller 的异常处理器。所以 401 要在 `AuthenticationEntryPoint` 里自己写 JSON。

**Q5：permitAll 是不是“完全没有安全”？**

不是。`permitAll()` 只是“这个路径不用登录就能访问”，Security 过滤器链照样会跑（比如 JWT 过滤器仍会执行）。Session A 的 `anyRequest().permitAll()` 才是“所有请求都放行”的临时状态。

**Q6：为什么 `anyRequest` 必须写在最后？**

规则从上到下匹配，命中就停。`anyRequest` 是“剩下的全部”，写在前面等于后面的规则永远不执行。

---

## 7. 动手观察（强烈建议做）

理解 Security 最快的方式是“看它跑”。

**观察 1：看过滤器链**

在 `application-dev.yml` 加一行：

```yaml
logging:
  level:
    org.springframework.security: DEBUG
```

启动后随便发一个请求，日志里会出现类似：

```text
Security filter chain: [
  ...,
  JwtAuthenticationFilter,
  UsernamePasswordAuthenticationFilter,
  ...,
  AuthorizationFilter
]
```

亲眼看到自己的过滤器在链条里，比任何解释都管用。观察完可以删掉这行（DEBUG 日志很吵）。

**观察 2：改规则看效果**

把 `anyRequest().authenticated()` 临时改成 `anyRequest().permitAll()`，未登录访问 `/api/v1/users/me`（Session C 后有）——会从 401 变成 200。改回来，体会“认证”到底拦在哪。

**观察 3：断点看 SecurityContext**

在 Controller 方法第一行打断点，调试运行，看 `SecurityContextHolder.getContext().getAuthentication()`：`authenticated=true`、`principal=userId`、`authorities=[...]`。这就是“名片”长什么样。

---

## 8. 自测：能回答这些就算入门

- [ ] Security 是怎么“插入”到请求处理流程里的？
- [ ] 认证和授权的区别？各自对应哪个状态码？
- [ ] `SecurityContextHolder` 存的是什么？什么时候放、什么时候取、什么时候清？
- [ ] `permitAll()` 和 `authenticated()` 分别什么意思？
- [ ] 为什么无状态 JWT API 要关 CSRF、用 STATELESS？
- [ ] 为什么 `PasswordEncoder` 必须自己声明 Bean？
- [ ] 401 由谁返回？403 有哪两条路径？
- [ ] 过滤器里的异常为什么到不了 `@RestControllerAdvice`？

都能答上来，就可以回 [Session B](session-b-register-and-password-security.md) 继续了。Session C/D 看到不懂的，随时回来翻这篇。

