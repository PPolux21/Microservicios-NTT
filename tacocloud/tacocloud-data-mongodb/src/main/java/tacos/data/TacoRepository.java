package tacos.data;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Flux;
import tacos.Taco;


public interface TacoRepository 
         extends ReactiveCrudRepository<Taco, String>, TacoSearchRepository {

  Flux<Taco> findAllByOrderByIdAsc();

}
