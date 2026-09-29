package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Allergen;
import tacos.Ingredient.DietaryTag;
import tacos.Ingredient.SpiceLevel;
import tacos.Ingredient.Type;
import tacos.Taco;
import tacos.data.TacoRepository;
import tacos.data.TacoSearchRepository.TacoSearchPage;
import tacos.data.TacoSearchRepository.TacoSearchQuery;

@SpringBootTest(
    classes=TacoSearchMongoIntegrationTest.TestApplication.class,
    webEnvironment=SpringBootTest.WebEnvironment.NONE,
    properties={
      "spring.data.mongodb.database=tc19_taco_search_test",
      "spring.data.mongodb.auto-index-creation=true"
    })
public class TacoSearchMongoIntegrationTest {

  @Autowired
  private TacoRepository repository;

  @Autowired
  private ReactiveMongoTemplate mongo;

  @Test
  public void shouldApplyEveryFilterInMongoAndEscapeRegexText() {
    Mono<List<TacoSearchPage>> scenario = cleanAndSeed()
        .then(Mono.zip(
            repository.search(query("fire",null,null,null,null,0,20,
                "id",false)),
            repository.search(query(null,"JALA",null,null,null,0,20,
                "id",false)),
            repository.search(query(null,null,DietaryTag.VEGAN,null,null,
                0,20,"id",false)),
            repository.search(query(null,null,null,Allergen.DAIRY,null,
                0,20,"id",false)),
            repository.search(query(null,null,null,null,SpiceLevel.HOT,
                0,20,"id",false)),
            repository.search(query(".*",null,null,null,null,0,20,
                "id",false))))
        .map(tuple -> Arrays.asList(
            tuple.getT1(),tuple.getT2(),tuple.getT3(),tuple.getT4(),
            tuple.getT5(),tuple.getT6()));

    StepVerifier.create(scenario)
        .assertNext(pages -> {
          assertEquals(Arrays.asList("A","C"),ids(pages.get(0)));
          assertEquals(Arrays.asList("A","C"),ids(pages.get(1)));
          assertEquals(Arrays.asList("A","B"),ids(pages.get(2)));
          assertEquals(Arrays.asList("A","B","C","E"),ids(pages.get(3)));
          assertEquals(Arrays.asList("A","C"),ids(pages.get(4)));
          assertEquals(Arrays.asList("E"),ids(pages.get(5)));
        })
        .verifyComplete();
  }

  @Test
  public void shouldIntersectCombinedFilters() {
    TacoSearchQuery combined = query(
        "fire","JALA",DietaryTag.VEGAN,Allergen.DAIRY,
        SpiceLevel.HOT,0,20,"id",false);

    StepVerifier.create(cleanAndSeed().then(repository.search(combined)))
        .assertNext(page -> assertEquals(Arrays.asList("A"),ids(page)))
        .verifyComplete();
  }

  @Test
  public void shouldPageWithStableIdTieBreakerAndReturnMetadata() {
    TacoSearchQuery first = query(
        null,null,null,null,null,0,2,"createdAt",true);
    TacoSearchQuery second = query(
        null,null,null,null,null,1,2,"createdAt",true);

    StepVerifier.create(cleanAndSeed().then(Mono.zip(
        repository.search(first),repository.search(second))))
        .assertNext(pages -> {
          assertEquals(Arrays.asList("A","B"),ids(pages.getT1()));
          assertEquals(Arrays.asList("C","D"),ids(pages.getT2()));
          assertEquals(0,pages.getT1().getPage());
          assertEquals(2,pages.getT1().getSize());
          assertEquals(5,pages.getT1().getTotalElements());
          assertEquals(3,pages.getT1().getTotalPages());
        })
        .verifyComplete();
  }

  @Test
  public void shouldReturnFirstPageForEmptyQueryAndSortAllowedField() {
    TacoSearchQuery empty = query(
        null,null,null,null,null,0,20,"name",false);

    StepVerifier.create(cleanAndSeed().then(repository.search(empty)))
        .assertNext(page -> {
          assertEquals(Arrays.asList("D","C","A","E","B"),ids(page));
          assertEquals(5,page.getTotalElements());
          assertEquals(1,page.getTotalPages());
        })
        .verifyComplete();
  }

  @Test
  public void shouldCreateOnlyRelevantSearchIndexes() {
    StepVerifier.create(cleanAndSeed()
        .then(repository.search(query(
            null,null,null,null,null,0,1,"id",false)))
        .thenMany(
        mongo.indexOps(Taco.class).getIndexInfo()
            .map(IndexInfo::getName).collectList()))
        .assertNext(names -> {
          assertTrue(names.contains("_id_"));
          assertTrue(names.contains("taco_name_idx"));
          assertTrue(names.contains("taco_ingredient_idx"));
          assertTrue(names.contains("taco_diet_idx"));
          assertTrue(names.contains("taco_allergen_idx"));
          assertTrue(names.contains("taco_spice_idx"));
          assertTrue(names.contains("taco_created_idx"));
          assertEquals(7,names.size());
        })
        .verifyComplete();
  }

  private Mono<Void> cleanAndSeed() {
    Date sameCreationTime = Date.from(Instant.parse("2026-01-01T00:00:00Z"));
    List<Taco> tacos = Arrays.asList(
        taco("A","Fire Vegan",sameCreationTime,corn(),jalapeno()),
        taco("B","Mild Vegan",sameCreationTime,corn(),mildPlant()),
        taco("C","Fire Beef",sameCreationTime,corn(),jalapeno(),beef()),
        taco("D","Dairy Delight",sameCreationTime,corn(),cheese()),
        taco("E","Literal .* Taco",sameCreationTime,corn(),beef()));
    return mongo.remove(new Query(),Taco.class)
        .thenMany(Flux.fromIterable(tacos).concatMap(mongo::save))
        .then();
  }

  private TacoSearchQuery query(String name,String ingredientId,
      DietaryTag diet,Allergen excludeAllergen,SpiceLevel spice,
      int page,int size,String sortField,boolean descending) {
    return new TacoSearchQuery(
        name,ingredientId,diet,excludeAllergen,spice,
        page,size,sortField,descending);
  }

  private List<String> ids(TacoSearchPage page) {
    return page.getItems().stream().map(Taco::getId)
        .collect(Collectors.toList());
  }

  private Taco taco(String id,String name,Date createdAt,
      Ingredient... ingredients) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName(name);
    taco.setCreatedAt(createdAt);
    taco.setIngredients(Arrays.asList(ingredients));
    return taco;
  }

  private Ingredient corn() {
    return ingredient("CORN",Type.WRAP,allTags(),noAllergens(),SpiceLevel.NONE);
  }

  private Ingredient jalapeno() {
    return ingredient("JALA",Type.SAUCE,allTags(),noAllergens(),SpiceLevel.HOT);
  }

  private Ingredient mildPlant() {
    return ingredient("MILD",Type.SAUCE,allTags(),noAllergens(),SpiceLevel.MILD);
  }

  private Ingredient beef() {
    return ingredient("BEEF",Type.PROTEIN,
        EnumSet.noneOf(DietaryTag.class),noAllergens(),SpiceLevel.MILD);
  }

  private Ingredient cheese() {
    return ingredient("CHEE",Type.CHEESE,
        EnumSet.of(DietaryTag.VEGETARIAN,DietaryTag.GLUTEN_FREE),
        EnumSet.of(Allergen.DAIRY),SpiceLevel.NONE);
  }

  private Ingredient ingredient(String id,Type type,Set<DietaryTag> tags,
      Set<Allergen> allergens,SpiceLevel spice) {
    Ingredient ingredient = new Ingredient(
        id,id,type,new BigDecimal("1.00"),true,10,2);
    ingredient.setDietaryTags(tags);
    ingredient.setAllergens(allergens);
    ingredient.setSpiceLevel(spice);
    return ingredient;
  }

  private Set<DietaryTag> allTags() {
    return EnumSet.allOf(DietaryTag.class);
  }

  private Set<Allergen> noAllergens() {
    return EnumSet.noneOf(Allergen.class);
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EnableReactiveMongoRepositories(basePackageClasses=TacoRepository.class)
  static class TestApplication {
  }
}
