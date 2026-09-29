import { Component, OnInit, Injectable } from '@angular/core';
import { RecentTacosService } from './RecentTacosService';

@Component({
  selector: 'recent-tacos',
  templateUrl: 'recents.component.html',
  styleUrls: ['./recents.component.css']
})

@Injectable()
export class RecentTacosComponent implements OnInit {
  recentTacos: any;

  constructor(private recentTacosService: RecentTacosService) { }

  ngOnInit() {
    this.recentTacosService.getRecentTacos()
        .subscribe(page => this.recentTacos = page.items);
  }
}
