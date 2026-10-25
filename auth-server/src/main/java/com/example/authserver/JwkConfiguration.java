package com.example.authserver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 持久化授权服务器（AS）的 JWT 签名密钥。
 *
 * 背景：Spring Authorization Server 默认在每次启动时随机生成 RSA 密钥对
 * （仅存于内存）。AS 重启后密钥更换，此前签发的 access_token 在资源服务器
 * 验签失败（401 invalid_token），而 DCR 注册的客户端多为公开客户端
 * （无 refresh_token），只能被迫重新走完整的浏览器授权流程。
 *
 * 方案：首次启动生成 RSA 密钥对并写入 ./data/as-signing-key.json，
 * 之后每次启动优先加载。路径与 H2 数据库同目录，同样相对进程启动
 * 工作目录——请保持与 auth-server 其余启动方式一致。
 */
@Configuration
class JwkConfiguration {

	private static final Logger log = LoggerFactory.getLogger(JwkConfiguration.class);

	private static final Path KEY_FILE = Path.of("./data/as-signing-key.json");

	/**
	 * Boot 的 OAuth2AuthorizationServerJwtAutoConfiguration 中默认 JWKSource
	 * 为 @ConditionalOnMissingBean，显式声明该 bean 即可接管。
	 */
	@Bean
	JWKSource<SecurityContext> jwkSource() {
		JWKSet jwkSet = loadOrCreate();
		return (jwkSelector, securityContext) -> jwkSelector.select(jwkSet);
	}

	private static JWKSet loadOrCreate() {
		try {
			if (Files.exists(KEY_FILE)) {
				JWKSet loaded = JWKSet.load(KEY_FILE.toFile());
				log.info("已加载持久化的 AS 签名密钥: {}", KEY_FILE.toAbsolutePath());
				return loaded;
			}
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			KeyPair keyPair = generator.generateKeyPair();
			RSAKey jwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
					.privateKey((RSAPrivateKey) keyPair.getPrivate())
					.keyID(UUID.randomUUID().toString())
					.build();
			Files.createDirectories(KEY_FILE.getParent());
			Files.writeString(KEY_FILE, new JWKSet(jwk).toString());
			log.info("已生成并持久化新的 AS 签名密钥: {}", KEY_FILE.toAbsolutePath());
			return new JWKSet(jwk);
		}
		catch (Exception ex) {
			throw new IllegalStateException("AS 签名密钥加载/生成失败: " + KEY_FILE.toAbsolutePath(), ex);
		}
	}

}
