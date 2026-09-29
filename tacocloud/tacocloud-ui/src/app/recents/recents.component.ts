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

  constructor(private recentTacosService: RecentTacosService) { }

  ngOnInit() {
    this.recentTacosService.getRecentTacos()
        .subscribe(page => this.recentTacos = page.items);
    this.reloadFavorites();
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
}
