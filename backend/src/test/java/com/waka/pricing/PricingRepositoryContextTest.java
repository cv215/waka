package com.waka.pricing;

import com.waka.pricing.domain.PricingException;
import com.waka.pricing.infrastructure.JdbcFareScheduleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class PricingRepositoryContextTest {
    @Test
    void repositoryCanStartWithSpringExceptionTranslationProxyWithoutDatabase() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PersistenceExceptionTranslationPostProcessor.class);
            // Aucun serveur, URL, identifiant ou mot de passe : aucune connexion n'est demandee.
            context.registerBean(JdbcTemplate.class,
                    () -> new JdbcTemplate(new DriverManagerDataSource()));
            context.registerBean(JdbcFareScheduleRepository.class);
            context.refresh();

            var repository = context.getBean(JdbcFareScheduleRepository.class);
            assertTrue(AopUtils.isCglibProxy(repository));
            var error = assertThrows(PricingException.class,
                    () -> repository.findByVersion("invalid version"));
            assertEquals(PricingException.Code.UNKNOWN_FARE_VERSION, error.code());
        }
    }
}
