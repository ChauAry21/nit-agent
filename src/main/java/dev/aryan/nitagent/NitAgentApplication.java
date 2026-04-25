package dev.aryan.nitagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.*;

@SpringBootApplication
public class NitAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(NitAgentApplication.class, args);
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(0);
        return RestClient.builder().requestFactory(factory);
    }

    @Bean
    public WebMvcConfigurer asyncConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                configurer.setDefaultTimeout(-1);
            }
        };
    }
}