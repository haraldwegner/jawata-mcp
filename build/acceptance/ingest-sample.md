# Catalogue ingest sample — read this before 187 rows are written
Generated 2026-08-30 from fork pin `22a34127d`, 187 patterns across 15 families.
**Why you are reading this.** These rows become recall entries. This loader has produced entries whose 'symptom' was really a section heading before, so a person reads a sample before anything is written in bulk.
**One pattern per family, largest family first.** For each: the *situation* is what recall matches on, the *principle* is what you see in a result, the *cause* is what separates two patterns that share a situation.
**Checked mechanically across all 187 first:** 0 heading-shaped summaries, 0 heading-shaped situations, 187/187 carry a family, 187/187 carry tags.

---

## Behavioral  (41 patterns)
**`acyclic-visitor`**
- **situation** — when a new operation must be added over an existing type hierarchy without modifying it and without every visitor depending on every type
- **principle** — The Acyclic Visitor pattern in Java decouples operations from an object hierarchy, providing a flexible design for various applications.
- **cause** — (none)
- **tags** — Decoupling, Extensibility, Interface, Object composition
- **entry point** — `com.iluwatar.acyclicvisitor.App`

## Structural  (35 patterns)
**`abstract-document`**
- **situation** — when objects of one family share some attributes and each type adds its own, and new properties must be addable without changing the classes that read them
- **principle** — The Abstract Document design pattern in Java is a crucial structural design pattern that provides a consistent way to handle hierarchical and tree-like data structures by defining a common interface for various document types.
- **cause** — (none)
- **tags** — Abstraction, Decoupling, Dynamic typing, Encapsulation, Extensibility, Polymorphism
- **entry point** — `com.iluwatar.abstractdocument.App`

## Architectural  (27 patterns)
**`backends-for-frontends`**
- **situation** — when several client types need different shapes of the same data and one shared API is being distorted to serve them all
- **principle** — Provide each client-side application (mobile, desktop, chatbot, and so on) with its own dedicated backend service, so every client gets an API shaped exactly for its own needs instead of sharing one general-purpose backend with every other client.
- **cause** — (none)
- **tags** — API design, Architecture, Client-server, Decoupling, Microservices
- **entry point** — `com.iluwatar.bff.App`

## Concurrency  (22 patterns)
**`active-object`**
- **situation** — when an object's methods must run on its own thread so callers are never blocked by its work
- **principle** — The Active Object pattern provides a reliable method for asynchronous processing in Java, ensuring responsive applications and efficient thread management.
- **cause** — (none)
- **tags** — Asynchronous, Decoupling, Messaging, Synchronization, Thread management
- **entry point** — `com.iluwatar.activeobject.App`

## Creational  (14 patterns)
**`abstract-factory`**
- **situation** — when a system must be configured with one of several families of related objects, and the families must not be mixed
- **principle** — The Abstract Factory pattern in Java provides an interface for creating families of related or dependent objects without specifying their concrete classes, enhancing modularity and flexibility in software design.
- **cause** — (none)
- **tags** — Abstraction, Decoupling, Gang of Four, Instantiation, Polymorphism
- **entry point** — `com.iluwatar.abstractfactory.App`

## Data access  (12 patterns)
**`metadata-mapping`**
- **situation** — when object-to-table mapping is hand-written per class and the same mapping code repeats for every one
- **principle** — Metadata Mapping Design Pattern is designed to manage the mapping between database records and Java objects in a way that keeps the database schema and object model decoupled and manageable.
- **cause** — (none)
- **tags** — Decoupling, Enterprise patterns, Object mapping, Persistence
- **entry point** — `com.iluwatar.metamapping.App`

## Functional  (8 patterns)
**`callback`**
- **situation** — when a routine must run code supplied by its caller at a point the caller cannot reach
- **principle** — The Java Callback Design Pattern is a piece of executable code passed as an argument to other code, which is expected to call back (execute) the argument at a convenient time.
- **cause** — (none)
- **tags** — Asynchronous, Decoupling, Idiom, Reactive
- **entry point** — `com.iluwatar.callback.App`

## Integration  (6 patterns)
**`ambassador`**
- **situation** — when calls to a remote service need retry, timeout, logging or monitoring added without touching the calling code
- **principle** — The Ambassador Pattern in Java helps offload common functionalities such as monitoring, logging, and routing from a shared resource to a helper service instance, enhancing performance and maintainability in distributed systems.
- **cause** — (none)
- **tags** — API design, Decoupling, Fault tolerance, Proxy, Resilience, Scalability
- **entry point** — `com.iluwatar.ambassador.App`

## Resilience  (6 patterns)
**`circuit-breaker`**
- **situation** — when calls to a failing dependency keep being made and each one costs time before failing anyway
- **principle** — The Circuit Breaker pattern is a critical Java design pattern that helps ensure fault tolerance and resilience in microservices and distributed systems.
- **cause** — (none)
- **tags** — Cloud distributed, Fault tolerance, Microservices, Retry
- **entry point** — `com.iluwatar.circuitbreaker.App`

## Messaging  (4 patterns)
**`data-bus`**
- **situation** — when unrelated components must exchange events without knowing about each other
- **principle** — The Data Bus design pattern aims to provide a centralized communication channel through which various components of a system can exchange data without being directly connected, thus promoting loose coupling and enhancing scalability and maintainability.
- **cause** — (none)
- **tags** — Decoupling, Event-driven, Messaging, Publish/subscribe, Scalability
- **entry point** — `com.iluwatar.databus.App`

## Testing  (4 patterns)
**`arrange-act-assert`**
- **situation** — when a test's setup, action and verification run together and a reader cannot tell which line is being tested
- **principle** — The Arrange/Act/Assert pattern is essential in unit testing in Java.
- **cause** — (none)
- **tags** — Code simplification, Isolation, Testing
- **entry point** — `com.iluwatar.arrangeactassert.Cash`

## Performance optimization  (3 patterns)
**`caching`**
- **situation** — when the same expensive result is requested repeatedly and recomputing it dominates cost
- **principle** — The Java Caching Design Pattern is crucial for performance optimization and resource management.
- **cause** — (none)
- **tags** — Caching, Data access, Performance, Resource management
- **entry point** — `com.iluwatar.caching.App`

## Resource management  (3 patterns)
**`resource-acquisition-is-initialization`**
- **situation** — when a resource must be released exactly once, on every path out of a scope
- **principle** — Ensure efficient Java resource management by tying the resource lifecycle to object lifetime, utilizing the RAII pattern.
- **cause** — (none)
- **tags** — Encapsulation, Memory management, Resource management
- **entry point** — `com.iluwatar.resource.acquisition.is.initialization.App`

## Idiom  (1 patterns)
**`immutable`**
- **situation** — when an object is shared across threads or used as a map key and its state must never change after construction
- **principle** — Ensure that an object's state cannot be changed after it is constructed, making it inherently thread-safe and easier to reason about.
- **cause** — (none)
- **tags** — Immutability, Thread safety, Concurrency, Object composition
- **entry point** — `com.iluwatar.immutable.App`

## Service Discovery  (1 patterns)
**`microservices-self-registration`**
- **situation** — when instances come and go and their addresses cannot be configured in advance
- **principle** — The intent of the Self-Registration pattern is to enable microservices to automatically announce their presence and location to a central registry (like Eureka) upon startup, simplifying service discovery and allowing other services to find and communicate with them without manual configuration or hardcoded addresses.
- **cause** — (none)
- **tags** — Microservices, Self-Registration, Service Discovery, Eureka, Spring Boot, Spring Cloud, Java, Dynamic Configuration, Resilience
- **entry point** — ``

---

## The read

Record below who read this, when, and the verdict.

| who | when | verdict |
|---|---|---|
|  |  |  |
