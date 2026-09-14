CREATE TABLE `study_question`
(
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id           BIGINT UNSIGNED NOT NULL COMMENT '题目所属用户',
    knowledge_base_id BIGINT UNSIGNED NOT NULL COMMENT '题目来源知识库',
    question_type     VARCHAR(30)     NOT NULL DEFAULT 'SINGLE_CHOICE',
    content           VARCHAR(1000)   NOT NULL COMMENT '题干',
    analysis          VARCHAR(2000)   NULL COMMENT '解析',
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_study_question_user (user_id),
    KEY idx_study_question_kb (knowledge_base_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='学习题目';

CREATE TABLE `study_question_option`
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    question_id BIGINT UNSIGNED NOT NULL,
    option_key  VARCHAR(10)     NOT NULL COMMENT 'A/B/C/D',
    content     VARCHAR(500)    NOT NULL,
    is_correct  TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_question_option (question_id, option_key),
    KEY idx_option_question (question_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='题目选项';

CREATE TABLE `study_attempt`
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT UNSIGNED NOT NULL,
    question_id BIGINT UNSIGNED NOT NULL,
    answer      VARCHAR(10)     NOT NULL,
    correct     TINYINT         NOT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_attempt_user_question (user_id, question_id),
    KEY idx_attempt_created_at (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='答题历史';

CREATE TABLE `study_wrong_question`
(
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id        BIGINT UNSIGNED NOT NULL,
    question_id    BIGINT UNSIGNED NOT NULL,
    wrong_count    INT             NOT NULL DEFAULT 1,
    next_review_at DATETIME        NULL,
    mastered       TINYINT         NOT NULL DEFAULT 0,
    updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wrong_user_question (user_id, question_id),
    KEY idx_wrong_review (user_id, mastered, next_review_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='错题状态';