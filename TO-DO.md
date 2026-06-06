# TO-DO

- [x] Keycloak configuration. There is not a client per microservice defined. Only generic ones. Align implementation with architecture
- [x] Review Springboot 4.0 fully declarative clients
      Current implementation not using @ImportHttpServices to declarative configure OAuth
      Ref: https://stevenpg.com/posts/ultimate-guide-spring-web-clients-oauth2/
- [x] External vs Internal Envoy-Gateway
      Changing frontend design to BFF pattern, enabling not exposing API services outside Kubernetes cluster. A dedicated internal envoy-gateway can be used as API Gateway for centrallized the calls from front-end to all backend services
      ADR-015 disposes this approach, additional internal gateway is not needed since the BFF pattern is implemented and Istio will be used for service-to-service communication in production.
- [x] Improve Obserbability 
- [x] Add Kafka authentication SASL/SCRAM and ACLs
- [x] Add Fake External Secrets Operator to manage secrets in non-production environments, such as Staging and Local. This operator will simulate the behavior of External Secrets Operator without requiring integration with actual secret management systems.
- [ ] Add Kafka UI, such as Kafbat, to manage topics, consumers, producers, and monitor cluster health.
- [x] Review Observatility stack in Staging. lgtm-distributed helm chart is deprecated. 
      Need to install components separately.
      - Prometheus (Deployed with Kube-Prom-Stack helm, OLTP needs to be enabled)
      - Loki (mononlithic mode, not using microservices and without S3-compatible storage)
      - Grafana (Deployed with Kube-Prom-Stack helm for simplicity)
      - Tempo (monolithic, not using microservices and without S3-compatible storage)
- [x] Keycloak Open Telemetry integration
      Integrate Keycloak with OpenTelemetry to collect and export telemetry data for monitoring and observability purposes. This includes configuring Keycloak to emit telemetry data and setting up OpenTelemetry collectors to receive and process the data.
- [ ] Review user lazy registration implementation, aligning with ADR-005
      Replace lazy registration (Option A) with a Keycloak SPI EventListenerProvider (Option C) to guarantee profile existence before first API call.
- [x] Valkey Authentication and Authorization
      Implement Valkey authentication and authorization for API services, ensuring secure access to resources. This includes integrating Valkey with the existing authentication mechanisms and defining appropriate access control policies.
- [x] Upgrade to Valkey 9.0
      Review and upgrade to Valkey 9.0, ensuring compatibility with existing systems and taking advantage of new features and improvements.
- [ ] Valkey Operator for Kubernetes deployment

- [ ] Review CNPG configuration for release 1.30. Declarative roles capability is added in 1.30. See issue: https://github.com/cloudnative-pg/cloudnative-pg/issues/5341
