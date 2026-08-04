package searchengine.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Stops startup before Liquibase/Hikari emit a misleading connection stack
 * trace when local database credentials have not been configured.
 */
@Component
public class DatabaseConfigurationValidator
        implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        String password = environment.getProperty("DB_PASSWORD");
        if (!StringUtils.hasText(password)) {
            throw new BeanInitializationException(
                    "DB_PASSWORD is required. Copy .env.example to .env and set the real PostgreSQL password."
            );
        }
    }
}
