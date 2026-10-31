package com.dujiteng.ragentlearning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 唯一启动入口：扫描共同根包下各模块的 Spring 组件。 */
@SpringBootApplication
public class RagentLearningApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagentLearningApplication.class, args);
    }
}
