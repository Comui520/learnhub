# 前置教学：MyBatis-Plus 高级用法与 XML Mapper

> 前面我们一直用 `@Select` 手写简单 SQL。这篇补齐 MyBatis-Plus 的“官方姿势”：IService、LambdaQueryWrapper、分页插件，以及什么时候该上 XML Mapper。Part 3 的分页、Part 4 的任务查询都会用到。

## 1. 先建立直觉：什么时候用什么

| 场景 | 用什么 | 为什么 |
|---|---|---|
| 单表简单 CRUD（按 id 查、删、改） | `BaseMapper` / `IService` 自带方法 | 不用写 SQL |
| 单表条件查询（按用户名查、按状态过滤、排序） | `LambdaQueryWrapper` | 类型安全、可读、不用写 SQL |
| 分页 | 分页插件 + `Page<T>` | 一行分页，不用手写 LIMIT |
| 多表 JOIN、动态条件（条件可选、列表项不定） | **XML Mapper** | SQL 复杂时注解里写不下，动态 SQL 是 XML 的强项 |
| 简单但重要的 SQL（按 id+userId 查） | `@Select` 保持现状 | 一眼看懂、可控 |

原则：**能白嫖就白嫖（自带方法），单表条件用 Wrapper，复杂查询才上 XML**。不要为了“高级”而高级。

---

## 2. IService / ServiceImpl（你已经用过一半）

Part 2 你让 `AuditLogService extends ServiceImpl<AuditLogMapper, AuditLog>` 时其实已经用了它。项目里我们**不写“接口 + 实现类”**，直接一个 `@Service` class 继承 `ServiceImpl<Mapper, T>`：

```java
@Service
public class DocumentService extends ServiceImpl<DocumentMapper, Document> {
    // 继承来的方法：save / saveBatch / getById / list / page / removeById / lambdaQuery ...
    // 自定义业务方法直接写在这里
    public DocumentResponse upload(...) { ... }
}
```

常用继承方法：

```java
save(entity);                          // 新增
getById(id);                           // 按主键查
list(wrapper);                         // 按条件查列表
removeById(id);                        // 按主键删
page(page, wrapper);                   // 分页
lambdaQuery().eq(...).list();          // 条件查询
count(wrapper);                        // 计数
```

> 注意：Controller 直接注入这个 `@Service` class（Spring 按类型装配）。不需要再为每个 Service 写接口——接口在“一个实现可能换多个（Mock、多实现）或跨模块暴露契约”时才值得；业务规则在手写方法里更清晰，直接 class 够用且少一层样板代码。全项目保持一致。

---

## 3. LambdaQueryWrapper：单表条件查询

对比三种写法：

```java
// ① 手写 @Select（我们现在的方式）
@Select("SELECT * FROM document WHERE knowledge_base_id = #{kbId} AND status = #{status} ORDER BY id DESC")
List<Document> findByKbIdAndStatus(...);

// ② QueryWrapper（字符串列名，不推荐：拼错不报编译错）
QueryWrapper<Document> qw = new QueryWrapper<>();
qw.eq("knowledge_base_id", kbId).eq("status", status).orderByDesc("id");

// ③ LambdaQueryWrapper（方法引用，推荐）
LambdaQueryWrapper<Document> lqw = new LambdaQueryWrapper<>();
lqw.eq(Document::getKnowledgeBaseId, kbId)
   .eq(Document::getStatus, status)
   .orderByDesc(Document::getId);
List<Document> list = documentMapper.selectList(lqw);
```

常用条件：

```java
.eq(实体::getField, value)      // =
.ne(...)                        // !=
.like(实体::getName, "java")    // LIKE '%java%'
.between(实体::getCreatedAt, start, end)
.in(实体::getStatus, List.of("A", "B"))
.gt/.lt/.ge/.le                 // > < >= <=
.orderByDesc(实体::getCreatedAt)
.last("LIMIT 10")               // 慎用，追加 SQL
```

动态条件经典写法（条件为 null 就忽略）：

```java
String keyword = request.getKeyword();
String status = request.getStatus();

LambdaQueryWrapper<Document> lqw = new LambdaQueryWrapper<>();
lqw.like(StringUtils.hasText(keyword), Document::getFileName, keyword)   // 第一个参数为 false 就跳过
   .eq(StringUtils.hasText(status), Document::getStatus, status)
   .eq(Document::getUserId, userId);                                     // 数据隔离条件永远在
```

---

## 4. 分页：MybatisPlusInterceptor

**第一步：注册分页插件**（一个配置类）：

```java
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 3.5.9+ 必须传 DbType，否则启动/查询报 "DbType must be set"
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
```

**第二步：查询时传 Page**：

```java
Page<Document> page = new Page<>(pageNum, pageSize);          // 页码从 1 开始
LambdaQueryWrapper<Document> lqw = new LambdaQueryWrapper<>();
lqw.eq(Document::getUserId, userId).orderByDesc(Document::getId);

Page<Document> result = documentMapper.selectPage(page, lqw);

result.getRecords();     // 当前页数据
result.getTotal();       // 总条数
result.getPages();       // 总页数
result.getCurrent();     // 当前页码
```

分页插件会**自动改写 SQL** 追加 `LIMIT`，并自动执行一次 `COUNT(*)` 查总数——你什么都不用写。

---

## 5. XML Mapper：复杂查询的正解

### 5.1 什么时候必须用 XML

- 多表 JOIN（比如“文档 + 知识库名”）。
- **动态 SQL**：条件可选的搜索、批量 `IN`、`<if>` 组合。
- SQL 很长，注解里写不下、看不清。

### 5.2 XML 和接口怎么关联

两种方式：

1. **同包同名自动关联**：接口 `com/.../mapper/DocumentTaskMapper.java` 对应 XML `com/.../mapper/DocumentTaskMapper.xml`，XML 的 `namespace` 写成接口全限定名，MyBatis 解析接口时会自动找同名 XML。
2. **显式配置**（多目录时用）：

```yaml
mybatis-plus:
  mapper-locations: classpath*:mapper/**/*.xml
```

推荐方式 1（就近原则，文件跟着接口走）。

### 5.3 一个完整例子：任务列表 + 文档名 JOIN + 动态条件

接口方法：

```java
@Mapper
public interface DocumentTaskMapper {
    List<TaskWithDocument> searchTasks(@Param("userId") Long userId,
                                       @Param("status") String status,
                                       @Param("limit") int limit);
}
```

XML（`src/main/resources/com/github/comui520/learnhub/knowledge/mapper/DocumentTaskMapper.xml`）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.github.comui520.learnhub.knowledge.mapper.DocumentTaskMapper">

    <resultMap id="TaskWithDocumentMap" type="com.github.comui520.learnhub.knowledge.dto.TaskWithDocument">
        <id property="id" column="id"/>
        <result property="documentId" column="document_id"/>
        <result property="fileName" column="file_name"/>
        <result property="status" column="status"/>
        <result property="retryCount" column="retry_count"/>
        <result property="lastError" column="last_error"/>
    </resultMap>

    <select id="searchTasks" resultMap="TaskWithDocumentMap">
        SELECT t.id, t.document_id, d.file_name, t.status, t.retry_count, t.last_error
        FROM `document_task` t
                 JOIN `document` d ON t.document_id = d.id
        WHERE d.user_id = #{userId}
        <if test="status != null and status != ''">
            AND t.status = #{status}
        </if>
        ORDER BY t.id DESC
        LIMIT #{limit}
    </select>
</mapper>
```

规则：

- `namespace` 必须等于接口**全限定名**。
- `<select id>` 必须等于接口**方法名**。
- 参数用 `#{xxx}`，对应 `@Param("xxx")`。
- `<if test="...">` 是动态 SQL，条件不满足就不拼这段——这就是“搜索条件可选”的正解。
- 返回多条 → `List<T>`；字段映射用 `resultMap`（列名和属性名对不上时）。

---

## 6. 小练习

1. 给“文档列表”加一个可选搜索：`keyword`（文件名模糊）+ `status`（可选），用 LambdaQueryWrapper 实现，并**永远带上 userId 条件**。
2. 给任务列表加分页：注册分页插件，接口返回 `Page<TaskWithDocument>`。
3. 把第 5.3 的 XML 例子抄进你的项目（先用假的 `TaskWithDocument` DTO），跑通后把 `LIMIT` 换成 `<foreach>` 批量查询试试（提示：`WHERE t.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>`）。

## 7. 自测题

- [ ] `QueryWrapper` 和 `LambdaQueryWrapper` 区别？为什么推荐后者？
- [ ] 分页插件要注册什么？3.5.9+ 为什么必须传 `DbType`？
- [ ] XML 和接口怎么关联？`namespace` 和 `<select id>` 分别对什么？
- [ ] `<if test="...">` 解决什么问题？
- [ ] 什么时候该用 XML，什么时候用 Wrapper，什么时候用 `@Select`？
