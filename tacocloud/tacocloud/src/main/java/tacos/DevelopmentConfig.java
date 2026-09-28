package tacos;

import java.math.BigDecimal;
import java.util.Arrays;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import tacos.Ingredient.Type;
import tacos.data.IngredientRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;

@Profile("!prod")
@Configuration
public class DevelopmentConfig {

  @Bean
  public CommandLineRunner dataLoader(IngredientRepository repo,
        UserRepository userRepo, PasswordEncoder encoder, TacoRepository tacoRepo,
        PaymentMethodRepository paymentMethodRepo) { // user repo for ease of testing with a built-in user
    
    return new CommandLineRunner() {
      @Override
      public void run(String... args) throws Exception {
        Ingredient flourTortilla = saveAnIngredient("FLTO", "Flour Tortilla", Type.WRAP,"0.75",120,20);
        Ingredient cornTortilla = saveAnIngredient("COTO", "Corn Tortilla", Type.WRAP,"0.65",120,20);
        Ingredient groundBeef = saveAnIngredient("GRBF", "Ground Beef", Type.PROTEIN,"2.25",80,15);
        Ingredient carnitas = saveAnIngredient("CARN", "Carnitas", Type.PROTEIN,"2.40",70,15);
        Ingredient tomatoes = saveAnIngredient("TMTO", "Diced Tomatoes", Type.VEGGIES,"0.45",90,15);
        Ingredient lettuce = saveAnIngredient("LETC", "Lettuce", Type.VEGGIES,"0.40",90,15);
        Ingredient cheddar = saveAnIngredient("CHED", "Cheddar", Type.CHEESE,"0.85",75,10);
        Ingredient jack = saveAnIngredient("JACK", "Monterrey Jack", Type.CHEESE,"0.90",75,10);
        Ingredient salsa = saveAnIngredient("SLSA", "Salsa", Type.SAUCE,"0.55",100,15);
        Ingredient sourCream = saveAnIngredient("SRCR", "Sour Cream", Type.SAUCE,"0.60",85,15);
        
//        UserUDT u = new UserUDT(username, fullname, phoneNumber)
        
        userRepo.save(new User("habuma", encoder.encode("password"), 
              "Craig Walls", "123 North Street", "Cross Roads", "TX", 
              "76227", "123-123-1234", "craig@habuma.com"))
          .subscribe(user -> {
              PaymentMethod paymentMethod = new PaymentMethod(user);
              paymentMethod.setId(user.getId());
              paymentMethod.setPaymentToken("tok_fake_development");
              paymentMethod.setBrand("VISA");
              paymentMethod.setLast4("1111");
              paymentMethod.setExpiration("12/30");
              paymentMethodRepo.save(paymentMethod).subscribe();
          });        
        
        Taco taco1 = new Taco();
        taco1.setId("TACO1");
        taco1.setName("Carnivore");
        taco1.setIngredients(Arrays.asList(flourTortilla, groundBeef, carnitas, sourCream, salsa, cheddar));
        tacoRepo.save(taco1).subscribe();

        Taco taco2 = new Taco();
        taco2.setId("TACO2");
        taco2.setName("Bovine Bounty");
        taco2.setIngredients(Arrays.asList(cornTortilla, groundBeef, cheddar, jack, sourCream));
        tacoRepo.save(taco2).subscribe();

        Taco taco3 = new Taco();
        taco3.setId("TACO3");
        taco3.setName("Veg-Out");
        taco3.setIngredients(Arrays.asList(flourTortilla, cornTortilla, tomatoes, lettuce, salsa));
        tacoRepo.save(taco3).subscribe();

      }

      private Ingredient saveAnIngredient(String id,String name,Type type,
          String unitPrice,int stockOnHand,int reorderLevel) {
        Ingredient ingredient = new Ingredient(
            id,name,type,new BigDecimal(unitPrice),true,stockOnHand,reorderLevel);
        repo.save(ingredient).subscribe();
        return ingredient;
      }
    };
  }
  
}
