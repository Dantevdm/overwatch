package com.overwatch.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS for the dashboard's dev server, and the OpenAPI description. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String allowedOrigins;

    public WebConfig(@Value("${overwatch.api.cors-allowed-origins:http://localhost:5173}")
                     String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // In Docker the UI is proxied through Vite and same-origin, so this only
        // matters when running the dashboard against the API directly.
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    @Bean
    public OpenAPI overwatchOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Overwatch Fraud API")
                .version("1.0.0")
                .description("""
                        Read API over the fraud data store, plus the two write paths that
                        matter: analyst disposition of alerts, and rule configuration.

                        Rules are data. Changing a threshold or moving a rule into shadow
                        is a call to this API, applied by the engine on its next refresh —
                        not a redeploy."""));
    }
}
