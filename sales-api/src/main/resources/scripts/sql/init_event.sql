INSERT INTO events (event_id, description, event_category, event_date, country, city, street, no, postal_code, name,
                    places_number)
VALUES (1, 'test-description', 'CONCERT', '2026-08-20T11:00:00Z', 'Poland', 'Poznan', 'Stanisława Wyspiańskiego', '33',
        '60-751', 'test-name1', 3);

INSERT INTO seats (seat_id, price, seat_number, seat_status, event_id)
VALUES (nextval('seats_seq'), 4500, 'S1', 'AVAILABLE', 1),
       (nextval('seats_seq'), 6000, 'S2', 'AVAILABLE', 1),
       (nextval('seats_seq'), 5000, 'S3', 'AVAILABLE', 1);


-- Instance for reversed lock problem
INSERT INTO events (event_id, description, event_category, event_date, country, city, street, no, postal_code, name,
                    places_number)
VALUES (2, 'test-description', 'CONCERT', '2026-08-20T11:00:00Z', 'Poland', 'Poznan',
        'Stanisława Wyspiańskiego', '33',
        '60-751', 'test-name2', 1);

INSERT INTO seats (seat_id, price, seat_number, seat_status, event_id)
VALUES (nextval('seats_seq'), 4500, 'S1', 'AVAILABLE', 2);



INSERT INTO events (event_id, description, event_category, event_date, country, city, street, no, postal_code, name,
                    places_number)
VALUES (3, 'test-description', 'CONCERT', '2026-08-20T11:00:00Z', 'Poland', 'Poznan', 'Stanisława Wyspiańskiego', '33',
        '60-751', 'test-name3', 1);

INSERT INTO seats (seat_id, price, seat_number, seat_status, event_id)
VALUES (nextval('seats_seq'), 4500, 'S1', 'AVAILABLE', 3);