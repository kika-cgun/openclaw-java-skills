package com.piotrcapecki.openclaw.core.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Arrays;

@Configuration
public class FlywayConfig {

    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
    }

    @Bean
    public static BeanFactoryPostProcessor flywayJpaDependency() {
        return beanFactory -> {
            try {
                BeanDefinition jpa = beanFactory.getBeanDefinition("entityManagerFactory");
                String[] existing = jpa.getDependsOn();
                if (existing == null) {
                    jpa.setDependsOn("flyway");
                } else {
                    String[] updated = Arrays.copyOf(existing, existing.length + 1);
                    updated[existing.length] = "flyway";
                    jpa.setDependsOn(updated);
                }
            } catch (BeansException ignored) {
                // JPA not present
            }
        };
    }
}
