package searchengine.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseConfigurationValidatorTest {

    @Test
    void rejectsMissingDatabasePassword() {
        DatabaseConfigurationValidator validator = validatorWith(new MockEnvironment());

        assertThrows(
                BeanInitializationException.class,
                () -> validator.postProcessBeanFactory(new DefaultListableBeanFactory())
        );
    }

    @Test
    void acceptsConfiguredDatabasePassword() {
        MockEnvironment environment = new MockEnvironment().withProperty("DB_PASSWORD", "secret");
        DatabaseConfigurationValidator validator = validatorWith(environment);

        assertDoesNotThrow(
                () -> validator.postProcessBeanFactory(new DefaultListableBeanFactory())
        );
    }

    private DatabaseConfigurationValidator validatorWith(MockEnvironment environment) {
        DatabaseConfigurationValidator validator = new DatabaseConfigurationValidator();
        validator.setEnvironment(environment);
        return validator;
    }
}
