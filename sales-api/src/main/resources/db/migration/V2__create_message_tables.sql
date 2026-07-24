CREATE TABLE public.outbox_messages (
                                       payload_version varchar(255) NOT NULL,
                                       retry_count int4 NOT NULL,
                                       next_attempt_at timestamptz(6) NULL,
                                       locked_at timestamptz(6) NULL,
                                       created_at timestamptz(6) NOT NULL,
                                       sent_at timestamptz(6) NULL,
                                       message_id uuid NOT NULL,
                                       version int8 NOT NULL,
                                       aggregate_id varchar(255) NOT NULL,
                                       aggregate_type varchar(255) NOT NULL,
                                       message_status varchar(255) NOT NULL,
                                       operation_type varchar(255) NOT NULL,
                                       payload text NOT NULL,
                                       exchange varchar(255) NOT NULL,
                                       routing_key varchar(255) NOT NULL,
                                       CONSTRAINT outbox_message_aggregate_type_check CHECK (((aggregate_type)::text = ANY ((ARRAY['RESERVATION'::character varying, 'EVENT'::character varying, 'SEAT'::character varying, 'PAYMENT'::character varying])::text[]))),
                                       CONSTRAINT outbox_message_payload_version_check CHECK (((payload_version)::text = ANY ((ARRAY['V1'::character varying])::text[]))),
                                       CONSTRAINT outbox_message_message_status_check CHECK (((message_status)::text = ANY ((ARRAY['PENDING'::character varying, 'SENT'::character varying, 'FAILED'::character varying,'PROCESSING'::character varying])::text[]))),
                                       CONSTRAINT outbox_message_operation_type_check CHECK (((operation_type)::text = ANY ((ARRAY['CREATE'::character varying, 'UPDATE'::character varying, 'DELETE'::character varying])::text[]))),
                                       CONSTRAINT outbox_message_pkey PRIMARY KEY (message_id)
);



CREATE TABLE public.processed_messages (
                                          processed_at timestamptz(6) NOT NULL,
                                          message_id uuid NOT NULL,
                                          consumer_name varchar(255) NOT NULL,
                                          CONSTRAINT processed_message_pkey PRIMARY KEY (message_id, consumer_name)
);