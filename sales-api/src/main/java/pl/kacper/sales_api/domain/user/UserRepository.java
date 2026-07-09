package pl.kacper.sales_api.domain.user;


import org.springframework.data.repository.ListCrudRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends ListCrudRepository<UserEntity, UUID> {

    Optional<UserEntity> findUserByEmail(String email);
}
