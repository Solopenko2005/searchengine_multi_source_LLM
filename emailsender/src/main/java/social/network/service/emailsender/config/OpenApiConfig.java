package social.network.service.emailsender.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI emailOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Научный поиск — E-mail API")
                        .version("1.0")
                        .description("Внутренний SMTP-модуль для писем подтверждения и восстановления пароля. "
                                + "В целях безопасности API не публикуется напрямую в интернете."))
                .servers(List.of(new Server().url("http://email:8771")
                        .description("Внутренняя сеть Docker")));
    }
}
