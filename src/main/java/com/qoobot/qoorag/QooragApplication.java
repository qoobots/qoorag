package com.qoobot.qoorag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** qoorag 企业级 RAG 知识库平台（MVP 骨架入口） */
@SpringBootApplication
public class QooragApplication {

    public static void main(String[] args) {
        SpringApplication.run(QooragApplication.class, args);
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
