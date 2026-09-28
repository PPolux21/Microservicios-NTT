import { Component, OnInit, Injectable } from '@angular/core';
import { CartService } from './cart-service';
import { HttpClient, HttpHeaders } from '@angular/common/http';

@Component({
  selector: 'taco-cart',
  templateUrl: 'cart.component.html',
  styleUrls: ['./cart.component.css']
})

@Injectable()
export class CartComponent implements OnInit {

  model = {
    deliveryName: '',
    deliveryStreet: '',
    deliveryCity: '',
    deliveryState: '',
    deliveryZip: '',
    cardNumber: '',
    expiration: '',
    cvv: ''
  };

  constructor(private cart: CartService, private httpClient: HttpClient) {
    this.cart = cart;
  }

  ngOnInit() {}

  get cartItems() {
    return this.cart.getItemsInCart();
  }

  get cartTotal() {
    return this.cart.getCartTotal();
  }

  onSubmit() {
    const tokenizationRequest = {
      cardNumber: this.model.cardNumber,
      expiration: this.model.expiration,
      cvv: this.model.cvv
    };

    this.httpClient.post<any>(
        'http://localhost:8080/api/payment-methods/tokenize',
        tokenizationRequest, {
            headers: new HttpHeaders().set('Content-type', 'application/json')
                    .set('Accept', 'application/json'),
        }).subscribe(paymentMethod => {
          const items = this.cart.getItemsInCart()
            .filter(cartItem => Number(cartItem.quantity) > 0)
            .map(cartItem => ({
              taco: {
                name: cartItem.taco.name,
                ingredientIds: cartItem.taco.ingredients
                  .map(ingredient => ingredient.id)
              },
              quantity: Number(cartItem.quantity)
            }));

          const orderRequest = {
            deliveryName: this.model.deliveryName,
            deliveryStreet: this.model.deliveryStreet,
            deliveryCity: this.model.deliveryCity,
            deliveryState: this.model.deliveryState,
            deliveryZip: this.model.deliveryZip,
            paymentMethodId: paymentMethod.id,
            items: items
          };

          this.model.cardNumber = '';
          this.model.cvv = '';

          this.httpClient.post(
              'http://localhost:8080/api/orders',
              orderRequest, {
                headers: new HttpHeaders().set('Content-type', 'application/json')
                        .set('Accept', 'application/json'),
              }).subscribe(r => this.cart.emptyCart());
        });

    // TODO: Do something after this...navigate to a thank you page or something
  }

}
