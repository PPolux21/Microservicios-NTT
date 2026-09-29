package tacos.web.api;

import java.util.List;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.web.api.dto.ApiDtos.RatingRequest;
import tacos.web.api.dto.ApiDtos.TopTacoRatingResponse;
import tacos.web.api.error.ApiExceptionHandler.ApiException;
import tacos.web.api.mapper.ApiMapper;

@RestController
@RequestMapping(path="/api/tacos",produces="application/json")
public class TacoRatingController {

  private final TacoRatingService ratingService;
  private final int maxTopLimit;

  public TacoRatingController(TacoRatingService ratingService,
      @Value("${tacocloud.rating.max-top-limit}") int maxTopLimit) {
    this.ratingService = ratingService;
    this.maxTopLimit = maxTopLimit;
  }

  @PutMapping(path="/{id}/rating",consumes="application/json")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> rate(@PathVariable String id,
      @Valid @RequestBody RatingRequest request,
      Authentication authentication) {
    return ratingService.rate(id,request.getScore(),authentication);
  }

  @GetMapping("/top")
  public Mono<List<TopTacoRatingResponse>> top(
      @RequestParam(defaultValue="10") int limit) {
    if (limit < 1 || limit > maxTopLimit) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,"INVALID_LIMIT",
          "limit must be between 1 and " + maxTopLimit + ".");
    }
    return ratingService.top(limit).map(ApiMapper::toRatingResponses);
  }
}
