package pl.kacper.sales_api.domain.user;


import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends ListCrudRepository<UserEntity, UUID> {

    Optional<UserEntity> findUserByEmail(String email);

    boolean existsByEmail(String email);
}
