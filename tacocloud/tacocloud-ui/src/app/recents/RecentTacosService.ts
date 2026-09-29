import { Injectable } from '@angular/core';
import { ApiService } from '../api/ApiService';
import 'rxjs/add/operator/map';

@Injectable()
export class RecentTacosService {

  constructor(private apiService: ApiService) {
  }

  search(params: any = {}) {
    const query = Object.keys(params)
        .filter(key => params[key] !== null && params[key] !== undefined && params[key] !== '')
        .map(key => encodeURIComponent(key) + '=' + encodeURIComponent(params[key]))
        .join('&');
    const path = '/api/tacos' + (query ? '?' + query : '');
    return this.apiService.get(path).map(response => response.json());
  }

  getRecentTacos() {
    return this.search({page: 0, size: 12, sort: 'createdAt,desc'});
  }

  getFavorites(page: number = 0, size: number = 50) {
    return this.apiService
        .get('/api/users/me/favorites?page=' + page + '&size=' + size)
        .map(response => response.json());
  }

  addFavorite(tacoId: string) {
    return this.apiService.put(
        '/api/users/me/favorites/' + encodeURIComponent(tacoId), {});
  }

  removeFavorite(tacoId: string) {
    return this.apiService.delete(
        '/api/users/me/favorites/' + encodeURIComponent(tacoId));
  }

  rateTaco(tacoId: string, score: number) {
    return this.apiService.put(
        '/api/tacos/' + encodeURIComponent(tacoId) + '/rating',
        {score: score});
  }

  getTopTacos(limit: number = 10) {
    return this.apiService
        .get('/api/tacos/top?limit=' + limit)
        .map(response => response.json());
  }

}
