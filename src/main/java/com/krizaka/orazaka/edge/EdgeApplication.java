package com.krizaka.orazaka.edge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the Orazaka Edge Gateway — the strangler facade in front of the backend
 * constellation (VISION_ARCHITECTURE §8.1, MICROSERVICES_TARGET_ARCHITECTURE Phase 1).
 *
 * <p>Transport concerns only: route table (path prefix → backend) and streaming reverse proxy. No
 * business logic, no {@code Intention} translation — the router keeps that. Every later service
 * extraction is a route-table change here, never a client/BFF change.
 *
 * <p>Deliberately scans only {@code com.krizaka.orazaka.edge}: the edge must never wire the
 * framework libraries into its context (it fronts them over HTTP).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EdgeApplication {

  public static void main(String[] args) {
    SpringApplication.run(EdgeApplication.class, args);
  }
}
