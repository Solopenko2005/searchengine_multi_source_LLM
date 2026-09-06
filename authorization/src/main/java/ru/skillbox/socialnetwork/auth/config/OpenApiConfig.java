package ru.skillbox.socialnetwork.auth.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI authorizationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Научный поиск — Authorization API")
                        .version("1.0")
                        .description("Регистрация, вход, обновление JWT, подтверждение администратора "
                                + "и восстановление доступа."))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }
}
