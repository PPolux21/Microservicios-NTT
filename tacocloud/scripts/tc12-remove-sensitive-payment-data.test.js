/*
 * Ejecutar desde la raíz de tacocloud sobre una BD de prueba:
 *
 * mongosh taco-cloud-test scripts/tc12-remove-sensitive-payment-data.test.js
 */

const collection = db.getCollection("tc12MigrationTest");

collection.drop();
collection.insertOne({
  name: "synthetic",
  ccNumber: "TEST-PAN",
  ccCVV: "TEST-CVV",
  ccExpiration: "12/30"
});

load("scripts/tc12-remove-sensitive-payment-data.js");

const migrated = collection.findOne({ name: "synthetic" });

if (migrated.ccNumber !== undefined) {
  throw new Error("ccNumber was not removed");
}

if (migrated.ccCVV !== undefined) {
  throw new Error("ccCVV was not removed");
}

if (migrated.ccExpiration !== undefined) {
  throw new Error("ccExpiration was not removed");
}

print("TC-12 migration test PASSED");
collection.drop();
