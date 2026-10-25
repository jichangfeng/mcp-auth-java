package com.example.mcpserver;

import java.time.LocalDateTime;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * 演示用 MCP 工具集。
 */
@Service
public class DemoTools {

	/**
	 * 演示从 Spring Security 上下文取当前用户（即 JWT 中的 sub / client-id）。
	 */
	@McpTool(name = "greet", description = "向当前已认证用户问好")
	public String greet() {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		String name = (authentication != null) ? authentication.getName() : "anonymous";
		return "Hello, %s!".formatted(name);
	}

	@McpTool(name = "add", description = "计算两个整数的和")
	public int add(@ToolParam(description = "第一个整数") int a,
			@ToolParam(description = "第二个整数") int b) {
		return a + b;
	}

	@McpTool(name = "current-time", description = "获取服务器当前时间")
	public LocalDateTime currentTime() {
		return LocalDateTime.now();
	}

}
