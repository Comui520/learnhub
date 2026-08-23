ALTER TABLE `document_task`
    DROP INDEX `idx_document_id`,
    ADD UNIQUE KEY `uk_file_id` (`file_id`);