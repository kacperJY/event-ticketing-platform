CREATE SCHEMA IF NOT EXISTS public AUTHORIZATION pg_database_owner ;
CREATE SEQUENCE events_seq
    START WITH 1
    INCREMENT BY 50;

CREATE TABLE public.events (
                               event_id int8 NOT NULL,
                               created_at timestamptz(6) DEFAULT now(),
                               updated_at timestamptz(6) DEFAULT now(),
                               description varchar(255) NULL,
                               event_category varchar(255) NULL,
                               event_date timestamptz(6) NULL,
                               country varchar(255) NULL,
                               city varchar(255) NULL,
                               street varchar(255) NULL,
                               "no" varchar(255) NULL,
                               postal_code varchar(255) NULL,
                               "name" varchar(255) NULL UNIQUE,
                               places_number int4 NOT NULL,
                               CONSTRAINT events_event_category_check CHECK (((event_category)::text = ANY ((ARRAY['CONCERT'::character varying, 'SPORT'::character varying, 'CINEMA'::character varying, 'THEATER'::character varying, 'CONFERENCE'::character varying, 'FESTIVAL'::character varying])::text[]))),
	CONSTRAINT events_pkey PRIMARY KEY (event_id)
);

CREATE TABLE public.users (
                              user_id uuid DEFAULT gen_random_uuid() NOT NULL,
                              created_at timestamptz(6) DEFAULT now(),
                              updated_at timestamptz(6) DEFAULT now(),
                              email varchar(255) NOT NULL,
                              firstname varchar(255) NULL,
                              is_active bool NOT NULL,
                              lastname varchar(255) NULL,
                              "password" varchar(255) NULL,
                              "role" varchar(255) NULL,
                              CONSTRAINT unique_users_email UNIQUE (email),
                              CONSTRAINT users_pkey PRIMARY KEY (user_id),
                              CONSTRAINT users_role_check CHECK (((role)::text = ANY ((ARRAY['ROLE_USER'::character varying, 'ROLE_ADMIN'::character varying])::text[])))
);

CREATE TABLE public.orders (
                               order_id uuid DEFAULT gen_random_uuid() NOT NULL,
                               created_at timestamptz(6) DEFAULT now(),
                               updated_at timestamptz(6) DEFAULT now(),
                               order_status varchar(255) NULL,
                               payment_session_id varchar(255) NULL,
                               price int8 NULL,
                               user_id uuid NULL,
                               CONSTRAINT orders_order_status_check CHECK (((order_status)::text = ANY ((ARRAY['PENDING'::character varying, 'PAID'::character varying,'CANCELED'::character varying, 'REFUNDED'::character varying])::text[]))),
	CONSTRAINT orders_pkey PRIMARY KEY (order_id),
	CONSTRAINT unique_orders_payment_session_id UNIQUE (payment_session_id),
	CONSTRAINT fk_orders_user_id FOREIGN KEY (user_id) REFERENCES public.users(user_id)
);

CREATE SEQUENCE seats_seq
    START WITH 1
    INCREMENT BY 50;

CREATE TABLE public.seats (
                              seat_id int8 NOT NULL,
                              created_at timestamptz(6) DEFAULT now(),
                              updated_at timestamptz(6) DEFAULT now(),
                              price int8 NULL,
                              seat_number varchar(255) NULL,
                              seat_status varchar(255) NULL,
                              event_id int8 NULL,
                              CONSTRAINT seats_pkey PRIMARY KEY (seat_id),
                              CONSTRAINT seats_seat_status_check CHECK (((seat_status)::text = ANY ((ARRAY['AVAILABLE'::character varying, 'LOCKED_FOR_CHECKOUT'::character varying, 'SOLD'::character varying])::text[]))),
	CONSTRAINT fk_seats_event_id FOREIGN KEY (event_id) REFERENCES public.events(event_id)
);


CREATE TABLE public.tickets (
                                ticket_id uuid DEFAULT gen_random_uuid() NOT NULL,
                                created_at timestamptz(6) DEFAULT now(),
                                updated_at timestamptz(6) DEFAULT now(),
                                price int8 NULL,
                                event_id int8 NULL,
                                order_id uuid NULL,
                                seat_id int8 NULL,
                                CONSTRAINT tickets_pkey PRIMARY KEY (ticket_id),
                                CONSTRAINT fk_tickets_seat_id FOREIGN KEY (seat_id) REFERENCES public.seats(seat_id),
                                CONSTRAINT fk_tickets_event_id FOREIGN KEY (event_id) REFERENCES public.events(event_id),
                                CONSTRAINT fk_seats_order_id FOREIGN KEY (order_id) REFERENCES public.orders(order_id)
);
