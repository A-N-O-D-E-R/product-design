package com.anode.productdesigner;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/** Les tests de l'API sans login : starter éteint (anode.security.enabled=false), tout est permis, sans CSRF. SecuriteTest teste le vrai. */
@Configuration
@ConditionalOnProperty(name = "anode.security.enabled", havingValue = "false")
class TestSansSecurite {

    @Bean
    SecurityFilterChain permissive(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).csrf(AbstractHttpConfigurer::disable).build();
    }
}
