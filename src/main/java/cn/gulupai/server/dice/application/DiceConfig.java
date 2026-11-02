package cn.gulupai.server.dice.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;

@Configuration
public class DiceConfig {
    @Bean
    RandomSource secureRandomSource() {
        return new SecureRandom()::nextInt;
    }
}
