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