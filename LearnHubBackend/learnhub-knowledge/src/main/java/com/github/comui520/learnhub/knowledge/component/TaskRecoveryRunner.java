package com.github.comui520.learnhub.knowledge.component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.comui520.learnhub.knowledge.DocumentTaskStatus;
import com.github.comui520.learnhub.knowledge.config.rabbitmq.DocumentParseRabbitMqConfig;
import com.github.comui520.learnhub.knowledge.entity.DocumentFile;
import com.github.comui520.learnhub.knowledge.entity.DocumentTask;
import com.github.comui520.learnhub.knowledge.mapper.DocumentFileMapper;
import com.github.comui520.learnhub.knowledge.mapper.DocumentTaskMapper;
import com.github.comui520.learnhub.knowledge.mq.DocumentParseMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class TaskRecoveryRunner implements ApplicationRunner {

    private final DocumentTaskMapper documentTaskMapper;
    private final DocumentFileMapper documentFileMapper;
    private final RabbitTemplate rabbitTemplate;

    public TaskRecoveryRunner(DocumentTaskMapper documentTaskMapper,
                              DocumentFileMapper documentFileMapper,
                              RabbitTemplate rabbitTemplate
    ) {
        this.documentTaskMapper = documentTaskMapper;
        this.documentFileMapper = documentFileMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<DocumentTask> pending = documentTaskMapper.selectList(
                new LambdaQueryWrapper<DocumentTask>()
                        .in(DocumentTask::getStatus, DocumentTaskStatus.PENDING.getStatus(), DocumentTaskStatus.RUNNING.getStatus())
        );

        for (DocumentTask task : pending) {
            DocumentFile file = documentFileMapper.selectById(task.getFileId());

            if (file == null) {
                log.warn("recovery: file not found, skip task id={}", task.getId());
                continue;
            }

            try {
                rabbitTemplate.convertAndSend(
                        DocumentParseRabbitMqConfig.EXCHANGE,
                        DocumentParseRabbitMqConfig.ROUTING_KEY,
                        new DocumentParseMessage(file.getId())
                );
                log.info("recovery: re-sent parse message for fileId={}", file.getId());
            } catch (Exception e) {
                log.error("recovery: re-send failed for fileId={}", file.getId(), e);
            }
        }
    }
}
