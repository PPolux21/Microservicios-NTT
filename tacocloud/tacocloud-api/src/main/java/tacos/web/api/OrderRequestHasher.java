package tacos.web.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import tacos.web.api.mapper.ApiMapper.OrderCreateCommand;
import tacos.web.api.mapper.ApiMapper.OrderItemCommand;
import tacos.web.api.mapper.ApiMapper.TacoCommand;

@Component
public class OrderRequestHasher {

  public String hash(OrderCreateCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("order command is required");
    }

    StringBuilder canonical = new StringBuilder();
    append(canonical,command.getDeliveryName());
    append(canonical,command.getDeliveryStreet());
    append(canonical,command.getDeliveryCity());
    append(canonical,command.getDeliveryState());
    append(canonical,command.getDeliveryZip());
    append(canonical,command.getPaymentMethodId());
    append(canonical,normalizeCoupon(command.getCouponCode()));

    List<OrderItemCommand> items = command.getItems() != null
        ? command.getItems() : Collections.emptyList();
    canonical.append(items.size()).append(':');
    for (OrderItemCommand item : items) {
      if (item == null) {
        append(canonical,null);
        continue;
      }
      append(canonical,item.getQuantity() != null
          ? item.getQuantity().toString() : null);
      TacoCommand taco = item.getTaco();
      append(canonical,taco != null ? taco.getName() : null);
      List<String> ingredientIds = taco != null
          && taco.getIngredientIds() != null
              ? taco.getIngredientIds() : Collections.emptyList();
      canonical.append(ingredientIds.size()).append(':');
      ingredientIds.forEach(id -> append(canonical,id));
    }

    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(
          canonical.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        hex.append(String.format(Locale.ROOT,"%02x",value & 0xff));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is not available",impossible);
    }
  }

  private String normalizeCoupon(String couponCode) {
    return couponCode == null || couponCode.trim().isEmpty()
        ? null : couponCode.trim().toUpperCase(Locale.ROOT);
  }

  private void append(StringBuilder target,String value) {
    if (value == null) {
      target.append("-1:");
      return;
    }
    target.append(value.length()).append(':').append(value);
  }
}
