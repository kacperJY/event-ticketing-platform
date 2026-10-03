alter table public.outbox_messages
    drop constraint outbox_message_aggregate_type_check;
alter table public.outbox_messages
    drop constraint outbox_message_operation_type_check;


alter table public.outbox_messages
    add constraint outbox_message_aggregate_type_check CHECK (((aggregate_type)::text = ANY
                                                               ((ARRAY ['ORDER'::character varying, 'EVENT'::character varying, 'SEAT'::character varying])::text[])));

alter table public.outbox_messages
    add constraint outbox_message_operation_type_check CHECK (((operation_type)::text = ANY
                                                               ((ARRAY ['CREATE'::character varying, 'UPDATE'::character varying, 'DELETE'::character varying, 'PAID'::character varying])::text[])));