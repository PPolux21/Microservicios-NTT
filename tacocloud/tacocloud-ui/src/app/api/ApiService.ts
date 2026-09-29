import { Injectable } from '@angular/core';
import { Http } from '@angular/http';

@Injectable()
export class ApiService {

  constructor(private http: Http) {
  }

  get(path: String) {
    return this.http.get('http://localhost:8080' + path);
  }

  put(path: String, body: any) {
    return this.http.put('http://localhost:8080' + path, body);
  }

  delete(path: String) {
    return this.http.delete('http://localhost:8080' + path);
  }

}
