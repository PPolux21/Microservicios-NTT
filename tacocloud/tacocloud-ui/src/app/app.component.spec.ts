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
});
