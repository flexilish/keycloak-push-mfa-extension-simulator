package de.arbeitsagentur.pushmfasim.config;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.core5.http.HttpHost;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    private final ProxyConfig proxyConfig;

    public RestTemplateConfig(ProxyConfig proxyConfig) {
        this.proxyConfig = proxyConfig;
    }

    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        if (proxyConfig.getHttpHost() != null && proxyConfig.getHttpPort() != -1) {
            HttpHost proxy = new HttpHost(proxyConfig.getHttpHost(), proxyConfig.getHttpPort());
            HttpClient httpClient = getHttpClient(proxy);
            restTemplate.setRequestFactory(getRequestFactory(httpClient));
        }
        return restTemplate;
    }

    protected CloseableHttpClient getHttpClient(HttpHost proxy) {
        return HttpClientBuilder.create()
                .setRoutePlanner(new DefaultProxyRoutePlanner(proxy))
                .build();
    }

    protected HttpComponentsClientHttpRequestFactory getRequestFactory(HttpClient httpClient) {
        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }
}
