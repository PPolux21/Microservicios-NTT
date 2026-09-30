package tacos.kitchen.consumer;

import java.util.Collections;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import tacos.TacoOrder.Status;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventType;
import tacos.web.api.OrderWorkflowService;
import tacos.web.api.error.ApiExceptionHandler.ApiException;

@Component
public class WorkflowOrderEventBusinessHandler
    implements OrderEventBusinessHandler {

  private static final UsernamePasswordAuthenticationToken KITCHEN_ACTOR =
      new UsernamePasswordAuthenticationToken(
          "kitchen-event-consumer","N/A",Collections.singletonList(
              new SimpleGrantedAuthority("ROLE_KITCHEN")));

  private final OrderWorkflowService workflow;

  public WorkflowOrderEventBusinessHandler(OrderWorkflowService workflow) {
    this.workflow = workflow;
  }

  @Override
  public Mono<Void> apply(OrderEvent event) {
    if (event.getEventType() == OrderEventType.CANCELLED) {
      return Mono.empty();
    }

    Status target;
    if (event.getEventType() == OrderEventType.ORDER_CREATED) {
      target = Status.ACCEPTED;
    } else if (event.getEventType() == OrderEventType.STATUS_CHANGED) {
      target = contractualStatus(event.getPayload().getStatus());
    } else {
      return Mono.error(new PermanentOrderEventException(
          "UNSUPPORTED_EVENT_TYPE",
          "Unsupported order event type: " + event.getEventType()));
    }

    return workflow.transition(event.getPayload().getOrderId(),target,
            "Order event " + event.getEventId(),KITCHEN_ACTOR)
        .then()
        .onErrorMap(ApiException.class,error ->
            new PermanentOrderEventException(
                error.getCode(),"Order workflow rejected the event.",error));
  }

  private Status contractualStatus(String status) {
    try {
      return Status.valueOf(status);
    } catch (IllegalArgumentException | NullPointerException error) {
      throw new PermanentOrderEventException(
          "UNSUPPORTED_ORDER_STATUS",
          "Unsupported contractual order status.",error);
    }
  }
}
