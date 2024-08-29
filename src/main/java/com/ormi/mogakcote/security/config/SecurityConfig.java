package com.ormi.mogakcote.security.config;

import com.ormi.mogakcote.security.jwt.JwtFilter;
import com.ormi.mogakcote.security.jwt.JwtFilterImpl;
import com.ormi.mogakcote.security.service.JwtService;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutHandler;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final Environment env;
    private final LogoutHandler logoutHandler;

    public SecurityConfig(Environment env, LogoutHandler logoutHandler) {
        this.env = env;
        this.logoutHandler = logoutHandler;
    }

    @Bean
    public JwtFilter jwtFilter(JwtService jwtService) {
        return new JwtFilterImpl(jwtService);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtFilter jwtFilter) throws Exception {
        http.csrf(cnf -> cnf.ignoringRequestMatchers("/api/**"));

        http.logout(cnf -> {
            cnf.logoutUrl("/api/v1/auth/logout");
            cnf.permitAll();
            cnf.addLogoutHandler(logoutHandler);
            cnf.logoutSuccessUrl("/");
        });

        http.authorizeHttpRequests(auth -> {
            auth.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll();

            // 메인
            auth.requestMatchers(HttpMethod.GET, "/api/*/post/**").permitAll();

            // 관리자
            auth.requestMatchers("/api/*/admin", "/api/*/admin/**").hasRole("Admin");

            // 공지사항
            auth.requestMatchers(HttpMethod.GET, "/api/*/notice/*").permitAll();
            auth.requestMatchers("/api/*/notice/*").hasRole("Admin");

            // 엘런 문제 분석
            auth.requestMatchers("/api/*/reports/**").hasRole("User");

            // 알림
            auth.requestMatchers(HttpMethod.GET, "/api/*/news/*").hasRole("User");
            auth.requestMatchers("/api/*/news", "/api/*/news/*").hasRole("Admin");

            // 댓글
            auth.requestMatchers(HttpMethod.GET, "/api/*/posts/*/comments/**").permitAll();
            auth.requestMatchers("/api/*/posts/*/comments", "/api/*/posts/*/comments/**").hasRole("User");

            // 게시글
            auth.requestMatchers(HttpMethod.GET, "/api/*/post/*").permitAll();
            auth.requestMatchers("/api/*/post", "/api/*/post/*").hasRole("User");

            // 회원가입
            auth.requestMatchers("/api/*/signup/**", "/api/*/users/**").permitAll();

            // 인증/권한
            auth.requestMatchers("/api/*/auth/**").anonymous();

            // 마이페이지
            auth.requestMatchers("/api/*/users", "/api/*/users/**").hasRole("User");
        });

        http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        String secretKey = env.getProperty("spring.security.user.password.secretKey");
        return new Pbkdf2PasswordEncoder(
            secretKey,
            16,
            310000,
            Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
    }

    @Bean
    public RoleHierarchy roleHierarchy() {
        RoleHierarchyImpl roleHierarchy = new RoleHierarchyImpl();
        roleHierarchy.setHierarchy("ROLE_Admin > ROLE_User");
        return roleHierarchy;
    }
}