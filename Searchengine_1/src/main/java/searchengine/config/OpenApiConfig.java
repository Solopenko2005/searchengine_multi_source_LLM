package searchengine.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Product-level metadata and authentication hints for Swagger UI. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI searchEngineOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Научный поиск — Search API")
                        .version("1.0")
                        .description("Поиск, индексация источников, рабочие группы, научный каталог "
                                + "и LLM-ассистент. Изменяющие операции требуют роль ADMIN и CSRF-токен.")
                        .contact(new Contact()
                                .name("Солопенко Виктория Владимировна")
                                .email("solopenko2005@yandex.ru"))
                        .license(new License().name("Proprietary")))
                .components(new Components()
                        .addSecuritySchemes("sessionCookie", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("JSESSIONID")
                                .description("Сессия создаётся после входа в приложение."))
                        .addSecuritySchemes("basicAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")
                                .description("Логин и пароль зарегистрированного пользователя."))
                        .addSecuritySchemes("csrfToken", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-XSRF-TOKEN")
                                .description("Нужен для POST, PUT и DELETE при работе через сессию.")))
                .addSecurityItem(new SecurityRequirement().addList("sessionCookie"));
    }
}
