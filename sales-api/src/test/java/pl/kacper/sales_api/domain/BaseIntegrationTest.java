package pl.kacper.sales_api.domain;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.jdbc.JdbcTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@ActiveProfiles("test")
@SpringBootTest()
@Testcontainers
public class BaseIntegrationTest {

    @Container
    @ServiceConnection
    private static PostgreSQLContainer postgreSQLContainer;

    @Container
    @ServiceConnection
    private static RabbitMQContainer rabbitMQContainer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    static {
        postgreSQLContainer = new PostgreSQLContainer("postgres:latest");
        rabbitMQContainer = new RabbitMQContainer("rabbitmq:4.3.2-management");
    }

    @AfterEach
    void cleanDatabase() {
        JdbcTestUtils.deleteFromTables(jdbcTemplate, "public.tickets", "public.seats", "public.orders", "public.users", "public.events");
    }
}
