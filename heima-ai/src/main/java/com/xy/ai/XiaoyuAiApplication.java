package com.xy.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.xy.ai.mapper")
@SpringBootApplication
public class XiaoyuAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(XiaoyuAiApplication.class, args);
    }

}
