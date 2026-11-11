package org.example;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A throwaway Oracle database for tests that load the full application context. {@code @ServiceConnection} points
 * {@code spring.datasource.*} at the container, so tests never use the local preferences-oracle database or its
 * password. Same image as {@code scripts/oracle-db.sh}. Needs Docker.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final DockerImageName ORACLE_IMAGE = DockerImageName.parse("gvenzl/oracle-free:23.26.3-faststart");

    @Bean
    @ServiceConnection
    OracleContainer oracleContainer() {
        // The same database and login names as the local container (scripts/oracle-db.sh).
        return new OracleContainer(ORACLE_IMAGE).withDatabaseName("preferences").withUsername("preferences");
    }
}
