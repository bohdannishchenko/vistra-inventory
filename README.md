Here's an updated version of the GitHub README that incorporates your implementation of the **Vistra Inventory Plugin**, which is a **Bókun to Bókun** integration:

---

# Bókun Inventory Service Plugin – Skeleton Implementation

This repository provides a skeleton implementation of a **Bókun Inventory Service plugin** in Java.

It serves as a solid starting point for developers aiming to implement their own inventory service plugin using Bókun's gRPC-based plugin infrastructure.

> 🔧 **Included Implementation:** This skeleton has been extended with a working example – the **Vistra Inventory Plugin**, a Bókun-to-Bókun plugin that demonstrates how to proxy inventory data between two Bókun accounts.

## ✅ Features

* Base structure for implementing Bókun Inventory Service plugins.
* Pre-configured with required dependencies for gRPC and protocol buffer integration.
* Built in Java 8.

## 📚 Documentation

Before diving in, please [read the official documentation](https://bokun.dev/implementing-inventory-service-plugin/rGmzgGe66zjtEQ8FUvS8dd/overview/ngZRtjHXMewiomPfARFDfZ) to understand the plugin interface and lifecycle.

## 🔌 Vistra Inventory Plugin

This repository now includes the **Vistra Inventory Plugin** – a working example of a **Bókun to Bókun plugin** that connects two separate Bókun instances.

It illustrates how to:

* Fetch products and inventory from a source Bókun account.
* Serve those as available inventory to a target account.
* Handle availability checks and booking ID mapping across systems.

## 🧰 Requirements

* Java 8
* Maven or Gradle (for building the project)
