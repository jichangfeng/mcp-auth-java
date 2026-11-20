package com.example.authserver;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 容忍动态客户端注册（DCR，{@code POST /oauth2/register}）请求里空的 {@code scope}
 * 字段：Spring Authorization Server 会严格校验并报
 * {@code scope cannot be empty}，而 MCP Inspector 等客户端会发送
 * {@code "scope": ""}。
 *
 * <p>按 RFC 7591，{@code scope} 是可选字段——合法写法是"不传"或"传非空值"，
 * 空字符串确实不合规，但让授权服务器对本公司的大量 MCP 客户端宽容一些更实用：
 * 本过滤器在请求进入 Spring Security 之前把空 {@code scope} 字段删除，
 * 等价于客户端没有传该字段。</p>
 *
 * <p>只处理以 JSON 提交的请求且仅在解析成功时改写；解析失败按原样放行，
 * 让 SAS 自己报标准错误。剔除动作打 INFO 日志——这类请求此前是静默拒绝
 * （语义校验失败不走认证失败链路，默认不记日志），排障时很难发现。</p>
 */
class EmptyScopeToleranceFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(EmptyScopeToleranceFilter.class);

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!"POST".equalsIgnoreCase(request.getMethod())) {
			chain.doFilter(request, response);
			return;
		}
		byte[] body = request.getInputStream().readAllBytes();
		byte[] rewritten = stripEmptyScope(body);
		chain.doFilter(new CachedBodyRequest(request, (rewritten != null) ? rewritten : body), response);
	}

	/**
	 * 删除空的 {@code scope} 字段；不需要改写时返回 null（调用方用原 body）。
	 */
	private static byte[] stripEmptyScope(byte[] body) {
		if (body.length == 0) {
			return null;
		}
		JsonNode root;
		try {
			root = MAPPER.readTree(body);
		}
		catch (IOException ex) {
			// 非 JSON（或格式错误）：交给下游按标准错误处理
			return null;
		}
		if (!(root instanceof ObjectNode node)) {
			return null;
		}
		JsonNode scope = node.get("scope");
		if (scope == null || !scope.isTextual() || !scope.asText().isBlank()) {
			return null;
		}
		node.remove("scope");
		try {
			byte[] rewritten = MAPPER.writeValueAsBytes(node);
			log.info("DCR 请求携带空的 scope 字段，已忽略以兼容客户端: client_name={} redirect_uris={}",
					node.path("client_name").asText("-"), node.path("redirect_uris"));
			return rewritten;
		}
		catch (IOException ex) {
			return null;
		}
	}

	/**
	 * 请求包装：body 已被读取并可能改写，下游再次读取时从缓存字节流返回。
	 */
	private static final class CachedBodyRequest extends HttpServletRequestWrapper {

		private final byte[] body;

		CachedBodyRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public ServletInputStream getInputStream() {
			ByteArrayInputStream input = new ByteArrayInputStream(this.body);
			return new ServletInputStream() {
				@Override
				public boolean isFinished() {
					return input.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener readListener) {
					throw new UnsupportedOperationException();
				}

				@Override
				public int read() {
					return input.read();
				}
			};
		}

		@Override
		public BufferedReader getReader() {
			return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
		}

		@Override
		public int getContentLength() {
			return this.body.length;
		}

		@Override
		public long getContentLengthLong() {
			return this.body.length;
		}

	}

}
