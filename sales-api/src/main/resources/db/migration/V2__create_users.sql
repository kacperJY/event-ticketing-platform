INSERT INTO users(email, firstname, is_active, lastname, password, role)
VALUES
('user@gmail.com','User',true,'User','$2a$10$O9PB93IrR1PzLzVX8PlWoubpXy43dxpUGqZUTUs3gOazfEc7F6Lqm','ROLE_USER'),
-- Password: User123

('admin@gmail.com','Admin',true,'Admin','$2a$10$FkzWR45Ni29OX.gDM3y6k.Tp6oM0FLJgTWvAxk7s3YSEXYrx1Vrae','ROLE_ADMIN')
-- Password: Admin123