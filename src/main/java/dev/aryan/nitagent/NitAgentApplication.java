package dev.aryan.nitagent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootApplication
public class NitAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(NitAgentApplication.class, args);
    }

    @Bean
    public RestClient.Builder restClientBuilder(
            @Value("${ollama.connect-timeout-seconds:10}") int connectTimeoutSeconds,
            @Value("${ollama.timeout-seconds:30}") int readTimeoutSeconds
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutSeconds * 1000);
        factory.setReadTimeout(readTimeoutSeconds * 1000);
        return RestClient.builder().requestFactory(factory);
    }

    @Bean
    public WebMvcConfigurer asyncConfigurer(@Value("${spring.mvc.async.request-timeout:120000}") long timeoutMillis) {
        return new WebMvcConfigurer() {
            @Override
            public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                configurer.setDefaultTimeout(timeoutMillis);
            }
        };
    }
}