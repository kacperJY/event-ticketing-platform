package pl.kacper.fulfillment_worker.domain.order;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import pl.kacper.exception.InvalidMessageFormatException;
import pl.kacper.fulfillment_worker.domain.message.dto.payload.CompletedOrderMessagePayloadV1;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class CompletedOrderMessagePayloadValidatorTest {

    private final LocalValidatorFactoryBean localValidatorFactoryBean = buildValidator();
    private final CompletedOrderMessagePayloadValidator completedOrderMessagePayloadValidator = new CompletedOrderMessagePayloadValidator(
            localValidatorFactoryBean
    );

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final Instant DATE = Instant.now();

    private static final List<CompletedOrderMessagePayloadV1.Item> VALID_ORDER_ITEM_LIST = List.of(
            new CompletedOrderMessagePayloadV1.Item(
                    88204L,
                    "Summer Music Festival 2026",
                    "United Kingdom",
                    "London",
                    "Wembley Park Drive",
                    "HA9 0WS",
                    "HA9 0WS",
                    DATE,
                    "Music Festivals",
                    "Section B, Row 14, Seat 22"
            ),
            new CompletedOrderMessagePayloadV1.Item(
                    30195L,
                    "Global AI & Tech Expo",
                    "United States",
                    "San Francisco",
                    "Howard Street",
                    "747",
                    "94103",
                    DATE,
                    "Conferences",
                    "Main Hall, Desk 104"
            )
    );

    private static final CompletedOrderMessagePayloadV1 VALID_DTO = new CompletedOrderMessagePayloadV1(
            ORDER_ID,
            "mail@gmail.com",
            DATE,
            VALID_ORDER_ITEM_LIST
    );

    private static LocalValidatorFactoryBean buildValidator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }

    @Test
    void shouldSuccessfullyValidatePayload() {
        Assertions.assertThatCode(() -> completedOrderMessagePayloadValidator.validate(VALID_DTO))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPayloadCases")
    void shouldRejectInvalidPayload(String caseName, CompletedOrderMessagePayloadV1 invalidPayload) {
        Assertions.assertThatThrownBy(() -> completedOrderMessagePayloadValidator.validate(invalidPayload))
                .isInstanceOf(InvalidMessageFormatException.class);
    }

    static Stream<Arguments> invalidPayloadCases() {
        return Stream.of(
                Arguments.of(
                        "missing orderId",
                        new CompletedOrderMessagePayloadV1(null, "mail@gmail.com", DATE, VALID_ORDER_ITEM_LIST)
                ),
                Arguments.of(
                        "missing purchaser email",
                        new CompletedOrderMessagePayloadV1(ORDER_ID, null, DATE, VALID_ORDER_ITEM_LIST)
                ),
                Arguments.of(
                        "blank purchaser email",
                        new CompletedOrderMessagePayloadV1(ORDER_ID, " ", DATE, VALID_ORDER_ITEM_LIST)
                ),
                Arguments.of(
                        "invalid purchaser email",
                        new CompletedOrderMessagePayloadV1(ORDER_ID, "invalid-email", DATE, VALID_ORDER_ITEM_LIST)
                ),
                Arguments.of(
                        "missing paidAt",
                        new CompletedOrderMessagePayloadV1(ORDER_ID, "mail@gmail.com", null, VALID_ORDER_ITEM_LIST)
                ),
                Arguments.of(
                        "missing item list",
                        new CompletedOrderMessagePayloadV1(ORDER_ID, "mail@gmail.com", DATE, null)
                ),
                Arguments.of(
                        "empty item list",
                        new CompletedOrderMessagePayloadV1(ORDER_ID, "mail@gmail.com", DATE, List.of())
                ),
                Arguments.of(
                        "null item",
                        new CompletedOrderMessagePayloadV1(ORDER_ID, "mail@gmail.com", DATE, Collections.singletonList(null))
                ),
                Arguments.of(
                        "missing orderItemId",
                        payloadWithItem(new OrderItemBuilder().orderItemId(null).build())
                ),
                Arguments.of(
                        "zero orderItemId",
                        payloadWithItem(new OrderItemBuilder().orderItemId(0L).build())
                ),
                Arguments.of(
                        "negative orderItemId",
                        payloadWithItem(new OrderItemBuilder().orderItemId(-1L).build())
                ),
                Arguments.of(
                        "blank event name",
                        payloadWithItem(new OrderItemBuilder().eventName(" ").build())
                ),
                Arguments.of(
                        "blank country",
                        payloadWithItem(new OrderItemBuilder().country(" ").build())
                ),
                Arguments.of(
                        "blank city",
                        payloadWithItem(new OrderItemBuilder().city(" ").build())
                ),
                Arguments.of(
                        "blank street",
                        payloadWithItem(new OrderItemBuilder().street(" ").build())
                ),
                Arguments.of(
                        "blank number",
                        payloadWithItem(new OrderItemBuilder().no(" ").build())
                ),
                Arguments.of(
                        "blank postal code",
                        payloadWithItem(new OrderItemBuilder().postalCode(" ").build())
                ),
                Arguments.of(
                        "missing event date",
                        payloadWithItem(new OrderItemBuilder().eventDate(null).build())
                ),
                Arguments.of(
                        "blank event category",
                        payloadWithItem(new OrderItemBuilder().eventCategory(" ").build())
                ),
                Arguments.of(
                        "blank seat number",
                        payloadWithItem(new OrderItemBuilder().seatNumber(" ").build())
                )
        );
    }

    @Test
    void shouldRejectDuplicatedOrderItemId() {
        long duplicatedOrderItemId = 88204L;
        CompletedOrderMessagePayloadV1 payload = new CompletedOrderMessagePayloadV1(
                ORDER_ID,
                "mail@gmail.com",
                DATE,
                List.of(
                        new OrderItemBuilder().orderItemId(duplicatedOrderItemId).build(),
                        new OrderItemBuilder().orderItemId(duplicatedOrderItemId).build()
                )
        );

        Assertions.assertThatThrownBy(() -> completedOrderMessagePayloadValidator.validate(payload))
                .isInstanceOf(InvalidMessageFormatException.class);
    }

    private static CompletedOrderMessagePayloadV1 payloadWithItem(CompletedOrderMessagePayloadV1.Item item) {
        return new CompletedOrderMessagePayloadV1(
                ORDER_ID,
                "mail@gmail.com",
                DATE,
                List.of(item)
        );
    }

    private static class OrderItemBuilder {
        private Long orderItemId = 88204L;
        private String eventName = "Summer Music Festival 2026";
        private String country = "United Kingdom";
        private String city = "London";
        private String street = "Wembley Park Drive";
        private String no = "22";
        private String postalCode = "HA9 0WS";
        private Instant eventDate = DATE;
        private String eventCategory = "Music Festivals";
        private String seatNumber = "Section B, Row 14, Seat 22";

        private OrderItemBuilder orderItemId(Long orderItemId) {
            this.orderItemId = orderItemId;
            return this;
        }

        private OrderItemBuilder eventName(String eventName) {
            this.eventName = eventName;
            return this;
        }

        private OrderItemBuilder country(String country) {
            this.country = country;
            return this;
        }

        private OrderItemBuilder city(String city) {
            this.city = city;
            return this;
        }

        private OrderItemBuilder street(String street) {
            this.street = street;
            return this;
        }

        private OrderItemBuilder no(String no) {
            this.no = no;
            return this;
        }

        private OrderItemBuilder postalCode(String postalCode) {
            this.postalCode = postalCode;
            return this;
        }

        private OrderItemBuilder eventDate(Instant eventDate) {
            this.eventDate = eventDate;
            return this;
        }

        private OrderItemBuilder eventCategory(String eventCategory) {
            this.eventCategory = eventCategory;
            return this;
        }

        private OrderItemBuilder seatNumber(String seatNumber) {
            this.seatNumber = seatNumber;
            return this;
        }

        private CompletedOrderMessagePayloadV1.Item build() {
            return new CompletedOrderMessagePayloadV1.Item(
                    orderItemId,
                    eventName,
                    country,
                    city,
                    street,
                    no,
                    postalCode,
                    eventDate,
                    eventCategory,
                    seatNumber
            );
        }
    }
}
