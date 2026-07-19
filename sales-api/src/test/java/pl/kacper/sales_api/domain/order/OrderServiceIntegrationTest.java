package pl.kacper.sales_api.domain.order;


import org.assertj.core.api.AbstractIntegerAssert;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import pl.kacper.sales_api.common.exception.NoSuchQuantityException;
import pl.kacper.sales_api.domain.BaseIntegrationTest;
import pl.kacper.sales_api.domain.order.dto.OrderRequestDto;
import pl.kacper.sales_api.domain.order.dto.TicketRequestDto;
import pl.kacper.sales_api.domain.seat.SeatRepository;
import pl.kacper.sales_api.domain.seat.SeatStatus;
import pl.kacper.sales_api.domain.user.UserEntity;
import pl.kacper.sales_api.domain.user.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class OrderServiceIntegrationTest extends BaseIntegrationTest {

    private final OrderService orderService;
    private final UserRepository userRepository;
    @Autowired
    private final OrderRepository orderRepository;
    private final SeatRepository seatRepository;

    @Autowired
    public OrderServiceIntegrationTest(OrderService orderService, UserRepository userRepository, OrderRepository orderRepository, SeatRepository seatRepository) {
        super();
        this.orderService = orderService;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.seatRepository = seatRepository;
    }

    @Test
    @Sql(scripts = {
            "classpath:scripts/sql/init_event.sql"
    })
    void shouldCreateOnlyOneOrderWhenConcurrentOrdersExceedAvailableSeats() {

        UserEntity userEntity1 = new UserEntity("test1@gmail.com", "Test123", "FirstnameTest", "LastnameTest");
        UserEntity userEntity2 = new UserEntity("test2@gmail.com", "Test123", "FirstnameTest", "LastnameTest");
        userRepository.save(userEntity1);
        userRepository.save(userEntity2);

        CountDownLatch startCountDownLatch = new CountDownLatch(2);

        OrderRequestDto orderRequestDto = new OrderRequestDto(List.of(new TicketRequestDto(1L, 2)));


        try (var executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> result1 = createOrderAsync(startCountDownLatch, orderRequestDto, userEntity1, executorService);
            CompletableFuture<Void> result2 = createOrderAsync(startCountDownLatch, orderRequestDto, userEntity2, executorService);

            int failedOperations = 0;

            if (!checkIfSuccessfullyCreatedOrder(result1)) failedOperations++;
            if (!checkIfSuccessfullyCreatedOrder(result2)) failedOperations++;

            Assertions.assertThat(failedOperations).isEqualTo(1);

            long orderCreatedCounter = orderRepository.count();
            int reservedSeats = seatRepository.countByEvent_EventIdAndSeatStatus(1L, SeatStatus.LOCKED_FOR_CHECKOUT);
            int availableSeats = seatRepository.countByEvent_EventIdAndSeatStatus(1L, SeatStatus.AVAILABLE);

            Assertions.assertThat(orderCreatedCounter).isEqualTo(1);
            Assertions.assertThat(reservedSeats).isEqualTo(2);
            Assertions.assertThat(availableSeats).isEqualTo(1).withFailMessage(() -> "Invalid number of available seats");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Assertions.fail("Test Main Thread has been interrupted");
        }
    }

    private boolean checkIfSuccessfullyCreatedOrder(CompletableFuture<Void> result) throws InterruptedException {
        try {
            result.get();
            return true;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            Assertions.assertThat(cause).isInstanceOf(NoSuchQuantityException.class);
            return false;
        }
    }

    private CompletableFuture<Void> createOrderAsync(CountDownLatch startCountDownLatch, OrderRequestDto orderRequestDto, UserEntity userEntity, Executor executorService) {
        return CompletableFuture.runAsync(() -> {
            try {
                startCountDownLatch.countDown();
                boolean await = startCountDownLatch.await(3L, TimeUnit.SECONDS);

                if (!await)
                    throw new TimeoutException("Waiting for %s - out of time".formatted(Thread.currentThread().getName()));

                orderService.createOrder(orderRequestDto, userEntity);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Assertions.fail("Test Order Thread has been interrupted");
            } catch (TimeoutException e) {
                Assertions.fail("Test infrastructure failure");
            }
        }, executorService);
    }

    @Test
    @Sql(scripts = {
            "classpath:scripts/sql/init_event.sql"
    })
    void shouldAcquireLocksInConsistentOrderForConcurrentMultiEventOrders() {
        UserEntity userEntityA = new UserEntity("test1@gmail.com", "Test123", "FirstnameTest", "LastnameTest");
        UserEntity userEntityB = new UserEntity("test2@gmail.com", "Test123", "FirstnameTest", "LastnameTest");
        userRepository.save(userEntityA);
        userRepository.save(userEntityB);

        OrderRequestDto orderRequestDtoA = new OrderRequestDto(List.of(new TicketRequestDto(2L, 1), new TicketRequestDto(3L, 1)));
        OrderRequestDto orderRequestDtoB = new OrderRequestDto(List.of(new TicketRequestDto(3L, 1), new TicketRequestDto(2L, 1)));

        CountDownLatch startCountDownLatch = new CountDownLatch(2);

        try (var executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Void> resultA = createOrderAsync(startCountDownLatch, orderRequestDtoA, userEntityA, executorService);
            CompletableFuture<Void> resultB = createOrderAsync(startCountDownLatch, orderRequestDtoB, userEntityB, executorService);

            int failedOperations = 0;

            if(!checkIfSuccessfullyCreatedOrder(resultA)) failedOperations++;
            if(!checkIfSuccessfullyCreatedOrder(resultB)) failedOperations++;

            Assertions.assertThat(failedOperations).isEqualTo(1);

            long count = orderRepository.count();
            Assertions.assertThat(count).isEqualTo(1);

            int reservedSeatForEvent_2 = seatRepository.countByEvent_EventIdAndSeatStatus(2L, SeatStatus.LOCKED_FOR_CHECKOUT);
            int reservedSeatForEvent_3 = seatRepository.countByEvent_EventIdAndSeatStatus(3L, SeatStatus.LOCKED_FOR_CHECKOUT);
            Assertions.assertThat(reservedSeatForEvent_2).isEqualTo(1);
            Assertions.assertThat(reservedSeatForEvent_3).isEqualTo(1);
        } catch (InterruptedException e){
            Thread.currentThread().interrupt();
            Assertions.fail("Test Main Thread has been interrupted");
        }
    }
}
