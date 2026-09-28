/*
 * TC-12
 *
 * Elimina campos sensibles heredados de todas las colecciones del laboratorio.
 * Ejecutar con mongosh sobre la base de datos que se desea limpiar.
 */

const sensitiveFields = {
  ccNumber: "",
  ccCVV: "",
  ccExpiration: ""
};

db.getCollectionNames().forEach(collectionName => {
  const collection = db.getCollection(collectionName);
  const query = {
    $or: [
      { ccNumber: { $exists: true } },
      { ccCVV: { $exists: true } },
      { ccExpiration: { $exists: true } }
    ]
  };
  const count = collection.countDocuments(query);

  if (count > 0) {
    print("TC-12: cleaning " + count + " document(s) from " + collectionName);
    collection.updateMany(query, { $unset: sensitiveFields });
  }
});

print("TC-12 migration completed.");
