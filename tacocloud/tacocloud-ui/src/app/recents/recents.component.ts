import { Component, OnInit, Injectable } from '@angular/core';
import { RecentTacosService } from './RecentTacosService';

@Component({
  selector: 'recent-tacos',
  templateUrl: 'recents.component.html',
  styleUrls: ['./recents.component.css']
})

@Injectable()
export class RecentTacosComponent implements OnInit {
  recentTacos: any[] = [];
  favoriteTacos: any[] = [];
  favoriteIds: {[id: string]: boolean} = {};
  topTacos: any[] = [];
  myOrders: any[] = [];
  selectedOrder: any = null;
  ratingScores: number[] = [1, 2, 3, 4, 5];

  constructor(private recentTacosService: RecentTacosService) { }

  ngOnInit() {
    this.recentTacosService.getRecentTacos()
        .subscribe(page => this.recentTacos = page.items);
    this.reloadFavorites();
    this.reloadTopTacos();
    this.reloadMyOrders();
  }

  reloadFavorites() {
    this.recentTacosService.getFavorites()
        .subscribe(page => {
          this.favoriteTacos = page.items;
          this.favoriteIds = {};
          page.items.forEach(taco => this.favoriteIds[taco.id] = true);
        });
  }

  isFavorite(tacoId: string) {
    return !!this.favoriteIds[tacoId];
  }

  toggleFavorite(taco: any) {
    const operation = this.isFavorite(taco.id)
        ? this.recentTacosService.removeFavorite(taco.id)
        : this.recentTacosService.addFavorite(taco.id);
    operation.subscribe(() => this.reloadFavorites());
  }

  rateTaco(taco: any, score: number) {
    this.recentTacosService.rateTaco(taco.id, score)
        .subscribe(() => this.reloadTopTacos());
  }

  reloadTopTacos() {
    this.recentTacosService.getTopTacos()
        .subscribe(ratings => this.topTacos = ratings);
  }

  reloadMyOrders() {
    this.recentTacosService.getMyOrders()
        .subscribe(page => this.myOrders = page.items);
  }

  showOrder(orderId: string) {
    this.recentTacosService.getMyOrder(orderId)
        .subscribe(order => this.selectedOrder = order);
  }
}
