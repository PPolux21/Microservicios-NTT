package tacos;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceConstructor;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.
                                          SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import lombok.ToString;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor(access=AccessLevel.PRIVATE, force=true)
@Document
public class User implements UserDetails {

  public enum Role {
    USER,
    ADMIN,
    KITCHEN
  }

  private static final long serialVersionUID = 1L;

  @Id
  private String id;
  
  @Indexed(unique=true)
  private final String username;
  
  @ToString.Exclude
  private final String password;
  private final String fullname;
  private final String street;
  private final String city;
  private final String state;
  private final String zip;
  private final String phoneNumber;

  @Indexed(unique=true)
  private final String email;

  private Role role = Role.USER;

  @PersistenceConstructor
  public User(String username, String password, String fullname, String street,
              String city, String state, String zip, String phoneNumber,
              String email) {
    this.username = username;
    this.password = password;
    this.fullname = fullname;
    this.street = street;
    this.city = city;
    this.state = state;
    this.zip = zip;
    this.phoneNumber = phoneNumber;
    this.email = email;
  }
  
  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {

    Role effectiveRole = 
      role != null ? role: Role.USER;

    return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + effectiveRole.name()));
  }

  @Override
  public boolean isAccountNonExpired() {
    return true;
  }

  @Override
  public boolean isAccountNonLocked() {
    return true;
  }

  @Override
  public boolean isCredentialsNonExpired() {
    return true;
  }

  @Override
  public boolean isEnabled() {
    return true;
  }

}
