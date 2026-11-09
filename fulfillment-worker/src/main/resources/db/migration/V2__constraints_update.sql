alter table fulfillment_orders
    drop constraint status_compatibility_check;

alter table fulfillment_orders
    add constraint status_compatibility_check check (
        (
            delivery_status = 'PENDING'
                and attempt_count >= 0
                and delivered_at is null
            )
            or
        (
            delivery_status = 'PROCESSING'
                and attempt_count >= 1
                and next_attempt_at is null
                and last_error is null
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
                and attempt_count >= 0
                and next_attempt_at is null
                and delivered_at is null
                and last_error is not null
            )
        );