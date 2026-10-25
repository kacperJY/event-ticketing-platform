create table processed_messages
(

    consumer_name text,
    message_id    uuid,
    processed_at  timestamptz(6) not null,
    CONSTRAINT processed_messages_composite_primary_key PRIMARY KEY (consumer_name, message_id)
);

create table fulfillment_orders
(
    source_order_id       uuid,
    purchaser_email       text                         not null,
    paid_at               timestamptz(6)               not null,
    delivery_status       text                         not null,
    processing_started_at timestamptz(6),
    attempt_count         int4                         not null,
    next_attempt_at       timestamptz(6),
    delivered_at          timestamptz(6),
    last_error            text,
    created_at            timestamptz(6) DEFAULT now() not null,
    version               int8                         not null,

    constraint fulfillment_orders_primary_key primary key (source_order_id),
    constraint attempt_count_zero_or_positive_check check ( attempt_count >= 0 ),
    constraint version_check check ( version >= 0 ),

    constraint status_compatibility_check check (
        (
            delivery_status = 'PENDING'
                and attempt_count = 0
                and processing_started_at is null
                and next_attempt_at is null
                and delivered_at is null
                and last_error is null
            )
            or
        (
            delivery_status = 'PROCESSING'
                and attempt_count >= 1
                and ((next_attempt_at is null and last_error is null) or (next_attempt_at is not null and last_error is not null))
                and processing_started_at is not null
                and delivered_at is null
            )
            or
        (
            delivery_status = 'SENT'
                and attempt_count >= 1
                and next_attempt_at is null
                and processing_started_at is not null
                and delivered_at is not null
                and last_error is null
            )
            or
        (
            delivery_status = 'FAILED'
                and attempt_count >= 1
                and processing_started_at is not null
                and next_attempt_at is null
                and delivered_at is null
                and last_error is not null
            )
        )
);

create table tickets
(

    ticket_id            uuid,
    source_order_id      uuid                         not null,
    source_order_item_id int8                         not null unique,
    created_at           timestamptz(6) DEFAULT now() not null,

    event_name           text                         not null,
    country              text                         not null,
    city                 text                         not null,
    street               text                         not null,
    no                   text                         not null,
    postal_code          text                         not null,
    event_date           timestamptz(6)               not null,
    event_category       text                         not null,
    seat_number          text                         not null,

    constraint tickets_primary_key primary key (ticket_id),
    constraint source_order_id_foreign_key foreign key (source_order_id) references fulfillment_orders (source_order_id)
);

create index if not exists ticket_source_order_id_idx on tickets (source_order_id);