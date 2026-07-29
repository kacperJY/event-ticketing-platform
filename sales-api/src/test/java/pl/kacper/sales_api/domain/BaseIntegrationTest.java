package pl.kacper.sales_api.domain;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.jdbc.JdbcTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
public class BaseIntegrationTest {

    @ServiceConnection
    private static PostgreSQLContainer postgreSQLContainer;

    @ServiceConnection
    private static RabbitMQContainer rabbitMQContainer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected MockMvc mockMvc;

    static {
        postgreSQLContainer = new PostgreSQLContainer("postgres:latest");
        postgreSQLContainer.start();
        rabbitMQContainer = new RabbitMQContainer("rabbitmq:4.3.2-management");
        rabbitMQContainer.start();
    }

    @AfterEach
    void cleanDatabase() {
        JdbcTestUtils.deleteFromTables(jdbcTemplate, "public.tickets", "public.seats", "public.orders", "public.users", "public.events", "public.processed_messages","public.outbox_messages");
    }
}
