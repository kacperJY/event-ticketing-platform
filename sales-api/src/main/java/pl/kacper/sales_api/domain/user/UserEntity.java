package pl.kacper.sales_api.domain.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import pl.kacper.sales_api.domain.BaseEntity;

import java.util.Objects;
import java.util.UUID;

@Getter
@NoArgsConstructor

@Entity
@Table(name = "users")
@EnableJpaAuditing
public class UserEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "user_id")
    private UUID userId;

    @Column(unique = true, nullable = false)
    private String email;

    private String password;

    private String firstname;

    private String lastname;

    @Enumerated(EnumType.STRING)
    private Role role;

    private boolean isActive;

    public UserEntity(String email, String password, String firstname, String lastname) {
        this.email = email;
        this.password = password;
        this.firstname = firstname;
        this.lastname = lastname;

        this.role = Role.ROLE_USER;
        this.isActive = true;
    }

    @Override
    public boolean equals(Object object) {
        if (object == null || getClass() != object.getClass()) return false;
        UserEntity that = (UserEntity) object;
        return Objects.equals(email, that.email);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(email);
    }
}
