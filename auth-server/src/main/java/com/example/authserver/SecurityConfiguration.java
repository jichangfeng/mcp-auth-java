package com.example.authserver;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 允许 MCP Inspector（运行在浏览器里）访问授权服务器：
 * 关闭 CSRF 并放开 CORS。
 *
 * 仅用于本地调试，生产环境不要这样配置。
 */
@Configuration
@EnableWebSecurity
class SecurityConfiguration {

	@Bean
	Customizer<HttpSecurity> mcpInspectorCustomizations() {
		return http -> {
			http.csrf(CsrfConfigurer::disable);
			http.cors(cors -> cors.configurationSource(corsConfigurationSource()));
		};
	}

	static CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOriginPatterns(List.of("*"));
		configuration.setAllowedMethods(List.of("*"));
		configuration.setAllowedHeaders(List.of("*"));
		configuration.setAllowCredentials(true);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

}
