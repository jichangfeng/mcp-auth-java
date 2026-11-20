package com.example.authserver;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 注册 DCR 兼容过滤器（{@link EmptyScopeToleranceFilter}）。
 *
 * <p>用 {@link FilterRegistrationBean} 显式挂到 {@code /oauth2/register}
 * 并置于最高优先级：它必须在 Spring Security 过滤器链之前执行，才能改写
 * 请求体让 SAS 的客户端注册端点读到不含空 {@code scope} 的 JSON。
 * 路径为 SAS 默认注册端点，若将来改过端点路径需同步修改。</p>
 */
@Configuration
class ClientRegistrationToleranceConfiguration {

	@Bean
	FilterRegistrationBean<EmptyScopeToleranceFilter> emptyScopeToleranceFilter() {
		FilterRegistrationBean<EmptyScopeToleranceFilter> registration =
				new FilterRegistrationBean<>(new EmptyScopeToleranceFilter());
		registration.addUrlPatterns("/oauth2/register");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}

}
