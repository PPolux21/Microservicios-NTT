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
