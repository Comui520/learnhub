package com.github.comui520.learnhub.study.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 题目列表中的选项。
 *
 * <p>题目列表使用 MyBatis nested resultMap 聚合选项，映射过程需要通过
 * 无参构造器和 setter 回填，因此这里不能使用 record。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StudyOptionResponse {

    private String optionKey;

    private String content;
}
