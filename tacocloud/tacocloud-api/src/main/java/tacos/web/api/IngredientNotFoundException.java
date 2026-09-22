package tacos.web.api;

public class IngredientNotFoundException
    extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String ingredientId;

  public IngredientNotFoundException(String ingredientId) {

    super("Ingredient not found: " + ingredientId);

    this.ingredientId =ingredientId;
  }

  public String getIngredientId() {
    return ingredientId;
  }
}