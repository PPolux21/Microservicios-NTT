import { APP_BASE_HREF } from '@angular/common';
import { TestBed, async } from '@angular/core/testing';

import { BrowserModule } from '@angular/platform-browser';
import { HttpModule } from '@angular/http';
import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { FormsModule } from '@angular/forms';

import { AppComponent } from './app.component';
import { HeaderComponent } from './header/header.component';
import { FooterComponent } from './footer/footer.component';
import { HomeComponent } from './home/home.component';
import { LoginComponent } from './login/login.component';
import { RecentTacosComponent } from './recents/recents.component';
import { ApiService } from './api/ApiService';
import { RecentTacosService } from './recents/RecentTacosService';
import { SpecialsComponent } from './specials/specials.component';
import { CloudTitleComponent } from './cloud-title/cloudtitle.component';
import { NonWrapsPipe } from './recents/NonWrapsPipe';
import { WrapsPipe } from './recents/WrapsPipe';
import { DesignComponent } from './design/design.component';
import { GroupBoxComponent } from './group-box/groupbox.component';
import { BigButtonComponent } from './big-button/bigbutton.component';
import { LittleButtonComponent } from './little-button/littlebutton.component';
import { LocationsComponent } from './locations/locations.component';
import { FormGroupDirective } from '@angular/forms/src/directives/reactive_directives/form_group_directive';
import { HttpClientModule } from '@angular/common/http';
import { Observable } from 'rxjs/Observable';
import 'rxjs/add/observable/of';

import { CartComponent } from './cart/cart.component';
import { CartService } from './cart/cart-service';

import { routes } from './app.routes';

describe('AppComponent', () => {
  beforeEach(async(() => {
    TestBed.configureTestingModule({
      declarations: [
        AppComponent,
        HeaderComponent,
        HomeComponent,
        LoginComponent,
        FooterComponent,
        RecentTacosComponent,
        SpecialsComponent,
        LocationsComponent,
        CloudTitleComponent,
        DesignComponent,
        CartComponent,
        NonWrapsPipe,
        WrapsPipe,
        GroupBoxComponent,
        BigButtonComponent,
        LittleButtonComponent,
      ],
      imports: [
        RouterModule.forRoot(routes),
        BrowserModule,
        HttpModule,
        HttpClientModule,
        FormsModule,
      ],
      providers: [
        {provide: APP_BASE_HREF, useValue: '/'},
        ApiService,
        CartService,
        RecentTacosService,
      ]
    }).compileComponents();
  }));
  it('should create the app', async(() => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.debugElement.componentInstance;
    expect(app).toBeTruthy();
  }));
  it(`should have as title 'app'`, async(() => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.debugElement.componentInstance;
    expect(app.title).toEqual('Taco Cloud');
  }));

  it('should preserve and send a cart quantity greater than one', () => {
    const cart = new CartService();
    cart.addToCart({
      name: 'Quantity Taco',
      ingredients: [
        {id: 'FLTO', unitPrice: 0.75},
        {id: 'CHED', unitPrice: 0.85}
      ]
    });
    cart.getItemsInCart()[0].quantity = 2;

    const requests: any[] = [];
    const httpClient: any = {
      post: (url: string, body: any, options: any) => {
        requests.push({url: url, body: body});
        return {
          subscribe: callback => callback(
            url.indexOf('/tokenize') >= 0 ? {id: 'PAYMENT-1'} : {})
        };
      }
    };

    const component = new CartComponent(cart,httpClient);
    component.model.cardNumber = '4111111111111111';
    component.model.expiration = '12/30';
    component.model.cvv = '123';
    component.onSubmit();

    expect(requests.length).toBe(2);
    expect(requests[1].url).toContain('/api/orders');
    expect(requests[1].body.items[0].quantity).toBe(2);
    expect(requests[1].body.items[0].taco.ingredientIds)
      .toEqual(['FLTO','CHED']);
    expect(requests[1].body.total).toBeUndefined();
    expect(requests[1].body.items[0].subtotal).toBeUndefined();
    expect(requests[1].body.items[0].unitPriceAtPurchase).toBeUndefined();
  });

  it('should request the paged taco API instead of the legacy recent route', () => {
    let requestedPath: string;
    const apiService: any = {
      get: (path: string) => {
        requestedPath = path;
        return Observable.of({json: () => ({items: []})});
      }
    };
    const service = new RecentTacosService(apiService);

    service.getRecentTacos().subscribe(page => expect(page.items).toEqual([]));

    expect(requestedPath)
      .toBe('/api/tacos?page=0&size=12&sort=createdAt%2Cdesc');
    expect(requestedPath).not.toContain('/tacos?recent');
  });

  it('should use me routes without sending a userId for favorites', () => {
    const requests: any[] = [];
    const apiService: any = {
      get: (path: string) => {
        requests.push({method: 'GET', path: path});
        return Observable.of({json: () => ({items: [{id: 'TACO-1'}]})});
      },
      put: (path: string, body: any) => {
        requests.push({method: 'PUT', path: path, body: body});
        return Observable.of({});
      },
      delete: (path: string) => {
        requests.push({method: 'DELETE', path: path});
        return Observable.of({});
      }
    };
    const service = new RecentTacosService(apiService);

    service.getFavorites().subscribe(page => expect(page.items.length).toBe(1));
    service.addFavorite('TACO-1').subscribe();
    service.removeFavorite('TACO-1').subscribe();

    expect(requests[0].path)
      .toBe('/api/users/me/favorites?page=0&size=50');
    expect(requests[1])
      .toEqual({method: 'PUT', path: '/api/users/me/favorites/TACO-1', body: {}});
    expect(requests[2].path).toBe('/api/users/me/favorites/TACO-1');
    expect(JSON.stringify(requests)).not.toContain('userId');
  });

  it('should restore favorite state from backend after component reload', () => {
    const service: any = {
      getRecentTacos: () => Observable.of({items: [{id: 'TACO-1'}]}),
      getFavorites: () => Observable.of({items: [{id: 'TACO-1'}]}),
      getTopTacos: () => Observable.of([]),
      getMyOrders: () => Observable.of({items: []})
    };
    const component = new RecentTacosComponent(service);

    component.ngOnInit();

    expect(component.isFavorite('TACO-1')).toBe(true);
    expect(component.favoriteTacos[0].id).toBe('TACO-1');
  });

  it('should load private order history and detail without sending userId', () => {
    const requests: string[] = [];
    const apiService: any = {
      get: (path: string) => {
        requests.push(path);
        const body = path.indexOf('/ORDER-1') >= 0
          ? {id: 'ORDER-1', items: []}
          : {items: [{id: 'ORDER-1'}]};
        return Observable.of({json: () => body});
      }
    };
    const service = new RecentTacosService(apiService);

    service.getMyOrders().subscribe(page => expect(page.items[0].id).toBe('ORDER-1'));
    service.getMyOrder('ORDER-1').subscribe(order => expect(order.id).toBe('ORDER-1'));

    expect(requests[0]).toBe('/api/users/me/orders?page=0&size=10');
    expect(requests[1]).toBe('/api/users/me/orders/ORDER-1');
    expect(JSON.stringify(requests)).not.toContain('userId');
  });

  it('should rate with score only and reload aggregate ranking', () => {
    const requests: any[] = [];
    const apiService: any = {
      put: (path: string, body: any) => {
        requests.push({method: 'PUT', path: path, body: body});
        return Observable.of({});
      },
      get: (path: string) => {
        requests.push({method: 'GET', path: path});
        return Observable.of({json: () => ([{
          taco: {id: 'TACO-1'}, average: 4.50, count: 2
        }])});
      }
    };
    const service = new RecentTacosService(apiService);

    service.rateTaco('TACO-1', 4).subscribe();
    service.getTopTacos().subscribe(top => expect(top[0].count).toBe(2));

    expect(requests[0]).toEqual({
      method: 'PUT',
      path: '/api/tacos/TACO-1/rating',
      body: {score: 4}
    });
    expect(requests[1].path).toBe('/api/tacos/top?limit=10');
    expect(JSON.stringify(requests)).not.toContain('userId');
  });
});
