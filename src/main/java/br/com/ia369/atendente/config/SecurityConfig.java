package br.com.ia369.atendente.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AtendenteProperties properties) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/webhooks/telnyx/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/actuator/info").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(new BearerTokenFilter(properties.apiAuthToken()), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(eh -> eh.authenticationEntryPoint((req, res, ex) ->
                        res.sendError(HttpStatus.UNAUTHORIZED.value(), "Unauthorized")
                ))
                .build();
    }

    static class BearerTokenFilter extends OncePerRequestFilter {

        private final String expectedToken;

        BearerTokenFilter(String expectedToken) {
            this.expectedToken = expectedToken;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain
        ) throws ServletException, IOException {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith("Bearer ") && expectedToken != null && !expectedToken.isBlank()) {
                String provided = header.substring("Bearer ".length()).trim();
                if (MessageDigest.isEqual(
                        provided.getBytes(StandardCharsets.UTF_8),
                        expectedToken.getBytes(StandardCharsets.UTF_8))) {
                    var auth = new UsernamePasswordAuthenticationToken(
                            "api-client",
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_API"))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
            filterChain.doFilter(request, response);
        }
    }
}

