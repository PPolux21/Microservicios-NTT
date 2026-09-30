# Microservicios-NTT

## TacoCloud HTTP API

La base recomendada para los contratos HTTP públicos es `/api/v1`. El contrato
revisable se encuentra en [`docs/openapi.yaml`](docs/openapi.yaml).

Las rutas equivalentes bajo `/api` se mantienen temporalmente como aliases y
responden con `Deprecation: true` y un `Link` hacia la ruta sucesora. Los
clientes mantenidos (Angular y cocina) usan `/api/v1`. El borde de tokenización
`/api/payment-methods/tokenize` permanece fuera del contrato público v1 porque
recibe datos efímeros de tarjeta; sólo su respuesta segura se usa para obtener
el `paymentMethodId` que acepta la API de órdenes.

## Suite de regresión

La suite completa requiere JDK 11, Maven y un motor compatible con Docker. Mongo
replica set y RabbitMQ son iniciados de forma efímera por Testcontainers; no se
requieren servicios instalados ni datos locales.

Desde la raíz del repositorio:

```shell
cd tacocloud
mvn clean verify
```

Subconjuntos útiles, ejecutados desde `tacocloud/`:

```shell
# Runtime HTTP real + Mongo
mvn -pl tacocloud -am -Dtest=TacoCloudApplicationTests -Dsurefire.failIfNoSpecifiedTests=false test

# Outbox e idempotencia con Mongo replica set
mvn -pl tacocloud-api -am -Dtest=OutboxMongoIntegrationTest,OrderIdempotencyMongoIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test

# Consumidor, redelivery, retry y DLQ con RabbitMQ real
mvn -pl tacocloud-kitchen -am -Dtest=RabbitOrderConsumerIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test

# Contratos JSON y OpenAPI
mvn -pl tacocloud-messaging-contract,tacocloud-api -am -Dtest=OrderEventContractTest,DtoContractTest,OpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Los resultados JUnit quedan en `*/target/surefire-reports/` (y, si se
incorporan pruebas Failsafe, en `*/target/failsafe-reports/`). El workflow de
GitHub Actions ejecuta el mismo `clean verify` y conserva estos reportes aunque
una prueba falle.
