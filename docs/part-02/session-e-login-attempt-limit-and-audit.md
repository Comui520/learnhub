# Session E：登录失败限制与审计日志

> 目标：连续 5 次密码错误锁定账号 15 分钟；登录成败输出结构化日志；错误响应不泄漏账号是否存在。
>
> 档位：限制器和集成 🧑‍🏫 我带；测试 🏃 你自己做。
> 预计时间：2～3 小时。

## 0. 今天到底要学会什么

1. 暴力破解是怎么发生的，为什么前端限制没用。
2. 登录失败限制的两种维度（按用户名 / 按 IP）和防绕过。
3. 内存版限制器的实现，以及为什么最终要换 Redis（Part 6）。
4. 结构化日志怎么写，哪些字段绝不能进日志。
5. 审计日志表和业务日志的分工。

---

## 1. 先建立直觉：攻击者是怎么进来的

攻击者不需要知道你的密码，他可以用脚本对同一个账号不断尝试常见密码（`123456`、`password`、`admin`……），这叫**暴力破解**。登录接口如果不做限制，相当于把门敞开让他试到天荒地老。

### 为什么不能只在前端限制

前端加“输错 5 次就禁用按钮”只能拦普通用户，攻击者的脚本根本不经过你的前端，直接打 API。**限制必须做在后端。**

### 按用户名锁，还是按 IP 锁？

- 按用户名锁：防针对单个账号的爆破，但攻击者可以换用户名试（批量撞库）。
- 按 IP 锁：防单点高频，但攻击者可以用代理池换 IP。

正解是**两者都做**（比如账号 5 次失败锁 15 分钟 + 同 IP 每分钟限流），本 Session 先做按用户名，IP 维度留作练习，Redis 版在 Part 6 统一做。

### 防绕过：不能告诉攻击者“这账号不存在”

Session C 登录接口已经统一了错误消息。如果“用户不存在”和“密码错误”返回不同提示，攻击者就能用登录接口枚举出哪些用户名注册过，再针对它们爆破。所以统一成 `Invalid username or password` 是防绕过的一部分。

---

## 2. 先认识关键概念

### 2.1 内存版 vs Redis 版

内存版用 `ConcurrentHashMap` 存“用户名 → 失败次数和锁定时间”：

- 优点：零依赖、实现快。
- 缺点：应用重启数据丢失；多实例部署时每个实例各记各的，攻击者可以轮流打不同实例。

所以生产环境要换成 Redis（共享、带过期时间、原子操作）。**本 Session 先做内存版，把“为什么要 Redis”写进笔记，Part 6 实现。**

### 2.2 把“配置”变成构造参数，测试才能控制时间

如果锁定时间写死在代码里（15 分钟），测试怎么验证“锁定过期后自动解锁”？要么等 15 分钟，要么把次数和时长变成构造参数，测试传一个 50 毫秒的时长。**为了可测试性而注入配置**，是 Service 设计的常见手法。

### 2.3 结构化日志

日志不是给人念的散文，而是给检索系统（ELK、Loki）查的。结构化日志的常见形态是 `key=value`：

```text
login success username=learnhub
login failed username=learnhub
```

能查、能统计（比如统计失败次数）。**密码、token、密钥永远不进日志**——就算加密过也不行。

### 2.4 审计日志 vs 业务日志

- 业务日志：给开发排错用（`log.info`），进日志系统。
- 审计日志：给合规和安全追溯用（谁在什么时候做了什么），通常进数据库表，长期保留。

本 Session 以业务日志为主，审计表作为加分项。

---

## 3. 跟着做一遍（🧑‍🏫 我带）

### Step 1：UserErrorCode 加锁定错误

在 `UserErrorCode` 里加：

```java
ACCOUNT_LOCKED("USER_ERROR_0423", "Account is temporarily locked", 423),
```

423 是 HTTP 的 Locked 状态码，表示资源被锁；也有项目用 429（Too Many Requests）。选 423 并在面试里解释选择即可。

### Step 2：LoginAttemptService

创建文件：

```text
learnhub-user/src/main/java/com/github/comui520/learnhub/user/service/LoginAttemptService.java
```

```java
package com.github.comui520.learnhub.user.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginAttemptService {

    private static final int DEFAULT_MAX_FAILURES = 5;
    private static final Duration DEFAULT_LOCK_DURATION = Duration.ofMinutes(15);

    private final int maxFailures;
    private final Duration lockDuration;
    private final Map<String, AttemptState> attempts = new ConcurrentHashMap<>();

    public LoginAttemptService() {
        this(DEFAULT_MAX_FAILURES, DEFAULT_LOCK_DURATION);
    }

    /** 包级私有构造器：给测试用，可以传很小的时长验证过期解锁 */
    LoginAttemptService(int maxFailures, Duration lockDuration) {
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
    }

    public boolean isLocked(String username) {
        AttemptState state = attempts.get(username);
        if (state == null) {
            return false;
        }
        // 只在“锁定窗口内”返回 true；计数中（lockedUntil == null）或已过期都返回 false。
        // 绝不在这里删除任何记录——否则会把还没到 maxFailures 次的计数清掉，永远封不了
        return state.lockedUntil() != null && state.lockedUntil().isAfter(Instant.now());
    }

    public void recordFailure(String username) {
        attempts.compute(username, (key, state) -> {
            // 锁定已过期的旧状态 = 全新开始，重新计数；否则计数 +1
            boolean expired = state != null && state.lockedUntil() != null
                    && !state.lockedUntil().isAfter(Instant.now());
            int count = (state == null || expired) ? 1 : state.count() + 1;
            Instant lockedUntil = count >= maxFailures
                    ? Instant.now().plus(lockDuration)
                    : null;
            return new AttemptState(count, lockedUntil);
        });
    }

    public void reset(String username) {
        attempts.remove(username);
    }

    record AttemptState(int count, Instant lockedUntil) {
    }
}
```

逐个解释：

- `ConcurrentHashMap`：线程安全的 Map，支持并发读写。`compute` 是**原子操作**，两个请求同时失败不会把计数写丢——这是“并发扣减/计数”的入门版，Part 6 会用 Redis Lua 做升级版。
- `AttemptState` 是个 record，装“失败次数”和“锁定到期时间”。
- 达到 `maxFailures` 次就记一个 `lockedUntil`；`isLocked` 检查是否在锁定窗口内。
- 两个构造器：Spring 用无参的（默认 5 次 15 分钟），测试用带参的（可以传 50 毫秒）。

> ⚠️ 两个容易错的地方（面试能讲就是加分）：
>
> 1. `isLocked` 推荐写成**纯查询**：只在锁定窗口内返回 true，绝不在这里删除记录。否则会把“还没到 5 次”的计数也清掉（比如第 2 次失败后第 3 次尝试时计数被删，永远封不了）。如果你选择“查询时顺带清理过期锁”的写法（`lockedUntil != null && 已过期` 才删），**必须用条件删除 `attempts.remove(username, state)`**，且绝不能动 `lockedUntil == null` 的计数条目。
> 2. 过期状态的清理/重计交给 `recordFailure` 的 `compute`：遇到“锁定已过期”的旧状态就从 1 重新计数，而不是继续 `count + 1`（否则解锁后输错一次又立刻被锁）。

### 认识 `AttemptState`（它不是官方类，是我们自己定义的嵌套 record）

`AttemptState` 不是 JDK 或 Spring 提供的类，而是写在 `LoginAttemptService` **类里面**的一个 **record**（嵌套 record）。它只干一件事：把两个相关的值打包成一个不可变对象。

**record 是什么**：Java 16 正式引入的“不可变数据载体”。你写一行：

```java
record AttemptState(int count, Instant lockedUntil) { }
```

编译器自动生成：全参构造器、访问器 `count()` 和 `lockedUntil()`（注意不是 `getCount()`）、`equals`/`hashCode`/`toString`。所以你**不用手写 getter/setter**，直接：

```java
AttemptState state = new AttemptState(3, Instant.now().plus(Duration.ofMinutes(15)));
int count = state.count();                    // 访问器直接叫字段名
Instant lockedUntil = state.lockedUntil();
```

**为什么在这里用 record**：

- 它是 `ConcurrentHashMap<String, AttemptState>` 的**值**，不可变意味着并发读写时不会出现“读一半被改”的问题。
- 比用两个散落的变量或 `Map.Entry` 更清晰：失败次数和锁定时间本来就是一组数据。
- 语法上它就是你在 Part 1 用过的 `ApiResponse<T>`、`RegisterRequest` 那些 record——**只是这次嵌套在另一个类里面**，别的完全一样。

> 面试点：能说出“record 是 Java 16 的不可变数据载体，编译器自动生成构造器/访问器/equals/hashCode/toString，适合做 DTO 和值对象”就够了。如果被追问“为什么不用 class”，可以答：class 需要手写样板代码、默认可变，record 更简洁且天然不可变。

### Step 3：AuthService 集成限制器

修改 `AuthService`：注入 `LoginAttemptService`，登录逻辑加锁定检查：

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final LoginAttemptService loginAttemptService;

    public AuthService(UserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       RoleMapper roleMapper,
                       UserRoleMapper userRoleMapper,
                       LoginAttemptService loginAttemptService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.loginAttemptService = loginAttemptService;
    }

    public TokenResponse login(LoginRequest request) {
        if (loginAttemptService.isLocked(request.username())) {
            log.warn("login blocked username={}", request.username());
            throw new BusinessException(UserErrorCode.ACCOUNT_LOCKED);
        }

        User user = userMapper.selectByUsername(request.username());

        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttemptService.recordFailure(request.username());
            log.warn("login failed username={}", request.username());
            throw new BusinessException(UserErrorCode.INVALID_CREDENTIALS);
        }

        loginAttemptService.reset(request.username());

        if (user.getStatus() == null || user.getStatus() != 1) {
            log.warn("login disabled username={}", request.username());
            throw new BusinessException(UserErrorCode.ACCOUNT_DISABLED);
        }

        String token = jwtTokenProvider.createToken(user.getId(), user.getUsername());
        log.info("login success username={}", user.getUsername());
        return new TokenResponse(token, "Bearer", jwtTokenProvider.getExpirationSeconds());
    }
}
```

注意几个细节：

- **锁定检查在最前面**：锁定期内连“查密码”都不做，直接拒绝。
- 失败才 `recordFailure`，成功 `reset`。
- 日志只有用户名，**没有任何密码/token**。`log.warn("... username={}", request.username())` 这种参数化写法也是安全习惯——不要用字符串拼接把敏感字段拼进消息。

### Step 4：验证

重启应用，登录接口连续输错 5 次密码：

```powershell
curl.exe -i -X POST "http://localhost:8080/api/v1/auth/login" -H "Content-Type: application/json" -d "{\"username\":\"learnhub\",\"password\":\"wrong1\"}"
```

第 1～5 次都返回 401 `USER_ERROR_0401`，第 6 次（以及之后 15 分钟内）返回 423 `USER_ERROR_0423`。观察应用日志里出现 `login failed username=learnhub` 5 次、`login blocked` 1 次。

---

## 4. 🏃 你自己做：LoginAttemptServiceTest + 审计日志（加分）

### 任务 1：LoginAttemptServiceTest

在 `learnhub-user/src/test/.../user/service/LoginAttemptServiceTest.java` 新建测试，覆盖：

- [ ] 5 次失败后 `isLocked` 返回 true（用默认 5 次，15 分钟时长）。
- [ ] 锁定过期后自动解锁（用 `new LoginAttemptService(2, Duration.ofMillis(50))`，`Thread.sleep(100)` 后再断言）。
- [ ] `reset` 后立即解锁。

这是纯单元测试，没有 Spring，直接 new。

### 任务 2（加分）：审计日志表

先读下面的“审计日志到底有什么用”，再做：

1. 写迁移建 `audit_log` 表：id、username、action、ip、detail、created_at，username 和 created_at 建索引。**版本号取你当前最大版本 +1**（先 `SELECT MAX(version) FROM flyway_schema_history` 确认，示例里叫 V5）。
2. 写一个 `AuditLogService`，`record(username, action, ip, detail)` 插入一条记录。
3. 在 `AuthService` 的登录成功、登录失败、账号锁定三个分支调用它。

提示：IP 从哪来？Controller 用 `ServletRequestAttributes` 或 `HttpServletRequest` 拿到 `request.getRemoteAddr()`，传给 Service。**Service 不要依赖 HttpServletRequest**——那是 Web 边界的东西，保持 Service 可独立测试。

### 审计日志到底有什么用（先理解再动手）

**业务日志 vs 审计日志：给谁看**

| | 业务日志（`log.info` / `log.warn`） | 审计日志（`audit_log` 表） |
|---|---|---|
| 去哪 | 控制台 / 日志文件 | 数据库 |
| 给谁 | 开发者排错 | 安全 / 合规事后查证 |
| 生命周期 | 会滚动清理 | 长期保留 |
| 典型问题 | “哪个请求 500 了、堆栈是什么” | “谁在什么时候从哪个 IP 登录、失败了几次、被锁过没有” |

审计日志的典型用途：

- **安全追溯**：暴力破解发生时，靠它还原攻击过程（哪个账号、哪个 IP、失败多少次）。
- **合规要求**：等保、金融、GDPR 都要求关键操作有可查记录。
- **内部追责**：管理员删了数据、改了配置，能查是谁干的。
- **异常检测**：审计表里同 IP 高频失败，就是暴力破解的直接证据。

**接入代码**（`AuditLogService` + `AuthService` 三处调用）：

```java
@Service
public class AuditLogService {
    private final AuditLogMapper auditLogMapper;   // 继承 BaseMapper<AuditLog>
    // 构造器注入

    public void record(String username, String action, String ip, String detail) {
        AuditLog log = new AuditLog();
        log.setUsername(username);
        log.setAction(action);
        log.setIp(ip);
        log.setDetail(detail);
        auditLogMapper.insert(log);
    }
}
```

```java
// AuthService.login 里三个分支各加一行（ip 由 Controller 传入）：
auditLogService.record(request.username(), "LOGIN_FAILED", ip, "bad credentials");  // 失败
auditLogService.record(request.username(), "LOGIN_LOCKED", ip, null);               // 锁定
auditLogService.record(user.getUsername(), "LOGIN_SUCCESS", ip, null);              // 成功
```

**三个设计要点（面试能讲）**：

1. **只追加（append-only）**：生产上审计表通常只允许 INSERT，禁止 UPDATE/DELETE，保留“不可抵赖”的基本性质；清理走归档脚本。
2. **不记敏感信息**：记结果（成功/失败）、用户名、IP 就够，**密码、token 绝不进审计表**。
3. **可以异步**：登录是高频动作，每次都插库有开销；成熟方案是发消息队列异步写（Part 4 学完 RabbitMQ 后可以升级）。

一句话：**业务日志是“调试记忆”，审计日志是“事件账本”**。表本身没有价值，价值在于“关键事件发生时往里写”。

### 审计日志配套代码（完整版）

**① 迁移**（版本号取你当前最大版本 +1，这里示例为 V5）：

```sql
CREATE TABLE `audit_log`
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    username   VARCHAR(30)     NOT NULL COMMENT '操作人',
    action     VARCHAR(50)     NOT NULL COMMENT '动作，如 LOGIN_SUCCESS / LOGIN_FAILED / LOGIN_LOCKED',
    ip         VARCHAR(45)     NULL COMMENT '来源 IP',
    detail     VARCHAR(500)    NULL COMMENT '补充信息',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_audit_username (username),
    KEY idx_audit_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='审计日志表';
```

**② Entity**（`learnhub-user/.../entity/AuditLog.java`）：

```java
@Getter
@Setter
@TableName("`audit_log`")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String action;

    private String ip;

    private String detail;

    private LocalDateTime createdAt;
}
```

**③ Mapper**（`learnhub-user/.../mapper/AuditLogMapper.java`）——`insert` 免费，查询方法按需加：

```java
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    /** 后续做“管理员查审计”时用：按用户名查最近记录 */
    @Select("SELECT * FROM `audit_log` WHERE username = #{username} ORDER BY id DESC LIMIT #{limit}")
    List<AuditLog> findRecentByUsername(@Param("username") String username, @Param("limit") int limit);
}
```

**④ Service**（`learnhub-user/.../service/AuditLogService.java`）：

```java
@Service
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;

    public AuditLogService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    public void record(String username, String action, String ip, String detail) {
        AuditLog log = new AuditLog();
        log.setUsername(username);
        log.setAction(action);
        log.setIp(ip);
        log.setDetail(detail);
        auditLogMapper.insert(log);
    }
}
```

**⑤ AuthService 接入**：注入 `AuditLogService`，`login` 方法增加 `String ip` 参数，三个分支各写一条：

```java
public TokenResponse login(LoginRequest request, String ip) {
    if (loginAttemptService.isLocked(request.username())) {
        auditLogService.record(request.username(), "LOGIN_LOCKED", ip, null);
        log.warn("login blocked username={}", request.username());
        throw new BusinessException(UserErrorCode.ACCOUNT_LOCKED);
    }

    User user = userMapper.selectByUsername(request.username());
    if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
        loginAttemptService.recordFailure(request.username());
        auditLogService.record(request.username(), "LOGIN_FAILED", ip, "bad credentials");
        log.warn("login failed username={}", request.username());
        throw new BusinessException(UserErrorCode.INVALID_CREDENTIALS);
    }

    loginAttemptService.reset(request.username());
    if (user.getStatus() == null || user.getStatus() != 1) {
        auditLogService.record(request.username(), "LOGIN_DISABLED", ip, null);
        log.warn("login disabled username={}", request.username());
        throw new BusinessException(UserErrorCode.ACCOUNT_DISABLED);
    }

    String token = jwtTokenProvider.createToken(user.getId(), user.getUsername());
    auditLogService.record(user.getUsername(), "LOGIN_SUCCESS", ip, null);
    log.info("login success username={}", user.getUsername());
    return new TokenResponse(token, "Bearer", jwtTokenProvider.getExpirationSeconds());
}
```

**⑥ Controller 只负责传 IP**（`AuthController`）：Service 不依赖 `HttpServletRequest`，IP 从 Web 边界拿好再传进去：

```java
@PostMapping("/login")
public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                        HttpServletRequest httpRequest) {
    return ApiResponse.success(authService.login(request, httpRequest.getRemoteAddr()));
}
```

> 注意：`login` 签名变了，`AuthServiceTest` / 登录相关的测试都要同步加参数；并给 `AuthService` 加 `@Mock AuditLogService`，登录测试里可以 `verify(auditLogService).record(...)` 断言“失败时确实记了一条审计”。

**⑦ 为什么没有“写审计日志的 Controller”**：审计记录是系统内部事件产生的，不是客户端能随便写的——如果开放接口，任何人都能伪造审计。需要的是**只读查询接口**（管理员看审计），加权限控制即可：

```java
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController {
    private final AuditLogService auditLogService;
    // 构造器注入

    @GetMapping
    public ApiResponse<List<AuditLogResponse>> list(@RequestParam(required = false) String username) {
        // 查最近 N 条，返回脱敏 DTO（不含任何敏感字段，本来就只有 username/action/ip/detail）
        return ApiResponse.success(auditLogService.findRecent(username, 100));
    }
}
```

> 生产提示：`getRemoteAddr()` 在 Nginx 反代后面拿到的是代理 IP，真实客户端 IP 在 `X-Forwarded-For` 头里（要配置 Trusted Proxies 并防伪造）。Part 7 部署时再处理，现在知道有这回事即可。

### 延伸：把 `log.info` 写进文件（logback）

Spring Boot 默认用 **Logback**，默认只输出到控制台。要落成文件有两种方式。

**方式一：一行配置（最简单）**

```yaml
logging:
  file:
    name: logs/learnhub.log
```

Spring Boot 检测到 `logging.file.*` 后会自动加一个 RollingFileAppender，默认单文件 10MB 滚动。

**方式二：自定义 `logback-spring.xml`（推荐，可控）**

在 `learnhub-application/src/main/resources/` 下新建 `logback-spring.xml`（`-spring` 后缀让 Spring Boot 接管，支持 profile）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- 控制台：开发时看 -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- 文件：按天 + 大小滚动，压缩归档，保留 30 天 -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/learnhub.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>logs/learnhub.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>10MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>1GB</totalSizeCap>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

看懂三个角色：

- **Logger**：谁在记录（我们代码里的 `log`）。
- **Appender**：输出到哪（控制台 / 文件 / 网络）。
- **Encoder / Pattern**：输出格式（时间、级别、线程、类名、消息）。

滚动策略 `SizeAndTimeBasedRollingPolicy`：按天切分，同一天超过 10MB 再按 `.1`、`.2` 递增，`.gz` 压缩，最多保留 30 天、总量 1GB。`logs/` 目录记得加进 `.gitignore`。

生产环境常见的进阶：用 `logstash-logback-encoder` 输出 JSON 格式日志，方便 ELK 检索——现在知道有这回事即可，Part 7 可观测性再展开。

**再强调一次区别**：文件日志是给开发/运维排错检索的，审计表是给安全/合规查证的，两者可以并存、用途不同。

---

## 5. 参考答案

### LoginAttemptServiceTest

```java
package com.github.comui520.learnhub.user.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    @Test
    void shouldLockAfterFiveFailures() {
        LoginAttemptService service = new LoginAttemptService(5, Duration.ofMinutes(15));

        for (int i = 0; i < 5; i++) {
            service.recordFailure("alice");
        }

        assertThat(service.isLocked("alice")).isTrue();
    }

    @Test
    void shouldUnlockAfterDuration() throws InterruptedException {
        LoginAttemptService service = new LoginAttemptService(2, Duration.ofMillis(50));
        service.recordFailure("alice");
        service.recordFailure("alice");

        assertThat(service.isLocked("alice")).isTrue();

        Thread.sleep(100);

        assertThat(service.isLocked("alice")).isFalse();
    }

    @Test
    void shouldResetAfterSuccess() {
        LoginAttemptService service = new LoginAttemptService(2, Duration.ofMinutes(15));
        service.recordFailure("alice");

        service.reset("alice");

        assertThat(service.isLocked("alice")).isFalse();
    }
}
```

### 审计表（参考，版本号取你当前最大版本 +1）

```sql
CREATE TABLE `audit_log`
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    username   VARCHAR(30)     NOT NULL COMMENT '操作人',
    action     VARCHAR(50)     NOT NULL COMMENT '动作，如 LOGIN_SUCCESS / LOGIN_FAILED',
    ip         VARCHAR(45)     NULL COMMENT '来源 IP',
    detail     VARCHAR(500)    NULL COMMENT '补充信息',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_audit_username (username),
    KEY idx_audit_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='审计日志表';
```

---

## 6. 主动制造错误（每个做完恢复）

**错误 A：没接限制器**

把 `AuthService.login` 里的 `isLocked` 检查注释掉，连续输错 10 次，发现没有锁定——亲眼看到“不接就是没有”。恢复。

**错误 B：锁定后还能继续“试探”**

把 `isLocked` 检查放到 `selectByUsername` 之后，观察锁定期间密码是否还在被查询（日志里 `login failed` 仍在刷）。恢复，体会“锁定检查要放在最前面”。

**错误 C：日志打了密码**

故意加一行 `log.info("password={}", request.password())`，登录一次，看日志里出现明文密码。然后**立刻删掉**，并把这条教训写进 Bug 记录。

---

## 7. 复盘题

1. 暴力破解为什么防不住前端限制？后端限制的本质是什么？
2. 按用户名锁和按 IP 锁各防什么？各自有什么绕过方式？
3. 内存版限制器在多实例部署下有什么问题？为什么 Redis 能解决？（提示：共享 + 过期 + 原子）
4. 为什么“用户不存在”和“密码错误”必须返回同样的错误？
5. `ConcurrentHashMap.compute` 为什么是原子的？这解决了什么问题？
6. 为什么 Service 不应该依赖 `HttpServletRequest`？

## 8. 加分练习（预习 Part 6）：Redis 版

> 主路线先用内存版；如果你手痒，可以在 Session E 之后试这个。它展示了“为什么最终要用 Redis”，但**先别在正式代码里替换**——Part 6 会讲 key 设计、TTL 竞态、Lua 原子性和 Redis 挂了怎么办。

先给 `learnhub-user` 加依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

```java
@Service
public class RedisLoginAttemptService {

    private static final String KEY_PREFIX = "login:fail:";
    private static final long MAX_FAILURES = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;

    public RedisLoginAttemptService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean isLocked(String username) {
        Long count = redisTemplate.opsForValue().get(KEY_PREFIX + username);
        return count != null && count >= MAX_FAILURES;
    }

    public void recordFailure(String username) {
        String key = KEY_PREFIX + username;
        redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, LOCK_DURATION);
    }

    public void reset(String username) {
        redisTemplate.delete(KEY_PREFIX + username);
    }
}
```

对照内存版，回答三个问题：

1. 多实例部署时，这个版本为什么比内存版正确？
2. `increment` 和 `expire` 是两条命令，不是原子的——如果服务在中间崩溃会怎样？（提示：key 没有 TTL，永久锁定）Part 6 会用 Lua 修。
3. Redis 挂了，登录接口会怎样？你应该选择“拒绝服务”还是“放行”？为什么？

完成后进入 [Session F](session-f-acceptance-and-review.md)：完整验收与答辩。
