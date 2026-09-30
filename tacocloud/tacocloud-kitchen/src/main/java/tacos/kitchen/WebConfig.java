package tacos.kitchen;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Profile("template")
@ConditionalOnExpression("'${tacocloud.messaging.transport:noop}' == 'jms' "
    + "|| '${tacocloud.messaging.transport:noop}' == 'rabbit'")
@Configuration
public class WebConfig implements WebMvcConfigurer {

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addRedirectViewController("/", "/orders/receive");
  }
  
}
