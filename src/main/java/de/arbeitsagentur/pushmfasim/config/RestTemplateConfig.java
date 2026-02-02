package de.arbeitsagentur.pushmfasim.config;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.core5.http.HttpHost;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig implements RestTemplateCustomizer {

    private final ProxyConfig proxyConfig;

    public RestTemplateConfig(ProxyConfig proxyConfig) {
        this.proxyConfig = proxyConfig;
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.customizers(this).build();
    }

    @Override
    public void customize(RestTemplate restTemplate) {
        if (proxyConfig.getHttpHost() != null && proxyConfig.getHttpPort() != -1) {
            HttpHost proxy = new HttpHost(proxyConfig.getHttpHost(), proxyConfig.getHttpPort());
            HttpClient httpClient = HttpClientBuilder.create()
                    .setRoutePlanner(new DefaultProxyRoutePlanner(proxy))
                    .build();
            restTemplate.setRequestFactory(new HttpComponentsClientHttpRequestFactory(httpClient));
        }
    }
}
