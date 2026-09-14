package com.github.comui520.learnhub.knowledge.mq;

/**
 * 解析任务消息：解析是文件级的，只需要 fileId。
 * 知识库归属（多对多）不参与消息——检索时按“库绑定的 fileId 列表”过滤（见 Part 5）。
 */
public record DocumentParseMessage(Long fileId) {
}
