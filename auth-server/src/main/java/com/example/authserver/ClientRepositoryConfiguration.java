package com.example.authserver;

import java.time.Duration;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/**
 * DCR 客户端持久化：用 H2 文件库代替内存存储。
 *
 * 背景：Qoder 等客户端注册一次后会缓存 client_id 反复使用，
 * 内存版 InMemoryRegisteredClientRepository 在 auth-server 重启后
 * 丢失已注册客户端，导致客户端拿着失效的 client_id 授权时报
 * Whitelabel Error Page（/oauth2/authorize 未知客户端异常）。
 *
 * starter 的默认 RegisteredClientRepository 是
 * {@code @ConditionalOnMissingBean}，这里显式声明后自动接管；
 * default-client 改为在此处注册（yml 里的 client 配置不再生效）。
 */
@Configuration
class ClientRepositoryConfiguration {

	/**
	 * 动态注册（DCR）客户端的令牌策略：
	 * SAS 默认 access_token 仅 5 分钟且不签发 refresh_token，
	 * 会导致客户端频繁重新授权，这里统一规范化。
	 */
	private static final Duration DCR_ACCESS_TOKEN_TTL = Duration.ofHours(8);

	private static final Duration DCR_REFRESH_TOKEN_TTL = Duration.ofDays(30);

	@Bean
	RegisteredClientRepository registeredClientRepository(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
		JdbcRegisteredClientRepository repository = new JdbcRegisteredClientRepository(jdbcTemplate);

		RegisteredClient defaultClient = RegisteredClient.withId(UUID.randomUUID().toString())
				.clientName("default-client")
				.clientId("default-client")
				.clientSecret("{noop}default-secret")
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
				.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				// 仅用于本地调试的授权码回调地址：MCP Inspector（浏览器端）的固定回调端口
				.redirectUri("http://localhost:6274/oauth/callback")
				.scope("openid")
				.scope("profile")
				.tokenSettings(TokenSettings.builder()
						.accessTokenTimeToLive(Duration.ofHours(1))
						.build())
				.clientSettings(ClientSettings.builder().build())
				.build();

		if (repository.findByClientId("default-client") == null) {
			repository.save(defaultClient);
		}

		return new RegisteredClientRepository() {

			@Override
			public void save(RegisteredClient client) {
				repository.save(normalizeDcrClient(client));
			}

			@Override
			public RegisteredClient findById(String id) {
				return repository.findById(id);
			}

			@Override
			public RegisteredClient findByClientId(String clientId) {
				return repository.findByClientId(clientId);
			}
		};
	}

	/**
	 * DCR 客户端规范化：access_token 8h、refresh_token 30d，
	 * 授权码客户端补充 REFRESH_TOKEN 授权类型以支持静默续期。
	 */
	private static RegisteredClient normalizeDcrClient(RegisteredClient client) {
		RegisteredClient.Builder builder = RegisteredClient.from(client)
				.tokenSettings(TokenSettings.builder()
						.accessTokenTimeToLive(DCR_ACCESS_TOKEN_TTL)
						.refreshTokenTimeToLive(DCR_REFRESH_TOKEN_TTL)
						.reuseRefreshTokens(true)
						.build());
		if (client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.AUTHORIZATION_CODE)
				&& !client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
			builder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
		}
		return builder.build();
	}

}
