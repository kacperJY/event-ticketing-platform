create table public.stripe_events
(
    stripe_event_id   text                         NOT NULL,
    order_id          uuid                         NOT NULL,
    payment_intent_id text                         NOT NULL,
    stripe_event_type text                         NOT NULL,
    processed_at      timestamptz(6) default now() NOT NULL,
    CONSTRAINT pk_stripe_event_id PRIMARY KEY (stripe_event_id)
);

alter table orders
    ADD COLUMN stripe_refund_id text unique;
alter table orders
    ADD COLUMN refund_requested_at timestamptz;
alter table orders
    ADD COLUMN refunded_at timestamptz;


ALTER TABLE orders
    DROP CONSTRAINT orders_payment_status_check;

ALTER TABLE orders
    ADD CONSTRAINT orders_payment_status_check CHECK (((payment_status)::text = ANY
                                                       ((ARRAY [
                                                           'PENDING'::character varying,
                                                           'SUCCEEDED'::character varying,
                                                           'NOT_INITIALIZED'::character varying,
                                                           'REFUNDED'::character varying,
                                                           'REFUND_REQUIRED'::character varying,
                                                           'REFUND_PENDING'::character varying,
                                                           'REFUND_FAILED'::character varying,
                                                           'CANCELED'::character varying])::text[])));