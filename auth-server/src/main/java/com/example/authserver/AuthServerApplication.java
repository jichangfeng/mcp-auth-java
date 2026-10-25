package com.example.authserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * OAuth2 授权服务器（对应 python-sdk simple-auth 中的 mcp-simple-auth-as）。
 * 端口 9000，签发 JWT 访问令牌，供 mcp-server 校验。
 */
@SpringBootApplication
public class AuthServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuthServerApplication.class, args);
	}

}
