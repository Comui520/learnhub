# Part 2 前置教学：Mockito 与 Service 测试

> 场景：Session B 的 `AuthServiceTest` 是你第一次写“带依赖的 Service”的单元测试。这篇把 Mockito 讲明白：那些注解分别是谁的、干什么的，以及真实开发里 Service 到底怎么测。

## 0. 先解决最容易混的命名问题

| 你看到的写法                                | 属于谁                   | 是干嘛的                                                                                  |
| ------------------------------------- | --------------------- | ------------------------------------------------------------------------------------- |
| `Mockito`                             | 一个库（框架）               | 提供“造假对象”的能力，和 JUnit、Spring 是并列的库                                                      |
| `@Mock`                               | Mockito 提供的注解         | 写在字段上，造一个假对象：`@Mock UserMapper userMapper;`                                           |
| `@InjectMocks`                        | Mockito 提供的注解         | 写在被测对象上，把上面的假对象自动塞进它的构造器                                  |
| `@ExtendWith(MockitoExtension.class)` | JUnit 5 + Mockito 的桥梁 | 让 `@Mock` / `@InjectMocks` 在测试里生效，并开启严格校验                                             |
| `@MockitoBean`                        | Spring Boot           | 把假对象**放进 Spring 容器**，替换真实 Bean（`@WebMvcTest` 切片测试用，week-01 的 `DemoControllerTest` 用过） |
| `@MockBean`                           | Spring Boot 旧版        | **已废弃**（Spring Boot 3.4 起），功能和 `@MockitoBean` 相同，只是包名/名字更老，看到就换成 `@MockitoBean` |

一句话：**Mockito 是库，`@Mock` 是注解，注解加在“你要假的哪个依赖”字段上。没有 `@Mockito` 这个注解。**

再补一句明确的：**`@Mock` 和 `@MockitoBean` 都没有废弃，都是当前推荐写法**。废弃的只有老版 `@MockBean`（名字里没有 “ito” 的那个）。

`@Mock` 和 `@MockitoBean` 的区别值得单独记：

- `@Mock`：纯手动造假对象，**不经过 Spring**。适合纯单元测试（我们的 `AuthServiceTest`）。
- `@MockitoBean`：把假对象**注册进 Spring 容器**替换真实 Bean。适合切片测试（`@WebMvcTest`）。

---

## 1. 为什么 week-01 没用到，现在需要

week-01 的 `GreetingServiceTest` 是直接 `new GreetingService()`——因为 `GreetingService` **一个依赖都没有**，自己就能跑。

现在的 `AuthService` 构造器要 `UserMapper`、`PasswordEncoder`（之后还有更多）。测试它时，问题变成：**这些依赖怎么办？** 三条路：

| 方案     | 依赖怎么处理          | 测试类型 | 特点                        |
| ------ | --------------- | ---- | ------------------------- |
| Mock 掉 | 全部用 `@Mock` 假对象 | 单元测试 | 快、稳定、只测 AuthService 自己的逻辑 |
| 用真的    | 真连 MySQL、真加密    | 集成测试 | 慢、要环境，验证真实装配和 SQL         |
| 抽纯逻辑   | 把不依赖外部的规则抽成独立方法 | 单元测试 | 最便宜，但不是每个逻辑都能抽            |

`AuthService` 的核心逻辑（查重 → 加密 → 入库 → 返回脱敏）和 Mapper/Encoder 的调用缠在一起，所以单测选第一条路：mock 掉依赖，专注验证编排逻辑。

---

## 2. 一个带注释的完整 AuthServiceTest

对照 Session B 的答案，逐行解释：

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

@ExtendWith(MockitoExtension.class)      // ① JUnit5 开启 Mockito：下面的 @Mock/@InjectMocks 才会被处理
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;        // ② 假的 UserMapper：不连数据库，行为全靠下面 given 安排

    @Mock
    private PasswordEncoder passwordEncoder; // ③ 假的 PasswordEncoder（升级练习里我们会改成真的）

    @InjectMocks
    private AuthService authService;      // ④ 真的 AuthService，Mockito 把上面的假对象自动注入构造器

    @Test
    void shouldEncodePasswordAndInsertUserWhenUsernameIsFree() {
        // ⑤ 安排假行为：查重返回 null，表示“用户名没被占用”
        given(userMapper.selectByUsername("learnhub")).willReturn(null);
        // ⑥ 安排假行为：encode 返回一个假哈希（模拟 BCrypt 的结果）
        given(passwordEncoder.encode("password123")).willReturn("$2a$10$hashed-value");

        // ⑦ 调用被测方法（这是真的代码，不是假的）
        UserResponse response = authService.register(new RegisterRequest("learnhub", "password123"));

        // ⑧ 断言返回结果
        assertThat(response.username()).isEqualTo("learnhub");
        // ⑨ 事后检查：insert 确实被调用过一次（参数可以是任何 User）
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void shouldThrowWhenUsernameAlreadyExists() {
        // 查重返回一个非 null 的 User，模拟“用户名已存在”
        given(userMapper.selectByUsername("learnhub")).willReturn(new User());

        // 断言抛业务异常，消息和错误码定义一致
        assertThatThrownBy(() -> authService.register(new RegisterRequest("learnhub", "password123")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(UserErrorCode.USERNAME_ALREADY_EXISTS.message());
    }
}
```

三种“动作”区分清楚：

| 写法                                              | 类型  | 含义                       |
| ----------------------------------------------- | --- | ------------------------ |
| `given(...).willReturn(...)` / `willThrow(...)` | 安排  | 预先设定假对象“遇到这个输入时返回什么/抛什么” |
| `assertThat(...)`                               | 断言  | 检查被测方法**返回了什么**          |
| `verify(...)`                                   | 检查  | 事后确认“某次交互确实发生了”          |

**`given` 和 `verify` 不是一回事**：`given` 是“假装它会这样”，`verify` 是“确认它真的这样了”。

---

## 3. 真实开发怎么测 Service

主流是**两条腿走路**：

1. **单元测试用 mock 测边界**。Service 依赖的 Mapper、外部 API、消息队列都属于“边界”，单测里 mock 掉，专注验证 Service 自己的编排逻辑。Java/Spring 世界里，Service 层单测用 Mockito 是默认操作。

2. **集成测试用真依赖验证装配**。Mock 有盲区：它测不到“SQL 写没写对”“事务回滚没回滚”“MyBatis 映射对不对”。这些要由少量集成测试（真 MySQL / Testcontainers）覆盖——你的计划里 Testcontainers 在后面，就是为了这个。

两条反模式，面试能讲出来是加分项：

- **不要 mock 你不拥有的东西**。`UserMapper` 是你写的，mock 它合理；但框架/库的实现（比如 `BCryptPasswordEncoder`）能真用就真用。
- **避免过度 mock**。如果测试全是 `verify(某某方法被调用)`，你其实在测 mock 而不是测代码。断言要有意义，比如“入库的 User 的 passwordHash 是加密后的值”。

---

## 4. 升级练习：PasswordEncoder 不用 mock

`BCryptPasswordEncoder` 是纯类，不碰数据库、不碰网络，所以**可以真用**。真用之后，测试能做出更有意义的断言——用 `ArgumentCaptor` 把 `insert` 收到的 User 捞出来，验证哈希真的能匹配明文：

```java
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Test
void shouldStorePasswordAsVerifiableHash() {
    given(userMapper.selectByUsername("learnhub")).willReturn(null);

    // PasswordEncoder 用真的，不 mock
    AuthService authService = new AuthService(userMapper, new BCryptPasswordEncoder());

    authService.register(new RegisterRequest("learnhub", "password123"));

    // 捕获 insert 收到的 User
    ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
    verify(userMapper).insert(captor.capture());
    User saved = captor.getValue();

    // 有意义的断言：存进去的哈希，用明文能验证通过
    assertThat(new BCryptPasswordEncoder().matches("password123", saved.getPasswordHash())).isTrue();
}
```

`ArgumentCaptor` 是 Mockito 的“参数捕获器”：`verify(...)` 配合 `captor.capture()`，能把方法收到的实参拿出来检查。

注意：`AuthService` 现在只有两个依赖（Session B 阶段），所以直接 `new AuthService(userMapper, new BCryptPasswordEncoder())`。以后构造器参数变多，改成 `@InjectMocks` + 在 `@BeforeEach` 里手动替换就行——那是 Session C/D 的事。

---

## 5. 自测题

- [ ] `Mockito`、`@Mock`、`@MockitoBean` 分别是什么？谁是谁的？
- [ ] `@ExtendWith(MockitoExtension.class)` 是干什么的？不写会怎样？
- [ ] `@InjectMocks` 是怎么把假对象送进被测类的？
- [ ] `given` 和 `verify` 的区别？
- [ ] `ArgumentCaptor` 解决什么问题？
- [ ] 为什么单元测试 mock Mapper 是合理的，而 mock BCryptPasswordEncoder 是浪费？
- [ ] mock 测不到什么？什么测试能补上？

都能答上来，就可以放心写 Session B 的 `AuthServiceTest` 了。
