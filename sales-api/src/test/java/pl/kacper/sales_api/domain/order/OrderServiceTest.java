package pl.kacper.sales_api.domain.order;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import pl.kacper.sales_api.common.exception.NoSuchQuantityException;
import pl.kacper.sales_api.domain.event.EventRepository;
import pl.kacper.sales_api.domain.order.dto.OrderRequestDto;
import pl.kacper.sales_api.domain.order.dto.TicketRequestDto;
import pl.kacper.sales_api.domain.seat.SeatEntity;
import pl.kacper.sales_api.domain.seat.SeatRepository;
import pl.kacper.sales_api.domain.seat.SeatStatus;
import pl.kacper.sales_api.domain.user.UserEntity;
import pl.kacper.sales_api.domain.user.UserRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@ExtendWith(MockitoExtension.class)
public class OrderServiceTest {


    @Mock
    private EventRepository eventRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SeatRepository seatRepository;

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderService orderService;

    private List<TicketRequestDto> ticketRequestDtoList;
    private UserEntity userEntity;

    @BeforeEach()
    void initializeState() {
        userEntity = new UserEntity("mail@gmail.com", "password", "Firstname", "Lastname");
        ;
        ticketRequestDtoList = List.of(
                new TicketRequestDto(1L, 1),
                new TicketRequestDto(2L, 2),
                new TicketRequestDto(3L, 3)
        );
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when pass one or more invalid Event IDs in DTO")
    void shouldThrowExceptionWhenPassedEventsNumberIsDifferentThanExistingEvents() {
        OrderRequestDto orderRequestDto = new OrderRequestDto(ticketRequestDtoList);

        long eventNumber = Math.max(0, ticketRequestDtoList.size() - 2);
        Mockito.when(eventRepository.countByEventIdIn(ArgumentMatchers.anyList())).thenReturn(eventNumber);

        Assertions.assertThatThrownBy(() -> orderService.createOrder(orderRequestDto, userEntity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Some of the event IDs are incorrect");

    }

    @Test
    @DisplayName("Should throw UsernameNotFoundException when pass as Order Owner invalid UserDetails of non existing User")
    void shouldThrowExceptionWhenOrderOwnerDoestNotExists() {
        OrderRequestDto orderRequestDto = new OrderRequestDto(ticketRequestDtoList);

        Mockito.when(eventRepository.countByEventIdIn(ArgumentMatchers.anyList())).thenReturn((long) ticketRequestDtoList.size());

        Mockito.when(userRepository.findUserByEmail(userEntity.getEmail())).thenReturn(Optional.empty());

        Assertions.assertThatThrownBy(() -> orderService.createOrder(orderRequestDto, userEntity))
                .isInstanceOf(UsernameNotFoundException.class);
    }


    @Test
    @DisplayName("Should throw NoSuchQuantityException when there is not enough available seats")
    void shouldThrowExceptionWhenNumberOfAvailableSeatsIsNotEnough() {
        OrderRequestDto orderRequestDto = new OrderRequestDto(ticketRequestDtoList);

        Mockito.when(eventRepository.countByEventIdIn(ArgumentMatchers.anyList())).thenReturn((long) ticketRequestDtoList.size());

        Mockito.when(userRepository.findUserByEmail(userEntity.getEmail())).thenReturn(Optional.of(userEntity));

        Mockito.when(seatRepository.findSeatByEventIdWithLocking(ArgumentMatchers.anyLong(), ArgumentMatchers.any(SeatStatus.class), ArgumentMatchers.any()))
                .thenReturn(Collections.emptyList());

        Assertions.assertThatThrownBy(() -> orderService.createOrder(orderRequestDto, userEntity))
                .isInstanceOf(NoSuchQuantityException.class);
    }

    @Test
    @DisplayName("Should create new Order")
    void shouldSuccessfullyCreateOrder() {
        OrderRequestDto orderRequestDto = new OrderRequestDto(ticketRequestDtoList);

        Mockito.when(eventRepository.countByEventIdIn(ArgumentMatchers.anyList())).thenReturn((long) ticketRequestDtoList.size());

        Mockito.when(userRepository.findUserByEmail(userEntity.getEmail())).thenReturn(Optional.of(userEntity));

        List<SeatEntity> seatEntityListFor1 = List.of(
                new SeatEntity(null, null, 400L, SeatStatus.AVAILABLE)
        );

        List<SeatEntity> seatEntityListFor2 = List.of(
                new SeatEntity(null, null, 500L, SeatStatus.AVAILABLE),
                new SeatEntity(null, null, 600L, SeatStatus.AVAILABLE)
        );

        List<SeatEntity> seatEntityListFor3 = List.of(
                new SeatEntity(null, null, 700L, SeatStatus.AVAILABLE),
                new SeatEntity(null, null, 800L, SeatStatus.AVAILABLE),
                new SeatEntity(null, null, 900L, SeatStatus.AVAILABLE)
        );

        Mockito.when(seatRepository.findSeatByEventIdWithLocking(ArgumentMatchers.eq(1L), ArgumentMatchers.eq(SeatStatus.AVAILABLE), ArgumentMatchers.any()))
                .thenReturn(seatEntityListFor1);

        Mockito.when(seatRepository.findSeatByEventIdWithLocking(ArgumentMatchers.eq(2L), ArgumentMatchers.eq(SeatStatus.AVAILABLE), ArgumentMatchers.any()))
                .thenReturn(seatEntityListFor2);

        Mockito.when(seatRepository.findSeatByEventIdWithLocking(ArgumentMatchers.eq(3L), ArgumentMatchers.eq(SeatStatus.AVAILABLE), ArgumentMatchers.any()))
                .thenReturn(seatEntityListFor3);

        orderService.createOrder(orderRequestDto, userEntity);

        ArgumentCaptor<OrderEntity> orderEntityArgumentCaptor = ArgumentCaptor.forClass(OrderEntity.class);
        Mockito.verify(orderRepository, Mockito.times(1)).save(orderEntityArgumentCaptor.capture());

        OrderEntity orderEntity = orderEntityArgumentCaptor.getValue();

        ArrayList<SeatEntity> allSeatEntities = new ArrayList<>();
        allSeatEntities.addAll(seatEntityListFor1);
        allSeatEntities.addAll(seatEntityListFor2);
        allSeatEntities.addAll(seatEntityListFor3);
        long fullPrice = allSeatEntities.stream().mapToLong(SeatEntity::getPrice).sum();

        Assertions.assertThat(orderEntity.getPrice()).isEqualTo(fullPrice);
    }
}
