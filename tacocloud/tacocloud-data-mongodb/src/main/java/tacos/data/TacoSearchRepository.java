package tacos.data;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import reactor.core.publisher.Mono;
import tacos.Ingredient.Allergen;
import tacos.Ingredient.DietaryTag;
import tacos.Ingredient.SpiceLevel;
import tacos.Taco;

public interface TacoSearchRepository {

  Mono<TacoSearchPage> search(TacoSearchQuery query);

  @Data
  @AllArgsConstructor
  class TacoSearchQuery {
    private String name;
    private String ingredientId;
    private DietaryTag diet;
    private Allergen excludeAllergen;
    private SpiceLevel spice;
    private int page;
    private int size;
    private String sortField;
    private boolean sortDescending;
  }

  @Data
  @AllArgsConstructor
  class TacoSearchPage {
    private List<Taco> items;
    private int page;
    private int size;
    private long totalElements;

    public int getTotalPages() {
      return size == 0
          ? 0
          : (int) ((totalElements + size - 1) / size);
    }
  }
}
