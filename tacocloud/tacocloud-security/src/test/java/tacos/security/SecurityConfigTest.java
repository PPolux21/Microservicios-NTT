package tacos.security;

import static org.springframework.security
    .test.web.servlet.request
    .SecurityMockMvcRequestPostProcessors
    .csrf;

import static org.springframework.security
    .test.web.servlet.request
    .SecurityMockMvcRequestPostProcessors
    .user;

import static org.springframework.test.web.servlet
    .request.MockMvcRequestBuilders.get;

import static org.springframework.test.web.servlet
    .request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet
    .result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.springframework.context.annotation.Import;

import org.springframework.security.core.userdetails.UserDetailsService;

import org.springframework.test.web.servlet.MockMvc;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;


@WebMvcTest(controllers = SecurityConfigTest.TestController.class)
@Import(SecurityConfig.class)
public class SecurityConfigTest {

  @Autowired
  private MockMvc mvc;


  @MockBean
  private UserDetailsService
      userDetailsService;

  @Test
  public void shouldApplyRoleMatrix()
      throws Exception {


    mvc.perform(
        get("/api/tacos"))

        .andExpect(
            status().isOk());


    mvc.perform(
        post("/api/orders")
            .with(csrf()))

        .andExpect(
            status().isUnauthorized());


    mvc.perform(
        post("/api/orders")
            .with(csrf())
            .with(
                user("jose")
                    .roles("USER")))

        .andExpect(
            status().isOk());


    mvc.perform(
        post("/api/ingredients")
            .with(csrf())
            .with(
                user("jose")
                    .roles("USER")))

        .andExpect(
            status().isForbidden());


    mvc.perform(
        post("/api/ingredients")
            .with(csrf())
            .with(
                user("admin")
                    .roles("ADMIN")))

        .andExpect(
            status().isOk());

    mvc.perform(
        post("/api/kitchen/queue")
            .with(csrf())
            .with(
                user("cook")
                    .roles("KITCHEN")))

        .andExpect(
            status().isOk());

    mvc.perform(
        post("/api/kitchen/queue")
            .with(csrf())
            .with(
                user("jose")
                    .roles("USER")))

        .andExpect(
            status().isForbidden());
  }


  @Test
  public void shouldDenyUnlistedEndpoint()
      throws Exception {

    mvc.perform(
        get("/new-unlisted-route")
            .with(
                user("admin")
                    .roles("ADMIN")))

        .andExpect(
            status().isForbidden());
  }


  @Test
  public void shouldProtectActuatorAndDataRest()
      throws Exception {

    mvc.perform(
        get("/actuator/health"))

        .andExpect(
            status().isOk());

    mvc.perform(
        get("/actuator/info")
            .with(
                user("jose")
                    .roles("USER")))

        .andExpect(
            status().isForbidden());

    mvc.perform(
        get("/actuator/info")
            .with(
                user("admin")
                    .roles("ADMIN")))

        .andExpect(
            status().isOk());

    mvc.perform(
        get("/data-api/users")
            .with(
                user("jose")
                    .roles("USER")))

        .andExpect(
            status().isForbidden());


    mvc.perform(
        get("/data-api/users")
            .with(
                user("admin")
                    .roles("ADMIN")))

        .andExpect(
            status().isOk());
  }

  @RestController
  static class TestController {

    @GetMapping("/api/tacos")
    public String tacos() {
      return "ok";
    }


    @PostMapping("/api/orders")
    public String createOrder() {
      return "ok";
    }


    @PostMapping("/api/ingredients")
    public String createIngredient() {
      return "ok";
    }


    @PostMapping("/api/kitchen/queue")
    public String kitchen() {
      return "ok";
    }


    @GetMapping("/actuator/health")
    public String health() {
      return "ok";
    }


    @GetMapping("/actuator/info")
    public String info() {
      return "ok";
    }


    @GetMapping("/data-api/users")
    public String dataApi() {
      return "ok";
    }


    @GetMapping("/new-unlisted-route")
    public String unlisted() {
      return "ok";
    }
  }
}