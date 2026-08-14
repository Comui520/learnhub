# Session B：知识库剩余 CRUD（列表 / 详情 / 修改 / 删除）

> 目标：独立完成知识库的查、改、删，并把“数据隔离”练成肌肉记忆。
>
> 档位：🤝 各写一半 + 🏃 你自己做。预计 2～3 小时。

## 0. 本 Session 的结构

Session A 已经带你把“创建”做完了。剩下的四个接口套路完全一样：Controller 取 userId → Service 校验归属 → 操作 → 返回 DTO。这次按三档推进：

| 接口 | 档位 |
|---|---|
| `GET /api/v1/knowledge-bases`（我的列表） | 🏃 你自己做 |
| `GET /api/v1/knowledge-bases/{id}`（详情） | 🤝 我给一半 |
| `PUT /api/v1/knowledge-bases/{id}`（改名/改描述） | 🏃 你自己做 |
| `DELETE /api/v1/knowledge-bases/{id}`（删除） | 🤝 我给一半 |

## 1. 数据隔离铁律（先背下来）

```text
所有按 ID 的查询，SQL 必须带 user_id：
  WHERE id = #{id} AND user_id = #{userId}
```

为什么不能只靠 Controller 判断？因为 SQL 是最后防线：万一 Controller 忘了传 userId、或者有人直接调 Service，数据库层仍然挡住。**隔离要在 SQL 层做，Controller 只是第一道门。**

还有一条：**访问别人的知识库返回 404，不返回 403**。403 等于告诉攻击者“这东西存在，只是你没权限”，404 让他无法枚举。只有“未登录”才是 401。

## 2. 🏃 你自己做：列表接口

需求：`GET /api/v1/knowledge-bases`，返回当前用户所有知识库 `ApiResponse<List<KnowledgeBaseResponse>>`，按 `created_at` 倒序。

提示：

- `KnowledgeBaseMapper extends BaseMapper<KnowledgeBase>`，用 `lambdaQuery` 或手写 SQL 都行，但**必须带 `user_id` 条件**。
- 分页现在不需要（数据量小），`selectList` + 排序即可。
- Controller 方法加 `@Operation` 和 `@ApiResponses`（200/401）。

## 3. 🤝 各写一半：详情接口

我已经写好 Service 的骨架，你来补两处：

```java
public KnowledgeBaseResponse getById(Long userId, Long id) {
    KnowledgeBase kb = knowledgeBaseMapper.findByIdAndUserId(id, userId);   // Session A 已写好的 SQL
    if (kb == null) {
        throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
    }
    return toResponse(kb);   // TODO 你补：和 create 里的转换保持一致
}
```

Controller：

```java
@GetMapping("/{id}")
public ApiResponse<KnowledgeBaseResponse> getById(@PathVariable Long id) {
    return ApiResponse.success(knowledgeBaseService.getById(currentUserId(), id));
}
```

## 4. 🏃 你自己做：修改接口

需求：`PUT /api/v1/knowledge-bases/{id}`，请求体 `UpdateKnowledgeBaseRequest(String name, String description)`，只允许改自己的知识库，名字冲突返回 409。

提示：

- 先 `findByIdAndUserId` 确认归属，不存在 → 404。
- 改名时查重：`existsByUserIdAndNameExcludingId(userId, name, id)`（排除自己），SQL：`SELECT COUNT(*) FROM knowledge_base WHERE user_id=#{userId} AND name=#{name} AND id != #{id}`。
- 更新用 `knowledgeBaseMapper.updateById(kb)`（MyBatis-Plus 只更新非 null 字段）。
- `UpdateKnowledgeBaseRequest` 的 name 校验和创建一致。

## 5. 🤝 各写一半：删除接口

先想一个问题：**删除知识库时，里面的文档怎么办？** 三个选项：

1. 禁止删除（还有文档时返回 409）。
2. 级联删除数据库记录，但 MinIO 对象留着（孤儿对象）。
3. 级联删除数据库记录 + 删除 MinIO 对象。

选项 3 最完整，但“数据库删了、MinIO 删失败”怎么办？这就是 Session A 复盘题 3 说的分布式一致性问题。**本 Session 先实现选项 1（有文档就拒绝删除）**——最简单也最安全，Session C 再升级成 3 并讲一致性方案。

Service 骨架（你补 TODO）：

```java
@Transactional
public void delete(Long userId, Long id) {
    KnowledgeBase kb = knowledgeBaseMapper.findByIdAndUserId(id, userId);
    if (kb == null) {
        throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
    }

    Long docCount = documentMapper.countByKnowledgeBaseId(id);   // TODO 你补 SQL：SELECT COUNT(*) FROM document WHERE knowledge_base_id=#{id}
    if (docCount > 0) {
        throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_HAS_DOCUMENTS);  // 加一个 409 错误码
    }

    knowledgeBaseMapper.deleteById(id);
}
```

Controller 补 `@DeleteMapping("/{id}")`，返回 `ApiResponse<Void>`（用 `ApiResponse.success()`）。

## 6. 测试要求

给 `KnowledgeBaseControllerTest`（放 application 模块测试目录，模式照抄 Part 2）写切片测试，至少覆盖：

- [ ] 创建成功 200。
- [ ] 未登录 401。
- [ ] 访问不存在的知识库 404。
- [ ] 删除有文档的知识库 409。

Service 层再补 `KnowledgeBaseServiceTest`（Mock Mapper），覆盖“访问别人的知识库 → 404”和“改名冲突 → 409”。

## 7. 参考答案

### 列表 Service

```java
public List<KnowledgeBaseResponse> listByUser(Long userId) {
    return knowledgeBaseMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeBase>()
                            .eq(KnowledgeBase::getUserId, userId)
                            .orderByDesc(KnowledgeBase::getCreatedAt))
            .stream()
            .map(this::toResponse)
            .toList();
}
```

### 修改 Service

```java
public KnowledgeBaseResponse update(Long userId, Long id, UpdateKnowledgeBaseRequest request) {
    KnowledgeBase kb = knowledgeBaseMapper.findByIdAndUserId(id, userId);
    if (kb == null) {
        throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
    }
    if (knowledgeBaseMapper.existsByUserIdAndNameExcludingId(userId, request.name(), id)) {
        throw new BusinessException(KnowledgeErrorCode.KNOWLEDGE_BASE_NAME_EXISTS);
    }
    kb.setName(request.name());
    kb.setDescription(request.description());
    knowledgeBaseMapper.updateById(kb);
    return toResponse(kb);
}
```

### Controller 的 currentUserId 复用

把 `currentUserId()` 从私有方法抽成一个 `@Component` 的 `CurrentUser`（封装 SecurityContext 取值），三个 Controller 都能用——这是 Part 2 就该做的重构，现在做正合适。

---

## 8. 复盘题

1. 为什么数据隔离要下沉到 SQL 层？只靠 Controller 判断有什么风险？
2. 访问别人的资源为什么返回 404 而不是 403？
3. 删除知识库的三种方案各自的取舍是什么？为什么先选“有文档就拒绝”？
4. `lambdaQuery` 和手写 `@Select` 你分别什么时候用？

完成并全绿后，进入 [Session C](session-c-document-lifecycle.md)：文档生命周期。

