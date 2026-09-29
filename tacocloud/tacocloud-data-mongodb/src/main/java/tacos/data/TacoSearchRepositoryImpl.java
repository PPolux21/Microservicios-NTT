package tacos.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient.SpiceLevel;
import tacos.Taco;
import tacos.data.TacoSearchRepository.TacoSearchPage;
import tacos.data.TacoSearchRepository.TacoSearchQuery;

public class TacoSearchRepositoryImpl implements TacoSearchRepository {

  private final ReactiveMongoTemplate mongoTemplate;
  private final Mono<Void> indexesReady;

  public TacoSearchRepositoryImpl(ReactiveMongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
    this.indexesReady = Flux.concat(
        ensureIndex("name",Sort.Direction.ASC,"taco_name_idx"),
        ensureIndex("ingredients.id",Sort.Direction.ASC,"taco_ingredient_idx"),
        ensureIndex("ingredients.dietaryTags",Sort.Direction.ASC,"taco_diet_idx"),
        ensureIndex("ingredients.allergens",Sort.Direction.ASC,"taco_allergen_idx"),
        ensureIndex("ingredients.spiceLevel",Sort.Direction.ASC,"taco_spice_idx"),
        ensureIndex("createdAt",Sort.Direction.DESC,"taco_created_idx"))
        .then()
        .cache();
  }

  @Override
  public Mono<TacoSearchPage> search(TacoSearchQuery search) {
    Criteria criteria = combinedCriteria(search);
    Query countQuery = criteria != null
        ? Query.query(criteria)
        : new Query();
    Query pageQuery = criteria != null
        ? Query.query(criteria)
        : new Query();

    Sort.Direction direction = search.isSortDescending()
        ? Sort.Direction.DESC
        : Sort.Direction.ASC;
    pageQuery.with(Sort.by(direction,search.getSortField()));
    if (!"id".equals(search.getSortField())) {
      pageQuery.with(Sort.by(Sort.Direction.ASC,"id"));
    }
    pageQuery.skip((long) search.getPage() * search.getSize())
        .limit(search.getSize());

    Mono<List<Taco>> items = mongoTemplate.find(pageQuery,Taco.class)
        .collectList();
    Mono<Long> total = mongoTemplate.count(countQuery,Taco.class);
    return indexesReady.then(Mono.zip(items,total)
        .map(result -> new TacoSearchPage(
            result.getT1(),search.getPage(),search.getSize(),result.getT2())));
  }

  private Mono<String> ensureIndex(String field,Sort.Direction direction,
      String name) {
    return mongoTemplate.indexOps(Taco.class)
        .ensureIndex(new Index().on(field,direction).named(name));
  }

  private Criteria combinedCriteria(TacoSearchQuery search) {
    List<Criteria> criteria = new ArrayList<>();
    if (search.getName() != null) {
      Pattern safeName = Pattern.compile(
          Pattern.quote(search.getName()),
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
      criteria.add(Criteria.where("name").regex(safeName));
    }
    if (search.getIngredientId() != null) {
      criteria.add(Criteria.where("ingredients.id")
          .is(search.getIngredientId()));
    }
    if (search.getDiet() != null) {
      criteria.add(Criteria.where("ingredients").not().elemMatch(
          Criteria.where("dietaryTags").ne(search.getDiet())));
    }
    if (search.getExcludeAllergen() != null) {
      criteria.add(Criteria.where("ingredients").not().elemMatch(
          Criteria.where("allergens").is(search.getExcludeAllergen())));
    }
    if (search.getSpice() != null) {
      criteria.add(Criteria.where("ingredients.spiceLevel")
          .is(search.getSpice()));
      List<SpiceLevel> hotter = Arrays.stream(SpiceLevel.values())
          .filter(level -> level.ordinal() > search.getSpice().ordinal())
          .collect(Collectors.toList());
      if (!hotter.isEmpty()) {
        criteria.add(Criteria.where("ingredients").not().elemMatch(
            Criteria.where("spiceLevel").in(hotter)));
      }
    }
    if (criteria.isEmpty()) {
      return null;
    }
    return new Criteria().andOperator(criteria.toArray(new Criteria[0]));
  }
}
