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

}
