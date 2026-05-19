package com.mingjin.school_wechat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SchoolWechatApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchoolWechatApplication.class, args);
    }

}
