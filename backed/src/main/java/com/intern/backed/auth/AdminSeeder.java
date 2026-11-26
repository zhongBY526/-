package com.intern.backed.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AdminSeeder implements CommandLineRunner {
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AdminSeeder(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // 种子账号仅用于学习/演示环境，生产部署前应删除或改为强制首登改密
    @Override
    public void run(String... args) {
        if (!userRepository.existsByUsername("admin")) {
            userRepository.insert("admin", passwordEncoder.encode("admin123"), "ADMIN", "管理部");
            log.info("已创建种子管理员账号 admin/admin123");
        }
        if (!userRepository.existsByUsername("demo")) {
            userRepository.insert("demo", passwordEncoder.encode("demo123"), "USER", "技术部");
            log.info("已创建种子演示账号 demo/demo123（技术部）");
        }
    }
}