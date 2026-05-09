# TO-DO

- [x] Keycloak configuration. There is not a client per microservice defined. Only generic ones. Align implementation with architecture
- [x] Review Springboot 4.0 fully declarative clients
      Current implementation not using @ImportHttpServices to declarative configure OAuth
      Ref: https://stevenpg.com/posts/ultimate-guide-spring-web-clients-oauth2/
- [x] External vs Internal Envoy-Gateway
      Changing frontend design to BFF pattern, enabling not exposing API services outside Kubernetes cluster. A dedicated internal envoy-gateway can be used as API Gateway for centrallized the calls from front-end to all backend services
- [ ] Improve Obserbability 
- [ ] Add Kafka authentication SASL/SCRAM
