package de.arbeitsagentur.pushmfasim.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

class RestTemplateConfigTest {

    @Spy
    HttpComponentsClientHttpRequestFactory[] httpComponentsClientHttpRequestFactory;

    @Test
    void shouldConfigureProxyWhenHostAndPortAreSet() {
        // given
        ProxyConfig proxyConfig = Mockito.mock(ProxyConfig.class);
        Mockito.when(proxyConfig.getHttpHost()).thenReturn("proxy.example.com");
        Mockito.when(proxyConfig.getHttpPort()).thenReturn(8080);

        RestTemplateConfig config = spy(new RestTemplateConfig(proxyConfig) {

            @Override
            protected HttpComponentsClientHttpRequestFactory getRequestFactory(HttpClient httpClient) {

                httpComponentsClientHttpRequestFactory = new HttpComponentsClientHttpRequestFactory[] {
                    spy(new HttpComponentsClientHttpRequestFactory(httpClient))
                };
                return httpComponentsClientHttpRequestFactory[0];
            }

            @Override
            protected CloseableHttpClient getHttpClient(HttpHost proxy) {
                assertEquals(proxy.getPort(), proxyConfig.getHttpPort());
                assertEquals(proxy.getHostName(), proxyConfig.getHttpHost());

                return HttpClientBuilder.create()
                        .setRoutePlanner(new DefaultProxyRoutePlanner(proxy))
                        .build();
            }
        });

        // when
        config.restTemplate();

        // then
        verify(config).getHttpClient(any());
        assertNotNull(httpComponentsClientHttpRequestFactory[0]);
    }

    @Test
    void shouldNotConfigureProxyWhenHostIsNull() {
        // given
        ProxyConfig proxyConfig = Mockito.mock(ProxyConfig.class);
        Mockito.when(proxyConfig.getHttpHost()).thenReturn(null);
        Mockito.when(proxyConfig.getHttpPort()).thenReturn(8080);

        RestTemplateConfig config = spy(new RestTemplateConfig(proxyConfig) {

            @Override
            protected HttpComponentsClientHttpRequestFactory getRequestFactory(HttpClient httpClient) {
                httpComponentsClientHttpRequestFactory = new HttpComponentsClientHttpRequestFactory[] {
                    spy(new HttpComponentsClientHttpRequestFactory(httpClient))
                };
                return httpComponentsClientHttpRequestFactory[0];
            }
        });

        // when
        RestTemplate restTemplate = spy(config.restTemplate());

        // then
        verify(config, never()).getHttpClient(any());
        verify(restTemplate, never()).setRequestFactory(any());
        assertNull(httpComponentsClientHttpRequestFactory);
    }

    @Test
    void shouldNotConfigureProxyWhenPortIsMinusOne() {
        // given
        ProxyConfig proxyConfig = Mockito.mock(ProxyConfig.class);
        Mockito.when(proxyConfig.getHttpHost()).thenReturn("proxy.example.com");
        Mockito.when(proxyConfig.getHttpPort()).thenReturn(-1);

        RestTemplateConfig config = spy(new RestTemplateConfig(proxyConfig) {

            @Override
            protected HttpComponentsClientHttpRequestFactory getRequestFactory(HttpClient httpClient) {
                httpComponentsClientHttpRequestFactory = new HttpComponentsClientHttpRequestFactory[] {
                    spy(new HttpComponentsClientHttpRequestFactory(httpClient))
                };
                return httpComponentsClientHttpRequestFactory[0];
            }
        });

        // when
        RestTemplate restTemplate = spy(config.restTemplate());

        // then
        verify(config, never()).getHttpClient(any());
        verify(restTemplate, never()).setRequestFactory(any());
        assertNull(httpComponentsClientHttpRequestFactory);
    }
}
