package pl.kacper.fulfillment_worker;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.jdbc.JdbcTestUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
public class BaseIT {

    @ServiceConnection
    private static final PostgreSQLContainer postgreSQLContainer;

    @Autowired
    private JdbcTemplate jdbcTemplate;


    static {
        postgreSQLContainer = new PostgreSQLContainer("postgres:17.10");
        postgreSQLContainer.start();
    }

    @AfterEach
    public void cleanDatabase(){
        JdbcTestUtils.deleteFromTables(jdbcTemplate, "tickets","fulfillment_orders", "processed_messages");
    }

}
