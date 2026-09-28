export class CartItem {

  quantity = 1;

  taco: any;

  constructor(taco: any) {
    this.taco = taco;
  }

  get unitPrice() {
    if (!this.taco || !this.taco.ingredients) {
      return 0;
    }

    return this.taco.ingredients.reduce(
      (total, ingredient) => total + Number(ingredient.unitPrice || 0), 0);
  }

  get lineTotal() {
    return Number(this.quantity) * this.unitPrice;
  }

}
