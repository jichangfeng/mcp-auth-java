package com.example.mcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 受 OAuth2 保护的 Spring AI MCP Server（对应 python-sdk simple-auth 中的
 * mcp-simple-auth-rs）。端口 8001，Streamable HTTP 传输。
 */
@SpringBootApplication
public class McpServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(McpServerApplication.class, args);
	}

}
