package pl.kacper.sales_api.domain.order;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.kacper.sales_api.common.exception.NoSuchQuantityException;
import pl.kacper.sales_api.domain.event.EventEntity;
import pl.kacper.sales_api.domain.event.EventRepository;
import pl.kacper.sales_api.domain.eventTicket.TicketEntity;
import pl.kacper.sales_api.domain.order.dto.OrderRequestDto;
import pl.kacper.sales_api.domain.order.dto.OrderResponseDto;
import pl.kacper.sales_api.domain.order.dto.TicketRequestDto;
import pl.kacper.sales_api.domain.seat.SeatEntity;
import pl.kacper.sales_api.domain.seat.SeatRepository;
import pl.kacper.sales_api.domain.seat.SeatStatus;
import pl.kacper.sales_api.domain.user.UserEntity;
import pl.kacper.sales_api.domain.user.UserRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class OrderService {

    private final SeatRepository seatRepository;
    private final OrderRepository orderRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;


    @Autowired
    public OrderService(SeatRepository seatRepository, OrderRepository orderRepository, EventRepository eventRepository, UserRepository userRepository) {
        this.seatRepository = seatRepository;
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public OrderResponseDto createOrder(OrderRequestDto orderRequestDto, UserDetails userDetails) {
        List<TicketRequestDto> tickets = orderRequestDto.tickets();

        List<Long> eventIdList = tickets.stream()
                .map(TicketRequestDto::eventId)
                .toList();

        long existedEvents = eventRepository.countByEventIdIn(eventIdList);

        // Safe-check if all event exists - to prevent fetching SeatEntity record for no reason
        if (existedEvents != eventIdList.size())
            throw new IllegalArgumentException("Some of the event IDs are incorrect or passed duplicate event ID. Provided %d, but founded %d."
                    .formatted(eventIdList.size(), existedEvents));

        UserEntity userEntity = userRepository.findUserByEmail(userDetails.getUsername()).
                orElseThrow(() -> new UsernameNotFoundException("Invalid username. Cannot find user with such username: " + userDetails.getUsername()));


        Map<Long, List<SeatEntity>> seatEntitiesByEventId = mapAvailablePlacesWithEventEntities(tickets);

        long fullPrice = 0;
        OrderEntity orderEntity = new OrderEntity();
        for (Map.Entry<Long, List<SeatEntity>> entry : seatEntitiesByEventId.entrySet()) {
            Long eventId = entry.getKey();
            EventEntity eventEntityReference = eventRepository.getReferenceById(eventId);
            for (SeatEntity seatEntity : entry.getValue()) {
                seatEntity.setSeatStatus(SeatStatus.LOCKED_FOR_CHECKOUT); // DirtyChecking - not explicit seatRepository.save()
                fullPrice += seatEntity.getPrice();
                TicketEntity ticketEntity = createTicketEntity(seatEntity.getPrice(), seatEntity, eventEntityReference);
                orderEntity.addTicketToOrder(ticketEntity);
            }
        }
        orderEntity.setPurchaser(userEntity);
        orderEntity.setOrderStatus(OrderStatus.PENDING);
        orderEntity.setPrice(fullPrice);

        orderRepository.save(orderEntity);

        return new OrderResponseDto(
                orderEntity.getOrderId(),
                orderEntity.getPurchaser().getEmail(),
                fullPrice,
                orderEntity.getCreatedAt(),
                orderEntity.getOrderStatus()
        );
    }

    private TicketEntity createTicketEntity(long price, SeatEntity seat, EventEntity eventEntity) {
        return new TicketEntity(price, seat, eventEntity);
    }


    private Map<Long, List<SeatEntity>> mapAvailablePlacesWithEventEntities(List<TicketRequestDto> tickets) {
        Map<Long, List<SeatEntity>> seatEntityListOfEventMap = new HashMap<>();

        for (TicketRequestDto ticket : tickets) {
            Long eventId = ticket.eventId();
            int quantity = ticket.quantity();
            PageRequest pageRequest = PageRequest.of(0, quantity, Sort.by("seatId").ascending());

            List<SeatEntity> seatEntityList = seatRepository.findSeatByEventIdWithLocking(eventId, SeatStatus.AVAILABLE, pageRequest);

            if (seatEntityList.size() != quantity)
                throw new NoSuchQuantityException("There are not enough available tickets for event ID=%d. Expected %d but available %d. Order will not be completed"
                        .formatted(eventId, quantity, seatEntityList.size()));

            seatEntityListOfEventMap.put(eventId, seatEntityList);

        }

        return seatEntityListOfEventMap;
    }
}
