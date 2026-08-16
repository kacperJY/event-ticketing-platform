ALTER TABLE orders
    RENAME COLUMN payment_session_id TO stripe_payment_intent_id;

ALTER TABLE orders
    DROP COLUMN price;
ALTER TABLE orders
    ADD COLUMN total_amount int8 NOT NULL;

ALTER TABLE orders
    DROP COLUMN order_status;
ALTER TABLE orders
    ADD COLUMN order_status varchar(255) NOT NULL default 'PENDING';
ALTER TABLE orders
    ADD CONSTRAINT orders_order_status_check CHECK (((order_status)::text = ANY
                                                     ((ARRAY ['PENDING'::character varying, 'COMPLETED'::character varying,'CANCELED'::character varying, 'EXPIRED'::character varying])::text[])));

ALTER TABLE orders
    ADD COLUMN payment_status varchar(255) NOT NULL default 'NOT_INITIALIZED'
        CONSTRAINT orders_payment_status_check CHECK (((payment_status)::text = ANY
                                                       ((ARRAY ['PENDING'::character varying, 'SUCCEEDED'::character varying,'FAILED'::character varying, 'NOT_INITIALIZED'::character varying, 'REFUNDED'::character varying, 'CANCELED'::character varying])::text[])));
ALTER TABLE orders
    ADD COLUMN currency_type varchar(255) NOT NULL default 'PLN'
        CONSTRAINT currency_check CHECK (((currency_type)::text = ANY ((ARRAY ['PLN'::character varying])::text[])));
ALTER TABLE orders
    ADD COLUMN payment_initialized_at timestamptz(6) NULL;
ALTER TABLE orders
    ADD COLUMN paid_at timestamptz(6) NULL;
ALTER TABLE orders
    ADD COLUMN expires_at timestamptz(6) NOT NULL;


drop table tickets;

CREATE SEQUENCE order_items_seq
    START WITH 1
    INCREMENT BY 50;

CREATE TABLE public.order_items
(
    order_item_id int8 NOT NULL,
    created_at    timestamptz(6) DEFAULT now(),
    updated_at    timestamptz(6) DEFAULT now(),
    price         int8 NOT NULL,
    event_id      int8 NOT NULL,
    order_id      uuid NOT NULL,
    seat_id       int8 NOT NULL,
    CONSTRAINT order_item_pkey PRIMARY KEY (order_item_id),
    CONSTRAINT fk_order_item_seat_id FOREIGN KEY (seat_id) REFERENCES public.seats (seat_id),
    CONSTRAINT fk_order_item_event_id FOREIGN KEY (event_id) REFERENCES public.events (event_id),
    CONSTRAINT fk_seats_order_id FOREIGN KEY (order_id) REFERENCES public.orders (order_id)
);