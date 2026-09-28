package tacos.security;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation
             .authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web
             .builders.HttpSecurity;
import org.springframework.security.config.annotation.web
                        .configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web
                        .configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@SuppressWarnings("deprecation")
@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {
  
  @Autowired
  private UserDetailsService userDetailsService;

  @Value ("${tacocloud.cors.allowed-origins:" + "http://localhost:4200}")
  private String allowedOrigins;
  
  @Override
  protected void configure(HttpSecurity http) throws Exception {

    OrRequestMatcher apiRequest =
        new OrRequestMatcher(
            new AntPathRequestMatcher("/api/**"),
            new AntPathRequestMatcher("/data-api/**"),
            new AntPathRequestMatcher("/actuator/**"));

    http
        .cors()
      .and()
        .csrf()
      .and()
        .authorizeRequests()
        .antMatchers(
            "/",
            "/login",
            "/register",
            "/images/**",
            "/scripts/**",
            "/styles.css",
            "/webjars/**")
        .permitAll()
        
        .antMatchers(
            HttpMethod.GET,
            "/api/tacos/**",
            "/api/ingredients/**")
        .permitAll()
        
        .antMatchers(
            HttpMethod.POST,
            "/api/tacos/**")
        .hasAnyRole(
            "USER",
            "ADMIN")

        .antMatchers(
            "/api/admin/ingredients/**")
        .hasRole("ADMIN")

        .antMatchers(
            "/api/ingredients/**")
        .hasRole("ADMIN")
        
        .antMatchers(
            HttpMethod.POST,
            "/api/orders/fromEmail")
        .hasRole("ADMIN")
        
        .antMatchers(
            "/api/orders/**")
        .hasAnyRole(
            "USER",
            "ADMIN")
        
            .antMatchers(
            "/api/kitchen/**")
        .hasRole("KITCHEN")
        
        .antMatchers(
            "/data-api/**")
        .hasRole("ADMIN")
        
        .antMatchers(
            "/actuator/health")
        .permitAll()
        
        .antMatchers(
            "/actuator/**")
        .hasRole("ADMIN")

        .antMatchers(
            HttpMethod.POST,
            "/api/payment-methods/tokenize")
        .hasAnyRole(
            "USER",
            "ADMIN")

        .anyRequest()
        .denyAll()

      .and()
        .exceptionHandling()

        .defaultAuthenticationEntryPointFor(
            new HttpStatusEntryPoint(
                HttpStatus.UNAUTHORIZED),
            apiRequest)

      .and()

        .formLogin()
        .loginPage("/login")
        .permitAll()

      .and()

        .httpBasic();
  }

  @Bean
  public PasswordEncoder encoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }
  
  
  @Override
  protected void configure(AuthenticationManagerBuilder auth)
      throws Exception {

    auth
      .userDetailsService(userDetailsService)
      .passwordEncoder(encoder());
    
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {

    CorsConfiguration configuration =new CorsConfiguration();

    configuration.setAllowedOrigins(
        Arrays.asList(allowedOrigins.split(",")));

    configuration.setAllowedMethods(
        Arrays.asList(
            "GET","POST","PUT","PATCH","DELETE","OPTIONS"));

    configuration.setAllowedHeaders(
        Arrays.asList("Authorization","Content-Type","X-Requested-With"));

    configuration.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

    source.registerCorsConfiguration("/api/**",configuration);

    return source;
  }
}
